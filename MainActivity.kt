package nz.luke.foldwallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val green = Color.parseColor("#8BC34A")
    private val bgColor = Color.parseColor("#1B1C18")
    private val cardColor = Color.parseColor("#2E302A")

    private lateinit var prefs: SharedPreferences
    private lateinit var mainThumb: ImageView
    private lateinit var coverThumb: ImageView
    private lateinit var mainFolderText: TextView
    private lateinit var coverFolderText: TextView
    private lateinit var statusText: TextView
    private lateinit var setButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        window.statusBarColor = green
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.CUR_MAIN || key == Prefs.CUR_COVER || key == Prefs.LIST_VERSION) refresh()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(18).toFloat() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun label(text: String, size: Float = 16f, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(text: String, filled: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (filled) bgColor else green)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (filled) setColor(green) else { setColor(Color.TRANSPARENT); setStroke(dp(1), green) }
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun check(text: String, key: String, def: Boolean, onChange: (() -> Unit)? = null) =
        CheckBox(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(green)
            isChecked = prefs.getBoolean(key, def)
            setPadding(dp(8), dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean(key, v).apply()
                onChange?.invoke()
            }
        }

    private fun thumbColumn(title: String, weight: Float): Pair<LinearLayout, ImageView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                .apply { marginStart = dp(4); marginEnd = dp(4) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        }
        col.addView(img)
        col.addView(label(title, 14f).apply { setPadding(0, dp(6), 0, 0) })
        return col to img
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        root.addView(label("Fold Wallpaper", 24f, color = Color.parseColor("#1B1C18")).apply {
            setBackgroundColor(green)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }
        root.addView(ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // Status + set wallpaper
        content.addView(card().apply {
            statusText = label("", 15f)
            addView(statusText)
            setButton = button("Set as wallpaper", filled = true) { applyWallpaper() }
            addView(setButton)
        })

        // Current images
        content.addView(card().apply {
            addView(label("Showing now", 18f, bold = true))
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            val (mCol, mImg) = thumbColumn("Main (inner) screen", 1.4f)
            val (cCol, cImg) = thumbColumn("Cover screen", 0.6f)
            mainThumb = mImg; coverThumb = cImg
            row.addView(mCol); row.addView(cCol)
            addView(row)
            addView(button("Change now") {
                if (ImageLibrary.effective(this@MainActivity, Screen.MAIN).isEmpty()) {
                    toast("Pick your comic folder first")
                } else Thread { Rotator.advance(this@MainActivity) }.start()
            })
        })

        // Folders
        content.addView(card().apply {
            addView(label("Albums", 18f, bold = true))
            mainFolderText = label("", 14f, color = Color.LTGRAY).apply { setPadding(0, dp(6), 0, 0) }
            addView(mainFolderText)
            addView(button("Choose comic folder") { pickFolder(REQ_MAIN) })
            coverFolderText = label("", 14f, color = Color.LTGRAY).apply { setPadding(0, dp(14), 0, 0) }
            addView(coverFolderText)
            addView(button("Choose separate cover screen folder (optional)") { pickFolder(REQ_COVER) })
            addView(button("Cover uses the main folder") {
                prefs.edit().remove(Prefs.COVER_FOLDER).apply()
                ImageLibrary.save(this@MainActivity, Screen.COVER, emptyList())
                Thread { Rotator.advance(this@MainActivity) }.start()
            })
            addView(button("Rescan folders for new images") { rescanAll() })
        })

        // Timer
        content.addView(card().apply {
            addView(label("Rules", 18f, bold = true))
            addView(check("Change wallpaper every", Prefs.TIMER_ON, true))
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(40), 0, 0, dp(6))
            }
            val num = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(prefs.getInt(Prefs.INTERVAL_VALUE, 15).toString())
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val v = s?.toString()?.toIntOrNull() ?: return
                        if (v >= 1) prefs.edit().putInt(Prefs.INTERVAL_VALUE, v).apply()
                    }
                })
            }
            val unit = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("minutes", "hours")
                )
                setSelection(prefs.getInt(Prefs.INTERVAL_UNIT, 0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        prefs.edit().putInt(Prefs.INTERVAL_UNIT, pos).apply()
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            row.addView(num); row.addView(unit)
            addView(row)
            addView(check("Different image on each screen", Prefs.DIFFERENT, true))
            addView(check("Shuffle (off = go through in order)", Prefs.SHUFFLE, true))
            addView(check("Change wallpaper on lockscreen unlock", Prefs.ON_UNLOCK, false))
            addView(check("Change wallpaper when double-tapping home screen", Prefs.DOUBLE_TAP, false))
        })

        // Fit / fill
        content.addView(card().apply {
            addView(label("Fit", 18f, bold = true))
            addView(check("Main screen: crop to fill (off = show whole page)", Prefs.MAIN_FILL, true))
            addView(check("Cover screen: crop to fill (off = show whole page)", Prefs.COVER_FILL, true))
        })

        return root
    }

    // ---------- Actions ----------

    private fun refresh() {
        val active = WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
        statusText.text = if (active) "✓ Fold Wallpaper is your current wallpaper."
        else "Not active yet. Pick a folder, then tap Set as wallpaper."
        setButton.text = if (active) "Re-apply wallpaper" else "Set as wallpaper"

        val mainCount = ImageLibrary.images(this, Screen.MAIN).size
        val coverCount = ImageLibrary.images(this, Screen.COVER).size
        mainFolderText.text = prefs.getString(Prefs.MAIN_FOLDER, null)?.let {
            "Comic folder: ${folderName(it)} ($mainCount images)"
        } ?: "No comic folder chosen yet"
        coverFolderText.text = prefs.getString(Prefs.COVER_FOLDER, null)?.let {
            "Cover folder: ${folderName(it)} ($coverCount images)"
        } ?: "Cover screen uses the comic folder"

        loadThumb(mainThumb, Prefs.currentUri(prefs, Screen.MAIN))
        loadThumb(coverThumb, Prefs.currentUri(prefs, Screen.COVER))
    }

    private fun loadThumb(view: ImageView, uri: String?) {
        if (uri == null) { view.setImageDrawable(null); return }
        Thread {
            val bmp = Images.decode(this, Uri.parse(uri), dp(160), dp(220))
            runOnUiThread { view.setImageBitmap(bmp) }
        }.start()
    }

    private fun folderName(tree: String): String {
        val id = Uri.decode(tree.substringAfterLast("/tree/"))
        return id.substringAfter(":").ifBlank { id }.ifBlank { "folder" }
    }

    private fun applyWallpaper() {
        if (ImageLibrary.effective(this, Screen.MAIN).isEmpty()) {
            toast("Pick your comic folder first")
            return
        }
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, ComicWallpaperService::class.java)
            ))
        } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            toast("Choose \"Fold Wallpaper\" from the list")
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFolder(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val screen = if (requestCode == REQ_COVER) Screen.COVER else Screen.MAIN
        prefs.edit().putString(if (screen == Screen.MAIN) Prefs.MAIN_FOLDER else Prefs.COVER_FOLDER, uri.toString()).apply()
        scan(screen, uri, advanceAfter = true)
    }

    private fun rescanAll() {
        prefs.getString(Prefs.MAIN_FOLDER, null)?.let { scan(Screen.MAIN, Uri.parse(it), false) }
        prefs.getString(Prefs.COVER_FOLDER, null)?.let { scan(Screen.COVER, Uri.parse(it), false) }
    }

    private fun scan(screen: Screen, tree: Uri, advanceAfter: Boolean) {
        toast("Scanning folder…")
        Thread {
            val list = ImageLibrary.scan(this, tree)
            ImageLibrary.save(this, screen, list)
            if (advanceAfter && list.isNotEmpty()) Rotator.advance(this)
            runOnUiThread {
                toast(if (list.isEmpty()) "No images found in that folder" else "Found ${list.size} images")
                refresh()
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_MAIN = 1
        private const val REQ_COVER = 2
    }
}
