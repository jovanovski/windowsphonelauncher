package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * The strip along the bottom of a Metro program, and the only one in the shell.
 *
 * Everything one of these programs can be told to do is here and nothing is anywhere else:
 * no menu bar, no toolbar, no buttons among the content. A row of open rings in the middle
 * for the handful of things reached for most, and behind the dots at the end of that row,
 * a list of the rest in words.
 *
 * Every strip in the phone is this class - the programs' own, the browser's with an address
 * in the middle of it, the music app's, and the shell's over Start (see [WP81SecondaryBar],
 * which is this with a slide bolted on). They were four separate near-identical strips
 * once, which is how they came to disagree about their own black by two units and about
 * whether the theme applied to them at all.
 *
 * The strip has a surface of its own rather than the page's, because it is not the page;
 * but which surface follows the theme, the way the phone's did. Near-black with white
 * rings over a dark page, near-white with black ones over a light page. Fixing it at the
 * dark value left every light-themed program in the shell with a black bar along the
 * bottom of a white page - see [WP81Palette.chrome].
 *
 * ```
 *   |  panels, stacked above the row: a list a program draws itself   |
 *   |  the command list behind the dots, when the strip draws it      |
 *   |     ( o )   [ a stretch view, if there is one ]     ( ... )     |
 * ```
 */
