package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The weather tile, as one face rather than a run of them.
 *
 * Where it is, what the sky is doing, what it is out there, and how far the day moves -
 * the four things somebody looks at a weather tile to find, all of them at once. The tile
 * turned through them before: three readings on a 1x1, three columns side by side on
 * anything wider, and either way the answer to "is it cold" was a number with no idea
 * what it was of. A tile that says everything cannot be caught on the wrong face, which
 * is why this one does not turn over at all.
 *
 * Drawn rather than laid out in child views, as the columns were: four kinds of type of
 * four different sizes, two of them on one line, are easier to keep on one baseline with a
 * canvas than with a stack of nested layouts - and the tile is already full of those.
 *
 * The tile's own name is not drawn here. It is the label along the foot, which every
 * program's live tile carries, and the room it needs arrives as this view's bottom
 * padding - see TileView.applyContentFooter.
 */
@SuppressLint("ViewConstructor")
class WeatherFaceView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /**
     * One reading of the sky, in the pieces the face sets separately.
     *
     * [temperature] is the figure alone and [unit] the degree with its letter, because the
     * two are set at different sizes: the number is what the tile is for and the scale is
     * a footnote to it. Everything but the figure may be absent - a forecast that has not
     * arrived carries no range, and a place the geocoder has not named yet has no name -
     * and the face closes up around whatever is missing rather than leaving a hole.
     */
    data class Reading(
        val place: String,
        val condition: String?,
        val temperature: String,
        val unit: String,
        val high: String?,
        val low: String?
    )

    private var reading: Reading? = null

    /**
     * A cell of the wall, in pixels, or 0 while nobody has said.
     *
     * The figure is sized from this rather than from the tile, so a reading is the same
     * size on every tile that shows one - the wall has no size ladder, and a forecast set
     * larger because its tile happens to be wide would be the one number on the screen
     * that grew. See TileView.sizeAsNumber, which is where the same rule is applied to
     * every other reading.
     */
    var cell: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // The place is a name and takes the weight the wall gives a name; the sky under it is
    // a caption and takes the caption's. The figure is set in the numeral face for the
    // reason every reading is: it is what the tile is for.
    private val placePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
    }

    private val conditionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
    }

    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
    }

    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
    }

    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setReading(reading: Reading?) {
        if (reading == this.reading) return
        this.reading = reading
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val reading = this.reading ?: return
        if (width == 0 || height == 0) return

        val ink = palette.onAccent()
        for (paint in listOf(placePaint, conditionPaint, figurePaint, unitPaint, rangePaint)) {
            paint.color = ink
        }
        rulePaint.color = ink

        val pad = dp(EDGE_DP)
        // The foot belongs to the tile's name, which is drawn over this view.
        val depth = (height - paddingBottom).toFloat()
        val room = width - 2 * pad
        val usable = depth - 2 * pad
        if (room <= 0f || usable <= 0f) return

        // The figure first, because it is the one thing the tile cannot be without: as
        // much of a cell as every other reading on the wall takes, and never more of this
        // tile than it has.
        val basis = if (cell > 0) cell.toFloat() else minOf(width.toFloat(), depth)
        // A tile two cells across and two deep has room the ladder does not ask for, and
        // the figure is the thing somebody opens the tile to read: it takes a little of
        // that room back. Measured against the whole tile rather than the span it was
        // built with, so a tile resized under the face gets the right answer without
        // being told. Not the strips or the 1x1, where the words above the figure have
        // every point of it already.
        val roomy = cell > 0 && width >= 2 * cell && height >= 2 * cell
        figurePaint.textSize = minOf(
            sp(FIGURE_MAX_SP),
            basis * FIGURE_CELL_SHARE + if (roomy) sp(FIGURE_ROOM_SP) else 0f,
            // Against the height a line of it actually stands, not against its size: a
            // figure is a third taller than the size it is set at, so a share of the tile
            // read as a size is half again as much of the tile as it says. Which is what
            // left the 1x1 with a number filling nine tenths of it and the sky squeezed
            // into what was left.
            sizeForHeight(figurePaint, usable * FIGURE_HEIGHT_SHARE)
        ).coerceAtLeast(sp(MIN_SP))
        unitPaint.textSize = figurePaint.textSize * UNIT_SHARE
        rangePaint.textSize = sp(RANGE_SP)
        placePaint.textSize = sp(PLACE_SP)
        conditionPaint.textSize = sp(CONDITION_SP)

        // Then the two words at the top, which are what gives way when there is no room
        // for all of it: a 1x1 with a name, a sky, a figure and a range stacked in it
        // would be four things too small to read rather than two worth reading.
        //
        // Where it is goes first. Somebody reading the smallest tile on their own wall
        // knows where they are; what they cannot see without looking is what the sky is
        // doing, so the word stays and the name goes. And where even those two will not
        // fit, the figure comes down to make room rather than the word going as well - a
        // reading a size smaller is still a reading, and "31" with nothing to say what
        // kind of day it is is half a tile.
        var place = reading.place.takeIf { it.isNotBlank() }
        var condition = reading.condition?.takeIf { it.isNotBlank() }
        val gap = dp(HEAD_GAP_DP)
        fun head(): Float {
            var total = 0f
            if (place != null) total += height(placePaint)
            if (condition != null) total += height(conditionPaint)
            if (place != null || condition != null) total += gap
            return total
        }
        if (head() + height(figurePaint) > usable) place = null
        if (head() + height(figurePaint) > usable) {
            val left = usable - head()
            val squeeze = left / height(figurePaint)
            figurePaint.textSize = (figurePaint.textSize * squeeze).coerceAtLeast(sp(MIN_SP))
            unitPaint.textSize = figurePaint.textSize * UNIT_SHARE
        }
        // And the word only goes where the figure has come as far down as it goes.
        if (head() + height(figurePaint) > usable) condition = null

        var y = pad
        place?.let {
            canvas.drawText(fit(it, placePaint, room), pad, y - placePaint.ascent(), placePaint)
            y += height(placePaint)
        }
        condition?.let {
            canvas.drawText(
                fit(it, conditionPaint, room), pad, y - conditionPaint.ascent(), conditionPaint)
        }

        // The reading stands on the floor of the tile rather than under the words: what is
        // between them is whatever the tile has spare, which is the air the face is read
        // through on anything taller than a strip.
        drawReading(canvas, reading, pad, depth - pad - figurePaint.descent(), room)
    }

    /**
     * The figure, the scale beside it, and the day's range beside that.
     *
     * One line of three parts, set on [baseline]. Where the tile is too narrow to hold
     * all three the range is the part that goes - it is the least of them, and a figure
     * squeezed to make room for what it is being compared against has the two the wrong
     * way round. Only if the figure and its scale alone will not fit is anything shrunk,
     * and then all of it together: a range set at a size the figure was not would read as
     * belonging to something else.
     */
    private fun drawReading(
        canvas: Canvas,
        reading: Reading,
        left: Float,
        baseline: Float,
        room: Float
    ) {
        // What the day's two ends are, and what is left of them once the tile has had its
        // say about how much room there is across it.
        val highOf = reading.high?.takeIf { it.isNotBlank() }
        val lowOf = reading.low?.takeIf { it.isNotBlank() }
        var high = highOf
        var low = lowOf

        fun rangeWidth() =
            maxOf(
                high?.let { rangePaint.measureText(it) } ?: 0f,
                low?.let { rangePaint.measureText(it) } ?: 0f
            )

        fun rowWidth(): Float {
            var wide = figurePaint.measureText(reading.temperature) +
                unitPaint.measureText(reading.unit)
            if (high != null || low != null) wide += dp(RANGE_GAP_DP) + rangeWidth()
            return wide
        }

        // Down the tile before across it. The pair stands one at either end of the
        // figure's own digits, so what it has to go in is the figure's height - and the
        // figure is drawn from the tile in pixels where the range is set in points, which
        // is how a phone asked for larger text came to have the day's ceiling drawn
        // straight through its floor. Asked first, so a range that has gone is not
        // something the row is measured around and a range that has come down is measured
        // at the size it came down to.
        if (high != null && low != null && !fitRange(-figurePaint.ascent())) {
            high = null
            low = null
        }

        // The range goes before anything is squeezed. A 1x1 has room across it for a
        // figure and its scale and nothing else, and three readings shrunk until they fit
        // side by side is three numbers nobody can read rather than the one worth having.
        if (rowWidth() > room) {
            high = null
            low = null
        }

        // Then, and only then, measured and scaled once: every width here is linear in the
        // text size, so the ratio that makes the row fit is exact rather than something to
        // iterate towards.
        val wide = rowWidth()
        if (wide > room && wide > 0f) {
            val squeeze = room / wide
            figurePaint.textSize = (figurePaint.textSize * squeeze).coerceAtLeast(sp(MIN_SP))
            unitPaint.textSize = (unitPaint.textSize * squeeze).coerceAtLeast(sp(MIN_SP))
            rangePaint.textSize = (rangePaint.textSize * squeeze).coerceAtLeast(sp(MIN_SP))
        }

        // Both of those are held above a floor of their own, so a squeeze hard enough to
        // put the range on its floor and not the figure leaves the pair taller than the
        // digits again. Cheap to ask a second time, and the answer is the tile's either
        // way.
        if (high != null && low != null && !fitRange(-figurePaint.ascent())) {
            high = null
            low = null
        }

        canvas.drawText(reading.temperature, left, baseline, figurePaint)
        var x = left + figurePaint.measureText(reading.temperature)
        // Raised to the top of the digits rather than sat on their line: the scale is a
        // mark on the number, the way a degree sign is, and one on the baseline reads as a
        // second, smaller number standing next to the first.
        val top = baseline + figurePaint.ascent()
        canvas.drawText(reading.unit, x, top - unitPaint.ascent(), unitPaint)
        x += unitPaint.measureText(reading.unit)

        if (high == null && low == null) return
        x += dp(RANGE_GAP_DP)
        // The pair spans the digits, one at either end of them, so the three read as one
        // block: the day's ceiling, a rule, and its floor.
        high?.let { canvas.drawText(it, x, top - rangePaint.ascent(), rangePaint) }
        low?.let { canvas.drawText(it, x, baseline, rangePaint) }
        if (high != null && low != null) {
            val ruleY = (top - rangePaint.ascent() + rangePaint.descent() +
                baseline + rangePaint.ascent()) / 2f
            canvas.drawRect(x, ruleY, x + rangeWidth(), ruleY + dp(RULE_DP), rulePaint)
        }
    }

    /**
     * Brings the day's two ends down until the pair of them stands inside [span].
     *
     * [span] is the figure's own height above its baseline, which is all the room the two
     * have: the ceiling is set on the top of the digits and the floor on the line under
     * them - see [drawReading]. Where they will not go in at the size they are set at they
     * come down to the size they will, and where even the smallest the tile sets is too
     * tall this answers false and the range is dropped. A range drawn through itself says
     * neither of the two numbers in it.
     */
    private fun fitRange(span: Float): Boolean {
        // The two lines, less the descent of the upper one, which the lower one's ascent
        // is measured from the other side of.
        val needed = 2f * -rangePaint.ascent() + rangePaint.descent()
        if (needed <= span) return true
        if (needed <= 0f || span <= 0f) return false
        val size = rangePaint.textSize * span / needed
        if (size < sp(MIN_SP)) return false
        rangePaint.textSize = size
        return true
    }

    /** One line's height in this paint. */
    private fun height(paint: Paint) = paint.descent() - paint.ascent()

    /**
     * The size at which a line of this paint stands exactly [room] tall.
     *
     * A face's height is a fixed multiple of its size, so the answer is one measurement
     * and a ratio rather than a search. Leaves the paint as it found it.
     */
    private fun sizeForHeight(paint: Paint, room: Float): Float {
        val was = paint.textSize
        val probe = if (was > 0f) was else sp(PROBE_SP)
        paint.textSize = probe
        val tall = height(paint)
        paint.textSize = was
        return if (tall <= 0f) probe else probe * room / tall
    }

    /**
     * [text] cut to [room] with an ellipsis, rather than set smaller to fit.
     *
     * A place name is a name: shrinking it until it fits would set two tiles side by side
     * in two different sizes because one of them is somewhere with a longer name.
     */
    private fun fit(text: String, paint: TextPaint, room: Float): String =
        TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()

    private fun dp(v: Int) = v * resources.displayMetrics.density

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** Kept clear of the tile's edges, as the type on any other face is. */
        private const val EDGE_DP = 10

        /** Between the words at the top and the reading at the foot, at the very least. */
        private const val HEAD_GAP_DP = 6

        /** Between the scale and the range, so the two are not read as one figure. */
        private const val RANGE_GAP_DP = 10

        /** The line between the day's two ends. */
        private const val RULE_DP = 1

        // The reading, sized as every reading on the wall is: a share of one cell, capped
        // at a ceiling that rarely binds. See TileView.sizeAsNumber, whose two constants
        // these are - a weather tile whose figure did not match the clock's beside it
        // would be the wall set by two different hands.
        private const val FIGURE_MAX_SP = 80f
        private const val FIGURE_CELL_SHARE = 0.4f

        /**
         * What the figure gains once the tile is at least two cells each way.
         *
         * The one place the wall's size ladder is stepped off, and only upwards: a 2x2
         * carries a name, a sky and a range around a figure sized for a tile a quarter
         * the area, and the room left over went to the air between them rather than to
         * the number.
         */
        private const val FIGURE_ROOM_SP = 5f

        /**
         * And never more of a short tile than this, whatever the cell says.
         *
         * Set against the line's own height. Just over half, so that a 1x1 - where this is
         * what binds - has the sky above the figure with air between them rather than two
         * things pushed into opposite ends of the tile.
         */
        private const val FIGURE_HEIGHT_SHARE = 0.55f

        /** The scale, as a share of the figure it is a footnote to. */
        private const val UNIT_SHARE = 0.34f

        // The name of the place, and the caption size the rest of the wall's second lines
        // use. The pair belongs to the wall rather than to this tile - a name over a line
        // saying something about it - and the calendar and the media tile are headed in
        // the same two: see TileView.FACE_TITLE_SP and TileView.LIVE_CAPTION_SP, which are
        // this size and this weight, and where the pair is reasoned about.
        private const val PLACE_SP = 14f
        private const val CONDITION_SP = 12f

        /**
         * The day's two ends, which stay where the caption was.
         *
         * It was filed with the captions and took their size, being what the figure is
         * measured against rather than a reading of its own. But it stands beside the
         * figure, not under a title, so when the wall's second lines came down a point it
         * stayed: brought with them it would have been the smallest thing on the tile,
         * shrunk for a reason that has nothing to do with where it sits.
         */
        private const val RANGE_SP = 13f

        /** However little room there is, what is in it is still meant to be read. */
        private const val MIN_SP = 9f

        /** Any size at all, for the one measurement [sizeForHeight] scales from. */
        private const val PROBE_SP = 20f
    }
}
