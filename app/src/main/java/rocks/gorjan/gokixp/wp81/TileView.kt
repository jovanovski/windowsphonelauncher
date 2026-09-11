package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * One Start screen tile.
 *
 * A flat accent-filled square with a white glyph and, on medium and wide tiles, a label
 * in the bottom-left corner. Small tiles are icon-only - there is no room for text, and
 * WP8.1 did not draw one.
 *
 * Live tiles flip about their horizontal axis, swapping [frontFace] for [backFace] at
 * the halfway point so the content changes while the tile is edge-on.
 */
@SuppressLint("ViewConstructor")
class TileView(
    context: Context,
    var tile: Tile,
    private var palette: WP81Palette
) : FrameLayout(context), TiltEffect.Target, StartBackgroundWindow {

    /**
     * A tile stepped back behind the one being arranged rests slightly shrunk, so the
     * press effect springs back to *that* rather than to full size. See TiltEffect.Target.
     */
    override fun restingScale(): Float = if (isDimmed) DIM_SCALE else 1f

    /**
     * Whether this tile is one of the ones standing back.
     *
     * Arranging a tile used to shrink the tile itself, which is the wrong way round: the
     * one being worked on is the one that should be whole, and it is the wall around it
     * that should get out of the way. See [setDimmed].
     */
    var isDimmed: Boolean = false
        private set

    private val frontFace = FrameLayout(context)
    private val backFace = FrameLayout(context)

    private val glyph = ImageView(context)

    /**
     * The icon and, beside it, how many notifications are waiting.
     *
     * A row rather than a centred glyph so the two are centred together: WP8.1 put the
     * count next to the mark and set it to the same height, and a number tucked into a
     * corner instead reads as decoration rather than as the count of a thing.
     */
    private val iconRow = android.widget.LinearLayout(context)
    private val countLabel = TextView(context)

    // What the count was last sized for, so a measure pass that changes neither does not
    // re-measure the type. See [applyCountSize].
    private var countSizedFor = 0
    private var countSizedText = ""
    private var countSizedRoom = 0

    /**
     * The blank the digits carry to the left of their own ink, in pixels.
     *
     * Type is drawn with side bearings and a text view is as wide as the advance, not as
     * wide as the marks: a "1" sits well inside its own box. Measured so it can be taken
     * back out of the gap, or the number reads as adrift from the icon however tight the
     * margin between their boxes is.
     */
    private var countBearing = 0

    /** And the blank after the last mark, which is what the pair is centred against. */
    private var countTrailing = 0

    /**
     * A folder's contents, shown in place of the folder glyph. See [setFolderPreview].
     *
     * Built the first time a folder asks for one rather than with the tile. Start is a
     * wall of tiles and almost none of them are folders; a grid that is never shown is
     * still a dozen views to create, measure and lay out on every one of them.
     */
    private var folderPreview: FolderPreviewView? = null

    /** Whether [folderPreview] is the front face right now. */
    private var hasFolderPreview = false

    /**
     * The People tile's wall of faces, shown in place of everything else. See
     * [setPeopleMosaic].
     *
     * Built on first use for the same reason the folder preview is: one tile on the wall
     * ever wants one.
     */
    private var peopleMosaic: PeopleMosaicView? = null

    /** Whether [peopleMosaic] is the front face right now. */
    private var hasPeopleMosaic = false

    /**
     * The weather, laid across the tile rather than turned through it. See
     * [setWeatherFace].
     *
     * Built on first use, as the other two are: one tile on a wall of forty ever wants one.
     */
    private var weatherFace: WeatherFaceView? = null

    /**
     * The forecast the tile was last handed, shown or not.
     *
     * The counterpart to [liveReading] for the one widget that is laid out rather than
     * read: a 1x1 keeps it and puts it up when it is grown. See [showsLive].
     */
    private var weatherReading: WeatherFaceView.Reading? = null

    /** Whether [weatherFace] is the front face right now. */
    private var hasWeatherFace = false

    /**
     * The charge, drawn into the cell that holds it. See [setBatteryFace].
     *
     * Built on first use, like the faces above it.
     */
    private var batteryFace: BatteryFaceView? = null

    /** The charge the tile was last handed. */
    private var batteryReading: BatteryFaceView.Reading? = null

    /** Whether [batteryFace] is the front face right now. */
    private var hasBatteryFace = false

    /**
     * Who the mosaic fills itself from: the people the user starred, and the rest of the
     * book behind them. See [applyPeopleGrid], which decides how many of the second it
     * actually takes.
     */
    private var favourites: List<ContactFeed.Person> = emptyList()
    private var otherPeople: List<ContactFeed.Person> = emptyList()

    private val label = TextView(context)
    private val backText = TextView(context)
    private val backTitle = TextView(context)
    private val backAside = TextView(context)
    /** The reverse's own reading row and the box that holds it. See [liveRow]. */
    private val backRow = android.widget.LinearLayout(context)
    private val backSpacer = View(context)
    private val backBox = android.widget.LinearLayout(context)
    private val notificationIcon = ImageView(context)

    /**
     * Corner mark for a live widget - the weather condition, for instance.
     *
     * Shares the top-right corner with the notification mark, but the two never coexist: a
     * live widget carries no notifications, and an app tile is not a live widget.
     */
    private val widgetGlyph = ImageView(context)

    /**
     * The mark at the foot of a widget, for a fact about the phone rather than about the
     * reading: an alarm is coming.
     *
     * Its own view rather than a second face for [widgetGlyph], because the two say
     * different kinds of thing and a tile may want both at once - and because this one is
     * the same whichever way up the tile is, where the corner mark is per face.
     */
    private val alarmMark = ImageView(context)

    /**
     * Marks a tile that has something unread.
     *
     * On a medium or wide tile it is tappable and turns the tile over to the notification,
     * so it can be read without leaving Start. A small tile has no room for a title and
     * body and cannot turn over at all, so there the dot is a pure indicator and the tap
     * falls through to launching the app - as it does once the tile *is* turned over,
     * where the dot stays on as a mark and has nothing left to turn to.
     *
     * The view is larger than the dot it draws: an inset keeps the mark small while the
     * touch target stays worth aiming at.
     */
    private val notificationDot = View(context)

    /**
     * Invisible hot zone over the tile's top-right corner.
     *
     * Turning a tile over is worth a target of its own: the notification dot is a small
     * mark, and the weather glyph beside it is not a button at all. This sits over both so
     * the whole corner does the same thing regardless of what happens to be drawn there.
     */
    private val flipCorner = View(context)

    /**
     * The same hot zone at the tile's top-left, for the story before this one.
     *
     * A tile that turns through a run of stories on its own is a tile that can turn past
     * the one being read - the clock does not wait to be finished with - and the corner
     * that turns it over only ever goes forward, so the way back was another five faces
     * round. Two corners, one either side, read as what they are: the left hand goes
     * back, the right hand goes on. Only where there is a run to step through; a tile with
     * one face and a reverse has nothing behind it, and the corner stays out of the way of
     * the tap that opens the app.
     */
    private val flipBackCorner = View(context)

    /**
     * The same hot zone at the tile's bottom-left, over the foot mark.
     *
     * The mark itself is eighteen points of clock face, which is a thing to read rather
     * than a thing to hit. The corner around it is what the finger is actually aiming at,
     * exactly as the turn-over corner is the target for the small mark it sits over.
     *
     * Only ever up while the mark is - see [applyAlarmMarkVisibility]. A tile with nothing
     * down there gives the whole corner back to the tap that opens the app.
     */
    private val alarmCorner = View(context)

    // --- Media -------------------------------------------------------------------------
    // An app that is playing something shows that instead of its icon or its notifications:
    // what is on now is more use than what arrived earlier, and the two would otherwise be
    // competing for the same tile.
    private val mediaFace = FrameLayout(context)
    private val mediaTitle = TextView(context)
    private val mediaArtist = TextView(context)
    private val mediaText = android.widget.LinearLayout(context)
    private val mediaControls = android.widget.LinearLayout(context)
    private val mediaPrevious = ImageView(context)
    private val mediaPlayPause = ImageView(context)
    private val mediaNext = ImageView(context)

    /** Play/pause shown in the corner of a small tile, where the dot would otherwise be. */
    private val mediaBadge = ImageView(context)

    /**
     * How far into the track, in the corner beside the app's mark.
     *
     * It used to share the subtitle with the artist, which on anything narrower than a
     * wide tile meant one of the two was cut off - and the artist is the half worth
     * keeping. The corner is empty on the media face apart from the mark, and a running
     * clock is exactly the kind of thing a corner is for.
     */
    private val mediaTime = TextView(context)

    private var media: MediaSessions.Info? = null

    /**
     * Width the transport is holding on a strip, in pixels.
     *
     * Computed where the controls are laid out and read where the text is measured: a
     * margin alone did not reliably keep a long title off the buttons, and a maximum width
     * is not something a TextView can be talked out of.
     */
    private var transportReservePx = 0

    /**
     * Redraws the elapsed time once a second while something is playing.
     *
     * Driven from the tile rather than from the session: media sessions report a position
     * only when something changes, so a clock that ticks has to run locally.
     */
    private val mediaTick = object : Runnable {
        override fun run() {
            bindMediaSubtitle()
            postDelayed(this, MEDIA_TICK_MS)
        }
    }

    var onMediaPlayPause: (() -> Unit)? = null
    var onMediaNext: (() -> Unit)? = null
    var onMediaPrevious: (() -> Unit)? = null

    /** One notification, as this tile shows it: a subject and a body. */
    data class Line(
        val title: String,
        val text: String
    ) {
        /**
         * Where a tap on this line goes, when it has somewhere of its own to go.
         *
         * A notification knows what it is about - the conversation, the message, the
         * order being delivered - and this is the host's way of handing that down to the
         * tile showing it. Null for a line with nothing behind it, and for anything the
         * shell writes itself; those open their program as they always did.
         *
         * Outside the constructor on purpose. Two lines are the same line when they say
         * the same thing, which is what [setNotifications] compares to know whether the
         * queue has changed - and a lambda is never equal to the one built for the same
         * line a tick earlier, so carrying it in the constructor would reset the tile to
         * the top of its queue on every refresh.
         */
        var open: (() -> Unit)? = null
    }

    /** Notification lines to cycle through on the flip face, newest first. */
    private var notifications: List<Line> = emptyList()
    private var notificationIndex = 0

    /** The line to bring up at the midpoint of the turn now running, if any. */
    private var pendingNotification: Int? = null

    /** A live widget's reverse: the same reading told a different way, or more of it. */
    private var widgetBack: Reading? = null

    /**
     * How a live widget's text is set.
     *
     * A temperature and a headline are not the same kind of thing and cannot share a size:
     * one is a number to be read across the room, the other a sentence. [READING] is the
     * default - a clock, a date, an index; [STORY] is prose, set small and allowed to wrap.
     */
    enum class LiveStyle { READING, STORY }

    private var liveStyle = LiveStyle.READING

    /** Whether a re-read of the size-dependent layout is already waiting. See [postSettle]. */
    private var settlePosted = false

    /**
     * Several faces a widget turns through in order, rather than two it alternates.
     *
     * The News tile is a queue, not a coin: each turn brings the next story, and the
     * faces themselves are only the two sides it happens to use to get there.
     */
    private var rotation: List<LiveFace> = emptyList()

    /**
     * The picture behind the face that is showing.
     *
     * Drawn centre-cropped under the tile's own colour, which is laid over it at partial
     * opacity - the same trick the Start background uses. A photograph at full strength
     * would take a tile out of the wall entirely, and the headline over it would be
     * unreadable half the time.
     */
    private var faceBackdrop: Bitmap? = null

    /** The cover of whatever this tile's app is playing, from its media session. */
    private var mediaArt: Bitmap? = null

    // --- Moving faces ------------------------------------------------------------------
    // A clip from the camera roll plays where its still would otherwise sit, muted, for as
    // long as its face is up. Built on first use: one tile in a wall of forty ever wants a
    // video surface, and the rest should not be paying for one.
    private var videoView: android.view.TextureView? = null
    private var videoPlayer: android.media.MediaPlayer? = null
    private var videoSurface: android.view.Surface? = null

    /** Which clip the surface is on, so re-binding the same face does not restart it. */
    private var videoUri: String? = null

    /**
     * The picture behind whatever this tile is currently showing.
     *
     * While something is playing, the cover is the tile's background at any size and on
     * any face - including the 1x1, which has no room for a media face and would otherwise
     * be the one tile playing something that gave no sign of what. A tile is either a live
     * widget turning through stories or an app playing something, so asking in this order
     * means neither has to know the other exists.
     */
    private val backdropForFace: Bitmap?
        get() = when {
            !showsBackdrop -> null
            media != null -> mediaArt
            else -> faceBackdrop
        }

    /**
     * Whether the tile draws the picture it has, or is left as a plain coloured tile.
     *
     * A story's photograph and an album cover are somebody else's picture arriving on a
     * wall the user arranged: they carry the tile's own colour as a wash, but a row of
     * them still reads as a row of photographs rather than as tiles. Turning one off is
     * per tile - the news is worth looking at and the cover of whatever is playing is
     * not, or the other way round - and it is offered wherever there is a picture to
     * turn off; see [hasPicture] and WP81SecondaryBar.
     *
     * It is the drawing that stops, and the fetching with it: an off tile asks for
     * nothing over the network and plays no clip. See [bindBackdrop].
     */
    var showsBackdrop: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // The picture is only asked for when it is going to be drawn, so turning it
            // back on has to go and ask: the face is already up, and nothing else will
            // fetch for it until the tile turns.
            rotation.getOrNull(rotationIndex)?.let { bindBackdrop(it) }
            invalidate()
        }

    /**
     * Whether the tile has a picture behind its face at all.
     *
     * What decides whether the picture command is on the strip: a News tile whose feed
     * carries no photographs has nothing to turn off, and offering the command there is
     * a key that appears to do nothing.
     *
     * A face that asked not to be washed is not counted - the camera roll's. There the
     * picture *is* the tile (see [LiveFace.washed]) and turning it off would leave an
     * empty square rather than a plainer tile.
     */
    val hasPicture: Boolean
        get() = if (media != null) mediaArt != null
        // A 1x1 draws no story picture at all, so there is nothing there to turn off
        // either. See [showsLive].
        else showsLive() && rotation.any { it.washed && it.image.isNotBlank() }

    /** Fetches a story's picture. Set by the host, which owns the network. */
    var backdropLoader: ((String, (Bitmap?) -> Unit) -> Unit)? = null

    /** Which of [rotation] is showing. Read by the host to know what a tap should open. */
    var rotationIndex: Int = 0
        private set

    /**
     * This tile's own offset into the flip cycle, 1-5 seconds.
     *
     * Fixed per tile rather than derived from its position, so the Start screen never
     * settles into a rhythm: an index-based stagger still turns the whole wall over in one
     * sweep, just left to right.
     */
    val flipPhaseMs: Long = 1000L + (Math.random() * 4000L).toLong()

    /**
     * The tile's own flip cycle: a widget's two faces, or an app's icon and what it has
     * waiting.
     *
     * Driven from the tile rather than posted by the host's refresh tick. The tick's job
     * is content, and hanging the flip off it made turning over conditional on things that
     * have nothing to do with it: a widget whose reverse happened to be empty when one
     * fired was never asked again, and a pending flip was thrown away outright whenever
     * the grid was rebuilt. Re-posting from here means the cycle cannot be lost.
     */
    private val liveFlip = object : Runnable {
        override fun run() {
            if (canTurnOver()) {
                when {
                    // A run of faces turns to the next one rather than back and forth.
                    rotation.size > 1 -> advanceRotation()
                    else -> {
                        // A tile with several notifications turns through all of them
                        // before it goes back to its icon: three emails are three things
                        // to read, and returning to the icon between each one meant the
                        // third was a minute away. The queue is stepped at the halfway
                        // point of the turn, where the face being read is edge-on.
                        val more = showingBack && !tile.kind.isLiveWidget &&
                            notificationIndex < notifications.lastIndex
                        if (more) {
                            pendingNotification = notificationIndex + 1
                            flipTo(true)
                        } else {
                            // Back to the top of the queue, ready for the next round.
                            if (showingBack && !tile.kind.isLiveWidget) pendingNotification = 0
                            flipTo(!showingBack)
                        }
                    }
                }
            }
            postDelayed(this, LIVE_FLIP_MS)
        }
    }

    /**
     * Whether the tile should turn itself over right now.
     *
     * Not while it is being arranged, and not while Start is behind the app list: turning
     * over off-screen spends the animation where nobody is looking and lands the tile on a
     * face they did not ask for.
     */
    private fun canTurnOver(): Boolean {
        if (!isShown || isEditMode) return false
        // A folder previewing its contents is already doing something live and never
        // turns itself face-down - but it does turn back up on its own, or a tile left on
        // a notification would stay on it for good. See setFolderPreview.
        if (hasFolderPreview) return showingBack
        return canFlip()
    }

    /**
     * Whether turning this tile over would land on anything but the face it is already on.
     *
     * The tiles that turn are the ones with something held back: a widget's other reading,
     * a run of stories, a notification waiting behind an icon. The rest are whole on the
     * front - a folder is a shelf of programs, the weather is the forecast laid out across
     * the tile, the People wall is already turning over a square at a time - and asking one
     * of those to turn used to spin it half a turn and drop it back, which is an animation
     * saying "nothing here" in place of the thing the tap was actually for. They open
     * instead: the corner stands down (see [applyFlipCornerSize]) so a tap there is a tap
     * on the tile like any other.
     */
    fun canFlip(): Boolean {
        // Media takes the tile outright while it lasts, and the transport is what a corner
        // would be taking taps from.
        if (media != null) return false
        // A folder does not turn over at all. What is on it is the folder - and a quarter
        // of every folder tile was spending its taps saying so.
        if (tile.kind == Tile.Kind.FOLDER || hasFolderPreview) return false
        // The People tile's faces are already turning over, one square at a time, and a
        // tile that spent half its time face-down would be showing them to nobody.
        if (hasPeopleMosaic) return false
        // And a tile showing the whole of the weather at once has nothing to turn over
        // *to*: everything it would have turned through is already on the front. The
        // battery is the same - the charge is one reading, drawn.
        if (hasWeatherFace || hasBatteryFace) return false
        if (!hasFlipContent()) return false
        // A 1x1 app tile has no room for a title and a body; it carries the dot instead.
        // A widget's reverse is a bare reading, which fits anywhere.
        return tile.size.canShowText || tile.kind.isLiveWidget
    }

    /**
     * Puts the tile back on its front face, however it was interrupted.
     *
     * A tile can be left mid-turn by leaving the launcher: the animator stops ticking when
     * the window is not drawing, and what comes back is a tile standing on its edge, or
     * one showing the empty reverse of a face whose content was never bound. That is the
     * blank News tile and the half-turned Calendar. Nothing about a tile's state is worth
     * carrying across a trip to another app, so none of it is.
     */
    fun resetAnimationState() {
        flipAnimator?.cancel()
        flipAnimator = null
        animate().cancel()
        rotationX = 0f
        showingBack = false
        pendingNotification = null
        notificationIndex = 0
        // A rotation's front face is bound at the halfway point of a turn that may never
        // have got there, so it is bound again from what the tile is actually on.
        if (rotation.isNotEmpty()) bindRotationFace(false)
        applyNotificationState()
        restartFlipClock()
    }

    /** Puts a full interval between now and the next turn. */
    private fun restartFlipClock() {
        removeCallbacks(liveFlip)
        postDelayed(liveFlip, LIVE_FLIP_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        videoPlayer?.let { applyVideoTransform(it.videoWidth, it.videoHeight) }
        postSettle()
    }

    /**
     * Re-reads everything that depends on how big the tile turned out to be.
     *
     * A tile is built, filled and asked all of its questions before it has been measured:
     * what it has room to show, how large a reading is set, whether its name fits under
     * the content. Every one of those was answered against a tile of no size, and the
     * answers then stood until something else happened to ask again - which is why a tile
     * could arrive with a name on a 1x1 or a reading at the wrong size and put itself
     * right a few seconds later, when it turned over. Now the packer's answer is what it
     * is laid out from, and the turn has nothing left to correct.
     *
     * Posted rather than done here: [applySize] ends in a layout request, and asking for a
     * layout from inside one is honoured late and unpredictably. Coalesced, so a tile that
     * is measured twice in a frame settles once.
     */
    private fun postSettle() {
        if (settlePosted) return
        settlePosted = true
        post {
            settlePosted = false
            applySize()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(liveFlip)
        // The first turn comes after this tile's own phase, so a wall of tiles never turns
        // over as one sweep.
        postDelayed(liveFlip, flipPhaseMs)
    }

    /** Live-widget face: headline + detail, shown instead of the glyph. */
    private val liveHeadline = TextView(context)
    private val liveDetail = TextView(context)

    /**
     * The small text beside a reading: on the clock and the date, the day and the date
     * itself standing next to the number they belong to.
     */
    private val liveAside = TextView(context)

    /** The reading, and whatever stands against it. */
    private val liveRow = android.widget.LinearLayout(context)

    /** The give between the caption at the head of a face and the reading at its foot. */
    private val liveSpacer = View(context)

    /** That row, and the caption over it. */
    private val liveBox = android.widget.LinearLayout(context)

    // Edit-mode handles, one per corner. See buildEditAffordances.
    private val resizeHandle = ImageView(context)

    /**
     * Top-right handle: takes the tile off Start.
     *
     * What that means depends on whose tile it is, and the icon says which. A tile the
     * user pinned is unpinned; one the shell provides cannot be - it is rebuilt on every
     * refresh and would simply come back - so that one is hidden instead, and wears a
     * struck-through eye rather than a pin.
     */
    private val unpinHandle = ImageView(context)

    /** Whether the handles are worn inside the corners. See [tuckHandles]. */
    private var handlesTucked = false

    /** Tapping that handle. The host decides what unpinning this tile actually does. */
    var onUnpinTap: (() -> Unit)? = null

    /**
     * The foot mark, or the corner around it, was tapped.
     *
     * Its own way out rather than the tile's: the mark is about an alarm wherever it is
     * shown, and the tile under it is showing the date or the time.
     */
    var onAlarmMarkTap: (() -> Unit)? = null

    private var showingBack = false

    /** The turn in progress, so a second one can take over from it cleanly. */
    private var flipAnimator: android.animation.ValueAnimator? = null

    /** How much of its canvas the current glyph covers. See MonochromeIconProvider. */
    private var glyphContentRatio = 1f

    // --- Start background -------------------------------------------------------------
    // With a Start background set, a tile is a window onto it rather than a solid block:
    // it draws the slice of the photo that lies behind its own position on screen, with
    // the accent laid over at partial opacity. The page around the tiles stays flat black
    // or white, so the photo is only ever visible through the tiles.
    /**
     * A colour this tile was given, instead of the accent.
     *
     * The accent is what makes a wall of tiles one thing; a handful of exceptions is what
     * makes it navigable. Null means "whatever the accent is", and stays null through a
     * palette change so a tile that was never recoloured follows the phone.
     */
    private var customAccent: Int? = null

    /** What this tile actually fills with. */
    private val fill: Int get() = customAccent ?: palette.accent

    /**
     * Whether the wall is holding every tile's colour back so the photo shows through.
     *
     * Set by the host from the settings switch; not something a tile can work out for
     * itself, because a tile that was never painted looks exactly the same either way.
     * What it changes is the ground under the tiles that carry words - see [drawFace].
     */
    var tileColorsHidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Whether the wash that carries text goes over every tile showing the photo.
     *
     * Off, the tile is darkened only while it has words of its own on it - see
     * [showingOwnWords] - which is what those words need to be readable and nothing more.
     * On, it is darkened whatever it is showing, so a wall where three tiles happen to be
     * saying something is not a wall with three darker squares in it. Set by the host from
     * the settings switch, for the same reason [tileColorsHidden] is.
     */
    var dimAllTiles: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * How strongly [dimAllTiles] darkens the photograph, 0 (untouched) to 1 (black).
     *
     * The user's, from the slider beside the switch. Only the wash asked for as a look
     * moves with it: the one a tile puts under its own words is there so white text can be
     * read at all, and is [CONTENT_SCRIM_ALPHA] whatever this says.
     */
    var dimAmount: Float = CONTENT_SCRIM_ALPHA
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * The colour this tile is painted in, custom or the scheme's.
     *
     * Read by the wall when it opens a folder: the rules that fence the folder off belong
     * to the tile that was opened, so they are drawn in its colour rather than in the
     * accent every other tile happens to share with it.
     */
    val fillColor: Int get() = fill

    /**
     * Whether this tile is a window onto the Start photograph rather than a block of
     * colour - the same question [drawFace] asks before it paints one.
     *
     * Read by the wall for the same reason [fillColor] is: the rules that fence an open
     * folder off belong to the tile that was opened, so they show whatever it shows. A
     * tile the user painted is solid and its rules are that colour; a tile left to the
     * accent while a photograph is set is a window, and a solid rule beside it reads as a
     * bar laid over the wallpaper rather than as the folder's own edge.
     */
    val showsStartBackground: Boolean
        get() = customAccent == null && startBackground?.isRecycled == false

    private var startBackground: Bitmap? = null
    private var backgroundSrc: Rect? = null
    private var backgroundDest = Rect()

    /**
     * Where a face's own picture is drawn - album art, a story's photograph.
     *
     * Its own rectangle, not the Start background's. Sharing one meant a tile that had
     * ever shown a cover kept the centre-crop that was computed for it, and the wallpaper
     * afterwards drew into a tile-sized box in the corner instead of across the screen.
     */
    private val faceDest = Rect()
    private var backgroundOffsetX = 0f
    private var backgroundOffsetY = 0f

    /**
     * Bilinear filtering for the background draw.
     *
     * Blur is applied by handing the tiles a downscaled bitmap, so the smoothing on the
     * way back up is what actually produces it - without this the photo would come out
     * blocky rather than soft.
     */
    private val backgroundPaint = android.graphics.Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    var isEditMode: Boolean = false
        private set

    /** Bottom-right handle dragged. Raw coordinates, so the host can track the finger. */
    var onResizeDrag: ((TileView, MotionEvent) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        // The accent fill and the background window are painted in onDraw rather than set
        // as a background drawable, so their layering is under our control.
        setWillNotDraw(false)

        addView(frontFace, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(backFace, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        backFace.visibility = GONE

        buildFront()
        buildBack()
        buildMediaFace()
        buildEditAffordances()

        applyPalette(palette)
        TiltEffect.apply(this)
    }

    /**
     * Left-to-right swipe turns the tile over.
     *
     * Only rightwards: a leftward swipe on Start is how the app list is reached, and a
     * tile stealing that would make the shell feel broken.
     */
    private val swipeDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            /**
             * Handled on scroll rather than on fling: a fling needs speed as well as
             * distance, and a deliberate short drag across a tile - which is what this
             * gesture is - often never qualifies.
             */
            override fun onScroll(
                down: MotionEvent?,
                move: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (swipeConsumed) return false
                val start = down ?: return false
                val dx = move.x - start.x
                val dy = move.y - start.y
                if (dx <= 0) return false
                if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false
                if (dx < dp(SWIPE_MIN_DP)) return false
                swipeConsumed = true
                toggleFlip()
                return true
            }
        }
    )

    /** Guards against one drag flipping the tile repeatedly as it continues. */
    private var swipeConsumed = false

    @SuppressLint("ClickableViewAccessibility")
    /**
     * Whether this tile says how many are waiting, or only that some are.
     *
     * A setting rather than a rule, and on by default: the number is what Windows Phone
     * showed and it is strictly more than the dot tells you, but a wall of numbers is not
     * what everyone wants a wall of tiles to be.
     */
    var countsEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateNotificationDot()
            // Which of the two the corner holds on a turned-over tile is this setting's
            // answer as well. See [dotHoldsCorner].
            applyNotificationMark()
            requestLayout()
        }

    /** Where the finger went down, and whether it has since travelled. See [performClick]. */
    private var pressStartX = 0f
    private var pressStartY = 0f
    private var pressDragged = false

    private val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Fed alongside the normal handling rather than replacing it, so taps and
        // long-presses on the tile still behave as they did.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeConsumed = false
                pressStartX = event.rawX
                pressStartY = event.rawY
                pressDragged = false
            }
            MotionEvent.ACTION_MOVE -> if (!pressDragged) {
                val travelled =
                    kotlin.math.hypot(event.rawX - pressStartX, event.rawY - pressStartY)
                if (travelled > tapSlop) pressDragged = true
            }
        }
        swipeDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    /**
     * A finger that travelled is not a tap, whatever it was instead.
     *
     * A tile is large enough that a whole gesture can begin and end inside one: a pull at
     * the top of Start that falls short of the shade, a swipe across the tile to turn it
     * over, a drag on a wall with nothing left to scroll. None of those leave the tile's
     * bounds, so the view's own press handling counts every one of them as a tap and
     * launches the app on release - and the enclosing ScrollView only cancels the press
     * when it takes the gesture over, which it will not do when there is nothing to scroll.
     *
     * So the tile decides for itself. Anything past the system's own tap slop was a
     * movement, and a movement is not a request to open something.
     */
    override fun performClick(): Boolean {
        if (pressDragged) return false
        return super.performClick()
    }

    /**
     * Pulls the lines of a wrapping label closer together.
     *
     * Segoe's default leading is set for paragraphs of body text. On a tile - a headline
     * over three lines, a notification over two - it leaves the block looking like
     * unrelated lines that happen to be stacked rather than one thing that wrapped.
     */
    private fun tightenLines(view: TextView) {
        view.setLineSpacing(0f, LINE_SPACING)
    }

    private fun buildFront() {
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        iconRow.orientation = android.widget.LinearLayout.HORIZONTAL
        iconRow.gravity = Gravity.CENTER_VERTICAL
        iconRow.addView(glyph, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        countLabel.visibility = GONE
        countLabel.maxLines = 1
        countLabel.includeFontPadding = false
        countLabel.typeface = segoe(COUNT_FONT)
        iconRow.addView(countLabel, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        frontFace.addView(iconRow, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        applyLabelStyle(label)
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        for (text in listOf(label, liveHeadline, liveDetail)) tightenLines(text)
        // Added to the tile itself rather than to a face: the app's name stays put whether
        // the tile is showing its icon or a notification.
        addView(label, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START))

        liveBox.orientation = android.widget.LinearLayout.VERTICAL
        liveBox.visibility = GONE
        liveRow.orientation = android.widget.LinearLayout.HORIZONTAL
        liveHeadline.typeface = segoe(TITLE_FONT)
        liveHeadline.maxLines = 1
        liveHeadline.ellipsize = android.text.TextUtils.TruncateAt.END
        // Whether it keeps the font's own padding depends on what the face turns out to be
        // showing, so it is settled with the rest of the typesetting - see
        // [applyLiveTextSizes].
        liveDetail.typeface = segoe(SUBTITLE_FONT)
        liveDetail.maxLines = 2
        liveDetail.ellipsize = android.text.TextUtils.TruncateAt.END
        applyLabelStyle(liveAside)
        liveAside.maxLines = 2
        liveAside.ellipsize = android.text.TextUtils.TruncateAt.END
        liveAside.visibility = GONE
        tightenLines(liveAside)
        // The reading takes exactly the width it needs and the words beside it take what
        // is left, set hard against it: the day belongs to the date it stands next to, and
        // a column of its own would leave the two either side of a gap.
        liveRow.addView(liveAside, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            gravity = Gravity.BOTTOM
            marginEnd = dp(ASIDE_GAP_DP)
        })
        liveRow.addView(liveHeadline, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })
        // The caption spans the tile rather than sharing the row: it is a phrase where the
        // rest is a word and a number, and squeezing it into the reading's column would
        // decide how wide the reading is by how long its caption happens to be.
        liveBox.addView(liveDetail, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        liveBox.addView(liveRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        frontFace.addView(liveBox, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }

    private fun buildBack() {
        backTitle.textSize = LIVE_TITLE_SP
        backTitle.maxLines = 1
        backTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        backTitle.typeface = segoe(TITLE_FONT)

        backText.textSize = LIVE_CAPTION_SP
        backText.maxLines = BODY_LINES
        backText.ellipsize = android.text.TextUtils.TruncateAt.END
        backText.typeface = segoe(SUBTITLE_FONT)
        for (text in listOf(backTitle, backText)) tightenLines(text)

        applyLabelStyle(backAside)
        backAside.maxLines = 2
        backAside.ellipsize = android.text.TextUtils.TruncateAt.END
        backAside.visibility = GONE
        tightenLines(backAside)

        // The reverse of a widget is the same kind of thing as its front, so it is built
        // the same way - see the front's [liveBox].
        backRow.orientation = android.widget.LinearLayout.HORIZONTAL
        backRow.addView(backAside, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            gravity = Gravity.BOTTOM
            marginEnd = dp(ASIDE_GAP_DP)
        })
        backRow.addView(backTitle, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        // The title first, the rest under it - which is how a story is set on the front
        // (see [arrangeFace], where a face of words is a title with the rest under it) and
        // therefore how a notification is set here. The two are the same kind of thing: a
        // line naming what arrived, and a line of it. Built the other way round, they were
        // the same two sizes in the opposite order - the small line heading the tile and
        // the large one under it - so a News tile and a message tile side by side read as
        // having been laid out by different hands.
        //
        // A widget's reverse is re-ordered from here anyway, this being the default the
        // reading layout starts from; see [applyReadingLayout].
        backBox.orientation = android.widget.LinearLayout.VERTICAL
        backBox.addView(backRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        backBox.addView(backText, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))

        backFace.addView(backBox, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        // The app's own mark, so a tile full of message text is still identifiable at a
        // glance. Only shown while a notification is up - the icon face has the full glyph
        // - and not even then where the dot has the corner. See [applyNotificationMark].
        notificationIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        notificationIcon.visibility = GONE
        addView(notificationIcon, LayoutParams(
            dp(CORNER_MARK_DP), dp(CORNER_MARK_DP),
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(CORNER_INSET_DP)
            marginEnd = dp(CORNER_INSET_DP)
        })

        notificationDot.visibility = GONE
        notificationDot.setOnClickListener { showNotificationFace() }
        TiltEffect.apply(notificationDot)
        // Sits in the same corner as the app mark, and the two are never shown together:
        // where the tile marks what is unread with a dot, the dot holds that corner on
        // both faces and the mark stands down. See [dotHoldsCorner].
        addView(notificationDot, LayoutParams(
            dp(NOTIFICATION_DOT_TARGET_DP), dp(NOTIFICATION_DOT_TARGET_DP),
            Gravity.TOP or Gravity.END))

        flipCorner.isClickable = true
        flipCorner.setOnClickListener { toggleFlip() }
        addView(flipCorner, LayoutParams(
            dp(FLIP_CORNER_DP), dp(FLIP_CORNER_DP), Gravity.TOP or Gravity.END))

        flipBackCorner.isClickable = true
        flipBackCorner.setOnClickListener { retreatRotation() }
        addView(flipBackCorner, LayoutParams(
            dp(FLIP_CORNER_DP), dp(FLIP_CORNER_DP), Gravity.TOP or Gravity.START))

        widgetGlyph.scaleType = ImageView.ScaleType.FIT_CENTER
        widgetGlyph.visibility = GONE
        // Same corner as the app mark and the dot; a live widget has neither of those, so
        // the three can never want it at once. Sized and inset in applyWidgetGlyphSize.
        addView(widgetGlyph, LayoutParams(
            dp(CORNER_MARK_DP), dp(CORNER_MARK_DP),
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(CORNER_INSET_DP)
            marginEnd = dp(CORNER_INSET_DP)
        })

        alarmMark.scaleType = ImageView.ScaleType.FIT_CENTER
        alarmMark.visibility = GONE
        // The corner every mark on this tile uses, so a mark means the same thing wherever
        // it is seen. It used to sit at the foot, which is the label's band: the two could
        // not both be up, and the tile that carries this mark is now the tile that carries
        // its program's name. Nothing contends for the corner - the two tiles that wear
        // this mark are the clock and the calendar, and neither turns over or carries a
        // widget mark of its own. Sized and inset by [applyCornerMark], a couple of
        // points under the rest - see [SMALL_MARK_DP].
        addView(alarmMark, LayoutParams(
            dp(SMALL_MARK_DP), dp(SMALL_MARK_DP),
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(CORNER_INSET_DP)
            marginEnd = dp(CORNER_INSET_DP)
        })

        alarmCorner.isClickable = true
        alarmCorner.visibility = GONE
        alarmCorner.setOnClickListener { onAlarmMarkTap?.invoke() }
        // After the mark, so the corner is the one that takes the touch: a FrameLayout
        // offers a press to its children back to front, and the mark is not clickable to
        // pass it on with.
        addView(alarmCorner, LayoutParams(
            dp(FLIP_CORNER_DP), dp(FLIP_CORNER_DP),
            Gravity.TOP or Gravity.END))
    }

    /**
     * The one corner handle a selected tile shows.
     *
     * ```
     *          [  tile  ]
     *                     (resize)
     * ```
     *
     * Resizing is a drag, so it needs a grip on the tile itself. The tile's commands moved
     * to the navigation bar: a menu is a list of words, and a strip across the bottom holds
     * words better than a 30dp circle in a corner does.
     */
    /**
     * The media face: what is playing, over transport controls.
     *
     * How much of it is shown depends on the tile - see [applyMediaLayout]. A wide tile
     * carries the full transport, a medium one just play/pause, and a small one has no
     * room for text at all and shows only a badge.
     */
    private fun buildMediaFace() {
        mediaTitle.maxLines = 2
        mediaTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        mediaTitle.typeface = segoe(FACE_TITLE_FONT)

        mediaArtist.maxLines = 1
        mediaArtist.ellipsize = android.text.TextUtils.TruncateAt.END
        // The pair the weather tile and the calendar are set in - see [FACE_TITLE_SP]: the
        // name of the thing heading the face, and the line saying something about it
        // underneath. A track with its artist under it is the same shape as a place with
        // its sky or an appointment with its hour, and the three were being set in three
        // different faces on tiles standing next to each other.
        mediaArtist.typeface = segoe(SUBTITLE_FONT)
        for (text in listOf(mediaTitle, mediaArtist)) tightenLines(text)

        mediaText.orientation = android.widget.LinearLayout.VERTICAL
        // Both lines take the width they need rather than the width they are offered. The
        // ceiling that keeps them clear of the clock and the mark is a maxWidth (see
        // onMeasure), and a text view told exactly how wide to be ignores its maxWidth
        // outright - as MATCH_PARENT here told them to. That is the title running under
        // the elapsed time on a strip.
        mediaText.addView(mediaTitle, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // Pulled up under the title. The two are one thing - a track - and the line boxes
        // either side of the gap leave more air between them than there is between the
        // title's own two lines.
        mediaText.addView(mediaArtist, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = -dp(MEDIA_TEXT_TIGHTEN_DP) })
        mediaFace.addView(mediaText, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        mediaPrevious.setImageResource(R.drawable.wp81_media_previous)
        mediaPlayPause.setImageResource(R.drawable.wp81_media_play)
        mediaNext.setImageResource(R.drawable.wp81_media_next)
        for ((control, action) in listOf(
            mediaPrevious to { onMediaPrevious?.invoke() },
            mediaPlayPause to { onMediaPlayPause?.invoke() },
            mediaNext to { onMediaNext?.invoke() }
        )) {
            control.scaleType = ImageView.ScaleType.FIT_CENTER
            control.isClickable = true
            control.setOnClickListener { action() }
            TiltEffect.apply(control)
            mediaControls.addView(control, android.widget.LinearLayout.LayoutParams(
                dp(CONTROL_MEDIUM_DP), dp(CONTROL_MEDIUM_DP)).apply { marginEnd = dp(10) })
        }
        mediaControls.orientation = android.widget.LinearLayout.HORIZONTAL
        mediaControls.gravity = Gravity.CENTER_VERTICAL
        mediaFace.addView(mediaControls, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        mediaFace.visibility = GONE
        addView(mediaFace, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Semibold, like the count on an app's tile: it is a figure being read off rather
        // than a caption, and at this size the semilight face left the digits looking like
        // they belonged to whatever was under them.
        mediaTime.typeface = segoe(COUNT_FONT)
        mediaTime.textSize = LIVE_CAPTION_SP
        mediaTime.maxLines = 1
        mediaTime.includeFontPadding = false
        mediaTime.visibility = GONE
        // Its own text sits in the middle of whatever height it is given, which is how it
        // ends up level with the mark rather than above it: the mark is a square with the
        // artwork centred in it, so matching its box and centring within it is the only
        // way to line the two up. Both the height and the offset are settled in onMeasure,
        // where the mark's size is known.
        mediaTime.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        addView(mediaTime, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply {
            topMargin = dp(CORNER_INSET_DP)
        })

        mediaBadge.scaleType = ImageView.ScaleType.FIT_CENTER
        mediaBadge.visibility = GONE
        mediaBadge.isClickable = true
        mediaBadge.setOnClickListener { onMediaPlayPause?.invoke() }
        addView(mediaBadge, LayoutParams(
            dp(NOTIFICATION_DOT_TARGET_DP), dp(NOTIFICATION_DOT_TARGET_DP),
            Gravity.TOP or Gravity.END))
    }

    /**
     * Sets what this tile's app is playing, or null when it is not playing anything.
     *
     * Media outranks notifications: an app that is playing shows the track, and its older
     * notifications wait until it stops.
     */
    fun setMedia(info: MediaSessions.Info?) {
        val artChanged = mediaArt !== info?.art
        media = info
        mediaArt = info?.art
        if (artChanged) invalidate()
        removeCallbacks(mediaTick)
        if (info != null) {
            mediaTitle.text = info.title
            bindMediaSubtitle()
            if (info.isPlaying) postDelayed(mediaTick, MEDIA_TICK_MS)
            val icon = if (info.isPlaying) R.drawable.wp81_media_pause
                       else R.drawable.wp81_media_play
            mediaPlayPause.setImageResource(icon)
            mediaBadge.setImageResource(icon)
            mediaPrevious.visibility = if (info.canSkipPrevious) VISIBLE else GONE
            mediaNext.visibility = if (info.canSkipNext) VISIBLE else GONE
        }
        applyNotificationState()
    }

    fun hasMedia(): Boolean = media != null

    /**
     * The line under the title: who it is by, and how far in it is.
     *
     * Both share one line rather than getting one each - even a wide tile only has room
     * for two lines above the controls, and the title has to be one of them.
     */
    private fun bindMediaSubtitle() {
        val info = media ?: return
        val elapsed = formatDuration(info.currentPositionMs())
        mediaTime.text = elapsed
        mediaArtist.text = info.artist
        mediaArtist.visibility = if (info.artist.isBlank()) GONE else VISIBLE
    }

    /** m:ss, or h:mm:ss for anything past an hour. */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(mediaTick)
        removeCallbacks(liveFlip)
        stopVideo()
        stopDrift()
    }

    /** Chooses how much of the media face this tile has room for. */
    private fun applyMediaLayout() {
        val showing = media != null && tile.size.canShowText
        val pad = dp(10)
        mediaFace.setPadding(
            pad + dp(2), pad, pad,
            if (tile.size.isStrip) pad else pad + dp(NOTIFICATION_LABEL_GAP_DP)
        )

        mediaArtist.visibility =
            if (tile.size.hasTwoTextLines && mediaArtist.text.isNotEmpty()) VISIBLE else GONE

        // What is playing is a name and who it is by is the line under it; they are set
        // like every other name and line on the wall rather than to the tile they happen
        // to land on. See [FACE_TITLE_SP].
        mediaTitle.textSize = FACE_TITLE_SP
        mediaArtist.textSize = LIVE_CAPTION_SP

        // A strip runs the text and the transport side by side rather than stacked, which
        // is the only way a title and any controls at all share one row - so it gets one
        // line whatever else it is given.
        if (tile.size.isStrip) mediaTitle.maxLines = 1
        when {
            // One cell across has no room for a transport, whatever its height.
            tile.size.cols <= 1 -> mediaControls.visibility = GONE

            // Play/pause only on a two-cell strip. Three controls across two cells left
            // the title with a third of the row, and skipping a track is a command you can
            // afford to open the app for - stopping it is not.
            tile.size.isStrip && tile.size.cols <= 2 -> {
                mediaControls.visibility = if (showing) VISIBLE else GONE
                mediaPrevious.visibility = GONE
                mediaNext.visibility = GONE
            }

            // Full transport everywhere else: three controls do fit across two cells once
            // they are sized for it - see applyMediaControlMetrics - and skipping a track
            // is the thing most worth reaching for.
            else -> {
                mediaControls.visibility = if (showing) VISIBLE else GONE
                mediaPrevious.visibility =
                    if (media?.canSkipPrevious == true) VISIBLE else GONE
                mediaNext.visibility = if (media?.canSkipNext == true) VISIBLE else GONE
            }
        }
        if (!tile.size.isStrip && tile.size != TileSize.SMALL) mediaTitle.maxLines = 2
        applyMediaControlMetrics()
    }

    /**
     * Sizes and places the transport controls for the current tile.
     *
     * They were laid out once at a fixed 26dp pinned to the bottom-left, which on a wide
     * tile left three small buttons huddled in a corner of a large empty area. Bigger
     * targets, and centred wherever the tile is wide enough for centring to read as
     * deliberate rather than as a gap on one side.
     */
    private fun applyMediaControlMetrics() {
        // Read off the footprint rather than off a list of names: the wall can be set wide
        // enough that a tile is five or six cells across, and a size that only knew the
        // 4x2 left every one of those with the smallest controls there are.
        val wide = tile.size.cols >= 4
        val edge = when {
            // Four cells across and two deep: the transport has the whole lower half of a
            // large tile and can be reached for without aiming.
            wide && !tile.size.isStrip -> CONTROL_LARGE_DP
            // The same width, one row: the controls share it with the title, so they take
            // the middle size rather than the large one.
            wide -> CONTROL_MEDIUM_DP
            // Sized down from the wide tile so all three fit across two cells - and the
            // two-cell strip takes the same, rather than the size it was squeezed to back
            // when three controls had to fit on it.
            else -> CONTROL_SMALL_DP
        }
        val gap = when {
            wide && !tile.size.isStrip -> dp(16)
            tile.size.cols == 2 && tile.size.isStrip -> dp(2)
            tile.size.cols == 2 -> dp(6)
            else -> dp(8)
        }
        for (control in listOf(mediaPrevious, mediaPlayPause, mediaNext)) {
            control.layoutParams = (control.layoutParams as android.widget.LinearLayout.LayoutParams)
                .apply {
                    width = dp(edge)
                    height = dp(edge)
                    marginEnd = gap
                }
        }

        if (tile.size.isStrip) {
            // Side by side: text on the left taking what is left over, transport pinned to
            // the bottom right. Stacking text and transport would give each about half of
            // an already short row - and the bottom corner rather than the middle of the
            // edge leaves the top one to the app's mark, which is the only thing on this
            // face that says what is playing it.
            (mediaControls.layoutParams as LayoutParams).gravity =
                Gravity.END or Gravity.BOTTOM
            (mediaText.layoutParams as LayoutParams).apply {
                // The top corner, as on every other tile that names something - see
                // [FACE_TITLE_SP]. Centring it was the strip reading its own height as
                // permission to float: a name and the line under it start where the tile
                // starts, and a strip whose text sat a few points lower than the tile
                // beside it was the pair set in one hand and placed in another.
                gravity = Gravity.START or Gravity.TOP
                // Reserve exactly what the transport occupies - each control is `edge`
                // wide and carries `gap` after it - plus a clearance, so the title
                // ellipsises short of the first button instead of sliding under it. None
                // of it is held back when there is no transport to hold it back for.
                val transport =
                    if (mediaControls.visibility == VISIBLE)
                        visibleControlCount() * (dp(edge) + gap)
                    else 0
                transportReservePx =
                    if (transport == 0) 0 else transport + dp(CONTROL_CLEARANCE_DP)
                marginEnd = transportReservePx
            }
        } else {
            transportReservePx = 0
            (mediaControls.layoutParams as LayoutParams).gravity =
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            (mediaText.layoutParams as LayoutParams).apply {
                gravity = Gravity.TOP
                marginEnd = 0
            }
        }
        mediaControls.requestLayout()
        mediaText.requestLayout()
    }

    private fun visibleControlCount(): Int =
        listOf(mediaPrevious, mediaPlayPause, mediaNext).count { it.visibility == VISIBLE }

    private fun buildEditAffordances() {
        resizeHandle.setImageResource(R.drawable.wp81_handle_resize)
        resizeHandle.visibility = GONE

        // Forwarded rather than handled here: only the parent knows the grid geometry a
        // resize has to snap to.
        resizeHandle.setOnTouchListener { _, event ->
            claimGesture(event)
            onResizeDrag?.invoke(this, event)
            true
        }

        unpinHandle.visibility = GONE
        unpinHandle.setOnClickListener { onUnpinTap?.invoke() }
        TiltEffect.apply(unpinHandle)

        // White on the black disc, whatever the artwork was drawn in and whatever the page
        // behind it is doing. See wp81_handle_circle.
        val mark = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        for (handle in listOf(resizeHandle, unpinHandle)) {
            handle.setBackgroundResource(R.drawable.wp81_handle_circle)
            handle.imageTintList = mark
            // No padding, and the disc does the trimming instead. These marks are drawn to
            // sit in a circle already; insetting them as well shrank them twice and left a
            // small icon adrift in the middle of a large button.
            handle.setPadding(0, 0, 0, 0)
            handle.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            handle.clipToOutline = true
        }

        val size = dp(HANDLE_DP)
        // Centred *on* the corner rather than tucked inside it, so half of each handle
        // hangs off the tile. Inside, a handle is a hole punched in the tile's own face
        // and reads as part of it; on the corner it reads as something attached to the
        // tile, which is what it is. See clipChildren below.
        val overhang = -size / 2
        addView(resizeHandle, LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = overhang
            bottomMargin = overhang
        })
        // Added after the resize handle so it sits above everything the tile draws, and
        // in the opposite corner from it: the two are never confused for one another.
        addView(unpinHandle, LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            marginEnd = overhang
            topMargin = overhang
        })

        // The half that hangs off has to be drawn, and a FrameLayout clips its children to
        // its own bounds by default. The grid does the same and is turned off there too.
        clipChildren = false
        clipToPadding = false
        applyUnpinHandle()
    }

    /**
     * Puts the right mark on the top-right handle, and takes the handle away where it
     * would be a lie.
     *
     * Settings is the one built-in with nowhere to hide to: it is the only route back into
     * this shell's own settings page, and a Start screen without it is one the user cannot
     * get out of.
     */
    private fun applyUnpinHandle() {
        val offerable = tile.kind != Tile.Kind.SETTINGS
        unpinHandle.setImageResource(
            if (tile.kind.isBuiltIn) R.drawable.wp81_edit_hide else R.drawable.wp81_edit_unpin
        )
        unpinHandle.isEnabled = offerable
        if (!offerable) unpinHandle.visibility = GONE
    }

    /**
     * Stops the enclosing ScrollView scrolling away mid-drag: a handle drag is mostly
     * vertical, which is exactly what the scroller would otherwise claim.
     */
    private fun claimGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun segoe(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    /**
     * Sets a view in the wall's label style. See [TileLabel].
     *
     * The size is a starting point rather than the last word for anything that has to fit
     * a gap it does not control - [sizeAside] takes it back down where the number beside
     * it has left nothing - but everything in this style starts here.
     */
    private fun applyLabelStyle(view: TextView) {
        view.textSize = TileLabel.SIZE_SP
        view.typeface = segoe(TileLabel.FONT)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun sp(v: Float) = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ---------------------------------------------------------------- content

    /**
     * Sets the tile art. A [MonochromeIconProvider.Glyph.Monochrome] is tinted white to
     * sit on the accent fill; a [MonochromeIconProvider.Glyph.FullColor] is the app's own
     * icon and must be drawn untouched.
     */
    /** Gives the tile a colour of its own, or hands it back to the accent with null. */
    fun setTileColor(color: Int?) {
        if (customAccent == color) return
        customAccent = color
        invalidate()
    }

    fun setGlyph(g: MonochromeIconProvider.Glyph?) {
        when (g) {
            is MonochromeIconProvider.Glyph.Monochrome -> {
                glyph.setImageDrawable(g.drawable)
                glyph.imageTintList =
                    android.content.res.ColorStateList.valueOf(palette.onAccent())
                glyphContentRatio = g.contentRatio
                setNotificationIcon(g.drawable, tint = true)
            }
            is MonochromeIconProvider.Glyph.FullColor -> {
                glyph.setImageDrawable(g.drawable)
                glyph.imageTintList = null
                glyphContentRatio = g.contentRatio
                setNotificationIcon(g.drawable, tint = false)
            }
            null -> {
                glyph.setImageDrawable(null)
                glyphContentRatio = 1f
            }
        }
        requestLayout()
    }

    /**
     * The apps inside a folder, previewed on its tile in place of the folder glyph.
     *
     * An empty list hands the tile back to its glyph, which is what a folder with nothing
     * in it gets - and what every tile that is not a folder gets, since nothing else has
     * contents to preview.
     *
     * A folder showing its contents does not turn itself over any more. It has a live
     * behaviour of its own now - the quarters taking turns - and the two cannot share the
     * tile: a folder that spent half its time face-down would be previewing nothing. Its
     * notifications are still there behind the corner and the swipe, and which app they
     * came from is on the front, as a dot on the app itself.
     */
    /** What its preview is showing, if it has one. See [setFolderPreview]. */
    val folderPreviewEntries: List<FolderPreviewView.Entry>
        get() = folderPreview?.contents.orEmpty()

    fun setFolderPreview(entries: List<FolderPreviewView.Entry>) {
        val show = entries.isNotEmpty()
        // Nothing to show and nothing built to show it in, which is every tile that is
        // not a folder. Asked on every notification refresh, so it answers before it
        // builds anything.
        if (!show && folderPreview == null) return
        val preview = requireFolderPreview()
        hasFolderPreview = show
        preview.setEntries(entries)
        // Visible even while the folder is open: what an emptied one drops is the apps and
        // the name, not the scoring the tile is divided by. See [applyEmptied].
        preview.visibility = if (show) VISIBLE else GONE
        preview.setContentsHidden(isEmptied)
        iconRow.visibility = if (show || isEmptied) GONE else VISIBLE
        applyFolderPreviewGrid()
        // Re-derives the whole front: on a one-row tile the folder's name steps aside for
        // the preview, and the corner dot gives way to the ones on the squares.
        applyNotificationState()
    }

    /**
     * Keeps the preview's bottom row clear of the folder's name.
     *
     * The band is the label's own line height plus its padding, so it follows the type
     * rather than restating it - and it comes off the grid as a whole, which keeps all
     * four quarters the same size as each other.
     */
    /**
     * Fits the preview's squares to the tile's footprint.
     *
     * One square is half the tile's *shorter* side, and the grid is however many of those
     * fill it: two by two on the 1x1 and the 2x2, four by two on a wide tile, eight by two
     * on a full-width strip, two by three and two by four on the tall ones. Halving the
     * short side rather than the height is what keeps them square whichever way the tile
     * is stretched.
     */
    /**
     * Works out the preview's grid from the tile's footprint.
     *
     * The name always has the bottom band, and how much of the tile that is depends on how
     * tall the tile is: one row deep and it takes half, two rows a third, three rows a
     * quarter. One line of type is one line of type whatever the tile is, so the taller the
     * tile the less of it the name needs.
     *
     * What is left over is the squares', and they get deeper as the tile does - one row of
     * them on a short tile, two on a square one, four on a tall one. The columns then
     * follow, because the squares are square: how many fit across is simply how much wider
     * the tile is than one row of them is tall.
     */
    private fun applyFolderPreviewGrid() {
        val preview = folderPreview ?: return
        val tall = tile.size.rows
        val squareRows = when (tall) {
            1 -> 1
            2 -> 2
            3 -> 4
            else -> tall + 1
        }
        // A half, a third, a quarter, a fifth: the name's share is one row's worth of the
        // tile plus itself.
        val fraction = tall / (tall + 1f)
        val cols = ((tile.size.cols.toFloat() / tall) * squareRows / fraction)
            .toInt().coerceAtLeast(1)
        preview.setLayout(cols, squareRows, fraction)
    }

    /**
     * The preview grid, made on first use.
     *
     * Fills the tile rather than sitting in the middle of it: the preview *is* the tile,
     * divided into squares.
     */
    private fun requireFolderPreview(): FolderPreviewView =
        folderPreview ?: FolderPreviewView(context, palette).also { preview ->
            folderPreview = preview
            preview.visibility = GONE
            frontFace.addView(preview, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    /**
     * The address book, filling the People tile in place of a glyph or a reading.
     *
     * An empty list hands the tile back to whatever it would otherwise show, which is the
     * invitation to grant access - so the same tile is a wall of faces once there is one
     * to draw and an ordinary live widget until then, without the host having to know
     * which of the two it is looking at.
     *
     * Like a folder showing its contents, a tile showing the mosaic does not turn itself
     * over any more: it has a live behaviour of its own, and the two cannot share the
     * tile. See [canTurnOver].
     */
    fun setPeopleMosaic(
        favourites: List<ContactFeed.Person>,
        others: List<ContactFeed.Person> = emptyList()
    ) {
        // Held before anything else is decided, so that a wall arriving at a 1x1 is still
        // there to go up when the tile is grown. See [showsLive].
        this.favourites = favourites
        this.otherPeople = others
        val show = (favourites.isNotEmpty() || others.isNotEmpty()) && showsLive()
        // Nothing to show and nothing built to show it in, which is every tile that is
        // not the People tile. Asked on every refresh, so it answers before it builds.
        //
        // Unless there is a live face up. Clearing the wall is also how the tile is told
        // to stop being a widget - a missed call does exactly that - and the face it was
        // showing has to come down whether or not a mosaic was ever built to replace. Left
        // in, the tile sat on "People / looking…" forever, because an empty run does not
        // take a live face down and there was nothing else that would.
        if (!show && peopleMosaic == null && !hasLiveContent()) return
        val mosaic = requirePeopleMosaic()
        hasPeopleMosaic = show
        mosaic.visibility = if (show) VISIBLE else GONE
        if (show) {
            // The mosaic *is* the tile: neither the glyph nor the reading that stood in
            // for it while the address book was out of reach has anywhere left to sit.
            iconRow.visibility = GONE
            liveBox.visibility = GONE
            // The invitation turns over like any other widget, so the faces can arrive at
            // a tile that is face-down or halfway through a turn - and the mosaic is on
            // the front. It is brought back rather than left showing a blank reverse.
            flipAnimator?.cancel()
            rotationX = 0f
            if (showingBack) {
                showingBack = false
                applyNotificationState()
            }
        } else {
            // The wall has been taken away - the address book is out of reach, or a missed
            // call has claimed the tile. Whatever hid the icon when the faces arrived has
            // to be undone, or the tile is left showing nothing at all: an empty mosaic
            // over a hidden icon is a blank square of accent.
            liveBox.visibility = GONE
            iconRow.visibility = if (isEmptied) GONE else VISIBLE
            applyNotificationState()
        }
        applyPeopleGrid()
        applyLabelVisibility()
        // The wall is one of the faces that leaves a tile with nowhere to turn, so whether
        // the corner is there at all is this call's to settle. See [canFlip].
        applyFlipCornerSize()
    }

    /**
     * Fits the mosaic to the tile's footprint, and to how many people there are to put in
     * it.
     *
     * Three faces across the short side is the nine-square People tile as Windows Phone
     * drew it, and as many rows as it takes to fill the rest - eighteen on the wide tile.
     * Two exceptions there, both about the tile running out of room: the 1x1 halves rather
     * than thirds, because a ninth of it is no longer a face, and a one-row strip gives its
     * whole height to a single row of them.
     *
     * Then who there is to put in it. The favourites come first and always - they are what
     * the tile is about - but four of them on a nine-square tile would be four faces and
     * five holes, so the rest of the address book makes up the difference, and [SPARE]
     * more than that go in behind them so there is somebody for a square to turn over to.
     * A wall of four favourites on a 3x3 tile is those four, five other contacts, and
     * three more waiting.
     *
     * Only if the phone has them. A book with fewer people in it than the tile has squares
     * falls back on the coarser grids behind each footprint - the same shape, the squares
     * still square - and takes the densest one it can actually fill, so a phone with four
     * contacts on it shows a 2x2 wherever the tile lands rather than a grid with holes.
     */
    private fun applyPeopleGrid() {
        val mosaic = peopleMosaic ?: return
        val grids = peopleGrids()
        // The densest grid the people on hand fill, or the coarsest there is when even that
        // is more squares than people - one face is better than one face and a hole.
        val available = favourites.size + otherPeople.size
        val (cols, rows) = grids.firstOrNull { (c, r) -> c * r <= available } ?: grids.last()
        mosaic.setGrid(cols, rows)
        // The grid decides how many people are wanted, so the wall is filled after it is
        // shaped rather than before.
        mosaic.setPeople(peopleForGrid(cols * rows))
    }

    /**
     * The mosaics this footprint can be drawn as, densest first.
     *
     * Worked out from the spans rather than looked up by footprint, because there is no
     * list of footprints any more: the wall can be set six or eight cells across and a tile
     * stretched to any of them, and every one of those needs a grid.
     *
     * The rule is the one [applyPeopleGrid] describes - [FACES_ACROSS] along the shorter
     * side, as many of that same square as fill the longer, then the same shape with fewer
     * across for the fallbacks - with its two exceptions written out first.
     */
    private fun peopleGrids(): List<Pair<Int, Int>> {
        val cols = tile.size.cols
        val rows = tile.size.rows
        // A square face is `across` of them along the short side; the long side takes as
        // many of that same square as fit, rounded down so they are never cut off.
        fun grid(across: Int): Pair<Int, Int> =
            if (rows <= cols) across * cols / rows to across
            else across to across * rows / cols

        return when {
            // One row of faces the full height of the strip, then halved, then one.
            rows == 1 && cols > 1 ->
                listOf(cols to 1, (cols / 2).coerceAtLeast(1) to 1, 1 to 1).distinct()
            // Halves rather than thirds - see above - which for the 1x1 is the 2x2 it
            // has always been drawn as.
            cols == 1 -> listOf(2 to 2 * rows, 1 to rows).distinct()
            else -> (FACES_ACROSS downTo 1).map { grid(it) }.distinct()
        }
    }

    /**
     * The favourites, and enough of the rest of the book to fill [slots] and turn over.
     *
     * Nobody else is added once the favourites already outnumber the squares: they have
     * their own spares by then, and a tile that is about the people the user marked should
     * not be showing anybody it did not have to.
     */
    private fun peopleForGrid(slots: Int): List<ContactFeed.Person> {
        val wanted = (slots + SPARE - favourites.size).coerceAtLeast(0)
        return favourites + otherPeople.take(wanted)
    }

    private fun requirePeopleMosaic(): PeopleMosaicView =
        peopleMosaic ?: PeopleMosaicView(context, palette).also { mosaic ->
            peopleMosaic = mosaic
            mosaic.visibility = GONE
            frontFace.addView(mosaic, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    /**
     * The whole of the weather, laid across the tile in place of a face it would turn to.
     *
     * Null puts the tile back to whatever it would otherwise show, exactly as an empty
     * mosaic hands the People tile back - which is what a tile gets while there is no
     * reading cached to lay on it.
     *
     * Like the mosaic, a tile showing this does not turn itself over: everything it could
     * turn to is already on the front. See [canTurnOver].
     */
    fun setWeatherFace(reading: WeatherFaceView.Reading?) {
        // Held before anything else is decided, so a forecast that arrived at a 1x1 is
        // still there to lay across the tile once it is grown. See [showsLive].
        weatherReading = reading
        val show = reading != null && showsLive()
        // Nothing to show and nothing built to show it in, which is every tile but one.
        // Asked on every weather refresh, so it answers before it builds anything.
        if (!show && weatherFace == null) return
        val face = requireWeatherFace()
        hasWeatherFace = show
        face.setReading(reading)
        face.cell = cellSize()
        face.visibility = if (show && !isEmptied) VISIBLE else GONE
        if (show) {
            // The face *is* the tile: neither the icon nor anything it was turning through
            // has anywhere left to sit.
            iconRow.visibility = GONE
            liveBox.visibility = GONE
            // A reading can arrive at a tile that is face-down or halfway through a turn -
            // whatever it was showing runs on its own clock - and this is on the front. It
            // is brought back rather than left showing a blank reverse.
            flipAnimator?.cancel()
            rotationX = 0f
            if (showingBack) {
                showingBack = false
                applyNotificationState()
            }
        } else {
            // What hid the icon when the face went up has to be undone, or the tile is
            // left as a blank square of accent.
            iconRow.visibility = if (isEmptied) GONE else VISIBLE
            applyNotificationState()
        }
        applyLabelVisibility()
        // Everything the tile could have turned to is on the front while the forecast is
        // laid across it, so the corner goes with it. See [canFlip].
        applyFlipCornerSize()
    }

    /**
     * The weather face, made on first use.
     *
     * Fills the tile rather than sitting in the middle of it, as the mosaic and the folder
     * preview do: the reading is the tile, laid out.
     */
    private fun requireWeatherFace(): WeatherFaceView =
        weatherFace ?: WeatherFaceView(context, palette).also { face ->
            weatherFace = face
            face.visibility = GONE
            frontFace.addView(face, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    /**
     * The charge, laid across the tile in place of the mark it would otherwise wear.
     *
     * The one live face that goes up at every footprint, the 1x1 included, where every
     * other widget stands down to its icon - see [showsLive]. It can, because what it
     * draws *is* a mark: a battery with the level painted inside it says how full the
     * phone is in a square one cell across, which is precisely what a headline, a
     * photograph or a bare number cannot do at that size. The figure beside it is the part
     * that needs room, and the face drops it when there is none. See BatteryFaceView.
     *
     * Null puts the tile back to its icon, which is what it gets on a phone that will not
     * say what the battery is doing.
     */
    fun setBatteryFace(reading: BatteryFaceView.Reading?) {
        batteryReading = reading
        val show = reading != null
        if (!show && batteryFace == null) return
        val face = requireBatteryFace()
        hasBatteryFace = show
        face.setReading(reading)
        face.cell = cellSize()
        face.visibility = if (show && !isEmptied) VISIBLE else GONE
        if (show) {
            // The face is the whole tile: neither the icon nor anything it was turning
            // through has anywhere left to sit.
            iconRow.visibility = GONE
            liveBox.visibility = GONE
            // A reading can arrive at a tile that is face-down or halfway through a turn,
            // and this is on the front. It is brought back rather than left showing a
            // blank reverse.
            flipAnimator?.cancel()
            rotationX = 0f
            if (showingBack) {
                showingBack = false
                applyNotificationState()
            }
        } else {
            iconRow.visibility = if (isEmptied) GONE else VISIBLE
            applyNotificationState()
        }
        applyLabelVisibility()
        // Everything the tile could have turned to is on the front while the charge is
        // laid across it, so the corner goes with it. See [canFlip].
        applyFlipCornerSize()
    }

    /** The battery face, made on first use. Fills the tile, as the weather's does. */
    private fun requireBatteryFace(): BatteryFaceView =
        batteryFace ?: BatteryFaceView(context, palette).also { face ->
            batteryFace = face
            face.visibility = GONE
            frontFace.addView(face, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    fun setGlyphResource(res: Int) {
        glyph.setImageResource(res)
        glyph.imageTintList = android.content.res.ColorStateList.valueOf(palette.onAccent())
    }

    fun setGlyphDrawable(d: Drawable?, tint: Boolean) {
        glyph.setImageDrawable(d)
        glyph.imageTintList =
            if (tint) android.content.res.ColorStateList.valueOf(palette.onAccent()) else null
        glyphContentRatio = d?.let { MonochromeIconProvider.measureContentRatio(it) } ?: 1f
    }

    // The corner mark, one per face. A widget whose two faces are about different things -
    // today and tomorrow - cannot carry one mark for both without the reverse claiming
    // this morning's weather for tomorrow afternoon.
    private var widgetGlyphFront: Int? = null
    private var widgetGlyphBack: Int? = null

    /** How much of its canvas the corner glyph covers, and which one that was measured for. */
    private var widgetGlyphRatio = 1f
    private var widgetGlyphMeasuredFor = 0

    /**
     * Sets the live widget's corner mark.
     *
     * [back] defaults to [front]: most widgets show the same mark whichever way up they
     * are, and a null [front] clears the corner entirely.
     */
    fun setWidgetGlyph(front: Int?, back: Int? = front) {
        widgetGlyphFront = front
        widgetGlyphBack = back
        applyWidgetGlyph()
    }

    /** Puts up the mark for the face that is showing. */
    private fun applyWidgetGlyph() {
        val face = rotation.getOrNull(rotationIndex)
        val res = when {
            // A rotation's faces bring their own; the front-and-reverse pair is for the
            // widgets that only have two sides.
            face != null -> face.glyph
            showingBack -> widgetGlyphBack
            else -> widgetGlyphFront
        }
        if (res == null) {
            widgetGlyph.setImageDrawable(null)
            widgetGlyph.visibility = GONE
            return
        }
        widgetGlyph.setImageResource(res)
        widgetGlyph.imageTintList =
            android.content.res.ColorStateList.valueOf(palette.onAccent())
        // Measured once per mark: the weather's changes with the forecast, and rasterising
        // it on every refresh would be paying for the same answer over and over.
        if (widgetGlyphMeasuredFor != res) {
            widgetGlyphMeasuredFor = res
            widgetGlyphRatio = widgetGlyph.drawable
                ?.let { MonochromeIconProvider.measureContentRatio(it) } ?: 1f
        }
        applyWidgetGlyphVisibility()
        applyWidgetGlyphSize()
    }

    /**
     * Shows the corner mark whenever there is one and the tile is not being arranged.
     *
     * Re-derived on every flip rather than only when the mark is set, or turning a tile
     * over left the corner as the last refresh had it until the next one came round.
     */
    private fun applyWidgetGlyphVisibility() {
        // Nor on a tile standing down to its icon: the mark in the corner and the mark in
        // the middle would be the same mark twice. See [showsLive].
        widgetGlyph.visibility =
            if (widgetGlyph.drawable != null && !isEditMode && showsLive()) VISIBLE else GONE
    }

    /**
     * Puts the turn-over hot zone on the tiles that turn over, and sizes it against them.
     *
     * A fixed 44dp corner is a quarter of a 2x2 tile and better than half of a 1x1 - so on
     * the small tile it was not a corner at all, it was the tile, and tapping the icon
     * turned it over instead of launching the app. The 1x1 is measured against itself
     * instead, in onMeasure; an app tile that size has no corner at all, because it cannot
     * show a notification face to turn to.
     */
    private fun applyFlipCornerSize() {
        // The corner exists where it does something: a tile with a face to turn to. On
        // every other tile - a folder, the weather laid out whole, an app with nothing
        // waiting - it was a quarter of the tile spent on swallowing taps meant for the
        // program, and answering them with half a turn that went nowhere. See [canFlip].
        val earnsCorner = canFlip()
        flipCorner.visibility = if (isEditMode || !earnsCorner) GONE else VISIBLE
        // Going back means going back through something: a run of faces. A reverse is one
        // face behind one other, where forward and back are the same turn.
        val earnsBack = rotation.size > 1
        flipBackCorner.visibility = if (isEditMode || !earnsBack) GONE else VISIBLE
        // The 1x1's corner is measured against the tile rather than in dp - see onMeasure,
        // which is where the tile's own size is known.
        if (tile.size == TileSize.SMALL) return
        val edge = if (tile.size.isStrip) FLIP_CORNER_STRIP_DP else FLIP_CORNER_DP
        for (corner in listOf(flipCorner, flipBackCorner, alarmCorner)) {
            val params = corner.layoutParams as? LayoutParams ?: continue
            corner.layoutParams = params.apply {
                width = dp(edge)
                height = dp(edge)
            }
        }
    }

    /**
     * The corner mark is one size on every tile, and so is every other mark up there.
     *
     * Scaling it to the footprint put it at a different distance from the corner on each
     * size - an icon drawn with its own margin inside its canvas is inset by a share of
     * whatever box it is given, so a bigger box meant a bigger gap. One box, one inset,
     * and nothing between the artwork and the edge of it.
     */
    private fun applyWidgetGlyphSize() {
        applyCornerMark(widgetGlyph, widgetGlyphRatio)
    }

    /** How much of its canvas the alarm mark covers, and which one that was measured for. */
    private var alarmMarkRatio = 1f
    private var alarmMarkMeasuredFor = 0

    /**
     * Puts a mark at the foot of the tile, or takes it away.
     *
     * Null for nothing, which is the usual state: the mark is there to say that something
     * is set, so a tile carrying it always means it.
     */
    fun setAlarmMark(res: Int?) {
        if (res == null) {
            alarmMark.setImageDrawable(null)
            // Through the one place that decides, so the corner goes with it. Setting the
            // mark's own visibility here left a hot zone over a tile with no mark on it,
            // quietly sending a quarter of the taps meant for the app to Alarms.
            applyAlarmMarkVisibility()
            return
        }
        alarmMark.setImageResource(res)
        alarmMark.imageTintList =
            android.content.res.ColorStateList.valueOf(palette.onAccent())
        // Measured once per mark, as the corner one is: the answer only changes when the
        // artwork does, and this is asked on every live-tile tick.
        if (alarmMarkMeasuredFor != res) {
            alarmMarkMeasuredFor = res
            alarmMarkRatio = alarmMark.drawable
                ?.let { MonochromeIconProvider.measureContentRatio(it) } ?: 1f
        }
        applyAlarmMarkVisibility()
        applyCornerMark(alarmMark, alarmMarkRatio, SMALL_MARK_DP)
    }

    /**
     * Shows the mark where there is a corner to put it in.
     *
     * Everywhere but the 1x1, which has none to spare: its reading is set to the width of
     * the whole tile - a time fills it corner to corner - so a mark up there would be over
     * the digits rather than beside them. It used to stand down for the label as well,
     * when the two shared the foot of the tile; from the top corner they no longer meet.
     */
    private fun applyAlarmMarkVisibility() {
        alarmMark.visibility = when {
            alarmMark.drawable == null || isEditMode -> GONE
            tile.size == TileSize.SMALL -> GONE
            else -> VISIBLE
        }
        // Nothing to aim at while there is no mark, and a corner that swallowed taps for a
        // mark that is not there would be a quarter of the tile that stopped opening it.
        alarmCorner.visibility = alarmMark.visibility
    }

    /**
     * Sizes and places one corner mark so every corner mark matches every other.
     *
     * Two things have to come out the same, and a single box cannot give both. These icons
     * are drawn with their own margin inside their canvas, and each one's is different: a
     * launcher icon keeps an adaptive-icon safe zone and covers about two thirds of its
     * square, where the shell's own glyphs very nearly fill theirs. In one box, the first
     * reads small and the second large.
     *
     * So the box is scaled by how much of it the artwork actually covers - which makes the
     * *visible* mark the same size for all of them - and the margin is pulled back by half
     * of whatever that added, which leaves the visible mark in the same place as well. The
     * inflation is capped: an icon that is mostly empty would otherwise be given a box
     * bigger than the corner it sits in.
     */
    private fun applyCornerMark(view: View, contentRatio: Float, markDp: Int = CORNER_MARK_DP) {
        val target = dp(markDp)
        val box = (target / contentRatio.coerceIn(MIN_CORNER_RATIO, 1f)).toInt()
        val inset = dp(CORNER_INSET_DP) - (box - target) / 2
        val params = view.layoutParams as? LayoutParams ?: return
        if (params.width == box && params.topMargin == inset) return
        view.layoutParams = params.apply {
            width = box
            height = box
            // Set on every side, and the mark's own gravity picks the two that place it.
            // A mark in the opposite corner is inset from that corner by exactly what a
            // mark in this one is inset from this, which is the whole point of the rule.
            topMargin = inset
            bottomMargin = inset
            marginStart = inset
            marginEnd = inset
        }
    }

    /** The reverse of a live widget, shown when it is turned over. Null makes it one-sided. */
    fun setWidgetBack(back: Reading?) {
        widgetBack = back
        if (showingBack && back != null) bindWidgetBack()
        // A reverse arriving is what earns a 1x1 its corner.
        applyFlipCornerSize()
    }

    private fun bindWidgetBack() {
        val back = widgetBack ?: return
        backTitle.text = back.number
        backTitle.visibility = VISIBLE
        setBackCaption(back.caption.orEmpty())
        backAside.text = back.aside.orEmpty()
        // The size of a reading is measured against the reading itself, so a new one is a
        // new size - "23:57" does not fit where "7" did.
        post { if (liveStyle == LiveStyle.READING) applyLiveTextSizes() }
        // Unlike a notification's body, a widget's second line is a caption for the number
        // above it - "AQI", "Moderate" - so it is allowed on the 1x1 too. Whether there is
        // one short enough to belong there is the host's call, not this one's.
        backText.visibility = if (back.caption.isNullOrEmpty()) GONE else VISIBLE
    }

    /** Whether turning this tile over would show anything different. */
    fun hasFlipContent(): Boolean = when {
        // A widget standing down to its icon has nothing on either face - the reading and
        // the run of stories are both being held rather than shown. See [showsLive].
        tile.kind.isLiveWidget && !showsLive() -> false
        rotation.isNotEmpty() -> rotation.size > 1
        tile.kind.isLiveWidget -> widgetBack != null
        else -> notifications.isNotEmpty()
    }

    /**
     * Turns the tile over, or back.
     *
     * Only a tile that has somewhere to turn. The rest are whole on their front, and what
     * a tap on one of those is for is the program behind it - see [canFlip].
     */
    fun toggleFlip() {
        // The cycle starts again from here. Turning a tile over by hand and having it turn
        // itself over a second later reads as the tile ignoring you - and on a rotation it
        // skips the story you just asked for.
        restartFlipClock()
        if (rotation.size > 1 && !isEditMode) {
            advanceRotation()
            return
        }
        // A tile with nothing held back does not turn: a folder opens, the weather opens,
        // and an app with nothing waiting launches. It has no corner to be tapped on
        // either, so this is the swipe, and the tiles this gesture is for are the ones
        // that have somewhere to go. See [canFlip].
        if (!canFlip()) return

        // A tile with several notifications is turned *through* them rather than merely
        // over: asking a tile to turn over is asking to read what is waiting on it, and
        // sending it back to its icon after the first of four means the other three can
        // only be reached by waiting for the tile to get round to them itself.
        //
        // The queue is stepped at the halfway point of the turn, where the face being read
        // is edge-on - the same way the tile steps it on its own clock.
        val queued = !tile.kind.isLiveWidget && notifications.size > 1
        if (queued && showingBack) {
            if (notificationIndex < notifications.lastIndex) {
                pendingNotification = notificationIndex + 1
                flipTo(true)
                return
            }
            // The last one has been read. Back to the icon, and back to the top of the
            // queue, so the next turn starts at the newest rather than the oldest.
            pendingNotification = 0
        }
        flipTo(!showingBack)
    }

    /**
     * A half turn that falls back, for a run of faces that has come down to one.
     *
     * Deliberately not a full rotation: 360 degrees looks like the tile flipped twice,
     * which reads as a stutter rather than as "there is nothing more here". Only ever
     * reached by a tile that was turning through stories and has been left with a single
     * one; a tile that never turns has no corner to ask this of - see [canFlip].
     */
    private fun spinInPlace() {
        flipAnimator?.cancel()
        animate().cancel()
        hingeOnMiddle()
        rotationX = 0f
        animate()
            .rotationX(NUDGE_DEGREES)
            .setDuration(FLIP_MS / 2)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                animate()
                    .rotationX(0f)
                    .setDuration(FLIP_MS / 2)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Puts the tile's axis of rotation back through its middle.
     *
     * A view's pivot follows its size until something sets one, and then it is that number
     * for good. The entrance hinges every tile on its left edge and sets a pivot to do it -
     * including the vertical one, at half of whatever height the tile had at the time. On a
     * cold start that is the height a tile has before it has been measured, which is none,
     * so every tile on the wall was left hinged on its top edge and turned over about it.
     *
     * That it came right after leaving the launcher and coming back was the same code
     * running a second time: the wall is laid out by then, so the entrance set the pivot
     * from a real height. Set here instead, where the turn is, so a turn is about the
     * middle whatever else has been done to the tile.
     */
    private fun hingeOnMiddle() {
        pivotX = width / 2f
        pivotY = height / 2f
    }

    /**
     * Half a turn, swap the faces, half a turn back.
     *
     * Split so the content changes while the tile is edge-on and the swap is invisible -
     * the trick WP8.1's own live tiles used.
     */
    private fun flipTo(showBack: Boolean) {
        flipAnimator?.cancel()
        animate().cancel()
        hingeOnMiddle()
        var swapped = false
        flipAnimator = android.animation.ValueAnimator.ofFloat(0f, 180f).apply {
            duration = FLIP_MS
            // Slow at both ends and quickest through the middle, which is where the tile
            // is edge-on. The turn takes exactly as long as it did; what changes is where
            // that time is spent - lingering on the faces rather than on the gap between
            // them.
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val turned = animation.animatedValue as Float
                // 0 through 90 on the way out, then -90 back up to 0 - the same two
                // quarter turns as before, driven as one movement.
                rotationX = if (turned <= 90f) turned else turned - 180f
                if (!swapped && turned >= 90f) {
                    swapped = true
                    applyFlipSwap(showBack)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Whether it finished or was cancelled: a tile left standing on its
                    // edge is an invisible tile.
                    if (!swapped) applyFlipSwap(showBack)
                    rotationX = 0f
                    flipAnimator = null
                }
            })
            start()
        }
    }

    /**
     * Swaps everything the turn is turning to, at the moment the tile is edge-on.
     *
     * One animator drives the whole turn rather than two chained together. The old pair
     * ended the first, ran this, and started the second - and a new animator does not
     * produce a frame until the next one, so the tile sat invisible at ninety degrees for
     * however long that took, plus any layout this work triggered. That pause was the
     * black gap in the middle of every flip; the geometry was never the problem.
     */
    private fun applyFlipSwap(showBack: Boolean) {
        showingBack = showBack
        // The next notification arrives with the turn, never under the eyes of someone
        // reading the last one.
        pendingNotification?.let { next ->
            pendingNotification = null
            notificationIndex = next
            bindNotification(next)
        }
        rotation.getOrNull(rotationIndex)?.let { bindBackdrop(it) }
        if (showBack && tile.kind.isLiveWidget) bindWidgetBack()
        frontFace.visibility = if (showBack) GONE else VISIBLE
        backFace.visibility = if (showBack) VISIBLE else GONE
        applyNotificationMark()
        applyWidgetGlyph()
        applyLabelVisibility()
        updateNotificationDot()
    }

    /**
     * Sets a live widget's face: the reading, over what it is of.
     *
     * A widget has no icon face - the reading *is* the tile - so the centre glyph steps
     * aside for it. What the number measures is said by the corner mark instead.
     */
    /**
     * Hands the widget a run of faces to turn through.
     *
     * Passing one leaves the tile still; passing none clears the rotation and returns it
     * to the ordinary front-and-reverse behaviour.
     */
    /**
     * One face of a rotation: what it says, the picture behind it, and the mark in its
     * corner.
     *
     * The mark belongs to the face rather than to the tile once there are more than two of
     * them: a weather tile turning through now, today and tomorrow is showing three
     * different conditions, and a front-and-reverse pair has nowhere to put the third.
     */
    data class LiveFace(
        val title: String,
        val detail: String?,
        val image: String = "",
        val glyph: Int? = null,
        /**
         * Whether the accent is laid over this face's picture.
         *
         * True for a story, whose headline has to stay readable over a photograph the
         * tile did not choose. False for the user's own photographs, which carry no words
         * and are the whole point of the tile - a wash over those is a tinted snapshot of
         * something they took, which is not what they took.
         */
        val washed: Boolean = true,
        /**
         * Whether [image] is a clip rather than a still.
         *
         * A clip plays where its first frame would otherwise sit - silently, and only for
         * as long as this face is up. See [playVideo].
         */
        val motion: Boolean = false,

        /** What stands beside the reading, if anything. See [Reading.aside]. */
        val aside: String? = null
    )

    fun setLiveWidgetRotation(faces: List<LiveFace>, style: LiveStyle) {
        val same = faces == rotation
        rotation = faces
        liveStyle = style
        // A run of faces takes the place of a reading, and on a tile too small to show
        // either it still has to take the icon's side of the question - see [showsLive].
        if (faces.isNotEmpty()) liveReading = null
        applyLiveFace()
        // Both of those decide whether the tile carries a name - a run of stories is
        // somebody else's words and says whose, a reading is its own caption - so the
        // question is put again here, empty run included.
        applyLabelVisibility()
        // Asked before the empty run is returned on, because both corners are the run's:
        // a tile whose stories have gone is a tile that should be nothing but a tap again.
        applyFlipCornerSize()
        if (faces.isEmpty()) return
        if (!same) rotationIndex = rotationIndex.coerceIn(0, faces.size - 1)
        // Re-bind whichever face is up, so refreshed content lands without waiting for the
        // next turn - and without turning the tile under someone who is reading it.
        bindRotationFace(showingBack)
        applyLiveTextSizes()
    }

    /** Puts the current story on the face that is about to be shown, picture and all. */
    private fun bindRotationFace(onBack: Boolean) {
        val face = rotation.getOrNull(rotationIndex) ?: return
        bindBackdrop(face)
        bindRotationText(onBack)
    }

    /**
     * Puts the current story's words on one face, and nothing else.
     *
     * Split from the picture because the two change at different moments: the words are
     * on a face the user cannot see and can be swapped whenever, where the picture is
     * behind the whole tile and has to wait for the tile to be edge-on.
     */
    private fun bindRotationText(onBack: Boolean) {
        val face = rotation.getOrNull(rotationIndex) ?: return
        val (title, detail) = face.title to face.detail
        if (onBack) {
            backTitle.text = title
            backTitle.visibility = VISIBLE
            setBackCaption(detail.orEmpty())
            backText.visibility = if (detail.isNullOrEmpty()) GONE else VISIBLE
            backAside.text = face.aside.orEmpty()
        } else {
            applyLiveFace()
            liveHeadline.text = title
            setLiveCaption(detail.orEmpty())
            liveDetail.visibility = if (detail.isNullOrEmpty()) GONE else VISIBLE
            liveAside.text = face.aside.orEmpty()
        }
        applyLiveTextSizes()
        // A story's first face goes up after the label has been settled, so this path has
        // to ask for itself - see [invalidateContentScrim].
        invalidateContentScrim()
    }

    /**
     * Asks for the current face's picture, and drops it if the tile has moved on.
     *
     * Cleared first: an arriving story with no picture of its own would otherwise keep the
     * last one's, and a photograph under the wrong headline is worse than no photograph.
     */
    private fun bindBackdrop(face: LiveFace) {
        applyWidgetGlyph()
        if (faceBackdrop != null) {
            faceBackdrop = null
            invalidate()
        }
        // The still is still asked for below, clip or not: it is the frame the tile shows
        // while the clip opens, and what it keeps if the clip will not play at all.
        if (face.motion && face.image.isNotBlank() && showsBackdrop && showsLive()) {
            playVideo(face.image)
        } else stopVideo()
        val loader = backdropLoader ?: return
        // Nothing is fetched for a tile that has been told not to show it. The whole cost
        // of a picture - the request, the decode, the bitmap held for as long as the face
        // is up - belongs to the drawing, so switching it off has to switch that off too
        // rather than quietly go on paying for a picture nobody can see.
        // Nor for a tile standing down to its icon, which has no face to put one behind.
        if (face.image.isBlank() || !showsBackdrop || !showsLive()) return
        val wanted = face.image
        loader(wanted) { bitmap ->
            if (bitmap == null) return@loader
            if (rotation.getOrNull(rotationIndex)?.image != wanted) return@loader
            faceBackdrop = bitmap
            invalidate()
        }
    }

    /**
     * The surface a clip plays on, made on first use.
     *
     * Below every face the tile draws but above its own fill and its still, which is what
     * the clip replaces the moment the first frame arrives.
     */
    private fun requireVideoView(): android.view.TextureView =
        videoView ?: android.view.TextureView(context).also { view ->
            videoView = view
            // Transparent until there are frames, so the still shows through while the
            // clip is opening rather than the tile going black for a moment.
            view.isOpaque = false
            view.visibility = GONE
            // Clipped to its own edge, because the crop in [applyVideoTransform]
            // deliberately overruns it: the picture is scaled up until it covers the tile,
            // and whatever hangs over the short side has to be cut off somewhere. Nothing
            // above can do the cutting - the tile stopped clipping its children so an edit
            // handle could hang off a corner, and the grid holding the tile did the same -
            // so the surface carries its own clip. Without it a portrait clip on a square
            // tile painted itself across the tiles beside it, worst inside an opened
            // folder, where a tile has neighbours on every side of it.
            view.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRect(0, 0, v.width, v.height)
                }
            }
            view.clipToOutline = true
            addView(view, 0, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            view.surfaceTextureListener =
                object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: android.graphics.SurfaceTexture, w: Int, h: Int
                    ) {
                        videoSurface = android.view.Surface(texture)
                        // The clip may have been asked for before there was anywhere to
                        // put it - the surface arrives a frame or two after the view does.
                        videoUri?.let { openPlayer(it) }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: android.graphics.SurfaceTexture, w: Int, h: Int
                    ) {
                        videoPlayer?.let { applyVideoTransform(it.videoWidth, it.videoHeight) }
                    }

                    override fun onSurfaceTextureDestroyed(
                        texture: android.graphics.SurfaceTexture
                    ): Boolean {
                        stopVideo()
                        videoSurface?.release()
                        videoSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(
                        texture: android.graphics.SurfaceTexture
                    ) = Unit
                }
        }

    /** Puts a clip up, or leaves the one already playing alone if it is the same one. */
    private fun playVideo(uri: String) {
        if (videoUri == uri && videoPlayer != null) return
        stopVideo()
        videoUri = uri
        requireVideoView().visibility = VISIBLE
        // With no surface yet this is all there is to do; the listener finishes the job.
        if (videoSurface != null) openPlayer(uri)
    }

    private fun openPlayer(uri: String) {
        val surface = videoSurface ?: return
        val player = android.media.MediaPlayer()
        videoPlayer = player
        try {
            player.setDataSource(context, android.net.Uri.parse(uri))
            player.setSurface(surface)
            // Short clips loop rather than freezing on their last frame; long ones are cut
            // off by the flip, which is the right length for a tile either way.
            player.isLooping = true
            // Silent, always. A tile is not somewhere a phone starts making noise from,
            // and muting rather than asking for audio focus is what keeps whatever the
            // user is actually listening to playing.
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                if (videoPlayer !== player) return@setOnPreparedListener
                applyVideoTransform(player.videoWidth, player.videoHeight)
                if (isShown && !isEditMode) player.start()
            }
            player.setOnErrorListener { _, what, extra ->
                android.util.Log.w(TAG, "Could not play a clip on a tile: $what/$extra")
                // The still is already up behind it, so there is nothing to put in its
                // place - the face simply stops moving.
                stopVideo()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not open a clip for a tile", e)
            stopVideo()
        }
    }

    private fun stopVideo() {
        videoUri = null
        videoPlayer?.let { player ->
            videoPlayer = null
            try {
                player.reset()
            } catch (e: IllegalStateException) {
                android.util.Log.w(TAG, "Clip would not reset", e)
            }
            player.release()
        }
        videoView?.visibility = GONE
    }

    /**
     * Centre-crops the clip, the same way the still behind it is cropped.
     *
     * A TextureView stretches its content to its own bounds, so the transform is the
     * correction for that: scale the stretched picture back out until the short side
     * covers the tile, then centre what does not fit.
     */
    private fun applyVideoTransform(videoW: Int, videoH: Int) {
        val view = videoView ?: return
        if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
        val scale = maxOf(width / videoW.toFloat(), height / videoH.toFloat())
        val drawW = videoW * scale
        val drawH = videoH * scale
        view.setTransform(android.graphics.Matrix().apply {
            setScale(drawW / width, drawH / height)
            postTranslate((width - drawW) / 2f, (height - drawH) / 2f)
        })
    }

    /**
     * Nothing plays off-screen or under a finger.
     *
     * Start is behind the app list as often as it is in front of it, and a clip decoding
     * away where nobody can see it is a tile spending battery on nothing.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val player = videoPlayer ?: return
        try {
            if (isVisible && !isEditMode) {
                if (!player.isPlaying) player.start()
            } else if (player.isPlaying) {
                player.pause()
            }
        } catch (e: IllegalStateException) {
            // Not prepared yet; the prepared listener will start it if it should be.
        }
    }

    /**
     * Moves back to the previous face in the rotation and turns the tile over to it.
     *
     * The clock is restarted as it is for a turn by hand: a tile that went back one story
     * and moved itself on again a second later has ignored what it was asked for.
     */
    fun retreatRotation() {
        restartFlipClock()
        if (rotation.size < 2) {
            spinInPlace()
            return
        }
        rotationIndex = (rotationIndex + rotation.size - 1) % rotation.size
        // Words now, picture at the halfway point - see [advanceRotation], which is this
        // in the other direction.
        bindRotationText(!showingBack)
        flipTo(!showingBack)
    }

    /** Moves to the next face in the rotation and turns the tile over to it. */
    fun advanceRotation() {
        if (rotation.size < 2) {
            spinInPlace()
            return
        }
        rotationIndex = (rotationIndex + 1) % rotation.size
        // Words now - they go onto the hidden face. The picture waits for the halfway
        // point, where the tile is edge-on and the change cannot be seen: swapping it here
        // meant the next story's photograph appeared under the last story's headline, a
        // clear half-second before the tile turned.
        bindRotationText(!showingBack)
        flipTo(!showingBack)
    }

    /**
     * What one of the shell's own widgets is showing.
     *
     * Three parts, laid out the way Windows Phone laid out a reading: the number itself in
     * the bottom corner, as large as the tile can take it, with its caption over it and
     * whatever belongs beside it - the day and the date, on the clock and the calendar -
     * standing at the foot of the other side. The number is what the tile is for, so it is
     * what the tile is mostly made of; everything else on it is there to say what the
     * number means.
     */
    data class Reading(
        val number: String,
        val caption: String? = null,
        val aside: String? = null
    )

    /**
     * The reading the widget was last handed, whether or not it is being shown.
     *
     * Kept for the same reason the run of faces is: the tile can be resized after it
     * arrives, and the smallest footprint shows no reading at all. See [showsLive].
     */
    private var liveReading: Reading? = null

    fun setLiveWidget(reading: Reading) {
        liveStyle = LiveStyle.READING
        rotation = emptyList()
        liveReading = reading
        applyLiveFace()
        liveHeadline.text = reading.number
        setLiveCaption(reading.caption.orEmpty())
        liveDetail.visibility = if (reading.caption.isNullOrEmpty()) GONE else VISIBLE
        liveAside.text = reading.aside.orEmpty()
        applyLabelVisibility()
        applyWidgetGlyphVisibility()
        applyLiveTextSizes()
    }

    /**
     * The captions the two faces were handed, before they were fitted to the tile.
     *
     * Kept so the fitting can be done again - a caption is cut to the width the tile
     * turned out to have, and a tile is filled before it is measured and resized after
     * that. See [fitCaption].
     */
    private var liveCaption: String = ""
    private var backCaption: String = ""

    private fun setLiveCaption(text: String) {
        liveCaption = text
        liveDetail.text = fitCaption(liveDetail, liveBox, text)
    }

    private fun setBackCaption(text: String) {
        backCaption = text
        backText.text = fitCaption(backText, backBox, text)
    }

    /** Re-cuts both captions to the width the tile has now. */
    private fun applyCaptionFit() {
        if (liveCaption.isNotEmpty()) liveDetail.text = fitCaption(liveDetail, liveBox, liveCaption)
        if (backCaption.isNotEmpty()) backText.text = fitCaption(backText, backBox, backCaption)
    }

    /**
     * Keeps a widget's caption to one line of type per line it was written in.
     *
     * A widget's caption is written as lines rather than as prose - the appointment and
     * then when it is, the weekday and then the date - and the second of those is the half
     * that says something the tile is not already showing. Left to wrap, a long name takes
     * both lines of the caption and the line that was meant to carry the time is either
     * pushed off the tile or dropped outright, so what is left is an appointment with no
     * hour on it.
     *
     * So each line is cut to the tile instead, and every one of them keeps its own line.
     * Only where the caption *is* lines: a notification's body is prose, and prose is meant
     * to wrap - see [bindNotification], which comes through here with the tile's own kind
     * to say so.
     */
    private fun fitCaption(
        view: TextView,
        box: android.widget.LinearLayout,
        text: String
    ): CharSequence {
        if (!tile.kind.isLiveWidget || '\n' !in text) return text
        // The caption is as wide as the face it sits on, less what the column is inset by.
        // Taken from the tile rather than from the view, which is a layout behind whenever
        // the tile has just been resized - and being a layout behind is exactly the case
        // this exists to survive.
        val room = (width - box.paddingLeft - box.paddingRight).toFloat()
        if (room <= 0f) return text
        // The first of those lines is a name where the rest are not - see [namesItsHead] -
        // and it is cut against the paint it will be drawn in rather than the caption's.
        // Measured two points short of the size it is set at, a title runs off the tile.
        val head = if (namesItsHead()) headPaint(view) else null
        val lines = text.split('\n').mapIndexed { index, line ->
            android.text.TextUtils.ellipsize(
                line, if (index == 0 && head != null) head else view.paint, room,
                android.text.TextUtils.TruncateAt.END
            ).toString()
        }
        val fitted = lines.joinToString("\n")
        return if (head == null) fitted else headed(fitted, lines.first().length)
    }

    /**
     * Whether the head of this widget's caption is the name of something.
     *
     * The calendar alone. Every other widget heads its caption with a word for the number
     * underneath - a weekday, the name of an index - where the calendar heads it with an
     * appointment's own title and puts the hour under that: a name with a line about it,
     * which is the pair the media and weather tiles are set in. See [FACE_TITLE_SP], and
     * [captionHeadDp], which is the air the same line already asked for.
     */
    private fun namesItsHead(): Boolean = tile.kind == Tile.Kind.LIVE_CALENDAR

    /** The caption's own paint, at the size and weight a name is set in. */
    private fun headPaint(view: TextView): android.text.TextPaint =
        android.text.TextPaint(view.paint).apply {
            textSize = sp(FACE_TITLE_SP)
            segoe(FACE_TITLE_FONT)?.let { typeface = it }
        }

    /**
     * [text] with its first [head] characters set as a name.
     *
     * Spans rather than a view of its own: the caption arrives as one string, wraps as one
     * block and is re-cut as one thing every time the tile is resized - see [fitCaption] -
     * and splitting it would mean keeping two views in step to draw what is written as
     * one.
     */
    private fun headed(text: String, head: Int): CharSequence =
        android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.AbsoluteSizeSpan(sp(FACE_TITLE_SP).toInt()),
                0, head, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            segoe(FACE_TITLE_FONT)?.let {
                setSpan(
                    android.text.style.TypefaceSpan(it),
                    0, head, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

    private fun applyLiveTextSizes() {
        // No size ladder at all. Scaling the headline to the footprint is what made the
        // wall look hand-set: a square widget read at one size, the wide one under it at
        // another, and resizing a tile changed its typography as well as its shape. What a
        // bigger tile buys is room - more lines, and a caption that has somewhere to go -
        // not bigger type. The 1x1 sets the size, being the tile with the least room: a
        // size that does not fit there is not a size the wall can share.
        // A reading is set large and in the corner; everything else keeps the one size the
        // wall shares. See [applyReadingLayout].
        val reading = liveStyle == LiveStyle.READING
        applyReadingLayout(reading)
        // One line, unless the face is telling a story - and how many lines a story gets
        // is settled at the foot of this method, against the tile rather than here. See
        // [fitStory].
        liveHeadline.maxLines = 1
        // A reading is measured against the floor of the tile and the font's own padding
        // would be measured along with it - see [sizeAsNumber] - so it comes off. Words
        // keep it, and what it amounts to is the air between a title and the line under
        // it. The notification face has been leaving exactly that much between the message
        // and who sent it, where a story had it stripped along with the reading's: which
        // was the whole of why a News tile and a message tile standing side by side were
        // set to two different rhythms.
        //
        // Before the sizing below, which measures against it.
        liveHeadline.includeFontPadding = !reading
        backTitle.includeFontPadding = !reading
        if (reading) {
            sizeAsNumber(liveHeadline)
            sizeAsNumber(backTitle)
        } else {
            liveHeadline.textSize = LIVE_TITLE_SP
            backTitle.textSize = LIVE_TITLE_SP
        }
        // The reverse of a widget is the same kind of thing as its front - the temperature,
        // the index, the next story - so it is typeset the same way rather than as
        // notification body text, which set the number it exists to show at the size of a
        // message preview.
        backTitle.maxLines = 1
        applyWidgetGlyphSize()
        // One caption size for every widget on the screen, the 1x1 included. Shrinking it
        // there made a row of widgets look like it had been set by three different people:
        // the 1x1's caption sat visibly smaller than the identical caption on the strip
        // beside it, and there was room for the real size all along.
        backText.textSize = LIVE_CAPTION_SP
        liveDetail.textSize = LIVE_CAPTION_SP
        // What stands beside a reading is part of it - "sun 30" is one date - so it is set
        // to be read with the number rather than as a caption for it.
        sizeAside(liveHeadline, liveAside)
        sizeAside(backTitle, backAside)
        // Shown whenever there is one, the 1x1 included - a widget's second line is a
        // caption for the reading above it, not prose, and "AQI" under an index is the
        // whole reason the number means anything. Whether a given widget has one short
        // enough for the smallest tile is the host's call, and it makes it per size.
        liveDetail.visibility = if (liveDetail.text.isNotEmpty()) VISIBLE else GONE
        // Last, because where a line has to be cut depends on the size it is set in, and
        // that is what this method has just decided.
        applyCaptionFit()
        // And a story after even that. It is the one face whose lines are counted against
        // the tile rather than declared, and the counting is done in the sizes everything
        // above has just settled - the source's included, that being the line it decides
        // the fate of. Both faces: a widget's stories alternate across the pair of them.
        if (liveStyle == LiveStyle.STORY) {
            val most = storyLines()
            fitFace(liveBox, liveHeadline, liveDetail, most)
            fitFace(backBox, backTitle, backText, most)
        }
    }

    /**
     * Lays a widget's face out as a reading, or as words.
     *
     * A reading is a number with a caption: the number sits in the bottom corner at the
     * largest size the tile can take, its caption goes *above* it - a caption under a
     * number reads as a second, smaller number - and anything standing beside it is at the
     * foot of the other side. A face of words is what it always was: the title first, the
     * rest under it, up the middle of the tile.
     *
     * The reverse follows the front, but only for the shell's own widgets. The same two
     * views carry an app's notifications, and a message preview is words however large the
     * tile showing it.
     */
    private fun applyReadingLayout(reading: Boolean) {
        // Where the words sit on the face. Only a reading is anchored - to the floor of
        // the tile, under its caption, which is where a number belongs. Everything else is
        // words, and words sit up the middle of the tile: a story and a notification are
        // both a title with something under it, and one of them being live does not change
        // where it wants to be read.
        val widget = tile.kind.isLiveWidget
        val anchor = if (widget && reading) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        arrangeFace(liveBox, liveRow, liveHeadline, liveDetail, liveAside, reading, anchor)
        arrangeFace(
            backBox, backRow, backTitle, backText, backAside,
            reading && widget, anchor
        )
    }

    /**
     * The air over a caption heading a tile.
     *
     * The calendar takes two hairs more than the rest of the wall. What heads that tile is
     * an appointment's own title - somebody's writing, and often two lines of it - where
     * every other widget heads itself with a word that names the number under it. A line
     * of writing wants the room a label does not, and set at the label's height it reads
     * as having been pushed up against the border.
     */
    private fun captionHeadDp(): Int =
        if (tile.kind == Tile.Kind.LIVE_CALENDAR) CAPTION_HEAD_DP + CALENDAR_HEAD_EXTRA_DP
        else CAPTION_HEAD_DP

    /**
     * What the calendar's reading takes on top of the size the wall shares.
     *
     * The shared size is worked out for the longest reading the shell sets - a time, four
     * digits and a colon - so a date, which is one or two digits with a weekday beside it,
     * is handed room it never uses. Two points back into the number and the word, and the
     * tile reads as the date it is for. See [sizeAsNumber] and [sizeAside], both of which
     * still fit what they are given to the tile it is on.
     */
    private fun readingExtraSp(): Float =
        if (tile.kind == Tile.Kind.LIVE_CALENDAR) CALENDAR_READING_EXTRA_SP else 0f

    private fun arrangeFace(
        box: android.widget.LinearLayout,
        row: android.widget.LinearLayout,
        number: TextView,
        caption: TextView,
        aside: TextView,
        reading: Boolean,
        anchor: Int
    ) {
        // A reading takes the whole face: its caption heads the tile and the number sits
        // on the floor of it, with the gap between them held open by a spacer that takes
        // whatever height is going. Words are a title with the rest under it, up the
        // middle of the tile, which is a column that only wants the height it uses.
        val wanted = if (reading) listOf(caption, spacerIn(box), row) else listOf(row, caption)
        val ordered = (0 until box.childCount).map { box.getChildAt(it) } == wanted
        if (!ordered) {
            box.removeAllViews()
            for (view in wanted) {
                val weight = if (view !is TextView && view !== row) 1f else 0f
                box.addView(view, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    if (weight > 0f) 0 else android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight))
            }
        }
        // A reading is not always a number - the clock's reverse is a weekday - and an
        // empty one still holds a line of its own height open under everything else.
        number.visibility = if (number.text.isNullOrEmpty()) GONE else VISIBLE
        val edge = if (reading) Gravity.END else Gravity.START
        // The row holds only the reading when there is nothing beside it, so it is the row
        // that has to take the side a story is set from.
        row.gravity = edge or Gravity.BOTTOM
        number.gravity = edge
        // The caption reads from the left wherever it sits: it is a phrase, and a phrase
        // set against the right edge is one the eye has to find the start of. Off the top
        // edge by a hair when it heads the tile: type set hard against a border reads as
        // having been cut off by it.
        caption.gravity = Gravity.START
        caption.setPadding(0, if (reading) dp(captionHeadDp()) else 0, 0, 0)
        // Set against the number rather than against the far edge of the tile: the two
        // belong to each other - the day beside the date, the descriptor beside the index -
        // and a word marooned in the opposite corner reads as a caption for the tile.
        aside.gravity = Gravity.END
        // The number is set in the weight it is meant to be read in; everything else on a
        // widget is the same one line of type it has always been.
        number.typeface = segoe(if (reading) NUMBER_FONT else TITLE_FONT)
        aside.visibility = if (reading && aside.text.isNotEmpty()) VISIBLE else GONE
        // A reading is the whole face - head and floor both - where words take the height
        // they use and stand where [anchor] puts them.
        (box.layoutParams as? LayoutParams)?.let { params ->
            val height = if (reading) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            if (params.gravity != anchor || params.height != height) {
                params.gravity = anchor
                params.height = height
                box.layoutParams = params
            }
        }
    }

    /** The give between a caption at the head of a face and the reading at its foot. */
    private fun spacerIn(box: android.widget.LinearLayout): View =
        if (box === liveBox) liveSpacer else backSpacer

    /**
     * Sets a reading to the largest size its tile can hold.
     *
     * Worked out rather than auto-sized. Android's auto-sizer fits text to the box the view
     * already has, and a view that is as tall as its own text has no room in it to grow
     * into: it will shrink a number that overflows and never enlarge one that does not.
     *
     * Worked out from one **cell** and from [NUMBER_REFERENCE] - the widest reading the
     * shell ever sets - rather than from the tile and the reading in hand. So the size is
     * the size at which a time fits the smallest tile there is, and every widget on the
     * wall is set in it, whatever it is showing and whatever it is sitting on.
     *
     * Both halves of that are the point. Sized to their own text, "30" came out half again
     * as large as "23:57" beside it; sized to their own tile, the same reading was one size
     * on a 1x1 and another on the strip under it. Either way a row of widgets read as
     * several designs rather than one wall - and the tile with the least room, which is the
     * one a time will not fit in, is the one that has to set the size for the rest.
     */
    private fun sizeAsNumber(view: TextView) {
        val scale = resources.displayMetrics.scaledDensity
        val cell = cellSize()
        if (cell <= 0) return
        val paint = android.text.TextPaint(view.paint)

        // One cell, not the tile: [NUMBER_HEIGHT_SHARE] of a cell's height, brought down
        // until the widest reading the shell sets fits across one - which is what makes
        // the size the same everywhere and makes a time fit the smallest tile there is.
        var size = minOf(LIVE_NUMBER_SP, cell * NUMBER_HEIGHT_SHARE / scale)
        val cellRoom = (cell - liveBox.paddingLeft - liveBox.paddingRight).toFloat()
        if (cellRoom > 0f) {
            paint.textSize = size * scale
            val wide = paint.measureText(NUMBER_REFERENCE)
            if (wide > cellRoom) size *= cellRoom / wide
        }

        // The calendar's own couple of points, on top of the size the wall shares - see
        // [readingExtraSp]. Added after the reference fit rather than before it: that fit
        // is a ratio, and scaling a bumped size by it takes the bump straight back out.
        size += readingExtraSp()

        // And never cropped. A reverse that says a word rather than a number - the clock's
        // says the weekday - is longer than anything the shared size was worked out for,
        // and it has this tile's own width to be long in.
        val text = view.text?.toString().orEmpty()
        val room = numberWidth()
        if (room > 0f && text.isNotEmpty()) {
            paint.textSize = size * scale
            val wide = paint.measureText(text)
            if (wide > room) size *= room / wide
        }

        view.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, size.coerceAtLeast(LIVE_NUMBER_MIN_SP))
    }

    /**
     * Sets what stands beside a reading, to the room the reading leaves it.
     *
     * One size for the whole wall like everything else, but a 1x1 with a date on it has
     * about thirty pixels to the left of the number, and "sun" set for a tile four times
     * the size does not go in them. So it is brought down where it has to be and left
     * alone everywhere else - which is nearly everywhere.
     *
     * Measured against the reading actually on the tile rather than the reference the
     * number was sized from: what is left over is what is left over.
     */
    private fun sizeAside(number: TextView, aside: TextView) {
        val scale = resources.displayMetrics.scaledDensity
        // The wall's size, and the calendar's couple of points over it: the weekday is
        // read with the date rather than beside it, so the two move together.
        val base = TileLabel.SIZE_SP + readingExtraSp()
        aside.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, base)
        if (aside.visibility != VISIBLE) return
        val text = aside.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val inner = (width - paddingLeft - paddingRight -
            liveBox.paddingLeft - liveBox.paddingRight).toFloat()
        if (inner <= 0f) return
        val taken = number.paint.measureText(number.text?.toString().orEmpty())
        val room = inner - taken - dp(ASIDE_GAP_DP)
        if (room <= 0f) return
        val paint = android.text.TextPaint(aside.paint)
        paint.textSize = base * scale
        // The widest of its lines: the clock's stands two deep.
        val wide = text.split('\n').maxOf { paint.measureText(it) }
        if (wide <= room) return
        aside.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            (base * room / wide).coerceAtLeast(TileLabel.MIN_SP))
    }

    /**
     * The side of one small cell, which is what a reading is sized from.
     *
     * Asked of the grid the tile is packed into. A tile that is not in one - and a folder's
     * band is a grid of its own, so that counts - works it out from its own footprint
     * instead, which is the same answer give or take the gaps between cells.
     */
    private fun cellSize(): Int {
        (parent as? TileGridLayout)?.cellSize?.takeIf { it > 0 }?.let { return it }
        if (width <= 0 || height <= 0) return 0
        return minOf(width / tile.size.cols, height / tile.size.rows)
    }

    /**
     * How much width the reading has, in pixels.
     *
     * Taken from the tile rather than from the column it sits in, which has not been
     * measured at the point the content arrives - and the split is fixed anyway, so it
     * follows from the same weights the row is built with.
     */
    private fun numberWidth(): Float {
        // The box's own padding as well as the tile's: the reading is set inside it, and
        // measuring against the tile handed the number eighteen pixels that were never
        // there - which is exactly how a time came to be cropped on a 1x1.
        val inner = (width - paddingLeft - paddingRight -
            liveBox.paddingLeft - liveBox.paddingRight).toFloat()
        if (inner <= 0f) return 0f
        val sharing = liveAside.visibility == VISIBLE || backAside.visibility == VISIBLE
        return if (sharing) inner * READING_WEIGHT / (READING_WEIGHT + ASIDE_WEIGHT) else inner
    }

    /** How many lines of headline the tile has room for under its own label. */
    private fun storyLines(): Int {
        val room = when {
            // A one-row tile, whatever its width: a title and two lines under it.
            tile.size.rows <= 1 -> 2
            // Twice and three times the height buys lines, not larger type - see
            // applyLiveTextSizes - so every row past the second is worth the same again.
            // Four cells across starts one lower, the wider measure needing fewer lines
            // to carry the same headline.
            else -> (if (tile.size.cols >= 4) 3 else 4) + STORY_LINES_PER_ROW * (tile.size.rows - 2)
        }
        // One of them goes to the app's name where the tile is deep enough to show it -
        // see applyLabelVisibility - since the name is drawn over the face rather than
        // under it, and a headline run to the foot of the tile would be read through it.
        return if (tile.size.rows >= 2) room - 1 else room
    }

    /**
     * Sets a face of words to the room the tile turned out to have.
     *
     * A story and a notification are the same face - a title over as many lines as the
     * tile allows, with one small line under it saying where it came from - and
     * [storyLines] and [BODY_LINES] are the ladders they are allowed by: what a footprint
     * is worth in lines, worked out from the shape of the tile and nothing else. The type
     * is not. It is set in points, so a wall packed into six columns of a small screen, or
     * a phone whose owner has asked for larger text, stands the same three lines in a tile
     * a third shorter than the one they were drawn for. The column is centred and free to
     * grow, so what did not fit was never dropped - it was laid over the app's own name
     * along the foot, which is exactly what the News tile did.
     *
     * So the ladder is a ceiling and the tile has the last word. The title is served
     * first, being what the tile is for, and the line under it keeps its place only where
     * there is one to spare under the title the story actually needs. A tile with room for
     * one of the two shows the story rather than the story's source.
     */
    private fun fitFace(
        box: android.widget.LinearLayout,
        title: TextView,
        source: TextView,
        most: Int
    ) {
        title.maxLines = most
        // Before the tile has been measured there is nothing to fit to, and the ladder is
        // the best answer there is. Asked again the moment there is one - see [postSettle].
        val room = height - paddingTop - paddingBottom - box.paddingTop - box.paddingBottom
        val across = width - paddingLeft - paddingRight - box.paddingLeft - box.paddingRight
        if (height <= 0 || room <= 0 || across <= 0) return
        // A line at a time rather than by division: what a line costs depends on whether
        // the font's own padding is kept, and the last of them does not cost what the ones
        // above it do.
        var lines = most
        var head = textHeight(title, across, lines)
        while (lines > 1 && head > room) {
            lines--
            head = textHeight(title, across, lines)
        }
        title.maxLines = lines
        // Against the headline in hand rather than against the lines it was allowed: a
        // story that wanted two of its three leaves the third to say where it came from.
        source.visibility =
            if (source.text.isNullOrEmpty() ||
                head + textHeight(source, across, source.maxLines) > room) GONE
            else VISIBLE
    }

    /**
     * How tall [view]'s own text stands, wrapped to [across] and cut off after [lines].
     *
     * Laid out rather than counted. What a line of type comes to is the face it is set in,
     * the leading the wall gives it and whether the font's padding is kept, and a story is
     * fitted at the margin where a few pixels either way is the difference between a line
     * going in and being laid over the tile's name.
     */
    private fun textHeight(view: TextView, across: Int, lines: Int): Int {
        val text = view.text
        if (text.isNullOrEmpty() || across <= 0 || lines <= 0) return 0
        return android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, android.text.TextPaint(view.paint), across)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(view.includeFontPadding)
            .setMaxLines(lines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
            .height
    }

    /**
     * Notifications this tile should surface, newest first.
     *
     * The tile keeps showing its icon and flips to each of these in turn, so a glance at
     * Start reads the same as a glance at the shade.
     */
    fun setNotifications(lines: List<Line>) {
        val changed = lines != notifications
        notifications = lines
        if (changed) {
            // Show the newest first rather than resuming an old rotation.
            notificationIndex = 0
            applyNotificationState()
        }
        if (lines.isNotEmpty()) bindNotification(notificationIndex)
    }

    /**
     * Where a tap on this tile should go, when what it is showing came from a notification.
     *
     * The line being read if the tile is turned over to one, and the newest if it is not:
     * a tile on its icon face with something waiting is a tile *about* that thing - the
     * mark in its corner says so - and it is the face it is on its way to showing anyway.
     *
     * Null when there is nothing waiting, or when what is waiting has nowhere of its own
     * to go, which is the tile's ordinary launch. See [Line.open].
     */
    fun notificationOpening(): (() -> Unit)? {
        if (notifications.isEmpty()) return null
        val index = if (showingBack) notificationIndex else 0
        return notifications.getOrNull(index)?.open
    }

    /**
     * Switches between the icon face and the notification face.
     *
     * Sets the faces to match the state the tile is in; the turning itself is [liveFlip]'s,
     * which alternates a tile with something waiting between its icon and what it has, the
     * way the live widgets alternate. The dot marks the tile through both of them - see
     * [dotHoldsCorner].
     */
    private fun applyNotificationState() {
        val playing = media != null
        // A live widget owns its own faces; the notification rules below are about app
        // tiles and would otherwise flip a widget back the moment anything refreshed. The
        // exception is a widget standing in as an ordinary tile - see [standingIn].
        if (tile.kind.isLiveWidget && !standingIn()) {
            mediaFace.visibility = GONE
            frontFace.visibility = if (showingBack) GONE else VISIBLE
            backFace.visibility = if (showingBack) VISIBLE else GONE
            notificationIcon.visibility = GONE
            notificationDot.visibility = GONE
            applyFlipCornerSize()
            return
        }

        // Two things force a tile back to the icon face - the notifications clearing, and
        // being resized down to small, which has no room for a title and body.
        if (notifications.isEmpty() || !tile.size.canShowText) showingBack = false
        // Media takes the tile outright while it lasts.
        if (playing) showingBack = false

        val mediaFaceShowing = playing && tile.size.canShowText
        mediaFace.visibility = if (mediaFaceShowing) VISIBLE else GONE
        frontFace.visibility = if (showingBack || mediaFaceShowing) GONE else VISIBLE

        backFace.visibility = if (showingBack) VISIBLE else GONE
        applyLabelVisibility()
        // The mark stays up on the media face as well: a title and an artist do not say
        // which app is playing them, and on a wall of tiles that is the question being
        // asked. It has the top of the corner and the transport has the bottom - see
        // applyMediaControlMetrics.
        applyNotificationMark()
        // The 1x1 never shows a media face, so this is only ever asking about the rest.
        mediaTime.visibility = if (mediaFaceShowing) VISIBLE else GONE
        applyMediaLayout()
        applyFlipCornerSize()
        updateNotificationDot()
        // Never leave a half-rotated tile behind if notifications arrive mid-transition.
        rotationX = 0f
    }

    /**
     * Decides whether the app's name shows along the bottom.
     *
     * Called from every path that changes what a tile is displaying - including the flip,
     * which used to set the faces directly and leave this untouched, so turning a tile over
     * to its notification silently dropped the name of the app the notification was from.
     */
    /**
     * Whether a live widget has anything of its own on the front.
     *
     * Handed *and* shown: a 1x1 holds its content without putting it up (see [showsLive]),
     * and a tile that is showing its icon is an icon tile in every way that matters here -
     * it carries a name, and a notification arriving on it is counted beside the mark
     * rather than turned to.
     */
    private fun hasLiveContent(): Boolean =
        showsLive() && (liveBox.visibility == VISIBLE || rotation.isNotEmpty())

    /**
     * Whether the tile has the room to be live at all.
     *
     * The 1x1 has not. A square that size is one glyph across: a headline set in it is
     * three words and an ellipsis, a photograph behind it is a smudge, a wall of faces is
     * four thumbnails the size of a fingernail, and a reading fills it corner to corner
     * with nothing left over to say what the number means. Windows Phone shipped its
     * smallest tiles as marks for that reason, and so does this one - the 1x1 is the
     * program's icon and nothing else, whichever program it belongs to.
     *
     * Only the showing of it waits. Everything a widget has been handed is kept - see
     * [liveReading], [rotation], [favourites] and [weatherReading] - so growing a tile
     * back out of the smallest footprint puts its face up again on the spot rather than
     * leaving an icon there until whatever feeds it next comes round.
     */
    private fun showsLive(): Boolean = tile.size.canShowText

    /**
     * Puts the widget's own face up, or hands the tile back to its icon.
     *
     * One place decides it, because three things can be on the front - a reading, a run of
     * faces, or neither - and the footprint can change under any of them. See [showsLive].
     */
    private fun applyLiveFace() {
        // The mosaic, the weather and the battery are the whole tile when they are up, so
        // nothing else on the front is showing whatever it was last handed.
        val taken = hasPeopleMosaic || hasWeatherFace || hasBatteryFace
        val live = showsLive() && !taken && (liveReading != null || rotation.isNotEmpty())
        liveBox.visibility = if (live) VISIBLE else GONE
        iconRow.visibility = if (live || taken || isEmptied) GONE else VISIBLE
        // The picture goes with the face it stood behind. A widget standing down to its
        // icon wears the tile's own colour rather than the last story's photograph, and a
        // clip left playing under an icon is paying for frames nobody can see.
        if (!live && (faceBackdrop != null || videoPlayer != null)) {
            faceBackdrop = null
            stopVideo()
            invalidate()
        }
    }

    /**
     * Whether a live widget is behaving as an ordinary app tile.
     *
     * A widget with nothing of its own to show and something waiting for the user is not,
     * at that moment, a widget: it is an icon with a count beside it, which is what every
     * other tile on the wall does with a notification and therefore what this one should
     * do too. The People tile is the case it was written for - a missed call is a thing to
     * answer, and a wall of faces is the wrong way to say so.
     */
    private fun standingIn(): Boolean =
        tile.kind.isLiveWidget && notifications.isNotEmpty() &&
            !hasLiveContent() && !hasPeopleMosaic && !hasWeatherFace && !hasBatteryFace

    private fun applyLabelVisibility() {
        val contentShowing = showingBack || mediaFace.visibility == VISIBLE
        label.visibility = when {
            // An opened folder is a marker for where it is, and nothing else.
            isEmptied -> GONE
            // A story is somebody else's words, so the tile says whose they are - but only
            // where there is a band spare under them, which is two rows and up. A reading
            // never needs it: what it is showing is a number this shell went and got, and
            // its caption already names it.
            tile.kind.isLiveWidget && liveStyle == LiveStyle.STORY ->
                if (tile.size.rows >= 2) VISIBLE else GONE
            // A wall of faces says who is on it, not what it is - so the tile says the
            // second part, exactly as Windows Phone's People tile did, and gives the same
            // corner over to a name while one of them has the whole tile. The 1x1 has room
            // for neither.
            hasPeopleMosaic -> if (tile.size.canShowText) VISIBLE else GONE
            // Except the weather, which has already said what it is. The face heads itself
            // with the name of a place - see WeatherFaceView - and a place over a
            // temperature is not a number the wall has to account for: "Amsterdam" and
            // "12°" say between them what the tile is without a third word under them
            // repeating it. Only while the face is actually up; a weather tile still
            // waiting for its first forecast is an icon like any other, and names itself
            // like one. The band it gives back goes to the reading - see
            // [applyContentFooter], which asks this question again to size it.
            hasWeatherFace -> GONE
            // A program's live tile says which program it is, exactly as its icon tile
            // would: the clock is Alarms', the pictures are Files', and a reading with no
            // name under it is a number the wall does not account for. Two cells and up,
            // and not on a one-row tile - a strip's reading already owns its height, and a
            // name under it would crush both. What is left of the tile is kept clear for
            // it; see [applyContentFooter].
            tile.kind.isProgramWidget ->
                if (tile.size.canShowText && !tile.size.isStrip) VISIBLE else GONE
            // Only once it has something to show. A widget whose content has not arrived
            // yet - the weather before the forecast is fetched, the news before the first
            // story - is a tile with an icon on it and nothing else, and it names itself
            // wherever an icon tile would: on a 1x1, nowhere. Reading the rule the other
            // way round is what left "Weather" sitting on the small tile until the first
            // flip went through and finally called this.
            tile.kind.isLiveWidget && hasLiveContent() -> GONE
            // A folder names itself at every size, the 1x1 included. Everywhere else the
            // smallest tile goes without because its icon already says what it is; a
            // folder's squares say what is *in* it, which is a different question from
            // which folder this is. The squares give up the bottom band for it.
            hasFolderPreview -> VISIBLE
            !tile.size.canShowText -> GONE
            // A strip has one row of height: a title, a subtitle and the app name under
            // them would crush all three, so the name yields while there is content - and
            // a folder's preview is content, two rows of it.
            tile.size.isStrip && (contentShowing || hasFolderPreview) -> GONE
            else -> VISIBLE
        }
        // The same question this method opens with - is the tile showing content, or is it
        // showing itself - is the one the scrim answers, so it is asked here too, along
        // with the room the content has to leave for what the label just decided.
        applyAlarmMarkVisibility()
        applyContentFooter()
        invalidateContentScrim()
    }

    /**
     * Keeps the foot of a live tile clear for its name.
     *
     * The name is drawn over the faces rather than beside them, so content that fills the
     * tile has to stop short of it or be printed through. Only what would be: a reading,
     * and the weather face. A picture and a wall of faces are meant to run under it
     * - the name sits on them over a scrim, exactly as a story's source does.
     */
    private fun applyContentFooter() {
        val band = labelBand()
        weatherFace?.let {
            if (it.paddingBottom != band) it.setPadding(0, 0, 0, band)
        }
        // The battery keeps its name where the weather gives one up - a cell with a number
        // beside it says how full without saying what of - so the face is held clear of
        // the same band. The band is nothing on the footprints that carry no name, which
        // is what puts the charge in the middle of a 1x1 rather than above a gap.
        batteryFace?.let {
            if (it.paddingBottom != band) it.setPadding(0, 0, 0, band)
        }
        if (!tile.kind.isLiveWidget) return
        val pad = dp(8)
        // The sides are set here too, so they are what the guard has to ask about as well.
        // A tile with no name under it asks for the bottom padding it already has - none -
        // and a guard that read only the band skipped the whole call and left the words
        // running to both edges of the tile. The 2x1 story tile is where it showed: a
        // headline is the one thing on a widget long enough to reach them.
        val side = pad + dp(2)
        if (liveBox.paddingLeft != side || liveBox.paddingRight != pad ||
            liveBox.paddingBottom != band) {
            liveBox.setPadding(side, 0, pad, band)
        }
        // And the reverse the same way. It is the same face turned over - a widget's
        // stories and its readings alternate across the pair of them - and it was left
        // running to the foot of the tile, which is why the source line on the back of a
        // story was printed through the app's own name.
        if (backBox.paddingLeft != side || backBox.paddingRight != pad ||
            backBox.paddingBottom != band) {
            backBox.setPadding(side, 0, pad, band)
        }
    }

    /**
     * The band along the foot of a tile that its name occupies.
     *
     * The name's own line, and the air under it. Measured rather than restated:
     * [NOTIFICATION_LABEL_GAP_DP] is what a line of the label comes to at the system's
     * ordinary text size, and it is in dp where the name it is holding room for is in
     * points. A phone asked for larger text therefore has a name taller than the band
     * being kept for it, and whatever sits above - a reading, a story's source - comes
     * down onto the word naming it. The constant stays as the floor, so nothing on a
     * phone at the ordinary size moves at all.
     */
    private fun labelBand(): Int {
        if (label.visibility != VISIBLE) return 0
        val floor = dp(8) + dp(NOTIFICATION_LABEL_GAP_DP)
        return maxOf(floor, label.lineHeight + label.paddingTop + label.paddingBottom)
    }

    /**
     * Says how much is waiting on a tile, or merely that something is.
     *
     * A number beside the icon wherever there is an icon to put one beside, which is what
     * WP8.1 did - it says how many rather than merely that there are some, and it costs
     * nothing that the corner was using. The dot is what is left for the cases with no
     * icon of their own: a live widget, whose face is a reading, and a folder, which marks
     * the app it came from on that app's own square instead - and for a tile turned over
     * onto the notification, where there is no icon left to count beside either.
     */
    private fun updateNotificationDot() {
        // A small tile that is playing shows play/pause in the corner instead of the dot -
        // the same spot, doing the more useful job.
        val badge = media != null && !tile.size.canShowText && !isEditMode
        mediaBadge.visibility = if (badge) VISIBLE else GONE

        val unread = notifications.isNotEmpty() && media == null && !isEditMode
        // The count belongs beside the glyph, so it is only ever asked for on the face
        // that has one.
        val counted = unread && !showingBack && countsEnabled && !hasFolderPreview &&
            (!tile.kind.isLiveWidget || standingIn())
        if (counted) countLabel.text = formatCount(notifications.size)
        countLabel.visibility = if (counted) VISIBLE else GONE

        applyFolderLabel()
        val show = unread && !counted && !hasFolderPreview && (!showingBack || dotHoldsCorner())
        notificationDot.visibility = if (show) VISIBLE else GONE
        // Only a tile that can actually turn over should take the tap; on a small one it
        // must fall through so the tile still launches, and on a tile already turned over
        // there is nothing left for the tap to do.
        notificationDot.isClickable = show && tile.size.canShowText && !showingBack
    }

    /**
     * Whether the dot keeps the corner while the notification itself is up.
     *
     * The corner is the app's mark on the notification face, so that a tile full of
     * message text still says which app it came from. Where the dot is what marks the
     * icon face, that swap costs more than it gives: the mark answers a question the
     * notification on the same tile has already answered, while the dot - the one thing
     * on the tile that says something is *unread* - disappears the moment the tile turns
     * over, so a wall mid-flip reads as quieter than it is. The dot stays put instead,
     * exactly where the icon face had it, and the mark stands down for it.
     *
     * Only where the dot is the mark. With numbers turned on the corner is empty on both
     * faces - the count sits beside the glyph - so there the mark keeps it, and a widget
     * or a folder never had an app mark of its own to give up.
     */
    private fun dotHoldsCorner(): Boolean =
        !countsEnabled && !tile.kind.isLiveWidget && !hasFolderPreview

    /**
     * Puts the app's mark in the corner, or leaves the corner to something else.
     *
     * One rule in one place, because the mark is set from every path that changes what a
     * tile is showing - the turn, a notification arriving, edit mode - and they used to
     * each carry their own version of it.
     */
    private fun applyNotificationMark() {
        val showing = !isEditMode && !isEmptied && !tile.kind.isLiveWidget &&
            (mediaFace.visibility == VISIBLE || (showingBack && !dotHoldsCorner()))
        notificationIcon.visibility = if (showing) VISIBLE else GONE
    }

    /**
     * Puts what is waiting inside a folder in front of the folder's name.
     *
     * A folder gives its corner to the preview and its squares to the apps, so neither the
     * dot nor the count has anywhere of its own to go - and something arriving inside a
     * folder is the one thing a folder tile cannot show, since the app it arrived for may
     * not be one of the squares currently up. The name is the only part of the tile that
     * belongs to the folder itself, so the mark goes on the name: a dot before it, or the
     * count in front of it where counts are turned on - "(3) Socials".
     */
    private fun applyFolderLabel() {
        val name = tile.label
        val marked = hasFolderPreview && notifications.isNotEmpty() && !isEditMode
        val counted = marked && countsEnabled
        label.text = if (counted) "(${formatCount(notifications.size)}) $name" else name
        val dot = if (marked && !counted) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(palette.onAccent())
                setSize(dp(FOLDER_DOT_DP), dp(FOLDER_DOT_DP))
            }
        } else {
            null
        }
        label.compoundDrawablePadding = dp(FOLDER_DOT_GAP_DP)
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }

    /** Two digits, and then it stops counting. So did Windows Phone. */
    private fun formatCount(count: Int): String =
        if (count > COUNT_MAX) "$COUNT_MAX+" else count.toString()

    /**
     * Sets the count so its digits stand exactly as tall as the icon beside them.
     *
     * Measured rather than assumed: how much of a font's size its digits actually occupy
     * is a property of the face, so the size is set once, the digits are measured at it,
     * and the size is scaled by however far off that came out.
     *
     * [room] is what is left of the tile once the icon and the gap have had theirs. Three
     * figures are three times as wide as one and the tile is the same size either way, so
     * a count that will not fit beside the icon is brought down until it does - the height
     * is what was asked for, not what the tile is obliged to give.
     */
    private fun applyCountSize(iconPx: Int, room: Int) {
        val text = countLabel.text.toString()
        val settled = countSizedFor == iconPx && countSizedText == text && countSizedRoom == room
        if (iconPx <= 0 || text.isEmpty() || settled) return
        countSizedFor = iconPx
        countSizedText = text
        countSizedRoom = room

        countLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, iconPx.toFloat())
        val bounds = Rect()
        countLabel.paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.height() <= 0) return
        val size = iconPx * iconPx.toFloat() / bounds.height()
        countLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)

        val measured = countLabel.paint.measureText(text)
        if (room > 0 && measured > room) {
            countLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * room / measured)
        }

        // Re-read at whatever size it ended up: the blanks scale with the type, and they
        // are what the gap and the centring are corrected by.
        countLabel.paint.getTextBounds(text, 0, text.length, bounds)
        countBearing = bounds.left
        countTrailing = (countLabel.paint.measureText(text) - bounds.right).toInt()
    }

    /**
     * Turns the tile over to its notification, from a tap on the dot.
     *
     * Split into two half-rotations so the faces swap while the tile is edge-on and the
     * change is invisible - the same trick WP8.1's own live tiles used.
     */
    fun showNotificationFace() {
        restartFlipClock()
        if (notifications.isEmpty() || showingBack || !tile.size.canShowText) return
        if (media != null) return
        flipTo(true)
    }

    private fun bindNotification(index: Int) {
        val line = notifications.getOrNull(index) ?: return
        // What arrived heads the tile, and who it came from is the small line under it -
        // the same way round as a story, whose headline heads the tile and whose source
        // goes underneath. The message is the thing being read and the sender is the label
        // on it, so the message takes the large line: a tile that led with the name set it
        // in the size the message should have had, and the message itself came out as the
        // caption for a person.
        //
        // A notification with nothing but a title - an app saying it has three of
        // something - puts that on the large line rather than leaving it empty and setting
        // the whole tile small.
        val said = line.text.ifEmpty { line.title }
        val from = if (line.text.isEmpty()) "" else line.title
        backTitle.text = said
        backTitle.visibility = if (said.isEmpty()) GONE else VISIBLE
        setBackCaption(from)
        backText.visibility = if (from.isEmpty()) GONE else VISIBLE
        fitNotification()
    }

    /**
     * The notification face, fitted to the tile the way a story is.
     *
     * The two are laid out alike and crowd alike: the three lines of message the ladder
     * allows, with the sender under them, is more type than a tile on a densely packed
     * wall has - and the sender is the line that gives way, exactly as a story's source
     * is. Only for app tiles; a widget standing in on this face is fitted along with the
     * rest of its typesetting. See [applyLiveTextSizes].
     */
    private fun fitNotification() {
        if (tile.kind.isLiveWidget) return
        fitFace(
            backBox, backTitle, backText,
            if (tile.size.isStrip) STRIP_BODY_LINES else BODY_LINES)
    }

    /** The app's mark for the notification face. Drawn untinted if it is a real icon. */
    fun setNotificationIcon(drawable: Drawable?, tint: Boolean) {
        notificationIcon.setImageDrawable(drawable)
        notificationIcon.imageTintList =
            if (tint) android.content.res.ColorStateList.valueOf(palette.onAccent()) else null
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
        label.setTextColor(p.onAccent())
        countLabel.setTextColor(p.onAccent())
        backText.setTextColor(p.onAccent())
        liveHeadline.setTextColor(p.onAccent())
        liveDetail.setTextColor(p.onAccent())
        liveAside.setTextColor(p.onAccent())
        backAside.setTextColor(p.onAccent())
        backTitle.setTextColor(p.onAccent())
        mediaTitle.setTextColor(p.onAccent())
        mediaArtist.setTextColor(p.onAccent())
        mediaTime.setTextColor(p.onAccent())
        val onAccent = android.content.res.ColorStateList.valueOf(p.onAccent())
        for (control in listOf(mediaPrevious, mediaPlayPause, mediaNext, mediaBadge)) {
            control.imageTintList = onAccent
        }
        if (widgetGlyph.drawable != null) widgetGlyph.imageTintList = onAccent
        if (alarmMark.drawable != null) alarmMark.imageTintList = onAccent
        // Drawn as an inset oval so the mark stays small while the view stays tappable.
        notificationDot.background = android.graphics.drawable.InsetDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(palette.onAccent())
            },
            dp((NOTIFICATION_DOT_TARGET_DP - NOTIFICATION_DOT_DP) / 2)
        )
        if (glyph.imageTintList != null) {
            glyph.imageTintList = android.content.res.ColorStateList.valueOf(p.onAccent())
        }
        folderPreview?.applyPalette(p)
        peopleMosaic?.applyPalette(p)
        weatherFace?.applyPalette(p)
        batteryFace?.applyPalette(p)
    }

    /**
     * Points this tile at the shared Start background.
     *
     * [src] is the crop of the bitmap to show, [dest] the full area that crop is stretched
     * across (normally the Start screen), and the offsets are where this tile sits inside
     * that area - which is what makes every tile line up into one continuous image.
     */
    override fun setStartBackground(bitmap: Bitmap?, src: Rect?, dest: Rect) {
        startBackground = bitmap
        backgroundSrc = src
        backgroundDest = dest
        invalidate()
    }

    override fun setBackgroundOffset(x: Float, y: Float) {
        if (x == backgroundOffsetX && y == backgroundOffsetY) return
        backgroundOffsetX = x
        backgroundOffsetY = y
        if (startBackground != null) invalidate()
    }

    /**
     * How strongly the accent is laid over whatever picture is on show.
     *
     * A cover and a story's photograph both carry text and both get the full wash; a
     * face that asked not to be washed is shown as it is. See [LiveFace.washed].
     */
    private val backdropWash: Float
        get() = when {
            media != null -> BACKDROP_TINT_ALPHA
            rotation.getOrNull(rotationIndex)?.washed == false -> 0f
            else -> BACKDROP_TINT_ALPHA
        }

    /**
     * Whether the tile is showing words of its own rather than its icon and its name.
     *
     * The label is not one of them: it is the app's name, set along the foot of the tile
     * where every tile carries one, and it reads over a photograph the way the glyph
     * does. What needs the ground under it darkened is content - a notification turned
     * face up, what is playing, a reading, a headline, the weather. A
     * mosaic of faces and a folder's squares are pictures, and cover the tile themselves.
     */
    private val showingOwnWords: Boolean
        get() = backFace.visibility == VISIBLE ||
            mediaFace.visibility == VISIBLE ||
            (frontFace.visibility == VISIBLE &&
                (liveBox.visibility == VISIBLE || hasWeatherFace || hasBatteryFace))

    /**
     * How much black goes over the photograph on this tile's face, 0 to 1.
     *
     * The look the user asked for wins where it is on, so a wall set to dim is one tone
     * rather than one tone with the talking tiles darker than the rest. Off, the wash is
     * the readability one and lands only where there are words to read. See [drawFace].
     */
    private val faceWash: Float
        get() = when {
            dimAllTiles -> dimAmount
            tileColorsHidden && showingOwnWords -> CONTENT_SCRIM_ALPHA
            else -> 0f
        }

    /**
     * Repaints the face after what the tile is showing has changed.
     *
     * The scrim in [drawFace] is decided from the faces that are up, and a child changing
     * visibility puts that child back on the render list, not the parent's own drawing -
     * so without this a tile turned onto its notification kept the ground it was already
     * drawn with. Only where the scrim is in play at all, which is a handful of tiles on
     * a wall of forty.
     */
    private fun invalidateContentScrim() {
        if (tileColorsHidden && customAccent == null && startBackground != null) invalidate()
    }

    /**
     * Paints the tile's own face, inside the tile's own bounds.
     *
     * The clip is this view's to apply now. The grid stopped clipping its children so an
     * edit handle could hang off a corner, and a tile's face is painted with
     * [Canvas.drawColor] and with a wallpaper positioned so only this tile's slice lands
     * on it - both of which fill whatever region they are given. Unclipped, every tile
     * painted the entire wall and the last one drawn became the screen.
     *
     * Only the face: the handles are children, drawn after this in dispatchDraw, by which
     * point the clip is gone again.
     */
    override fun onDraw(canvas: Canvas) {
        val clip = canvas.save()
        canvas.clipRect(0, 0, width, height)
        drawFace(canvas)
        canvas.restoreToCount(clip)
        super.onDraw(canvas)
    }

    private fun drawFace(canvas: Canvas) {
        val backdrop = backdropForFace
        if (backdrop != null && !backdrop.isRecycled) {
            // Centre-crop: the picture covers the tile whatever shape either of them is,
            // and what does not fit is cropped rather than squashed.
            val scale = maxOf(
                width / backdrop.width.toFloat(),
                height / backdrop.height.toFloat()
            )
            val drawW = backdrop.width * scale
            val drawH = backdrop.height * scale
            faceDest.set(
                ((width - drawW) / 2f).toInt(),
                ((height - drawH) / 2f).toInt(),
                ((width + drawW) / 2f).toInt(),
                ((height + drawH) / 2f).toInt()
            )
            canvas.drawBitmap(backdrop, null, faceDest, backgroundPaint)
            // The wash goes over every picture that carries words, a cover included. Text
            // on a tile is white, those pictures arrive from elsewhere, and the accent over
            // them is what keeps the wall one surface rather than a row of unrelated
            // photographs.
            val wash = backdropWash
            if (wash > 0f) {
                // Black rather than the accent while the colours are held back. A wash is
                // a tile colour laid over a photograph, so a news story or an album cover
                // was the one tile that came back as a block of accent the moment every
                // other tile turned into a window - which is the exact thing the switch
                // was thrown to be rid of. Black carries the headline just as well.
                val tint = if (tileColorsHidden) Color.BLACK else fill
                canvas.drawColor(
                    Color.argb(
                        (Color.alpha(tint) * wash).toInt(),
                        Color.red(tint),
                        Color.green(tint),
                        Color.blue(tint)
                    )
                )
            }
            return
        }

        // A tile the user painted is that colour and nothing else. The wallpaper shows
        // through the tiles that were left alone, and a painted one is a solid block among
        // them - which is what makes it stand out at all. Washed over the photo instead, it
        // was a tinted window like every other tile, only a different tint.
        val bmp = startBackground.takeIf { customAccent == null }
        if (bmp != null && !bmp.isRecycled) {
            val saved = canvas.save()
            // Shift the whole image so the slice behind this tile lands under it.
            canvas.translate(-backgroundOffsetX, -backgroundOffsetY)
            canvas.drawBitmap(bmp, backgroundSrc, backgroundDest, backgroundPaint)
            canvas.restoreToCount(saved)
            // The photo, as it is: no wash over a tile that was left to show it - unless
            // the tile has words on it. A sender and two lines of an email are white text
            // lying directly on a photograph, and whether they can be read at all is down
            // to what the photograph happens to be doing behind them. Everywhere else the
            // tile's own colour is what carries them; here it is a little black instead,
            // light enough that the picture is still the thing showing through.
            // Or over the lot of them, where the user has asked for that: the same wash,
            // asked for as a look rather than earned by what the tile is saying, and at
            // whatever strength they set it to. See [faceWash].
            val dim = faceWash
            if (dim > 0f) {
                canvas.drawColor(Color.argb((255 * dim).toInt(), 0, 0, 0))
            }
        } else {
            canvas.drawColor(fill)
        }
    }

    /** Re-reads size-dependent layout: label visibility, glyph scale, text padding. */
    fun applySize() {
        applyUnpinHandle()
        applyFlipCornerSize()
        applyFolderPreviewGrid()
        applyPeopleGrid()
        val pad = dp(8)
        label.setPadding(pad + dp(2), 0, pad, pad)
        backBox.setPadding(pad + dp(2), 0, pad, 0)
        // A one-row tile has the height for a title and two lines under it, and no more.
        // The column is centred and free to grow, so a third line was not dropped - it was
        // laid out past the bottom edge and cut through, leaving half a line of letters.
        // Capping it at what fits puts the ellipsis where the text actually stops.
        val body = if (tile.size.isStrip) STRIP_BODY_LINES else BODY_LINES
        if (tile.kind.isLiveWidget) {
            backText.maxLines = body
        } else {
            // The notification face leads with the message - see [bindNotification] - so
            // the lines it has are the message's, and the sender under it keeps one, the
            // way a story's source does.
            backTitle.maxLines = body
            backText.maxLines = 1
        }
        // Set whatever the tile is: a widget showing stories names the source of them.
        // A folder's name may carry a mark in front of it - see applyFolderLabel.
        applyFolderLabel()
        // Asked here as well as everywhere content changes, because this runs when the
        // tile is built. Without it a tile's name was whatever the field defaulted to
        // until something happened to it, which on a widget waiting for its first content
        // meant showing the app's name on a tile that should never carry one.
        applyLabelVisibility()
        if (tile.kind.isLiveWidget) {
            // Growing out of the smallest footprint - or shrinking into it - decides
            // whether the tile is live at all, and everything it was handed is still here
            // to put up or take down. See [showsLive].
            //
            // A tile shrunk into it comes back to its front first: a widget that stands
            // down to its icon has no reverse to be left on, and one caught face-up would
            // have shown the back of a story on a tile that is no longer telling one.
            if (!showsLive() && showingBack) {
                flipAnimator?.cancel()
                rotationX = 0f
                showingBack = false
            }
            if (weatherFace != null || weatherReading != null) setWeatherFace(weatherReading)
            // The battery face is up at every footprint, so what a resize changes for it
            // is only how much room it has - but the cell it sizes itself from has moved,
            // and it is the setter that hands that over.
            if (batteryFace != null || batteryReading != null) setBatteryFace(batteryReading)
            if (peopleMosaic != null || favourites.isNotEmpty() || otherPeople.isNotEmpty()) {
                setPeopleMosaic(favourites, otherPeople)
            }
            applyLiveFace()
            // Its side padding, and whatever the name at the foot has left it.
            applyContentFooter()
            applyLiveTextSizes()
        } else {
            // Room along the bottom for the app name, which is drawn over both faces -
            // except on a one-row tile, where the name gives way to the content instead
            // (see applyLabelVisibility). Holding the space anyway pushed a notification
            // up against the top of the tile to clear a label that was not there.
            val bottom =
                if (tile.size.isStrip) pad
                else maxOf(pad + dp(NOTIFICATION_LABEL_GAP_DP), labelBand())
            backBox.setPadding(pad + dp(2), pad, pad, bottom)
            // The lines set just above are the ladder, which is a ceiling; what the tile
            // can actually hold of them is measured.
            fitNotification()
        }
        // Resizing changes what the tile is capable of showing - growing out of small makes
        // it able to turn over, shrinking into small takes that away - so the notification
        // state is re-derived here rather than only when notifications change.
        applyNotificationState()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The glyph is sized from the tile box rather than its intrinsic bounds, so a
        // monochrome vector and a full-colour launcher icon occupy the same optical area.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val basis = minOf(w, h)
        // Divide by how much of its canvas the artwork covers, so every tile shows its
        // glyph at the same optical size no matter how the source was padded. Capped so a
        // heavily-padded glyph cannot scale up and outgrow the tile.
        val fraction = (GLYPH_FRACTION / glyphContentRatio).coerceAtMost(MAX_GLYPH_FRACTION)
        val target = (basis * fraction).toInt().coerceAtLeast(1)
        // A widget showing a reading has no glyph in the middle to size: its mark is the
        // corner one, which is sized against the tile in applyWidgetGlyphSize.
        //
        // Only while it is showing one. A widget standing in as an ordinary tile - and a
        // widget on the 1x1, where every one of them stands down to its mark (see
        // [showsLive]) - has the glyph in the middle as the whole of what it is showing,
        // and it wants sizing like anybody else's. Left out, the icon sat at whatever an
        // ImageView gives an unmeasured drawable, which is a fraction of what the wall
        // around it was wearing: the People tile was the first case, and the 1x1 widgets
        // are the rest of it.
        // The battery is the exception to the exception: its face is up at 1x1 as well, so
        // there is no mark in the middle to size there either. See [setBatteryFace].
        if (tile.kind.isLiveWidget && !standingIn() && (showsLive() || hasBatteryFace)) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // What the icon actually *looks* like, rather than the box it is given. The box is
        // inflated to correct for the padding the artwork was drawn with, so setting the
        // count against it made the digits taller than the icon reads and pushed them out
        // to the edge of the tile.
        val visible = (target * glyphContentRatio).toInt().coerceIn(1, target)
        val counting = countLabel.visibility == VISIBLE

        // Sized before the gap is worked out, because how far the digits have to be pulled
        // back depends on how much blank they turn out to be carrying.
        //
        // Set against the tile rather than against the icon beside it. Every glyph on the
        // wall is drawn at the same optical size already, but the *box* each one is given
        // is not - it is inflated by however much padding that artwork was drawn with - so
        // sizing the digits off it gave two identical tiles two different numbers. The
        // footprint is the one thing they genuinely share.
        val digits = (basis * COUNT_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
        if (counting) {
            applyCountSize(digits, w - target - dp(COUNT_EDGE_DP) * 2)
        }

        // What is wanted is a gap between the *marks*, and almost all of it is there before
        // any margin is added: the glyph box is inflated past its artwork (see the fraction
        // above) and the digits sit inside their own advance. Both are measured and taken
        // back off, which means the margin is normally negative - the number is pulled into
        // blank that already belongs to the pair rather than parked beyond it.
        //
        // Zero when there is nothing to count. A margin held for an absent number is what
        // shifted every glyph on the wall off centre, including the ones with nothing to say.
        val gap = if (counting) {
            (visible * COUNT_GAP_FRACTION - (target - visible) / 2f - countBearing).toInt()
        } else {
            0
        }
        // Guarded, because assigning layout params asks for another layout pass and this
        // runs on every one of them.
        (glyph.layoutParams as android.widget.LinearLayout.LayoutParams).let { params ->
            if (params.width != target || params.marginEnd != gap) {
                glyph.layoutParams = params.apply {
                    width = target
                    height = target
                    marginEnd = gap
                }
            }
        }

        // Stand the digits on the icon's own foot, rather than centring their box against
        // it. A text view's box is lopsided - all of the descent is below the digits and
        // none of it above - so centring the two boxes hangs the number below the mark it
        // belongs to, which is the "1" sitting lower than the envelope beside it.
        //
        // The lift is the correction for [visible] itself: it is the optical size the whole
        // shell scales glyphs by, taken from the artwork's longest side, so for the usual
        // mark - wider than it is tall - it reaches a little past the real foot of the icon.
        if (counting) {
            val metrics = countLabel.paint.fontMetrics
            val baselineFromCentre = (-metrics.ascent - metrics.descent) / 2f
            countLabel.translationY = visible / 2f - baselineFromCentre -
                digits * COUNT_LIFT_FRACTION - dp(COUNT_LIFT_DP)
        }

        // Centre what can be seen, not the boxes it is in.
        //
        // The row is centred by its own width, and its two ends are not equally blank: the
        // glyph box is inflated past its artwork on the left where the digits trail off
        // into a side bearing on the right. Centring the boxes therefore puts the icon and
        // its number visibly right of the middle. This takes the difference out again.
        val leftBlank = (target - visible) / 2f
        iconRow.translationX = if (counting) -(leftBlank - countTrailing) / 2f else 0f
        // The app's mark is placed by the same rule as the widget's - see applyCornerMark.
        // Smaller on the media face, which is the one face that has something else in the
        // corner with it - see [SMALL_MARK_DP].
        val markDp = if (mediaFace.visibility == VISIBLE) SMALL_MARK_DP else CORNER_MARK_DP
        applyCornerMark(notificationIcon, glyphContentRatio, markDp)

        // A quarter of the tile, in from the top and the right. A fixed 44dp corner is more
        // than half of a 1x1, which is why it had none at all; a quarter of whatever the
        // tile actually is holds on every screen and every grid, and is a corner rather
        // than the tile.
        if (tile.size == TileSize.SMALL) {
            for (corner in listOf(flipCorner, flipBackCorner)) {
                (corner.layoutParams as? LayoutParams)?.let { params ->
                    corner.layoutParams = params.apply {
                        width = (w * SMALL_CORNER_FRACTION).toInt().coerceAtLeast(1)
                        height = (h * SMALL_CORNER_FRACTION).toInt().coerceAtLeast(1)
                    }
                }
            }
        }

        // Level with the mark, and left of it.
        //
        // Measured against what the mark *looks* like rather than the box it is given: the
        // box is inflated to correct for the padding an icon was drawn with and pulled back
        // by the same amount, so whatever its size, the visible mark always occupies the
        // band from CORNER_INSET_DP to CORNER_INSET_DP + [markDp]. Matching the box
        // instead made the clock as tall as the inflation and sat it below the mark.
        //
        // [markDp] rather than the shared size: the clock is only ever up on the media
        // face, so it follows the mark that face actually has.
        val markSlot = dp(CORNER_INSET_DP) + dp(markDp) + dp(CLOCK_GAP_DP)
        val markBand = dp(markDp)
        // Clear of the mark's slot, and then a little further in: the digits are ranged
        // right, and sitting exactly on the slot's edge put them tight against the mark
        // above them rather than under it.
        val timeEnd = markSlot + dp(MEDIA_TIME_NUDGE_DP)
        (mediaTime.layoutParams as? LayoutParams)?.let { params ->
            if (params.marginEnd != timeEnd || params.height != markBand) {
                mediaTime.layoutParams = params.apply {
                    marginEnd = timeEnd
                    height = markBand
                }
            }
        }

        // Hard ceiling on the media text, rather than a margin the layout is free to
        // interpret: a track title is one long word as often as not, and left to its own
        // width it runs under whatever else the face is carrying.
        //
        // The mark and the elapsed time have the top corner on every size, and the first
        // line of the title is level with them on every size too - the text starts at the
        // top of the face, and the corner band starts eight points above that. On a strip
        // the transport is beside the text as well rather than under it.
        if (mediaFace.visibility == VISIBLE) {
            // A flat push off the right edge - see [MEDIA_CORNER_PUSH_DP]. Measuring the
            // corner to the point, the mark's band plus the widest reading the clock can
            // show, was right about where the corner ends and still took too much: it is
            // the title's first line that meets the clock, and holding the whole column
            // back for it cut titles that had the room.
            val push = dp(MEDIA_CORNER_PUSH_DP)
            val inner = w - mediaFace.paddingStart - mediaFace.paddingEnd
            // The transport only stands beside the text on a strip. Anywhere taller it is
            // along the foot of the tile with the text above it, and holding its width
            // back there would crop a title for a row it never shares.
            val reserve = if (tile.size.isStrip) maxOf(transportReservePx, push) else push
            // However tight the tile, the title keeps something to be read in - but never
            // at the cost of the push, which is the one thing this is here for.
            val cap = (inner - reserve)
                .coerceAtLeast(dp(MEDIA_TEXT_MIN_DP))
                .coerceAtMost((inner - push).coerceAtLeast(1))
            mediaTitle.maxWidth = cap
            mediaArtist.maxWidth = cap
        } else {
            mediaTitle.maxWidth = Int.MAX_VALUE
            mediaArtist.maxWidth = Int.MAX_VALUE
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ---------------------------------------------------------------- edit mode

    fun setEditMode(editing: Boolean) {
        isEditMode = editing
        val vis = if (editing) VISIBLE else GONE
        resizeHandle.visibility = vis
        unpinHandle.visibility = if (editing && unpinHandle.isEnabled) VISIBLE else GONE
        // Nothing on a tile being arranged moves under the finger holding it.
        folderPreview?.setPaused(editing)
        peopleMosaic?.setPaused(editing)
        videoPlayer?.let { player ->
            try {
                if (editing && player.isPlaying) player.pause() else if (!editing) player.start()
            } catch (e: IllegalStateException) {
                // Not prepared yet; the prepared listener decides whether it should run.
            }
        }
        // Nothing is turned over while a tile is being arranged.
        applyFlipCornerSize()
        // The corner is the resize handle's while editing, so no indicator shows.
        applyNotificationMark()
        applyWidgetGlyphVisibility()
        applyAlarmMarkVisibility()
        updateNotificationDot()
        // Lifted above its neighbours, because its handles now hang over them: without
        // this the tile drawn after it covers whichever half is on its side.
        elevation = if (editing) EDIT_ELEVATION else 0f
        // Cancel first, then animate. The press effect is already running an animation on
        // scale when this fires, and queueing a second one behind it is what made selecting
        // a tile look like it stalled before resizing - the zoom itself was never the
        // problem, only waiting for the previous animation to finish.
        animate().cancel()
        val resting = restingScale()
        animate()
            .scaleX(resting)
            .scaleY(resting)
            .alpha(1f)
            .setStartDelay(0)
            .setDuration(EDIT_SCALE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Empties the tile of everything but its colour and its scoring.
     *
     * For a folder that has been opened into the wall: its contents are on screen a row
     * below, so repeating them in miniature on the tile itself would be the same list
     * twice, and its name is at the head of what opened. The block of colour stays,
     * because it is what marks where the folder that is open actually is - and so do the
     * lines across it, which are what say the block is a folder rather than a tile that
     * has lost its icon.
     */
    fun setEmptied(emptied: Boolean) {
        if (isEmptied == emptied) return
        isEmptied = emptied
        applyEmptied()
    }

    private var isEmptied = false

    private fun applyEmptied() {
        folderPreview?.let {
            it.visibility = if (hasFolderPreview) VISIBLE else GONE
            it.setContentsHidden(isEmptied)
        }
        iconRow.visibility = if (isEmptied || hasFolderPreview) GONE else VISIBLE
        // Asked rather than told: emptying a tile hides its name, but filling it again
        // does not simply show it - whether there is room for a name is a question about
        // the tile, and answering it here put one on tiles that have no room for one.
        applyLabelVisibility()
        if (isEmptied) {
            notificationDot.visibility = GONE
            notificationIcon.visibility = GONE
        }
    }

    /**
     * Pushes what this tile is saying out of focus while the wall's attention is elsewhere.
     *
     * For the wall behind an opened folder: what is in the folder is sharp, and what is
     * around it is the suggestion of a wall rather than a wall. Blur rather than a fade,
     * because a faded tile is still a tile to be read and reading them is exactly what is
     * not on offer - a tap on any of them closes the folder instead of opening anything.
     * See StartScreenView.animateWallBlur.
     *
     * [amount] runs 0 (sharp) to 1, and is handed over a frame at a time by the animation
     * that puts the wall back, so this has to be cheap to call repeatedly.
     */
    fun setBlur(amount: Float) {
        val clamped = amount.coerceIn(0f, 1f)
        if (blurAmount == clamped) return
        blurAmount = clamped
        applyBlur()
    }

    /** How far out of focus this tile currently is. See [setBlur]. */
    private var blurAmount = 0f

    /** The radius the effect was last built at, so frames that change nothing don't. */
    private var blurRadius = 0f

    /**
     * Puts the blur on what the tile is showing, or takes it off.
     *
     * On the children rather than on the tile, because the tile itself is not what is
     * meant to soften: its face is the wallpaper seen through a window - see [drawFace] -
     * and blurring that put a smeared, offset slice of the photograph in every tile, so a
     * wall of windows onto one picture became a wall of little pictures. The face stays as
     * it is; the glyph, the words, the counts and the covers laid over it are what go out
     * of focus, which is also all there is to read.
     *
     * Nothing happens below API 31. Blurring a live view needs the renderer's own effect
     * and there is none before it; [Blur] cannot stand in, because it works on a bitmap
     * and these are views that turn over, count and tick. The wall stays sharp on those,
     * and the tap that closes the folder still closes it.
     */
    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = blurAmount * BLUR_MAX_DP * resources.displayMetrics.density
        // A change too small to see is a RenderEffect built for nothing - on every tile on
        // the wall, for every frame of the animation.
        if (radius > 0f && kotlin.math.abs(radius - blurRadius) < BLUR_STEP_PX) return
        blurRadius = radius
        // A blur spreads: what comes out is larger than what went in, by the radius, on
        // every side. The wall does not clip its children - a tile's edit handles have to
        // hang over its neighbours - so nothing downstream stopped that, and every blurred
        // tile wore a halo of itself over the ones beside it. Clipped to its own edges
        // while it is blurred, and only while: a clip is exactly what the handles must not
        // have, and they are only ever out in edit mode, which is sharp.
        val clip = radius >= BLUR_STEP_PX
        if (clipToOutline != clip) {
            outlineProvider =
                if (clip) BLUR_CLIP else android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = clip
        }
        // Zero is not a radius the renderer will take, and anything under half a pixel is
        // not one worth taking: below that the effect comes off entirely, which is also
        // what puts a sharp tile back on the cheap drawing path.
        val effect =
            if (radius < BLUR_STEP_PX) null
            // Clamped rather than decayed: what is at the edge of a face carries on past
            // it, so a cover or a mosaic that fills the tile still reaches its corners
            // instead of fading out short of them. Where a face is transparent at the edge
            // - which is most of them, a glyph in the middle of nothing - there is nothing
            // to carry, so it costs those none of their softness.
            else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        for (i in 0 until childCount) getChildAt(i).setRenderEffect(effect)
    }

    /**
     * A face built while the wall is out of focus arrives out of focus with it.
     *
     * A tile behind an open folder is still live: it turns over, and the far side is made
     * when it is first needed rather than with the tile. Without this the tile would come
     * back sharp on the turn, and stay sharp, while the wall around it was still blurred.
     */
    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (blurRadius < BLUR_STEP_PX) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        child.setRenderEffect(
            RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
        )
    }

    /**
     * Keeps the handles on the screen for a tile with nothing to its right.
     *
     * Both of them are centred on the tile's right-hand edge, which for a tile on the last
     * column is the edge of the screen: half of each disc, and half of the mark inside it,
     * would be off it - visible as nothing and tappable as nothing. So an edge tile wears
     * them inside its corners instead. The wall runs to the edges of the screen, and the
     * hairline it does hold back - see TileGridLayout.MARGIN_DP - is a third of what a
     * handle needs: a handle that can be seen and pressed is worth more than one that sits
     * prettily on a corner.
     *
     * Told to the tile by the grid, which is what knows the column. See
     * TileGridLayout.onMeasure.
     */
    fun tuckHandles(tucked: Boolean) {
        if (handlesTucked == tucked) return
        handlesTucked = tucked
        applyHandleTuck()
    }

    private fun applyHandleTuck() {
        val overhang = if (handlesTucked) 0 else -dp(HANDLE_DP) / 2
        for (handle in listOf(resizeHandle, unpinHandle)) {
            val lp = handle.layoutParams as? LayoutParams ?: continue
            if (lp.marginEnd == overhang) continue
            lp.marginEnd = overhang
            // Set back through the property rather than left mutated in place: the tile
            // has to be measured again for a margin to move anything, and assigning is
            // what asks for that.
            handle.layoutParams = lp
        }
    }

    /**
     * Whether a point in this tile's own coordinates lands on one of its edit handles.
     *
     * The handles straddle the corners, so half of each lies outside the tile - and a
     * parent only hands a child the touches that fall inside that child. The grid asks
     * this so it can route the outer halves here itself. See TileGridLayout.
     */
    fun handleHit(x: Float, y: Float): Boolean {
        if (!isEditMode) return false
        return hitsHandle(resizeHandle, x, y) || hitsHandle(unpinHandle, x, y)
    }

    private fun hitsHandle(handle: View, x: Float, y: Float): Boolean =
        handle.visibility == VISIBLE &&
            x >= handle.left && x < handle.right && y >= handle.top && y < handle.bottom

    /**
     * Steps this tile back while another one is being arranged.
     *
     * The wall gives way to the tile in hand rather than the other way round: everything
     * else shrinks a little, fades, and drifts, which leaves the one being moved at full
     * size and full strength without having to do anything to it at all.
     */
    fun setDimmed(dimmed: Boolean) {
        if (isDimmed == dimmed) return
        isDimmed = dimmed
        stopDrift()
        animate().cancel()
        val resting = restingScale()
        val settle = animate()
            .scaleX(resting)
            .scaleY(resting)
            .alpha(if (dimmed) DIM_ALPHA else 1f)
            .setStartDelay(0)
            .setDuration(EDIT_SCALE_MS)
            .setInterpolator(DecelerateInterpolator())
        if (dimmed) {
            // The drift picks up where the settle leaves off, so the tile does not start
            // wandering while it is still stepping back.
            settle.withEndAction { if (isDimmed) startDrift() }
        } else {
            settle.translationX(0f).translationY(0f)
        }
        settle.start()
    }

    // --- Edit-mode drift ---------------------------------------------------------------
    // The wall is never quite still while a tile is being arranged. Not a wobble - the
    // tiles are not asking to be removed, they are standing out of the way - but a slow
    // wander of a few pixels, which is what makes the whole screen read as loose and the
    // one tile in hand as the thing that is fixed.

    /** This tile's own place in the cycle and its own pace. No two trace the same path. */
    private val driftPhase = (Math.random() * Math.PI * 2).toFloat()
    private val driftPeriodMs =
        DRIFT_PERIOD_MS + (Math.random() * DRIFT_PERIOD_SPREAD_MS).toFloat()

    private var drifting = false
    private var driftStartedAt = 0L

    /**
     * Driven from elapsed time rather than from a looping animator.
     *
     * A repeating ValueAnimator restarts its value at zero every cycle, which is only
     * seamless if *both* axes come back to where they started - and they cannot, because
     * the whole point of [DRIFT_Y_RATE] is that the second axis runs at a different rate
     * and so is partway through its own cycle when the first one ends. That mismatch is
     * the snap: the tile jumped back to the top of a path it was in the middle of.
     *
     * A clock has no cycle to restart, so the path simply continues.
     */
    private val drift = object : Runnable {
        override fun run() {
            if (!drifting) return
            val elapsed = android.os.SystemClock.uptimeMillis() - driftStartedAt
            val t = TWO_PI * elapsed / driftPeriodMs
            // Eased in, so the tile slides into the wander from where it is standing
            // rather than jumping to wherever its own phase happens to start it.
            val amplitude = dp(DRIFT_DP) * (elapsed / DRIFT_RAMP_MS).coerceAtMost(1f)
            translationX = amplitude * kotlin.math.sin(t + driftPhase)
            // A different rate on the other axis, so the path is a slow figure rather than
            // a circle and nothing on the wall looks mechanical.
            translationY = amplitude * kotlin.math.cos(t * DRIFT_Y_RATE + driftPhase)
            postOnAnimation(this)
        }
    }

    private fun startDrift() {
        if (drifting) return
        drifting = true
        driftStartedAt = android.os.SystemClock.uptimeMillis()
        postOnAnimation(drift)
    }

    private fun stopDrift() {
        drifting = false
        removeCallbacks(drift)
    }

    // ---------------------------------------------------------------- live flip

    /**
     * Flips between the icon face and the live-content face.
     *
     * Split into two half-rotations so the content can be swapped while the tile is
     * edge-on and the change is invisible - the same trick WP8.1's own live tiles use.
     */
    companion object {
        /** Glyph edge as a fraction of the tile's short side, before any size correction. */
        private const val GLYPH_FRACTION = 0.42f

        /**
         * Ceiling for a corrected glyph.
         *
         * A capped glyph is the one case where two tiles do *not* end up matching, since
         * the correction is being held back - so it sits high enough to catch only artwork
         * with almost nothing in its canvas.
         */
        private const val MAX_GLYPH_FRACTION = 0.72f
        /**
         * The blur radius at full strength, in dp. See [setBlur].
         *
         * Read against a tile rather than against the screen: what a blur has to do here
         * is take the glyph and the words off a square about a finger wide, and it is the
         * square that says how far that is.
         */
        private const val BLUR_MAX_DP = 16f

        /** Radius changes smaller than this are not worth a new effect, nor is a blur. */
        private const val BLUR_STEP_PX = 0.5f

        /**
         * Holds a blurred tile's spread inside the tile. See [applyBlur].
         *
         * Cuts without casting: the outline is here to clip, and the provider a tile wears
         * otherwise - BACKGROUND, over a tile that has no background drawable - throws no
         * shadow either, so a tile lifted in edit mode goes on looking as it did.
         */
        private val BLUR_CLIP = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRect(0, 0, view.width, view.height)
                outline.alpha = 0f
            }
        }

        // How far the wall stands back from the tile being arranged, and how far it fades.
        private const val DIM_SCALE = 0.92f
        private const val DIM_ALPHA = 0.7f

        // And how far it wanders while it waits. Small on purpose: this is the wall
        // breathing, not the tiles asking to be dealt with.
        private const val DRIFT_DP = 3
        private const val DRIFT_PERIOD_MS = 4200f
        private const val DRIFT_PERIOD_SPREAD_MS = 2600.0
        private const val DRIFT_Y_RATE = 0.73f

        /** How long the wander takes to reach full size once a tile starts it. */
        private const val DRIFT_RAMP_MS = 900f

        private const val TWO_PI = (2.0 * Math.PI).toFloat()

        /** Lifts the tile being arranged over its neighbours, handles and all. */
        const val EDIT_ELEVATION = 8f

        /** How long the selection zoom takes. Short, and never queued behind anything. */
        private const val EDIT_SCALE_MS = 140L

        /** Handle diameter. Large enough to hit on a small tile without covering it. */
        private const val HANDLE_DP = 30

        /**
         * How far a handle reaches past the tile it belongs to.
         *
         * Half of it, since each one is centred on its corner. Read by the wall, which has
         * to leave the bottom row this much room to put its handle in.
         */
        const val HANDLE_OVERHANG_DP = HANDLE_DP / 2

        /** How long a turn takes, end to end. */
        private const val FLIP_MS = 500L

        /**
         * Every mark in the top-right corner: the app's, a widget's, the one on a tile
         * turned over. One size on every tile, and the same distance in from the edge.
         */
        private const val CORNER_MARK_DP = 18

        /**
         * The same mark a couple of points down, where the corner is not the mark's alone.
         *
         * Two tiles are in that position. The clock and the calendar carry the alarm mark
         * over a face that is a reading corner to corner, where at the shared size it read
         * as part of what the tile was showing rather than as a note about the phone. The
         * media face carries the app's mark with the elapsed time ranged up against it and
         * a title and an artist underneath, which is the busiest corner on the wall.
         */
        private const val SMALL_MARK_DP = 16

        /** However tight the row, the title keeps this much to be read in. */
        private const val MEDIA_TEXT_MIN_DP = 48

        /**
         * How far the media title and its line are held off the tile's right edge.
         *
         * A flat figure rather than the corner measured: the mark and the clock sit in
         * that band and the title's first line runs level with them, and thirty points
         * clears both by eye without cutting the line for the sake of the last digit.
         */
        private const val MEDIA_CORNER_PUSH_DP = 30

        /**
         * Between the icon and the number of things waiting, against the icon's own box.
         *
         * Small, and smaller than it looks: type carries its own side bearings and an icon
         * its own margin, so most of the space between the two is already there before any
         * gap is added. The pair should read as one thing - an icon and its count - rather
         * than as two things sharing a tile.
         */
        private const val COUNT_GAP_FRACTION = 0.25f

        /**
         * How tall the digits stand against the tile's short side.
         *
         * The tile, not the icon: two tiles of one footprint carry one size, whatever
         * artwork happens to be on them. [GLYPH_FRACTION] times the share of the icon the
         * digits were set to before it, so a tile that was already showing them at about
         * the right size still is.
         *
         * A count too long to fit beside its icon is still brought down until it does -
         * see [applyCountSize]. That is the one case where two tiles of a size can differ,
         * and the alternative is a number running off the edge of one of them.
         */
        private const val COUNT_HEIGHT_FRACTION = 0.28f

        // How far the digits are lifted off the icon's foot: a share of themselves, which
        // corrects for the optical measurement overshooting the real foot, and then a flat
        // distance, which is the clearance that looked right.
        private const val COUNT_LIFT_FRACTION = 0.04f
        private const val COUNT_LIFT_DP = 4

        /**
         * The count's weight: a step up from everything else on the tile.
         *
         * The wall is set in Semilight throughout and a number is the one thing on it that
         * is read rather than looked at - at a glance, from a pocket's distance, against a
         * mark of its own size beside it. A step up is enough to make it legible without
         * making it loud.
         *
         * Semibold rather than the Regular that sits between them, because of one glyph:
         * this family's Regular is the only weight whose "1" is drawn with a base serif,
         * where Light, Semilight and Semibold all give it the bare stem Segoe WP had. A
         * footed 1 is the wrong numeral for this shell, and it is the numeral a count
         * shows more often than any other.
         */
        private val COUNT_FONT = R.font.segoeui_semibold

        /** The highest a tile counts to before it gives up and says "and more". */
        private const val COUNT_MAX = 99

        /** Kept between the count and the edge of the tile it is being fitted into. */
        private const val COUNT_EDGE_DP = 8

        /** Unread marker on a tile still showing its icon. */
        private const val NOTIFICATION_DOT_DP = 14

        /** View size around the dot, so it is reliably tappable on tiles that can flip. */
        private const val NOTIFICATION_DOT_TARGET_DP = 36

        /** Clearance kept under notification text for the app name. */
        private const val NOTIFICATION_LABEL_GAP_DP = 16


        // How many lines of notification body a tile can hold under its title.
        private const val BODY_LINES = 3
        private const val STRIP_BODY_LINES = 2

        /**
         * What a row of height past the second is worth to a headline, in lines.
         *
         * A story is set at the one size the wall shares, so a taller tile buys lines
         * rather than larger type - see applyLiveTextSizes and storyLines.
         */
        private const val STORY_LINES_PER_ROW = 3

        // Transport control edges. Sized to the tile: a wide tile has room for real
        // buttons, and 26dp everywhere made them fiddly on exactly the tile with the most
        // space to give them.
        private const val CONTROL_LARGE_DP = 44
        private const val CONTROL_MEDIUM_DP = 36
        private const val CONTROL_SMALL_DP = 30

        /** Kept between the end of the text and the first transport control. */
        private const val CONTROL_CLEARANCE_DP = 6

        /** How often the elapsed time redraws. */
        private const val MEDIA_TICK_MS = 1000L

        // One title size and one caption size, for every face of every tile: a widget's
        // reading, a story's headline, a notification's subject, the name of a track.
        // Nothing on the wall is set to its own scale.
        /** How far the artist is pulled up under the track it belongs to. */
        private const val MEDIA_TEXT_TIGHTEN_DP = 2

        /** How far in from the mark's slot the elapsed time is ranged. */
        private const val MEDIA_TIME_NUDGE_DP = 3

        /**
         * The widest elapsed time the corner is held open for by default.
         *
         * Four digits and a colon, which is every track short of an hour. A longer one is
         * measured as itself rather than pushing this to the length of the rarest case and
         * cropping every title on the wall for it.
         */

        /**
         * A story's headline, and the message on a notification.
         *
         * Two points down from where it was. At 16 it was the largest thing on the wall
         * that was not a reading, and on a wall where an appointment, a track and a place
         * all head themselves at 14 - see [FACE_TITLE_SP] - the News tile and the message
         * tile were the two that shouted. A headline is the one title that is read rather
         * than glanced at, so it is the one that loses least by coming down, and a story
         * fits more of itself on the same tile for it.
         */
        private const val LIVE_TITLE_SP = 14f

        /**
         * The line under a title, wherever there is one.
         *
         * A story's source, a notification's sender, an artist, the hour an appointment
         * starts, what the sky is doing - see WeatherFaceView.CONDITION_SP, which is this
         * size on the one face that draws its own type. A point down from where it was, so
         * that it sits two clear of the title over it rather than one: at 13 under a 14
         * the pair read as one block of the same size set in two weights, and a caption a
         * reader cannot tell from its title is not doing a caption's job.
         */
        private const val LIVE_CAPTION_SP = 12f

        /**
         * The head of a face that names something.
         *
         * An appointment, a track, a place: three tiles saying the same shape of thing -
         * the name of what the tile is about, with one line under it saying when, or who
         * by, or what the sky is doing. They were set three different ways. A track took
         * the story size in Semilight; an appointment took the caption size in Regular and
         * was told apart from the hour under it by nothing at all; a place took a size of
         * its own. Three tiles standing in the same row, each heading itself in a
         * different hand.
         *
         * One size for the lot of them, and it is the one the weather tile already used -
         * see WeatherFaceView.PLACE_SP. The size a headline takes as well, [LIVE_TITLE_SP]
         * having since come down to meet it: what tells a name from a headline is the
         * weight it is set in and the line it is paired with, not how large it is.
         */
        private const val FACE_TITLE_SP = 14f

        /**
         * And the weight it is set in, a step up from the line beneath it.
         *
         * The wall is Semilight throughout and tells a title from its caption by size and
         * position alone - see [TITLE_FONT], which is still how a headline is set, a
         * headline being long enough that where it starts and where it stops says what it
         * is. A name is two or three words, one point clear of the line under it, and one
         * point is not a difference anybody reads: the weight is what separates them.
         */
        private val FACE_TITLE_FONT = R.font.segoeui_semibold

        /** Air over a caption heading a tile, so it is not set against the border. */
        private const val CAPTION_HEAD_DP = 4

        /** What the calendar's own caption takes on top of it. See [captionHeadDp]. */
        private const val CALENDAR_HEAD_EXTRA_DP = 2

        /** What its date and weekday take on top of theirs. See [readingExtraSp]. */
        private const val CALENDAR_READING_EXTRA_SP = 2f

        // A folder's unread dot and the air between it and the name. Smaller than the dot
        // in a tile's corner: that one has a whole corner and has to be seen from across a
        // wall, where this one is set beside a word and only has to be seen with it.
        private const val FOLDER_DOT_DP = 8
        private const val FOLDER_DOT_GAP_DP = 5

        /**
         * The wall's small words, as one style.
         *
         * The app's name along the foot of a tile, and what stands beside a reading - the
         * calendar's weekday. They are the same thing seen twice: a word rather than a
         * number, read at a glance against whatever the tile is showing. Held together
         * here so the wall's words are changed in one place rather than three.
         *
         * Semibold, where the rest of the small type on a tile is Semilight. These are
         * the smallest things on the wall and the ones most often read over a photograph
         * or a busy piece of album art, where Semilight simply disappears.
         */
        private object TileLabel {
            const val SIZE_SP = 13f

            /** However little room is left beside a number, it is still meant to be read. */
            const val MIN_SP = 11f

            val FONT = R.font.segoeui_semibold
        }

        /** The air between a reading and what stands beside it. */
        private const val ASIDE_GAP_DP = 5


        // A widget's reading: how large it may be set, on a 1x1 and on everything else,
        // and how small it may be squeezed before the tile gives up and crops it. Large
        // enough that the ceiling rarely binds - what actually sets the size is the tile,
        // through [NUMBER_HEIGHT_SHARE] and the width of the column it is set in.
        private const val LIVE_NUMBER_SP = 80f
        private const val LIVE_NUMBER_MIN_SP = 12f

        /**
         * The reading every tile is sized to fit, whatever it is actually showing.
         *
         * A clock's time, which is the widest of them: five glyphs where a date is two and
         * an index three. Digits are tabular in this face, so any time is this wide.
         */
        private const val NUMBER_REFERENCE = "00:00"

        /** How much of a cell's height the number may take. */
        private const val NUMBER_HEIGHT_SHARE = 0.4f

        /** How the foot of a reading is shared between what is beside it and the number. */
        private const val ASIDE_WEIGHT = 1f
        private const val READING_WEIGHT = 2f

        // And one weight for the lot of it. Semilight is the face Windows Phone was set
        // in, and the wall reads as one surface when nothing on it is heavier than
        // anything else - a title is told apart from its caption by size and position,
        // which is enough, and was the only job the bolder face was doing.
        private val TITLE_FONT = R.font.segoeui_semilight

        /** A reading is set in weight as well as in size: it is the tile's whole point. */
        private val NUMBER_FONT = R.font.segoeui_semibold

        /**
         * The line over a reading, a weight up from the rest of the small type.
         *
         * It carries the words on the tile - what the number is of, what is on today - and
         * at that size, set in the same weight as everything else, it was the first thing
         * to disappear into the accent behind it.
         */
        private val SUBTITLE_FONT = R.font.segoeui_regular

        /** Leading, as a multiple of the font's own. See tightenLines. */
        private const val LINE_SPACING = 0.9f

        /** How long a tile rests on each face before turning over. */
        private const val LIVE_FLIP_MS = 9_000L

        /**
         * How many people beyond the squares the People tile keeps in hand.
         *
         * Three. A wall with exactly as many people as squares is a wall that can never
         * turn one over: every face is already up, and there is nobody to turn over *to*.
         */
        private const val SPARE = 3

        /**
         * Faces along the People tile's shorter side.
         *
         * Three, which on the square tile is the nine-square mosaic Windows Phone drew.
         * See peopleGrids, which works every other footprint out from this one.
         */
        private const val FACES_ACROSS = 3

        private const val TAG = "WP81Tile"

        /** How far in from the corner every one of those marks sits. */
        private const val CORNER_INSET_DP = 8

        /** Between the media clock and the app mark it sits beside. */
        private const val CLOCK_GAP_DP = 6

        /**
         * The emptiest an icon may be before the correction stops growing its box.
         *
         * Half: a mark drawn at a third of its canvas would otherwise be given three times
         * the room and pushed off the edge of the tile to keep its position.
         */
        private const val MIN_CORNER_RATIO = 0.5f

        // Corner hot zone for turning a tile over.
        private const val FLIP_CORNER_DP = 44
        private const val FLIP_CORNER_STRIP_DP = 34

        /** How far into a 1x1 its corner reaches, from the top and from the right. */
        private const val SMALL_CORNER_FRACTION = 0.25f

        /** Shortest rightward drag that counts as a flip. Short on purpose. */
        private const val SWIPE_MIN_DP = 14

        /** How far a tile with nothing on its reverse tips before falling back. */
        private const val NUDGE_DEGREES = 26f

        /** How strongly the accent tints a tile that is showing the Start background. */
        private const val BACKGROUND_TINT_ALPHA = 0.45f

        /**
         * And how strongly it tints a story's picture or an album cover.
         *
         * Heavier than the Start background's: that one is seen through a hole in a tile,
         * where these have a headline or a track name sitting on them and have to stay
         * readable over a photograph the tile did not choose and has never seen.
         */
        private const val BACKDROP_TINT_ALPHA = 0.62f

        /**
         * How far the ground under a tile's own words is darkened, while the colours are
         * held back and the wallpaper is showing through the tile.
         *
         * Lighter than [BACKDROP_TINT_ALPHA]: that one covers a photograph the tile
         * brought with it and has only the headline to protect, where this lies over the
         * user's own picture - the thing the switch exists to show - so it goes only as
         * far as the text needs and no further.
         *
         * Public because it is also where the dim slider starts: a wall switched to dim
         * every tile, and not touched further, sits at the tone the talking tiles were
         * already wearing. See [dimAmount] and WP81Settings.getWP81DimAmount.
         */
        const val CONTENT_SCRIM_ALPHA = 0.45f
    }
}