@SuppressLint("ViewConstructor")
open class MetroAppBar(
    context: Context,
    private var palette: WP81Palette
) : LinearLayout(context) {

    /** One command in the list behind the dots. */
    data class Item(val label: String, val action: () -> Unit)

    /**
     * What that list holds, asked for each time it opens rather than set once.
     *
     * A command list is written in the present tense - "archive" or "restore", "draw
     * three" or "draw one" - and which of those it says depends on the state of the thing
     * at the moment the dots are tapped.
     *
     * Left unset where there is nothing behind the dots, and then there are no dots: a
     * strip with one command on it is a strip with one command on it, and an ellipsis that
     * opens nothing is a button that appears to be broken.
     *
     * A program whose list is richer than words - the browser's, with tick boxes and
     * favourites in it - draws its own and reaches the dots through [setOverflow] instead.
     */
    var menu: (() -> List<Item>)? = null
        set(value) {
            field = value
            overflowAction = if (value == null) null else ({ toggleMenu() })
            syncOverflow()
        }

    /** Panels a program stacks above the row: its own lists, and anything that grows. */
    private val panels = LinearLayout(context)
    private val menuColumn = LinearLayout(context)

    /** The row itself, and the rings in it. */
    private val row = FrameLayout(context)
    private val group = LinearLayout(context)

    /**
     * The row when something is standing in the middle of it - see [setStretch].
     *
     * Null while the rings are simply centred, which is every strip but the browser's.
     */
    private var spread: LinearLayout? = null

    private var overflow: View? = null

    /** What the dots do, which is either [menu]'s list or a program's own. */
    private var overflowAction: (() -> Unit)? = null

    /**
     * The commands currently marked as in force, so a change of theme can repaint the rest
     * without flattening them. See [setCommandOn] and [applyPalette].
     */
    private val commandsOn = mutableSetOf<ImageView>()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.chrome)
        // The strip is the bottom of the program: a tap on it is a tap on the strip, not on
        // whatever of the page is behind it.
        isClickable = true

        panels.orientation = VERTICAL
        addView(panels, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        menuColumn.orientation = VERTICAL
        menuColumn.visibility = GONE
        menuColumn.setPadding(0, dp(6), 0, dp(6))
        addView(menuColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        group.orientation = HORIZONTAL
        group.gravity = Gravity.CENTER
        row.addView(group, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(HEIGHT_DP)))
    }

    // ---------------------------------------------------------------- the commands

    /**
     * Adds a ring to the row, and hands it back so the caller can keep hold of it.
     *
     * A command that says something about the state it is in - a mode that is on, a move
     * there is none of left - is one the program has to be able to reach again; see
     * [setCommandOn] and [setCommandEnabled].
     *
     * The glyph may be left out by a program that paints it later out of something it does
     * not know yet: the browser's tabs button carries a count of the open pages.
     */
    fun addCommand(iconAsset: String?, onTap: () -> Unit): ImageView =
        makeCommand(iconAsset, onTap).also { addToGroup(it) }

    /** The same, for a glyph that is a drawable in the shell rather than an icon asset. */
    fun addCommand(@DrawableRes icon: Int, onTap: () -> Unit): ImageView =
        makeCommand(icon, onTap).also { addToGroup(it) }

    /**
     * Builds a ring without putting it on the strip.
     *
     * For a bar whose row is not simply filled once and left: the shell's own strip over
     * Start keeps three commands and shows whichever pair the held tile has - see
     * [WP81SecondaryBar] and [setCommands].
     */
    fun makeCommand(iconAsset: String?, onTap: () -> Unit): ImageView =
        command(iconAsset?.let { SvgIcon.fromAsset(context, it) }, onTap)

    fun makeCommand(@DrawableRes icon: Int, onTap: () -> Unit): ImageView =
        command(ResourcesCompat.getDrawable(resources, icon, null), onTap)

    private fun command(glyph: Drawable?, onTap: () -> Unit): ImageView =
        ring(context, palette.onChrome, glyph, GLYPH_INSET_DP).apply {
            setOnClickListener {
                Haptics.tap(it)
                closeMenu()
                onTap()
            }
        }

    /**
     * Replaces the rings in the row with [buttons], in the order given.
     *
     * Re-added rather than shown and hidden in place, so a command's position on the strip
     * is its position in the list handed over rather than the order the buttons happened
     * to be built in.
     */
    fun setCommands(buttons: List<View>) {
        group.removeAllViews()
        for ((i, button) in buttons.withIndex()) {
            (button.parent as? ViewGroup)?.removeView(button)
            group.addView(button, LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                if (i > 0) marginStart = dp(GAP_DP)
            })
        }
        syncOverflow()
    }

    /**
     * The commands in the row, which is what a caller compares against before rebuilding
     * it. The dots are not among them: they are the strip's, not the program's.
     */
    fun commands(): List<View> =
        (0 until group.childCount).map { group.getChildAt(it) }.filter { it !== overflow }

    /**
     * Marks a command as the one currently in force.
     *
     * The accent, filled, against the plain outlines of the commands that are merely
     * available: a mode that is on is a fact about the program, and a ring that only
     * differed by being a slightly brighter white would be a fact nobody could see.
     */
    fun setCommandOn(button: ImageView, on: Boolean) {
        if (on) commandsOn.add(button) else commandsOn.remove(button)
        val ink = if (on) palette.accent else palette.onChrome
        button.backgroundTintList = ColorStateList.valueOf(ink)
        button.imageTintList = ColorStateList.valueOf(ink)
    }

    /** Dims a command that has nothing to do, and stops it answering. */
    fun setCommandEnabled(button: ImageView, enabled: Boolean) {
        button.isClickable = enabled
        button.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /**
     * Puts a ring in the row, behind whatever is already in it.
     *
     * The dots come out first and go back in [syncOverflow]: they are always last, so a
     * command arriving after them must not measure its own gap against them - a strip
     * given its list before its first command would otherwise lead with a gap and sit
     * off-centre by half of one.
     */
    private fun addToGroup(button: View) {
        overflow?.let { (it.parent as? ViewGroup)?.removeView(it) }
        group.addView(button, LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
            if (group.childCount > 0) marginStart = dp(GAP_DP)
        })
        syncOverflow()
    }

    // ---------------------------------------------------------------- the middle

    /**
     * Stands [view] in the middle of the row, between the commands and the dots.
     *
     * The browser's address bar, and nothing else so far. It spreads the row: with a field
     * in it the rings can no longer sit together in the centre, because the thing between
     * them is the widest object on the strip and it is what the row is now about.
     */
    fun setStretch(view: View) {
        val middle = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SPREAD_PAD_DP), 0, dp(SPREAD_PAD_DP), 0)
        }
        row.removeView(group)
        middle.addView(group, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        middle.addView(view, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(SPREAD_GAP_DP)
            marginEnd = dp(SPREAD_GAP_DP)
        })
        row.addView(middle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        spread = middle
        syncOverflow()
    }

    /** Stacks [view] above the row: a list a program draws for itself, or anything that grows. */
    fun addPanel(view: View) {
        panels.addView(view, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ---------------------------------------------------------------- the dots

    /**
     * Hands the dots to a program that draws its own list behind them.
     *
     * The alternative to [menu], not an addition to it: there is one ellipsis on a strip
     * and it opens one thing.
     */
    fun setOverflow(onTap: () -> Unit) {
        overflowAction = onTap
        syncOverflow()
    }

    /** The three dots, in a ring like every other command, kept at the end of the row. */
    private fun overflowButton(): View {
        val holder = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            backgroundTintList = ColorStateList.valueOf(palette.onChrome)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                overflowAction?.invoke()
            }
        }
        // Drawn rather than typed: an ellipsis character is a row of full stops sitting on
        // the baseline, and what the phone had was three round dots centred in the button.
        val dots = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(3) { i ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.onChrome)
                }
            }, LayoutParams(dp(DOT_DP), dp(DOT_DP)).apply {
                if (i > 0) marginStart = dp(DOT_GAP_DP)
            })
        }
        holder.addView(dots, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        TiltEffect.apply(holder)
        return holder
    }

    /**
     * Keeps the dots at the end of the row, or off it.
     *
     * Taken out and put back as each command arrives, because what is behind them is the
     * tail of the list and has to be read last - and the commands are added one at a time
     * in the order the program wants them read. With something standing in the middle of
     * the row they go at the far end of it instead, past the thing in the middle.
     */
    private fun syncOverflow() {
        overflow?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (overflowAction == null) return
        val dots = overflow ?: overflowButton().also { overflow = it }
        val into = spread ?: group
        into.addView(dots, LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
            // Nothing extra at the far end of a spread row: the thing in the middle
            // already carries the gap, and a second one would push the ring off the edge.
            if (spread == null && into.childCount > 0) marginStart = dp(GAP_DP)
        })
    }

    // ---------------------------------------------------------------- the list

    private fun toggleMenu() {
        if (menuColumn.visibility == VISIBLE) closeMenu() else openMenu()
    }

    fun openMenu() {
        val items = menu?.invoke().orEmpty()
        if (items.isEmpty()) return
        menuColumn.removeAllViews()
        for (item in items) menuColumn.addView(menuRow(item))
        menuColumn.visibility = VISIBLE
        playListEntrance(menuColumn)
    }

    /** Whether the list is up, which is what the back key asks before it leaves the page. */
    fun isMenuOpen(): Boolean = menuColumn.visibility == VISIBLE

    fun closeMenu(): Boolean {
        if (menuColumn.visibility != VISIBLE) return false
        menuColumn.visibility = GONE
        menuColumn.removeAllViews()
        return true
    }

    private fun menuRow(item: Item): View =
        TextView(context).apply {
            // Lowercase, like every command list in this shell.
            text = item.label.lowercase()
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            textSize = 16f
            setTextColor(palette.onChrome)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                closeMenu()
                item.action()
            }
            TiltEffect.apply(this)
        }

    // ---------------------------------------------------------------- the theme

    /**
     * Repaints the strip for a new theme.
     *
     * A command marked as in force keeps the accent rather than being flattened back to
     * the ordinary ink - see [commandsOn]. The list behind the dots is not touched: it is
     * built from scratch each time it opens, out of whatever palette is current then.
     */
    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.chrome)
        val ink = ColorStateList.valueOf(p.onChrome)
        for (i in 0 until group.childCount) {
            val button = group.getChildAt(i)
            if (button is ImageView && button !in commandsOn) {
                button.backgroundTintList = ink
                button.imageTintList = ink
            }
        }
        overflow?.let {
            it.backgroundTintList = ink
            paintDots(it, p.onChrome)
        }
        for (i in 0 until menuColumn.childCount) {
            (menuColumn.getChildAt(i) as? TextView)?.setTextColor(p.onChrome)
        }
    }

    /** The three dots inside the overflow button, which are views rather than a drawable. */
    private fun paintDots(holder: View, @ColorInt ink: Int) {
        val dots = (holder as? FrameLayout)?.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until dots.childCount) {
            (dots.getChildAt(i).background as? GradientDrawable)?.setColor(ink)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The row of rings. Every strip in the shell is this tall. */
        const val HEIGHT_DP = 62

        /**
         * The strip's button, wherever one is drawn: a ring, open in the middle, with a
         * glyph of the same ink inside it.
         *
         * The shape the Start screen puts on a tile in edit mode, without its black fill:
         * there is nothing behind the button but the surface it sits on, so the ring alone
         * is the button and that surface shows through it. See wp81_appbar_circle, which
         * is drawn white and tinted here so that one drawable serves both themes.
         *
         * [ink] rather than the palette, because the same button is drawn in two places
         * that disagree about what it should be made of: on a strip it is [WP81Palette
         * .onChrome], and on a page that has borrowed it - a profile's row of ways to
         * reach somebody, a message's send key - it is the page's own foreground.
         *
         * Painted and tilting, but silent: what a ring does is the caller's business, and
         * so is whether pressing it ticks.
         */
        fun ring(
            context: Context,
            @ColorInt ink: Int,
            glyph: Drawable?,
            insetDp: Int = GLYPH_INSET_DP
        ): ImageView = ImageView(context).apply {
            setBackgroundResource(R.drawable.wp81_appbar_circle)
            glyph?.let { setImageDrawable(it) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = (insetDp * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
            backgroundTintList = ColorStateList.valueOf(ink)
            imageTintList = ColorStateList.valueOf(ink)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isClickable = true
            TiltEffect.apply(this)
        }

        /**
         * A command list swinging down, each row about its own top edge, on a stagger.
         *
         * The shell's own way of opening a list of words - see WP81ContextMenu - and the
         * strip's, whether the strip drew the list or the program did.
         */
        fun playListEntrance(column: ViewGroup) {
            for (i in 0 until column.childCount) {
                val row = column.getChildAt(i)
                row.cameraDistance = 8000f * column.resources.displayMetrics.density
                row.pivotX = 0f
                row.pivotY = 0f
                row.rotationX = -90f
                row.alpha = 0f
                row.animate().rotationX(0f).alpha(1f)
                    .setStartDelay(i * STAGGER_MS)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        private const val BUTTON_DP = 44

        /** Between the rings. Wide, so they read as a row of commands and not as a block. */
        private const val GAP_DP = 28

        /** How far a glyph sits inside its ring. */
        const val GLYPH_INSET_DP = 5

        /** The edges of a row with something standing in the middle of it, and its gaps. */
        private const val SPREAD_PAD_DP = 10
        private const val SPREAD_GAP_DP = 10

        private const val DOT_DP = 5
        private const val DOT_GAP_DP = 4

        private const val STAGGER_MS = 30L

        /** What is left of a command with nothing to do. */
        private const val DISABLED_ALPHA = 0.35f
    }
}
