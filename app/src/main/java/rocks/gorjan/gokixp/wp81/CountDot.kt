package rocks.gorjan.gokixp.wp81

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A tile's unread dot, with how many are waiting set into it.
 *
 * The digits are cut out of the dot rather than painted onto it. The dot is the tile's one
 * foreground colour, and what is under it is whatever the tile is - the accent, a colour of
 * its own, or a window onto the wallpaper - so there is no second colour that reads against
 * all of them. A hole does: the number is always exactly the tile.
 *
 * Drawn centred in its bounds, which is where the plain dot sat, so a count moves nothing.
 * One too wide for a circle stretches into a pill to the left, keeping the right-hand edge -
 * the one lined up with the tile's corner - where the circle had it.
 */
class CountDot(typeface: Typeface?, private val diameter: Float) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val pill = RectF()
    private val ink = Rect()

    /** What is set into the dot. Empty draws a plain dot. */
    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    init {
        // Sized by how tall the digits actually stand rather than by the font's nominal
        // size, the same measurement TileView.applyCountSize makes for the full-size count.
        digits.textSize = diameter
        digits.getTextBounds("0", 0, 1, ink)
        if (ink.height() > 0) {
            digits.textSize = diameter * DIGIT_FRACTION * diameter / ink.height()
        }
    }

    fun setColor(color: Int) {
        fill.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val radius = diameter / 2
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val width = if (text.isEmpty()) diameter
            else maxOf(diameter, digits.measureText(text) + diameter * SIDE_FRACTION * 2)
        pill.set(maxOf(b.left.toFloat(), cx + radius - width), cy - radius, cx + radius, cy + radius)

        // Its own layer, so the digits clear the dot and nothing that was drawn before it.
        val layer = canvas.saveLayer(pill, null)
        canvas.drawRoundRect(pill, radius, radius, fill)
        if (text.isNotEmpty()) {
            // Centred on the ink, not the advance: a lone "1" is mostly blank to one side.
            digits.getTextBounds(text, 0, text.length, ink)
            canvas.drawText(text, pill.centerX() - ink.exactCenterX(), cy - ink.exactCenterY(), digits)
        }
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** How tall the digits stand against the dot. */
        const val DIGIT_FRACTION = 0.5f

        /** Room either side of a count wide enough to stretch the dot, against its height. */
        const val SIDE_FRACTION = 0.25f
    }
}
