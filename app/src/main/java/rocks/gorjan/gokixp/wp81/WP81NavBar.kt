package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import rocks.gorjan.gokixp.R

/**
 * The three capacitive keys along the bottom of every Windows Phone screen: back, Start,
 * and search.
 *
 * They are fixed, as the hardware's were, and they are the same three everywhere - on
 * Start, in a folder, with a tile held. A key that turns into a different command
 * depending on the page is a key you have to look at before pressing, and these stand in
 * for keys you could find without looking. On Start back has nowhere to go and does
 * nothing, which is what it did on the phone too. Settings is reached by holding Start,
 * along with the shell's other commands, and the task switcher by holding back - both on
 * the key whose tap already means the nearest thing to them.
 *
 * Commands that belong to what the user is doing - recolouring a tile, filling a folder -
 * are not here: they slide up on [WP81SecondaryBar], which is the app bar WP8.1 put above
 * these keys for exactly that.
 *
 * On real hardware these sat below the display; here they are drawn as a bar the shell
 * reserves space for, which is why the floating-window container's bottom margin is
 * re-based onto [HEIGHT_DP] when the WP8.1 shell is active.
 */
@SuppressLint("ViewConstructor")
class WP81NavBar(
    context: Context,
    private var palette: WP81Palette
) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null

    /** Holding the Start key: the commands that belong to the shell itself. */
    var onStartLongPress: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null

    /**
     * Holding the search key: Cortana, wherever the user is.
     *
     * The one key whose two meanings are not "this screen" and "the shell": the tap is
     * lent to whatever is in front when that screen has a search of its own - the address
     * book, the music library, the app list - and the hold is what it is lent against.
     * Cortana is reachable from inside every one of those without leaving it first, which
     * is the only reason the tap can be given away at all. See [WP81Searchable].
     *
     * Timed longer than the back key's hold, and for the opposite reason. Back means the
     * same thing pressed slowly as pressed quickly, so a hold read early costs nothing;
     * here a hold read early takes somebody who meant to search their contacts out of the
     * address book entirely. See [SEARCH_HOLD_MS].
     */
    var onSearchLongPress: (() -> Unit)? = null


    private val backButton =
        button(R.drawable.wp81_nav_back) { onBack?.invoke() }
    private val startButton = button(R.drawable.wp81_nav_windows) { onStart?.invoke() }
        .apply {
            isLongClickable = true
            setOnLongClickListener {
                // No tick of its own: claiming a long press is what makes the framework
                // give the shell's, and one fired here as well was a second buzz on top of
                // it. See Haptics.
                onStartLongPress?.invoke()
                true
            }
        }
    private val searchButton =
        button(
            R.drawable.wp81_nav_search,
            onHold = { onSearchLongPress?.invoke() },
            holdMs = SEARCH_HOLD_MS
        ) {
            onSearch?.invoke()
        }

    private val allButtons = listOf(backButton, startButton, searchButton)

    /** Whether the strip is wearing the accent. See [setAccented]. */
    private var accented = false

    /** Whether the search key is standing for a search on the screen. See [setSearchOffered]. */
    private var searchOffered = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        // A gap at each end as well as between the keys, so all the gaps are the same
        // size: keys pinned to the corners read as pulled apart, and the outer two sitting
        // in from the edge by as much as they stand off each other is what makes the strip
        // look spaced rather than justified. It also means Start lands in the middle
        // without a case of its own.
        addView(spacer())
        for (key in allButtons) {
            addView(key, LayoutParams(dp(HEIGHT_DP), LayoutParams.MATCH_PARENT))
            addView(spacer())
        }
        applyPalette(palette)
    }

    /** The gap that does the spreading. */
    private fun spacer(): View =
        View(context).apply { layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f) }

    /**
     * The strip swallows whatever misses a key.
     *
     * Without this, a touch that landed on the black between the keys fell through to the
     * desktop underneath, which is still there behind the shell and still watching for the
     * hold that opens its own right-click menu - so holding the bottom bar of a Windows
     * Phone raised a Windows 95 context menu. The bar is a piece of the phone's hardware,
     * not a window onto the desktop: nothing behind it should hear a finger on it.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(
        iconRes: Int,
        /**
         * What holding this key means, or null for a key that only taps.
         *
         * Taken here rather than set by the caller afterwards, because a view has one
         * touch listener and [TiltEffect] is already using it - see [applyHold].
         */
        onHold: (() -> Unit)? = null,
        /** How long that hold has to last. Ignored by a key that only taps. */
        holdMs: Long = HOLD_MS,
        onClick: () -> Unit
    ): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            // Sized explicitly rather than by padding, so the glyph does not shrink when
            // the bar height or the system inset changes.
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Even on all four sides, so the glyph sits in the middle of the strip.
            //
            // It used to have none underneath, on the reasoning that the strip was the
            // bottom edge of the screen and a key with a gap below it would read as a
            // button on a bar rather than as the bottom of the phone. Two things undid
            // that: the strip is no longer the bottom edge - the band the system's gesture
            // bar sits in is below it and is painted to match - and a strip wearing the
            // accent shows exactly where its ground begins and ends, which is what turned
            // nine device-pixels of difference from a subtlety into a mistake.
            val inset = ((HEIGHT_DP - GLYPH_DP) / 2f * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            isClickable = true
            setOnClickListener {
                // The capacitive keys these stand in for buzzed under the finger, and a
                // key drawn on glass has nothing else to confirm it was hit. The shell's
                // one tick, so a key feels like every other thing that answers a touch.
                Haptics.tap(it)
                onClick()
            }
            if (onHold == null) TiltEffect.apply(this) else applyHold(this, holdMs, onHold)
        }

    /**
     * A key that means one thing tapped and another held.
     *
     * Not [View.setOnLongClickListener], which the Start key uses: the framework's long
     * press fires at its own timeout and this key is pressed dozens of times a session as
     * plain back, so the threshold wants to be this shell's own decision rather than the
     * platform's. It is nonetheless set close to it. A second was tried first, on the
     * reasoning that a slow deliberate back press should never open the switcher by
     * accident, and it was simply too long: a hold that outlasts the user's certainty that
     * anything is going to happen reads as the phone having missed the press.
     *
     * The tilt is already on this view's one touch listener, so the timer rides along
     * inside it rather than replacing it - see [TiltEffect.apply].
     *
     * A completed hold spends the gesture: the release that follows is swallowed, so the
     * key does not also go back on the way out of a switcher it has just opened. That is
     * also why the tick is fired by hand here. A view that claims a long press is given
     * the shell's tick by the framework; nothing is being claimed here, because nothing
     * framework-side is involved, so the buzz that says the hold has landed has to be
     * asked for. See [Haptics].
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun applyHold(key: ImageView, holdMs: Long, onHold: () -> Unit) {
        var fired = false
        val timer = Runnable {
            fired = true
            Haptics.tap(key)
            onHold()
        }
        TiltEffect.apply(key) { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    view.postDelayed(timer, holdMs)
                    false
                }
                // A finger that has wandered off the key is on its way somewhere else -
                // the system's own gesture strip is directly below these - and is no
                // longer holding anything.
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.y < 0 ||
                        event.x > view.width || event.y > view.height
                    ) view.removeCallbacks(timer)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(timer)
                    // Consuming the release means View.onTouchEvent never sees it, so the
                    // pressed state it set on the way down is cleared here instead.
                    if (fired) view.isPressed = false
                    fired
                }
                else -> false
            }
        }
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        repaint()
    }

    /**
     * Paints the strip in the accent rather than in the page's own ground.
     *
     * The keys go white on it, the way everything drawn on an accent fill does - see
     * [WP81Palette.onAccent]. Kept as a switch here rather than as a second palette,
     * because the bar is the only thing in the shell that wears it: a palette whose
     * background is the accent would recolour every page that took it.
     */
    fun setAccented(on: Boolean) {
        if (accented == on) return
        accented = on
        repaint()
    }

    /**
     * Lights the search key for a search on the screen behind it.
     *
     * The mark goes to the accent while the other two keys stay in the page's foreground.
     * On a strip that is already wearing the accent there is no accent left to go to, so
     * the mark goes black instead: everything drawn on an accent fill in this shell is
     * white - see [WP81Palette.onAccent] - and the one key that is not white is therefore
     * the one being pointed at. Both ways round it is the same idea, which is that this
     * key is not painted like its neighbours.
     *
     * Said by the shell rather than worked out here, because what the key stands for is a
     * fact about the screen in front - see [WP81Searchable] and `WP81Shell.refreshSearchKey`.
     */
    fun setSearchOffered(on: Boolean) {
        if (searchOffered == on) return
        searchOffered = on
        repaint()
    }

    /**
     * What the strip is wearing, for the band below it to match.
     *
     * The keys stop at the top of the system's gesture bar, but the colour must not: a
     * strip in the accent with a page-coloured band under it is two bars where the phone
     * has one. See `MainActivity.paintWP81NavBar`.
     */
    @ColorInt
    fun groundColour(): Int = if (accented) palette.accent else palette.background

    private fun repaint() {
        setBackgroundColor(groundColour())
        val tint = ColorStateList.valueOf(
            if (accented) palette.onAccent() else palette.foreground
        )
        for (b in allButtons) b.imageTintList = tint
        if (searchOffered) {
            // See setSearchOffered: the accent on an ordinary strip, black on one that is
            // already wearing the accent.
            searchButton.imageTintList =
                ColorStateList.valueOf(if (accented) Color.BLACK else palette.accent)
        }
    }

    companion object {
        const val HEIGHT_DP = 64

        /** Glyph edge. Deliberately large - these are the only navigation on screen. */
        private const val GLYPH_DP = 46

        /** How long the back key has to be held to mean the switcher. See [applyHold]. */
        private const val HOLD_MS = 200L

        /**
         * The same for the search key, which is held for Cortana.
         *
         * Twice as long, because this is the one key where tapping and holding go to
         * different places and the tap is the one being aimed at. Two hundred milliseconds
         * is inside the time an unhurried tap takes, which on back is fine - it goes back
         * either way - and here would be a search key that opened Cortana on every
         * deliberate press. Still short of the platform's own half second: the hold is a
         * command, not a wait.
         */
        private const val SEARCH_HOLD_MS = 400L
    }
}
