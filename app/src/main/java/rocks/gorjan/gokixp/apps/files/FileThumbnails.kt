package rocks.gorjan.gokixp.apps.files

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A small picture of what is actually in a file, for the rows of the Files app.
 *
 * A folder of photographs drawn as forty identical page glyphs tells the reader nothing
 * they did not already know from the extension; the one thing they came to the folder for
 * is which picture is which, and only the picture itself can say. So the three kinds of
 * file that have something to show - a picture, a clip, a document - show it, and
 * everything else keeps the glyph for its type, because a spreadsheet has no first frame.
 *
 * Everything here is asked for by the row that will draw it and answered on the main
 * thread. The list decides *when* to ask: it only asks for rows near the window, so
 * opening a folder of two thousand photographs decodes the dozen the user can see rather
 * than all of them. See the pending queue in [MetroFilesApp].
 *
 * Held in a cache keyed by the file's path, size and date - and by the box it was asked
 * for at, since a row's mark and a cell of the photo grid want the same picture at very
 * different sizes - so scrolling back up is free and a file replaced under the same name
 * is drawn again rather than remembered wrong.
 * Static, like the app's clipboard: the pictures in a folder are a fact about the phone,
 * and leaving the app and coming back should not mean decoding all of them a second time.
 */
object FileThumbnails {

    /** The kinds of file that have a picture of themselves in them. */
    enum class Kind { IMAGE, VIDEO, DOCUMENT }

    /**
     * What this file can show, or null if the answer is a glyph.
     *
     * By extension rather than by looking inside, because this is asked once per row while
     * a listing is being built and opening every file in a folder to sniff it is a folder's
     * worth of reads to draw a list. A name that lies about its contents gets its thumbnail
     * attempted and quietly refused, which is the same place it would have ended up.
     */
    fun kindOf(file: File): Kind? {
        if (file.isDirectory) return null
        return when (file.extension.lowercase(Locale.getDefault())) {
            in IMAGE_TYPES -> Kind.IMAGE
            in VIDEO_TYPES -> Kind.VIDEO
            in DOCUMENT_TYPES -> Kind.DOCUMENT
            else -> null
        }
    }

    /**
     * The picture of [file] if one is already held, and null if reading it would be work.
     *
     * For a list being rebuilt rather than opened - a sort, a rename, a refresh - so a row
     * that already had its picture can put it back as it is drawn, instead of showing a
     * glyph until the queue comes round to it again.
     */
    fun held(file: File, sizePx: Int): Bitmap? = cache.get(keyOf(file, sizePx))

    /**
     * Hands [onReady] a picture of [file] at roughly [sizePx] square.
     *
     * The callback always runs on the main thread, and runs *straight away* when the
     * picture is already held - which is what lets a row that is redrawn for some other
     * reason put its thumbnail back without a flicker. It may hand back null: plenty of
     * things named .jpg are not one, and a file can be deleted between the listing and the
     * decode.
     *
     * Two rows asking for the same file at once are answered by one decode. That is not
     * the scrolling case - a folder holds each name once - it is the case where the list is
     * rebuilt while a decode is in flight, which a sort or a refresh can easily do.
     */
    fun load(file: File, kind: Kind, sizePx: Int, onReady: (Bitmap?) -> Unit) {
        val box = bucket(sizePx)
        val key = keyOf(file, sizePx)
        cache.get(key)?.let {
            onReady(it)
            return
        }
        if (key in failed) {
            onReady(null)
            return
        }
        synchronized(waiting) {
            val already = waiting[key]
            if (already != null) {
                already += onReady
                return
            }
            waiting[key] = mutableListOf(onReady)
        }
        executor.execute {
            val bitmap = decode(file, kind, box)
            if (bitmap == null) failed.add(key) else cache.put(key, bitmap)
            val asked = synchronized(waiting) { waiting.remove(key) }.orEmpty()
            main.post { for (callback in asked) callback(bitmap) }
        }
    }

    /**
     * What a held picture is a picture of.
     *
     * The path is not enough on its own: a photograph edited in place, or a name reused by
     * a download, is a different picture at the same address. Size and date are what every
     * filesystem will say cheaply about that, and between them they catch it.
     */
    private fun keyOf(file: File, sizePx: Int): String =
        "${file.absolutePath}|${file.lastModified()}|${file.length()}|${bucket(sizePx)}"

