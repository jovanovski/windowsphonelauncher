package rocks.gorjan.gokixp.wp81.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.ColorInt
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * What the keys are made of.
 *
 * The keyboard has always worn the phone's own colours, and that is not a theme - it is the
 * absence of one. The accent is chosen once in Settings > theme and everything on the phone
 * follows it, the keyboard included, which is the whole argument of [KeyboardAppearance] and
 * is why there was nothing to choose here.
 *
 * There is now exactly one thing worth choosing, and it is not another accent: a keyboard
 * that is not a Windows Phone keyboard at all. Windows 98's buttons were not coloured
 * rectangles, they were *raised* ones - two bands of light down the top and left, two of
 * shadow down the bottom and right, on the one warm neutral every machine of that decade was
 * made of - and pressing one did not light it up, it turned the bevel inside out so the
 * button appeared to go into the screen. None of that can be said in a palette, which is why
 * this is a theme and not one more accent colour.
 *
 * Two parts, and they are deliberately separate:
 *
 *  - [palette] answers for everything that is only a colour. A theme hands back the
 *    [WP81Palette] the whole keyboard is painted from, so the suggestion bar, the emoji panel,
 *    the clipboard list and every popup follow it without knowing a theme exists.
 *  - [bevelled] answers for the one thing a palette cannot carry, which is the shape of a key.
 *    See [KeyView.drawFace] and [Win98.bevel].
 *
 * The keyboard's own setting, kept in the keyboard's own file and read in the keyboard's own
 * process - see [WP81Settings.getWP81KeyboardTheme]. It is stored as [id] rather than as an
 * ordinal, so the numbering of this enum is free to change and a value written by a later
 * version falls back to [PHONE] rather than to whatever now sits at that index.
 */
enum class KeyboardTheme(
    /** What is written to the settings file. Never change one of these. */
    val id: String,
    /** What the settings page calls it. */
    val title: String,
    /** And what the settings page says about it, underneath. */
    val note: String
) {

    PHONE(
        "phone",
        "the phone's own",
        "flat keys in the accent from Settings > theme, and the background to match. " +
            "a pressed key lights up."
    ),

    WIN98(
        "win98",
        "Windows 98",
        "beige keys with a raised edge, in Tahoma. a pressed key goes into the screen " +
            "instead of lighting up, and the accent is the navy blue that version of " +
            "Windows selected things in."
    );

    /**
     * Whether a key is a raised button rather than a flat fill.
     *
     * Asked as a question about the theme rather than tested against [WIN98] at each of the
     * half dozen places that care, so that a second bevelled theme - Windows 95 and 98 drew
     * the same button - is one entry here and nothing else.
     */
    val bevelled: Boolean get() = this == WIN98

    /**
     * The colours to paint in, given the phone's own.
     *
     * [PHONE] hands back what it was given, which is not a special case being tolerated - it
     * is the theme. The phone's keyboard is the phone's colours, and the accent it follows is
     * one the user has already chosen somewhere else.
     */
    fun palette(phone: WP81Palette): WP81Palette = when (this) {
        PHONE -> phone
        WIN98 -> Win98.PALETTE
    }

    companion object {

        /**
         * The theme [id] names, or [PHONE] for anything else.
         *
         * Anything else covers a fresh install, a settings file written by a later version
         * that knows a theme this one does not, and a restored backup from either.
         */
        fun of(id: String?): KeyboardTheme = entries.firstOrNull { it.id == id } ?: PHONE
    }
}

/**
 * The beige, and the two bands of it that make a button.
 *
 * Windows 95 through 2000 drew every button, every toolbar and every dialog out of one
 * colour and four shades of it, and the shades are not decoration: they are a light source in
 * the top-left corner of the screen, applied consistently enough that a rectangle with white
 * along its top reads as sticking out and the same rectangle with black along its top reads
 * as pushed in. That is the entire mechanism, and it is why a pressed key here does not need
 * to change colour to say it has been pressed.
 *
 * The *relationships* are the system's - `3DLIGHT` halfway between the face and white,
 * `3DSHADOW` two thirds of the face, `3DDKSHADOW` as good as black - and they are what the
 * eye is actually reading, so they are kept exactly. The face they are worked out from is
 * not `#C0C0C0`: it is the warm grey of the plastic those machines were moulded in, which is
 * what people picture when they picture one, and it is the colour this theme was asked for
 * in. Every band is derived from it rather than copied from the system palette, because a
 * neutral grey highlight over a warm face is the one thing that would make it look like a
 * mistake instead of a choice.
 */
internal object Win98 {

    /**
     * The face of every key, and the ground they sit on.
     *
     * `COLOR_3DFACE`'s part, in the beige of the case rather than the grey of the screen.
     */
    @ColorInt
    const val FACE = 0xFFD3CEC7.toInt()

    /**
     * `COLOR_3DHIGHLIGHT`: the outer band along the lit edges of a raised button.
     *
     * White, and not tinted with the rest of them. It is not a shade of the face, it is the
     * light falling on it, and Windows used the same white whatever the face was set to.
     */
    @ColorInt
    const val HIGHLIGHT = 0xFFFFFFFF.toInt()

