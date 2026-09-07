package rocks.gorjan.gokixp.apps.cortana

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import rocks.gorjan.gokixp.wp81.WP81Palette
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The fog Cortana stands in.
 *
 * Every other page in this shell is a flat colour, deliberately - Metro's whole argument
 * was that a page is a page and not a surface. Cortana was the one screen Microsoft
 * exempted from that: a dark, grainy haze with slow light moving through it, which is
 * the only piece of atmosphere anywhere on the phone. It is worth having exactly because
 * it is the exception. This is the one place the phone is pretending to be somewhere
 * rather than showing you something.
 *
 * Four things, drawn back to front:
 *
 *  - the page's own ground, so a Light phone gets a light fog rather than a black screen;
 *  - **drifting banks**, five soft blobs on slow independent paths, each a barely-there
 *    lift off the ground - this is the smoke, and the reason it reads as smoke rather than
 *    as a gradient is that no two of them are ever in the same place twice;
 *  - **the wash**, a wide accent haze up where the ring sits, so the fog looks lit by her
 *    rather than tinted by hand;
 *  - **grain**, a fixed speckle over the lot. The screenshot this was drawn from is full
 *    of it, and without it five overlapping gradients on a modern panel band visibly -
 *    the noise is what breaks the bands up, which is the same job film grain has always
 *    had.
 *
 * The banks move on periods of forty to seventy seconds. That is slow enough that nothing
 * on screen is ever *seen* to move, and it still looks different every time the app is
 * opened, which is the whole point of it being animated at all.
 */