    /**
     * The size a request is actually served at: the next rung of [BOXES] at or above it.
     *
     * There are several places in the app that want a picture of the same file at slightly
     * different sizes - a row's mark, a cell in an album's mosaic, a photograph in the grid
     * inside one - and a cache keyed on the exact number asked for would hold a copy of
     * every photograph on the phone for each of them, differing by a few pixels. The rungs
     * collapse all of that onto a handful of sizes.
     *
     * They step by half rather than by double, and that is not fussiness: a thumbnail is a
     * square of pixels, so a rung twice as wide is a bitmap four times the size, and the
     * cache is a fixed slice of the heap. Rounding a 270px cell up to 512 rather than to
     * 384 would halve how many photographs fit in it - which is felt as a grid that
     * re-decodes everything each time it is scrolled back up.
     *
     * The round-up is also what pays for the crop. These thumbnailers fit the whole picture
     * inside the box they are given while the grid crops a square out of the middle, so an
     * ordinary 4:3 photograph comes back only three-quarters of the box tall - and asking
     * for the next rung up is what leaves that three-quarters still larger than the cell.
     */
    private fun bucket(sizePx: Int): Int = BOXES.firstOrNull { it >= sizePx } ?: BOXES.last()

    private fun decode(file: File, kind: Kind, sizePx: Int): Bitmap? = try {
        val box = Size(sizePx, sizePx)
        when (kind) {
            // The platform's own thumbnailers. Worth using rather than BitmapFactory for
            // the two things they do that it does not: turning a photograph the right way
            // up from its EXIF, and having any opinion at all about what frame of a video
            // is the one to show.
            Kind.IMAGE -> ThumbnailUtils.createImageThumbnail(file, box, null)
            Kind.VIDEO -> ThumbnailUtils.createVideoThumbnail(file, box, null)
            Kind.DOCUMENT -> firstPageOf(file, sizePx)
        }
    } catch (e: Exception) {
        Log.w(TAG, "No thumbnail for ${file.name}", e)
        null
    } catch (e: OutOfMemoryError) {
        // A picture large enough to do this is one this phone was never going to draw.
        Log.w(TAG, "No room to decode ${file.name}")
        null
    }

    /**
     * The top of a PDF's first page, on white.
     *
     * The top rather than the middle, and square: a page is far taller than it is wide, so
     * a thumbnail of the whole of it is a grey rectangle with a suggestion of type in it,
     * while the top inch is the title - which is the only part of a document anybody
     * recognises at this size. Drawn onto white because a PDF page paints only its ink,
     * and unpainted paper is transparent.
     *
     * One at a time. Each renderer is its own object, but they meet in one native library
     * underneath, and rendering two pages on two threads is not something to find out
     * about in a crash log.
     */
    private fun firstPageOf(file: File, sizePx: Int): Bitmap? = synchronized(pdfLock) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { handle ->
            PdfRenderer(handle).use { pdf ->
                if (pdf.pageCount < 1) return null
                pdf.openPage(0).use { page ->
                    val across = page.width.coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val scale = sizePx.toFloat() / across
                    page.render(
                        bitmap,
                        null,
                        Matrix().apply { setScale(scale, scale) },
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    bitmap
                }
            }
        }
    }

    /** Two at a time: enough to keep a scroll fed, few enough to leave the phone alone. */
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** A sixteenth of the heap. Losing one of these only costs a decode. */
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** What has already been tried and refused, so a scroll past it does not try again. */
    private val failed = Collections.synchronizedSet(mutableSetOf<String>())

    /** Who is waiting on a decode that is already running. See [load]. */
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private val pdfLock = Any()

    private val IMAGE_TYPES = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "ico", "tif", "tiff", "avif"
    )
    private val VIDEO_TYPES = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "3gp", "mpg", "mpeg"
    )
    private val DOCUMENT_TYPES = setOf("pdf")

    /**
     * Every size a thumbnail is ever decoded at. See [bucket].
     *
     * The floor is a cell of an album's mosaic on a plain screen; the ceiling is a cell of
     * the photo grid on a dense one, which is as large as anything in this app draws. Above
     * that the file is being *looked* at rather than listed, and the phone opens it
     * properly in whatever program does that.
     */
    private val BOXES = intArrayOf(64, 96, 128, 192, 256, 384, 512)

    private const val TAG = "MetroFileThumbs"
}
