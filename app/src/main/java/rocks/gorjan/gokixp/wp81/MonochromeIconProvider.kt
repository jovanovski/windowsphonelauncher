package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import rocks.gorjan.gokixp.NotificationListenerService

/**
 * Resolves the glyph a third-party app's Start tile should show.
 *
 * Windows Phone tiles are flat: a single white glyph on an accent fill. Android apps
 * don't ship WP tile art, so we look for the closest thing they do ship, in order:
 *
 *  1. **The themed-icon (monochrome) layer.** Android 13's Material You icon - artwork
 *     the developer authored specifically to be drawn in one colour. This is the right
 *     answer when it exists, and it needs no notification and no permission.
 *  2. **The notification small icon.** Android requires these to be flat silhouettes,
 *     so they suit a tile well, but they only exist once an app has actually posted a
 *     notification. Harvested opportunistically, so this fills in over time.
 *  3. **The full-colour launcher icon**, unmodified. Always recognisable - and honest
 *     to the platform: real WP8.1 third-party tiles used whatever art the developer
 *     supplied, so a Start screen mixing flat glyphs with full-colour logos is what
 *     WP8.1 actually looked like. Deliberately *not* an auto-generated silhouette,
 *     which turns detailed icons into unreadable blobs.
 */
class MonochromeIconProvider(private val context: Context) {

    /** What we found, so callers can decide whether to tint and how to size. */
    sealed class Glyph {
        /**
         * Flat artwork; caller tints it (normally white on the accent fill).
         *
         * [contentRatio] is how much of its own canvas the artwork actually covers, so
         * the caller can scale every glyph to the same optical size. See [measureContentRatio].
         */
        data class Monochrome(val drawable: Drawable, val contentRatio: Float = 1f) : Glyph()

        /**
         * The app's real icon; must be drawn as-is, never tinted.
         *
         * [fromPack] marks the ones an icon pack answered for rather than the app itself.
         * The picture is drawn the same either way; what it says is that this row's mark
         * was chosen for it, so a surface that stands its marks on an accent square can go
         * on doing that instead of dropping the square the moment a pack is turned on.
         * See AppListView's drawGlyph.
         */
        data class FullColor(
            val drawable: Drawable,
            val contentRatio: Float = 1f,
            val fromPack: Boolean = false,
        ) : Glyph()
    }

    /**
     * The icon pack's answer for a package, when the user has asked for one to reach the
     * tiles as well as the app list.
     *
     * A seam rather than a pack of its own, for the same reason the fallback is handed in:
     * which pack is on, and whether it was invited this far, are the shell's business.
     * Unset - or set and silent about this app - and the three-step resolution below is
     * exactly what it was.
     *
     * It goes first because it is the one answer here the user chose deliberately. The
     * three below are all inferences about what an app's tile should look like; a pack is
     * an instruction, and an instruction that loses to an inference is not one.
     */
    @Volatile
    var packGlyph: ((String) -> Drawable?)? = null

    /**
     * Whether the Start tiles are included in what [packGlyph] answers for.
     *
     * The app list always is, and the wall only when the user has said so - which is a
     * setting rather than a preference of this class's because the two surfaces want
     * different things. A list row is a name with a mark beside it and a pack's artwork
     * suits it; the wall is a grid of accent squares that a full-colour icon breaks the
     * look of. See [glyphFor]'s `isTile`, and WP81Settings.getWP81IconPackOnTiles.
     */
    @Volatile
    var packOnTiles: Boolean = false

    /**
     * @param isTile whether the caller is drawing a Start tile, as opposed to a row in the
     *   app list. The only thing it changes is whether the icon pack gets a turn.
     */
    fun glyphFor(packageName: String, fallback: Drawable?, isTile: Boolean = false): Glyph? {
        if (!isTile || packOnTiles) packGlyph?.invoke(packageName)?.let {
            // Full colour, so it is never tinted: a pack's artwork is the picture, not a
            // silhouette of one, and washing it in white would leave a white square.
            return Glyph.FullColor(it, ratioFor("pack:$packageName", it), fromPack = true)
        }
        monochromeLayer(packageName)?.let {
            return Glyph.Monochrome(it, ratioFor("mono:$packageName", it))
        }
        NotificationListenerService.getSmallIcon(context, packageName)?.let {
            return Glyph.Monochrome(it, ratioFor("notif:$packageName", it))
        }
        return fallback?.let { Glyph.FullColor(it, ratioFor("full:$packageName", it)) }
    }

    private val ratioCache = mutableMapOf<String, Float>()
    private val inkCache = mutableMapOf<String, RectF?>()