@SuppressLint("ViewConstructor")
class CortanaBackdropView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    /**
     * One drifting bank of fog.
     *
     * The path is a Lissajous - two sines at unrelated periods - rather than a circle,
     * because a blob going round a circle is a blob going round a circle however slowly
     * it does it. Crossing periods never close, so the five of them never fall back into
     * an arrangement the eye has already seen.
     */
    private class Bank(
        /** Where its path is centred, as a share of the view. */
        val homeX: Float,
        val homeY: Float,
        /** How far it wanders, as a share of the view's smaller edge. */
        val driftX: Float,
        val driftY: Float,
        /** Seconds per lap of each axis. Deliberately co-prime-ish. See the class comment. */
        val periodX: Float,
        val periodY: Float,
        /** Where on its path it starts, so they do not all set off together. */
        val phase: Float,
        /** Its own size, as a share of the view's larger edge. */
        val radius: Float,
        /** How much of the lift this one carries. */
        val weight: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()
    }

    /**
     * Fixed, not random.
     *
     * A generated set would be a different fog per launch, which sounds better than it is:
     * the arrangement below puts weight low and left, keeps the middle clear for the ring
     * and the greeting, and leaves one bank high on the right so the screen is not
     * symmetrical. Randomising it would throw that away a good share of the time.
     */
    private val banks = listOf(
        Bank(0.22f, 0.30f, 0.16f, 0.11f, 61f, 43f, 0.0f, 0.62f, 1.00f),
        Bank(0.78f, 0.18f, 0.13f, 0.15f, 47f, 67f, 1.7f, 0.50f, 0.72f),
        Bank(0.50f, 0.72f, 0.19f, 0.09f, 71f, 53f, 3.1f, 0.70f, 0.85f),
        Bank(0.12f, 0.86f, 0.11f, 0.13f, 41f, 59f, 4.6f, 0.55f, 0.68f),
        Bank(0.88f, 0.62f, 0.14f, 0.12f, 57f, 73f, 2.3f, 0.48f, 0.60f)
    )

    /** The accent haze behind the ring. */
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The speckle over everything. */
    private val grainPaint = Paint()
    private var grain: Bitmap? = null

    private val startedAt = SystemClock.uptimeMillis()
    private var running = false

    /**
     * Where the ring is, as a share of the view's height, so the wash sits under it.
     *
     * Told rather than assumed: the greeting under the ring is one line or two depending
     * on which one came up, and the column that holds both is centred as a whole, so the
     * ring is not at a fixed height.
     */
    private var washCentreY = 0.34f

    init {
        // The fog is the one thing here that would be improved by being smoothed, and a
        // layer of its own is what lets the grain sit over the banks rather than under
        // each of them.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        applyPalette(palette)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    /** As with the ring: a minimised program is not worth a frame. See [CortanaRingView]. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == running) return
        running = isVisible
        if (isVisible) invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        buildShaders()
        invalidate()
    }

    /** Puts the accent haze where the ring actually ended up. See [washCentreY]. */
    fun setRingCentre(fraction: Float) {
        val clamped = fraction.coerceIn(0.1f, 0.9f)
        if (kotlin.math.abs(clamped - washCentreY) < 0.005f) return
        washCentreY = clamped
        buildShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    private fun buildShaders() {
        if (width == 0 || height == 0) return
        val short = minOf(width, height).toFloat()
        val long = maxOf(width, height).toFloat()

        // Which way the fog goes. On a dark phone it is light coming up out of the black,
        // which is what the screenshot shows; on a light one the same lift would be
        // invisible, so the banks are shadow instead. Either way it is the ground moving a
        // little away from itself rather than a second colour laid on top - fog that can be
        // named as a colour has stopped being fog.
        val lift = if (palette.isDark) Color.WHITE else Color.BLACK
        // Warmed with a trace of the accent, so the haze belongs to the phone's colour
        // without ever being the colour.
        val tinted = blend(lift, palette.accent, 0.35f)

        for (bank in banks) {
            val r = long * bank.radius
            val peak = (BANK_ALPHA * bank.weight * 255).toInt().coerceIn(0, 255)
            // Centred on the origin and moved by the matrix each frame: rebuilding a
            // gradient sixty times a second to shift it a pixel is a lot of allocation for
            // fog that takes a minute to cross the screen.
            bank.paint.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(
                    withAlpha(tinted, peak),
                    withAlpha(tinted, (peak * 0.45f).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        washPaint.shader = RadialGradient(
            width / 2f, height * washCentreY, short * WASH_RADIUS,
            intArrayOf(
                withAlpha(palette.accent, (WASH_ALPHA * 255).toInt()),
                withAlpha(palette.accent, (WASH_ALPHA * 0.4f * 255).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        if (grain == null) grain = makeGrain()
        grainPaint.shader = BitmapShader(grain!!, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grainPaint.alpha = (GRAIN_ALPHA * 255).toInt()
    }

    /**
     * A tile of speckle.
     *
     * Small and repeated: a full-screen noise bitmap is several megabytes to say something
     * a 64-pixel square says just as well once it is this faint. The seed is fixed so the
     * pattern is the same every launch - nobody will ever see it, but a grain that changed
     * under a screenshot would be a diff in every screenshot test this app ever has.
     *
     * White either way, laid on at a few percent. On a dark phone it lifts; on a light one
     * the same speckle is invisible against white, which is correct - a light Cortana
     * should not look like a photocopy.
     */
    private fun makeGrain(): Bitmap {
        val random = Random(GRAIN_SEED)
        val size = GRAIN_TILE
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            val v = random.nextInt(256)
            pixels[i] = Color.argb(v, 255, 255, 255)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val short = minOf(width, height).toFloat()
        val long = maxOf(width, height).toFloat()

        for (bank in banks) {
            val x = width * bank.homeX +
                short * bank.driftX * sin(((t / bank.periodX) * TWO_PI + bank.phase).toDouble())
                    .toFloat()
            val y = height * bank.homeY +
                short * bank.driftY * cos(((t / bank.periodY) * TWO_PI + bank.phase).toDouble())
                    .toFloat()
            bank.matrix.reset()
            bank.matrix.setTranslate(x, y)
            bank.paint.shader?.setLocalMatrix(bank.matrix)
            canvas.drawCircle(x, y, long * bank.radius, bank.paint)
        }

        canvas.drawPaint(washPaint)
        canvas.drawPaint(grainPaint)

        // Thirty a second. The banks take the better part of a minute to cross the screen,
        // so half the frames is a saving nobody can see - and this is five full-screen
        // gradients a pass, which is the one thing on this page worth being careful with.
        if (running) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val TWO_PI = 2.0 * Math.PI

        /** How far the strongest bank lifts the ground. Very little, by design. */
        const val BANK_ALPHA = 0.085f

        const val WASH_ALPHA = 0.13f

        /** The accent haze's reach, as a share of the screen's shorter edge. */
        const val WASH_RADIUS = 0.85f

        const val GRAIN_ALPHA = 0.035f
        const val GRAIN_TILE = 64
        const val GRAIN_SEED = 8_1L

        const val FRAME_MS = 33L
    }
}

/** [a] mixed [amount] of the way towards [b]. */
private fun blend(a: Int, b: Int, amount: Float): Int {
    val f = amount.coerceIn(0f, 1f)
    fun mix(x: Int, y: Int) = (x + (y - x) * f).toInt().coerceIn(0, 255)
    return Color.rgb(
        mix(Color.red(a), Color.red(b)),
        mix(Color.green(a), Color.green(b)),
        mix(Color.blue(a), Color.blue(b))
    )
}
