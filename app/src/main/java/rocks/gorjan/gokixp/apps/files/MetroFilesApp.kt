package rocks.gorjan.gokixp.apps.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroMarker
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.MonochromeIconProvider
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81InputDialog
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81Program
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Files, the app Windows Phone 8.1 finally got in 2014.
 *
 * The phone shipped for two years with no way to look at its own storage, and when the
 * app arrived it was deliberately plain: a list of folders and files, a strip of commands
 * along the bottom, and a select mode for doing something to several things at once. There
 * is no ribbon, no tree in a left pane and no two-pane copy - a phone has one screen, and
 * the whole design follows from that.
 *
 * This is that app, on this shell's own furniture. The command strip, the prompt and the
 * press-and-tilt are all the shell's and arrived built; what is written here is the list,
 * the sorting, and the file work behind the commands.
 *
 * There is a third section on it that the phone kept in a separate program: photos. It is
 * here rather than in one of its own because on this shell the two apps would have been
 * the same app twice - one list of folders that opens files, and beside it another list of
 * folders that opens files, differing only in which files each of them will admit exists.
 * What actually differs is how a folder of photographs wants to be *drawn*, which is a
 * page rather than a program: a wall of albums, each showing what is newest in it, and the
 * pictures themselves laid out four across instead of one under the other. So the strip,
 * the clipboard and select mode are the same ones the other two sections use, and the
 * only thing photos brings of its own is the shape of the page.
 *
 * Deliberately not the desktop's My Computer with a new coat of paint. My Computer is a
 * Windows window: drives named after letters, a folder rendered as icons on a grid, and
 * the whole thing framed in chrome. This is a phone's file list - one column, names set
 * large and light, everything else in the subtle colour underneath. The two shells get the
 * app each of them would have had, and neither has to pretend to be the other.
 *
 * The clipboard is on the companion rather than on an instance, because a cut is a
 * statement about the phone and not about a window: cutting something, closing the app and
 * opening it again somewhere else to paste is exactly the way anybody moves a file, and a
 * clipboard that emptied itself on the way out would break that.
 */
