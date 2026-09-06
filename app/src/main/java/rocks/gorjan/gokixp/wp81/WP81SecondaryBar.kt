package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.view.animation.DecelerateInterpolator
import rocks.gorjan.gokixp.R

/**
 * The strip of commands that belongs to what the user is *doing*, as against where they
 * are - WP8.1's app bar, which is what this is on the Start screen.
 *
 * It exists because the three keys below it must not change. They stand in for capacitive
 * hardware, and hardware does not rearrange itself: a key that means "back" until a tile
 * is held and something else afterwards is a key you have to look at before pressing.
 * Holding a tile now leaves them alone and slides this up instead.
 *
 * ```
 *   |  tiles, undisturbed            |
 *   |        ( o )      ( ... )      |   <- this, sliding up over them
 *   |    <-        [#]        Q      |   <- the nav bar, never changing
 * ```
 *
 * The slide comes out from underneath the nav bar, which is drawn after it and so hides
 * it while it is parked. Nothing on screen moves to make room: the bar is drawn over the
 * bottom of the wall, and the wall does not know it is there. A tile the bar happens to
 * cover is one dismissal away, and re-laying out the whole Start screen every time a tile
 * was held would be a far louder thing than the bar itself.
 *
 * It *is* a [MetroAppBar] - same ground, same rings, same height, same theme - with a
 * slide and a set of modes on top. What a held tile can be told to do is a program's app
 * bar in every respect except that the program is the shell.
 */
@SuppressLint("ViewConstructor")
class WP81SecondaryBar(
    context: Context,
    palette: WP81Palette
) : MetroAppBar(context, palette) {

    /** Editing a tile: choose the colour it is painted in. */
    var onTileColor: (() -> Unit)? = null

    /** Editing a tile: open its command list. */
    var onTileMenu: (() -> Unit)? = null

    /** Inside a folder, nothing selected: put another app in it. */
    var onAddApp: (() -> Unit)? = null

    /**
     * What the bar is currently for. [Mode.NONE] is the usual case - navigating, with
     * nothing held - and takes the bar off the screen.
     */
    enum class Mode {
        /** Nothing to command: the bar is away. */
        NONE,

        /** A tile on Start is selected. */
        EDIT_START,

        /** A tile inside a folder is selected. */
        EDIT_FOLDER,

        /** A folder page is open with nothing selected. */
        FOLDER
    }

    // Made rather than added: which of the three is on the strip is the mode's business,
    // and setMode puts them there. See MetroAppBar.makeCommand.
    private val colorButton = makeCommand(R.drawable.wp81_nav_color) { onTileColor?.invoke() }
    private val menuButton = makeCommand(R.drawable.wp81_handle_menu) { onTileMenu?.invoke() }
    private val addButton = makeCommand(R.drawable.wp81_nav_add) { onAddApp?.invoke() }

    var mode: Mode = Mode.NONE
        private set

    /** Whether the bar is out, as against parked behind the keys. */
    private var out = false

    init {
        // A tap on the bar is a tap on the bar - which the strip already sees to - but it
        // starts life parked out of sight rather than standing at the bottom of a page.
        visibility = GONE
        translationY = parkedY()
    }

    /**
     * Chooses the commands, and with them whether the bar is on screen at all.
     *
     * [hasSelection] is separate from the mode because both editing commands act on the
     * selected tile: with nothing selected there is nothing for the bar to say, and an
     * empty strip is worse than no strip.
     */
    fun setMode(mode: Mode, hasSelection: Boolean) {
        val shown = when (mode) {
            Mode.NONE -> emptyList()
            // "New folder" is gone: folders are made by holding one tile over another,
            // which is how the phone did it and needs no key. Unpinning and hiding live in
            // the command list - each is a thing you do once, where recolouring is a thing
            // you do repeatedly until it looks right.
            Mode.EDIT_START -> if (hasSelection) listOf(colorButton, menuButton) else emptyList()
            // Inside a folder the tile's own colour is the folder's business, not the
            // wall's, so only the command list is offered.
            Mode.EDIT_FOLDER -> if (hasSelection) listOf(menuButton) else emptyList()
            Mode.FOLDER -> listOf(addButton)
        }
        if (shown.isEmpty()) {
            this.mode = mode
            slideAway()
            return
        }
        // Nothing to do only if the bar is already out with these very commands on it:
        // the same mode with the bar parked - a tile reselected after being let go - still
        // has to bring it back.
        if (out && mode == this.mode && shown == commands()) return
        this.mode = mode
        setCommands(shown)
        slideOut()
    }

    // ---------------------------------------------------------------- the slide

    private fun slideOut() {
        if (out) return
        out = true
        visibility = VISIBLE
        animate().cancel()
        animate()
            .translationY(0f)
            .setDuration(SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            // Cleared explicitly: a ViewPropertyAnimator keeps the end action it was last
            // given, so without this the hide's "go away" would fire at the end of a show.
            .withEndAction(null)
            .start()
    }

    private fun slideAway() {
        if (!out) return
        out = false
        animate().cancel()
        animate()
            .translationY(parkedY())
            .setDuration(SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!out) visibility = GONE }
            .start()
    }

    /**
     * Where the bar waits: exactly its own height lower, which puts it inside the nav
     * bar's band. The nav bar is added after it and so covers it there.
     *
     * Measured from the constant rather than from [getHeight], which is zero until the
     * first layout - and the first hold on a tile can come before one.
     */
    private fun parkedY(): Float =
        HEIGHT_DP * resources.displayMetrics.density

    companion object {
        /** Kept for the callers that reserve room for the strip. Every strip is this tall. */
        const val HEIGHT_DP = MetroAppBar.HEIGHT_DP

        private const val SLIDE_MS = 200L
    }
}