    /**
     * Guards the two caches, which are asked from more than one thread: the app list
     * resolves its rows' artwork on a worker, so that typing in its search field is not
     * stalled behind a package manager call, while the tiles ask for theirs on the main
     * thread as they are laid out.
     *
     * Held around the maps and nothing else. Measuring is the expensive half, and doing it
     * inside the lock would put the main thread to sleep behind a background raster - two
     * threads measuring the same artwork at once is a wasted measure, not a wrong answer.
     */
    private val lock = Any()

    /**
     * Forgets cached measurements for a package.
     *
     * Its artwork has changed, so the proportion of its canvas the content covers has to be
     * measured again - otherwise a new icon is drawn scaled for the old one.
     */
    fun invalidate(packageName: String) {
        synchronized(lock) {
            ratioCache.keys.removeAll { it.endsWith(":$packageName") }
            inkCache.keys.removeAll { it.endsWith(":$packageName") }
        }
    }

    /**
     * Forgets every cached measurement.
     *
     * For when the whole set of hand-picked icons has been swapped out - arriving on the
     * phone shell reloads the mappings for its own theme, and every "custom:" measurement
     * taken under the previous theme was of a different picture.
     */
    fun invalidateAll() {
        synchronized(lock) {
            ratioCache.clear()
            inkCache.clear()
        }
    }

    /**
     * Cached [measureContentRatio]; measuring means rasterising, so do it once per app.
     *
     * Answered out of the ink box under the same key, which is the only thing that
     * rasterising is for - so artwork asked for both is drawn once rather than twice.
     */
    fun ratioFor(key: String, drawable: Drawable): Float {
        synchronized(lock) { ratioCache[key] }?.let { return it }
        val ratio = ratioOf(inkFor(key, drawable), drawable)
        synchronized(lock) { ratioCache[key] = ratio }
        return ratio
    }

    /**
     * Cached [measureInk]. Null is a real answer - artwork that drew nothing - and is
     * cached as one, so a blank drawable is not rasterised again on every bind.
     */
    fun inkFor(key: String, drawable: Drawable): RectF? {
        synchronized(lock) { if (inkCache.containsKey(key)) return inkCache[key] }
        val ink = measureInk(drawable)
        synchronized(lock) { inkCache[key] = ink }
        return ink
    }