class MetroFilesApp(
    private val context: Context,
    private var palette: WP81Palette,
    /** Handing a file to whatever opens that kind of file. The shell decides, not this app. */
    private val onOpen: (File) -> Unit,
    /** The shell's own toast: what it says when work finishes, and when it cannot be done. */
    private val onNotify: (String, String) -> Unit
) : WP81Program {

    /** A place the phone keeps files, at the top of the tree. See [rootsOf]. */
    private data class Root(val label: String, val dir: File, val icon: String)

    /** What a listing is put in order by. The user's choice, and it is remembered. */
    private enum class Sort { NAME, DATE, SIZE }

    /** What is waiting to be pasted, and whether pasting it should also remove it. */
    private enum class ClipMode { COPY, CUT }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    /** Only for its ink measuring, which is the hard half of putting a glyph on a row. */
    private val icons = MonochromeIconProvider(context)

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama

    /** The browse page: where you are, how you got there, and what is in it. */
    private lateinit var browsePage: LinearLayout
    private lateinit var pathLine: TextView
    private lateinit var listColumn: LinearLayout
    private lateinit var scroller: ScrollView

    /** The recents page: one list, and a line above it that only select mode uses. */
    private lateinit var recentsLine: TextView
    private lateinit var recentsColumn: LinearLayout
    private lateinit var recentsScroller: ScrollView

    /** The photos page: the wall of albums, or the pictures inside one of them. */
    private lateinit var photosLine: TextView
    private lateinit var photosColumn: LinearLayout
    private lateinit var photosScroller: ScrollView

    private lateinit var barSlot: FrameLayout
    private lateinit var viewer: PhotoViewer
    private lateinit var dialog: WP81InputDialog

    /**
     * The strip standing in [barSlot], and - in select mode - how to re-arm its rings.
     *
     * Kept so a tap on a checkbox can say what the strip now allows without building
     * another one: a new strip is its icons read and parsed out of the assets again, and
     * that is not something to pay for on every tick.
     */
    private var shownBar: MetroAppBar? = null
    private var syncSelectBar: (() -> Unit)? = null

    private val roots = rootsOf()

    /**
     * Where the list is now. Null is the roots page - the one screen that is not a folder.
     *
     * Only ever null on a phone that has somewhere other than its own storage to offer: a
     * roots page listing one root is a screen whose only purpose is to be tapped through,
     * so where there is nothing to choose between the app opens in the storage itself.
     */
    private var current: File? = if (roots.size > 1) null else roots.firstOrNull()?.dir

    /**
     * Which album the photos page is standing in, or null for the wall of albums itself.
     *
     * Two depths and no more, deliberately. A folder of photographs has nothing inside it
     * worth walking into on this page - the folders that do are exactly what the wall is a
     * list of - so photos never grows a trail, and anything deeper than one step is what
     * browse is for.
     */
    private var album: File? = null

    /**
     * Which section is showing.
     *
     * Held rather than asked of the panorama, because the panorama's answer is its position
     * and a position is halfway between two sections for the length of a slide. The strip
     * under the page has to be right before the slide finishes, not after it.
     */
    private var page = PAGE_RECENTS

    private var sort = readSort()
    private var showHidden = prefs.getBoolean(KEY_HIDDEN, false)

    /** Select mode, and what is picked out in it. Empty and off is the ordinary listing. */
    private var selecting = false
    private val selection = LinkedHashSet<File>()

    /**
     * Which section select mode was started on.
     *
     * A selection is about one listing and there are three of them now, so the count line
     * belongs to the page it was made on rather than to whichever page happens to be
     * showing. Held rather than read back off [page], because [page] moves to the section
     * being arrived at before there is any chance to notice that the selection was left
     * behind on the one being left.
     */
    private var selectPage = PAGE_RECENTS

    /**
     * How each thing on a page shows whether it is picked out, by the file it stands for.
     *
     * What lets select mode touch one row rather than a page. A tap in it used to draw the
     * whole listing again to move one tick - a readdir, a sort that asked the disk for a
     * date at every comparison, every row's icon parsed afresh - and on a folder of any
     * size that was a pause you could feel between the tap and the tick. Now each row
     * leaves behind how to paint itself, and a tap paints the one that was tapped.
     *
     * Emptied and filled again as a page is drawn. In listing order, which is the order
     * "select all" picks things out in.
     */
    private val picks = List(PAGE_COUNT) { LinkedHashMap<File, (Boolean, Boolean) -> Unit>() }

    /** True while a copy, move or delete is running. The strip is dead until it is not. */
    private var busy = false

    /** Set once the window is gone, so work finishing afterwards touches no views. */
    private var released = false

    // ------------------------------------------------------------------------ the page

    /**
     * Rebuilds the program in a new theme. See [WP81Program].
     */
    override fun applyPalette(palette: WP81Palette): View {
        // The viewer holds a decoder thread of its own and a new one is built with the
        // rest of the page; put this one down rather than leaving it running behind the
        // rebuild. See PhotoViewer.release.
        viewer.release()
        this.palette = palette
        return createView()
    }

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        panorama = MetroPanorama(context, palette).apply {
            // The app's left margin, section names and all, which is why the pages below
            // carry only their right one.
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // The app's own name, on the panorama rather than in any of the pages - which is
        // the point of putting it there: it is the slowest of the moving layers, drifting a
        // fraction of the screen while the sections go by underneath, and that drift is
        // what says the app is one wide surface rather than three screens being swapped.
        panorama.setTitle("files & photos")
        panorama.addPage("recents", buildRecentsPage())
        panorama.addPage("photos", buildPhotosPage())
        panorama.addPage("browse", buildBrowsePage())
        // The strip belongs to whichever page is under it, and what each section can
        // offer differs - so it is put up again as the panorama settles.
        panorama.onPageSettled = { at ->
            val moved = at != page
            page = at
            // Select mode belongs to the listing it was started in, and swiping off that
            // listing is leaving it: a strip of commands about a selection no longer on
            // screen is a strip that gets used by mistake. The marks come off the page the
            // selection was made on, because that is the one still wearing them.
            if (moved && selecting) endSelecting(refresh = false)
            // Nothing else is rebuilt. Arriving on a section is not a reason to draw its
            // list again - it has been on screen throughout the swipe - and rebuilding it
            // would throw away where the reader had scrolled to on their way out of it.
            titleThePage()
            installBar()
            if (at == PAGE_RECENTS || at == PAGE_PHOTOS) startScan(force = false)
        }
        root.addView(panorama, FrameLayout.LayoutParams(MATCH, MATCH))

        // The strip is swapped rather than edited: browsing and selecting have different
        // commands on them, and MetroAppBar is built to be filled once and left alone.
        barSlot = FrameLayout(context)
        root.addView(barSlot, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // Over the pages and their strip, and under the prompt below: a picture being
        // looked at covers the app, and the prompt asking whether to delete it covers the
        // picture.
        viewer = PhotoViewer(
            context = context,
            palette = palette,
            onShare = { file -> share(listOf(file)) },
            onDelete = { file -> askToDelete(listOf(file)) },
            onOpenWith = { file -> onOpen(file) }
        )
        root.addView(viewer.view(), FrameLayout.LayoutParams(MATCH, MATCH))

        // Over everything: a prompt that dims the page it belongs to cannot be inside it.
        dialog = WP81InputDialog(context, palette)
        root.addView(dialog, FrameLayout.LayoutParams(MATCH, MATCH))

        refresh()
        return root
    }

    /**
     * The browse page: the app as it was before there were two of them.
     *
     * Two things have gone from it. Its left margin, which the panorama now supplies for
     * every page at once - a section name that did not line up with the rows under it would
     * be the one thing the eye caught. And its heading: the app is titled once, on the
     * panorama, and a second name under that one saying "phone" is a heading for a page
     * which has one already. What the folder is called moved down into the trail, which was
     * always the line that said where you were.
     */
    private fun buildBrowsePage(): View {
        browsePage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        pathLine = trailLine()
        browsePage.addView(pathLine, wide())

        listColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroller = listScroller(listColumn)
        browsePage.addView(scroller, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return browsePage
    }

    /**
     * The recents page: one list, no heading of its own.
     *
     * The section name above it is the heading - it says "recents" in the panorama's own
     * type, and a second word under it saying the same thing would be a heading for a page
     * that has one already. Nothing to sort by either: newest first is what the page is.
     */
    private fun buildRecentsPage(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // Nothing to say about where you are - the files on this page are from all over
        // the phone - so the line is empty and gone until select mode wants to count.
        recentsLine = trailLine()
        page.addView(recentsLine, wide())

        recentsColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(24))
        }
        recentsScroller = listScroller(recentsColumn)
        page.addView(recentsScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return page
    }

    /**
     * The photos page: every folder on the phone that has pictures in it, and then the
     * pictures in one of them.
     *
     * The two halves are one page rather than two, for the same reason browse is one page
     * however deep it goes: opening an album is going somewhere, and the section title
     * above stays put while you do. What changes is the shape of the wall - albums two
     * across, photographs four - and the line at the top, which is empty on the wall and
     * names the album once you are in one.
     *
     * Why a wall and not a list. A folder of photographs is the one folder where the names
     * are worthless: IMG_20240817_142233.jpg says nothing anybody wants to know, and the
     * thing they came for is which picture is which. So the picture is the row - which
     * means it can be small, which means four of them fit across, which is what makes a
     * page of a hundred photographs something you can find one in.
     */
    private fun buildPhotosPage(): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        photosLine = trailLine()
        page.addView(photosLine, wide())

        photosColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The right margin sits on the column rather than on each row of tiles: the
            // rows here are a grid rather than a list, and a grid that measured its cells
            // against a width its own padding was inside would come out short by one gap.
            // A little air at the top, because on the wall the line above is gone and a
            // tile would otherwise start hard against the section names.
            setPadding(0, dp(6), dp(PAGE_MARGIN_DP), dp(24))
        }
        photosScroller = listScroller(photosColumn)
        page.addView(photosScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return page
    }

    /**
     * The thin line above a listing: where you are, or - in select mode - how many things
     * are picked out. Every section has one; only browse and photos ever put a trail in it.
     */
    private fun trailLine(): TextView = TextView(context).apply {
        typeface = font(R.font.segoeui_regular)
        textSize = 15f
        // The base under the trail's own spans, and the whole colour of the count that
        // stands here in select mode.
        setTextColor(palette.foregroundSubtle)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        setPadding(0, dp(2), dp(PAGE_MARGIN_DP), dp(12))
        visibility = View.GONE
    }

    /**
     * A scrolling list, wired to ask for its pictures as it moves.
     *
     * Both hooks are needed: the scroll one is the scrolling, and the layout one is the
     * first draw of a listing, a rotation, and the rows coming back after the list is
     * rebuilt without having moved.
     */
    private fun listScroller(content: LinearLayout): ScrollView {
        val view = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        view.setOnScrollChangeListener { _, _, _, _, _ -> askForPicturesInView() }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            askForPicturesInView()
        }
        return view
    }

    /**
     * The back key, on the way out of wherever it currently is.
     *
     * Five things in order, and the order is the point: a prompt, then a picture, then the
     * strip's list, then select mode, then a folder. Only when none of those is standing
     * does the key mean what it means everywhere else, and the window closes.
     */
    fun handleBack(): Boolean {
        if (dialog.isShowing()) { dialog.dismiss(); return true }
        // Before select mode, because a picture opened out of a listing covers that
        // listing: the key the user is pressing is the one that puts the page back.
        if (viewer.handleBack()) return true
        // The list behind the dots, which is where a held thing's commands are now. It
        // goes the way the hold menu they came from did, and the thing stays picked out.
        if (shownBar?.closeMenu() == true) return true
        if (selecting) { endSelecting(); return true }
        // Recents is flat, with nothing above it to climb to: on that page the key means
        // what it means everywhere else and the window closes. Photos has exactly one step
        // to give back, out of an album onto the wall it was picked off.
        return when (page) {
            PAGE_BROWSE -> goUp()
            PAGE_PHOTOS -> album != null && run { closeAlbum(); true }
            else -> false
        }
    }

    fun cleanup() {
        released = true
        viewer.release()
        waitingRows.clear()
        handler.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------------- getting around

    /**
     * Up one, and whether there was anywhere to go.
     *
     * False is the answer that closes the window, and it is the right answer in two places:
     * at a root on a phone with only one, and on the roots page itself. Above either of
     * those there is no folder left, only the shell.
     */
    private fun goUp(): Boolean {
        val here = current ?: return false
        if (roots.any { it.dir == here }) {
            if (roots.size <= 1) return false
            navigateTo(null)
            return true
        }
        val parent = here.parentFile ?: return false
        navigateTo(parent)
        return true
    }

    private fun navigateTo(dir: File?, swing: Boolean = true) {
        current = dir
        // A folder left in select mode and then left behind would come back selected in
        // somewhere else entirely; the selection is about this listing and dies with it.
        endSelecting(refresh = false)
        redraw()
        // Every page in this shell swings in about its left edge. Going into a folder is
        // going somewhere, so it does too - unless the panorama is already carrying the
        // page across, which is one movement and does not want a second inside it.
        if (swing) MetroPageTransition(scroller).playIn()
    }

    /**
     * The folder the section being shown is standing in, or null for a page that is not
     * standing in one.
     *
     * Two of the five screens in this app are standing in one - a browsed folder, an
     * opened album - and the other three are lists of places to go or of files from all
     * over the phone. Everything the strip can do splits on that question rather than on
     * which section it is: making a folder and pasting into one need somewhere to put the
     * result, and the screens with nowhere show those commands dead rather than pretending.
     */
    private fun folderHere(): File? = when (page) {
        PAGE_BROWSE -> current
        PAGE_PHOTOS -> album
        else -> null
    }

    private fun openAlbum(dir: File) {
        album = dir
        endSelecting(refresh = false)
        // A wall scrolled halfway down would otherwise hand its scroll straight to the
        // album, which opens somewhere in the middle of a folder nobody has looked at yet.
        photosScroller.scrollTo(0, 0)
        redraw()
        MetroPageTransition(photosScroller).playIn()
    }

    private fun closeAlbum() {
        album = null
        endSelecting(refresh = false)
        photosScroller.scrollTo(0, 0)
        redraw()
        MetroPageTransition(photosScroller).playIn()
    }

    // ------------------------------------------------------------------- drawing it all

    /**
     * Draws the page as things now stand.
     *
     * Public because the window is not the only thing that changes what is on disk: a
     * download landing or a photograph being taken while Files sits behind another window
     * leaves the listing describing a folder that has moved on without it, so the shell
     * refreshes it on the way back in.
     */
    fun refresh() {
        if (released) return
        redrawAll()
        // What was on these pages a minute ago may have been deleted from under them, and
        // a walk of the whole phone is far too much to spend on finding that out.
        pruneRecents()
        startScan(force = false)
    }

    /**
     * The page under the finger and the strip beneath it, which is all that most changes
     * touch.
     *
     * One page rather than three, because the two that are not showing have not changed:
     * a step into a folder, a new folder and a change of sort all happen inside one
     * listing. Drawing the other two would cost a folder listing each and throw away
     * where the reader had scrolled to on them, to arrive at exactly what was there.
     */
    private fun redraw() {
        if (released) return
        titleThePage()
        fillPage(page)
        installBar()
    }

    /**
     * Every page, for the changes that are about the phone rather than about a listing.
     *
     * A delete, a paste or a move can take something off all three at once - the file in
     * the folder, its row on recents, its picture in an album - so after work on disk the
     * whole app is drawn again rather than the one page that happened to ask for it.
     */
    private fun redrawAll() {
        if (released) return
        titleThePage()
        fillList()
        fillPhotos()
        fillRecents()
        installBar()
    }

    private fun fillPage(which: Int) {
        when (which) {
            PAGE_BROWSE -> fillList()
            PAGE_PHOTOS -> fillPhotos()
            else -> fillRecents()
        }
    }

    /**
     * Says where the page is, which is now the trail's whole job.
     *
     * While things are picked out it counts instead: what the page is about in that moment
     * is the selection and not the folder, and the count is what the heading used to carry.
     */
    private fun titleThePage() {
        // The roots page and the wall of albums have nothing to say: their rows are the
        // places you could go, which is the whole of where you are.
        sayWhere(pathLine, PAGE_BROWSE, current?.let { trailTo(it) } ?: "")
        sayWhere(photosLine, PAGE_PHOTOS, album?.let { trailTo(it) } ?: "")
        sayWhere(recentsLine, PAGE_RECENTS, "")
    }

    /**
     * One page's line: where that page is, or - if the selection was made on it - how much
     * of it is picked out.
     *
     * Each page keeps its own, rather than one line being moved about, because the count
     * is about a listing and the listing it is about may not be the one showing. Swiping
     * off a selection ends it, but the swipe takes a moment and the count must not be
     * sitting over somebody else's page for the length of it.
     */
    private fun sayWhere(line: TextView, forPage: Int, trail: CharSequence) {
        if (selecting && selectPage == forPage) {
            line.setTextColor(palette.foreground)
            line.text =
                if (selection.size == 1) "1 selected" else "${selection.size} selected"
            line.visibility = View.VISIBLE
            return
        }
        line.setTextColor(palette.foregroundSubtle)
        line.visibility = if (trail.isEmpty()) View.GONE else View.VISIBLE
        line.text = trail
    }

    /**
     * How to get to this folder, said as a trail rather than as a path.
     *
     * "Pictures › Camera", not "/storage/emulated/0/Pictures/Camera". The second is true
     * and tells the reader nothing they were wondering about; the first is the answer to
     * the only question the page leaves open, which is where they are.
     *
     * The folder on the end used to be left off, because a heading above was already saying
     * it. There is no heading now, so the trail says the whole thing - and the last step,
     * being the answer rather than the way to it, is set in the ink the rest of the page is
     * written in while the way there stays behind it in the subtle one.
     *
     * What it does not say is the volume. On a phone with no memory card there is one place
     * files can be, and leading every trail with its name - or standing the word "phone" on
     * its own under the app's title, which is what the top of the tree came to - is a line
     * spent saying the only thing that was never in question. Where there *is* a card the
     * word earns its place, because then it is a choice between two, and it comes back.
     */
    private fun trailTo(here: File): CharSequence {
        val rootHere = roots.firstOrNull { here.absolutePath.startsWith(it.dir.absolutePath) }
        val steps = when {
            // Somewhere outside every root, which should not happen and is not worth
            // guessing about: the folder and the one above it, said plainly.
            rootHere == null -> listOfNotNull(here.parentFile?.name, here.name)
            // Standing in the root itself. With a card to tell it from, its name is the
            // whole answer; without one there is nothing here to say at all.
            here == rootHere.dir ->
                if (roots.size > 1) listOf(rootHere.label) else return ""
            else -> {
                val below = here.absolutePath
                    .removePrefix(rootHere.dir.absolutePath)
                    .trim(File.separatorChar)
                    .split(File.separatorChar)
                if (roots.size > 1) listOf(rootHere.label) + below else below
            }
        }
        val line = android.text.SpannableStringBuilder(steps.joinToString(SEPARATOR))
        val last = line.length - steps.last().length
        line.setSpan(
            android.text.style.ForegroundColorSpan(palette.foreground),
            last, line.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (last > 0) {
            line.setSpan(
                android.text.style.ForegroundColorSpan(palette.foregroundSubtle),
                0, last, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return line
    }

    private fun fillList() {
        listColumn.removeAllViews()
        // The queue is about the listing on the screen. A folder left behind takes its
        // unanswered rows with it - and only its own: the recents page beside it may be
        // part-way through a queue of its own.
        waitingRows.removeAll { it.scroller === scroller }
        picks[PAGE_BROWSE].clear()

        val here = current
        if (here == null) {
            for (r in roots) listColumn.addView(rootRow(r), wide())
            return
        }

        if (!here.canRead()) {
            say(listColumn, "this folder cannot be opened")
            return
        }

        val entries = entriesOf(here)
        if (entries.isEmpty()) {
            say(listColumn, if (showHidden) "empty" else "nothing here")
            return
        }
        for (entry in entries) listColumn.addView(fileRow(entry), wide())
        // Nothing has been measured yet, so the sweep above the list cannot say what is in
        // view. This one runs once it can - and the layout hook covers the case where the
        // frame arrives first.
        handler.post { askForPicturesInView() }
    }

    /** A sentence rather than a picture of an empty box, the way the phone said it. */
    private fun say(into: LinearLayout, words: String) {
        into.addView(TextView(context).apply {
            text = words
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(10), dp(PAGE_MARGIN_DP), 0)
        }, wide())
    }

    private fun entriesOf(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        val visible = if (showHidden) all.toList() else all.filter { !it.name.startsWith(".") }
        val within = when (sort) {
            Sort.NAME -> compareBy<File> { it.name.lowercase(Locale.getDefault()) }
            // Newest and largest first: sorting by a date or a size is asking which is the
            // most of it, and answering with the least would mean scrolling to find out.
            Sort.DATE -> compareByDescending<File> { it.lastModified() }
            Sort.SIZE -> compareByDescending<File> { if (it.isDirectory) 0L else it.length() }
        }
        // Folders above files whatever the sort, because a listing is somewhere to get
        // through as much as something to read, and the ways onward belong at the top.
        return visible.sortedWith(compareByDescending<File> { it.isDirectory }.then(within))
    }

    // --------------------------------------------------------------------------- recents

    /**
     * One file on the recents page.
     *
     * Its date, its size and the folder it was found in are carried rather than asked for
     * again when the row is drawn. Every one of them is a stat call, the walk has just made
     * them all, and a row that asked again could disagree with the order it was put in.
     */
    private data class Recent(
        val file: File,
        val where: String,
        val at: Long,
        val size: Long
    )

    /**
     * A folder with pictures in it, as the wall of albums shows one.
     *
     * Everything a tile needs is carried, because the tile is drawn from this and never
     * goes back to the disk: the newest few pictures for the mosaic, how many there are
     * altogether, and when the newest of them arrived - which is the order the wall is in.
     */
    private data class Album(
        val dir: File,
        val label: String,
        val newest: Long,
        val count: Int,
        val shots: List<File>,
        /** Where this folder comes in the order regardless of dates. See [cameraRank]. */
        val rank: Int
    )

    private var recents: List<Recent> = emptyList()
    private var albums: List<Album> = emptyList()
    private var scanning = false
    private var scannedAt = 0L

    /**
     * Walks the storage for what both flat pages are made of, unless that was done recently
     * enough.
     *
     * One walk, two answers. Recents and the wall of albums ask the filesystem the very
     * same question - open every folder, look at the date on every file - and running two
     * traversals to answer it would be paying twice for one pass over the disk. So the
     * sweep collects both and the two pages are filled from it together.
     *
     * On a thread, always: the number of folders on a phone that has been in use for a
     * year is not something to find out on the one thread that draws. The pages say they
     * are looking while it looks.
     *
     * [force] is for the two moments when the answer is known to have changed rather than
     * merely aged - the hidden-files switch, which changes what the walk is allowed to see,
     * and the refresh command, which is somebody saying so outright.
     */
    private fun startScan(force: Boolean) {
        if (released || scanning) return
        if (!force && recents.isNotEmpty() &&
            SystemClock.elapsedRealtime() - scannedAt < RESCAN_MS
        ) return

        scanning = true
        // Only where there is nothing to show: a page that already has a list keeps it
        // while the next walk runs rather than emptying itself to announce that it is
        // working.
        if (recents.isEmpty()) fillRecents()
        if (albums.isEmpty() && album == null) fillPhotos()
        Thread {
            val found = try {
                sweepStorage()
            } catch (e: Exception) {
                Log.w(TAG, "Could not walk the storage", e)
                Sweep(emptyList(), emptyList())
            }
            handler.post {
                scanning = false
                if (released) return@post
                scannedAt = SystemClock.elapsedRealtime()
                recents = found.recents
                albums = found.albums
                fillRecents()
                // Not while the reader is inside an album. What the walk found is the wall
                // behind them, and rebuilding the page they are on to put it there would
                // throw away their place in a folder the walk has nothing to say about.
                if (album == null) fillPhotos()
            }
        }.start()
    }

    /** What one walk of the storage comes back with: both flat pages, in one pass. */
    private data class Sweep(val recents: List<Recent>, val albums: List<Album>)

    /**
     * The newest files under the roots, and every folder with pictures in it.
     *
     * Breadth first, and that is the whole design: what somebody is looking for on the
     * recents page is a download, a photograph or something a program just wrote, and
     * those sit one or two folders down. A depth-first walk would spend its whole budget
     * inside the first deep folder it fell into and never come back up to Downloads.
     *
     * Bounded three ways, because a filesystem has no size a walk can rely on: a ceiling on
     * folders opened, a clock, and a queue that only ever holds [RECENTS_MAX] files - the
     * oldest is thrown out at every push, so a phone with sixty thousand files costs sixty
     * entries rather than sixty thousand. A walk that runs out of budget returns what it
     * has, which is the newest of what it reached.
     *
     * The albums cost nothing on top of that. A folder's pictures are noticed while its
     * children are already being looked at for recents, and finished the moment that
     * folder's listing runs out - so what is held at any point is one folder's worth of
     * dates and, per album kept, the [MOSAIC_MAX] files its tile will actually draw.
     */
    private fun sweepStorage(): Sweep {
        val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MS
        // Ordered on the date read out of the file rather than on the file, because a
        // comparison on File.lastModified() is a stat call and a heap does several per
        // push: on a phone with fifty thousand files that is the difference between one
        // pass over the disk and six.
        val keep = java.util.PriorityQueue<Recent>(RECENTS_MAX + 1, compareBy { it.at })
        val walls = mutableListOf<Album>()
        val queue = ArrayDeque<File>()
        roots.forEach { queue.addLast(it.dir) }

        var opened = 0
        while (queue.isNotEmpty() && opened < SCAN_FOLDER_MAX) {
            if (SystemClock.elapsedRealtime() > deadline) break
            val here = queue.removeFirst()
            val children = here.listFiles() ?: continue
            opened++
            val pictures = mutableListOf<Pair<File, Long>>()
            for (child in children) {
                if (!showHidden && child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (!skipped(child)) queue.addLast(child)
                    continue
                }
                // A file with no date is a file the walk cannot place, and both pages are
                // nothing but an order - it would sit at the bottom saying nothing.
                val at = child.lastModified()
                if (at <= 0L) continue
                if (!working(child)) {
                    keep.add(Recent(child, "", at, child.length()))
                    if (keep.size > RECENTS_MAX) keep.poll()
                }
                if (isPicture(child)) pictures.add(child to at)
            }
            if (pictures.isEmpty()) continue
            val newest = pictures.sortedByDescending { it.second }
            walls.add(Album(
                dir = here,
                label = labelOf(here),
                newest = newest.first().second,
                count = pictures.size,
                shots = newest.take(MOSAIC_MAX).map { it.first },
                rank = cameraRank(here)
            ))
        }
        // The trail is worked out for the survivors only. It is string work per file, and
        // all but sixty of them were about to be thrown away.
        return Sweep(
            keep.sortedByDescending { it.at }.map { it.copy(where = whereOf(it.file)) },
            // The camera first, and after it the folder whose newest picture is newest -
            // which on any phone in use puts whatever the user was last sent, or last
            // saved, at the top of the wall.
            walls.sortedWith(compareBy<Album> { it.rank }.thenByDescending { it.newest })
        )
    }

    /**
     * Where a folder comes on the wall before dates are considered at all.
     *
     * The camera roll is the album nobody should have to look for: it is the one folder on
     * the phone whose contents the user made themselves, and a wall that buried it under
     * whichever chat app was busiest this morning would be a wall they had to read. So it
     * is pinned to the front, and everything else is left to the ordinary order.
     *
     * DCIM itself comes next, and only where it holds pictures directly - some cameras
     * file into it rather than into a Camera folder beneath, and on those phones that *is*
     * the roll. On the ordinary phone the folder is empty of files and never becomes an
     * album at all, so the rank costs nothing.
     */
    private fun cameraRank(dir: File): Int = when {
        dir.name.equals("Camera", true) && dir.parentFile?.name.equals("DCIM", true) -> 0
        dir.name.equals("DCIM", true) -> 1
        else -> 2
    }

    /**
     * What an album is called: the folder's own name, except at the top of a volume, where
     * the folder has no name worth reading and the volume's is the true answer.
     */
    private fun labelOf(dir: File): String =
        roots.firstOrNull { it.dir == dir }?.label ?: dir.name

    /**
     * Whether a file belongs on the photos page.
     *
     * Clips as well as stills, because the thing this page is a page of is the camera roll,
     * and a roll that dropped every video would be a gappy account of a day out - the phone
     * put both in Photos for exactly that reason. Which of the two a tile is showing is
     * said by the clip mark, the same way it is said on a row.
     */
    private fun isPicture(file: File): Boolean = when (FileThumbnails.kindOf(file)) {
        FileThumbnails.Kind.IMAGE, FileThumbnails.Kind.VIDEO -> true
        else -> false
    }

    /**
     * Folders the walk does not go into.
     *
     * Android/data and Android/obb are where programs keep their own working files:
     * gigabytes of caches and databases, rewritten constantly, so *always* the newest
     * things on the phone - and on most phones unreadable anyway. A recents page that let
     * them in would be a list of nothing anybody put there. Android/media is left alone,
     * because that is where several messaging apps keep what they have actually received -
     * the working files those apps keep there too are dealt with one at a time, by
     * [working], rather than by shutting the folder.
     */
    private fun skipped(dir: File): Boolean {
        if (!dir.canRead()) return true
        val name = dir.name
        return (name == "data" || name == "obb") && dir.parentFile?.name == "Android"
    }

    /**
     * Whether a file is a program's own working copy rather than something somebody put on
     * the phone.
     *
     * The companion to [skipped], for the folders that cannot simply be left out.
     * Android/media is walked on purpose - it is where several messaging apps keep what
     * they have actually received - but it is also where those same apps keep their chat
     * databases, and a chat database is rewritten every night while the phone is charging.
     * Left in, it is permanently the newest thing on the phone: the top of recents becomes
     * msgstore.db.crypt14 and stays there, above the photograph the user took this
     * afternoon. Nobody opens one, and nobody can - it is encrypted with a key the app
     * keeps to itself.
     *
     * Answered by extension, the way every other question this page asks about a file is,
     * and deliberately a short list: the types that are always a program's and never a
     * person's, rather than a guess at what is interesting. A chat backup is numbered by
     * its format - crypt12 through crypt15 so far, and another every year or two - so that
     * family is recognised by its shape instead of being listed and going stale.
     *
     * Recents only. Browsing is somebody walking to the file deliberately, and a page that
     * hid what is plainly in the folder would be lying about the disk.
     */
    private fun working(file: File): Boolean {
        val type = file.extension.lowercase(Locale.getDefault())
        if (type in WORKING_TYPES) return true
        return type.length > CRYPT_PREFIX.length &&
            type.startsWith(CRYPT_PREFIX) &&
            type.drop(CRYPT_PREFIX.length).all { it.isDigit() }
    }

    /**
     * Which folder a file was found in, said the way the browse page says a trail - the
     * volume left off for the same reason, unless there is a card to tell it from.
     *
     * The one exception is a file sitting in the root itself, where there is no folder to
     * name: the volume is then the only true answer to where it is.
     */
    private fun whereOf(file: File): String {
        val holding = file.parentFile ?: return ""
        val rootHere = roots.firstOrNull { holding.absolutePath.startsWith(it.dir.absolutePath) }
            ?: return holding.name
        if (holding == rootHere.dir) return rootHere.label
        val below = holding.absolutePath
            .removePrefix(rootHere.dir.absolutePath)
            .trim(File.separatorChar)
            .split(File.separatorChar)
        val steps = if (roots.size > 1) listOf(rootHere.label) + below else below
        return steps.joinToString(SEPARATOR)
    }

    /**
     * Drops what the two flat pages are pointing at that is no longer there, without
     * walking anything.
     *
     * A delete, a move or a rename leaves them describing files and folders that have gone,
     * and the honest fix - look again - is a walk of the whole phone to learn one thing the
     * app already knows. A stat call per row and per album answers it instead, and the next
     * walk is due anyway.
     *
     * An album emptied while the reader is standing in it is the one case worth handling
     * outright: the page they are on has become a folder that no longer exists, so they are
     * put back on the wall rather than left looking at it.
     */
    private fun pruneRecents() {
        // The picture being looked at, deleted from the strip under it. Nothing else can
        // put the viewer away, because the delete it was asked for is what closes it.
        if (viewer.showing()?.exists() == false) viewer.hide()
        val liveRecents = recents.filter { it.file.exists() }
        if (liveRecents.size != recents.size) {
            recents = liveRecents
            fillRecents()
        }
        val liveAlbums = albums.filter { it.dir.exists() }
        val leftAlbum = album?.exists() == false
        if (leftAlbum) album = null
        if (liveAlbums.size != albums.size || leftAlbum) {
            albums = liveAlbums
            titleThePage()
            fillPhotos()
            installBar()
        }
    }

    private fun fillRecents() {
        if (!::recentsColumn.isInitialized) return
        recentsColumn.removeAllViews()
        // Only this page's queue. The browse list may be part-way through its own.
        waitingRows.removeAll { it.scroller === recentsScroller }
        picks[PAGE_RECENTS].clear()

        if (recents.isEmpty()) {
            say(
                recentsColumn,
                if (scanning) "looking for what is new\u2026"
                else "nothing found on this phone"
            )
            return
        }
        for (entry in recents) recentsColumn.addView(recentRow(entry), wide())
        handler.post { askForPicturesInView() }
    }

    // ---------------------------------------------------------------------- the photos

    private fun fillPhotos() {
        if (!::photosColumn.isInitialized) return
        photosColumn.removeAllViews()
        // Only this page's queue. The two lists beside it may be part-way through theirs.
        waitingRows.removeAll { it.scroller === photosScroller }
        picks[PAGE_PHOTOS].clear()

        val inside = album
        if (inside == null) fillAlbumWall() else fillAlbum(inside)
        handler.post { askForPicturesInView() }
    }

    /** Every folder with pictures in it, two tiles across. */
    private fun fillAlbumWall() {
        if (albums.isEmpty()) {
            say(
                photosColumn,
                if (scanning) "looking for pictures\u2026" else "no pictures on this phone"
            )
            return
        }
        val gap = dp(TILE_GAP_DP)
        val side = cellSide(ALBUM_COLUMNS, gap)
        for (row in albums.chunked(ALBUM_COLUMNS)) {
            layGrid(row, ALBUM_COLUMNS, gap) { a, strip -> albumTile(a, strip, side) }
        }
    }

    /** The pictures in one album, four across. */
    private fun fillAlbum(inside: File) {
        if (!inside.canRead()) {
            say(photosColumn, "this album cannot be opened")
            return
        }
        val shots = picturesIn(inside)
        if (shots.isEmpty()) {
            say(photosColumn, "no pictures here")
            return
        }
        val gap = dp(PHOTO_GAP_DP)
        val side = cellSide(PHOTO_COLUMNS, gap)
        for (row in shots.chunked(PHOTO_COLUMNS)) {
            layGrid(row, PHOTO_COLUMNS, gap) { shot, strip -> photoCell(shot, strip, side) }
        }
    }

    /**
     * One row of a grid, laid on the page.
     *
     * The gap is a margin on every cell but the last, so a full row runs exactly from the
     * page's left margin to its right one - a gap after the final cell would push the whole
     * row narrow and leave the grid sitting off-centre by the width of it.
     *
     * A short last row is padded out with nothing rather than stretched. The wall is a
     * grid, and a grid with one wide cell in the corner has stopped being one.
     */
    private fun <T> layGrid(
        row: List<T>,
        columns: Int,
        gap: Int,
        build: (T, LinearLayout) -> View
    ) {
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, gap)
        }
        for ((i, item) in row.withIndex()) {
            strip.addView(build(item, strip), cell(if (i == columns - 1) 0 else gap))
        }
        for (i in row.size until columns) {
            strip.addView(View(context), cell(if (i == columns - 1) 0 else gap))
        }
        photosColumn.addView(strip, wide())
    }

    /**
     * What is in an album, newest first.
     *
     * Read here rather than carried over from the walk, because the walk keeps only the
     * handful a tile draws - and because by the time somebody opens an album the listing
     * behind it may be minutes old. One readdir is the whole cost.
     *
     * Newest first whatever the browse listing is sorted by, and that is not the sort
     * command being ignored: a wall of pictures *is* a chronology, the tile that was
     * tapped showed the newest of them, and a page that opened at the oldest picture in
     * the folder would be answering a question nobody asked. Browse is where a folder is
     * put in some other order, and it has all of the same files in it.
     */
    private fun picturesIn(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        return all.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .filter { isPicture(it) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    private fun cell(gap: Int) = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
        marginEnd = gap
    }

    /**
     * How wide one cell of a grid comes out, in pixels.
     *
     * Only ever used to decide how large a picture to read from disk - the cells themselves
     * are laid out by weight, and are square because they measure themselves that way, so
     * nothing on screen depends on this being exactly right. Which is just as well: the
     * first fill of a page happens before it has been measured, and the width of the window
     * is the best answer available then.
     */
    private fun cellSide(columns: Int, gap: Int): Int {
        // The scroller's width still has the column's own right margin inside it; the
        // fallback starts from the window and takes off the panorama's left margin as well.
        val across =
            if (::photosScroller.isInitialized && photosScroller.width > 0)
                photosScroller.width - dp(PAGE_MARGIN_DP)
            else context.resources.displayMetrics.widthPixels - dp(PAGE_MARGIN_DP) * 2
        return ((across - gap * (columns - 1)) / columns).coerceAtLeast(dp(48))
    }

    /**
     * An album, drawn as the phone drew one: a square of what is newest in it, with its
     * name written across the foot.
     *
     * The mosaic is the album's own contents rather than a folder glyph, because a wall of
     * identical folder marks would be a list of names in a costume - the pictures are the
     * only thing that tells one album from another at a glance, and a phone's albums are
     * mostly told apart by what is in them rather than by what they are called.
     *
     * Sixteen of them where there are sixteen. Below that the lattice steps down to
     * whatever square fills completely - nine, four, one - so a folder holding three
     * photographs shows three large ones rather than three in the corner of an empty grid.
     */
    private fun albumTile(a: Album, strip: LinearLayout, side: Int): View {
        val tile = Square(context)

        val lattice = latticeFor(a.shots.size)
        val cellPx = side / lattice
        val mosaic = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until lattice) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until lattice) {
                val at = r * lattice + c
                val shot = a.shots.getOrNull(at)
                val square = FrameLayout(context).apply { setBackgroundColor(emptyCell()) }
                // No clip mark inside the mosaic: at a sixteenth of a tile it would be a
                // smudge, and the tile is a portrait of the album rather than a list of
                // what each thing in it is.
                if (shot != null) pictureIn(square, shot, strip, cellPx, playDp = 0)
                line.addView(square, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            mosaic.addView(line, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        tile.addView(mosaic, FrameLayout.LayoutParams(MATCH, MATCH))
        tile.addView(namePlate(a), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        tileMarks(tile, a.dir, resting = Color.TRANSPARENT)

        tile.isClickable = true
        tile.setOnClickListener { if (selecting) toggle(a.dir) else openAlbum(a.dir) }
        tile.setOnLongClickListener { hold(a.dir) }
        TiltEffect.apply(tile)
        return tile
    }

    /**
     * The largest complete square that can be drawn from [shots] pictures.
     *
     * A full lattice or none of it. Fifteen pictures in a four-by-four leaves a hole in the
     * corner, and a hole reads as a picture that failed to load rather than as a folder
     * that happens to hold fifteen things.
     */
    private fun latticeFor(shots: Int): Int = when {
        shots >= 16 -> 4
        shots >= 9 -> 3
        shots >= 4 -> 2
        else -> 1
    }

    /**
     * The album's name across the foot of its tile.
     *
     * White on a scrim that fades upward into the picture, which is how the phone wrote
     * over artwork everywhere it did: a solid band would be a caption stuck under a
     * photograph, while a fade leaves the tile reading as one picture with a name on it.
     * White rather than the theme's ink for the same reason the clip mark is - what is
     * behind it is a photograph, and a photograph has no theme.
     */
    private fun namePlate(a: Album): View {
        val plate = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(android.graphics.Color.TRANSPARENT, SCRIM_DEEP)
            )
            setPadding(dp(10), dp(18), dp(10), dp(8))
        }
        plate.addView(TextView(context).apply {
            text = a.label
            typeface = font(R.font.segoeui_semilight)
            textSize = 17f
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, wide())
        plate.addView(TextView(context).apply {
            text = if (a.count == 1) "1 picture" else "${a.count} pictures"
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            setTextColor(SUBTLE_ON_PICTURE)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        }, wide())
        return plate
    }

    /**
     * One photograph in an opened album.
     *
     * Nothing written on it. The name of a photograph is the one fact about it worth
     * nobody's attention, and four captions to a row would take more of the page than the
     * pictures do - what a caption would have said is on the row in browse, which is one
     * swipe away and is the section for reading about files.
     */
    private fun photoCell(shot: File, strip: LinearLayout, side: Int): View {
        val square = Square(context).apply { setBackgroundColor(emptyCell()) }
        pictureIn(square, shot, strip, side, playDp = PLAY_ON_PHOTO_DP)
        // After the picture, so the mark stays on top of it however late it arrives.
        tileMarks(square, shot, resting = emptyCell())

        square.isClickable = true
        square.setOnClickListener { if (selecting) toggle(shot) else openFile(shot) }
        square.setOnLongClickListener { hold(shot) }
        TiltEffect.apply(square)
        return square
    }

    /**
     * What a picked-out tile looks like: pushed into a block of the accent.
     *
     * The phone's own answer, and it survives being drawn over a photograph - a tick alone
     * on a bright picture is a tick nobody sees, while a tile that has visibly shrunk into
     * a coloured frame reads across the whole wall at once.
     */
    private fun dressForSelection(tile: FrameLayout, picked: Boolean, resting: Int) {
        if (picked) {
            tile.setBackgroundColor(palette.accent)
            val inset = dp(SELECT_INSET_DP)
            tile.setPadding(inset, inset, inset, inset)
        } else {
            // Back to the ground it had, because the accent was painted on the same frame:
            // a cell whose picture has not arrived would otherwise stay a block of it.
            tile.setBackgroundColor(resting)
            tile.setPadding(0, 0, 0, 0)
        }
    }

    /**
     * Lets select mode mark a tile where it stands: the tick in its corner while its page
     * is being picked from, and the block of accent around it once it is picked.
     *
     * [resting] is what the tile is painted when it is not picked. See [dressForSelection].
     */
    private fun tileMarks(tile: FrameLayout, file: File, resting: Int) {
        var tick: View? = null
        pickable(PAGE_PHOTOS, file) { marked, picked ->
            dressForSelection(tile, marked && picked, resting)
            if (marked) {
                val mark = tick ?: View(context).also {
                    tile.addView(it, tickParams())
                    tick = it
                }
                paintMark(mark, picked)
            } else {
                tick?.let { tile.removeView(it) }
                tick = null
            }
        }
    }

    private fun tickParams() = FrameLayout.LayoutParams(
        dp(MetroMarker.SIZE_DP), dp(MetroMarker.SIZE_DP), Gravity.TOP or Gravity.END
    ).apply {
        val edge = dp(6)
        setMargins(edge, edge, edge, edge)
    }

    /** The flat block a cell shows until its picture has been read off the disk. */
    private fun emptyCell(): Int = if (palette.isDark) EMPTY_ON_DARK else EMPTY_ON_LIGHT

    /**
     * Puts a picture into [slot], as an image that is a plain block until it arrives.
     *
     * No glyph behind it, unlike a row: sixteen page marks in a mosaic, or forty in a grid,
     * is a page of noise saying what the section already said. The block is what a picture
     * that has not been read yet looks like, and it is what one that cannot be read stays.
     *
     * The image goes into the slot here rather than being handed back to be added, and
     * that is not tidiness: a picture already in the cache is shown from inside this call,
     * and showing a clip lays its play mark in the same slot. Added afterwards, the image
     * would go in over the mark and hide it on exactly the frames that had been seen before.
     */
    private fun pictureIn(
        slot: FrameLayout,
        shot: File,
        strip: LinearLayout,
        sizePx: Int,
        playDp: Int
    ) {
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        slot.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        val kind = FileThumbnails.kindOf(shot) ?: return
        val waiting = Pending(strip, slot, image, shot, kind, photosScroller, sizePx, playDp)
        val held = FileThumbnails.held(shot, sizePx)
        if (held != null) show(waiting, held, fade = false) else waitingRows += waiting
    }

    // ------------------------------------------------------------------------- the rows

    private fun rootRow(r: Root): View {
        val row = rowShell(onTap = { navigateTo(r.dir) })
        row.addView(glyph(r.icon), glyphParams())
        row.addView(labels(r.label, spaceOn(r.dir)), textParams())
        return row
    }

    private fun fileRow(entry: File): View {
        val row = rowShell(
            onTap = {
                if (selecting) toggle(entry)
                else if (entry.isDirectory) navigateTo(entry)
                else openFile(entry)
            },
            onHold = { hold(entry) }
        )
        row.addView(iconSlot(row, entry, scroller), glyphParams())
        row.addView(labels(entry.name, detailOf(entry)), textParams())
        rowMarks(row, PAGE_BROWSE, entry)
        return row
    }

    /**
     * A row on the recents page.
     *
     * Selectable like any other row, and the four things worth doing to several files at
     * once all work from here: what a copy, a cut, a delete or a share needs is the files
     * themselves, and these have full paths like every other file on the phone. The two
     * commands that are not about the files but about the folder you are standing in -
     * making one, pasting into one - are the ones this page cannot answer, and they stand
     * on the strip dead rather than absent, so the strip is the same strip everywhere.
     */
    private fun recentRow(entry: Recent): View {
        val row = rowShell(
            onTap = { if (selecting) toggle(entry.file) else openFile(entry.file) },
            onHold = { hold(entry.file) }
        )
        row.addView(iconSlot(row, entry.file, recentsScroller), glyphParams())
        row.addView(labels(entry.file.name, detailOfRecent(entry)), textParams())
        rowMarks(row, PAGE_RECENTS, entry.file)
        return row
    }

    private fun rowShell(
        onTap: () -> Unit,
        onHold: (() -> Boolean)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), dp(PAGE_MARGIN_DP), dp(9))
            isClickable = true
            // No buzz for opening something. The phone answered a command with one - a
            // thing deleted, a hold that picked something out - and going into a folder is
            // not a command, it is just going somewhere.
            setOnClickListener { onTap() }
            if (onHold != null) setOnLongClickListener { onHold() }
        }
        TiltEffect.apply(row)
        return row
    }

    private fun labels(name: String, detail: String): View {
        val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(TextView(context).apply {
            text = name
            typeface = font(R.font.segoeui_semilight)
            textSize = 21f
            setTextColor(palette.foreground)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, wide())
        stack.addView(TextView(context).apply {
            text = detail
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foregroundSubtle)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }, wide())
        return stack
    }

    private fun glyph(
        asset: String,
        slotDp: Int = ICON_DP,
        inkDp: Int = GLYPH_DP,
        tint: Int = palette.accent
    ): ImageView {
        val view = ImageView(context)
        val drawable = SvgIcon.fromAsset(context, asset)
        view.setImageDrawable(drawable)
        // The set is drawn white for tiles. A file list is not always dark, so the marks
        // take the accent here rather than vanishing on a Light theme.
        view.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        if (drawable == null) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return view
        }
        placeGlyph(view, drawable, asset, slotDp, inkDp)
        return view
    }

    /**
     * Puts a mark on its row at the size it should be, wherever its ink sits in the canvas.
     *
     * These icons cover about half of the 76-unit square they are drawn on, and not the
     * same half each time - a folder is wide and short, a page is tall and narrow, and both
     * carry their own margin. Fitting the canvas to the row therefore fits the *margin* to
     * the row, and the mark comes out small, off-centre, and a different size on every line.
     *
     * So the measured ink box is what gets placed, exactly as the app list places a tile's
     * glyph: its longer side scaled to [inkDp] and its centre put at the middle of the
     * [slotDp] square it is being drawn in. By matrix rather than by padding, because a
     * mark covering half its canvas needs a canvas twice the slot to show at the right
     * size, and padding cannot give it one.
     */
    private fun placeGlyph(
        view: ImageView,
        drawable: Drawable,
        asset: String,
        slotDp: Int,
        inkDp: Int
    ) {
        val ink = icons.inkFor("files:$asset", drawable)
        val canvasW = drawable.intrinsicWidth.toFloat()
        val canvasH = drawable.intrinsicHeight.toFloat()
        // Artwork that drew nothing, or that will not say how large it is: an ImageView
        // ignores the matrix for a drawable with no intrinsic size, so the plain fit is the
        // only honest answer.
        if (ink == null || canvasW <= 0f || canvasH <= 0f) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        val inkW = ink.width() * canvasW
        val inkH = ink.height() * canvasH
        val scale = dp(inkDp) / maxOf(inkW, inkH)
        val centre = dp(slotDp) / 2f
        view.scaleType = ImageView.ScaleType.MATRIX
        view.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                centre - scale * (ink.left * canvasW + inkW / 2f),
                centre - scale * (ink.top * canvasH + inkH / 2f)
            )
        }
    }

    /**
     * Lets select mode put the square beside a row, and take it away again, where the row
     * stands - see [picks].
     */
    private fun rowMarks(row: LinearLayout, forPage: Int, file: File) {
        var box: View? = null
        pickable(forPage, file) { marked, picked ->
            if (marked) {
                val mark = box ?: View(context).also {
                    row.addView(it, 0, checkParams())
                    box = it
                }
                paintMark(mark, picked)
            } else {
                box?.let { row.removeView(it) }
                box = null
            }
        }
    }

    /**
     * The square beside a row in select mode, and in the corner of a tile.
     *
     * The shell's own square, the same one the settings page draws beside a switch: off is
     * an outline, on is a block of the accent with a tick in it. Drawn here rather than
     * again from scratch, because a checkbox that is nearly the settings one is a checkbox
     * the user has to look twice at to be sure it means the same thing.
     */
    private fun paintMark(mark: View, on: Boolean) {
        mark.background = MetroMarker.drawable(context, palette, round = false, on = on)
    }

    // ------------------------------------------------------------------- the thumbnails

    /**
     * A row whose mark should become a picture, once the list is sure it is worth reading.
     *
     * The row itself is held as well as the slot, because whether to read the file is a
     * question about where the row is: only rows at or near the window are asked for.
     */
    private class Pending(
        /**
         * What has to be near the window for this picture to be worth reading.
         *
         * The row on a list, and on the photos page the strip of cells the picture is one
         * of - because the test is a comparison against the scroller's own coordinates, and
         * only a direct child of the scrolling column has any. Several cells of one strip
         * therefore share it, which is right: they come into view together.
         */
        val row: View,
        val slot: FrameLayout,
        val image: ImageView,
        val file: File,
        val kind: FileThumbnails.Kind,
        /** Which list the row is in - three of them scroll now, and independently. */
        val scroller: ScrollView,
        /** How large to read it. A cell of the photo grid is not a mark on a row. */
        val sizePx: Int,
        /** How big the clip mark goes, or 0 where the picture is too small to carry one. */
        val playDp: Int
    )

    /** Rows in this listing that could show a picture and have not been given one yet. */
    private val waitingRows = mutableListOf<Pending>()

    /**
     * What goes in the mark's place on a row.
     *
     * A glyph, always, straight away - the list is drawn in the frame it is asked for and
     * nothing is read from disk to do it. For a file with a picture inside it, that glyph
     * goes into a slot and the row joins the queue; the picture replaces it when and if it
     * arrives. Which means the answer to a file that cannot be read, or is not really a
     * JPEG, is the same thing the app drew before: its type's mark.
     */
    private fun iconSlot(row: View, entry: File, within: ScrollView): View {
        val mark = glyph(iconFor(entry))
        val kind = FileThumbnails.kindOf(entry) ?: return mark
        val slot = FrameLayout(context)
        slot.addView(mark, FrameLayout.LayoutParams(MATCH, MATCH))
        val size = dp(ICON_DP * DECODE_OVER)
        val waiting = Pending(row, slot, mark, entry, kind, within, size, PLAY_DP)
        // Read once already: this listing is being rebuilt rather than opened, so the
        // picture goes back as the row is drawn. Waiting for the queue instead would mean
        // a glyph flashing in place of every photograph on every sort, rename and refresh.
        val held = FileThumbnails.held(entry, size)
        if (held != null) show(waiting, held, fade = false) else waitingRows += waiting
        return slot
    }

    /**
     * Asks for the pictures of the rows the user can see, and a screenful either side.
     *
     * The whole reason the queue exists. A folder of two thousand photographs is two
     * thousand files that would each have to be opened and decoded to draw a list nobody
     * has scrolled yet; this reads the dozen on the screen, and reads the next dozen as
     * they come up. The margin is what keeps a picture from arriving visibly late during
     * an ordinary scroll.
     */
    private fun askForPicturesInView() {
        if (released || waitingRows.isEmpty()) return
        val margin = dp(LOOKAHEAD_DP)
        val queue = waitingRows.iterator()
        while (queue.hasNext()) {
            val waiting = queue.next()
            val list = waiting.scroller
            // Nothing has been laid out yet, so nothing can be said about what is in view.
            // Something laid out later will call this again.
            val window = list.height
            if (window == 0) continue
            val row = waiting.row
            if (row.height == 0) continue
            if (row.bottom < list.scrollY - margin) continue
            if (row.top > list.scrollY + window + margin) continue
            queue.remove()
            askFor(waiting)
        }
    }

    /** Anything reaching here had to be read from disk, so it arrives with a fade. */
    private fun askFor(waiting: Pending) {
        FileThumbnails.load(waiting.file, waiting.kind, waiting.sizePx) { picture ->
            if (released || picture == null) return@load
            show(waiting, picture, fade = true)
        }
    }

    private fun show(waiting: Pending, picture: Bitmap, fade: Boolean) {
        val image = waiting.image
        // The glyph was tinted with the accent; a photograph is not.
        image.imageTintList = null
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setImageBitmap(picture)
        // A frame from a clip looks exactly like a photograph, and the row would be saying
        // the file is something it is not. The mark says which it is, the way the phone's
        // own camera roll did.
        var mark: View? = null
        if (waiting.kind == FileThumbnails.Kind.VIDEO && waiting.playDp > 0) {
            val ink = waiting.playDp * PLAY_INK_DP / PLAY_DP
            mark = playMark(waiting.playDp, ink)
            waiting.slot.addView(mark, FrameLayout.LayoutParams(
                dp(waiting.playDp), dp(waiting.playDp), Gravity.BOTTOM or Gravity.START))
        }
        if (!fade) return
        // The picture and its mark, rather than the slot holding them. On a row those are
        // the same thing, but a cell of the photo grid *is* the slot - fading that would
        // blink the flat block standing in for the picture out to the page behind it and
        // then bring the photograph up from nothing, which reads as the cell flickering.
        for (view in listOfNotNull<View>(image, mark)) {
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    /** The little triangle in the corner of a clip's frame, on a scrim so it reads. */
    private fun playMark(sizeDp: Int, inkDp: Int): View =
        glyph(PLAY_ICON, sizeDp, inkDp, Color.WHITE).apply { setBackgroundColor(SCRIM) }

    /**
     * A frame that is as tall as it is wide.
     *
     * Every cell of both grids on the photos page. Measured rather than worked out in
     * pixels because the cells are laid out by weight - the page has to divide a width it
     * only learns at layout, and a square asked for in pixels beforehand would be a square
     * on one screen and a rectangle on the next.
     */
    private class Square(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, widthSpec)
        }
    }

    // --------------------------------------------------------------------- what a row says

    private fun detailOf(entry: File): String {
        val changed = whenOf(entry.lastModified())
        if (!entry.isDirectory) return "${sizeOf(entry.length())}   $changed"
        // Names only rather than File objects: a listing is one readdir either way, and
        // this one does not need a stat per child to be counted.
        val count = entry.list()?.size
        return when (count) {
            null -> changed
            0 -> "empty   $changed"
            1 -> "1 item   $changed"
            else -> "$count items   $changed"
        }
    }

    /** How much room is left, for the roots page - the one place the question is asked. */
    private fun spaceOn(dir: File): String = try {
        val free = dir.freeSpace
        val total = dir.totalSpace
        if (total <= 0L) "" else "${sizeOf(free)} free of ${sizeOf(total)}"
    } catch (e: Exception) {
        ""
    }

    private fun sizeOf(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        // One decimal place under ten, none above it: "1.4 MB" is worth knowing and
        // "847.3 MB" is three digits of noise on a number nobody reads that closely.
        return if (value < 10) String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
        else String.format(Locale.getDefault(), "%.0f %s", value, units[unit])
    }

    /**
     * What a recents row says under the name: when, how big, and - the point of the page -
     * where it turned out to be.
     *
     * Where comes last because it is the longest and the least fixed: a trail four folders
     * deep runs off the end of the row, and what runs off the end should be the part the
     * reader can get the rest of by holding the row.
     */
    private fun detailOfRecent(entry: Recent): String {
        val parts = listOf(whenExactly(entry.at), sizeOf(entry.size), entry.where)
        return parts.filter { it.isNotEmpty() }.joinToString("   ")
    }

    /** Built once: a folder of a thousand files is a thousand rows asking for a date. */
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    /** The same, for a file from today. See [whenExactly]. */
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun whenOf(at: Long): String =
        if (at <= 0L) "" else dateFormat.format(java.util.Date(at))

    /**
     * When something arrived, at the resolution that tells this page's rows apart.
     *
     * Most of a recents list is today, and a column of one repeated date sorts nothing: the
     * clock is what separates this morning's download from the one before breakfast. Past
     * today the clock stops meaning anything and the date takes over - by calendar day
     * rather than by hours elapsed, because "yesterday" is a day and not a duration.
     */
    private fun whenExactly(at: Long): String {
        if (at <= 0L) return ""
        val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val now = java.util.Calendar.getInstance()
        val today = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        return if (today) timeFormat.format(java.util.Date(at)) else dateFormat.format(java.util.Date(at))
    }

    /**
     * The mark for a file, which for some files is only what stands there until its own
     * picture arrives.
     *
     * The three kinds that can show themselves are asked about through [FileThumbnails],
     * so there is one list of what counts as a picture rather than two lists that would
     * drift - a file drawn with the camera mark and never thumbnailed, or the other way
     * round, is exactly what a second list eventually produces.
     */
    private fun iconFor(entry: File): String {
        if (entry.isDirectory) return FOLDER_ICON
        FileThumbnails.kindOf(entry)?.let {
            return when (it) {
                FileThumbnails.Kind.IMAGE -> IMAGE_ICON
                FileThumbnails.Kind.VIDEO -> VIDEO_ICON
                FileThumbnails.Kind.DOCUMENT -> PDF_ICON
            }
        }
        return when (entry.extension.lowercase(Locale.getDefault())) {
            "mp3", "wav", "ogg", "m4a", "flac", "aac", "wma", "opus", "mid", "midi" -> AUDIO_ICON
            "txt", "md", "log", "json", "xml", "csv", "ini", "cfg" -> TEXT_ICON
            "zip", "rar", "7z", "tar", "gz", "apk" -> ARCHIVE_ICON
            else -> FILE_ICON
        }
    }

    // -------------------------------------------------------------------------- the strip

    private fun installBar() {
        barSlot.removeAllViews()
        shownBar = null
        syncSelectBar = null

        // One screen has nothing to command: the roots page, whose rows are "phone" and
        // "sd card". Selecting a volume to copy it, or making a folder beside it, are not
        // things anybody means, so it gets no strip at all rather than a row of dead
        // buttons, and its two rows run to the foot of the page instead of stopping above
        // a black band with nothing on it. The padding is the panorama's because it is
        // what holds the pages.
        if (!selecting && page == PAGE_BROWSE && current == null) {
            panorama.setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            return
        }
        // The list stops above the strip rather than running under it, so the last file is
        // reachable instead of sitting behind a button.
        panorama.setPadding(dp(PAGE_MARGIN_DP), 0, 0, dp(MetroAppBar.HEIGHT_DP))

        val bar = MetroAppBar(context, palette)
        if (selecting) fillSelectBar(bar) else fillBrowseBar(bar)
        barSlot.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP))
        shownBar = bar
    }

    /**
     * The ordinary strip, which is the same three commands on every page that has one.
     *
     * What differs is which of them are alive, and that is a question about the page rather
     * than about the section: making a folder and pasting into one need a folder to be
     * standing in, and two of the four screens that carry a strip are not - the wall of
     * albums and recents are both lists of things from elsewhere. Dead rather than missing,
     * because a strip whose buttons moved about as you swiped would be three strips to
     * learn instead of one.
     */
    private fun fillBrowseBar(bar: MetroAppBar) {
        val here = folderHere()

        val select = bar.addCommand(SELECT_ICON) { beginSelecting() }
        bar.setCommandEnabled(select, !busy && anythingToPick())

        val newFolder = bar.addCommand(NEW_FOLDER_ICON) { askForNewFolder() }
        bar.setCommandEnabled(newFolder, !busy && here?.canWrite() == true)

        val paste = bar.addCommand(PASTE_ICON) { paste() }
        bar.setCommandEnabled(paste, !busy && clipboard.isNotEmpty() && here?.canWrite() == true)

        bar.menu = {
            buildList {
                // Only browse is sorted. The other two pages are an order rather than a
                // list that has been put into one - recents is what is newest and an album
                // is a chronology - so a sort command there would be a command with
                // nothing to change, which is worse than one that is not offered.
                if (page == PAGE_BROWSE) {
                    if (sort != Sort.NAME) add(MetroAppBar.Item("sort by name") { setSort(Sort.NAME) })
                    if (sort != Sort.DATE) add(MetroAppBar.Item("sort by date") { setSort(Sort.DATE) })
                    if (sort != Sort.SIZE) add(MetroAppBar.Item("sort by size") { setSort(Sort.SIZE) })
                }
                add(MetroAppBar.Item(if (showHidden) "hide hidden files" else "show hidden files") {
                    showHidden = !showHidden
                    prefs.edit { putBoolean(KEY_HIDDEN, showHidden) }
                    redrawAll()
                    // The walk leaves hidden files out too, so what it found is now the
                    // answer to a question that is no longer the one being asked.
                    startScan(force = true)
                })
                if (clipboard.isNotEmpty()) {
                    add(MetroAppBar.Item("clear clipboard") {
                        clipboard = emptyList()
                        clipMode = null
                        redraw()
                    })
                }
                add(MetroAppBar.Item("refresh") {
                    redrawAll()
                    startScan(force = true)
                })
            }
        }
    }

    /**
     * Whether this page has anything on it worth picking out.
     *
     * Asked cheaply, and that is the point: the strip is rebuilt every time the page is
     * drawn, and a listing of the folder to find out whether it has one file in it would
     * be a second readdir on every one of those.
     */
    private fun anythingToPick(): Boolean = when (page) {
        PAGE_BROWSE -> current?.canRead() == true
        PAGE_PHOTOS -> album?.canRead() ?: albums.isNotEmpty()
        else -> recents.isNotEmpty()
    }

    /**
     * The strip in select mode: three rings for what can be done to any number of things
     * at once, and behind the dots, everything else.
     */
    private fun fillSelectBar(bar: MetroAppBar) {
        val copy = bar.addCommand(COPY_ICON) { takeToClipboard(ClipMode.COPY) }
        val cut = bar.addCommand(CUT_ICON) { takeToClipboard(ClipMode.CUT) }
        val remove = bar.addCommand(DELETE_ICON) { askToDelete(selection.toList()) }

        // Kept rather than run once, because every tick changes what the rings can do - and
        // re-arming three of them is a great deal cheaper than a new strip. See [toggle].
        val sync = {
            val picked = selection.isNotEmpty()
            // Asked of what is picked rather than of the folder the page is in, because on
            // recents there is no such folder and the files are from all over the phone:
            // what decides whether a thing can be moved away is the folder holding it.
            // Each folder once - a whole album picked out is one folder, not a thousand.
            val movable = picked &&
                selection.mapTo(HashSet()) { it.parentFile }.all { it?.canWrite() == true }
            bar.setCommandEnabled(copy, picked && !busy)
            bar.setCommandEnabled(cut, movable && !busy)
            bar.setCommandEnabled(remove, movable && !busy)
        }
        sync()
        syncSelectBar = sync

        bar.menu = {
            buildList {
                // What holding a thing used to put up in a menu of its own, now that a hold
                // picks it out instead - the way Android's own lists do it. Copy, cut and
                // delete are the rings just above, so the list says the rest rather than
                // saying those twice.
                val only = selection.singleOrNull()
                if (only != null) {
                    addAll(commandsFor(only))
                } else if (selection.isNotEmpty() && selection.none { it.isDirectory }) {
                    add(MetroAppBar.Item("share") { share(selection.toList()) })
                }
                // Everything on the page, as the page drew it - which it has already listed,
                // so finding out costs nothing.
                val all = picks[selectPage].keys
                if (selection.size < all.size) {
                    add(MetroAppBar.Item("select all") {
                        selection.addAll(all)
                        paintPicks(selectPage)
                        titleThePage()
                        syncSelectBar?.invoke()
                    })
                }
                add(MetroAppBar.Item("done") { endSelecting() })
            }
        }
    }

    private fun setSort(to: Sort) {
        sort = to
        prefs.edit { putString(KEY_SORT, to.name) }
        redraw()
    }

    private fun readSort(): Sort = try {
        Sort.valueOf(prefs.getString(KEY_SORT, Sort.NAME.name) ?: Sort.NAME.name)
    } catch (e: IllegalArgumentException) {
        Sort.NAME
    }

    // ---------------------------------------------------------------------- select mode

    /** [first] is the row that asked for select mode, and is picked out on the way in. */
    private fun beginSelecting(first: File? = null) {
        selecting = true
        selectPage = page
        selection.clear()
        if (first != null) selection.add(first)
        // Onto the rows already on the page, rather than the page drawn again with them on:
        // a hold picks out the thing under the finger, and the list should not blink.
        paintPicks(page)
        titleThePage()
        installBar()
    }

    /**
     * Out of select mode. [refresh] false is for a caller about to draw the page itself,
     * which leaves the count and the strip to it.
     */
    private fun endSelecting(refresh: Boolean = true) {
        if (!selecting && selection.isEmpty()) return
        selecting = false
        selection.clear()
        // Off the page the selection was made on, which is not always the one showing, and
        // in place, the way they went on.
        paintPicks(selectPage)
        if (!refresh) return
        titleThePage()
        installBar()
    }

    private fun toggle(entry: File) {
        val picked = entry !in selection
        if (picked) selection.add(entry) else selection.remove(entry)
        // The thing tapped, the count above the list and what the strip allows - and
        // nothing else, because nothing else has changed. See [picks].
        picks[selectPage][entry]?.invoke(marking(selectPage), picked)
        titleThePage()
        // A list opened before the tick is a list about what was picked then.
        shownBar?.closeMenu()
        val sync = syncSelectBar
        if (sync != null) sync() else installBar()
    }

    /**
     * Whether [forPage] wears its marks: only the page the selection was started on does,
     * whichever page happens to be drawing itself.
     */
    private fun marking(forPage: Int) = selecting && selectPage == forPage

    /** Registers how [file] shows itself picked out on [forPage], and shows it so now. */
    private fun pickable(forPage: Int, file: File, paint: (Boolean, Boolean) -> Unit) {
        picks[forPage][file] = paint
        paint(marking(forPage), file in selection)
    }

    /** Puts every mark on [forPage] right for the selection as it now stands. */
    private fun paintPicks(forPage: Int) {
        val marked = marking(forPage)
        for ((file, paint) in picks[forPage]) paint(marked, file in selection)
    }

    // ------------------------------------------------------------------------- holding

    /**
     * A hold on anything in a listing, which picks it out.
     *
     * It used to put up a menu of commands about the thing held, one of which was
     * "select" - the long way round to what a hold is nearly always for, and not how
     * Android's own lists answer one. The hold goes straight into select mode with the
     * held thing already ticked, and the rest of that menu is behind the dots on the strip
     * that comes up: see [commandsFor]. Held again in select mode, it is a tap.
     *
     * Refused while work is running, as the strip's own select command is. What select
     * mode can do goes dead until the work is done, and a copy started on top of another
     * copy is exactly what the dimming is there to prevent.
     */
    private fun hold(entry: File): Boolean {
        if (busy) return false
        if (selecting) toggle(entry) else beginSelecting(entry)
        return true
    }

    /**
     * What can be done to the one thing picked out, besides the rings: the hold menu each
     * section used to put up, now behind the dots.
     *
     * Which section it was picked out on decides a little of it, as it decided the menus.
     * Recents and the wall of albums are lists of things from elsewhere, so they carry the
     * way over to where the thing actually lives; browse is already standing in it.
     */
    private fun commandsFor(only: File): List<MetroAppBar.Item> = buildList {
        val holding = only.parentFile
        val isAlbum = selectPage == PAGE_PHOTOS && album == null
        add(MetroAppBar.Item("open") {
            when {
                isAlbum -> openAlbum(only)
                only.isDirectory -> navigateTo(only)
                else -> {
                    endSelecting()
                    openFile(only)
                }
            }
        })
        if (holding?.canRead() == true) {
            // An album opens in browse as itself rather than as the folder it is in, which
            // is where the rest of what is in it can be seen.
            if (isAlbum) {
                add(MetroAppBar.Item("show in folder") { showInFolder(only) })
            } else if (selectPage == PAGE_RECENTS) {
                add(MetroAppBar.Item("show in folder") { showInFolder(holding) })
            }
        }
        if (holding?.canWrite() == true) add(MetroAppBar.Item("rename") { askToRename(only) })
        if (!only.isDirectory) add(MetroAppBar.Item("share") { share(listOf(only)) })
    }

    /** Over to browse, standing in the folder the file was found in. */
    private fun showInFolder(folder: File) {
        // The section is claimed before the folder is opened, so the strip browse wants is
        // already under the page when it arrives rather than appearing beneath the list a
        // moment later and shortening it in front of the reader.
        page = PAGE_BROWSE
        navigateTo(folder, swing = false)
        // Animated: this is a move the user asked for from a page they can see, and it
        // should look like the swipe they would otherwise have made themselves.
        panorama.goTo(PAGE_BROWSE, animated = true)
    }

    // ------------------------------------------------------------------------- commands

    /**
     * A file tapped, wherever it was tapped.
     *
     * Everything goes out to the phone except a picture, which opens here. See
     * [PhotoViewer] for why that one is the exception - and why it is the exception in
     * every section rather than only on the photos page. The same JPEG found by browsing
     * to it, by seeing it near the top of recents and by tapping it on a wall of them is
     * one file, and an app that opened it three different ways depending on which door it
     * came through would be three apps.
     *
     * A clip is not a picture for this purpose. A viewer that showed the first frame of a
     * video and called that opening it would be worse than the chooser it replaced, and
     * playing one is a program this app has no business being.
     */
    private fun openFile(file: File) {
        if (!file.canRead()) {
            onNotify("Files", "${file.name} cannot be opened")
            return
        }
        if (FileThumbnails.kindOf(file) == FileThumbnails.Kind.IMAGE) {
            viewer.show(file, standInFor(file))
            return
        }
        onOpen(file)
    }

    /**
     * The thumbnail of [file] that whichever page was tapped has already read, if it has.
     *
     * Two sizes are asked for because two kinds of page open pictures: a cell of the photo
     * grid, and a mark on a row. Whichever of them the tap came from is holding one, and
     * it goes up in the same frame while the full picture is read. Nothing is decoded
     * here - a miss simply means the viewer opens on black for a moment.
     */
    private fun standInFor(file: File): Bitmap? =
        FileThumbnails.held(file, cellSide(PHOTO_COLUMNS, dp(PHOTO_GAP_DP)))
            ?: FileThumbnails.held(file, dp(ICON_DP * DECODE_OVER))

    private fun askForNewFolder() {
        val here = folderHere() ?: return
        dialog.show("new folder", "") { typed ->
            val name = typed.trim()
            if (!nameIsUsable(name)) return@show
            val made = File(here, name)
            if (made.exists()) {
                onNotify("Files", "There is already something called $name here")
                return@show
            }
            if (made.mkdir()) redraw()
            else onNotify("Files", "Could not make $name")
        }
    }

    private fun askToRename(entry: File) {
        dialog.show("rename", entry.name) { typed ->
            val name = typed.trim()
            if (!nameIsUsable(name) || name == entry.name) return@show
            val renamed = File(entry.parentFile, name)
            if (renamed.exists()) {
                onNotify("Files", "There is already something called $name here")
                return@show
            }
            if (entry.renameTo(renamed)) {
                // The old file is the one that was picked out, and it no longer exists.
                selection.remove(entry)
                if (selecting) selection.add(renamed)
                // An album can be what was renamed, and the wall names it.
                if (album == entry) album = renamed
                redrawAll()
                pruneRecents()
            } else {
                onNotify("Files", "Could not rename ${entry.name}")
            }
        }
    }

    /**
     * Whether a typed name can be a file at all, said back to the user where it cannot.
     *
     * Only the rules the filesystem actually has - not empty, not one of the two names
     * every directory already answers to, and no separator in it. Everything else a name
     * could be is the user's business, including the ones they will regret.
     */
    private fun nameIsUsable(name: String): Boolean = when {
        name.isEmpty() -> false
        name == "." || name == ".." -> {
            onNotify("Files", "That name is not allowed")
            false
        }
        name.contains(File.separatorChar) -> {
            onNotify("Files", "A name cannot contain ${File.separator}")
            false
        }
        else -> true
    }

    private fun takeToClipboard(mode: ClipMode, what: List<File> = selection.toList()) {
        if (what.isEmpty()) return
        clipboard = what
        clipMode = mode
        val many = what.size > 1
        onNotify(
            "Files",
            if (mode == ClipMode.COPY) {
                if (many) "${what.size} items copied" else "${what.first().name} copied"
            } else {
                if (many) "${what.size} items ready to move" else "${what.first().name} ready to move"
            }
        )
        endSelecting()
    }

    private fun askToDelete(what: List<File>) {
        if (what.isEmpty()) return
        val subject = if (what.size == 1) what.first().name else "${what.size} items"
        // Named after what it is about to do rather than "OK", because the one word the
        // user reads before answering should be the answer.
        dialog.confirm(
            "delete",
            if (what.size == 1 && what.first().isDirectory)
                "Delete $subject and everything in it? This cannot be undone."
            else "Delete $subject? This cannot be undone.",
            "delete"
        ) {
            endSelecting()
            work {
                var gone = 0
                for (entry in what) if (deleteTree(entry)) gone++
                // Anything on the clipboard that has just been deleted would paste into
                // nothing; it leaves with the file.
                clipboard = clipboard.filterNot { it in what }
                if (clipboard.isEmpty()) clipMode = null
                when {
                    gone == what.size && gone == 1 -> "${what.first().name} deleted"
                    gone == what.size -> "$gone items deleted"
                    gone == 0 -> "Nothing could be deleted"
                    else -> "$gone of ${what.size} deleted"
                }
            }
        }
    }

    private fun paste() {
        val here = folderHere() ?: return
        val what = clipboard
        val mode = clipMode
        if (what.isEmpty() || mode == null) return

        // A folder cannot be put inside itself, and the check has to be the canonical path
        // rather than the name: /Music and /Music/Live are different strings that are very
        // much the same problem, and copying one into the other would run until the disk
        // filled up.
        val impossible = what.firstOrNull { swallows(it, here) }
        if (impossible != null) {
            onNotify("Files", "${impossible.name} cannot be put inside itself")
            return
        }

        work {
            var done = 0
            for (entry in what) {
                if (!entry.exists()) continue
                // Something cut and then pasted back where it already was. Left alone and
                // counted as done: the alternative is freeNameIn colliding the file with
                // itself and quietly renaming it to "name (2)", which is not what anybody
                // meant by pasting it into the folder it is already in.
                if (mode == ClipMode.CUT && entry.parentFile == here) {
                    done++
                    continue
                }
                val target = freeNameIn(here, entry.name)
                try {
                    if (mode == ClipMode.CUT) {
                        // A rename is the whole move when both ends are on one volume, and
                        // it is instant. Across volumes it fails and there is no shortcut:
                        // the bytes have to be written and the original taken away after.
                        if (entry.renameTo(target)) {
                            done++
                        } else {
                            copyTree(entry, target)
                            // The bytes are across either way; a source that will not go
                            // away is a stray copy left behind, not a move that failed.
                            deleteTree(entry)
                            done++
                        }
                    } else {
                        copyTree(entry, target)
                        done++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not paste ${entry.name}", e)
                }
            }
            // A cut is spent once it lands. A copy is not: copying one thing into three
            // folders in turn is a thing people do, and emptying the clipboard after the
            // first would make them go back and copy it again.
            if (mode == ClipMode.CUT) {
                clipboard = emptyList()
                clipMode = null
            }
            when {
                done == what.size && done == 1 ->
                    if (mode == ClipMode.CUT) "${what.first().name} moved" else "${what.first().name} pasted"
                done == what.size ->
                    if (mode == ClipMode.CUT) "$done items moved" else "$done items pasted"
                done == 0 -> "Nothing could be pasted"
                else -> "$done of ${what.size} pasted"
            }
        }
    }

    private fun share(what: List<File>) {
        if (what.isEmpty()) return
        try {
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList(what.map { FileProvider.getUriForFile(context, authority, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(what.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "Share")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            endSelecting()
        } catch (e: Exception) {
            // The commonest cause is a file on a memory card: the provider is declared over
            // the phone's own storage, and a card is a different volume it does not cover.
            Log.w(TAG, "Could not share", e)
            onNotify("Files", "This cannot be shared from here")
        }
    }

    // --------------------------------------------------------------------- the file work

    /**
     * Runs something slow off the main thread, with the strip dead while it runs.
     *
     * Copying a folder is not a thing that finishes inside a frame, and doing it on the
     * main thread would freeze the list mid-scroll. The block hands back the sentence to
     * say when it is over, which is said on the way back in.
     */
    private fun work(job: () -> String) {
        busy = true
        installBar()
        Thread {
            val outcome = try {
                job()
            } catch (e: Exception) {
                Log.w(TAG, "File work failed", e)
                "Something went wrong"
            }
            handler.post {
                busy = false
                if (released) return@post
                // An album deleted out from under the page it was open on.
                if (album?.exists() == false) album = null
                redrawAll()
                // A copy, a move or a delete: whatever it was, the recents list may be
                // pointing at something that is no longer where it says it is.
                pruneRecents()
                onNotify("Files", outcome)
            }
        }.start()
    }

    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs()) {
                throw IOException("Could not make ${target.absolutePath}")
            }
            source.listFiles()?.forEach { child -> copyTree(child, File(target, child.name)) }
        } else {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            // A copy of a photograph taken last year is still from last year. Best effort:
            // some filesystems will not have it, and a wrong date is not worth failing over.
            target.setLastModified(source.lastModified())
        }
    }

    private fun deleteTree(entry: File): Boolean {
        if (entry.isDirectory) {
            entry.listFiles()?.forEach { child -> deleteTree(child) }
        }
        return entry.delete()
    }

    /** Whether putting [source] into [target] would put it inside itself. See [paste]. */
    private fun swallows(source: File, target: File): Boolean = try {
        if (!source.isDirectory) false
        else {
            val from = source.canonicalPath
            val into = target.canonicalPath
            into == from || into.startsWith(from + File.separator)
        }
    } catch (e: IOException) {
        // Unreadable either way: refusing is the safe answer to a question that cannot be
        // asked, because the cost of being wrong is a copy that never ends.
        true
    }

    /**
     * A name nothing in [dir] is already using.
     *
     * "photo (2).jpg", with the number before the extension rather than after it, so the
     * copy is still a JPEG to everything that reads one.
     */
    private fun freeNameIn(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val tail = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($n)$tail")
            n++
        }
        return candidate
    }

    private fun mimeOf(file: File): String {
        val extension = file.extension.lowercase(Locale.getDefault())
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension) ?: "*/*"
    }

    // ----------------------------------------------------------------------- the roots

    /**
     * The places this phone keeps files.
     *
     * Its own storage always, and a memory card where there is one. The card is found the
     * only way an app is allowed to find it - the second entry in the private directories
     * the system hands out per volume - and then walked back up to the volume itself,
     * because the private folder is not what anybody came here to look at.
     */
    private fun rootsOf(): List<Root> = buildList {
        val phone = Environment.getExternalStorageDirectory()
        if (phone != null && phone.exists()) add(Root("phone", phone, PHONE_ICON))
        try {
            ContextCompat.getExternalFilesDirs(context, null)
                .filterNotNull()
                .drop(1)
                .mapNotNull { volumeRootOf(it) }
                .filter { it.exists() && it.canRead() }
                .distinctBy { it.absolutePath }
                .forEach { add(Root("sd card", it, CARD_ICON)) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not look for a memory card", e)
        }
    }

    private fun volumeRootOf(privateDir: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val at = privateDir.absolutePath.indexOf(marker)
        return if (at > 0) File(privateDir.absolutePath.substring(0, at)) else null
    }

    // -------------------------------------------------------------------------- plumbing

    private fun font(res: Int) = ResourcesCompat.getFont(context, res)

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun glyphParams() = LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply {
        marginEnd = dp(14)
    }

    private fun checkParams() = LinearLayout.LayoutParams(
        dp(MetroMarker.SIZE_DP), dp(MetroMarker.SIZE_DP)
    ).apply {
        marginEnd = dp(MetroMarker.GAP_DP)
    }

    private fun textParams() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MetroFiles"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        private const val PREFS = "wp81_files"
        private const val KEY_SORT = "files_sort"
        private const val KEY_HIDDEN = "files_show_hidden"

        private const val PAGE_MARGIN_DP = 22

        /**
         * The three sections, in the order the strip lays them out.
         *
         * Recents first, which is where the app opens. Browse is the app's whole job and
         * recents is a shortcut past it - but a shortcut is only worth having if it is what
         * you land on, and the file somebody opens Files to find is usually the one they
         * saved a minute ago.
         *
         * Photos in the middle, between the two shortcuts and the tree. It is the other
         * way of not browsing - the same files reached by looking rather than by reading -
         * so it belongs beside recents, and browse stays at the end where the trail can go
         * as deep as it likes without anything waiting behind it.
         */
        private const val PAGE_RECENTS = 0
        private const val PAGE_PHOTOS = 1
        private const val PAGE_BROWSE = 2
        private const val PAGE_COUNT = 3

        /** How many of the newest files the page holds. A screenful is about eight. */
        private const val RECENTS_MAX = 60

        /**
         * What recents leaves out: databases and the journals kept beside them, logs,
         * half-finished downloads, the copy a program made before rewriting something.
         * See [working].
         */
        private val WORKING_TYPES = setOf(
            "db", "db-journal", "db-wal", "db-shm", "sqlite", "sqlite3", "journal",
            "log", "tmp", "temp", "part", "crdownload", "bak", "thumbdata",
        )

        /** What a chat backup's extension starts with, before the format number. */
        private const val CRYPT_PREFIX = "crypt"

        /** How long a walk's answer stands before the page goes and looks again. */
        private const val RESCAN_MS = 30_000L

        // What the walk is allowed to spend. Whichever runs out first stops it, and what it
        // has by then is what the page shows: the newest files it reached are still the
        // newest files it reached. Sized so that a phone with a full camera roll and a
        // year of downloads is covered several times over, and a pathological one - a
        // checked-out repository, a folder of a hundred thousand somethings - cannot hold
        // the page up for longer than it takes to read the word "recents".
        private const val SCAN_FOLDER_MAX = 6000
        private const val SCAN_BUDGET_MS = 2_500L

        /** The size the platform set an app's own name in, and the shell's page titles. */
        /** Between one step of a trail and the next, in both places one is written. */
        private const val SEPARATOR = "  \u203a  "
        /**
         * The slot a mark sits in on a row, and the square a picture is cropped to.
         *
         * Wider than the mark needs, because a thumbnail fills the whole of it and a
         * photograph at the size of a glyph is not a photograph of anything.
         */
        private const val ICON_DP = 40

        /** The mark itself, measured across its ink rather than its canvas. */
        private const val GLYPH_DP = 24

        /**
         * How much larger than the slot a picture is read at.
         *
         * The platform's thumbnailer fits the whole picture inside the box it is given,
         * while the row crops a square out of the middle of what comes back. Asking for
         * the size of the slot would therefore hand back a 16:9 frame barely half the
         * slot tall, which the row would then have to blow up to fill it. Twice over is
         * enough that anything up to a 2:1 picture still covers the square at full size.
         */
        private const val DECODE_OVER = 2

        /** How far beyond the window a row is still worth reading a picture for. */
        private const val LOOKAHEAD_DP = 400

        /** Long enough to be a fade rather than a flash, short enough not to be a wait. */
        private const val FADE_MS = 160L

        /** The clip mark in the corner of a frame, and its ink inside that. */
        private const val PLAY_DP = 15
        private const val PLAY_INK_DP = 9

        /** The same mark on a photo grid cell, which is four times the size of a row's. */
        private const val PLAY_ON_PHOTO_DP = 26

        /** Behind the clip mark, so a white triangle is not lost in a bright frame. */
        private const val SCRIM = 0x99000000.toInt()

        // ------------------------------------------------------------------ the photos

        /**
         * How many across each of the two walls is.
         *
         * Two for the albums, because an album tile has to be a picture of what is in it
         * and a quarter of the screen is the smallest square that can be - four across
         * would be sixteen photographs in a thumbnail the size of a stamp.
         *
         * Four for the photographs themselves, which is the other half of the same
         * argument: what a page of pictures is for is finding one, and a picture only has
         * to be large enough to be recognised rather than large enough to be looked at.
         * Four across is about how many a hand covers, and it puts a hundred of them in
         * five swipes.
         */
        private const val ALBUM_COLUMNS = 2
        private const val PHOTO_COLUMNS = 4

        /** The most a tile's mosaic can hold: four rows of four. See [latticeFor]. */
        private const val MOSAIC_MAX = 16

        /**
         * Between one tile and the next, and between one photograph and the next.
         *
         * Tighter for the photographs. The gap on a wall of tiles is what says they are
         * separate things; on a wall of pictures the pictures say that themselves, and a
         * wide gutter would only be taking room from them.
         */
        private const val TILE_GAP_DP = 6
        private const val PHOTO_GAP_DP = 3

        /** How far a picked-out tile shrinks into its block of accent. */
        private const val SELECT_INSET_DP = 6

        /** The foot of a tile, under its name: transparent at the top, near-black at the
         *  bottom, so the name reads over whatever the picture happens to be. */
        private const val SCRIM_DEEP = 0xCC000000.toInt()

        /** The count under an album's name - the subtle ink, in the one place on the page
         *  where the ground is a photograph rather than the theme. */
        private const val SUBTLE_ON_PICTURE = 0xB3FFFFFF.toInt()

        /**
         * A cell with no picture in it yet, or none to be had.
         *
         * A flat block a shade off the page rather than a glyph: sixteen page marks in a
         * mosaic is a tile that says nothing about the album, and a grid of them is a page
         * of noise. It is also what an unreadable file settles at, which is the honest
         * answer - the file is there and the phone cannot show it.
         */
        private const val EMPTY_ON_DARK = 0xFF242424.toInt()
        private const val EMPTY_ON_LIGHT = 0xFFDEDEDE.toInt()

        /**
         * What is waiting to be pasted, and whether pasting should also take it away.
         *
         * Static because a clipboard is a fact about the phone rather than about a window.
         * Cutting something, leaving the app and coming back somewhere else to paste is how
         * anybody moves a file, and a clipboard that emptied itself on the way out would
         * make that impossible.
         */
        private var clipboard: List<File> = emptyList()
        private var clipMode: ClipMode? = null

        // Modern UI Icons, the set the rest of the phone shell's marks come from.
        private const val ICON_DIR = "custom_icons_8"
        private const val FOLDER_ICON = "$ICON_DIR/appbar.folder.svg"
        private const val FILE_ICON = "$ICON_DIR/appbar.page.svg"
        private const val TEXT_ICON = "$ICON_DIR/appbar.page.text.svg"
        private const val IMAGE_ICON = "$ICON_DIR/appbar.page.image.svg"
        private const val AUDIO_ICON = "$ICON_DIR/appbar.page.music.svg"
        private const val VIDEO_ICON = "$ICON_DIR/appbar.film.svg"
        private const val PDF_ICON = "$ICON_DIR/appbar.page.file.pdf.svg"
        private const val ARCHIVE_ICON = "$ICON_DIR/appbar.box.svg"
        private const val PHONE_ICON = "$ICON_DIR/appbar.os.windowsphone.svg"
        private const val CARD_ICON = "$ICON_DIR/appbar.cabinet.svg"

        private const val SELECT_ICON = "$ICON_DIR/appbar.list.select.svg"
        private const val NEW_FOLDER_ICON = "$ICON_DIR/appbar.folder.open.svg"
        private const val PASTE_ICON = "$ICON_DIR/appbar.clipboard.paste.svg"
        private const val COPY_ICON = "$ICON_DIR/appbar.page.copy.svg"
        private const val CUT_ICON = "$ICON_DIR/appbar.scissor.svg"
        private const val DELETE_ICON = "$ICON_DIR/appbar.delete.svg"
        private const val PLAY_ICON = "$ICON_DIR/appbar.control.play.svg"
    }
}
