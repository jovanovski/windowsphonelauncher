package rocks.gorjan.gokixp.apps.cortana

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.cos

/**
 * Cortana herself: two rings that breathe, and tumble.
 *
 * Microsoft drew the assistant as a circle rather than as a face, and then made the circle
 * the only thing on the screen that moves. That is the whole design. A still ring is a
 * loading spinner that has stopped; a ring that is quietly alive is something waiting to
 * be asked.
 *
 * The mark is the logo: a solid ring with a wider, dimmer halo hugging it - see
 * `wp81_glyph_cortana`, which is the same shape held still. Two things happen to it, on
 * one clock:
 *
 *  - **the breath.** The solid ring swells and shrinks by about seven percent, and the
 *    halo's outer edge moves the *opposite* way, so the band between them opens and closes
 *    rather than the whole mark scaling. The halo's inner edge is always exactly the ring's
 *    outer edge: as the ring grows it eats into its own halo, which is what stops the
 *    breath reading as a zoom.
 *  - **the tumble.** Once a breath, at the moment the ring is at its smallest, the two of
 *    them turn a half revolution in three dimensions about *perpendicular* axes - the ring
 *    about the vertical, the halo about the horizontal - and pass edge-on at the same
 *    instant. For about a fifth of a second the mark is a vertical sliver crossed by a
 *    horizontal one, which reads as a wireframe sphere turning over, and then it is two
 *    circles again. It is the most characteristic thing the real animation does, and it is
 *    what a ring does instead of having a face to change.
 *
 * Every number below - the two radii, the halo's 62% weight, the seven percent breath, the
 * 3.2 second cycle, the 0.55 second turn - is measured off Microsoft's own calm-state
 * animation frame by frame rather than guessed at.
 *
 * A rotated circle projects to an ellipse of the same width and `cos` times the height,
 * which is the whole of the three-dimensional part: there is no matrix and no camera here,
 * only two ovals whose radii are being multiplied by a cosine.
 *
 * Drawn rather than played as frames. The real thing shipped as sprite sheets per state,
 * which is the right answer when the artwork is fixed and the wrong one here - the mark
 * takes the phone's accent, and twenty accents times two hundred frames is a lot of
 * bitmaps to ship in order to draw two ovals.
 */
