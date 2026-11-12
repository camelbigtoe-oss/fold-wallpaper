package nz.luke.foldwallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.max
import kotlin.math.min

/**
 * A live wallpaper. On a Galaxy Fold the same wallpaper surface moves between the inner
 * and cover panels when you fold/unfold, and its size changes. We look at the shape of
 * the surface to tell which panel we're on and draw that panel's own image.
 */
class ComicWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ComicEngine()

    inner class ComicEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx: Context get() = this@ComicWallpaperService
        private val prefs = Prefs.get(this@ComicWallpaperService)
        private val thread = HandlerThread("comic-wallpaper").apply { start() }
        private val bg = Handler(thread.looper)

        @Volatile private var width = 0
        @Volatile private var height = 0
        @Volatile private var visible = false

        private val bitmaps = HashMap<Screen, Pair<String, Bitmap?>>()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.3f, 0.3f, 0.3f, 1f) })
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        private val matrix = Matrix()
        private var gesture: GestureDetector? = null
        private var receiverRegistered = false

        private val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (prefs.getBoolean(Prefs.ON_UNLOCK, false)) bg.post { Rotator.advance(ctx) }
            }
        }

        private val tick = object : Runnable {
            override fun run() {
                if (!Rotator.maybeAdvance(ctx)) draw()
                scheduleTick()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
            gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (prefs.getBoolean(Prefs.DOUBLE_TAP, false)) bg.post { Rotator.advance(ctx) }
                    return true
                }
            })
            if (!isPreview) {
                val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    ctx.registerReceiver(unlockReceiver, filter)
                }
                receiverRegistered = true
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            }
            bg.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }

        override fun onTouchEvent(event: MotionEvent) {
            gesture?.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            bg.post { draw() }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            bg.post { draw() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            bg.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                bg.post {
                    if (!Rotator.maybeAdvance(ctx)) draw()
                    scheduleTick()
                }
            } else {
                bg.removeCallbacks(tick)
            }
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            bg.post {
                draw()
                if (visible) scheduleTick()
            }
        }

        private fun scheduleTick() {
            bg.removeCallbacks(tick)
            if (!visible || !prefs.getBoolean(Prefs.TIMER_ON, true)) return
            val due = prefs.getLong(Prefs.LAST_CHANGE, 0) + Prefs.intervalMs(prefs)
            val delay = (due - System.currentTimeMillis()).coerceIn(5_000L, Prefs.intervalMs(prefs))
            bg.postDelayed(tick, delay)
        }

        /** Cover panels are tall and narrow (~21:9); the inner panel is nearly square. */
        private fun currentScreen(w: Int, h: Int): Screen {
            if (w <= 0 || h <= 0) return Screen.MAIN
            val ratio = max(w, h).toFloat() / min(w, h).toFloat()
            return if (ratio >= 1.6f) Screen.COVER else Screen.MAIN
        }

        private fun bitmapFor(screen: Screen, w: Int, h: Int): Bitmap? {
            val uri = Prefs.currentUri(prefs, screen) ?: return null
            val key = "$uri|$w|$h"
            bitmaps[screen]?.let { if (it.first == key) return it.second }
            val bmp = Images.decode(ctx, Uri.parse(uri), w, h)
            bitmaps[screen]?.second?.recycle()
            bitmaps[screen] = key to bmp
            return bmp
        }

        private fun draw() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val screen = currentScreen(w, h)
            val bmp = bitmapFor(screen, w, h)
            val holder = surfaceHolder
            val canvas: Canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                if (bmp != null && !bmp.isRecycled) {
                    if (Prefs.fill(prefs, screen)) {
                        drawScaled(canvas, bmp, w, h, fill = true, p = paint)
                    } else {
                        // Dimmed, cropped copy behind; whole image on top.
                        drawScaled(canvas, bmp, w, h, fill = true, p = dimPaint)
                        drawScaled(canvas, bmp, w, h, fill = false, p = paint)
                    }
                } else {
                    textPaint.textSize = min(w, h) / 18f
                    canvas.drawText("Open Fold Wallpaper", w / 2f, h / 2f, textPaint)
                    canvas.drawText("and pick your comic folder", w / 2f, h / 2f + textPaint.textSize * 1.4f, textPaint)
                }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }

        private fun drawScaled(canvas: Canvas, b: Bitmap, w: Int, h: Int, fill: Boolean, p: Paint) {
            val sx = w.toFloat() / b.width
            val sy = h.toFloat() / b.height
            val s = if (fill) max(sx, sy) else min(sx, sy)
            matrix.setScale(s, s)
            matrix.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
            canvas.drawBitmap(b, matrix, p)
        }
    }
}