    /**
     * Puts a mark on an ImageView at the same optical size as every other mark.
     *
     * Nothing about the source can be taken on trust. A themed layer keeps the
     * adaptive-icon safe zone, a notification silhouette fills its bounds, this shell's own
     * glyphs cover about half of theirs - so drawing them each at the size of their own
     * canvas gives a column where one app's mark is twice the next one's. What is placed
     * instead is the *ink*: its longer side scaled to [glyphPx] and its centre put at the
     * centre of a [boxPx] square, whatever padding it arrived wrapped in.
     *
     * By matrix rather than by padding, because a glyph covering a seventh of its canvas
     * would need a canvas seven times the box to show at the right size, and no amount of
     * padding can give it one. What hangs over the edge is that artwork's own margin, and
     * the box clips it.
     *
     * [key] is what the measurement is cached under and should be the same string wherever
     * the same artwork is placed, so the app list, the Action Center and the status bar
     * share one rasterisation of each app's icon rather than paying for three.
     */
    fun placeInk(
        view: android.widget.ImageView,
        drawable: Drawable,
        key: String,
        boxPx: Int,
        glyphPx: Int
    ) {
        val ink = inkFor(key, drawable)
        val canvasW = drawable.intrinsicWidth.toFloat()
        val canvasH = drawable.intrinsicHeight.toFloat()
        // Artwork that drew nothing, or will not say how large it is: an ImageView ignores
        // the matrix for a drawable with no intrinsic size, so the plain fit is the only
        // honest answer.
        if (ink == null || canvasW <= 0f || canvasH <= 0f) {
            view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            return
        }
        val inkW = ink.width() * canvasW
        val inkH = ink.height() * canvasH
        val scale = glyphPx / maxOf(inkW, inkH)
        val centre = boxPx / 2f
        view.scaleType = android.widget.ImageView.ScaleType.MATRIX
        view.imageMatrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                centre - scale * (ink.left * canvasW + inkW / 2f),
                centre - scale * (ink.top * canvasH + inkH / 2f)
            )
        }
    }

    /**
     * The Android 13+ themed-icon layer, or null when the platform is older or the app
     * ships no monochrome artwork. [AdaptiveIconDrawable.getMonochrome] is API 33; the
     * app's minSdk is 29, so this is a real branch, not a formality.
     */
    private fun monochromeLayer(packageName: String): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            (icon as? AdaptiveIconDrawable)?.monochrome
        } catch (e: Exception) {
            Log.d(TAG, "No monochrome layer for $packageName: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WP81Icons"

        /**
         * The largest canvas the ink is measured on.
         *
         * Artwork is measured at its own intrinsic size wherever that fits under this,
         * because some of it does not survive being measured anywhere else: a themed layer
         * that comes back as an InsetDrawable carries insets in *pixels*, chosen for the
         * canvas the artist drew on, and rasterising it into a smaller box eats the whole
         * picture. Google Drive's measured as a blank square for exactly that reason.
         * The cap is only there to keep a 1536px icon from asking for a 9MB bitmap.
         */
        private const val PROBE_MAX_PX = 512

        /** The canvas for artwork that will not say how large it is. */
        private const val PROBE_PX = 72

        /**
         * The least of its canvas a mark may be said to cover.
         *
         * A floor, not a rejection. This used to hand back 1f - "fills its canvas" - for
         * anything sparser, which is the opposite of the truth and had callers *shrink*
         * the very glyphs that needed blowing up: Reddit's themed layer covers 14% of what
         * it is drawn on, and came out as a speck.
         */
        private const val MIN_RATIO = 0.2f

        /**
         * How much of its own canvas a drawable's visible content covers, from 0 to 1,
         * measured on the longer edge - which is what FIT_CENTER fits to.
         *
         * Source artwork pads itself wildly differently: an adaptive icon's monochrome
         * layer sits inside the central 72dp safe zone of a 108dp canvas and so covers
         * about two thirds, while a notification small icon or a flat app logo fills its
         * bounds. Drawn at a single fixed size, those land at visibly different optical
         * sizes on neighbouring tiles.
         */
        fun measureContentRatio(drawable: Drawable): Float =
            ratioOf(measureInk(drawable), drawable)

        /** [measureContentRatio] once the ink box has already been measured. */
        private fun ratioOf(ink: RectF?, drawable: Drawable): Float {
            if (ink == null) return 1f
            val (w, h) = canvasOf(drawable)
            val ratio = maxOf(ink.width() * w, ink.height() * h) / maxOf(w, h)
            return ratio.coerceIn(MIN_RATIO, 1f)
        }

        /**
         * Where the visible ink actually sits inside a drawable's own canvas, as fractions
         * of it, or null when the artwork drew nothing at all.
         *
         * The whole box rather than one number, so a caller can put the *ink* where it
         * wants it at the size it wants: artwork is padded differently by every source and
         * is not always centred in what padding it has, and neither of those can be
         * corrected for by scaling alone. Rasterises, so cache it - see [inkFor].
         */
        fun measureInk(drawable: Drawable): RectF? {
            return try {
                val (iw, ih) = canvasOf(drawable)
                val scale = minOf(1f, PROBE_MAX_PX / maxOf(iw, ih))
                val boxW = (iw * scale).toInt().coerceAtLeast(1)
                val boxH = (ih * scale).toInt().coerceAtLeast(1)

                val bmp = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, boxW, boxH)
                drawable.draw(canvas)

                val pixels = IntArray(boxW * boxH)
                bmp.getPixels(pixels, 0, boxW, 0, 0, boxW, boxH)
                bmp.recycle()

                var left = boxW; var top = boxH; var right = -1; var bottom = -1
                for (y in 0 until boxH) {
                    for (x in 0 until boxW) {
                        // 8 of 255 ignores anti-aliased fringes and near-transparent glows.
                        if ((pixels[y * boxW + x] ushr 24) > 8) {
                            if (x < left) left = x
                            if (x > right) right = x
                            if (y < top) top = y
                            if (y > bottom) bottom = y
                        }
                    }
                }
                if (right < left || bottom < top) return null

                RectF(
                    left / boxW.toFloat(),
                    top / boxH.toFloat(),
                    (right + 1) / boxW.toFloat(),
                    (bottom + 1) / boxH.toFloat()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not measure glyph content", e)
                null
            }
        }

        /**
         * The canvas a drawable draws on, which is its intrinsic size where it has one.
         *
         * Aspect is kept rather than squared off: the artwork is drawn through a transform
         * that preserves it, and measuring inside a forced square stretched any non-square
         * icon before its box was taken - which is how one app's glyph came out visibly
         * bigger than its neighbours'.
         */
        fun canvasOf(drawable: Drawable): Pair<Float, Float> = Pair(
            (drawable.intrinsicWidth.takeIf { it > 0 } ?: PROBE_PX).toFloat(),
            (drawable.intrinsicHeight.takeIf { it > 0 } ?: PROBE_PX).toFloat()
        )
    }
}
