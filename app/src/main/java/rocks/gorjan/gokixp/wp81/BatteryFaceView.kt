package rocks.gorjan.gokixp.wp81

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The battery tile: the cell itself, filled to the charge, with the figure beside it.
 *
 * The mark is the icon pack's own battery - `appbar.battery.0`, the empty one - turned a
 * quarter so it stands upright, and the charge is drawn into the well the outline leaves
 * hollow. That is the whole idea: the pack ships four batteries at four states of charge
 * and none of them is the state this phone is actually in, so the empty one is used as
 * what it really is - a container - and the level is painted inside it.
 *
 * Which means there is one place the geometry is known, and it is the file: the outline is
 * drawn from the asset unedited, and the only numbers written down here are where the
 * hollow is inside it. See [WELL].
 *
 * On charge the mark becomes the pack's `appbar.battery.charging` - the same battery with a
 * plug across it - and the fill climbs. A pale column runs from the level up to the top of
 * the cell and starts again, which is the animation every phone has used to say "taking
 * charge" since phones had batteries; the real level stays drawn underneath it, so the tile
 * still answers the question while it is doing it.
 *
 * The two marks are drawn to the same box, measured on the battery rather than on the plug,
 * so the cell does not move or change size at the moment the charger goes in. What the plug
 * adds is a few units of overhang on the left, which the face keeps clear of the edge
 * whether or not there is a plug in it - see [PLUG_OVERHANG].
 *
 * Drawn rather than laid out, like the weather face beside it on the wall: a mark whose
 * inside has to be painted, and a figure set against its height, are one canvas's work and
 * three nested views' worth of trouble.
 */
