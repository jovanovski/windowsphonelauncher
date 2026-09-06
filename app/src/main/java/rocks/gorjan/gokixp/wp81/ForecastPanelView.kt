package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The weather tile's forecast, laid out across the tile instead of turned through it.
 *
 * A tile with one cell to its name can only show one reading at a time, so it shows them
 * in turn: now, what the sky does next, tomorrow's. Give it two cells and turning them
 * over stops being economy and starts being a delay - the tile has the room to say all of
 * it at once, and a glance at a wide tile should not have to be timed to catch the day it
 * wanted.
 *
 * So each reading gets a column of its own - what it is of, what the sky is doing, and the
 * figure - and the tile stops rotating for as long as it is wide enough to hold them. Which
 * readings there are is not this view's business: it draws the columns it is handed, two
 * of them or three, and the host settles what the middle one is of - today's high while
 * the day can still reach it, tonight's low once it cannot.
 *
 * Drawn rather than laid out in child views. Three bands of different kinds of thing, sized
 * against each other and against the tile they are in, are easier to keep on one baseline
 * with a canvas than with a row of nested layouts - and the tile is already full of those.
 */
@SuppressLint("ViewConstructor")
class ForecastPanelView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /**
     * One column: what the reading is of, the sky it was taken under, and the reading.
     *
     * [glyph] is allowed to be absent - a WMO code the shell does not have a mark for
     * leaves the column its label and its figure rather than a hole where the sky was.
     */
    data class Column(
        val label: String,
        val glyph: Int?,
        val reading: String
    )

    private var columns: List<Column> = emptyList()

    /** The marks, held past the first draw: every column asks for one on every frame. */
    private val icons = HashMap<Int, Drawable?>()

    // The label is the line over a reading, and takes the same weight up as the one on a
    // turning face does - at this size, in the wall's Semilight, it is the first thing to
    // disappear into the accent behind it. The figure is set in the numeral face for the
    // same reason a reading anywhere else is: it is what the tile is for.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        textAlign = Paint.Align.CENTER
    }

    private val readingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        textAlign = Paint.Align.CENTER
    }

    fun setColumns(columns: List<Column>) {
        if (columns == this.columns) return
        this.columns = columns
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        // The marks are tinted as they are drawn, so what is held here is now the wrong
        // colour - and a held drawable cannot be re-tinted once it has been.
        icons.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (columns.isEmpty() || width == 0 || height == 0) return

        val ink = palette.onAccent()
        labelPaint.color = ink
        readingPaint.color = ink

        // The foot may be spoken for by the tile's name, which is drawn over this view.
        // What is left above it is the panel: a named tile sets its columns a little
        // higher rather than printing them through the name. See TileView.applyContentFooter.
        val depth = height - paddingBottom
        val pad = dp(EDGE_DP)
        val columnWidth = (width - 2 * pad) / columns.size.toFloat()
        // One size for the whole panel rather than one per column: three readings side by
        // side are one row of type, and a "9°" set larger than the "35°" beside it would
        // read as the more important of the two rather than the shorter.
        val room = columnWidth - dp(COLUMN_GAP_DP)

        // The height is shared out before either line is set, so the same three bands fit
        // a strip as fit a two-deep tile. Sized to the band and then capped at what the
        // wall uses elsewhere, so a tall tile is unchanged and a short one shrinks to fit
        // rather than losing the mark between its two lines - which is what happened when
        // the mark was given only what the type had left over.
        val usable = depth - 2 * pad
        val gap = minOf(dp(BAND_GAP_DP), usable * GAP_MAX_SHARE)
        val bands = usable - 2 * gap
        labelPaint.textSize = minOf(
            fit(labelPaint, columns.map { it.label }, room, LABEL_SP),
            sizeForHeight(labelPaint, bands * LABEL_HEIGHT_SHARE, LABEL_SP)
        )
        readingPaint.textSize = minOf(
            fit(readingPaint, columns.map { it.reading }, room, READING_SP),
            sizeForHeight(readingPaint, bands * READING_HEIGHT_SHARE, READING_SP)
        )

        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val readingHeight = readingPaint.descent() - readingPaint.ascent()
        // What is left between the two lines is the mark's, up to the point where it would
        // be wider than its column.
        val side = minOf(
            columnWidth * GLYPH_WIDTH_SHARE,
            usable - labelHeight - readingHeight - 2 * gap
        ).coerceAtLeast(0f)

        val block = labelHeight + gap + side + gap + readingHeight
        val top = (depth - block) / 2f

        for ((index, column) in columns.withIndex()) {
            val centre = pad + columnWidth * (index + 0.5f)
            canvas.drawText(column.label, centre, top - labelPaint.ascent(), labelPaint)
            if (side > 0f) {
                val markTop = top + labelHeight + gap
                icon(column.glyph)?.let { mark ->
                    mark.setBounds(
                        (centre - side / 2f).toInt(), markTop.toInt(),
                        (centre + side / 2f).toInt(), (markTop + side).toInt()
                    )
                    mark.draw(canvas)
                }
            }
            canvas.drawText(
                column.reading,
                centre,
                top + block - readingPaint.descent(),
                readingPaint
            )
        }
    }

    /**
     * The largest size in [max] at which every one of [texts] fits [room].
     *
     * Measured against the longest of them rather than each in turn: the columns share a
     * size, so the one that binds is the one with the most in it.
     */
    private fun fit(paint: Paint, texts: List<String>, room: Float, max: Float): Float {
        val ceiling = sp(max)
        if (room <= 0f) return ceiling
        paint.textSize = ceiling
        val widest = texts.maxOfOrNull { paint.measureText(it) } ?: 0f
        if (widest <= room) return ceiling
        return (ceiling * room / widest).coerceAtLeast(sp(MIN_SP))
    }

    /**
     * The largest size in [max] at which a line of this paint is no taller than [room].
     *
     * A paint's height scales with its size, so the ratio of the two is the answer. The
     * floor is the same one the width fit uses: however little room there is, what is in
     * it is still meant to be read.
     */
    private fun sizeForHeight(paint: Paint, room: Float, max: Float): Float {
        val ceiling = sp(max)
        if (room <= 0f) return sp(MIN_SP)
        paint.textSize = ceiling
        val height = paint.descent() - paint.ascent()
        if (height <= room) return ceiling
        return (ceiling * room / height).coerceAtLeast(sp(MIN_SP))
    }

    /** The mark for a condition, tinted and held. */
    private fun icon(res: Int?): Drawable? {
        if (res == null) return null
        return icons.getOrPut(res) {
            ContextCompat.getDrawable(context, res)?.mutate()?.apply {
                setTint(palette.onAccent())
            }
        }
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** Kept clear of the tile's edges, as the type on any other face is. */
        private const val EDGE_DP = 10

        /**
         * Between one column and the next, so two readings are never read as one.
         *
         * Taken out of the room the type is fitted to rather than drawn: what it actually
         * does is make the figures a size smaller on the tile with three of them across
         * two cells, which is where "28° 35° 31°" would otherwise run together into one
         * long number.
         */
        private const val COLUMN_GAP_DP = 12

        /** Between the label, the mark and the reading. */
        private const val BAND_GAP_DP = 6

        // The two sizes the panel is set in, and the floor either may be squeezed to. The
        // caption size the rest of the wall's second lines use, and a reading a step above
        // it - not the reading size a turning face uses, which is sized to have a whole
        // tile to itself and would leave three columns with nothing but digits in them.
        private const val LABEL_SP = 13f
        private const val READING_SP = 21f

        /** However narrow the column, what is in it is still meant to be read. */
        private const val MIN_SP = 9f

        /** How much of its column the condition mark may take. */
        private const val GLYPH_WIDTH_SHARE = 0.62f

        // How the height is shared out between the three bands before the type is set.
        // The mark takes what the two lines leave, which is the rest.
        private const val LABEL_HEIGHT_SHARE = 0.24f
        private const val READING_HEIGHT_SHARE = 0.34f

        /** The gap between bands gives way on a short tile rather than squeezing the mark. */
        private const val GAP_MAX_SHARE = 0.07f
    }
}
