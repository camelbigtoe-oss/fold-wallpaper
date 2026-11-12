package nz.luke.foldwallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import kotlin.random.Random

/** Which physical screen we are drawing for. */
enum class Screen { MAIN, COVER }

object Prefs {
    const val NAME = "fold_wallpaper"

    const val MAIN_FOLDER = "main_folder"
    const val COVER_FOLDER = "cover_folder"
    const val TIMER_ON = "timer_on"
    const val INTERVAL_VALUE = "interval_value"
    const val INTERVAL_UNIT = "interval_unit" // 0 = minutes, 1 = hours
    const val ON_UNLOCK = "on_unlock"
    const val DOUBLE_TAP = "double_tap"
    const val DIFFERENT = "different"
    const val SHUFFLE = "shuffle"
    const val MAIN_FILL = "main_fill"
    const val COVER_FILL = "cover_fill"

    const val CUR_MAIN = "cur_main"
    const val CUR_COVER = "cur_cover"
    const val IDX_MAIN = "idx_main"
    const val IDX_COVER = "idx_cover"
    const val LAST_CHANGE = "last_change"
    const val LIST_VERSION = "list_version"

    fun get(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun intervalMs(p: SharedPreferences): Long {
        val v = p.getInt(INTERVAL_VALUE, 15).coerceAtLeast(1).toLong()
        return if (p.getInt(INTERVAL_UNIT, 0) == 1) v * 3_600_000L else v * 60_000L
    }

    fun currentUri(p: SharedPreferences, s: Screen): String? =
        p.getString(if (s == Screen.MAIN) CUR_MAIN else CUR_COVER, null)

    fun fill(p: SharedPreferences, s: Screen): Boolean =
        p.getBoolean(if (s == Screen.MAIN) MAIN_FILL else COVER_FILL, true)
}

/** Scans folders picked with the system folder picker and caches the image list on disk. */
object ImageLibrary {
    private val cache = HashMap<Screen, Pair<Long, List<String>>>()

    private fun listFile(c: Context, s: Screen) =
        File(c.applicationContext.filesDir, "${s.name.lowercase()}_images.txt")

    @Synchronized
    fun images(c: Context, s: Screen): List<String> {
        val f = listFile(c, s)
        if (!f.exists()) return emptyList()
        val stamp = f.lastModified() xor f.length()
        cache[s]?.let { if (it.first == stamp) return it.second }
        val list = f.readLines().filter { it.isNotBlank() }
        cache[s] = stamp to list
        return list
    }

    /** The cover screen falls back to the main folder if it has no folder of its own. */
    fun effective(c: Context, s: Screen): List<String> =
        if (s == Screen.COVER) images(c, Screen.COVER).ifEmpty { images(c, Screen.MAIN) }
        else images(c, Screen.MAIN)

    fun usesSharedList(c: Context) = images(c, Screen.COVER).isEmpty()

    @Synchronized
    fun save(c: Context, s: Screen, list: List<String>) {
        val f = listFile(c, s)
        if (list.isEmpty()) f.delete() else f.writeText(list.joinToString("\n"))
        cache.remove(s)
        val p = Prefs.get(c)
        p.edit().putLong(Prefs.LIST_VERSION, System.currentTimeMillis()).apply()
    }

    /** Recursively lists every image inside a tree Uri (sub-folders included). */
    fun scan(c: Context, tree: Uri): List<String> {
        data class Item(val name: String, val uri: String)
        val out = ArrayList<Item>()
        val stack = ArrayDeque<Pair<String, String>>() // docId, path prefix for sorting
        stack.add(DocumentsContract.getTreeDocumentId(tree) to "")
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        while (stack.isNotEmpty()) {
            val (id, prefix) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            try {
                c.contentResolver.query(children, cols, null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val docId = cur.getString(0) ?: continue
                        val mime = cur.getString(1) ?: ""
                        val name = cur.getString(2) ?: docId
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.add(docId to "$prefix$name/")
                        } else if (mime.startsWith("image/")) {
                            out.add(
                                Item(
                                    "$prefix$name",
                                    DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Unreadable sub-folder: skip it.
            }
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out.map { it.uri }
    }
}

/** Picks the next image(s) and records them in preferences. Engines redraw on change. */
object Rotator {
    @Synchronized
    fun advance(c: Context) {
        val p = Prefs.get(c)
        val shuffle = p.getBoolean(Prefs.SHUFFLE, true)
        val different = p.getBoolean(Prefs.DIFFERENT, true)
        val mainList = ImageLibrary.effective(c, Screen.MAIN)
        val coverList = ImageLibrary.effective(c, Screen.COVER)
        val shared = ImageLibrary.usesSharedList(c)
        val ed = p.edit()

        var mainIdx = -1
        if (mainList.isNotEmpty()) {
            val prev = p.getInt(Prefs.IDX_MAIN, -1)
            mainIdx = if (!shuffle && shared && different) {
                // Sequential through one folder: main gets page N, cover page N+1, then jump 2.
                if (prev < 0) 0 else (prev + 2) % mainList.size
            } else next(prev, mainList.size, shuffle)
            ed.putInt(Prefs.IDX_MAIN, mainIdx).putString(Prefs.CUR_MAIN, mainList[mainIdx])
        }

        if (coverList.isNotEmpty()) {
            val coverIdx: Int = when {
                !different && shared && mainIdx >= 0 -> mainIdx
                shared && mainIdx >= 0 && !shuffle -> (mainIdx + 1) % coverList.size
                else -> {
                    var i = next(p.getInt(Prefs.IDX_COVER, -1), coverList.size, shuffle)
                    if (shared && coverList.size > 1 && i == mainIdx) {
                        i = if (shuffle) pickOther(coverList.size, mainIdx) else (i + 1) % coverList.size
                    }
                    i
                }
            }
            ed.putInt(Prefs.IDX_COVER, coverIdx).putString(Prefs.CUR_COVER, coverList[coverIdx])
        }

        ed.putLong(Prefs.LAST_CHANGE, System.currentTimeMillis()).apply()
    }

    /** Advances if the timer is on and the interval has passed (or nothing has been chosen yet). */
    fun maybeAdvance(c: Context): Boolean {
        val p = Prefs.get(c)
        val nothingYet = p.getString(Prefs.CUR_MAIN, null) == null
        val due = p.getBoolean(Prefs.TIMER_ON, true) &&
                System.currentTimeMillis() - p.getLong(Prefs.LAST_CHANGE, 0) >= Prefs.intervalMs(p)
        if (nothingYet || due) {
            if (ImageLibrary.effective(c, Screen.MAIN).isEmpty()) return false
            advance(c)
            return true
        }
        return false
    }

    private fun next(prev: Int, size: Int, shuffle: Boolean): Int =
        if (shuffle) pickOther(size, prev) else (prev + 1).mod(size)

    private fun pickOther(size: Int, avoid: Int): Int {
        if (size <= 1) return 0
        var r: Int
        do { r = Random.nextInt(size) } while (r == avoid)
        return r
    }
}

object Images {
    /** Decodes an image downsampled so it still covers reqW x reqH. Returns null on failure. */
    fun decode(c: Context, uri: Uri, reqW: Int, reqH: Int): Bitmap? = try {
        val src = ImageDecoder.createSource(c.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            val w = info.size.width
            val h = info.size.height
            while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (_: Throwable) {
        null
    }
}