@SuppressLint("ViewConstructor")
class BatteryFaceView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /**
     * The charge, and whether it is going up.
     *
     * [percent] is 0-100. [charging] is taking charge rather than merely being plugged in:
     * a phone held at full is on a charger and is not filling, and a tile animating for it
     * would be saying something untrue all night.
     */
    data class Reading(val percent: Int, val charging: Boolean)

    private var reading: Reading? = null

    /**
     * A cell of the wall, in pixels, or 0 while nobody has said.
     *
     * Both the mark and the figure are sized from this rather than from the tile, so the
     * battery on a 1x1 and the battery on a wide tile are the same battery - and the
     * figure matches every other reading on the wall. See TileView.sizeAsNumber and
     * WeatherFaceView.cell, which is the same rule.
     */
    var cell: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The outline, straight out of the assets. See the class comment. */
    private val shell = SvgIcon.fromAsset(context, MARK_ASSET)

    /**
     * The same battery with a plug across it, read the first time one goes in.
     *
     * Lazily, because most tiles never see it: a wall is looked at far more often off
     * charge than on, and a second file parsed on the way up for a state the phone may not
     * be in is a file parsed for nothing.
     */
    private val chargingShell: SvgIcon? by lazy {
        SvgIcon.fromAsset(context, CHARGING_ASSET)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * The reading, sign and all.
     *
     * One paint and one string: the per cent sign is set at the figure's own size rather
     * than raised small beside it the way the weather tile's degree is. A degree is a mark
     * on a number - "31" is the reading and the little circle says what kind - where "87%"
     * is the reading, one word, and setting the last character of it a third smaller reads
     * as a footnote to something that has none.
     */
    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
    }

    /** Where the climbing column has got to, 0-1 of the way from the level to full. */
    private var sweep = 0f

    private var charger: ValueAnimator? = null

    /** Reused per draw: allocating a rectangle sixty times a second is a rectangle a frame. */
    private val box = RectF()

    fun setReading(reading: Reading?) {
        if (reading == this.reading) return
        this.reading = reading
        syncCharger()
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
    }

    // The animation is worth running only while somebody can see it: a tile behind the app
    // list, or on a Start screen that is not the one showing, would be spending a frame
    // every sixteen milliseconds on a picture of nothing.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncCharger()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCharger()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncCharger()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncCharger()
    }

    private fun syncCharger() {
        val now = reading
        // The window as well as the view. [isShown] walks the view tree and knows nothing
        // about whether the window it is in is on screen at all, so on its own it stays
        // true for a launcher the user has left - which is a frame every sixteen
        // milliseconds spent animating a tile behind whatever app they went to.
        val watched = isShown && windowVisibility == VISIBLE
        // A phone with animations turned off does not get one. Asked outright rather than
        // left to the animator: with the duration scale at zero a repeating animation does
        // not stop, it arrives at its end on every frame - which would hold the pale column
        // permanently at the top of the cell and read as a full battery. The level itself
        // is drawn either way, which is what the tile is actually for.
        val wanted = watched && ValueAnimator.areAnimatorsEnabled() &&
            now != null && now.charging && now.percent < 100
        if (wanted == (charger != null)) return
        if (wanted) startCharger() else stopCharger()
    }

    private fun startCharger() {
        charger = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // The climb takes most of the cycle and the rest is a pause at the top,
                // so the column arrives somewhere rather than snapping back the instant
                // it is full. Held at one through the pause; the restart is the only jump.
                val t = it.animatedValue as Float
                sweep = (t / CLIMB_SHARE).coerceAtMost(1f)
                invalidate()
            }
            start()
        }
    }

    private fun stopCharger() {
        charger?.cancel()
        charger = null
        sweep = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val reading = this.reading ?: return
        if (width == 0 || height == 0) return

        val ink = palette.onAccent()
        fillPaint.color = ink
        figurePaint.color = ink

        val pad = dp(EDGE_DP)
        // The foot belongs to the tile's own name, which is drawn over this view.
        val depth = (height - paddingBottom).toFloat()
        val room = width - 2 * pad
        val usable = depth - 2 * pad
        if (room <= 0f || usable <= 0f) return

        val level = (reading.percent.coerceIn(0, 100)) / 100f

        // The mark, sized from a cell of the wall and then held to whatever this tile
        // actually has. A tall tile does not get a taller battery - the wall has no size
        // ladder - but a short one does get a shorter one rather than an overhanging one.
        val basis = if (cell > 0) cell.toFloat() else minOf(width.toFloat(), depth)
        var markHeight = minOf(basis * MARK_CELL_SHARE, usable)
        var markWidth = markHeight * MARK_W / MARK_H
        if (markWidth > room) {
            markWidth = room
            markHeight = markWidth * MARK_H / MARK_W
        }

        // Whether there is a number beside the mark at all.
        //
        // A cell across is the line, and it is drawn by the wall rather than by what
        // happens to fit: a 1x1 has enough width to squeeze "87%" in beside the battery at
        // about a third the size of every other reading on the screen, which is a number
        // nobody can read next to a picture that was already answering the question. So
        // the smallest tile is the mark and its charge, exactly as it is for every other
        // widget on the wall - see TileView.showsLive - and anything wider carries the
        // figure too. Falls back to the tile's own proportions on the rare surface that
        // has not said how big a cell is.
        val wideEnough = if (cell > 0) width >= 2 * cell else room > usable * ASPECT_FOR_FIGURE

        // The figure, sized as every reading on the wall is.
        figurePaint.textSize = minOf(
            sp(FIGURE_MAX_SP),
            basis * FIGURE_CELL_SHARE,
            sizeForHeight(figurePaint, usable * FIGURE_HEIGHT_SHARE)
        ).coerceAtLeast(sp(MIN_SP))

        val figure = "${reading.percent.coerceIn(0, 100)}$UNIT"
        var figureWidth = figurePaint.measureText(figure)
        val gap = dp(GAP_DP)
        var showFigure = wideEnough && markWidth + gap + figureWidth <= room
        // A tile that is wide enough to carry a figure and has run out of room for the one
        // it was handed - a phone asked for very large text, or "100%" where "9%" fitted -
        // gets it a size smaller rather than not at all.
        if (wideEnough && !showFigure) {
            val spare = room - markWidth - gap
            val squeeze = if (figureWidth > 0f) spare / figureWidth else 0f
            if (figurePaint.textSize * squeeze >= sp(MIN_SP)) {
                figurePaint.textSize *= squeeze
                figureWidth = figurePaint.measureText(figure)
                showFigure = true
            }
        }

        // Left-aligned with the number beside it, the way every other reading on the wall
        // stands; centred when the mark is the whole of the tile, because a battery alone
        // in the corner of a square reads as one that slipped.
        //
        // Either way the plug's overhang is kept clear, whether or not there is a plug in
        // it right now. Held to whichever is larger, the edge or the overhang: a phone
        // where the two marks stood in different places would shift the whole tile
        // sideways at the moment the charger went in, and on a tile large enough for the
        // overhang to beat the edge the prongs would be cut off by the view's own bounds.
        val plugRoom = PLUG_OVERHANG * markHeight / MARK_H
        val block = if (showFigure) markWidth + gap + figureWidth else markWidth
        val left =
            if (showFigure) maxOf(pad, plugRoom)
            else maxOf(plugRoom, pad + (room - block) / 2f)
        val middle = pad + usable / 2f

        box.set(left, middle - markHeight / 2f, left + markWidth, middle + markHeight / 2f)
        drawBattery(canvas, box, level, reading.charging)

        if (!showFigure) return
        // On the mark's own middle rather than on a line of its own: the figure is what
        // the battery beside it is showing, and the two read as one statement only if
        // they are set against each other.
        val baseline = middle - (figurePaint.ascent() + figurePaint.descent()) / 2f
        canvas.drawText(figure, left + markWidth + gap, baseline, figurePaint)
    }

    /**
     * The cell, upright, filled to [level].
     *
     * Three transforms, outermost first: the mark is placed and scaled into [box], then
     * the quarter turn is undone so that everything inside is stated in the coordinates
     * the asset itself uses. Which is the point - the outline is drawn from the file
     * unedited, and the well it leaves hollow is named in the file's own numbers.
     *
     * The turn is a quarter anticlockwise, which puts the terminal - drawn at the right
     * hand end of the file's battery - at the top, and makes the fill climb along what
     * the file calls x.
     *
     * [charging] chooses which of the two marks is drawn, and with it how the charge under
     * it is painted. See [CHARGING_FILL_ALPHA].
     */
    private fun drawBattery(canvas: Canvas, box: RectF, level: Float, charging: Boolean) {
        val mark = (if (charging) chargingShell else shell) ?: return
        val scale = minOf(box.width() / MARK_W, box.height() / MARK_H)
        if (scale <= 0f) return
        val wide = MARK_W * scale
        val tall = MARK_H * scale
        mark.setTintList(
            android.content.res.ColorStateList.valueOf(palette.onAccent()))

        val saved = canvas.save()
        canvas.translate(box.centerX() - wide / 2f, box.centerY() - tall / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-MARK_L, -MARK_T)
        canvas.rotate(-90f, PIVOT, PIVOT)

        // The charge, then the outline over it. Under rather than over, because the mark
        // that goes on top has a plug lying across the well: a level painted over that
        // would swallow the plug whole at anything above a quarter full, and the tile
        // would say "charging" by showing a battery with nothing in it to say so.
        val span = WELL.width()
        if (level > 0f) {
            fillPaint.alpha = if (charging) CHARGING_FILL_ALPHA else 255
            canvas.drawRect(WELL.left, WELL.top, WELL.left + span * level, WELL.bottom, fillPaint)
        }
        // The climbing column, drawn only above the level: the two are neighbours rather
        // than layers now that neither of them is opaque, and a pale column drawn under a
        // half-pale one would make the charge itself the darkest part of the cell.
        if (sweep > 0f) {
            val ghost = level + (1f - level) * sweep
            fillPaint.alpha = GHOST_ALPHA
            canvas.drawRect(
                WELL.left + span * level, WELL.top,
                WELL.left + span * ghost, WELL.bottom, fillPaint)
        }
        fillPaint.alpha = 255

        mark.setBounds(0, 0, VIEWPORT, VIEWPORT)
        mark.draw(canvas)
        canvas.restoreToCount(saved)
    }

    /**
     * The size at which a line of this paint stands exactly [room] tall.
     *
     * A face's height is a fixed multiple of its size, so this is one measurement and a
     * ratio rather than a search. Leaves the paint as it found it.
     */
    private fun sizeForHeight(paint: Paint, room: Float): Float {
        val was = paint.textSize
        val probe = if (was > 0f) was else sp(PROBE_SP)
        paint.textSize = probe
        val tall = paint.descent() - paint.ascent()
        paint.textSize = was
        return if (tall <= 0f) probe else probe * room / tall
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {

        /** The empty cell of the Windows Phone icon pack, used as the container it is. */
        private const val MARK_ASSET = "custom_icons_8/appbar.battery.0.svg"

        /** The same cell with a plug across it, for while the charge is going up. */
        private const val CHARGING_ASSET = "custom_icons_8/appbar.battery.charging.svg"

        /** The square the pack is drawn on, and the middle of it that the turn is about. */
        private const val VIEWPORT = 76
        private const val PIVOT = VIEWPORT / 2f

        /**
         * The hollow inside the outline, in the asset's own coordinates.
         *
         * The one thing about the file that is written down here rather than read out of
         * it: `appbar.battery.0` is an outer body with an inner rectangle cut out of it by
         * the winding rule, and this is that rectangle. If the asset is ever replaced with
         * a battery of another shape, this is the line that has to follow it.
         */
        private val WELL = RectF(24f, 32f, 52f, 44f)

        // What the mark actually covers once it is upright, in the same coordinates: the
        // body and its terminal, and nothing of the empty square around them. Fitted to
        // this rather than to the viewport, or a battery on a tile would be drawn at
        // half the size of the icon beside it for want of the padding its author left.
        //
        // The battery alone, in both states. The charging mark is the same battery with a
        // plug added, and fitting *that* to its own extent would have shrunk the cell to
        // make room for the plug - so the plug hangs off the box instead, which is what
        // keeps the battery still when the charger goes in.
        private const val MARK_L = 28f
        private const val MARK_T = 17f
        private const val MARK_W = 20f
        private const val MARK_H = 39f

        /**
         * How far the plug reaches past the battery, in the same coordinates.
         *
         * Its prongs are drawn at `y = 24` in the file where the body starts at 28, and
         * the quarter turn puts that four units off the battery's left flank. Kept clear
         * at every level of charge - see the placement in `onDraw` for why it is not left
         * to whether there is a plug on the tile at this moment.
         */
        private const val PLUG_OVERHANG = 4f

        /** How much of a cell the upright battery stands. */
        private const val MARK_CELL_SHARE = 0.558f

        /** Between the cell and the figure beside it. */
        private const val GAP_DP = 10

        /**
         * How much wider than it is tall a tile has to be to carry the figure, where the
         * wall has not said how big a cell is.
         *
         * Stands in for "more than one cell across" on a surface with no grid behind it.
         * Comfortably over one, so a square tile is never mistaken for a wide one by a
         * pixel of padding.
         */
        private const val ASPECT_FOR_FIGURE = 1.4f

        /** Kept clear of the tile's edges, as the type on any other face is. */
        private const val EDGE_DP = 10

        // The figure, sized as every reading on the wall is: a share of one cell, capped
        // at a ceiling that rarely binds. TileView.sizeAsNumber and WeatherFaceView carry
        // the same two - a battery whose figure did not match the clock's beside it would
        // be the wall set by two different hands.
        private const val FIGURE_MAX_SP = 80f
        private const val FIGURE_CELL_SHARE = 0.4f

        /** And never more of a short tile than this, whatever the cell says. */
        private const val FIGURE_HEIGHT_SHARE = 0.62f

        /** Set with the digits, in the same paint. See [figurePaint]. */
        private const val UNIT = "%"

        /** However little room there is, what is in it is still meant to be read. */
        private const val MIN_SP = 9f

        /** Any size at all, for the one measurement [sizeForHeight] scales from. */
        private const val PROBE_SP = 20f

        /** One climb of the charging column, pause included. */
        private const val SWEEP_MS = 1800L

        /** How much of that is the climb; the rest is the pause at the top. */
        private const val CLIMB_SHARE = 0.78f

        /**
         * How strongly the climbing column is drawn.
         *
         * Faint enough that the part underneath is unmistakably where the charge actually
         * is, strong enough to be seen moving out of the corner of an eye.
         */
        private const val GHOST_ALPHA = 90

        /**
         * How strongly the charge is drawn while the plug is across it.
         *
         * Off charge the level is solid, because there is nothing in the well but the
         * level. On charge there is a plug lying in it, drawn in the same white, and a
         * solid level would simply absorb it - so the level steps back and the plug stands
         * over it at full strength. Three readable weights in the one cell: the charge,
         * the column climbing above it, and the plug.
         */
        private const val CHARGING_FILL_ALPHA = 165
    }
}