@SuppressLint("ViewConstructor")
class CortanaRingView(
    context: Context,
    @param:ColorInt private var accent: Int
) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Reused per frame: two ovals a frame, sixty frames a second, is not worth allocating. */
    private val oval = RectF()

    /**
     * Where the cycle started.
     *
     * Moved rather than added to by [acknowledge], which restarts the cycle so that a
     * tumble happens now - see there.
     */
    private var startedAt = SystemClock.uptimeMillis()

    /** When a question was last sent, or 0. See [acknowledge]. */
    private var acknowledgedAt = 0L

    /** Whether the mark is on screen and worth the frames. See [onDraw]. */
    private var running = false

    /** The outer extent everything below is a fraction of. Set from the view's size. */
    private var extent = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /**
     * The mark stops while it cannot be seen.
     *
     * Leaving a program minimises its window rather than closing it - see
     * [rocks.gorjan.gokixp.wp81.WP81Program] - so without this, a Cortana the user opened
     * once and walked away from goes on asking for a frame sixty times a second behind
     * whatever they are actually doing.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == running) return
        running = isVisible
        if (isVisible) invalidate()
    }

    fun applyAccent(@ColorInt colour: Int) {
        accent = colour
        rebuildGlow()
        invalidate()
    }

    /**
     * A question just left: she tumbles now rather than waiting her turn.
     *
     * The cycle is restarted rather than a separate flourish being run, because the phone
     * only ever had the one gesture and this is what it is for - the mark turning over is
     * already what it does when something has happened. It also brightens for a moment,
     * which fills the beat before the browser opens: a screen that did nothing at all in
     * that beat reads as a tap that missed.
     */
    fun acknowledge() {
        val now = SystemClock.uptimeMillis()
        startedAt = now
        acknowledgedAt = now
        invalidate()
    }

    /**
     * Whether she is listening to the room, which the mark says by never stopping.
     *
     * The calm state turns over once every three seconds and is still between times. This
     * one turns continuously - the cycle is short enough that a tumble ends as the next
     * begins - which is the difference between a thing that is waiting and a thing that is
     * working. Shazam's own listening state does the same, and so did Cortana's.
     *
     * The clock is restarted rather than left running, so the change of pace begins at the
     * start of a turn instead of halfway through whatever the mark was already doing.
     */
    fun setListening(listening: Boolean) {
        if (this.listening == listening) return
        this.listening = listening
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private var listening = false

    /**
     * Square, and sized off the width it is offered.
     *
     * Given as a share of the screen rather than in dp because this is the one thing on the
     * page whose size is the composition: the phone put a ring of about a fifth of the
     * width a quarter of the way down, and a fixed 96dp ring would be that on one handset
     * and a bead on a tablet. Clamped at both ends so neither extreme gets silly.
     *
     * The view is larger than the mark inside it - see [GLOW_REACH] - so the share here is
     * of the whole haze, not of the circle somebody would measure.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val offered = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val side = (offered * SIZE_SHARE)
            .coerceIn(MIN_SIDE_DP * density, MAX_SIDE_DP * density)
            .toInt()
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        extent = (minOf(w, h) / 2f) / (1f + GLOW_REACH)
        rebuildGlow()
    }

    private fun rebuildGlow() {
        if (extent <= 0f || width == 0) return
        // Faint. The real animation has almost none - its edge falls off over a pixel or
        // two - but that one was drawn on flat black, and this one stands in fog: without a
        // little bloom the mark reads as pasted onto the fog rather than as the thing
        // lighting it.
        glowPaint.shader = RadialGradient(
            width / 2f, height / 2f, extent * (1f + GLOW_REACH),
            intArrayOf(
                withAlpha(accent, 44),
                withAlpha(accent, 22),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (extent <= 0f) return
        val now = SystemClock.uptimeMillis()
        val cx = width / 2f
        val cy = height / 2f

        // One clock for both motions, because in the original they are one motion: the
        // tumble happens at the moment the breath bottoms out.
        val cycleLength = if (listening) LISTENING_CYCLE_SECONDS else CYCLE_SECONDS
        val tumbleLength = if (listening) LISTENING_TUMBLE_SECONDS else TUMBLE_SECONDS
        val cycle = ((now - startedAt) / 1000f) % cycleLength

        // -1 at the start of a cycle, where the ring is at its smallest and the halo at its
        // widest, and +1 half a cycle later.
        val breath = -cos(cycle / cycleLength * TWO_PI).toFloat()

        // How far through a tumble: 0 as it starts, 1 once it is over. Eased at both ends,
        // so the mark is entered and left at rest rather than snapping into rotation. While
        // listening the tumble fills the whole cycle, so there is no "at rest" to reach.
        val turn = if (cycle > tumbleLength) 1f else smoothstep(cycle / tumbleLength)

        // A half turn is all it takes: a ring at 180 degrees is the ring it started as, so
        // there is nothing to be had from going the rest of the way round. The absolute
        // value is what keeps the ellipse from inverting past the quarter turn - a ring has
        // no back, so both halves of the turn look the same.
        val squash = abs(cos(turn * Math.PI)).toFloat()
            // Never quite zero: at dead edge-on an oval of no depth is nothing at all, and
            // the original keeps a sliver at the crossing rather than blinking out.
            .coerceAtLeast(EDGE_ON_FLOOR)

        // What is left of an acknowledgement, falling off the way a struck thing does.
        val burst = if (acknowledgedAt == 0L) 0f else {
            val gone = (now - acknowledgedAt) / ACKNOWLEDGE_MS.toFloat()
            if (gone >= 1f) {
                acknowledgedAt = 0L
                0f
            } else (1f - gone) * (1f - gone)
        }

        val ringMid = extent * RING_MID * (1f + BREATH_DEPTH * breath)
        val ringThickness = extent * RING_THICKNESS * (1f + BREATH_DEPTH * breath)
        val ringOuter = ringMid + ringThickness / 2f

        // The halo runs from the ring's outer edge out to a boundary that barely moves, so
        // the band closes as the ring swells rather than the pair scaling together.
        val haloOuter = extent * HALO_OUTER * (1f - HALO_BREATH_DEPTH * breath)
        val haloThickness = haloOuter - ringOuter
        val haloMid = (ringOuter + haloOuter) / 2f

        glowPaint.alpha = (255 * (GLOW_ALPHA + burst * 0.4f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, extent * (1f + GLOW_REACH), glowPaint)

        // The halo turns about the horizontal axis: it loses height and keeps its width.
        if (haloThickness > 0.5f) {
            haloPaint.color = accent
            haloPaint.alpha = (255 * (HALO_ALPHA + burst * 0.25f)).toInt().coerceIn(0, 255)
            haloPaint.strokeWidth = haloThickness
            oval.set(
                cx - haloMid, cy - haloMid * squash,
                cx + haloMid, cy + haloMid * squash
            )
            canvas.drawOval(oval, haloPaint)
        }

        // The ring turns about the vertical axis: it loses width and keeps its height. The
        // two of them crossing edge-on at the same instant is the whole gesture.
        ringPaint.color = accent
        ringPaint.alpha =
            (255 * (RING_ALPHA + burst * (1f - RING_ALPHA))).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = ringThickness
        oval.set(
            cx - ringMid * squash, cy - ringMid,
            cx + ringMid * squash, cy + ringMid
        )
        canvas.drawOval(oval, ringPaint)

        if (running) postInvalidateOnAnimation()
    }

    /** Smooth at both ends, so the turn is entered and left at rest. */
    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val TWO_PI = 2.0 * Math.PI

        /** The whole haze as a share of the width offered. See [onMeasure]. */
        const val SIZE_SHARE = 0.41f
        const val MIN_SIDE_DP = 128f
        const val MAX_SIDE_DP = 260f

        /**
         * The mark's proportions, as fractions of [extent].
         *
         * Measured across the middle of Microsoft's own animation: at rest the solid ring
         * runs from 0.63 to 0.76 of the outer extent and the halo from 0.78 out to 1.0,
         * which is the same shape as the logo - see `wp81_glyph_cortana`.
         */
        const val RING_MID = 0.735f
        const val RING_THICKNESS = 0.140f
        const val HALO_OUTER = 0.975f

        /** The halo's weight against the ring: 62%, off the original's own pixels. */
        const val HALO_ALPHA = 0.62f
        const val RING_ALPHA = 0.94f

        const val CYCLE_SECONDS = 3.18f

        /** How far the ring swells either side of its mean over a breath. */
        const val BREATH_DEPTH = 0.069f

        /** The halo's outer edge moves the other way, and much less. */
        const val HALO_BREATH_DEPTH = 0.026f

        /** A half turn, measured off the original: about a third of a second either side. */
        const val TUMBLE_SECONDS = 0.55f

        /**
         * The same two while she is listening.
         *
         * Equal, so one turn runs straight into the next with no pause between them, and
         * short enough that the mark reads as busy rather than as merely awake.
         */
        const val LISTENING_CYCLE_SECONDS = 1.15f
        const val LISTENING_TUMBLE_SECONDS = 1.15f

        /** What is left of a ring at dead edge-on. See where it is used. */
        const val EDGE_ON_FLOOR = 0.012f

        const val GLOW_REACH = 0.5f
        const val GLOW_ALPHA = 0.5f

        const val ACKNOWLEDGE_MS = 700L
    }
}

/** [colour] at [alpha], keeping its hue. */
@ColorInt
internal fun withAlpha(@ColorInt colour: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

/**
 * [colour] pulled [amount] of the way towards white.
 *
 * Towards white rather than up in value, because the accents this has to work with include
 * ones that are already at full brightness - a yellow cannot be made any more yellow.
 */
@ColorInt
internal fun brighten(@ColorInt colour: Int, amount: Float): Int {
    val f = amount.coerceIn(0f, 1f)
    fun lift(channel: Int) = (channel + (255 - channel) * f).toInt().coerceIn(0, 255)
    return Color.argb(
        Color.alpha(colour),
        lift(Color.red(colour)),
        lift(Color.green(colour)),
        lift(Color.blue(colour))
    )
}
