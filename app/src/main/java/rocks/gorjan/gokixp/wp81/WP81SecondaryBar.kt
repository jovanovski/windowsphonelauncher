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

    /**
     * Editing a tile that has a picture: show it, or hold it back.
     *
     * Offered only where there is a picture - a News tile turning through photographs, a
     * tile playing something with a cover. See [setMode] and TileView.showsBackdrop.
     */
    var onTilePicture: (() -> Unit)? = null

    /** Inside a folder, nothing selected: put another app in it. */
    var onAddApp: (() -> Unit)? = null

    /** Editing a tile: make a folder out of it, where it stands. */
    var onNewFolder: (() -> Unit)? = null

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

    // Made rather than added: which of them are on the strip is the mode's business, and
    // setMode puts them there. See MetroAppBar.makeCommand.
    private val colorButton = makeCommand(R.drawable.wp81_nav_color) { onTileColor?.invoke() }
    // The app bar image glyph, which the Photos tile also wears - it is the same picture
    // in both places, and this is the icon that set was drawn for.
    private val pictureButton =
        makeCommand(R.drawable.wp81_glyph_photos) { onTilePicture?.invoke() }
    private val menuButton = makeCommand(R.drawable.wp81_handle_menu) { onTileMenu?.invoke() }
    private val addButton = makeCommand(R.drawable.wp81_nav_add) { onAddApp?.invoke() }
    private val newFolderButton =
        makeCommand(R.drawable.wp81_nav_new_folder) { onNewFolder?.invoke() }

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
     *
     * [picture] is the selected tile's picture: null where it has none, and otherwise
     * whether it is currently showing it. Null takes the picture command off the strip
     * altogether - a tile with no photograph behind it has nothing to turn off, and a key
     * that does nothing is a key that looks broken.
     *
     * [canFolder] is the same kind of answer about the new-folder command: a folder, a
     * built-in, or a tile already filed inside one cannot be made into a folder, and the
     * key comes off the strip rather than standing there refusing. See
     * StartScreenView.editingCanFolder.
     */
    fun setMode(
        mode: Mode,
        hasSelection: Boolean,
        picture: Boolean? = null,
        canFolder: Boolean = false
    ) {
        // Alongside the colour rather than in the command list: both are the same kind of
        // thing - how the tile looks, tried and untried until it looks right - where the
        // list holds the once-only verbs. A key that is *on* says so by wearing the accent,
        // which is what a picture that is currently showing gets.
        val toggle = pictureButton.takeIf { picture != null }
        setCommandOn(pictureButton, picture == true)
        val shown = when (mode) {
            Mode.NONE -> emptyList()
            // The folder key sits between the two looks-of-the-tile commands and the list,
            // and the list stays last: the dots are the way out of the strip and move about
            // as little as the keys under it. Unpinning and hiding are in that list - each
            // is a thing you do once, where recolouring is a thing you do repeatedly until
            // it looks right.
            //
            // The phone had no such key: folders there were made by holding one tile over
            // another. That is still written and still works, but it is switched off - see
            // StartScreenView.FOLD_ON_DRAG for what it cost the wall to keep.
            Mode.EDIT_START ->
                if (hasSelection) listOfNotNull(
                    colorButton,
                    toggle,
                    newFolderButton.takeIf { canFolder },
                    menuButton
                ) else emptyList()
            // Inside a folder the tile's own colour is the folder's business, not the
            // wall's, so only the command list is offered - and the picture, which is not:
            // a News tile filed away still turns through photographs, and where it is
            // filed has nothing to do with whether they are wanted.
            Mode.EDIT_FOLDER ->
                if (hasSelection) listOfNotNull(toggle, menuButton) else emptyList()
            Mode.FOLDER -> listOf(addButton)
        }
        if (shown.isEmpty()) {
            this.mode = mode
            slideAway()
            return
        }
        // Nothing to do only if the bar is already out with these very commands on it:
        // the same mode with the bar parked - a tile reselected after being let go - still
        // has to bring it back. The ring's own state is settled above, before this: the
        // strip is asked again the moment the picture is switched, and that changes how a
        // command is drawn without changing which commands there are.
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
