package rocks.gorjan.gokixp.wp81

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * What a folder tile shows instead of a folder icon: the apps inside it.
 *
 * A folder drawn as a folder says only that it is one. The same tile filled with the
 * icons inside it says what is in it, which is the question actually being asked of a
 * tile on Start - and it is what WP8.1's own Live Folders did.
 *
 * The mini tiles are **always square**, each half the folder tile's shorter side: a 1x1
 * folder previews at 0.5x0.5, a 2x2 at 1x1. How many rows and columns that comes to
 * follows from the folder's own shape - two by two on a square tile, four by two on a wide
 * one, eight by two on a full-width strip, two by four on a tall one - so the squares fill
 * the tile at every size instead of stretching to fit it.
 *
 * They fill it edge to edge, with nothing between them but a hairline. The tile is not a
 * little wall of little tiles - it is one tile scored into squares, exactly as the phone
 * drew it: the face keeps its own colour throughout, the squares have no padding and no
 * gap, and the only marks on it are the lines dividing column from column, row from row,
 * and the bottom row from the folder's name. See [lineColor].
 *
 * Every square carries an app. A folder with more apps than squares used to spend the last
 * one on an ellipsis, which said there was more without saying what: the squares turn over
 * anyway, so the same square spent on another app tells you what the folder holds *and*
 * still gets round to everything in it.
 *
 * The mini tiles are not live, and they carry an app's icon and nothing else. What is
 * unread inside a folder is marked once, on the folder's own name - see
 * TileView.applyFolderLabel - rather than on whichever squares happen to be up: a square
 * turns over every few seconds, so a dot on one is a mark that comes and goes on its own.
 *
 * When there are more apps than squares, one square at a time turns over to another of
 * them, so what is inside reveals itself over a few seconds rather than only on opening.
 */
