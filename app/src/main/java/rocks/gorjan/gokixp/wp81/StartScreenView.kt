package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import rocks.gorjan.gokixp.R

/**
 * The Windows Phone 8.1 Start screen: a vertically scrolling wall of live tiles.
 *
 * Long-pressing a tile puts the screen into **edit mode** and simultaneously grabs that
 * tile for dragging, exactly as WP8.1 did - one gesture, not two. In edit mode every
 * tile shows an unpin button and a resize chevron, and tapping empty space leaves.
 */
@SuppressLint("ViewConstructor")
class StartScreenView(
    context: Context,
    private var palette: WP81Palette
) : ScrollView(context) {

    var onLaunch: ((Tile) -> Unit)? = null
    var onTilesChanged: ((List<Tile>) -> Unit)? = null
    var onEditModeChanged: ((Boolean) -> Unit)? = null

    /** Pulled down while already at the top of Start. */
    var onSwipeDownAtTop: (() -> Unit)? = null

    /** Pushed up while already at the bottom of Start. */
    var onSwipeUpAtBottom: (() -> Unit)? = null

    private val grid = TileGridLayout(context)

    /**
     * The wall and whatever sits under it, which the scroller actually holds.
     *
     * The grid used to be the scroller's only child. It cannot be any more: the arrow to
     * the app list belongs below the last row and has to scroll with it, and the grid is a
     * packer of tiles rather than a column that will take anything.
     */
    private val content = LinearLayout(context)

    /** The way to the app list, for anyone who has not found the swipe. */
    private val appListArrow = ImageView(context)

    var onOpenAppList: (() -> Unit)? = null

    /**
     * A folder tile was tapped and wants its contents.
     *
     * The wall does not know what is in a folder - the host does - so it asks, and opens
     * whatever comes back in place under the tile. Returning an empty list leaves the
     * folder closed.
     */
    var onFolderOpened: ((Tile) -> Unit)? = null

    /**
     * A folder's own arrangement changed - a tile inside it was resized.
     *
     * Separate from [onTilesChanged] because it is a different list kept in a different
     * place: these tiles are filed inside the folder, not pinned to Start.
     */
    var onFolderTilesChanged: ((String, List<Tile>) -> Unit)? = null

    /**
     * A tile was dropped somewhere that changes which list it belongs to.
     *
     * The folder id it landed in, or null for "out of whatever folder it was in and onto
     * Start". Filing into a closed folder is a drop on an offer that was already showing,
     * never merely a drop on a folder: a tile passing over one on its way somewhere else
     * has not been put in it, and swallowing tiles in transit is the one thing a drag onto
     * a folder must not do.
     */
    var onTileFiled: ((Tile, String?, Boolean) -> Unit)? = null

    /** The name at the head of an opened folder was tapped. */
    var onFolderRename: ((Tile) -> Unit)? = null

    /**
     * Two tiles were held together long enough to become a folder, and then let go.
     *
     * The first is the one that was picked up, the second the one it was held over. A
     * folder is made where the second was standing, with both of them in it.
     */
    var onTilesFoldered: ((Tile, Tile) -> Unit)? = null

    /**
     * What a folder holding these tiles would look like, for the preview shown while they
     * are being held together. Answered by the host, which owns the artwork.
     */
    var folderPreviewOf: ((List<Tile>) -> List<FolderPreviewView.Entry>)? = null

    /**
     * What an existing folder would show with one more tile in it - the folder first, the
     * arrival second - for the preview shown while the two are being held together.
     */
    var folderPreviewWith: ((Tile, Tile) -> List<FolderPreviewView.Entry>)? = null

    private val tiles = mutableListOf<Tile>()

    /**
     * The one tile currently showing its handles, if any.
     *
     * Editing is per-tile rather than a mode the whole screen enters: long-pressing a tile
     * selects exactly that one, and nothing else responds until it is dismissed. Putting
     * handles on every tile at once made it ambiguous which one a drag belonged to.
     */
    private var editingView: TileView? = null

    val isEditMode: Boolean
        get() = editingView != null

    /** The tile currently selected for editing, if any. */
    val editingTile: Tile?
        get() = editingView?.tile

    // Start background, shared by every tile. See TileView.setStartBackground.
    private var startBackground: Bitmap? = null
    private var backgroundFocusX = 0.5f
    private var lastSignature = 0

    /**
     * The crop the whole wall is drawing from: the region of the photo, and the area it is
     * stretched over.
     *
     * Kept so a tile built after the crop was worked out can be handed the same one. The
     * area is not the screen - it is the screen plus the room the photo pans and drifts in
     * (see [pushBackgroundToTiles]) - so a tile given a crop fitted to the screen instead
     * is a window onto a picture that stops at the bottom of it, which is what left the
     * tiles across the fold half black.
     */
    private var backgroundSrc: Rect? = null
    private var backgroundDest = Rect()

    // --- Drift ------------------------------------------------------------------------
    // The photo can be given a little slack in the crop and moved about inside it, which
    // turns a static wallpaper into something the tiles appear to be windows onto. The
    // slack has to be built into the crop: without it the image would come off its edges
    // and leave tiles with nothing behind them.

    /** Total travel, in pixels, on each axis. Zero when the effect is off. */
    private var driftRange = 0

    // Where in that travel the photo currently sits, in pixels from the centre.
    private var driftX = 0f
    private var driftY = 0f

    private val drift = WallpaperDrift(context) { x, y -> applyDrift(x, y) }

    /**
     * Whether the background wanders behind the tiles.
     *
     * Turning it on re-crops the photo: the travel is cut out of the image rather than
     * added around it, so a tile is never left looking past the edge of the picture.
     */
    var driftEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pushBackgroundToTiles()
            syncDrift()
        }

    /**
     * How far the photo actually travels per pixel scrolled.
     *
     * Derived from the pan range the zoom really provides rather than from
     * [PARALLAX_FACTOR] directly: once MAX_OVERSCAN caps the zoom on a long Start screen,
     * travelling at the nominal rate would slide the photo off the top and leave the
     * bottom of the screen bare.
     */
    private var effectiveParallax = 0f

    /**
     * Room kept in the crop at each end for the over-pull, in pixels.
     *
     * The resting offset sits this far into the picture, so the photo has somewhere to go
     * when the wall is pulled off either end of its travel. See [pushBackgroundToTiles].
     */
    private var overscrollSlack = 0

    // Drag state. The grab offset is where inside the tile the finger landed, so the tile
    // keeps that exact spot under the finger for the whole drag.
    private var dragView: TileView? = null
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private val screenOrigin = IntArray(2)
    private var pendingDragView: TileView? = null
    private var pressRawX = 0f
    private var pressRawY = 0f

    /**
     * Whether the press under way set off inside the strip the system keeps for itself.
     *
     * Read by everything a press can turn into. A back drag starts as a finger against the
     * rim, held there for as long as the system takes to make up its mind, and until it
     * does the wall is being handed the touch as if it were meant for it.
     */
    private var pressFromEdge = false
    private var lastMoveRawX = 0f
    private var lastMoveRawY = 0f

    // --- Edge scrolling ----------------------------------------------------------------
    // A tile can be dragged past the bottom of the screen onto a row that is not on it.
    // Without this the page stayed put and there was no way to move a tile down the wall
    // except in screenfuls: drop it, scroll, pick it up again.

    /** Pixels to scroll per frame while the finger is in an edge band. Signed. */
    private var edgeScrollSpeed = 0

    private val edgeScroll = object : Runnable {
        override fun run() {
            val tile = dragView
            if (tile == null || edgeScrollSpeed == 0) return
            val before = scrollY
            scrollBy(0, edgeScrollSpeed)
            if (scrollY != before) {
                // The finger has not moved, but the wall under it has: the tile is placed
                // from absolute coordinates, so it has to be told, and where it now sits
                // may be somebody else's slot.
                followFinger(tile, lastMoveRawX, lastMoveRawY)
                reorderUnder(tile)
            }
            postOnAnimation(this)
        }
    }

    /**
     * Sets the page scrolling when a dragged tile is held near the top or bottom.
     *
     * Speed rises with how far into the band the finger is, so easing towards the edge
     * creeps and pushing right to it moves at a useful rate - the same shape of response
     * as dragging a selection past the edge of a text field.
     */
    private fun trackEdgeScroll(rawY: Float) {
        getLocationOnScreen(screenOrigin)
        val top = screenOrigin[1].toFloat()
        val y = rawY - top
        val band = EDGE_SCROLL_BAND_DP * resources.displayMetrics.density

        val depth = when {
            y < band -> -(band - y) / band
            y > height - band -> (y - (height - band)) / band
            else -> 0f
        }.coerceIn(-1f, 1f)

        val wanted =
            (depth * EDGE_SCROLL_MAX_DP * resources.displayMetrics.density).toInt()
        // Nothing to scroll towards: at the ends, leave it alone rather than fighting the
        // over-scroll.
        val blocked = (wanted < 0 && scrollY <= 0) ||
            (wanted > 0 && scrollY >= scrollRange())
        val next = if (blocked) 0 else wanted

        if (next == edgeScrollSpeed) return
        val wasIdle = edgeScrollSpeed == 0
        edgeScrollSpeed = next
        if (next != 0 && wasIdle) postOnAnimation(edgeScroll)
    }

    private fun stopEdgeScroll() {
        edgeScrollSpeed = 0
        removeCallbacks(edgeScroll)
    }
    private var lastReorderAt = 0L

    /** The part of the wall the drag has been resting on, and since when. See [reorderUnder]. */
    private var pendingReorderCell = TileGridLayout.NO_CELL
    private var pendingReorderSince = 0L

    /**
     * The question the packer has already answered with "leave it where it is".
     *
     * Working out where a drop goes costs a packing of the wall per tile on it - see
     * TileGridLayout.dropIndexFor - so the same question is not put twice. Any actual move
     * forgets it, because the wall it was answered about no longer exists.
     */
    private var settledCell = TileGridLayout.NO_CELL
    private var settledFrom = -1
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // Edge-swipe state: pull down at the top, push up at the bottom.
    private var edgeSwipeStartY = 0f
    private var pullDownArmed = false
    private var pushUpArmed = false
    /**
     * How far the wall has to be pushed off either end before something else takes over -
     * the shade at the top, the app list at the bottom.
     *
     * Several times the plain edge swipe, and the same at both ends. The wall moves with
     * the finger for the whole of it, so the gesture is something the user is doing rather
     * than a distance to be covered, and what arrives while a thumb is still travelling is
     * something nobody asked for. The give is what buys the longer throw: without it the
     * distance was all the gesture had, so it had to be short.
     */
    private val edgeGiveThreshold by lazy {
        EDGE_SWIPE_DP * PULL_DOWN_FACTOR * resources.displayMetrics.density
    }


    // Resize-handle drag state
    private var resizeTouchRawX = 0f
    private var resizeTouchRawY = 0f
    private var resizeStartWidth = 0f
    private var resizeStartHeight = 0f

    init {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        // An edit handle is centred on its tile's corner, so half of it lies outside the
        // cell the grid gave that tile. The grid has to stop clipping for it to be drawn.
        //
        grid.clipChildren = false

        // And the scroller has to stop clipping the *grid* to the grid, for the same
        // reason: a parent clips each child to that child's own bounds, so the handle on
        // the last row was cut off by the end of the wall it hangs below.
        //
        // Its own bounds still clip - clipToPadding is left alone and there is no padding
        // to shrink it - so nothing is drawn over the navigation bar. Only the grid's
        // edges stop being a boundary.
        clipChildren = false

        // Room under the last row to put that handle in, so scrolling to the end does not
        // leave it pressed against the foot of the window.
        grid.bottomReservePx =
            (TileView.HANDLE_OVERHANG_DP * resources.displayMetrics.density).toInt()

        // And the same room above the first row, for the unpin handle on the other corner.
        // Not clipping is no help there: the wall starts at the top of the scroller, and
        // what hangs above the first row is outside the scroller itself rather than merely
        // outside the grid. So the rows start a handle's reach down instead - see
        // TileGridLayout.topReservePx.
        grid.topReservePx = grid.bottomReservePx

        content.orientation = LinearLayout.VERTICAL
        content.clipChildren = false
        content.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(buildAppListArrow(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setBackgroundColor(palette.background)
        setOnClickListener { if (isEditMode) exitEditMode() }
    }

    // ---------------------------------------------------------------- data

    // What a live tile is currently showing, kept from the last rebuild. A tile built
    // after it - the band a folder opens into - is dressed from the same four, so what is
    // in a folder is as live as what is around it; and the tick can ask every tile on the
    // surface again without the caller handing them over twice. See [dress].
    private var liveWidgetContent: (Tile) -> TileView.Reading? = { null }
    private var widgetGlyphContent: (Tile) -> Pair<Int?, Int?> = { null to null }
    private var widgetBackContent: (Tile) -> TileView.Reading? = { null }
    private var alarmMarkContent: (Tile) -> Int? = { null }

    /**
     * Rebuilds the whole Start screen.
     *
     * [liveWidget] supplies the reading for the live tiles that show one, which sits on
     * the front face permanently rather than being flipped to. Returning null leaves the
     * tile as an ordinary icon tile.
     */
    fun setTiles(
        newTiles: List<Tile>,
        liveWidget: (Tile) -> TileView.Reading? = { null },
        widgetGlyphs: (Tile) -> Pair<Int?, Int?> = { null to null },
        widgetBacks: (Tile) -> TileView.Reading? = { null },
        alarmMarks: (Tile) -> Int? = { null },
        tileColors: (Tile) -> Int? = { null },
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ) {
        // Rebuilding drops the selection, and that has to be announced: the key strip
        // switches to the edit commands off this callback, and would otherwise be left
        // showing them with nothing selected - and with "done" doing nothing, because
        // exitEditMode() finds no selection to clear.
        val wasEditing = editingView != null
        editingView = null
        if (wasEditing) onEditModeChanged?.invoke(false)
        // The band is one of the grid's children and is about to be swept away with the
        // rest of them; the wall has to be told, or it goes on believing a folder is open
        // and refuses to open it again.
        closeFolder(animated = false)

        liveWidgetContent = liveWidget
        widgetGlyphContent = widgetGlyphs
        widgetBackContent = widgetBacks
        alarmMarkContent = alarmMarks

        tiles.clear()
        tiles.addAll(newTiles.sortedBy { it.index })
        grid.removeAllViews()
        for (tile in tiles) {
            val view = buildTileView(tile, glyphs)
            view.setTileColor(tileColors(tile))
            dress(view)
            grid.addView(view)
        }
        grid.requestLayout()
    }

    /** Hands one tile whatever its kind is showing at the moment it is built. */
    private fun dress(view: TileView) {
        val tile = view.tile
        if (tile.kind.isLiveWidget) {
            liveWidgetContent(tile)?.let { reading -> view.setLiveWidget(reading) }
        }
        val (frontGlyph, backGlyph) = widgetGlyphContent(tile)
        view.setWidgetGlyph(frontGlyph, backGlyph)
        view.setWidgetBack(widgetBackContent(tile))
        view.setAlarmMark(alarmMarkContent(tile))
    }

    /**
     * Asks every tile on the surface what it is showing now, band included.
     *
     * The tick's job. Walks the views rather than [tiles], because a folder opened into
     * the wall is a band of tiles inside the wall's own grid and is not in that list - and
     * a clock in a folder is a clock, which is to say it is wrong within the minute if
     * nobody asks it again.
     */
    fun refreshLiveWidgets() {
        forEachTileView { dress(it) }
    }

    /** Repaints one tile, without rebuilding the wall around it. */
    fun setTileColor(tileId: String, color: Int?) {
        forEachTileView { if (it.tile.id == tileId) it.setTileColor(color) }
        // The open folder's rules are its tile's colour, so repainting that tile repaints
        // them - including the case that matters most, every tile on the wall being handed
        // back to the accent so the wallpaper can show through them.
        if (tileId == openFolderId) syncBandRules()
    }

    /**
     * Puts the open folder's rules back in step with the tile they belong to.
     *
     * Cheap and idempotent, so it is called from anywhere that could have changed what
     * that tile is showing rather than being reasoned about case by case.
     */
    private fun syncBandRules() {
        if (bandRules.isEmpty()) return
        val folderView = openFolderId?.let { id -> findTileView { it.tile.id == id } }
        val color = folderView?.fillColor ?: palette.accent
        val window = folderView?.showsStartBackground == true
        for (rule in bandRules) rule.setRule(color, window, ruleDim())
    }

    /**
     * How far an open folder's rules hold their photograph down.
     *
     * Only the wash asked for as a look. The other one a tile can wear is there so the
     * words on it can be read, and a rule has none. See TileView.dimAllTiles.
     */
    private fun ruleDim(): Float = if (dimAllTiles) dimAmount else 0f

    /** Hands one live widget the run of faces it turns through. */
    fun setLiveWidgetRotation(
        tileId: String,
        faces: List<TileView.LiveFace>,
        style: TileView.LiveStyle
    ) {
        forEachTileView { if (it.tile.id == tileId) it.setLiveWidgetRotation(faces, style) }
    }

    /**
     * The same run, to every tile of one kind at once.
     *
     * Which is how a program's live tile is fed: it is pinned from the app list like any
     * other tile, so there can be two of them on the wall, and both are showing the same
     * headlines. The tile-at-a-time form above is for a run that differs between them -
     * the weather, whose labels are shortened to the tile they are being read on.
     */
    fun setLiveWidgetRotation(
        kind: Tile.Kind,
        faces: List<TileView.LiveFace>,
        style: TileView.LiveStyle
    ) {
        forEachTileView { if (it.tile.kind == kind) it.setLiveWidgetRotation(faces, style) }
    }

    /**
     * The same run, worked out for each tile of the kind in turn.
     *
     * For content that differs between two tiles of one kind: the weather, whose labels
     * are shortened to the tile they are being read on. Returning null for a tile leaves
     * its faces alone, which is what a reading that has not arrived yet wants - a tile
     * still turning the last forecast beats a tile turning nothing.
     */
    fun setLiveWidgetRotation(
        kind: Tile.Kind,
        style: TileView.LiveStyle,
        faces: (Tile) -> List<TileView.LiveFace>?
    ) {
        forEachTileView { view ->
            if (view.tile.kind != kind) return@forEachTileView
            faces(view.tile)?.let { view.setLiveWidgetRotation(it, style) }
        }
    }

    /**
     * Hands the People tile the address book its wall of faces is made of.
     *
     * Both parts of it: the favourites, and the rest of the book the tile makes its
     * numbers up from. How many of the second it takes is the tile's own decision - only
     * it knows how many squares it has. See TileView.applyPeopleGrid.
     *
     * Passing nothing takes the mosaic away again, which is what a tile whose access has
     * been revoked gets: it goes back to being an ordinary live widget with an invitation
     * on it.
     */
    fun setPeopleMosaic(
        favourites: List<ContactFeed.Person>,
        others: List<ContactFeed.Person> = emptyList()
    ) {
        forEachTileView {
            if (it.tile.kind == Tile.Kind.LIVE_PEOPLE) it.setPeopleMosaic(favourites, others)
        }
    }

    /**
     * Hands every weather tile the reading it lays across itself, or nothing at all.
     *
     * Nothing puts a tile back to what it would otherwise show, which is what one gets
     * while there is no forecast cached. What fits on the tile is the face's own decision:
     * it is handed the whole reading whatever size the tile is, and drops the lines a
     * short one has no room for. See WeatherFaceView and TileView.setWeatherFace.
     */
    fun setWeatherFace(reading: WeatherFaceView.Reading?) {
        forEachTileView {
            if (it.tile.kind == Tile.Kind.LIVE_WEATHER) it.setWeatherFace(reading)
        }
    }

    /**
     * Hands every battery tile the charge it draws into its cell.
     *
     * The same reading to all of them whatever size they are, exactly as the weather is
     * handed out: what a given tile has room for is the face's own decision, and a 1x1
     * shows the level without the figure rather than showing nothing. See BatteryFaceView
     * and TileView.setBatteryFace.
     */
    fun setBatteryFace(reading: BatteryFaceView.Reading?) {
        forEachTileView {
            if (it.tile.kind == Tile.Kind.LIVE_BATTERY) it.setBatteryFace(reading)
        }
    }

    /** Hands every tile the loader it fetches face pictures through. */
    fun setBackdropLoader(loader: (String, (android.graphics.Bitmap?) -> Unit) -> Unit) {
        forEachTileView { it.backdropLoader = loader }
    }

    /**
     * Which face a rotating widget is on, so the host knows what a tap should open.
     *
     * Null where this surface is not holding the tile at all - Start and an open folder
     * are both asked and only one of them has it - so that the answer of the one that does
     * is not read alongside a stand-in from the one that does not. And -1 where the tile
     * is resting on its icon rather than showing one of its faces; see
     * TileView.rotationIndexShowing.
     */
    fun rotationIndexOf(tileId: String): Int? {
        var index: Int? = null
        forEachTileView { if (it.tile.id == tileId) index = it.rotationIndexShowing }
        return index
    }

    /**
     * Repaints one tile's icon without rebuilding the wall.
     *
     * For the live widget that steps aside for a notification and has to say *which kind*
     * it stepped aside for. Every other tile's icon is settled when the wall is built and
     * never moves; this one changes with the shade. See MainActivity.refreshWP81Notifications.
     */
    fun setGlyph(kind: Tile.Kind, glyph: MonochromeIconProvider.Glyph?) {
        forEachTileView { if (it.tile.kind == kind) it.setGlyph(glyph) }
    }

    /**
     * The same, for a tile named by the program it opens rather than by its kind.
     *
     * Phone and Messaging both step aside for what is waiting - a handset with an arrow, a
     * speech bubble - and both are plain SYSTEM_APP tiles, so there is no kind to pick
     * them out by. See MainActivity.wp81WaitingMarkFor.
     */
    fun setGlyph(packageName: String, glyph: MonochromeIconProvider.Glyph?) {
        forEachTileView { if (it.tile.packageName == packageName) it.setGlyph(glyph) }
    }

    private fun buildTileView(
        tile: Tile,
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ): TileView {
        val view = TileView(context, tile, palette)
        view.countsEnabled = countsEnabled
        view.tileColorsHidden = tileColorsHidden
        view.dimAllTiles = dimAllTiles
        view.dimAmount = dimAmount
        view.showsBackdrop = tile.id !in picturesHidden
        view.applySize()
        view.setGlyph(glyphs(tile))
        // The wall's own crop, not a fresh one against the screen. Where there is none yet
        // the tile is left bare: the crop has not been worked out at all, which means the
        // signature cannot match either, so the next layout hands it to every tile at once.
        startBackground?.let { bmp ->
            if (!backgroundDest.isEmpty) {
                view.setStartBackground(bmp, backgroundSrc, backgroundDest)
            }
        }
        view.setOnClickListener {
            when {
                // The wall behind an open folder is out of focus and out of use: a tap on
                // any of it is a tap outside the folder, so the folder closes and nothing
                // else happens. The tile that was hit is not what the tap was for - it is
                // only where the finger came down on the way out. See [setBlur].
                !isEditMode && isFolderOpen() && !isBandTile(view) -> closeFolder()
                // Nothing launches while a tile is selected: arranging and opening are
                // different jobs, and a wall of live tiles is far too easy to open by
                // accident while moving one.
                // A folder does not go anywhere: it opens where it is. Turning the wall
                // out for it would be the wall leaving for a page that never arrives.
                !isEditMode && tile.kind == Tile.Kind.FOLDER -> toggleFolder(tile)
                !isEditMode -> launchWithTurnstile(tile, view)
                // Tapping a different tile moves the selection to it rather than dropping
                // out and making the user press and hold all over again. Resizing three
                // tiles in a row is one gesture and two taps this way, not three
                // long-presses.
                editingView !== view -> enterEditMode(view)
                // Tapping the tile already in hand puts it down, as tapping the wall does.
                else -> exitEditMode()
            }
        }
        view.setOnLongClickListener {
            // Not one that began at the rim. The system's back gesture starts as a finger
            // held still there while it decides, and that is long enough for the tile
            // underneath to call it a press and hold - so the wall picked a tile up, and
            // put its handles on it, on the way out of Start.
            //
            // Claimed rather than declined, which is the opposite of what the other tiles
            // do below: declining leaves the release to land as a tap, and a tap is exactly
            // what must not happen here. Same rule as the app list's rows.
            if (pressFromEdge) return@setOnLongClickListener true
            // A hold on one of the other tiles is a tap on it: the selection moves there,
            // and it is not picked up. Holding one is how arranging *starts*, and once it
            // has started there is nothing left for a hold to mean - but a hold that did
            // nothing at all still buzzed and still left the finger waiting for something.
            //
            // Declined rather than handled, which is what makes it a tap: an unhandled
            // long press is not given the system's long-press buzz, and the release still
            // lands as a click - and the click already moves the selection.
            if (isEditMode && editingView !== view) return@setOnLongClickListener false
            // One continuous gesture: the press selects the tile *and* picks it up, so it
            // can be dragged straight away rather than being put down and grabbed again.
            enterEditMode(view)
            startBodyDrag(view, pressRawX, pressRawY)
            // Nothing buzzes here: the framework gives a held view the system's own
            // long-press tick as soon as the press is claimed, and everything on this
            // shell that answers a hold is claiming one. A second buzz fired by hand was
            // what made a hold feel like two or three separate knocks.
            true
        }
        view.onResizeDrag = { v, event -> handleResizeDrag(v, event) }
        // What "off Start" means differs between a pinned app, a built-in and a tile
        // inside a folder, and only the host knows which of those it is looking at.
        view.onUnpinTap = { onTileUnpin?.invoke(tile) }
        // No tile passed with it: the mark means the same thing on whichever tile is
        // carrying it, and the host opens the one app that answers for it.
        view.onAlarmMarkTap = { onAlarmMarkTap?.invoke() }
        return view
    }

    /** The top-right handle on the selected tile was tapped. See TileView.onUnpinTap. */
    var onTileUnpin: ((Tile) -> Unit)? = null

    /** The alarm mark at the foot of a tile was tapped. See TileView.onAlarmMarkTap. */
    var onAlarmMarkTap: (() -> Unit)? = null

    /**
     * Whether tiles count their notifications or just mark them. See TileView.countsEnabled.
     *
     * Held here as well as pushed down, because the wall is rebuilt often and a tile made
     * after the setting changed has to be born knowing it.
     */
    /** How many cells the wall is wide. See TileGridLayout.columns. */
    /**
     * How many columns the wall is set to, as the user chose it.
     *
     * A portrait number - three, four or six across a phone held upright. What the packer
     * is actually given is that scaled to the shape of the screen; see [applyColumns].
     */
    var columns: Int = TileGridLayout.COLUMNS
        set(value) {
            if (field == value) return
            field = value
            // The packer decides which tile sits where from the width it has, so a change
            // of width is a change of arrangement, and the arrangement is what is stored.
            if (applyColumns()) post { commit() }
        }

    /**
     * Hands the packer the column count the screen's shape asks for.
     *
     * Turned on its side the screen is twice as wide and no taller. Keeping the portrait
     * count there makes every cell twice the size - which is the wall of four enormous
     * tiles with a row and a half showing that landscape used to be - so the count is
     * scaled by how much wider the screen has become. A tile then stays the size it is in
     * portrait, and the width that was gained is spent on more of them.
     */
    /**
     * Whether the column count is scaled to the screen's shape.
     *
     * True on a phone, where a screen turned on its side should hold more tiles of the
     * same size rather than the same number of larger ones. False where the extra width
     * is not extra room: a car's screen is wide because it is short, and is read from
     * across the cabin rather than from arm's length, so it wants the few large tiles the
     * scaling is designed to prevent. Such a surface sets its own count and keeps it.
     */
    var autoColumns: Boolean = true

    /**
     * Whether the way down to the app list is offered.
     *
     * There is not always one to go to. The car screen shows the wall by itself, and an
     * arrow there is a button that does nothing at best.
     */
    var showAppListArrow: Boolean = true
        set(value) {
            field = value
            appListArrow.visibility = if (value) VISIBLE else GONE
        }

    /**
     * The screen measurement margins and gaps are worked out from, when it should not be
     * the screen's own shorter side.
     *
     * Those are a fixed fraction of the phone's width, so that a tile and the space around
     * it keep their relation whatever the screen. A surface showing the wall smaller than
     * life has to say so here as well as in its column count, or the tiles shrink and the
     * gutters between them stay where they were.
     */
    var metricBasisOverride: Int? = null

    private fun applyColumns(): Boolean {
        // The phone's shorter side, whichever way it is being held: that is the width the
        // wall was designed across, and what a tile's size is worked out from.
        val metrics = resources.displayMetrics
        val basis = metricBasisOverride
            ?: kotlin.math.min(metrics.widthPixels, metrics.heightPixels)
        grid.metricBasis = basis
        bandGrid?.metricBasis = basis
        val across = if (autoColumns) {
            TileGridLayout.columnsFor(width, basis, columns, metrics.density)
        } else columns
        if (grid.columns == across) return false
        grid.columns = across
        bandGrid?.columns = across
        return true
    }

    var countsEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            forEachTileView { it.countsEnabled = value }
        }

    /**
     * Whether every tile is holding its colour back so the Start photo shows through.
     *
     * The wall's rather than each tile's, for the reason [countsEnabled] is: it is one
     * answer for the whole wall, and a tile built after it was given has to be born
     * knowing it. See TileView.tileColorsHidden.
     */
    var tileColorsHidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            forEachTileView { it.tileColorsHidden = value }
        }

    /**
     * Whether every tile showing the photo is darkened, not only the ones carrying words.
     *
     * The wall's, for the reason [tileColorsHidden] is: one answer for all of them, and a
     * tile built after it was given has to be born knowing it. See TileView.dimAllTiles.
     */
    var dimAllTiles: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            forEachTileView { it.dimAllTiles = value }
            syncBandRules()
        }

    /** How strongly [dimAllTiles] darkens the photo. See TileView.dimAmount. */
    var dimAmount: Float = TileView.CONTENT_SCRIM_ALPHA
        set(value) {
            if (field == value) return
            field = value
            forEachTileView { it.dimAmount = value }
            syncBandRules()
        }

    /**
     * Which tiles are holding their picture back, by id.
     *
     * Per tile rather than for the wall - it is a decision about the News tile or about
     * whatever is playing, not about tiles in general - but held here for the reason
     * [tileColorsHidden] is: the wall builds its tiles, so a tile built after the user
     * turned its picture off has to be born knowing. See TileView.showsBackdrop.
     */
    var picturesHidden: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            forEachTileView { it.showsBackdrop = it.tile.id !in value }
        }

    /**
     * Whether the selected tile is showing a picture, or null if it has none to show.
     *
     * One question rather than two, because the strip asks both at once: null takes the
     * picture command off it, and true or false is which way the command's ring is
     * turned. See WP81SecondaryBar.setMode.
     */
    val editingPicture: Boolean?
        get() = editingView?.takeIf { it.hasPicture }?.showsBackdrop

    /**
     * Whether a folder could be made out of the selected tile.
     *
     * The same three rules the drag answered with - see [foldKindFor] - asked of a
     * selection rather than of a drop: a folder does not go inside a folder, a built-in is
     * rebuilt on every refresh and would come straight back out of one, and a tile already
     * inside an opened folder is being arranged rather than filed. Answered here because
     * which grid a tile is packed by is the wall's own business; the strip only knows there
     * is a selection. See WP81SecondaryBar.setMode.
     */
    val editingCanFolder: Boolean
        get() = editingView?.let {
            gridOf(it) === grid &&
                it.tile.kind != Tile.Kind.FOLDER &&
                !it.tile.kind.isBuiltIn
        } == true

    /**
     * The arrow under the wall, on the right, where the app list is.
     *
     * Windows Phone put one here and it was the only visible way to the list - the swipe
     * is faster once you know about it, and invisible until you do. The disc is the one
     * the edit handles wear, for the same reason: it sits over the page rather than on a
     * tile, and has to read against a photograph as well as against black.
     */
    private fun buildAppListArrow(): View {
        val row = FrameLayout(context)
        appListArrow.setImageResource(R.drawable.wp81_nav_applist)
        appListArrow.scaleType = ImageView.ScaleType.FIT_CENTER
        appListArrow.setBackgroundResource(R.drawable.wp81_handle_circle)
        appListArrow.imageTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        appListArrow.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        appListArrow.clipToOutline = true
        appListArrow.isClickable = true
        appListArrow.setOnClickListener { onOpenAppList?.invoke() }
        TiltEffect.apply(appListArrow)
        val size = (APP_LIST_ARROW_DP * resources.displayMetrics.density).toInt()
        val margin = (APP_LIST_ARROW_MARGIN_DP * resources.displayMetrics.density).toInt()
        row.addView(appListArrow, FrameLayout.LayoutParams(
            size, size, android.view.Gravity.END).apply {
            marginEnd = margin
            topMargin = margin
            bottomMargin = margin
        })
        return row
    }

    /**
     * Clears the wall, then opens what was tapped.
     *
     * The order is the whole point: Windows Phone turned Start out *before* the app
     * arrived, so the launch read as leaving one place for another rather than as a screen
     * being replaced. Whatever opens - an app, a folder, a dialog - happens on the far side
     * of it.
     */
    private fun launchWithTurnstile(tile: Tile, view: TileView) {
        // An installed app is handed over before the wall has finished leaving: the system
        // draws its own opening animation over the top, and the tail of the turnstile is
        // meant to be happening underneath it.
        //
        // Anything this shell opens itself - News, Zune, a window of its own - has no such
        // animation to hide behind. It simply appears, so it waits for the wall to have
        // actually gone: handed over early, it arrived over a screen still visibly turning.
        val at = if (tile.kind == Tile.Kind.APP) LAUNCH_AT else 1f
        // A tile turned over to a notification opens that rather than the program it came
        // from: the tap is on the message, so it lands on the conversation. Only the face
        // that is actually up counts - a tile showing its icon and its name is a tile the
        // tap was aimed at as a program, however much is waiting behind it. Which face that
        // is, and what the line opens, are the tile's to decide - see
        // TileView.notificationOpening - and everything else launches as it always did.
        val open = view.notificationOpening() ?: { onLaunch?.invoke(tile) }
        // A folder is opened to get at what is inside it, so opening one of those is the
        // end of what the folder was for. Left standing, it is what the user comes back
        // to: a wall still parted around a folder they finished with, with their own tiles
        // pushed a row down.
        //
        // Going out to an installed app is caught on the way out of the activity as well
        // (see MainActivity.onStop); this is the same rule for the programs this shell
        // opens in a window of its own, which never stop it.
        //
        // Not until the wall has gone, and without animation: a folder folding itself away
        // underneath a launch is movement the user did not ask to watch, and by the time
        // this runs there is nothing left on screen to watch it. Only the folder that was
        // open at the tap, so a wall that has moved on since - a folder inside this one
        // opened from the band - is left where the user put it.
        val folderId = openFolderId
        playTurnstileOut(
            at,
            gone = { if (openFolderId == folderId) closeFolder(animated = false) }
        ) { open() }
    }

    // ---------------------------------------------------------------- inline folders

    /** The folder currently opened into the wall, by tile id. */
    var openFolderId: String? = null
        private set

    private var bandAnimator: android.animation.ValueAnimator? = null

    // The reveal scroll that runs alongside the gap opening: where the wall set off from,
    // where it is going, and whether it is still being carried there. The destination is
    // worked out once and then kept, because it belongs to the folder rather than to the
    // frame - both edges of a band are settled the moment it has been measured, and
    // neither moves while the gap opens. It is null only for the frame or two before that
    // measure has happened. See [followBandOpen].

    private var bandScrollFrom = 0
    private var bandScrollTo: Int? = null
    private var bandScrollTracking = false

    /** The band's own grid and the column inside it, for dragging into and sliding. */
    private var bandGrid: TileGridLayout? = null
    private var bandColumn: View? = null

    /**
     * The two rules that fence the open folder off, kept so they can be repainted.
     *
     * The wall's photograph reaches them by the walk, like everything else on it - see
     * [forEachWindowPlaced] - but what they are *for* is the folder's own colour, and
     * that can change under them while the folder is open. See [syncBandRules].
     */
    private var bandRules: List<BandRule> = emptyList()

    /** Which side of the folder the tile in hand started on. See [fileOnDrop]. */
    private var dragStartedInBand = false

    /**
     * Whether this tile is one of an open folder's rather than one of the wall's own.
     *
     * By where it hangs, not by what it is: a tile is filed into a folder by being moved
     * into the band's own packer, and moved back out of it the same way, so the packer it
     * is in *is* the answer. See [gridUnderTile].
     */
    private fun isBandTile(view: TileView): Boolean = view.parent === bandGrid

    /** How far the wall behind an open folder is currently pushed out of focus. */
    private var wallBlur = 0f

    /** What is carrying it there, kept so a change of mind can interrupt it. */
    private var blurAnimator: android.animation.ValueAnimator? = null

    /**
     * The one tile the blur leaves alone: the folder that is open.
     *
     * Its own tile is where the gap is anchored and is the only thing left saying which
     * folder this is - the contents are a row below and the tile itself has been emptied -
     * so it stays sharp. Held apart from [openFolderId] because it is still wanted while
     * the folder is closing, and by then that is already null.
     */
    private var blurExempt: String? = null

    /**
     * Takes the wall out of focus behind an open folder, or brings it back.
     *
     * Run over the same stretch as whatever it accompanies - the gap opening, the wall
     * standing back to be arranged - so the blur is part of that movement rather than a
     * second one after it.
     */
    private fun animateWallBlur(target: Float, duration: Long = BAND_MS) {
        blurAnimator?.cancel()
        if (wallBlur == target) return
        blurAnimator = android.animation.ValueAnimator.ofFloat(wallBlur, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyWallBlur(it.animatedValue as Float) }
            start()
        }
    }

    /** Hands one frame of the blur to the wall's own tiles. The band's stay sharp. */
    private fun applyWallBlur(amount: Float) {
        wallBlur = amount
        val exempt = blurExempt
        for (i in 0 until grid.childCount) {
            val view = grid.getChildAt(i) as? TileView ?: continue
            view.setBlur(if (view.tile.id == exempt) 0f else amount)
        }
    }

    /** Puts the blur where the wall's state says it should be, without animating it. */
    private fun clearWallBlur() {
        blurAnimator?.cancel()
        applyWallBlur(0f)
    }

    // --- Holding one tile on another ---------------------------------------------------
    // Resting a tile in the middle of another for a moment offers to put the two together:
    // a new folder if the one underneath is a plain tile, or an arrival if it is already a
    // folder. It has to be a dwell rather than a drop: a tile crosses half the wall on its
    // way somewhere, and every tile it passes over would otherwise be an offer to file it
    // away.

    /** What resting the tile in hand on another one would do. */
    private enum class FoldKind {
        /** Two plain tiles: make a folder of the pair, where the lower one stands. */
        CREATE,

        /** A plain tile on a folder: put it in. */
        INTO
    }

    /** The tile currently being rested on, what that would do, and since when. */
    private var foldTarget: TileView? = null
    private var foldKind: FoldKind? = null
    private var foldSince = 0L

    /** True once the dwell is up: the target is showing the offer and a drop will take it. */
    private var foldArmed = false

    /**
     * What the target was showing before the offer.
     *
     * A folder is already previewing its own contents, and withdrawing the offer has to
     * give them back rather than leave it blank.
     */
    private var foldRestore: List<FolderPreviewView.Entry> = emptyList()


    private fun toggleFolder(tile: Tile) {
        if (openFolderId == tile.id) closeFolder() else onFolderOpened?.invoke(tile)
    }

    /**
     * Opens a folder into the wall: the rows below part, and its tiles appear in the gap.
     *
     * The folder's own tile is emptied while this is up. It is still there - it is where
     * the gap is anchored, and tapping it again is how the gap closes - but its contents
     * are on screen a row below, and showing them twice would only make it harder to see
     * which of the two is the real one.
     */
    fun openFolder(
        folder: Tile,
        contents: List<Tile>,
        tileColors: (Tile) -> Int? = { null },
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ) {
        if (contents.isEmpty()) return
        closeFolder(animated = false)
        openFolderId = folder.id
        forEachTileView { if (it.tile.id == folder.id) it.setEmptied(true) }
        // Closed before it exists. The gap keeps its last opening's progress, so a band
        // added while that still reads as 1 is laid out at full height for the frame before
        // the animator gets to set it back to nothing - which is the folder appearing
        // whole and only the wall below it animating.
        grid.bandProgress = 0f
        grid.setBand(buildFolderBand(folder, contents, tileColors, glyphs), folder.id)
        setBandClipping(true)
        // The rest of the wall goes out of focus as the gap opens, over the same stretch:
        // one movement, the folder coming forward and everything else stepping behind it.
        blurExempt = folder.id
        animateWallBlur(FOLDER_BLUR)
        // Brought into view as the gap opens rather than after it. Where the wall has to
        // end up is known as soon as the band has been measured - a frame in, and long
        // before any of it is on screen - so waiting for the slide to finish and only then
        // scrolling was two movements, one after the other, for one action. See
        // [followBandOpen]. The scroll at the end is what is left over: a correction where
        // the band turned out taller than it measured, and nothing at all otherwise.
        bandScrollFrom = scrollY
        bandScrollTo = null
        bandScrollTracking = true
        slideBand(open = true) {
            bandScrollTracking = false
            post { revealBand() }
        }
        // Built with a crop of their own by buildTileView, which is the wrong one: the
        // wall's photograph is zoomed for its own scroll range and the band's tiles have
        // to be windows onto that, not onto a fresh copy fitted to the screen.
        post { pushBackgroundToTiles() }
    }

    fun closeFolder(animated: Boolean = true) {
        val id = openFolderId ?: return
        openFolderId = null
        bandScrollTracking = false
        forEachTileView { if (it.tile.id == id) it.setEmptied(false) }
        if (!animated) {
            bandAnimator?.cancel()
            grid.setBand(null, null)
            grid.bandProgress = 1f
            bandGrid = null
            bandColumn = null
            bandRules = emptyList()
            blurExempt = null
            clearWallBlur()
            return
        }
        // Back into focus as the gap closes, the way it went out of it.
        animateWallBlur(0f)
        slideBand(open = false) {
            grid.setBand(null, null)
            bandGrid = null
            bandColumn = null
            bandRules = emptyList()
            blurExempt = null
            // The wall is a row shorter again, so the parallax has a different range.
            post { pushBackgroundToTiles() }
        }
    }

    /**
     * Carries the wall towards an opening folder, a frame at a time.
     *
     * The gap and the scroll are one movement, so they are given one curve: [progress] is
     * the slide's own eased value, and the wall covers that same share of the distance it
     * has to travel. The folder is therefore fully in view the moment it is fully open,
     * instead of the wall setting off once the gap has stopped moving.
     *
     * The coercion is not a formality. Room to scroll into is made by the gap itself, and
     * the gap is still opening - a wall that ends up scrolled to its very foot has, at the
     * halfway point, only half of that room. Coercing each frame to what there is means the
     * wall follows the room down as it appears, which for the folder at the very bottom of
     * Start is the whole of the movement.
     */
    private fun followBandOpen(progress: Float) {
        if (!bandScrollTracking) return
        val target = bandScrollTo ?: bandRevealTarget()?.also { bandScrollTo = it } ?: return
        // Already whole on the screen, which is most folders: nothing to follow.
        if (target == bandScrollFrom) {
            bandScrollTracking = false
            return
        }
        val to = bandScrollFrom + ((target - bandScrollFrom) * progress).toInt()
        scrollTo(0, to.coerceIn(0, scrollRange()))
    }

    /**
     * Where the wall has to get to for the folder to be on screen, or null while the band
     * has not been measured and so has no answer to give yet.
     *
     * The same reckoning as [revealBand], made against the gap the folder is going to have
     * made rather than the one it has so far: the band's top is the row it hangs under and
     * its height is what its tiles need, and neither of those waits for the slide. The end
     * of the wall does wait for it, so the range allowed for here is the one the wall will
     * have once the rest of the gap has opened.
     */
    private fun bandRevealTarget(): Int? {
        val band = grid.bandView ?: return null
        val full = grid.bandFullHeight
        if (full <= 0) return null
        val top = grid.top + band.top
        val bottom = top + full
        val range = scrollRange() + full - band.height
        return when {
            bottom > scrollY + height -> minOf(top, bottom - height)
            top < scrollY -> top
            else -> scrollY
        }.coerceIn(0, range)
    }

    /**
     * Scrolls a folder that has just opened fully onto the screen.
     *
     * A folder near the foot of the wall opens mostly below it: the tile it came out of is
     * the last thing visible and its contents are somewhere under the navigation bar. So
     * the wall comes up by however much of the gap is over the edge - and no further than
     * the top of the gap, because the row the folder belongs to is what says which folder
     * this is. A folder too tall to fit is shown from its own top for the same reason.
     *
     * Nothing happens where the whole of it is already on screen, which by the time this
     * runs is the usual case: [followBandOpen] has been carrying the wall down for the
     * length of the slide, and this is only what is left over - the band measured taller
     * than it was going to be, or the wall was taken back by a finger part way. A wall
     * that jumped every time a folder was opened would be a wall that moved for no reason.
     */
    private fun revealBand() {
        val band = grid.bandView ?: return
        val top = grid.top + band.top
        val bottom = grid.top + band.bottom
        val target = when {
            bottom > scrollY + height -> minOf(top, bottom - height)
            top < scrollY -> top
            else -> return
        }
        smoothScrollTo(0, target.coerceIn(0, scrollRange()))
    }

    fun isFolderOpen(): Boolean = openFolderId != null

    /**
     * Opens or closes the gap: nothing moves except its height, from nothing to the height
     * the folder needs and back.
     *
     * The contents stay exactly where they are the whole time and the window in front of
     * them grows, so a folder is revealed rather than flown in - and nothing of it is ever
     * drawn over the tiles above or below, because there is no frame in which it is
     * anywhere but inside its own gap.
     *
     * The clip is only on while that is happening. Once the gap is fully open a tile's
     * edit handles have to be able to hang past its edges like any other tile's, and the
     * clip that makes the opening look right would cut them off.
     */
    private fun slideBand(open: Boolean, after: (() -> Unit)? = null) {
        bandAnimator?.cancel()
        setBandClipping(true)
        val from = if (open) 0f else grid.bandProgress
        grid.bandProgress = from
        bandAnimator = android.animation.ValueAnimator.ofFloat(from, if (open) 1f else 0f)
            .apply {
                duration = BAND_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val value = it.animatedValue as Float
                    grid.bandProgress = value
                    // Only ever tracking on the way open, so the closing slide falls
                    // straight through this.
                    followBandOpen(value)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cut = false

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cut = true
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        bandAnimator = null
                        if (open) setBandClipping(false)
                        // A slide cut short is one something else has taken over from - a
                        // second folder opened on top of this one, or Start put away - and
                        // what was to happen at the end of this slide would land in the
                        // middle of that one: the new band taken away again, or the wall
                        // scrolled at a folder that is no longer the one opening.
                        if (!cut) after?.invoke()
                    }
                })
                start()
            }
    }

    /**
     * The gap's contents: the folder's name, then its tiles, closed off by a rule.
     *
     * The two bars are what make the gap read as one thing rather than as a run of tiles
     * that happens to be there. The first starts where the name ends and runs to the edge
     * of the screen; the last runs the whole width. Between them is the folder.
     */
    private fun setBandClipping(clip: Boolean) {
        // Held by the grid, not by the band: the band is not clipped by its parent, so
        // nothing it does to itself can stop it being drawn whole. See bandClipped.
        grid.bandClipped = clip
    }

    private fun buildFolderBand(
        folder: Tile,
        contents: List<Tile>,
        tileColors: (Tile) -> Int?,
        glyphs: (Tile) -> MonochromeIconProvider.Glyph?
    ): View {
        val density = resources.displayMetrics.density
        val bar = (BAND_BAR_DP * density).toInt()
        val margin = TileGridLayout.marginPxFor(density)

        val overhang = (TileView.HANDLE_OVERHANG_DP * density).toInt()
        // The rules fence off a folder, so they show whatever the folder's own tile shows:
        // a tile the user has painted opens into a band that matches it rather than into
        // the accent it no longer wears, and a tile that is a window onto the wallpaper
        // opens into rules that are windows too. See [BandRule] and TileView.fillColor.
        val folderView = findTileView { it.tile.id == folder.id }
        val rule = folderView?.fillColor ?: palette.accent
        val ruleIsWindow = folderView?.showsStartBackground == true
        bandRules = listOf(BandRule(context), BandRule(context)).onEach {
            it.setRule(rule, ruleIsWindow, ruleDim())
            // Handed the wall's crop as they are made, exactly as [buildTileView] hands it
            // to a tile: the band slides in over the next few frames, and a rule that
            // waited for the next push would spend them as a bar of solid accent.
            startBackground?.let { bmp ->
                if (!backgroundDest.isEmpty) it.setStartBackground(bmp, backgroundSrc, backgroundDest)
            }
        }

        // Two views, not one: the outer is the window the folder is seen through and never
        // moves, the inner is the folder itself and slides up into it. One view cannot do
        // both, because a view cannot be clipped by its own edge.
        val band = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Clipped to the window's bounds rather than to its padding, so the padding is
            // room a handle on the first row can hang into rather than a second edge for it
            // to be cut off at.
            clipChildren = false
            clipToPadding = false
            // Nothing above the name. The row of tiles the folder opened out of already
            // ends with its own gap, so a band that added air of its own on top of that
            // sat the name a clear step further from the folder it belongs to than from
            // the tiles under it. The air at the other end stays - see the closing rule's
            // bottom margin - because there is no such gap under the last row to borrow.
        }

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            // Inset both sides to the grid's own margin, so the rule beside the name stops
            // where the tiles under it stop rather than running on to the edge of the
            // screen. The air under it is the folder's inner margin, matched at the other
            // end by the closing rule's.
            setPadding(margin, 0, margin, (BAND_GAP_DP * density).toInt())
        }
        heading.addView(android.widget.TextView(context).apply {
            text = folder.label
            // The name of the thing you are looking into is the obvious place to rename it,
            // and a folder opened in the wall has no menu of its own to put it in.
            isClickable = true
            setOnClickListener { onFolderRename?.invoke(folder) }
            TiltEffect.apply(this)
            typeface = androidx.core.content.res.ResourcesCompat
                .getFont(context, R.font.segoeui_semilight)
            textSize = 22f
            setTextColor(palette.foreground)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        heading.addView(bandRules[0], LinearLayout.LayoutParams(0, bar, 1f).apply {
            marginStart = (BAND_GAP_DP * density).toInt()
            // Set on the foot of the name rather than in the middle of it, so what lies
            // between the rule and the tiles is the heading's own padding and nothing
            // else - a rule floating in the middle of a line of type puts half a line of
            // air under it that no measurement here can account for.
            gravity = android.view.Gravity.BOTTOM
        })
        column.addView(heading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val inner = TileGridLayout(context).apply {
            columns = grid.columns
            metricBasis = grid.metricBasis
            clipChildren = false
            // And room under the last row for its resize handle, exactly as the wall keeps.
            bottomReservePx = overhang
        }
        for (child in contents.sortedBy { it.index }) {
            val view = buildTileView(child, glyphs)
            view.setTileColor(tileColors(child))
            // A folder opens into the wall, so what is in it is live in the same way what
            // is around it is: the tile is dressed from the same four the wall was built
            // from. The runs that turn over - a forecast, a wall of faces - arrive after
            // this, by kind, and reach the band because it is one of the grid's children.
            dress(view)
            inner.addView(view)
        }
        column.addView(inner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        column.addView(bandRules[1], LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, bar).apply {
            // Starting and stopping with the tiles, like the rule at the top: the two rules
            // are the folder's own edges, and edges wider than what they enclose read as a
            // second, larger thing behind it.
            marginStart = margin
            marginEnd = margin
            // The grid keeps a handle's worth of room under its last row, which is empty
            // space the rule would otherwise be pushed down by - so it is pulled back up
            // through it, and what is left between the tiles and the rule is the same
            // margin the heading leaves above them.
            topMargin = (BAND_GAP_DP * density).toInt() - overhang
            bottomMargin = (BAND_TOP_DP * density).toInt()
        })

        band.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        bandGrid = inner
        bandColumn = column
        return band
    }

    fun tiles(): List<Tile> = tiles.toList()

    // ---------------------------------------------------------------- edit mode

    /** Selects [view] for editing, replacing any previous selection. */
    fun enterEditMode(view: TileView) {
        if (editingView === view) return
        editingView?.setEditMode(false)
        editingView = view
        view.setEditMode(true)
        // The wall stands back rather than the tile shrinking: the one being arranged is
        // the one that should be whole. See TileView.setDimmed.
        forEachTileView { it.setDimmed(it !== view) }
        // And comes back into focus while it is being arranged, even with a folder open.
        // The wall and the folder in it are one surface to drag on - a tile is filed by
        // being carried across the band's edge - and half of that surface being a smear
        // is half of it being somewhere you cannot see to drop.
        animateWallBlur(0f, BLUR_EDIT_MS)
        onEditModeChanged?.invoke(true)
    }

    fun exitEditMode() {
        val editing = editingView ?: return
        editing.setEditMode(false)
        clearSelection()
        commit()
    }

    /**
     * Drops the selection and lets the wall stand back up.
     *
     * Every way out of edit mode goes through here, because clearing [editingView] is only
     * half of leaving it: the other tiles are still shrunk, faded and drifting, and a tile
     * disappearing from under the user while the wall stays stood back is edit mode that
     * has ended everywhere except on screen.
     */
    private fun clearSelection() {
        editingView = null
        forEachTileView { it.setDimmed(false) }
        // Behind a folder that is still open, the wall goes back out of focus.
        if (isFolderOpen()) animateWallBlur(FOLDER_BLUR, BLUR_EDIT_MS)
        onEditModeChanged?.invoke(false)
    }

    /** True if the view consumed the back press. */
    fun handleBack(): Boolean {
        if (isEditMode) { exitEditMode(); return true }
        return false
    }

    /**
     * Every tile on the wall, including the ones inside an opened folder's band.
     *
     * The band's tiles are two levels down - band, then its own grid - and they are still
     * tiles on this wall: they carry notifications, they wear the accent, and they are
     * windows onto the same photograph. Walking only the top level left them out of all
     * of it.
     */
    /** The first tile on the wall the predicate accepts, band included. */
    private fun findTileView(match: (TileView) -> Boolean): TileView? {
        var found: TileView? = null
        forEachTileView { if (found == null && match(it)) found = it }
        return found
    }

    private fun forEachTileView(action: (TileView) -> Unit) {
        forEachTileViewIn(grid, action)
    }

    private fun forEachTileViewIn(parent: ViewGroup, action: (TileView) -> Unit) {
        for (i in 0 until parent.childCount) {
            when (val child = parent.getChildAt(i)) {
                is TileView -> action(child)
                is ViewGroup -> forEachTileViewIn(child, action)
            }
        }
    }

    /**
     * Every window onto the photograph, with its position in the wall's own coordinates.
     *
     * The tiles, and an open folder's two rules. A tile inside the band knows where it is
     * inside the band; the photograph behind the wall is positioned in the wall's space,
     * so the offsets have to be accumulated on the way down or every tile in a folder
     * shows the same wrong slice of it.
     */
    private fun forEachWindowPlaced(action: (StartBackgroundWindow, Int, Int) -> Unit) {
        placeWindowsIn(grid, 0, 0, action)
    }

    /** The same walk where the position is not wanted. */
    private fun forEachWindow(action: (StartBackgroundWindow) -> Unit) {
        forEachWindowPlaced { window, _, _ -> action(window) }
    }

    private fun placeWindowsIn(
        parent: ViewGroup,
        offsetX: Int,
        offsetY: Int,
        action: (StartBackgroundWindow, Int, Int) -> Unit
    ) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val x = offsetX + child.left
            val y = offsetY + child.top
            // A tile is both a window and a ViewGroup, and it is asked as a window: there
            // are no tiles inside a tile, and the faces that are do not show the wall's
            // photograph.
            when (child) {
                is StartBackgroundWindow -> action(child, x, y)
                is ViewGroup -> placeWindowsIn(child, x, y, action)
            }
        }
    }

    fun unpinTile(tile: Tile) {
        unpinTile(tile.id)
    }

    /**
     * Takes one tile off the wall by id, whatever ended it.
     *
     * Returns whether there was one to take. A folder thrown away for being empty may have
     * been filed inside another folder rather than standing on the wall, and its owner has
     * to be able to tell that nothing here removed it.
     */
    fun unpinTile(tileId: String): Boolean {
        val index = tiles.indexOfFirst { it.id == tileId }
        if (index < 0) return false
        val view = grid.getChildAt(index)
        // Unpinning is the end of the job the tile was picked up for, so it is the end of
        // edit mode too - and the key strip has to be told, since it is showing the
        // commands for a tile that is on its way off the screen.
        if (view === editingView) clearSelection()
        tiles.removeAt(index)
        reindex()
        view.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(160)
            .withEndAction {
                grid.removeView(view)
                grid.requestLayout()
                commit()
            }.start()
        return true
    }

    fun resizeTile(tile: Tile) {
        tile.size = tile.size.next()
        forEachTileView { if (it.tile.id == tile.id) it.applySize() }
        grid.requestLayout()
        commit()
    }

    private fun reindex() = tiles.forEachIndexed { i, t -> t.index = i }

    /** The grid a tile is packed by: the band's, for a tile inside an opened folder. */
    private fun gridOf(view: TileView): TileGridLayout =
        view.parent as? TileGridLayout ?: grid

    /**
     * Writes down whichever arrangement was being changed.
     *
     * The wall and an opened folder are two arrangements, kept in two places, and the same
     * gestures edit both. Committing the wall after moving something inside a folder would
     * save a list the folder's tiles are not in - which reads as unpinning all of them.
     */
    private fun commit() {
        val editing = editingView
        if (editing != null && gridOf(editing) !== grid) {
            commitFolder()
            return
        }
        reindex()
        onTilesChanged?.invoke(tiles.toList())
    }

    private fun commitFolder() {
        val id = openFolderId ?: return
        val band = bandGrid ?: return
        val ordered = mutableListOf<Tile>()
        for (i in 0 until band.childCount) {
            (band.getChildAt(i) as? TileView)?.let { ordered.add(it.tile) }
        }
        ordered.forEachIndexed { i, tile -> tile.index = i }
        onFolderTilesChanged?.invoke(id, ordered)
    }

    fun addTile(tile: Tile, glyph: MonochromeIconProvider.Glyph?) {
        if (tiles.any { it.id == tile.id }) return
        tile.index = tiles.size
        tiles.add(tile)
        val view = buildTileView(tile) { glyph }
        // A tile arriving while a folder is open joins a wall that is out of focus.
        view.setBlur(wallBlur)
        // Ahead of the band, so the wall's child order still matches its tile list.
        grid.addTile(view)
        grid.requestLayout()
        commit()
    }

    // ---------------------------------------------------------------- handle drags

    /**
     * Top-right handle: carries the tile around and reorders as it passes over others.
     *
     * Uses raw screen coordinates because the handle moves with the tile it is dragging -
     * measuring against the handle's own frame would feed the movement back into itself.
     */
    /**
     * Places the tile so the point the finger grabbed stays under the finger.
     *
     * Recomputed from absolute coordinates every move rather than accumulated as deltas:
     * a reorder changes the tile's layout position mid-drag, and an accumulated offset
     * would then be measured from the wrong base and the tile would snap away.
     */
    private fun followFinger(view: TileView, rawX: Float, rawY: Float) {
        lastMoveRawX = rawX
        lastMoveRawY = rawY
        getLocationOnScreen(screenOrigin)
        val layoutX = screenOrigin[0] + placedLeftOf(view)
        val layoutY = screenOrigin[1] + placedTopOf(view) - scrollY + content.translationY
        view.translationX = (rawX - grabOffsetX) - layoutX
        view.translationY = (rawY - grabOffsetY) - layoutY
    }

    /**
     * Bottom-right handle: resizes live as the finger moves.
     *
     * The size is read off how many grid columns the finger has reached from the tile's
     * left edge, so the tile follows the drag instead of cycling through sizes.
     */
    private fun handleResizeDrag(view: TileView, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeTouchRawX = event.rawX
                resizeTouchRawY = event.rawY
                resizeStartWidth = view.width.toFloat()
                resizeStartHeight = view.height.toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                val widthNow = resizeStartWidth + (event.rawX - resizeTouchRawX)
                val heightNow = resizeStartHeight + (event.rawY - resizeTouchRawY)
                // Against the grid the tile is actually in. A tile inside an opened
                // folder is packed by the band's own grid, and measuring its drag against
                // the wall's cells made every size it snapped to the wrong one.
                val home = gridOf(view)
                val wanted = TileSize.forSpan(
                    home.columnsForWidth(widthNow),
                    home.rowsForHeight(heightNow)
                )
                if (wanted != view.tile.size) {
                    view.tile.size = wanted
                    view.applySize()
                    home.requestLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> commit()
        }
    }

    // ---------------------------------------------------------------- drag to reorder

    private fun beginDrag(view: TileView) {
        dragView = view
        forgetPendingReorder()
        dragStartedInBand = gridOf(view) !== grid
        view.elevation = DRAG_ELEVATION
        view.animate().scaleX(DRAG_SCALE).scaleY(DRAG_SCALE).setDuration(120).start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Tracked here as well as in onTouchEvent: ACTION_DOWN lands on whichever tile was
        // pressed, not on this ScrollView, so waiting for onTouchEvent means never seeing
        // where the gesture began. onInterceptTouchEvent always sees the DOWN first.
        trackPullDown(ev)

        val dragging = dragView
        if (dragging != null) {
            // Ended here, not in onTouchEvent. A view group is never handed the event it
            // intercepts on: the child is cancelled, and the stream only reaches
            // onTouchEvent from the *next* event. A tile picked up by a long press and let
            // go without ever being moved produces no next event, so the drag was never
            // told it had ended - the tile stayed picked up, and the following touch
            // anywhere on the wall carried it off.
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) endDrag(dragging)
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A finger on the wall while a folder is opening takes it back: the reveal
                // is a courtesy, and a courtesy that goes on scrolling under a hand that
                // has started scrolling for itself is a fight. The gap carries on opening
                // either way - what is given up is only the wall's own movement.
                bandScrollTracking = false
                // Only the selected tile is draggable, and only by its body.
                pendingDragView = editingView?.takeIf { contains(it, ev.x, ev.y) }
                // Recorded for every press, not just draggable ones: a long-press starts a
                // drag from where the finger already is, and by then there is no fresh
                // ACTION_DOWN left to read it from.
                pressRawX = ev.rawX
                pressRawY = ev.rawY
                lastMoveRawX = ev.rawX
                lastMoveRawY = ev.rawY
                pressFromEdge = beganAtScreenEdge(ev.rawX)
            }
            MotionEvent.ACTION_MOVE -> {
                // A drag that set off from either rim is the system's back gesture until it
                // proves otherwise, and none of it is the wall's to read.
                //
                // Taken off the tile under it rather than merely left alone. A drag across
                // the screen never leaves the tile it began on if that tile is wide enough,
                // and a press that never leaves its view is a tap when it is let go - so
                // swiping out of Start moved the selection to whichever tile the thumb had
                // crossed, or, with nothing selected, opened it. Intercepting cancels that
                // press, which is the only thing that stops the release counting.
                //
                // Only once it has moved: a press held still at the rim is not a back
                // gesture and is nobody else's, so tapping the edge column still works.
                if (pressFromEdge && moved(ev) > touchSlop) {
                    pendingDragView = null
                    return true
                }
                val pending = pendingDragView
                if (pending != null && moved(ev) > touchSlop) {
                    startBodyDrag(pending, pressRawX, pressRawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pendingDragView = null
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        trackPullDown(ev)

        val dragging = dragView
        if (dragging != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    followFinger(dragging, ev.rawX, ev.rawY)
                    reorderUnder(dragging)
                    trackEdgeScroll(ev.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(dragging)
            }
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun moved(ev: MotionEvent): Float =
        kotlin.math.hypot(ev.rawX - pressRawX, ev.rawY - pressRawY)

    /**
     * Whether a press at [rawX] began in the system's own gesture strip, down either side.
     *
     * The same strip the app list refuses to read holds in, and for the same reason - see
     * MetroIndexList.SYSTEM_GESTURE_DP, where the width and the argument for it are
     * written down. Measured against the screen rather than against this view: the strip
     * is the system's and belongs to the display, whatever happens to be laid out under it.
     */
    private fun beganAtScreenEdge(rawX: Float): Boolean {
        val edge = MetroIndexList.SYSTEM_GESTURE_DP * resources.displayMetrics.density
        return rawX < edge || rawX > resources.displayMetrics.widthPixels - edge
    }

    /**
     * Where a tile sits in the wall's own coordinates.
     *
     * A tile inside an opened folder is two levels down, so its own left and top are
     * positions inside the band. Everything that reasons about where a tile *is* - picking
     * one up, dragging it, working out what it was dropped on - has to ask in the same
     * space, or a folder's tiles are all reported as being in the top-left corner.
     */
    private fun placedLeftOf(view: View): Int {
        var x = 0
        var v: View? = view
        while (v != null && v !== grid) {
            x += v.left
            v = v.parent as? View
        }
        return x
    }

    private fun placedTopOf(view: View): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== grid) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    /** True when a point in this view's coordinates falls inside [view]'s laid-out bounds. */
    private fun contains(view: TileView, x: Float, y: Float): Boolean {
        val left = placedLeftOf(view)
        val top = placedTopOf(view) - scrollY + content.translationY
        return x >= left && x <= left + view.width && y >= top && y <= top + view.height
    }

    /**
     * Picks the selected tile up for repositioning.
     *
     * Dragging the tile itself rather than a corner handle: the whole tile is a far bigger
     * target, and a handle that moves between corners as the tile crosses the grid ends up
     * jumping around under the finger.
     */
    private fun startBodyDrag(view: TileView, rawX: Float, rawY: Float) {
        beginDrag(view)
        getLocationOnScreen(screenOrigin)
        grabOffsetX = rawX - (screenOrigin[0] + placedLeftOf(view))
        grabOffsetY = rawY - (screenOrigin[1] + placedTopOf(view) - scrollY + content.translationY)
        pendingDragView = null
    }

    /** Swaps the dragged tile with whichever one its centre is now over. */
    /**
     * Which grid the tile in hand is currently over: the wall's, or an opened folder's.
     *
     * The wall and the folder open in it are one surface to drag on. They are two packers
     * underneath, which is an implementation detail the finger should never be able to
     * feel: crossing the folder's top edge moves the tile from one to the other, and
     * everything after that is an ordinary reorder in whichever one it landed in.
     */
    private fun gridUnderTile(view: TileView): TileGridLayout {
        val band = grid.bandView ?: return grid
        val inner = bandGrid ?: return grid
        // The tile's own middle, not the finger's - the same point everything else about
        // the drag is decided by, or a tile could be filed into the folder while the eye
        // still had it on the wall above.
        val (_, y) = dragProbe(view)
        return if (y >= band.top && y <= band.bottom) inner else grid
    }

    /**
     * Carries a tile across the folder's edge, into it or out of it.
     *
     * The view itself moves between the two grids so the wall repacks around the hole and
     * the folder repacks around the arrival, both while the finger is still down. Filing it
     * only on release meant a drag that showed nothing until it was over, and a drop that
     * had to guess what was underneath it.
     */
    private fun moveAcross(view: TileView, target: TileGridLayout) {
        val from = gridOf(view)
        val index = from.indexOfChild(view)
        if (from === grid && index in tiles.indices) {
            // The wall keeps its own list, and the tile is leaving it.
            tiles.removeAt(index)
        }
        from.removeView(view)
        if (target === grid) {
            tiles.add(view.tile)
            // Ahead of the band, so the wall's child order still matches its tile list.
            grid.addTile(view)
            reindex()
        } else {
            target.addView(view)
        }
        lastReorderAt = android.os.SystemClock.uptimeMillis()
        // The slot the finger was asking for was a slot in the grid it has just left.
        forgetPendingReorder()
        // Its new home has not been laid out yet, so where the tile *is* on screen is not
        // known until it has been. The translation carrying it under the finger is measured
        // from that position, so without this the tile jumps to wherever the old one put it
        // and snaps back a frame later.
        view.post { if (dragView === view) followFinger(view, lastMoveRawX, lastMoveRawY) }
    }

    /** Where the finger is, in the wall's own coordinates. */
    private fun fingerInGrid(): Pair<Float, Float> {
        getLocationOnScreen(screenOrigin)
        return (lastMoveRawX - screenOrigin[0]) to
            (lastMoveRawY - screenOrigin[1] + scrollY - content.translationY)
    }

    /**
     * The point on the wall a drag is asking about: the middle of the tile in hand.
     *
     * Not the finger. A tile is carried by whatever part of it was grabbed, so on anything
     * bigger than a small one the finger and the tile are in two different places - and it
     * is the tile that has to be somewhere, because what a drag means is "this goes here".
     * Aiming with the finger meant a wide tile picked up by its left end was placed by a
     * point most of a tile away from what the eye was lining up.
     *
     * Worked out from the finger rather than read off the view, because on the frame a
     * reorder happens the view has not been laid out in its new slot yet.
     */
    private fun dragProbe(view: TileView): Pair<Float, Float> {
        val (x, y) = fingerInGrid()
        return (x - grabOffsetX + view.width / 2f) to (y - grabOffsetY + view.height / 2f)
    }

    /**
     * What resting the tile in hand on [under] would do, or null for nothing.
     *
     * A folder takes tiles but never another folder, and nothing the shell provides goes
     * either way: a built-in is rebuilt on every refresh and would come straight back out
     * of any folder it was put in. Neither happens inside an opened folder - there is no
     * nesting to offer there, only arranging.
     *
     * Also read by [reorderUnder] before any offer is made, because a tile that cannot be
     * folded with is not an ambiguous place to be standing and the wall need not wait on
     * it. See [FOLD_APPROACH_MS].
     */
    private fun foldKindFor(view: TileView, under: TileView): FoldKind? {
        if (gridOf(view) !== grid || gridOf(under) !== grid) return null
        if (view.tile.kind == Tile.Kind.FOLDER || view.tile.kind.isBuiltIn) return null
        return when {
            under.tile.kind == Tile.Kind.FOLDER -> FoldKind.INTO
            under.tile.kind.isBuiltIn -> null
            else -> FoldKind.CREATE
        }
    }

    /**
     * Whether the tile in hand is squarely on the middle of [under] rather than merely on it.
     *
     * The middle is what two tiles are put together over; everything round it is ordinary
     * wall, where the drag is either passing across or asking for the slot. Both readings
     * have to stay available on the same square, and this is the whole of what tells them
     * apart - which is why it is a bullseye and not most of the tile. It used to be most of
     * the tile, and the cost was that the tile could not be *aimed at* at all: the only
     * part of it the wall would open for was the rim.
     *
     * A circle on the tile's centre, sized from its shorter side, rather than an inset
     * rectangle. Two reasons: a wide tile then keeps its ends for the wall instead of
     * being nearly all middle, and what is left over on a small one is four corners rather
     * than a thin band all the way round - a shape a thumb can actually find.
     *
     * [armed] widens it. A circle that decides something is a circle whose edge is worth
     * shaking on, and an offer that has already been made and shown should not be taken
     * back by a tremor. See [FOLD_RELEASE_SLACK].
     */
    private fun inFoldZone(under: TileView, x: Float, y: Float, armed: Boolean): Boolean {
        val reach =
            FOLD_RADIUS_FRACTION * (if (armed) FOLD_RELEASE_SLACK else 1f)
        val radius = kotlin.math.min(under.width, under.height) * reach
        val dx = x - (placedLeftOf(under) + under.width / 2f)
        val dy = y - (placedTopOf(under) + under.height / 2f)
        return kotlin.math.hypot(dx, dy) < radius
    }

    /**
     * Holds the two together and shows what putting them together would give.
     *
     * The preview goes on the tile being rested on, because that is where the folder will
     * be - either the one made of the pair, or the one that is already there with the
     * arrival in it. Returning true means the wall stands still: nothing shuffles out from
     * under a tile that is being offered something, which would move the target being
     * aimed at.
     *
     * The hold is the whole cost of the offer, and it is why the bullseye is small - see
     * [inFoldZone]. It buys the aim, and it is paid for out of one circle in the middle of
     * one tile rather than out of the wall.
     *
     * Letting go before the offer is armed is not a folder: it is an ordinary drop, and
     * lands the tile on the square it is standing on. So the two readings of the same spot
     * are told apart by how long the hand stays - a beat to put it there, a moment longer
     * to put it *in* - and neither is reached by accident.
     */
    private fun trackFold(view: TileView, under: TileView): Boolean {
        val kind = foldKindFor(view, under)
        if (kind == null) {
            clearFold()
            return false
        }
        if (under !== foldTarget) {
            clearFold()
            foldTarget = under
            foldKind = kind
            foldSince = android.os.SystemClock.uptimeMillis()
            return true
        }
        if (foldArmed) return true
        if (android.os.SystemClock.uptimeMillis() - foldSince < FOLD_DWELL_MS) return true

        val entries = when (kind) {
            FoldKind.CREATE -> folderPreviewOf?.invoke(listOf(under.tile, view.tile))
            FoldKind.INTO -> folderPreviewWith?.invoke(under.tile, view.tile)
        }.orEmpty()
        if (entries.isEmpty()) return true
        foldArmed = true
        foldRestore = under.folderPreviewEntries
        under.setFolderPreview(entries)
        // And the target comes forward out of the wall. Everything but the tile in hand is
        // standing back while it is being arranged, so stepping back in is what an offer
        // looks like here - and it is the same signal whether a folder is being made or
        // added to, which the preview alone is not.
        under.setDimmed(false)
        Haptics.tap(under)
        return true
    }

    private fun clearFold() {
        foldTarget?.takeIf { foldArmed }?.let { target ->
            // Back to whatever it was showing - its own contents, for a folder - and back
            // into the wall with the rest.
            target.setFolderPreview(foldRestore)
            target.setDimmed(true)
        }
        foldRestore = emptyList()
        foldTarget = null
        foldKind = null
        foldArmed = false
    }

    /** Forgets where the drag was resting, so the dwell starts again. */
    private fun forgetPendingReorder() {
        pendingReorderCell = TileGridLayout.NO_CELL
        settledCell = TileGridLayout.NO_CELL
        settledFrom = -1
    }


    /**
     * Decides what a drag hovering somewhere means, and does it.
     *
     * One drag has to do two jobs - move a tile about, and put two tiles together - and
     * they are told apart by *where* the tile in hand has got to and by *how long* it has
     * been there. Neither on its own is enough. Where alone gives a border so thin it
     * cannot be aimed at; how long alone means the tile being aimed at slides out from
     * under the aim before the hold is up.
     *
     *  - Squarely on the **middle of another tile** the two are being offered to each
     *    other. After a beat the tile underneath shows what it would hold - the pair as a
     *    new folder, or its own contents with the arrival added - and letting go takes the
     *    offer. Letting go before it appears does not: that is an ordinary drop, onto the
     *    very square the tile is standing on.
     *  - **Everywhere else** the wall is being asked to take the tile there, and it opens
     *    once the drag has stayed long enough to mean it. Everywhere: the outer part of a
     *    tile, the gutter between two, the empty end of a row, a gap left beside a tall
     *    one, the room below the last row. No point on the wall means nothing, and where
     *    the tile would actually *land* is asked of the packer rather than read off the
     *    geometry - see TileGridLayout.dropIndexFor for why those are not the same
     *    question and why only the first of them can reach an empty space.
     *
     * That the place has to be asked for is what keeps the middle reachable: a tile crossed
     * on the way to somewhere is crossed in well under the dwell, so it stays where it is
     * and can still be aimed at. The dwell is a beat and not a pause, though - see
     * [REORDER_DWELL_MS] - because a wall that only moves for a hand that has stopped is a
     * wall that looks like it is refusing to get out of the way.
     *
     * The one place it is longer is a tile that could be folded with, which is the only
     * square on the wall where standing still means two different things - see
     * [FOLD_APPROACH_MS]. Everywhere the ambiguity does not arise, and that is most of
     * where arranging actually happens, the wall opens on the beat.
     */
    private fun reorderUnder(view: TileView) {
        // One surface: crossing into the folder, or out of it, before anything else.
        val over = gridUnderTile(view)
        if (over !== gridOf(view)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastReorderAt < REORDER_COOLDOWN_MS) return
            clearFold()
            moveAcross(view, over)
            return
        }

        val home = gridOf(view)
        val from = home.indexOfChild(view)
        if (from < 0) return
        val (probeX, probeY) = dragProbe(view)
        val localX = probeX - placedLeftOf(home)
        val localY = probeY - placedTopOf(home)

        // The tile the drag is standing on, if it is one the two could be put together -
        // which is the only case where where-it-is means two different things.
        val under = (home.getChildAt(home.indexAt(localX, localY)) as? TileView)
            ?.takeIf { it !== view && foldKindFor(view, it) != null }

        // Squarely on its middle: the two are being offered to each other, and while that
        // is being aimed at the wall holds still, or the target slides out from under the
        // aim. The wall's own dwell is deliberately left running underneath - back out of
        // the middle and the slot opens at once instead of starting its count again.
        if (under != null &&
            inFoldZone(under, probeX, probeY, armed = foldArmed && under === foldTarget)
        ) {
            if (trackFold(view, under)) return
        } else {
            clearFold()
        }

        // Everywhere else is somewhere the tile could go, and every part of the wall
        // counts: the gutters, the empty end of a row, a gap left beside a tall tile, the
        // room below the last row. Which are asked for is decided by where the tile would
        // *land*, not by the geometry under it - see TileGridLayout.dropIndexFor.
        //
        // The resting place has to be asked for rather than passed through, so a tile
        // flicked across the wall leaves what it crosses where it is. A beat only - long
        // enough to tell a crossing from an arrival, short enough that the wall opens under
        // a hand still in motion, which is what makes the tiles look like they are getting
        // out of the way rather than rearranging themselves once it stops.
        val cell = home.probeCell(localX, localY)
        val now = android.os.SystemClock.uptimeMillis()
        if (cell != pendingReorderCell) {
            pendingReorderCell = cell
            pendingReorderSince = now
            return
        }
        // A square with a tile on it this one could go in with is the one ambiguous place
        // on the wall - the drag might mean "take its place" or "go in with it" - so the
        // wall waits longer there before assuming the first. That wait is the room the aim
        // needs to reach the middle. Nowhere else is ambiguous and nowhere else waits: the
        // gaps, the gutters, the end of a row and the room below it all open on the beat,
        // and those are what arranging a wall is mostly done on.
        val dwell = if (under != null) FOLD_APPROACH_MS else REORDER_DWELL_MS
        if (now - pendingReorderSince < dwell) return
        // One reorder at a time. Without this a drag held over a boundary re-packs on every
        // frame, and the reflow animations restart faster than they can finish - which is
        // the shuffling that shows up as rows twitching.
        if (now - lastReorderAt < REORDER_COOLDOWN_MS) return
        // Already asked about this spot, from this index, and told the tile is where it
        // belongs. Asking again would pack the wall out once per tile for the same answer.
        if (cell == settledCell && from == settledFrom) return

        val to = home.dropIndexFor(view, localX, localY)
        if (to < 0) {
            settledCell = cell
            settledFrom = from
            return
        }
        lastReorderAt = now
        forgetPendingReorder()
        place(view, home, from, to)
    }

    /** Moves a tile within whichever grid holds it, sliding what it displaces. */
    private fun place(view: TileView, home: TileGridLayout, from: Int, to: Int) {
        if (home !== grid) {
            reflow(home) {
                home.removeViewAt(from)
                home.addView(view, to)
            }
            return
        }
        moveTile(from, to)
    }

    /**
     * Puts the tile where it was let go, whatever the drag's own pacing had got round to.
     *
     * The dwell and the cooldown keep the wall still while a tile is being carried over it;
     * neither has any business deciding where it comes to rest. Without this the last move
     * of a drag - the one the hand actually aimed - was thrown away whenever the finger
     * lifted inside the beat, and the tile sprang back to a slot it had already left. That
     * is a drop that does nothing, which is what a tile refusing to be dropped looked like.
     *
     * Returns whether the wall moved it, which is whether the spring home has to be re-based.
     */
    private fun dropInPlace(view: TileView): Boolean {
        val home = gridOf(view)
        val from = home.indexOfChild(view)
        if (from < 0) return false
        val (probeX, probeY) = dragProbe(view)
        val to = home.dropIndexFor(
            view,
            probeX - placedLeftOf(home),
            probeY - placedTopOf(home)
        )
        if (to < 0) return false
        lastReorderAt = android.os.SystemClock.uptimeMillis()
        place(view, home, from, to)
        return true
    }

    /**
     * Watches for a downward drag begun at the very top of Start.
     *
     * A ScrollView already at offset zero does nothing with a downward drag, so that
     * gesture is free to mean something else - here, the notification shade, matching the
     * pull-down every other Android surface has.
     *
     * Only the deciding is done here. The wall following the finger while the gesture is
     * under way is the over-pull's doing - see [overScrollBy] - so a swipe that begins at
     * an end and one that merely arrives at one give exactly the same way.
     */
    private fun trackPullDown(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwipeStartY = ev.rawY
                pullBlocked = false
                // A hand on a bouncing wall stops it dead, where it is. Letting go springs
                // it home from there - see [releaseOverscroll] below.
                stopSpring()
                // Armed against whichever end of the scroll the gesture starts at. Both
                // are free to mean something else, because a ScrollView already at an end
                // does nothing with a drag that would take it further.
                pullDownArmed = scrollY == 0 && !isEditMode && dragView == null
                pushUpArmed = isScrolledToBottom() && !isEditMode && dragView == null
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragView != null) {
                    pullDownArmed = false
                    pushUpArmed = false
                    return
                }
                val travelled = ev.rawY - edgeSwipeStartY

                if (pullDownArmed) {
                    // Any actual scrolling means the gesture was a scroll, not a pull.
                    if (scrollY > 0) {
                        pullDownArmed = false
                        releaseOverscroll()
                    } else if (travelled > edgeGiveThreshold) {
                        pullDownArmed = false
                        pushUpArmed = false
                        pullBlocked = true
                        releaseOverscroll()
                        onSwipeDownAtTop?.invoke()
                        return
                    }
                }

                if (pushUpArmed) {
                    // The mirror of the pull above, and for the same reason: a push that
                    // did nothing until it crossed a line had no beginning, only an
                    // outcome, and one that fell short looked like a tap being ignored.
                    if (!isScrolledToBottom()) {
                        pushUpArmed = false
                        releaseOverscroll()
                    } else if (-travelled > edgeGiveThreshold) {
                        pushUpArmed = false
                        pullDownArmed = false
                        pullBlocked = true
                        releaseOverscroll()
                        onSwipeUpAtBottom?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pullDownArmed = false
                pushUpArmed = false
                releaseOverscroll()
            }
        }
    }

    // ---------------------------------------------------------------- over-pull

    /**
     * How far past its end the wall has been taken, in pixels of raw travel: positive
     * when it has come down off the top of Start, negative when it has come up off the
     * bottom.
     *
     * Raw, meaning the distance asked for rather than the distance shown. What is drawn is
     * [givenBy] of this, a share that shrinks the further the wall is taken - so the wall
     * gives less and less and never leaves the screen, while the number the spring works
     * on stays linear and can be integrated honestly.
     */
    private var pull = 0f

    /** How fast [pull] is changing, in pixels a second, signed the same way. */
    private var pullVelocity = 0f

    /** When [pull] last moved, which is what the speed above is measured against. */
    private var pullAt = 0L

    private var springAt = 0L
    private var springing = false

    /** The last fling frame that actually moved the scroll, and how fast it was going. */
    private var flingFrameAt = 0L
    private var flingSpeed = 0f

    /**
     * Set once a gesture that began as an over-pull has handed over to what it opens.
     *
     * The shade and the app list are pulled out of the ends of Start, and the wall
     * following the finger is the first part of that gesture - but only the first part.
     * Once the thing being pulled out has arrived, the finger belongs to it, and a wall
     * still stretching behind it is the launcher arguing with what it just opened. So the
     * band springs home and stays home until the next touch.
     */
    private var pullBlocked = false

    /**
     * The offset a raw over-pull is actually drawn at.
     *
     * Asymptotic: an infinite pull approaches the limit and never passes it. Taken on the
     * distance with the sign put back afterwards, so a push up gives exactly as much as a
     * pull down and no more. The whole wall moves as one piece, which is what Windows
     * Phone did - the per-tile stretch Android's own overscroll draws needs a render
     * effect this shell cannot use below the version it supports, and a tile wall bending
     * is not what the phone looked like anyway.
     */
    private fun givenBy(raw: Float): Float {
        val limit = OVERSCROLL_LIMIT_DP * resources.displayMetrics.density
        val given = limit * (1f - kotlin.math.exp(-kotlin.math.abs(raw) / limit))
        return if (raw < 0f) -given else given
    }

    /** Puts the wall at a raw over-pull without touching the speed it is moving at. */
    private fun applyPull(raw: Float) {
        pull = raw
        pullAt = AnimationUtils.currentAnimationTimeMillis()
        content.translationY = givenBy(raw)
        // The pull is a scroll as far as the photo is concerned, so it drifts behind the
        // tiles at the same rate an ordinary scroll moves it at. Left out, the wall came
        // away from a picture that stayed nailed to the screen.
        updateTileOffsets()
    }

    /**
     * Moves the wall to a raw over-pull, remembering how fast it got there.
     *
     * That speed is what the spring is launched with when the finger lets go, so a wall
     * flicked into the give carries on for a moment rather than stopping with the hand.
     */
    private fun setPull(raw: Float) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = (now - pullAt).coerceIn(MIN_FRAME_MS, MAX_FRAME_MS).toFloat()
        pullVelocity = if (raw == pull) 0f else (raw - pull) * 1000f / dt
        applyPull(raw)
    }

    /** How far past either end of the scroll [y] falls, signed. Zero when it is inside. */
    private fun pastEnd(y: Int, range: Int): Int = when {
        y < 0 -> y
        y > range -> y - range
        else -> 0
    }

    /**
     * Takes what a dragging finger owes the over-pull out of its move, and hands back
     * whatever is left for the scroll to have.
     *
     * Two things to settle, and in this order. A wall already off its end is picked up
     * first: the drag has to put it back before it counts as scrolling again, which is
     * what makes the band feel attached rather than making the wall jump home the moment
     * the finger turns round. Then whatever of the move runs off the end goes into the
     * pull instead of being thrown away, which is the band stretching.
     */
    private fun pullThrough(deltaY: Int, scrollY: Int, range: Int): Int {
        var left = deltaY
        var raw = pull
        if (raw != 0f) {
            val after = raw - left
            if (after * raw > 0f) {
                // Still off the end: the whole move goes into the give.
                setPull(after)
                return 0
            }
            // The give is used up part-way through the move; the rest is an ordinary scroll.
            raw = 0f
            left = (-after).toInt()
        }
        val past = pastEnd(scrollY + left, range)
        if (past != 0) raw -= past.toFloat()
        if (raw != pull) setPull(raw)
        return left
    }

    /**
     * Everything that would take the scroll past either end of the wall.
     *
     * The scroll is not allowed past - the wall is as far as it goes - so what is left
     * over is put into the over-pull instead, and that is the whole of the rubber band.
     * A finger dragging into the end takes the wall with it; a fling arriving at the end
     * hands the spring the speed it still had, which is what stops a flick at the top of
     * Start simply ceasing to move on arrival.
     */
    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        if (!isTouchEvent) {
            watchFlingIntoEnd(deltaY, scrollY, scrollRangeY)
            return super.overScrollBy(
                deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, maxOverScrollY, false
            )
        }
        // The wall has already given what it was going to for this gesture: it is on its
        // way home and the rest of the drag is the shade's or the app list's. See
        // [pullBlocked].
        if (pullBlocked) {
            return super.overScrollBy(
                deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, maxOverScrollY, true
            )
        }
        // A hand on the wall owns it, so whatever the spring was doing to it stops here.
        stopSpring()
        val left = pullThrough(deltaY, scrollY, scrollRangeY)
        val clamped = super.overScrollBy(
            deltaX, left, scrollX, scrollY, scrollRangeX, scrollRangeY,
            maxOverScrollX, maxOverScrollY, true
        )
        // Reported as clamped whenever the give took any of the move, so the ScrollView
        // drops the velocity it was gathering: a drag that only stretched the band is not
        // a flick, and flinging on it threw the wall at an end it was already resting on.
        return clamped || left != deltaY
    }

    /**
     * The start of a fling, noted so its frames can be timed. See [watchFlingIntoEnd].
     */
    override fun fling(velocityY: Int) {
        flingFrameAt = AnimationUtils.currentAnimationTimeMillis()
        flingSpeed = 0f
        super.fling(velocityY)
    }

    /**
     * Follows a fling frame by frame and catches the one that runs out of wall.
     *
     * The scroller is allowed to travel past the end - that is where the speed a fling has
     * left over shows up at all - but the scroll is not, so the first frame that would go
     * past is where the fling ends and the bounce begins. The speed handed over is the one
     * measured on the frame *before* that: the frame that crosses the end has already
     * spent part of itself arriving, so on its own it reads slow.
     */
    private fun watchFlingIntoEnd(deltaY: Int, scrollY: Int, range: Int) {
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = (now - flingFrameAt).coerceIn(MIN_FRAME_MS, MAX_FRAME_MS).toFloat()
        flingFrameAt = now
        // In over-pull terms: a scroll up towards the top of Start takes the wall down.
        val speed = -deltaY * 1000f / dt
        if (pastEnd(scrollY + deltaY, range) == 0) {
            flingSpeed = speed
            return
        }
        // Already off the end under a finger, or already bouncing: nothing to hand over.
        if (springing || pull != 0f) return
        val launch = if (flingSpeed * speed > 0f &&
            kotlin.math.abs(flingSpeed) > kotlin.math.abs(speed)
        ) flingSpeed else speed
        flingSpeed = 0f
        if (kotlin.math.abs(launch) < BOUNCE_MIN_DPS * resources.displayMetrics.density) return
        pullVelocity = launch
        startSpring()
    }

    /**
     * The rubber band: everything the wall was pulled or thrown into the give with, given
     * back.
     *
     * A spring rather than an animation of a fixed length, because what starts it is not
     * always a hand letting go - a fling that runs out of wall hands over whatever speed
     * it still had, and a spring is the one shape that takes a speed as its opening
     * condition rather than only a distance. It is also why a hard flick bounces deeper
     * than a soft one without bouncing for any longer: a spring's period does not depend
     * on how far it is stretched.
     */
    private val spring = object : Runnable {
        override fun run() {
            if (!springing) return
            val now = AnimationUtils.currentAnimationTimeMillis()
            var left = ((now - springAt) / 1000f).coerceIn(0f, MAX_SPRING_FRAME)
            springAt = now
            var x = pull
            var v = pullVelocity
            // Integrated in short steps rather than one per frame: a dropped frame is a
            // long step, and a long step through a stiff spring does not slow the wall
            // down, it throws it.
            while (left > 0f) {
                val step = kotlin.math.min(left, SPRING_STEP)
                left -= step
                v += (-BOUNCE_STIFFNESS * x - BOUNCE_DAMPING * v) * step
                x += v * step
            }
            pullVelocity = v
            if (kotlin.math.abs(x) < SPRING_REST_PX && kotlin.math.abs(v) < SPRING_REST_SPEED) {
                springing = false
                pullVelocity = 0f
                applyPull(0f)
                return
            }
            applyPull(x)
            postOnAnimation(this)
        }
    }

    /** Sets the spring going, if there is anything left for it to do. */
    private fun startSpring() {
        if (springing) return
        if (pull == 0f && kotlin.math.abs(pullVelocity) < SPRING_REST_SPEED) {
            pullVelocity = 0f
            return
        }
        springing = true
        springAt = AnimationUtils.currentAnimationTimeMillis()
        postOnAnimation(spring)
    }

    /** Stops the band where it is, leaving the wall wherever the spring had got it to. */
    private fun stopSpring() {
        if (!springing) return
        springing = false
        removeCallbacks(spring)
        pullVelocity = 0f
    }

    /** Lets the wall back up, whether the pull opened anything or not. */
    private fun releaseOverscroll() = startSpring()

    /**
     * True when there is no further to scroll.
     *
     * Includes the case where the tiles do not fill the screen at all - then Start is both
     * its own top and its own bottom, and either gesture should work.
     */
    /**
     * How far the wall can be scrolled.
     *
     * The grid carries the room kept under the last row for its handle, so this needs no
     * correction of its own - but it is worth having in one place, since the push-up
     * gesture, the drag edge-scroll and the parallax all have to agree about where the
     * end is.
     */
    private fun scrollRange(): Int = (content.height - height).coerceAtLeast(0)

    private fun isScrolledToBottom(): Boolean = scrollY >= scrollRange()


    private fun moveTile(from: Int, to: Int) {
        if (from !in tiles.indices || to !in tiles.indices) return
        reflow(grid) {
            val tile = tiles.removeAt(from)
            tiles.add(to, tile)
            val view = grid.getChildAt(from)
            grid.removeViewAt(from)
            grid.addView(view, to)
            reindex()
        }
    }

    /**
     * Runs a rearrangement and animates everything it displaced into its new slot.
     *
     * That motion is the whole point: without it a tile passing over its neighbours makes
     * them appear in new places rather than move to them. It takes the grid to work on
     * because a folder opened in the wall is a second grid doing exactly the same job, and
     * its tiles were the ones still snapping.
     */
    private fun reflow(home: TileGridLayout, rearrange: () -> Unit) {
        // Visual position, not layout position: a tile part-way through an earlier reflow
        // is drawn offset from its slot, and animating from the slot would snap it.
        val before = HashMap<View, Pair<Float, Float>>(home.childCount)
        for (i in 0 until home.childCount) {
            val child = home.getChildAt(i)
            before[child] = (child.left + child.translationX) to (child.top + child.translationY)
        }

        rearrange()
        home.requestLayout()

        // Read now rather than when the animation is set up. A drop rearranges the wall and
        // then lets go, so by the time the post runs the drag is already over - and the tile
        // in hand, which has a spring home of its own, would be animated back twice.
        val dragging = dragView
        home.post {
            for ((child, old) in before) {
                if (child === dragging) continue
                val dx = old.first - child.left
                val dy = old.second - child.top
                child.animate().cancel()
                if (kotlin.math.abs(dx) < 0.5f && kotlin.math.abs(dy) < 0.5f) {
                    child.translationX = 0f
                    child.translationY = 0f
                    continue
                }
                child.translationX = dx
                child.translationY = dy
                child.animate()
                    .translationX(0f).translationY(0f)
                    .setDuration(REFLOW_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * Where a drop belongs, if it belongs somewhere other than where it started.
     *
     * Only one move is left here: a tile from an opened folder let go anywhere outside that
     * folder's band comes out of it. Its mirror image - a tile from the wall going into a
     * folder that is closed - is not a drop on a folder but a drop on an offer the folder
     * was already showing, and [endDrag] takes that before anything gets this far.
     */
    private fun fileOnDrop(view: TileView): Boolean {
        val endedInBand = gridOf(view) !== grid

        // Crossed the folder's edge during the drag. The view is already where it belongs -
        // it was carried across while the finger was down - so this only writes down which
        // list that is. Nothing is rebuilt: rebuilding would take the tile the user has just
        // watched arrive and replace it with a new one, which is the pop.
        if (endedInBand != dragStartedInBand) {
            // Both orders changed - one list lost a tile and the other gained one - so both
            // are written, rather than whichever one commit() would have guessed at.
            reindex()
            // Which goes first is not a detail. Coming *out* of a folder the wall's order
            // is written before the host is told, because being told is what can empty the
            // folder - and a folder emptied is a folder thrown away, which sends the host
            // back to the stored positions this call is what writes. Told first, the tile
            // was still recorded where it had stood *inside* the folder, and that is the
            // place the wall then put it: taking the last tile out of one sent it to the
            // top of Start rather than leaving it where it was dropped.
            //
            // Going *in*, the order is written afterwards, for the opposite reason: the
            // host reads a tile missing from the wall's order as an unpinning, so it has to
            // know the tile has been filed before it sees it gone. See [absorbIntoFolder],
            // which files first for the same reason.
            if (!endedInBand) onTilesChanged?.invoke(tiles.toList())
            onTileFiled?.invoke(view.tile, if (endedInBand) openFolderId else null, true)
            if (endedInBand) onTilesChanged?.invoke(tiles.toList())
            // Last, and after the filing either way: the folder's own list is written from
            // what is left in the band, and the host clears out anything filed in the
            // folder that is not in it. The tile that has just left is exactly that until
            // the filing above has moved it.
            commitFolder()
            return true
        }

        return false
    }

    private fun endDrag(view: TileView) {
        stopEdgeScroll()
        // Let go on an offer that was already showing: the pair becomes a folder where the
        // lower one stands, or the folder underneath takes the tile in.
        val folding = foldTarget?.takeIf { foldArmed }
        val kind = foldKind
        // Cleared first either way: the target is showing a preview of what it is about to
        // become, and what it actually becomes is built from the lists, not from that.
        clearFold()
        if (folding != null && kind != null) {
            dragView = null
            forgetPendingReorder()
            when (kind) {
                FoldKind.CREATE -> onTilesFoldered?.invoke(view.tile, folding.tile)
                FoldKind.INTO -> absorbIntoFolder(view, folding, folding.tile.id)
            }
            return
        }
        // Where the hand left it has the last word. Taken while the drag is still on, so
        // the reflow leaves the tile alone and the spring below is the only thing moving it.
        val fromX = view.left + view.translationX
        val fromY = view.top + view.translationY
        val moved = dropInPlace(view)
        // Filed after the slot has been settled, never before. A tile that crossed the
        // folder's edge is filed at the place it was let go, and where that is has only
        // just been decided - filed off the release, the last move of the drag was the one
        // the hand had aimed and the one thrown away, and the tile was left standing
        // wherever the dwell had last put it while the wall claimed it was somewhere else.
        val filed = fileOnDrop(view)
        dragView = null
        forgetPendingReorder()

        val settle = Runnable {
            // Re-based on the slot it has actually landed in: the drop may have moved the
            // tile's layout position out from under the translation carrying it, and
            // animating what is left to zero would then start the spring somewhere else.
            view.translationX = fromX - view.left
            view.translationY = fromY - view.top
            view.animate()
                .translationX(0f).translationY(0f)
                .scaleX(view.restingScale())
                .scaleY(view.restingScale())
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                // Back to the edit lift rather than to nothing: the tile is still selected
                // after a drag, and its handles still hang over its neighbours.
                .withEndAction {
                    view.elevation = if (view === editingView) TileView.EDIT_ELEVATION else 0f
                }
                .start()
        }
        // A drop that moved the tile has to wait for the wall to be laid out again before
        // it knows where it is springing from - and so does one that changed hands, since
        // a folder left empty by it closes and takes a row of the wall with it.
        if (moved || filed) view.post(settle) else settle.run()
        // Both lists were written down as the tile changed hands. commit() knows only one
        // of them, and which one it would pick is the one the tile is no longer in.
        if (!filed) commit()
    }

    /**
     * Sends a tile into the folder it was dropped on, and closes the wall behind it.
     *
     * The tile is drawn towards the folder as it shrinks away, so the drop has somewhere
     * to land rather than simply ending. The wall is not rebuilt: the one tile that left
     * is taken out and the rest repack around the hole, which is the same thing unpinning
     * does and reads as one movement instead of two.
     */
    private fun absorbIntoFolder(view: TileView, folder: TileView, folderId: String) {
        val dx = (placedLeftOf(folder) + folder.width / 2f) -
            (placedLeftOf(view) + view.width / 2f)
        val dy = (placedTopOf(folder) + folder.height / 2f) -
            (placedTopOf(view) + view.height / 2f)
        view.animate()
            .translationX(dx)
            .translationY(dy)
            .scaleX(ABSORB_SCALE)
            .scaleY(ABSORB_SCALE)
            .alpha(0f)
            .setDuration(ABSORB_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                val index = tiles.indexOfFirst { it.id == view.tile.id }
                if (index >= 0) tiles.removeAt(index)
                grid.removeView(view)
                // The tile that was being arranged is inside the folder now. Leaving the
                // selection pointing at it would hold the wall stood back around a tile
                // that is no longer on it.
                if (editingView === view) clearSelection()
                reindex()
                // Filed before the wall's new order is written down, never after. The
                // host reads a tile's absence from that order as an unpinning and throws
                // the icon away - which is what filing it first says it is not. The other
                // way round the tile went into the folder and was deleted in the same
                // breath, and the drop looked like it had swallowed it.
                onTileFiled?.invoke(view.tile, folderId, false)
                onTilesChanged?.invoke(tiles.toList())
                grid.requestLayout()
            }
            .start()
    }

    // ---------------------------------------------------------------- appearance

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        forEachTileView { it.applyPalette(p) }
    }

    // ---------------------------------------------------------------- start background

    /**
     * Sets the photo the tiles are windows onto.
     *
     * [focusX] picks which part of a photo wider than the screen is shown, 0 for the left
     * edge through 1 for the right.
     */
    fun setStartBackground(bitmap: Bitmap?, focusX: Float) {
        startBackground = bitmap
        backgroundFocusX = focusX.coerceIn(0f, 1f)
        pushBackgroundToTiles()
        syncDrift()
    }

    private fun pushBackgroundToTiles() {
        val bmp = startBackground
        if (bmp == null) {
            backgroundSrc = null
            backgroundDest = Rect()
            forEachWindow { it.setStartBackground(null, null, EMPTY_RECT) }
            syncBandRules()
            return
        }
        if (width == 0 || height == 0) return

        // Room for the photo to travel. Zooming in is what creates it: the image is drawn
        // taller than the viewport, and the extra height is exactly the distance it pans
        // over the full scroll. No scrollable content means no pan and no zoom.
        val scrollRange = scrollRange()
        val panRange = (scrollRange * PARALLAX_FACTOR)
            .coerceAtMost(height * MAX_OVERSCAN)
            .toInt()

        effectiveParallax = if (scrollRange > 0) panRange / scrollRange.toFloat() else 0f

        // Slack for the drift, on both axes. Asking for a larger area than the screen is
        // what zooms the photo in, exactly as the parallax overscan above does.
        driftRange = if (driftEnabled) (height * DRIFT_FRACTION).toInt() else 0

        // And slack at each end for the over-pull, which travels past both ends of the
        // scroll. Without it the photo runs out at exactly the point the wall is pulled
        // off the top, and the bounce would show the tiles emptying out at the edge.
        overscrollSlack = (OVERSCROLL_LIMIT_DP * resources.displayMetrics.density *
            (1f - effectiveParallax)).toInt()

        val dest = Rect(
            0, 0,
            width + driftRange,
            height + panRange + driftRange + 2 * overscrollSlack
        )
        val src = cropFor(bmp, dest.width(), dest.height(), backgroundFocusX)
        backgroundSrc = src
        backgroundDest = dest
        forEachWindow { it.setStartBackground(bmp, src, dest) }
        // Asked after the photograph has been handed out, since whether a tile is a window
        // is partly whether it has one to be a window onto.
        syncBandRules()
        lastSignature = signature()
        updateTileOffsets()
    }

    /** Places the photo within its slack, from the drift driver's -1..1 position. */
    private fun applyDrift(x: Float, y: Float) {
        val half = driftRange / 2f
        val nextX = Math.round(x * half).toFloat()
        val nextY = Math.round(y * half).toFloat()
        // Rounded to whole pixels and compared, so a frame that has not actually moved the
        // image does not repaint every tile on the screen.
        if (nextX == driftX && nextY == driftY) return
        driftX = nextX
        driftY = nextY
        updateTileOffsets()
    }

    /**
     * Runs the drift only while it can be seen.
     *
     * It holds a sensor and a frame callback, so anything that takes the Start screen off
     * screen - the app list sliding over, the activity going to the background - should
     * stop it rather than leave it turning over behind the user's back.
     */
    private fun syncDrift() {
        val wanted = driftEnabled && startBackground != null && isShown
        if (wanted) drift.start() else drift.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncDrift()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drift.stop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncDrift()
        // Coming back to Start is an arrival, so the wall arrives: the same turnstile it
        // opens with on a cold start. It is also the only reliable way back from
        // playTurnstileOut, which leaves every tile turned away and transparent - and the
        // activity is not always rebuilt on the way home.
        if (isVisible) playEntrance()
    }

    /** Everything the drawn background depends on, so it is only rebuilt when it changes. */
    private fun signature(): Int {
        var h = width
        h = 31 * h + height
        // The whole scrolling column, which is what the pan range is taken from - the
        // grid alone leaves out the arrow under it.
        h = 31 * h + content.height
        h = 31 * h + backgroundFocusX.hashCode()
        h = 31 * h + driftRange
        h = 31 * h + (startBackground?.hashCode() ?: 0)
        return h
    }

    /**
     * The region of [bmp] to show so it fills a [viewW] x [viewH] area without distortion.
     *
     * Centre-crop, except that the horizontal placement is driven by [focusX] so the user
     * can slide a wide photo to frame what they want. Because [viewH] includes the parallax
     * overscan, asking for a taller area is what zooms the photo in.
     */
    private fun cropFor(bmp: Bitmap, viewW: Int, viewH: Int, focusX: Float): Rect {
        if (viewW <= 0 || viewH <= 0) return Rect(0, 0, bmp.width, bmp.height)
        val viewAspect = viewW.toFloat() / viewH
        val bmpAspect = bmp.width.toFloat() / bmp.height
        return if (bmpAspect > viewAspect) {
            // Wider than the target: crop the sides, positioned by focusX.
            val cropW = (bmp.height * viewAspect).toInt().coerceIn(1, bmp.width)
            val left = ((bmp.width - cropW) * focusX).toInt().coerceIn(0, bmp.width - cropW)
            Rect(left, 0, left + cropW, bmp.height)
        } else {
            // Taller than the target: crop top and bottom evenly.
            val cropH = (bmp.width / viewAspect).toInt().coerceIn(1, bmp.height)
            val top = ((bmp.height - cropH) / 2).coerceIn(0, bmp.height - cropH)
            Rect(0, top, bmp.width, top + cropH)
        }
    }

    /**
     * Tells each tile where it sits relative to the photo, so the slices line up into one
     * continuous image.
     */
    private fun updateTileOffsets() {
        if (startBackground == null) return
        // Where the wall has got to, scrolled and pulled together: an over-pull moves the
        // tiles without moving the scroll position, and to the photo behind them the two
        // are the same movement.
        val travelled = scrollY - content.translationY
        // The tiles move a full pixel per pixel scrolled; the photo behind them moves only
        // effectiveParallax of that, which is what reads as depth. A factor of 0 would pin
        // the photo to the screen, 1 would glue it to the tiles.
        val backgroundShift = travelled * (1f - effectiveParallax)
        // Half the drift travel is the resting point, so the photo has the same room to
        // move in both directions before it runs out of image.
        val centre = driftRange / 2f
        forEachWindowPlaced { window, x, y ->
            window.setBackgroundOffset(
                x + centre + driftX,
                y - backgroundShift + centre + overscrollSlack + driftY
            )
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateTileOffsets()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        // Turning the phone changes what the wall is packed into, and the wall is packed
        // from the shape of the screen rather than from the setting alone.
        if (applyColumns()) post { commit() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // A reorder re-lays-out the dragged tile into a new slot mid-gesture. Re-anchoring
        // here - during layout, before the frame is drawn - keeps it under the finger;
        // doing it a frame later showed as a jump.
        dragView?.let { followFinger(it, lastMoveRawX, lastMoveRawY) }
        // Resizing or adding a tile changes the scroll range, and with it how far the photo
        // has to travel - so the crop is rebuilt whenever anything it depends on moves.
        if (signature() != lastSignature) pushBackgroundToTiles() else updateTileOffsets()
    }

    /**
     * Runs the wall to its end, once whatever has just been added to it has been placed.
     *
     * For a tile that has just been pinned: it goes on the end, which on a full Start
     * screen is off the bottom of it, and an app that appears somewhere the user cannot
     * see is indistinguishable from one that was never pinned at all.
     *
     * Waited for rather than done now - the wall has not been measured with the new tile
     * yet, so where its end *is* is not known until it has been.
     */
    fun scrollToEnd(framesLeft: Int = SCROLL_TO_END_FRAMES) {
        androidx.core.view.OneShotPreDrawListener.add(this) {
            // A page that is not on screen is not laid out either, so while the app list is
            // still sliding away the wall's height is the height it had before the tile
            // arrived. Asked again on the next frame rather than acted on: the wall is put
            // back on screen by the first frame of that slide, and measured in the same
            // pass this runs at the end of.
            if (visibility == VISIBLE && content.height > 0) smoothScrollTo(0, scrollRange())
            else if (framesLeft > 0) scrollToEnd(framesLeft - 1)
        }
    }

    /** Jumps to the top without animating, for a return-to-home. */
    fun scrollToTop() {
        stopSpring()
        applyPull(0f)
        scrollTo(0, 0)
        updateTileOffsets()
    }

    /** Staggered entrance - tiles drop in one after another when Start appears. */
    /**
     * Turnstile-in: the wall unfolds a tile at a time, each swinging round its own left
     * edge from edge-on to flat.
     *
     * This is the animation Windows Phone was known by, and it is a rotation rather than a
     * fade: every tile is hinged on its left side, so the wall opens like a run of doors
     * rather than appearing. The stagger runs in packing order, which is left to right and
     * down, and the tiles also come in from the left - the rotation alone reads as tiles
     * turning on the spot, and the phone's did not turn, they arrived.
     */
    fun playEntrance() {
        // Whatever the wall was doing when it went away, it is not doing it now. A pull
        // that was still springing back leaves the whole column shifted - which is a wall
        // that looks scrolled and cannot be scrolled back, because the offset is a
        // translation and not a scroll position.
        stopSpring()
        applyPull(0f)
        forEachTileView { it.resetAnimationState() }

        val slide = ENTRANCE_OFFSET_DP * resources.displayMetrics.density
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.cameraDistance = CAMERA_DISTANCE * resources.displayMetrics.density
            child.pivotX = 0f
            // From whatever height is known: the entrance can run before the wall has been
            // measured, and half of nothing is a pivot on the tile's top edge that outlives
            // the animation. See TileView.hingeOnMiddle.
            child.pivotY = (if (child.height > 0) child.height else child.measuredHeight) / 2f
            child.rotationY = TURNSTILE_DEGREES
            child.translationX = -slide
            // Cleared, not left alone: the turnstile out carries every tile up as well as
            // left, and an entrance that only put the horizontal offset back left the
            // whole wall drawn a few dozen pixels high - which reads as a Start screen
            // scrolled down that cannot be scrolled back, because it is a translation and
            // not a scroll position.
            child.translationY = 0f
            child.alpha = 0f
            child.animate()
                .rotationY(0f)
                .translationX(0f)
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(i * ENTRANCE_STAGGER_MS)
                .setDuration(TURNSTILE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Turnstile-out: tiles rotate away about their left edge in sequence, the way WP8.1
     * cleared Start when launching an app. [after] runs [at] the given point in the turn -
     * early, so that what is opening and the wall leaving are one movement - and [gone]
     * once the last tile has actually gone.
     */
    fun playTurnstileOut(at: Float = LAUNCH_AT, gone: (() -> Unit)? = null, after: () -> Unit) {
        if (grid.childCount == 0) { after(); gone?.invoke(); return }
        val slide = TURNSTILE_OUT_OFFSET_DP * resources.displayMetrics.density
        var maxEnd = 0L
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.pivotX = 0f
            child.pivotY = (if (child.height > 0) child.height else child.measuredHeight) / 2f
            child.cameraDistance = CAMERA_DISTANCE * resources.displayMetrics.density
            val delay = i * TURNSTILE_STAGGER_MS
            maxEnd = maxOf(maxEnd, delay + TURNSTILE_MS)
            child.animate()
                .rotationY(-TURNSTILE_DEGREES)
                // Away from the app that is opening: up and to the left, so the wall reads
                // as being left behind rather than as closing.
                .translationX(-slide)
                .translationY(-slide)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(TURNSTILE_MS)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
        }
        // How far into the turn whatever is opening is asked for. See [launchWithTurnstile]:
        // the wall clearing and an app arriving are one movement, and where the system has
        // an animation of its own to cover the join, the two are allowed to overlap.
        postDelayed({ after() }, (maxEnd * at).toLong())
        // For whatever must not be seen happening. [after] is called partway through the
        // turn, with the wall still on screen; [gone] waits for the last tile, so anything
        // done here is done to a screen with nothing left on it.
        gone?.let { postDelayed(it, maxEnd) }
    }

    /** Restores tiles after a [playTurnstileOut], e.g. on returning to Start. */
    fun resetTurnstile() {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            child.animate().cancel()
            child.rotationY = 0f
            child.translationX = 0f
            child.translationY = 0f
            child.alpha = 1f
        }
    }



    /** Hands each tile its current notifications. */
    fun setNotifications(lookup: (Tile) -> List<TileView.Line>) {
        forEachTileView { it.setNotifications(lookup(it.tile)) }
    }

    /** Hands each folder tile the contents its preview is made of. */
    fun setFolderPreviews(lookup: (Tile) -> List<FolderPreviewView.Entry>) {
        forEachTileView { it.setFolderPreview(lookup(it.tile)) }
    }

    /** Hands each tile whatever its app is playing, or null. */
    fun setMedia(lookup: (Tile) -> MediaSessions.Info?) {
        forEachTileView { it.setMedia(lookup(it.tile)) }
    }

    /** Routes a tile's transport buttons back to the host. */
    fun setMediaHandlers(
        onPlayPause: (Tile) -> Unit,
        onNext: (Tile) -> Unit,
        onPrevious: (Tile) -> Unit
    ) {
        forEachTileView { view ->
            view.onMediaPlayPause = { onPlayPause(view.tile) }
            view.onMediaNext = { onNext(view.tile) }
            view.onMediaPrevious = { onPrevious(view.tile) }
        }
    }

    /**
     * One of the two rules that fence an open folder off.
     *
     * A block of the folder's colour, or - where the folder's own tile is a window onto
     * the Start photograph - a window onto the same photograph, cut from the same crop at
     * the rule's own place on the wall. Held solid while every tile around it turned into
     * a window, it was the one opaque thing on the screen: a bar laid *over* the wallpaper
     * instead of the folder's edge cut *into* the wall.
     *
     * A plain [View] rather than a background colour because that is what drawing a slice
     * of a shared bitmap needs. See [StartBackgroundWindow].
     */
    private class BandRule(context: Context) : View(context), StartBackgroundWindow {

        private var color: Int = 0
        private var window = false

        /** How far the photograph is held down, 0 to 1. Only ever the wall's own wash. */
        private var dim = 0f
        private var bitmap: Bitmap? = null
        private var src: Rect? = null
        private var dest = Rect()
        private var offsetX = 0f
        private var offsetY = 0f

        // The same filtering the tiles draw their slice with, so a rule between two rows
        // of them is not visibly sharper or softer than the rows are.
        private val paint = android.graphics.Paint().apply {
            isFilterBitmap = true
            isDither = true
        }

        /**
         * What this rule is: a colour, whether the photograph outranks it, and how far
         * down the wall is holding the photograph.
         *
         * The dim comes along because it is part of what a window *shows* - a bright strip
         * between two rows of darkened tiles is the same mismatch this class exists to
         * fix, one setting further on. See TileView.dimAllTiles.
         */
        fun setRule(color: Int, window: Boolean, dim: Float) {
            if (this.color == color && this.window == window && this.dim == dim) return
            this.color = color
            this.window = window
            this.dim = dim
            invalidate()
        }

        override fun setStartBackground(bitmap: Bitmap?, src: Rect?, dest: Rect) {
            this.bitmap = bitmap
            this.src = src
            this.dest = dest
            invalidate()
        }

        override fun setBackgroundOffset(x: Float, y: Float) {
            if (x == offsetX && y == offsetY) return
            offsetX = x
            offsetY = y
            if (bitmap != null) invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            // Clipped by hand, as the tiles are: the band does not clip its children, so
            // the wallpaper positioned for this rule's slot would otherwise be painted
            // across the whole wall. See TileView.onDraw.
            val clip = canvas.save()
            canvas.clipRect(0, 0, width, height)
            val bmp = bitmap.takeIf { window }
            if (bmp != null && !bmp.isRecycled) {
                val shifted = canvas.save()
                canvas.translate(-offsetX, -offsetY)
                canvas.drawBitmap(bmp, src, dest, paint)
                // Back out of the photograph's coordinates before the wash, which covers
                // this rule and not the whole picture.
                canvas.restoreToCount(shifted)
                if (dim > 0f) {
                    canvas.drawColor(
                        android.graphics.Color.argb((255 * dim).toInt(), 0, 0, 0))
                }
            } else {
                canvas.drawColor(color)
            }
            canvas.restoreToCount(clip)
        }
    }

    companion object {
        private const val DRAG_ELEVATION = 24f
        private const val DRAG_SCALE = 1.06f
        // The arrow under the wall: its disc, and the air kept round it.
        private const val APP_LIST_ARROW_DP = 46
        private const val APP_LIST_ARROW_MARGIN_DP = 18

        // The gap an opened folder makes: its rules, the air around them, and how long
        // the wall takes to part.
        private const val BAND_BAR_DP = 10f
        private const val BAND_GAP_DP = 10f
        private const val BAND_TOP_DP = 14f
        private const val BAND_MS = 520L

        /** How far out of focus the wall goes behind it. See TileView.setBlur. */
        private const val FOLDER_BLUR = 0.5f

        /**
         * And how long it takes when edit mode is what moved it, rather than the folder.
         *
         * The length of the wall's own step back, so the two happen together. See
         * TileView.setDimmed.
         */
        private const val BLUR_EDIT_MS = 140L

        // A tile being drawn into the folder it was dropped on.
        private const val ABSORB_MS = 190L
        private const val ABSORB_SCALE = 0.5f

        /**
         * How long a tile has to be held in the middle of another before they offer to go
         * together.
         *
         * Short: the middle of a tile is not somewhere a drag passes through by accident
         * now that crossing one no longer moves it, so the dwell is only there to tell
         * resting on a tile apart from travelling over it.
         */
        private const val FOLD_DWELL_MS = 280L

        /**
         * How much of a tile is its middle: the radius two tiles are put together within,
         * as a share of the shorter side of the one being rested on. See [inFoldZone].
         *
         * A shade under half, so the middle is about half the area of a square tile and
         * the corners are the wall's.
         */
        private const val FOLD_RADIUS_FRACTION = 0.30f

        /**
         * How much wider the bullseye is once the offer has been made, as a multiple.
         *
         * Hysteresis, and only outwards: getting in is exact, getting out is not, so a
         * hand holding an offer steady does not have it flicker away and back.
         */
        private const val FOLD_RELEASE_SLACK = 1.35f

        /**
         * How long the wall waits before taking a drag resting on a tile to mean its slot.
         *
         * Longer than the beat everywhere else, and only here - see [REORDER_DWELL_MS]. It
         * is the time the aim has to travel from the edge of a tile to its middle, and if
         * the wall opens first then the tile being aimed at is gone before it is reached
         * and no folder can ever be made by hand. Everywhere that is not a tile there is
         * nothing to aim at and nothing to wait for.
         *
         * The same quarter-second the offer itself takes to appear: the wall gives the aim
         * as long to arrive as it then gives the hand to change its mind. See
         * [FOLD_DWELL_MS].
         */
        private const val FOLD_APPROACH_MS = 280L


        private const val ENTRANCE_STAGGER_MS = 19L
        private const val ENTRANCE_OFFSET_DP = 24f
        private const val FLIP_STAGGER_MS = 220L

        /** How long one tile takes to swing in, and how far it comes from. */
        private const val TURNSTILE_IN_MS = 200L

        /** How far a leaving tile travels up and left as it turns away. */
        private const val TURNSTILE_OUT_OFFSET_DP = 40f

        /**
         * How far into the turnstile an installed app is opened.
         *
         * Short of the end on purpose - the system's own opening animation covers the rest
         * of the turn. What the shell opens itself waits for all of it.
         */
        private const val LAUNCH_AT = 0.7f

        /**
         * Perspective for the hinge.
         *
         * Without a camera distance proportional to density the rotation skews rather than
         * turns - the same number TiltEffect uses, and for the same reason.
         */
        private const val CAMERA_DISTANCE = 8000f

        /**
         * How far off either end the wall can ever be, however hard it is pulled or thrown.
         *
         * The ceiling on the whole band: the give under a finger, and the depth a fling
         * bounces to. Shorter than the threshold that opens the shade, deliberately - the
         * wall should be visibly at the end of its travel by the time that gesture
         * completes, so what arrives reads as the next thing rather than an interruption.
         * The photograph behind the tiles is cropped with this much room at each end for
         * the same reason - see [overscrollSlack].
         */
        private const val OVERSCROLL_LIMIT_DP = 68f

        /** The pull down is this many times the plain edge swipe. */
        private const val PULL_DOWN_FACTOR = 3.2f

        /**
         * The rubber band, as a spring: how hard it pulls the wall back, and how much of
         * that it takes out again on the way.
         *
         * Stiffness is in units of 1/second squared and damping in 1/second, so neither
         * needs scaling for density - a spring's rate does not depend on how far it is
         * stretched, which is the property the whole bounce is built on. The damping is a
         * shade under critical, at about 0.68 of it, so the wall comes back past flat by a
         * few per cent and settles rather than stopping - the same overshoot the fixed
         * animation this replaced was given, arrived at honestly.
         */
        private const val BOUNCE_STIFFNESS = 260f
        private const val BOUNCE_DAMPING = 22f

        /** Below this, a fling arriving at the end is simply a fling that stopped there. */
        private const val BOUNCE_MIN_DPS = 120f

        /** Integration step for the spring, and the longest frame it will swallow whole. */
        private const val SPRING_STEP = 0.004f
        private const val MAX_SPRING_FRAME = 0.064f

        /** Close enough to home, and slow enough, to call it arrived. */
        private const val SPRING_REST_PX = 0.5f
        private const val SPRING_REST_SPEED = 32f

        /**
         * The window a frame time is believed within, in milliseconds.
         *
         * Speed is measured by dividing a frame's travel by a frame's length, and both
         * ends of that need a floor: a clock that has barely moved reports an impossible
         * speed, and one that has been away for a second reports a stall as a crawl.
         */
        private const val MIN_FRAME_MS = 8L
        private const val MAX_FRAME_MS = 64L

        /** How far to drag past either end of Start before the gesture fires. */
        private const val EDGE_SWIPE_DP = 48f
        private const val TURNSTILE_STAGGER_MS = 20L
        private const val TURNSTILE_MS = 147L
        private const val TURNSTILE_DEGREES = 80f

        /**
         * How long [scrollToEnd] will wait for the wall to be on screen and measured.
         *
         * A handful of frames, so a wall that never appears at all - pinned from somewhere
         * that does not go back to Start - gives up rather than asking every frame forever.
         */
        private const val SCROLL_TO_END_FRAMES = 12

        /** How long a displaced tile takes to slide into its new slot. */
        private const val REFLOW_MS = 160L

        /** Minimum gap between reorders, so the grid settles before it moves again. */
        private const val REORDER_COOLDOWN_MS = 180L

        /**
         * How long a drag has to stay on a slot before the wall opens it.
         *
         * Asking rather than passing through - but a beat, not a pause. At a quarter of a
         * second the wall only ever moved for a hand that had stopped, so tiles appeared to
         * refuse to get out of the way and the arranging all happened after the fact. This
         * is short enough that the wall opens under a hand still in motion and long enough
         * that a tile flicked across it leaves the ones it crosses alone.
         */
        private const val REORDER_DWELL_MS = 100L


        /**
         * Target travel of the Start background relative to the tiles, 0 to 1. Low enough
         * that the photo drifts rather than scrolls. The rate actually used is
         * [effectiveParallax], which honours the zoom ceiling.
         */
        private const val PARALLAX_FACTOR = 0.3f

        /** Ceiling on the zoom, as a fraction of viewport height. */
        /** How deep the strip at each edge is that starts the page scrolling under a drag. */
        private const val EDGE_SCROLL_BAND_DP = 96f

        /** Scroll rate at the very edge, per frame - about 400dp a second at 60fps. */
        private const val EDGE_SCROLL_MAX_DP = 7f

        private const val MAX_OVERSCAN = 0.35f

        /**
         * How far the photo drifts, as a fraction of the screen.
         *
         * The travel is also the speed: the wander crosses it in a fixed time either way,
         * so widening it moves the photo further *and* faster. At half this it was subtle
         * to the point of being arguable - which, for an effect you turn on deliberately,
         * is the wrong side of the line to be on. The cost is zoom: the travel is cut out
         * of the photo, not added around it.
         */
        private const val DRIFT_FRACTION = 0.12f

        private val EMPTY_RECT = Rect()
    }
}