    /** `COLOR_3DLIGHT`: the inner band, halfway from the face to the highlight. */
    @ColorInt
    const val LIGHT = 0xFFE9E7E3.toInt()

    /** `COLOR_3DSHADOW`: the inner band along the shaded edges, and a dimmed label. */
    @ColorInt
    const val SHADOW = 0xFF8D8985.toInt()

    /** `COLOR_3DDKSHADOW`: the outer band, and the frame around a default button. */
    @ColorInt
    const val DARK_SHADOW = 0xFF0A0A0A.toInt()

    /**
     * `COLOR_HIGHLIGHT`. What Windows selected text, menu items and list rows in.
     *
     * Left at the navy it always was. It is the one colour here that was never a shade of the
     * face - it had to stand against whatever the face happened to be - and against this
     * beige it stands exactly as well as it did against the grey.
     */
    @ColorInt
    const val SELECTION = 0xFF000080.toInt()

    /** What is written on a key. Black, as everything in that shell was. */
    @ColorInt
    const val INK = 0xFF000000.toInt()

    /**
     * The whole keyboard's colours, as a [WP81Palette].
     *
     * Everything on this keyboard already takes its colours from a palette, so a theme that
     * hands over a different one repaints the suggestion bar, the emoji panel, the clipboard
     * list, the alternates row and the key preview without any of them being told that themes
     * exist. That is the reason this is expressed as a palette at all rather than as a set of
     * constants the keys read directly.
     *
     * The accent is the selection navy, which is the right answer rather than a convenient
     * one: the accent is used here for the word under a finger on the suggestion bar, the
     * chosen cell of the alternates row and the emoji under a thumb, and every one of those
     * is the thing Windows would have drawn in `COLOR_HIGHLIGHT` with white on top of it.
     *
     * `isDark` is false, which is what tells the system to draw its own chevron and globe in
     * the strip under the keyboard in black rather than white - see
     * `WP81KeyboardService.paintNavigationBar`. On a beige this pale, white ones would be
     * gone.
     */
    val PALETTE = WP81Palette(
        accent = SELECTION,
        background = FACE,
        foreground = INK,
        // Not the palette's usual translucent black: on this face a dimmed label is
        // `COLOR_3DSHADOW`, which is the one Windows used for exactly that.
        foregroundSubtle = SHADOW,
        inactive = Color.argb(51, 0, 0, 0),
        chrome = FACE,
        onChrome = INK,
        isDark = false
    )

    /**
     * How thick one band of a bevel is.
     *
     * Windows drew it in single pixels, on a screen whose pixels were a third of a millimetre
     * across. Copying the number rather than the size would produce a hairline nobody could
     * see on a phone, so it is copied in dp and comes out at about the size it looked - and
     * floored at a whole pixel, because a bevel that lands on half a pixel is a smear rather
     * than an edge.
     */
    fun band(density: Float): Float = maxOf(1f, BAND_DP * density)

    /**
     * Draws a button's edge inside the given rectangle: raised, or pressed in.
     *
     * Two bands a side, four colours, and [sunken] swaps the pairs over. That swap is the
     * whole of what a press looks like in this theme - the face does not change colour, and
     * the light simply moves to the other corner of the button.
     *
     * The caller has already filled the face; this only draws the edge over it. [paint] is
     * borrowed and its colour is left wherever the last band put it, so it must not be the
     * paint holding the face's fill.
     */
    fun bevel(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        paint: Paint,
        band: Float,
        sunken: Boolean
    ) {
        edge(
            canvas, left, top, right, bottom, paint, band,
            lit = if (sunken) DARK_SHADOW else HIGHLIGHT,
            shaded = if (sunken) HIGHLIGHT else DARK_SHADOW
        )
        edge(
            canvas, left + band, top + band, right - band, bottom - band, paint, band,
            lit = if (sunken) SHADOW else LIGHT,
            shaded = if (sunken) LIGHT else SHADOW
        )
    }

    /**
     * One band: [lit] along the top and left, [shaded] along the bottom and right.
     *
     * The shaded pair is drawn second so that it owns the two corners where the bands meet.
     * Either choice leaves a corner belonging to one side or the other - Windows mitred them,
     * which at a single pixel was a decision about one pixel - and the shaded corner is the
     * one that reads correctly, because the eye takes the darker mark as the edge furthest
     * from the light.
     */
    private fun edge(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        paint: Paint,
        band: Float,
        @ColorInt lit: Int,
        @ColorInt shaded: Int
    ) {
        if (right - left <= band * 2 || bottom - top <= band * 2) return
        paint.color = lit
        canvas.drawRect(left, top, right, top + band, paint)
        canvas.drawRect(left, top, left + band, bottom, paint)
        paint.color = shaded
        canvas.drawRect(left, bottom - band, right, bottom, paint)
        canvas.drawRect(right - band, top, right, bottom, paint)
    }

    /** See [band]. A pixel of 1998 was about this much of a phone. */
    private const val BAND_DP = 1.5f
}