@SuppressLint("ViewConstructor")
class FolderPreviewView(
    context: Context,
    private var palette: WP81Palette
) : ViewGroup(context) {

    /** One app inside the folder, as a mini tile shows it. */
    data class Entry(
        /** The desktop icon this came from. Identity for the rotation, not for display. */
        val id: String,
        val icon: Drawable?,
        /** Flat artwork, to be drawn in the tile's foreground colour rather than as-is. */
        val tint: Boolean,
        /** How much of its own canvas the artwork covers. See MonochromeIconProvider. */
        val contentRatio: Float = 1f
    )

    private var entries: List<Entry> = emptyList()

    /**
     * What the preview is currently showing.
     *
     * Read by the wall, which borrows a folder's tile to show what dropping the tile in
     * hand into it would give and has to put back what was there if the offer is withdrawn.
     */
    val contents: List<Entry>
        get() = entries

    /** Which entry each square is showing, as indices into [entries]. */
    private val shown = mutableListOf<Int>()

    /** Round-robin cursors, so every square takes a turn and every app gets shown. */
    private var nextSlot = 0
    private var nextEntry = 0

    private var paused = false

    /**
     * How much of the tile's height the squares are given, 0 to 1.
     *
     * The rest is the folder's name, which is drawn by the tile rather than here but has
     * to be got out of the way of all the same. A short tile gives the name half its
     * height and a tall one a fifth: the name is one line whatever the tile is, so the
     * taller the tile the smaller its share.
     */
    private var heightFraction = 1f

    /**
     * The shape of the grid, set from the folder tile's footprint rather than measured.
     * See [setGrid].
     */
    private var columns = 0
    private var rows = 0

    private val cells = mutableListOf<Slot>()

    private val linePaint = Paint()

    /**
     * Whether the squares are showing their apps, or only the lines dividing them.
     *
     * An open folder leaves its tile behind on the wall as a marker for where it is, and
     * a marker that still has the apps and the name on it is the folder twice over - the
     * page in front of the user is already listing them. What is left is the scoring: the
     * same tile, the same divisions, and nothing in them. See TileView.applyEmptied.
     */
    private var contentsHidden = false

    /**
     * This view's own offset into the rotation, 1-5 seconds.
     *
     * The same trick the tiles themselves use: without it every folder on Start would
     * turn a square over on the same frame.
     */
    private val phaseMs: Long = 1000L + (Math.random() * 4000L).toLong()

    private val rotate = object : Runnable {
        override fun run() {
            rotateOne()
            postDelayed(this, ROTATE_MS)
        }
    }

    init {
        setLayout(2, 2, 1f)
        applyPalette(palette)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(rotate)
        postDelayed(rotate, phaseMs)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(rotate)
    }

    /**
     * Sets how many squares fill the tile, across and down.
     *
     * Taken from the tile's footprint by the caller rather than worked out from the
     * measured size, so the squares are created before they are measured rather than in
     * the middle of a layout pass.
     */
    fun setLayout(cols: Int, rows: Int, heightFraction: Float) {
        val nextCols = cols.coerceAtLeast(1)
        val nextRows = rows.coerceAtLeast(1)
        val nextFraction = heightFraction.coerceIn(0.2f, 1f)
        val same = nextCols == columns && nextRows == this.rows &&
            nextFraction == this.heightFraction
        if (same) return
        this.heightFraction = nextFraction
        if (nextCols != columns || nextRows != this.rows) {
            columns = nextCols
            this.rows = nextRows
            val needed = nextCols * nextRows
            while (cells.size < needed) Slot(context).also { cells.add(it); addView(it) }
            while (cells.size > needed) removeView(cells.removeAt(cells.size - 1))
            seed()
        }
        requestLayout()
    }

    /**
     * The folder's contents, in the order the folder page lists them.
     *
     * Called again on every notification refresh, so the rotation is only restarted when
     * the contents themselves change - re-seeding it every couple of seconds would leave
     * the same apps on show forever.
     */
    fun setEntries(newEntries: List<Entry>) {
        val sameApps = newEntries.size == entries.size &&
            newEntries.indices.all { newEntries[it].id == entries[it].id }
        entries = newEntries
        if (sameApps) bindSlots() else seed()
    }

    /** Nothing turns over while the tile is being arranged. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        linePaint.color = lineColor()
        invalidate()
        for (cell in cells) cell.invalidate()
    }

    /** Empties the squares, leaving the lines. See [contentsHidden]. */
    fun setContentsHidden(hidden: Boolean) {
        if (contentsHidden == hidden) return
        contentsHidden = hidden
        for (cell in cells) cell.cancelFlip()
        bindSlots()
    }

    /**
     * The colour of the lines the tile is divided by.
     *
     * A line rather than a block: the folder is one tile scored into squares, not a crowd
     * of small tiles pushed together, and lightening each square to say where it began
     * turned the tile into a grid of paler patches - which is not what the phone drew.
     * The face is left exactly as it is, the accent or the photograph showing through it
     * unbroken, and only the divisions themselves are marked.
     *
     * The theme picks which way the scoring goes, not the tile: black under the dark
     * theme and white under the light one, so a wall of folders is scored the same way
     * throughout rather than each tile deciding for itself from whatever colour it
     * happens to be wearing.
     */
    private fun lineColor(): Int =
        if (palette.isDark) Color.argb(LINE_ALPHA, 0, 0, 0)
        else Color.argb(LINE_ALPHA, 255, 255, 255)

    /** Starts the preview over: the first apps in the folder, in the first squares. */
    private fun seed() {
        shown.clear()
        for (i in 0 until minOf(appSlots(), entries.size)) shown.add(i)
        nextEntry = shown.size
        nextSlot = 0
        for (cell in cells) cell.cancelFlip()
        bindSlots()
    }

    /** How many squares carry an app: all of them. */
    private fun appSlots(): Int = cells.size

    private fun bindSlots() {
        for (i in cells.indices) {
            // A square in the middle of turning over already knows what it is turning to,
            // and binding it now would snap the new icon up before the cell is edge-on.
            if (cells[i].isFlipping) continue
            cells[i].bind(shown.getOrNull(i)?.let { entries.getOrNull(it) })
        }
    }

    /**
     * Slides one square on to an app that is not currently showing.
     *
     * One at a time, and round-robin over both the squares and the contents, so the
     * preview changes gently and works its way through the whole folder rather than
     * flickering between the same two apps.
     */
    private fun rotateOne() {
        if (paused || contentsHidden || !isShown) return
        if (shown.isEmpty() || entries.size <= shown.size) return

        val slot = nextSlot % shown.size
        nextSlot = slot + 1

        var pick = -1
        for (step in entries.indices) {
            val candidate = (nextEntry + step) % entries.size
            if (candidate !in shown) {
                pick = candidate
                nextEntry = candidate + 1
                break
            }
        }
        if (pick < 0) return

        shown[slot] = pick
        cells[slot].slideTo(entries[pick])
    }

    /**
     * Where the squares stop and the folder's name begins, measured down the tile.
     *
     * Everything above it is the grid's and everything below it the name's, and the line
     * drawn along it is the one that separates the two.
     */
    private fun gridBottom(h: Int): Int = (h * heightFraction).toInt()

    /**
     * The left edge of column [i], in whole pixels from the tile's own left edge.
     *
     * Taken as a fraction of the whole width rather than counted up in squares, so the
     * columns divide the tile exactly: what the division leaves over is a pixel here and
     * a pixel there inside the grid rather than a strip of unused tile at the end of the
     * row. The squares come out square to within that pixel, because the shape of the
     * grid was derived from the tile's own footprint. [rowEdge] does the same downwards,
     * over the band the name has not taken.
     */
    private fun colEdge(i: Int, w: Int): Int = (w.toLong() * i / columns).toInt()

    private fun rowEdge(j: Int, h: Int): Int =
        (gridBottom(h).toLong() * j / rows).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in cells.indices) {
            val col = i / rows
            val row = i % rows
            val cw = (colEdge(col + 1, w) - colEdge(col, w)).coerceAtLeast(1)
            val ch = (rowEdge(row + 1, h) - rowEdge(row, h)).coerceAtLeast(1)
            cells[i].measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (cells.isEmpty()) return
        // Edge to edge and against the top of the tile: the squares are the tile, divided,
        // so there is nothing to centre them in and nothing between them but the line.
        // Column by column rather than row by row: a folder rarely has enough in it to
        // fill a wide tile, and filling across would leave the lower rows empty and the
        // squares strung out along the top. Down then across keeps whatever there is as a
        // block against the left edge, however few of them there are.
        for (i in cells.indices) {
            val col = i / rows
            val row = i % rows
            cells[i].layout(
                colEdge(col, width),
                rowEdge(row, height),
                colEdge(col + 1, width),
                rowEdge(row + 1, height)
            )
        }
    }

    /**
     * The lines the tile is scored into squares by, drawn over the squares themselves.
     *
     * Over rather than under, so a line is the same weight the whole way across whatever
     * happens to be behind it - and an icon sliding through its square passes under the
     * scoring rather than over it.
     *
     * Only the divisions are drawn, never the outside: a folder is a tile like any other
     * and an outline round it would make it a framed one. The last line across is the one
     * under the bottom row, which is what separates the squares from the folder's name -
     * and it is only there when the name has a band to be separated from.
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (columns <= 0 || rows <= 0) return
        val weight = dp(LINE_DP).coerceAtLeast(1).toFloat()
        val bottom = gridBottom(height)
        for (i in 1 until columns) {
            val x = colEdge(i, width) - weight / 2f
            canvas.drawRect(x, 0f, x + weight, bottom.toFloat(), linePaint)
        }
        for (j in 1 until rows) {
            val y = rowEdge(j, height) - weight / 2f
            canvas.drawRect(0f, y, width.toFloat(), y + weight, linePaint)
        }
        if (bottom < height) {
            val y = bottom - weight / 2f
            canvas.drawRect(0f, y, width.toFloat(), y + weight, linePaint)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * One square of the folder tile.
     *
     * Drawn rather than assembled out of an ImageView and a dot: everything on it is
     * sized from the square, which is only known once the tile has been measured, and a
     * quarter of a 1x1 tile is small enough that the marks on it have to be placed
     * against its actual size rather than in dp.
     */
    private inner class Slot(context: Context) : View(context) {

        private var icon: Drawable? = null
        private var tint = false
        private var ratio = 1f

        private var flip: ValueAnimator? = null

        /** How far the icon is currently slid out of its square, in pixels. */
        private var slideOffset = 0f

        val isFlipping: Boolean get() = flip != null

        fun bind(entry: Entry?) {
            apply(entry)
            visibility = if (entry == null || contentsHidden) INVISIBLE else VISIBLE
        }

        private fun apply(entry: Entry?) {
            // A copy, because the same Drawable is very likely also the app's icon
            // somewhere else on screen, and bounds and colour filter are set on the
            // instance rather than on the draw.
            icon = entry?.icon?.let { it.constantState?.newDrawable()?.mutate() ?: it }
            tint = entry?.tint == true
            ratio = entry?.contentRatio ?: 1f
            invalidate()
        }

        /**
         * Slides the mini tile on to another app.
         *
         * Upwards and in one direction: the old icon leaves through the top of its square
         * and the new one comes up from below, which reads as a list moving past a window.
         * A flip reads as the square itself turning over, and four squares turning over in
         * a tile that is already a grid of squares was too much movement for what it says.
         *
         * Drawn rather than translated, so the icon is clipped to its own square instead
         * of sliding across its neighbours - see [onDraw].
         */
        fun slideTo(entry: Entry) {
            cancelFlip()
            var swapped = false
            flip = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SLIDE_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    val t = animation.animatedValue as Float
                    val travel = height.toFloat()
                    slideOffset = if (t <= 0.5f) -travel * (t * 2f) else travel * (1f - t) * 2f
                    if (!swapped && t >= 0.5f) {
                        swapped = true
                        bind(entry)
                    }
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!swapped) bind(entry)
                        slideOffset = 0f
                        flip = null
                        invalidate()
                    }
                })
                start()
            }
        }

        fun cancelFlip() {
            flip?.cancel()
            flip = null
            slideOffset = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val side = minOf(width, height).toFloat()
            // The block stays put and only what is on it moves, clipped to the square, so
            // a sliding icon never appears over the square beside it.
            val saved = canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            canvas.translate(0f, slideOffset)
            icon?.let { drawIcon(canvas, it, side) }
            canvas.restoreToCount(saved)
        }

        /**
         * The app's icon, centred and scaled to the square.
         *
         * Corrected for how much of its canvas the artwork covers, exactly as the full
         * tile does, so a padded adaptive icon and a flat silhouette come out the same
         * optical size side by side.
         */
        private fun drawIcon(canvas: Canvas, drawable: Drawable, side: Float) {
            val box = (side * ICON_FRACTION / ratio.coerceIn(MIN_RATIO, 1f))
                .coerceAtMost(side * MAX_ICON_FRACTION)
            val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
            val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
            val scale = box / maxOf(iw, ih)
            val w = iw * scale
            val h = ih * scale
            val cx = width / 2f
            val cy = height / 2f
            drawable.setBounds(
                (cx - w / 2f).toInt(),
                (cy - h / 2f).toInt(),
                (cx + w / 2f).toInt(),
                (cy + h / 2f).toInt()
            )
            drawable.colorFilter =
                if (tint) PorterDuffColorFilter(palette.onAccent(), PorterDuff.Mode.SRC_IN) else null
            drawable.draw(canvas)
        }

    }

    companion object {

        /** How thick the scoring between the squares is drawn. */
        private const val LINE_DP = 2

        /** And how far it takes the face down - or up, under the light theme. */
        private const val LINE_ALPHA = 140

        // Icon size against the square, and the ceiling once the padding correction has
        // been applied. Mirrors the full tile's own numbers.
        private const val ICON_FRACTION = 0.46f
        private const val MAX_ICON_FRACTION = 0.72f
        private const val MIN_RATIO = 0.5f

        /** How long a mini tile rests before another app takes its place. */
        private const val ROTATE_MS = 6_000L

        /** And how long the slide itself takes. */
        private const val SLIDE_MS = 460L
    }
}
