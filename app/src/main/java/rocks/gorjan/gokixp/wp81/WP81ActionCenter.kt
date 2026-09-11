package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.NotificationListenerService
import rocks.gorjan.gokixp.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The Windows Phone 8.1 Action Center, pulled down out of the top of the Start screen.
 *
 * ```
 *   |  ..iI  H+          [#]   18:30 |   WP81StatusBar - not this view's, and
 *   |  3                   86% 16/04 |   always on screen. The panel starts below.
 *   +------+------+---------+--------+
 *   | WIFI |  BT  | FLIGHT  | CAMERA |   four quick actions
 *   +--------------------------------+
 *   |  [cover] Ashes of Eden     [#] |   whatever is playing, with the app it
 *   |          Timmies               |   is coming out of - see MiniPlayer
 *   |          |<  ||  >|  1:12/3:04 |
 *   |  ======------------------------|   and a bar that can be dragged
 *   +--------------------------------+
 *   |  X CLEAR ALL     ALL SETTINGS  |
 *   |                                |
 *   |  [#] 3Alerts  · Tue        v   |   the app's mark, then what the
 *   |      From Three: thanks for... |   notification says and when - see Row
 *   |  [#] Outlook.com Team  · 16:39 |   newest app first, newest row first
 *   |      Your statement is ready   |
 *   |  ------------------------------|   and what is only running last, shut
 *   |  ongoing  (2)                v |   under a rule of its own that opens
 *   |                                |   it - see rebuild
 *   +--------------------------------+
 *   |============  ---  =============|   the accent grabber
 *   +--------------------------------+
 * ```
 *
 * It covers the whole shell below the status bar, navigation keys included, which is what
 * the phone's own did. The strip above stays put while the panel slides away behind it -
 * that is the phone's arrangement, where the status bar is a fixed band and the Action
 * Center is what comes down out of it. See [WP81StatusBar].
 *
 * Pushing the panel back up closes it, from anywhere but the notifications themselves - see
 * [onInterceptTouchEvent] - as does the grabber along the bottom, and back.
 *
 * **What this is not.** It is not Android's shade and cannot become one: an app is not
 * allowed to draw over the system's, and the two live side by side. Android's own is still
 * there, one swipe from the real status bar above; this is what the *launcher's* pull-down
 * gesture opens, in the shell's own idiom, over the notifications the listener is already
 * reading for the tiles. See `WP81Settings.getWP81ActionCenter` for the switch that chooses
 * between the two, and NotificationListenerService.ShadeEntry for what fills the list.
 *
 * **What the quick actions can actually do.** Very little, and honestly so. Android stopped
 * letting apps turn the radios on and off years ago - `setWifiEnabled` has been a no-op
 * since Android 10 and `BluetoothAdapter.enable` since 13, airplane mode has been a system
 * setting since 4.2 - so three of the four read their state and hand the tap to the
 * platform's own panel for that radio, which is the nearest thing to a toggle an app is
 * given. The fourth is not a switch at all: the camera is a command, and lights up for
 * nothing. See [QuickAction].
 */
@SuppressLint("ViewConstructor")
class WP81ActionCenter(
    context: Context,
    private var palette: WP81Palette,
    private val iconProvider: MonochromeIconProvider
) : FrameLayout(context) {

    // ---------------------------------------------------------------- what the host wires

    /** A row was tapped: send the notification where it was going. */
    var onOpenNotification: ((NotificationListenerService.ShadeEntry) -> Unit)? = null

    /** A row was swiped away: retire that one notification. */
    var onDismissNotification: ((NotificationListenerService.ShadeEntry) -> Unit)? = null

    /** An app's heading was tapped: open the app itself. */
    var onOpenApp: ((String) -> Unit)? = null

    /** "clear all". */
    var onClearAll: (() -> Unit)? = null

    // The mini player's four. The panel knows which app is making the sound and nothing
    // else about how to reach it: the sessions are the host's. See [MiniPlayer].

    /** Play, or pause, whatever the named app is playing. */
    var onMediaPlayPause: ((String) -> Unit)? = null

    /** Next track. */
    var onMediaNext: ((String) -> Unit)? = null

    /** Previous track. */
    var onMediaPrevious: ((String) -> Unit)? = null

    /** Move playback to a position, in milliseconds. Only where the app takes it. */
    var onMediaSeek: ((String, Long) -> Unit)? = null

    /** "all settings" - the shell's own settings page, not Android's. */
    var onAllSettings: (() -> Unit)? = null

    /**
     * The panel is coming out, or has finished going away.
     *
     * The shell's, not the host's: what listens to this is the status strip above, which
     * grows its second line while the panel is out. Reported late on the way back - once
     * the panel is off the screen rather than as it starts to leave - because the strip
     * shrinking is the panel's own top edge moving, and doing that under a panel still in
     * flight would jerk it upward as it went.
     */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    /** The empty page was tapped while the listener is switched off: go and switch it on. */
    var onNotificationAccess: (() -> Unit)? = null

    // ---------------------------------------------------------------- the panel

    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val quickRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private val clearAllRow = CommandRow("clear all", "$ICONS/appbar.close.svg")
    private val allSettingsRow = CommandRow("all settings", "$ICONS/appbar.cog.svg")

    /** The two of them on their line. Held because the player above it moves it up. */
    private val commandsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
    }

    /**
     * What is said where nothing has been posted.
     *
     * The phone showed an empty black field, which on a screen that has just slid down
     * over the wall reads as a page that failed to load rather than as good news. One
     * quiet line says which of the two it is.
     */
    private val emptyLine = TextView(context)

    private val grabber = FrameLayout(context)
    private val grabberHandle = View(context)

    /**
     * The air under the last row, so a list scrolled to its end does not sit hard against
     * the accent band below it.
     *
     * Held rather than made on the spot because [rowsBottom] has to be able to tell it from
     * a notification: it is black, and black is somewhere the panel can be taken hold of.
     */
    private val trailingSpace = View(context)

    private val quickActions: List<QuickAction>

    /**
     * The player between the quick actions and the two commands.
     *
     * Declared here and built in `init`, like [quickActions] and for the reason spelled
     * out at [rows]: a property whose initialiser runs after `init` is still null while
     * `init` is putting the panel together.
     */
    private val miniPlayer: MiniPlayer

    // ---------------------------------------------------------------- state

    private var open = false

    /**
     * What the mini player is currently showing, or null when nothing is playing.
     *
     * Read by [rebuild] as well as by the player itself: the list leaves out the
     * notification belonging to whatever is drawn up there. See [setMedia].
     */
    private var playing: MediaSessions.Info? = null

    /**
     * Whether the ongoing section at the foot of the panel is open.
     *
     * Shut every time the panel comes down - see [close]. What is running has been running
     * all afternoon and is there to be checked on when somebody goes looking for it; the
     * screen the panel opens on belongs to what is actually waiting.
     */
    private var ongoingOpen = false

    /** The list as it was last drawn, so an unchanged tick does not rebuild it. */
    private var latest: List<NotificationListenerService.ShadeEntry> = emptyList()
    private var drawnSignature: String? = null

    /**
     * Which notifications are open, by key rather than by row.
     *
     * The list is rebuilt from scratch whenever anything about it changes, and a row opened
     * out is a thing the user did that should survive the next notification arriving
     * somewhere else on the panel. Keys of notifications that have since been withdrawn
     * cost a string each and are dropped on the next rebuild.
     */
    private val expandedKeys = mutableSetOf<String>()

    /**
     * The packages with something waiting, in the order the panel lists them.
     *
     * Reported out because the status strip wears the same marks in the same order - which
     * is the strip saying what is behind it, and the panel is the only thing that knows.
     */
    private var marked: List<String> = emptyList()

    /** The same, less the apps with nothing but ongoing notifications. */
    private var markedForStrip: List<String> = emptyList()

    /** Fired when [markedForStrip] changes. Wired to the status strip by the shell. */
    var onMarkedAppsChanged: ((List<String>) -> Unit)? = null

    /**
     * The rows currently on the list, in the order they are drawn.
     *
     * Declared up here with the rest of the state and not beside the class it holds, which
     * is where it reads better and where it does not work: Kotlin runs property
     * initialisers in the order they are written, `init` among them, and this list is built
     * from `init` - so declared below it, it was still null when the first build reached
     * for it, and the launcher came up to a black screen.
     */
    private val rows = mutableListOf<Row>()

    /**
     * Whether the launcher is being told about notifications at all.
     *
     * An empty list means two very different things - nothing is waiting, or nobody is
     * telling us - and the panel is the one place on the phone where the difference
     * matters enough to say out loud. See [rebuild].
     */
    private var hasAccess = true

    /**
     * App marks, kept because the list is rebuilt from a two-second tick.
     *
     * The package manager is an IPC apiece; asking it for the icon of every app in the
     * list thirty times a minute is the kind of thing that shows up as jank on the wall
     * behind rather than here.
     */
    private val marks = mutableMapOf<String, MonochromeIconProvider.Glyph?>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        visibility = GONE
        // The panel is opaque and covers the shell, so nothing behind it should be
        // reachable through it - including the tile a finger happens to come down on.
        isClickable = true

        quickActions = buildQuickActions()
        miniPlayer = MiniPlayer()
        // Between the strip of tiles and the two commands, which is where the phone put
        // the music: under the switches, above everything that is a list, with a little
        // air above it so it reads as its own thing rather than a fifth quick action.
        panel.addView(miniPlayer.view, wide().apply { topMargin = dp(5) })
        buildCommands()

        scroll.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildGrabber()

        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyPalette(palette)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
    private fun font(id: Int) = ResourcesCompat.getFont(context, id)

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    // ---------------------------------------------------------------- quick actions

    /**
     * One of the four squares along the top: an icon, a word, and a fill that says whether
     * the thing it names is on.
     *
     * [state] is read every time the panel is opened and on every tick while it is down,
     * because none of these are this shell's to change and all four can be changed from
     * somewhere else while it is looking at them.
     */
    private inner class QuickAction(
        label: String,
        asset: String,
        private val state: () -> Boolean,
        private val press: () -> Unit
    ) {
        private val icon = ImageView(context)
        private val caption = TextView(context)
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(7))
            isClickable = true
        }

        private var on = false

        init {
            icon.setImageDrawable(SvgIcon.fromAsset(context, asset))
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            caption.text = label
            caption.textSize = QUICK_LABEL_SP
            caption.typeface = font(R.font.segoeui_bold)
            caption.maxLines = 1
            caption.ellipsize = TextUtils.TruncateAt.END
            caption.isAllCaps = true
            caption.includeFontPadding = false

            view.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
            // The word sits on the bottom edge whatever the icon's height, which is what
            // keeps the four captions on one line across the row.
            view.addView(Space(context), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            view.addView(caption, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            TiltEffect.apply(view)
            view.setOnClickListener {
                Haptics.tap(view)
                press()
                // Not repainted here: nothing this press does has happened yet - a panel
                // is opening, or a permission is being asked for - and painting the tile
                // as though it had would be the one lie a status light must not tell.
                // The tick that runs while the panel is down settles it.
            }
        }

        /** Re-reads the setting behind the tile and repaints if it has moved. */
        fun refresh(force: Boolean = false) {
            val now = try {
                state()
            } catch (e: Exception) {
                // Reading a radio's state can be refused rather than answered - bluetooth
                // wants a runtime permission for it - and a tile that cannot see is drawn
                // as off rather than crashing the panel it sits on.
                false
            }
            if (!force && now == on) return
            on = now
            view.setBackgroundColor(if (on) palette.accent else palette.chrome)
            val ink = if (on) palette.onAccent() else palette.onChrome
            icon.setColorFilter(ink)
            caption.setTextColor(ink)
        }
    }

    private fun buildQuickActions(): List<QuickAction> {
        val actions = listOf(
            QuickAction(
                "wi-fi", "$ICONS/appbar.connection.wifi.variant.svg",
                state = { wifiOn() },
                // Settings.Panel is the inline sheet the platform offers in place of the
                // toggle it took away: it comes up over the launcher rather than leaving
                // it, which is as close to a quick action as an app can now get.
                press = { start(Intent(Settings.Panel.ACTION_WIFI)) }
            ),
            QuickAction(
                "bluetooth", "$ICONS/appbar.connection.bluetooth.svg",
                state = { bluetoothOn() },
                press = {
                    // Turning it on is a request the platform will put to the user;
                    // turning it off is not offered at all, so that half goes to settings.
                    start(
                        if (bluetoothOn()) Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        else Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    )
                }
            ),
            QuickAction(
                "flight mode", "$ICONS/appbar.plane.svg",
                state = { airplaneOn() },
                press = { start(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            ),
            QuickAction(
                "camera", "$ICONS/appbar.camera.svg",
                // A command rather than a switch, so it is never lit: there is nothing for
                // it to be the state of. The phone's own camera quick action was the same -
                // a way to the camera from a screen you had already pulled down.
                state = { false },
                press = {
                    start(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                }
            )
        )
        for ((index, action) in actions.withIndex()) {
            quickRow.addView(action.view, LinearLayout.LayoutParams(0, dp(QUICK_HEIGHT_DP), 1f)
                .apply { if (index < actions.lastIndex) marginEnd = dp(4) })
        }
        // A little air under the strip, so the row of tiles reads as the first thing on
        // the panel rather than as a second line of the status bar.
        panel.addView(quickRow, wide().apply { topMargin = dp(5) })
        return actions
    }

    private fun wifiOn(): Boolean {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifi?.isWifiEnabled == true
    }

    private fun bluetoothOn(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    private fun airplaneOn(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Nothing on this phone answers ${intent.action}", e)
        }
    }

    // ---------------------------------------------------------------- what is playing

    /**
     * The mini player: whatever is coming out of the speaker, drawn as controls.
     *
     * ```
     *   +--------+  Ashes of Eden                    [#] |  cover, track, the app it is
     *   |        |  Timmies                              |  coming out of
     *   | cover  |                                       |
     *   |        |  |<  ||  >|                1:12 / 3:04|  transport, and how far in
     *   +--------+---------------------------------------+
     *   |=================------------------------------ |  the scrubber
     * ```
     *
     * It stands in place of the notification the player posts rather than beside it - see
     * [rebuild] - which is the arrangement the phone had: music was something the Action
     * Center *did*, with a cover and buttons, not a line of text among the mail.
     *
     * Every button reports out through the host: the panel knows which package is making
     * the sound and nothing else about how to reach it. See MainActivity.wireWP81ActionCenter.
     */
    private inner class MiniPlayer {

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Nothing under the scrubber: what separates it from the commands below is
            // their own spacing, and the strip the bar is drawn on is already a margin of
            // sorts. See [spaceCommands].
            setPadding(dp(14), dp(8), dp(14), 0)
            // Nothing is playing until something says otherwise, and a player showing an
            // empty square is worse than no player.
            visibility = GONE
        }

        private val artBox = FrameLayout(context)
        private val art = ImageView(context).apply {
            // Cropped rather than fitted: a cover is square, and the few that are not are
            // better trimmed than shown floating in a box of black.
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        /** The app's own mark, on the cover's square, for a player that published no art. */
        private val artMark = ImageView(context)

        private val title = TextView(context).apply {
            textSize = TITLE_SP
            typeface = font(R.font.segoeui_semibold)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

        private val artist = TextView(context).apply {
            textSize = BODY_SP
            typeface = font(R.font.segoeui_regular)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(2), 0, 0)
        }

        /**
         * Which app the sound is coming out of.
         *
         * Small, and beside the track rather than on the cover: the cover is what the eye
         * goes to and the app is the footnote - but it is the answer to "why is this
         * playing", and on a phone with three players installed it is not a guess.
         */
        private val badgeBox = FrameLayout(context)
        private val badge = ImageView(context)

        private val previous = transport(R.drawable.wp81_media_previous) { onMediaPrevious?.invoke(it) }
        private val playPause = transport(R.drawable.wp81_media_play) { onMediaPlayPause?.invoke(it) }
        private val next = transport(R.drawable.wp81_media_next) { onMediaNext?.invoke(it) }

        private val elapsed = clockText()
        private val length = clockText()

        private val scrubber = Scrubber()

        init {
            artBox.addView(art, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            artBox.addView(artMark, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val heading = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(title, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            badgeBox.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            heading.addView(badgeBox, LinearLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP))
                .apply { marginStart = dp(10) })

            val keys = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((index, key) in listOf(previous, playPause, next).withIndex()) {
                keys.addView(key, LinearLayout.LayoutParams(
                    dp(TRANSPORT_DP), dp(TRANSPORT_DP)
                ).apply { if (index > 0) marginStart = dp(4) })
            }
            // Space rather than a View, for the reason given in [buildCommands].
            keys.addView(Space(context), LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            keys.addView(elapsed, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            keys.addView(length, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })

            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            words.addView(heading, wide())
            words.addView(artist, wide())
            words.addView(keys, wide().apply { topMargin = dp(6) })

            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(artBox, LinearLayout.LayoutParams(dp(ART_DP), dp(ART_DP)))
            top.addView(words, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) })

            view.addView(top, wide())
            view.addView(scrubber, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(SCRUB_TARGET_DP)
            ).apply { topMargin = dp(2) })

            // The cover and the words open the player itself; the buttons keep their own
            // taps, being children and asked first.
            for (target in listOf(artBox, words)) {
                TiltEffect.apply(target)
                target.setOnClickListener {
                    val app = playing?.packageName ?: return@setOnClickListener
                    Haptics.tap(target)
                    close()
                    onOpenApp?.invoke(app)
                }
            }

            scrubber.onSeek = { fraction ->
                playing?.let { info ->
                    onMediaSeek?.invoke(info.packageName, (info.durationMs * fraction).toLong())
                }
            }
        }

        private fun clockText() = TextView(context).apply {
            textSize = BODY_SP
            typeface = font(R.font.segoeui_regular)
            maxLines = 1
            includeFontPadding = false
        }

        /**
         * One transport key.
         *
         * The padding is the target and the drawing inside it is the picture, the same way
         * a row's chevron is done: these are small marks and a small mark is a small thing
         * to hit with a thumb.
         */
        private fun transport(icon: Int, press: (String) -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            TiltEffect.apply(this)
            setOnClickListener {
                val app = playing?.packageName ?: return@setOnClickListener
                Haptics.tap(this)
                press(app)
            }
        }

        /** Draws [info], or takes the player off the panel when there is nothing playing. */
        fun bind(info: MediaSessions.Info?) {
            if (info == null) {
                view.visibility = GONE
                spaceCommands(playerShowing = false)
                return
            }
            view.visibility = VISIBLE
            spaceCommands(playerShowing = true)
            title.text = info.title
            artist.text = info.artist
            artist.visibility = if (info.artist.isBlank()) GONE else VISIBLE

            if (info.art != null) {
                art.visibility = VISIBLE
                artMark.visibility = GONE
                art.setImageBitmap(info.art)
                artBox.background = null
            } else {
                // No cover: the app's own mark on an accent square, which is what this
                // shell puts anywhere it has to stand for an app.
                art.visibility = GONE
                artMark.visibility = VISIBLE
                drawMark(artMark, artBox, info.packageName, ART_DP, ART_GLYPH_DP)
            }
            drawMark(badge, badgeBox, info.packageName, BADGE_DP, BADGE_GLYPH_DP)

            playPause.setImageResource(
                if (info.isPlaying) R.drawable.wp81_media_pause
                else R.drawable.wp81_media_play)
            // A key that does nothing is not drawn: a player with no queue behind it -
            // a podcast, a radio stream - should not offer a skip that is ignored.
            previous.visibility = if (info.canSkipPrevious) VISIBLE else GONE
            next.visibility = if (info.canSkipNext) VISIBLE else GONE

            paint()
            tick()
        }

        /** Moves the position on. Called by the panel's own second while it is down. */
        fun tick() {
            val info = playing ?: return
            val known = info.durationMs > 0
            val position = info.currentPositionMs()
            elapsed.text = clock(position)
            length.text = if (known) "/ ${clock(info.durationMs)}" else ""
            length.visibility = if (known) VISIBLE else GONE
            // A live stream has no length to be a fraction of, so it gets no bar: a bar
            // that cannot fill is a bar that reads as stuck.
            scrubber.visibility = if (known) VISIBLE else GONE
            if (known) {
                scrubber.show(
                    fraction = (position.toFloat() / info.durationMs).coerceIn(0f, 1f),
                    seekable = info.canSeek)
            }
        }

        /** The palette, on everything here that carries one. */
        fun paint() {
            title.setTextColor(palette.foreground)
            artist.setTextColor(palette.foregroundSubtle)
            elapsed.setTextColor(palette.foregroundSubtle)
            length.setTextColor(palette.foregroundSubtle)
            for (key in listOf(previous, playPause, next)) {
                key.setColorFilter(palette.foreground)
            }
            scrubber.invalidate()
        }
    }

    /**
     * The line under the track: how far in it is, and where the player takes it, where to
     * go instead.
     *
     * Drawn rather than assembled out of a SeekBar, which arrives wearing a thumb, a
     * ripple and a tint that belong to another phone entirely - and squares are the whole
     * of what this one needs.
     */
    private inner class Scrubber : View(context) {

        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

        /** How far through, 0 to 1. */
        private var fraction = 0f

        /** Whether the app said it would take being moved. See [MediaSessions.Info.canSeek]. */
        private var seekable = false

        /** Whether a finger is on it, in which case what it says is the finger's, not the app's. */
        private var held = false

        /** Where a finger let go. Reported as a fraction; the caller knows the length. */
        var onSeek: ((Float) -> Unit)? = null

        fun show(fraction: Float, seekable: Boolean) {
            this.seekable = seekable
            // While it is being dragged the bar belongs to the finger: taking the app's
            // position back off it mid-gesture is the bar jumping out from under the thumb.
            if (held) return
            if (fraction != this.fraction) {
                this.fraction = fraction
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val middle = height / 2f
            val half = dpf(SCRUB_DP) / 2f
            ink.color = palette.foregroundSubtle
            ink.alpha = 80
            canvas.drawRect(0f, middle - half, width.toFloat(), middle + half, ink)
            ink.color = palette.accent
            ink.alpha = 255
            val travelled = width * fraction
            canvas.drawRect(0f, middle - half, travelled, middle + half, ink)
            // A handle only while it is being moved: at rest the bar is a reading, and a
            // grip drawn on it permanently is a promise the streams cannot keep.
            if (held) canvas.drawCircle(travelled, middle, dpf(SCRUB_GRIP_DP), ink)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!seekable) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The panel above claims vertical drags to put itself away, and the
                    // scroll below claims what is left. Neither may have this one.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    held = true
                    moveTo(event.x)
                    return true
                }
                MotionEvent.ACTION_MOVE -> if (held) {
                    moveTo(event.x)
                    return true
                }
                MotionEvent.ACTION_UP -> if (held) {
                    held = false
                    moveTo(event.x)
                    Haptics.tap(this)
                    onSeek?.invoke(fraction)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> if (held) {
                    // Whatever took the gesture away, the app's own position is the truth
                    // again and the next tick will put it back.
                    held = false
                    invalidate()
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun moveTo(x: Float) {
            if (width <= 0) return
            fraction = (x / width).coerceIn(0f, 1f)
            invalidate()
        }
    }

    /**
     * Hands the panel every media session that is alive, and it draws at most one of them.
     *
     * Which one: whatever is playing, and where two things are playing at once - a podcast
     * paused behind a video, the phone's own music behind a game - the one already on the
     * panel keeps its place. A player that changes under the user's thumb between one
     * two-second tick and the next is a player whose buttons cannot be trusted.
     */
    fun setMedia(sessions: Collection<MediaSessions.Info>) {
        val current = playing?.packageName
        val chosen = sessions.firstOrNull { it.isPlaying && it.packageName == current }
            ?: sessions.firstOrNull { it.isPlaying }
            ?: sessions.firstOrNull { it.packageName == current }
            ?: sessions.firstOrNull()
        // Same track, same state, same position as last reported: the ticker is already
        // moving the bar along and there is nothing here to redraw.
        if (chosen == playing) return
        val was = current
        playing = chosen
        miniPlayer.bind(chosen)
        if (open) setMediaTicking(chosen?.isPlaying == true)
        // The list leaves out the notification belonging to whatever is drawn up here, so
        // which app that is is part of what the list is. See [rebuild].
        if (chosen?.packageName != was) {
            drawnSignature = null
            if (open) rebuild()
        }
    }

    /**
     * The player's own second hand.
     *
     * Only while the panel is down and something is actually playing: a session reports a
     * position and the moment it was taken, not a running clock, so the bar between reports
     * is this view's arithmetic. See [MediaSessions.Info.currentPositionMs].
     */
    private val mediaTick = object : Runnable {
        override fun run() {
            miniPlayer.tick()
            postDelayed(this, MEDIA_TICK_MS)
        }
    }

    private fun setMediaTicking(on: Boolean) {
        removeCallbacks(mediaTick)
        if (on) postDelayed(mediaTick, MEDIA_TICK_MS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(mediaTick)
    }

    /** m:ss, and h:mm:ss for anything past the hour. */
    private fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        val hours = seconds / 3600
        return if (hours > 0) String.format(
            Locale.US, "%d:%02d:%02d", hours, (seconds / 60) % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    // ---------------------------------------------------------------- the two commands

    /**
     * "clear all" and "all settings": a glyph with a word beside it, in the small caps the
     * phone set its commands in.
     */
    private inner class CommandRow(label: String, asset: String) {
        private val icon = ImageView(context)
        private val caption = TextView(context)
        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            isClickable = true
        }

        init {
            icon.setImageDrawable(SvgIcon.fromAsset(context, asset))
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            caption.text = label
            caption.textSize = COMMAND_SP
            caption.typeface = font(R.font.segoeui_regular)
            caption.isAllCaps = true
            caption.includeFontPadding = false
            view.addView(icon, LinearLayout.LayoutParams(dp(21), dp(21)))
            view.addView(caption, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })
            TiltEffect.apply(view)
        }

        fun onTap(action: () -> Unit) {
            view.setOnClickListener {
                Haptics.tap(view)
                action()
            }
        }

        fun paint(color: Int) {
            icon.setColorFilter(color)
            caption.setTextColor(color)
        }
    }

    private fun buildCommands() {
        val row = commandsRow
        spaceCommands(playerShowing = false)
        clearAllRow.onTap {
            // Cleared here as well as through the host, so the list empties as the finger
            // leaves it rather than on whichever of the next two-second ticks the listener
            // has caught up by. What is left behind is what the platform refuses to clear.
            onClearAll?.invoke()
            latest = latest.filterNot { it.clearable }
            drawnSignature = null
            rebuild()
        }
        allSettingsRow.onTap {
            close()
            onAllSettings?.invoke()
        }
        row.addView(clearAllRow.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        // Space, not a View. A plain View does not wrap to nothing: asked for its height
        // with AT_MOST it answers with the whole of what it was offered, so this one grew
        // to the height of the panel, took the row with it, and left the notification list
        // measured at zero with the grabber pushed off the bottom. Space is the widget that
        // exists for exactly this and is the one that reports nothing.
        row.addView(Space(context), LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        row.addView(allSettingsRow.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(row, wide())

        emptyLine.textSize = 15f
        emptyLine.typeface = font(R.font.segoeui_regular)
        emptyLine.gravity = Gravity.CENTER
        emptyLine.setPadding(dp(20), dp(56), dp(20), dp(20))
        emptyLine.text = "no notifications"
    }

    /**
     * How much air sits over the two commands.
     *
     * Less while the player is showing. The scrubber leaves a band of its own under the
     * bar - it is drawn down the middle of a strip wide enough to be caught by a thumb -
     * and that band on top of the panel's own spacing reads as a hole between the two.
     */
    private fun spaceCommands(playerShowing: Boolean) {
        commandsRow.setPadding(dp(14), if (playerShowing) dp(3) else dp(10), dp(14), dp(4))
    }

    // ---------------------------------------------------------------- the grabber

    /**
     * The accent band across the foot of the panel, and the way out of it.
     *
     * The phone put a handle there and let it be dragged back up. Both that and a plain tap
     * close it here, because a band at the bottom of the screen with a grip drawn on it is
     * a thing people tap as readily as they drag - and the drag itself is not this view's
     * any more but the whole panel's, which is what [onInterceptTouchEvent] is for. A
     * twenty-two dp strip on the very bottom edge is the worst target on the screen to have
     * made the only one: it is exactly where Android listens for its own back and home
     * gestures, so half the drags aimed at it were taken by the system before they arrived.
     */
    private fun buildGrabber() {
        grabber.isClickable = true
        grabber.addView(grabberHandle, LayoutParams(dp(42), dp(3), Gravity.CENTER))
        panel.addView(grabber, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(GRABBER_DP)))
        grabber.setOnClickListener {
            Haptics.tap(grabber)
            close()
        }
    }

    // ---------------------------------------------------------------- pushing it back up

    private var dragStartY = 0f
    private var dragStartX = 0f
    private var dragging = false

    /** Whether the gesture began over the notifications rather than over the chrome. */
    private var dragFromList = false

    /**
     * Claims an upward drag that means "put this away".
     *
     * Anywhere on the panel's own black - the strip of quick actions, the two commands, the
     * grabber, and the empty field under the last notification. The one thing it is not is
     * a drag that starts on a notification while there are more of them than fit: pushing
     * up there is how somebody reads the rest of the list, and a panel that closed instead
     * would be unusable. Where the list does not scroll at all there is nothing for it to
     * own, so even the rows hand the drag back. See [overNotifications].
     *
     * Decided on the same event that crosses the slop, which is the only chance there is:
     * the ScrollView asks its parents to stand off the moment it starts scrolling, and a
     * parent that has been told that is not consulted again for the rest of the gesture.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!open) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.y
                dragStartX = ev.x
                dragging = false
                dragFromList = overNotifications(ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging || opening != null) return true
                val dy = ev.y - dragStartY
                val vertical = kotlin.math.abs(dy) > kotlin.math.abs(ev.x - dragStartX)
                if (!vertical) return false

                // Downward, on a row with more behind it: open it out. Taken here rather
                // than on the row itself because the scroll view claims the gesture the
                // moment the finger leaves the slop circle, and a parent is the only thing
                // that gets a say before it does.
                if (dy > touchSlop) {
                    val row = expandableAt(dragStartY)
                    if (row != null && row.beginDrag(opening = true)) {
                        opening = row
                        return true
                    }
                    return false
                }

                if (-dy > touchSlop && !(dragFromList && listScrolls())) {
                    // An opened row closes before the panel does: the gesture that opened
                    // it is the gesture that puts it back, and a panel that vanished
                    // instead would take the thing being read away with it.
                    val row = expandedAt(dragStartY)
                    if (row != null && row.beginDrag(opening = false)) {
                        opening = row
                        return true
                    }
                    dragging = true
                    panel.animate().cancel()
                    return true
                }
            }
        }
        return false
    }

    /**
     * The row a drag has taken hold of to open or close, or null while it has taken hold
     * of nothing.
     */
    private var opening: Row? = null

    /** The row under [y] that has something more to show and is not already showing it. */
    private fun expandableAt(y: Float): Row? =
        rowAt(y)?.takeIf { it.canExpand && !expandedKeys.contains(it.entry.key) }

    /** The row under [y] that is currently opened out. */
    private fun expandedAt(y: Float): Row? =
        rowAt(y)?.takeIf { expandedKeys.contains(it.entry.key) }

    private fun rowAt(y: Float): Row? {
        val onPanel = y - panel.translationY
        if (onPanel < scroll.top || onPanel > scroll.bottom) return null
        val inList = onPanel - scroll.top + scroll.scrollY
        return rows.firstOrNull { inList >= it.view.top && inList <= it.view.bottom }
    }

    /** Whether there is more list than fits, either way. */
    private fun listScrolls(): Boolean =
        scroll.canScrollVertically(1) || scroll.canScrollVertically(-1)

    /**
     * Whether [y] is on a notification rather than on the panel's own black.
     *
     * The scroll view fills the rest of the panel whether or not there is anything in it,
     * so its bounds are the wrong question: below the last row is black, and black is
     * somewhere a drag should be able to take hold of the panel. Only the rows themselves
     * are the list's, and only while there is more of them than fits.
     */
    private fun overNotifications(y: Float): Boolean {
        // Against the panel rather than against this view: a gesture can begin while the
        // panel is still on its way down, and the two are the same only once it has landed.
        val onPanel = y - panel.translationY
        if (onPanel < scroll.top || onPanel > scroll.bottom) return false
        // Where in the list that lands, allowing for how far it has been scrolled.
        return onPanel - scroll.top + scroll.scrollY <= rowsBottom()
    }

    /** The foot of the last real row, ignoring what is only there to hold space. */
    private fun rowsBottom(): Int {
        for (index in list.childCount - 1 downTo 0) {
            val child = list.getChildAt(index)
            if (child === trailingSpace || child === emptyLine) continue
            return child.bottom
        }
        return 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        opening?.let { row ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE ->
                    row.dragBy(kotlin.math.abs(ev.y - dragStartY))
                MotionEvent.ACTION_UP -> {
                    row.endDrag(kotlin.math.abs(ev.y - dragStartY) > dpf(EXPAND_TRAVEL_DP))
                    opening = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    row.endDrag(commit = false)
                    opening = null
                }
            }
            return true
        }
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            // Upward only: the panel already fills the screen, so following a finger
            // downward would only open a band of nothing above it.
            MotionEvent.ACTION_MOVE -> panel.translationY = minOf(0f, ev.y - dragStartY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (dragStartY - ev.y > dpf(CLOSE_TRAVEL_DP)) close()
                else panel.animate().translationY(0f).setDuration(140).start()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- opening and closing

    fun isOpen(): Boolean = open

    /**
     * Brings the panel down over the wall.
     *
     * The list and the strip are built before the slide rather than during it: a panel
     * that arrives empty and fills in as it lands is a panel that flickers, and everything
     * here is already in memory - the notifications from the listener, the rest from
     * settings the platform answers for immediately.
     */
    fun open() {
        if (open) return
        prepare()
        panel.translationY = -travel()
        panel.animate()
            .translationY(0f)
            .setDuration(IN_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /**
     * Everything that has to be true before the panel can be seen, short of moving it.
     *
     * Shared by [open] and [beginPull], which differ only in what moves it afterwards: an
     * animation, or a finger.
     */
    private fun prepare() {
        open = true
        pulling = false
        onOpenChanged?.invoke(true)
        drawnSignature = null
        rebuild()
        refreshChrome()
        // The position only moves while somebody can see it move.
        setMediaTicking(playing?.isPlaying == true)
        scroll.scrollTo(0, 0)
        visibility = VISIBLE
        panel.animate().cancel()
        // The close's listener is still on the animator and would fire on the next thing
        // to end. It is guarded, but an animator carrying a listener for a state it is no
        // longer in is one bug away from putting the panel away as it arrives.
        panel.animate().setListener(null)
    }

    // ------------------------------------------------------------ pulled out by hand

    /** A drag on the strip above is carrying the panel rather than an animation. */
    private var pulling = false

    /**
     * Takes hold of the panel at the top of its travel, ready to be dragged down.
     *
     * This is the gesture the phone opened the Action Center with: the status bar is the
     * handle, and what comes out of it follows the finger the whole way rather than playing
     * an animation once some threshold has been crossed. [pullTo] moves it and [endPull]
     * decides whether it stays.
     */
    fun beginPull() {
        if (open) return
        prepare()
        pulling = true
        panel.translationY = -travel()
    }

    /** Puts the panel at [travelled] pixels of finger, clamped to its own height. */
    fun pullTo(travelled: Float) {
        if (!pulling) return
        panel.translationY = (travelled - travel()).coerceIn(-travel(), 0f)
    }

    /**
     * Lets go: the panel finishes coming down, or goes back where it came from.
     *
     * A share of its own height rather than a fixed distance, so the commit point is in the
     * same place on a small screen as on a large one - and a short one, because somebody
     * who has dragged the bar a fifth of the way down has said what they wanted.
     */
    fun endPull(travelled: Float) {
        if (!pulling) return
        pulling = false
        if (travelled > travel() * OPEN_COMMIT_FRACTION) {
            panel.animate()
                .translationY(0f)
                .setDuration(IN_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            // It never really opened, so this is not a close so much as a withdrawal - but
            // it leaves by the same door and has the same tidying to do.
            close()
        }
    }

    /** Takes it back up the way it came. Safe to call when it is already away. */
    fun close() {
        if (!open) return
        open = false
        // A drag can be cut off by something other than the finger lifting - back, the
        // Start key, an app coming to the front - and a panel left mid-pull would go on
        // answering to a gesture that is no longer about it.
        pulling = false
        // Shut, so the next pull-down opens on what is waiting rather than on wherever the
        // last one was left. See [ongoingOpen].
        ongoingOpen = false
        setMediaTicking(false)
        opening?.endDrag(commit = false)
        opening = null
        dragging = false
        panel.animate().cancel()
        panel.animate()
            .translationY(-travel())
            .setDuration(OUT_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            // A listener rather than withEndAction, which is not run when an animation is
            // cancelled: a close cut short by anything at all must still leave the panel
            // put away, or it stays on the screen - invisible, off the top edge, and over
            // everything the shell draws.
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!open) {
                        visibility = GONE
                        onOpenChanged?.invoke(false)
                    }
                }
            })
            .start()
    }

    /**
     * How far the panel has to move to be off the screen.
     *
     * Its own height once it has been laid out, and the display's before that - the first
     * opening can happen on the same frame the view is added, where the measured height is
     * still zero and a slide of zero pixels is a panel that simply appears.
     */
    private fun travel(): Float {
        height.takeIf { it > 0 }?.let { return it.toFloat() }
        // Never laid out: it has been GONE since it was built, and a GONE child is not
        // measured. Worked out from the shell instead, which has been - the display's own
        // height would be too long by the strip above and the panel would still be off the
        // screen with the finger at the bottom of it.
        val room = (parent as? View)?.height ?: resources.displayMetrics.heightPixels
        val margins = layoutParams as? MarginLayoutParams
        return (room - (margins?.topMargin ?: 0) - (margins?.bottomMargin ?: 0))
            .toFloat().coerceAtLeast(1f)
    }

    /** Back closes it, and nothing else in the shell hears the press. */
    fun handleBack(): Boolean {
        if (!open) return false
        close()
        return true
    }

    // ---------------------------------------------------------------- the list

    /**
     * Hands the panel what is currently posted.
     *
     * Called from the host's two-second notification tick whether or not the panel is
     * down, so it is written to be cheap when nothing has moved: the rows are rebuilt only
     * when the list is actually different, and never at all while the panel is away - what
     * arrives while it is up there is drawn when it next comes down.
     */
    fun setNotifications(entries: List<NotificationListenerService.ShadeEntry>) {
        latest = entries
        // Worked out here rather than in [rebuild], which only runs while the panel is
        // down: the strip above wears these marks whether or not anything is open, so the
        // order has to be settled on every tick.
        val order = entries.groupBy { it.packageName }
            .entries
            .sortedByDescending { group -> group.value.maxOf { it.postedAt } }
            .map { it.key }
        // Marked only for what is actually waiting. An app whose whole contribution is
        // ongoing - the download, the track, the foreground service - stays off the strip:
        // a mark up there is a claim on the user's attention, and those three have been
        // sitting quietly all afternoon without wanting anything.
        val marks = order.filter { app ->
            entries.any { it.packageName == app && !it.ongoing }
        }
        marked = order
        if (marks != markedForStrip) {
            markedForStrip = marks
            onMarkedAppsChanged?.invoke(marks)
        }
        if (open) rebuild()
    }

    /** The mark for [packageName], as the panel and the status strip both draw it. */
    fun markFor(packageName: String): MonochromeIconProvider.Glyph? =
        marks.getOrPut(packageName) {
            val fallback: Drawable? = try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                null
            }
            iconProvider.glyphFor(packageName, fallback, isTile = true)
        }

    /**
     * Says whether the listener behind the list is switched on.
     *
     * Signature-checked like the rest of the list would be, except that the signature of an
     * empty list is the same either way - so this clears it by hand, or the page would go
     * on saying "no notifications" after access had been granted.
     */
    fun setNotificationAccess(granted: Boolean) {
        if (granted == hasAccess) return
        hasAccess = granted
        drawnSignature = null
        if (open) rebuild()
    }

    /**
     * Re-reads the four quick actions.
     *
     * Only while the panel is down: each is a settings read or an IPC, and asking what the
     * radios are doing every two seconds for a panel that is off the top of the screen is
     * work for nobody. The status bar above is not part of this - it is always on screen
     * and keeps its own cadence. See WP81StatusBar.refresh.
     */
    fun refreshChrome() {
        if (!open) return
        for (action in quickActions) action.refresh()
    }

    /**
     * What the list is, reduced to a string.
     *
     * The tick runs every two seconds and the list is usually the same list; rebuilding it
     * anyway would throw away the scroll position, the row a finger is halfway through
     * swiping and the press state of whatever is under it, thirty times a minute.
     */
    private fun signatureOf(entries: List<NotificationListenerService.ShadeEntry>): String =
        // The ongoing flag among the rest of it: a download that finishes is the same
        // notification under the same key, and it has to move up out of the ongoing
        // section when it does.
        entries.joinToString(" ") { "${it.key}|${it.title}|${it.text}|${it.ongoing}" }

    private fun rebuild() {
        val signature = signatureOf(latest)
        if (signature == drawnSignature) return
        drawnSignature = signature
        list.removeAllViews()
        rows.clear()
        // Anything still open that is no longer posted is forgotten here, so the set does
        // not grow for the life of the launcher.
        expandedKeys.retainAll(latest.map { it.key }.toSet())

        // What the mini player is already showing is not listed underneath it: the track
        // is drawn up there with its cover and its buttons, and a row saying the same
        // thing is the same track twice. Only that app's, and only its player - a download
        // from the same app is still news. See [setMedia].
        val listed = latest.filterNot {
            it.media && it.packageName == playing?.packageName
        }

        if (listed.isEmpty()) {
            // Nothing waiting, or nothing being passed on. The second is a thing the user
            // can fix and the first is not, so only the second is offered as a tap.
            emptyLine.text =
                if (hasAccess) "no notifications"
                else "notification access is off\n\ntap to let this launcher see them"
            emptyLine.isClickable = !hasAccess
            emptyLine.setOnClickListener(
                if (hasAccess) null
                else View.OnClickListener {
                    Haptics.tap(emptyLine)
                    close()
                    onNotificationAccess?.invoke()
                }
            )
            list.addView(emptyLine, wide())
            return
        }

        // Two lists rather than one: what is waiting, and what is merely running. A
        // download in progress, a playing track, a foreground service - none of them is
        // news, and standing them among the news is what makes the news hard to find. They
        // keep their headings and their order, under a rule of their own at the foot.
        val waiting = listed.filter { !it.ongoing }
        val running = listed.filter { it.ongoing }

        drawSection(waiting)
        if (running.isNotEmpty()) {
            list.addView(ongoingRule(running.size), wide())
            // Smaller down here, headings and rows both: none of it is being read so much
            // as glanced at, and a foot of the panel set at the same size as the news
            // above it is a download taking up as much room as a message. Not drawn at all
            // while the section is shut, which is how it starts.
            if (ongoingOpen) drawSection(running, compact = true)
        }
        list.addView(trailingSpace, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(12)))
    }

    /**
     * One stretch of the list: each app's heading and the rows under it.
     *
     * App by app in the order settled by [setNotifications] - which of them said something
     * last, so an app that has just spoken comes to the top rather than staying wherever
     * the package name sorted it. An app's rows are together and each wears its mark;
     * there is no heading over them, the mark being the whole of what one said. An app
     * with something waiting *and* something running appears in both stretches.
     */
    private fun drawSection(
        entries: List<NotificationListenerService.ShadeEntry>,
        compact: Boolean = false
    ) {
        val groups = entries.groupBy { it.packageName }
        for (packageName in marked) {
            val group = groups[packageName] ?: continue
            for (entry in group.sortedByDescending { it.postedAt }) {
                val row = Row(entry, compact)
                rows += row
                list.addView(row.view, wide())
            }
        }
    }

    /**
     * The rule the ongoing sit under, the word that says what they are, and the tap that
     * shows them.
     *
     * A count on the heading, so a shut section still says how much is behind it - the
     * same "(4)" a group of notifications wears, and for the same reason: it is the one
     * thing somebody wants to know without opening anything. See [ongoingOpen].
     */
    private fun ongoingRule(count: Int): View {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(2))
            isClickable = true
        }
        val rule = View(context).apply { setBackgroundColor(palette.foregroundSubtle) }
        block.addView(rule, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, dp(1))))

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val caption = TextView(context).apply {
            text = "ongoing" + if (count > 1) "  ($count)" else ""
            textSize = BODY_SP
            typeface = font(R.font.segoeui_semilight)
            setTextColor(palette.foregroundSubtle)
            includeFontPadding = false
            maxLines = 1
        }
        head.addView(caption, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // The same two marks a row opens on, so the gesture reads the same wherever it is
        // met: the one that points down opens, the one that points up folds it away.
        head.addView(ImageView(context).apply {
            setImageDrawable(SvgIcon.fromAsset(
                context,
                if (ongoingOpen) "$ICONS/appbar.chevron.up.svg"
                else "$ICONS/appbar.chevron.down.svg"))
            setColorFilter(palette.foregroundSubtle)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(CHEVRON_DP), dp(CHEVRON_DP)))
        block.addView(head, wide())

        TiltEffect.apply(block)
        block.setOnClickListener {
            Haptics.tap(block)
            ongoingOpen = !ongoingOpen
            // The list is the same list; what has changed is how much of it is drawn.
            drawnSignature = null
            rebuild()
        }
        return block
    }

    /**
     * Puts one app's mark on its square at the same optical size as every other.
     *
     * The same treatment the app list gives its rows, and it is needed here for the same
     * reason: nothing about the source can be taken on trust. A themed layer keeps the
     * adaptive-icon safe zone, a notification silhouette fills its bounds, this shell's own
     * glyphs cover about half of theirs - so laying them all out at the size of their own
     * canvas gives a column where one heading's mark is twice the next one's. What is
     * placed instead is the *ink*: its longer side scaled to [MARK_GLYPH_DP] and its centre
     * put at the square's centre, whatever padding it arrived wrapped in.
     *
     * By matrix rather than by padding, because a glyph covering a seventh of its canvas
     * would need a canvas seven times the square to show at the right size, and no amount
     * of padding can give it one. What hangs over the edge is that artwork's own margin and
     * the square clips it. See AppListView.placeGlyph, which this follows.
     */
    private fun drawMark(
        mark: ImageView,
        box: FrameLayout,
        packageName: String,
        boxDp: Int = MARK_DP,
        glyphDp: Int = MARK_GLYPH_DP
    ) {
        when (val glyph = markFor(packageName)) {
            is MonochromeIconProvider.Glyph.Monochrome -> {
                // The tile look: a square of the accent with a white silhouette on it,
                // which is what an app's mark is everywhere else in this shell.
                box.setBackgroundColor(palette.accent)
                mark.setImageDrawable(glyph.drawable)
                mark.setColorFilter(palette.onAccent())
                iconProvider.placeInk(
                    mark, glyph.drawable, "ink:$packageName",
                    dp(boxDp), dp(glyphDp))
            }
            is MonochromeIconProvider.Glyph.FullColor -> {
                mark.setImageDrawable(glyph.drawable)
                mark.clearColorFilter()
                mark.scaleType = ImageView.ScaleType.FIT_CENTER
                // A pack's artwork keeps the square it replaced a mark on; an app's own
                // icon is a picture and stands on nothing.
                if (glyph.fromPack) box.setBackgroundColor(palette.accent)
                else box.background = null
            }
            null -> box.setBackgroundColor(palette.accent)
        }
    }

    /**
     * A line of two, where the second stays against the first.
     *
     * A weighted row does the opposite: the first child takes everything left over and
     * the second rides the far edge, which is the column down the right this arrangement
     * was meant to be rid of. Weight is not the answer either way round - what is wanted
     * is the first child measured to its own text, and shortened only where there is not
     * room for both - so [first] is capped here at whatever the line has left once
     * [second] has had its share, and its own ellipsis does the rest.
     */
    private inner class TightLine(
        private val first: TextView,
        private val second: View
    ) : LinearLayout(context) {

        init {
            orientation = HORIZONTAL
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val room = MeasureSpec.getSize(widthSpec) - paddingStart - paddingEnd
            var reserved = 0
            if (second.visibility != GONE) {
                measureChild(
                    second,
                    MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST),
                    heightSpec)
                reserved = second.measuredWidth +
                    ((second.layoutParams as? MarginLayoutParams)?.marginStart ?: 0)
            }
            val cap = (room - reserved).coerceAtLeast(0)
            // Only where it has moved: setting it asks for another layout, and setting it
            // to what it already is on every pass is a row that measures itself forever.
            if (first.maxWidth != cap) first.maxWidth = cap
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    /**
     * One notification, as a row: whose it is, what it says, and when.
     *
     * ```
     *   [#]  3Alerts  · Tue          v    the app's mark, then the notification's own
     *        From Three: thanks for...        title, the time after a dot, and the text
     * ```
     *
     * The app's mark and nothing else stands for the app - no name over a group of rows,
     * which cost a heading's worth of panel per app to say what the square already says.
     * Rows of the same app are still drawn together; see [drawSection].
     *
     * Tapping it sends it where the shade would send it. Swiping it sideways retires that
     * one notification - but only where the posting app allows it: a running download or a
     * playing track is not clearable and the row simply does not move. Dragging it down
     * opens it out; see [expandableAt].
     */
    private inner class Row(
        val entry: NotificationListenerService.ShadeEntry,
        /** The ongoing section's rows, drawn a size down. See [drawSection]. */
        private val compact: Boolean = false
    ) {

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The mark starts at the panel's own margin and the words follow it, so a row
            // is the whole of one notification across the whole of the width.
            if (compact) setPadding(dp(20), dp(4), dp(20), dp(4))
            else setPadding(dp(20), dp(7), dp(20), dp(7))
            isClickable = true
        }

        /** How large this row's mark is drawn, and the ink on it. See [drawMark]. */
        private val markDp = if (compact) COMPACT_MARK_DP else MARK_DP
        private val glyphDp = if (compact) COMPACT_MARK_GLYPH_DP else MARK_GLYPH_DP

        private val body: TextView?
        private val picture: ImageView?

        /**
         * The mark that says this row has more behind it, and the way to it for anyone who
         * is not going to discover the drag.
         *
         * Only on rows that have somewhere to go - see [canExpand] - so it is a promise
         * rather than decoration: every chevron on the panel opens something.
         */
        private val chevron: ImageView?

        /**
         * Whether there is anything behind this row worth opening it for.
         *
         * A row whose whole content is already on screen must not answer to the gesture:
         * an opened row that looks exactly like the closed one reads as the drag having
         * failed rather than as there being nothing more.
         */
        val canExpand: Boolean =
            entry.image != null || entry.fullText.trim() != entry.text.trim()

        init {
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            // Held to the top rather than centred: an opened row is several lines tall and
            // a mark floating half way down it has come loose from the line it belongs to.
            val markBox = FrameLayout(context)
            val mark = ImageView(context)
            markBox.addView(mark, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            drawMark(mark, markBox, entry.packageName, markDp, glyphDp)
            head.addView(markBox, LinearLayout.LayoutParams(dp(markDp), dp(markDp))
                .apply { gravity = Gravity.TOP })

            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(context).apply {
                // "(3)" where this one notification is carrying three messages. Without it
                // a conversation with three unread messages and one with a single message
                // are the same row, and the only way to tell was to open it.
                text = entry.title.ifEmpty { entry.text } +
                    if (entry.messageCount > 1) "  (${entry.messageCount})" else ""
                textSize = if (compact) COMPACT_TITLE_SP else TITLE_SP
                typeface = font(R.font.segoeui_semibold)
                setTextColor(palette.foreground)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            // The time sits against the title, after a dot, rather than in a column of its
            // own down the right: a column costs the width of "16:39" on every row of the
            // panel, and what it buys is a time nobody is reading in a straight line.
            // Its own view rather than more of the title's text, so a long title
            // ellipsizes and the time survives being crowded - see [TightLine], which is
            // what keeps the two of them together.
            val stamp = TextView(context).apply {
                text = stampFor(entry.postedAt).let { if (it.isEmpty()) "" else "\u00b7  $it" }
                textSize = if (compact) COMPACT_BODY_SP else BODY_SP
                typeface = font(R.font.segoeui_regular)
                setTextColor(palette.foregroundSubtle)
                maxLines = 1
                includeFontPadding = false
                visibility = if (text.isEmpty()) GONE else VISIBLE
            }
            val heading = TightLine(title, stamp)
            heading.addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            heading.addView(stamp, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) })
            words.addView(heading, wide())

            // Only where there is a second thing to say. A notification that is all title -
            // "Missed call" - should not leave an empty line under itself.
            body = if (entry.title.isNotEmpty() && entry.text.isNotEmpty()) {
                TextView(context).apply {
                    textSize = if (compact) COMPACT_BODY_SP else BODY_SP
                    typeface = font(R.font.segoeui_regular)
                    setTextColor(palette.foregroundSubtle)
                    setPadding(0, dp(2), 0, 0)
                    includeFontPadding = false
                }.also { words.addView(it, wide()) }
            } else null

            head.addView(words, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(if (compact) 8 else 12) })

            // On the title's line at the right-hand end, the column it used to share with
            // the time having gone with the time. Held to the top for the same reason the
            // mark is: an opened row grows downwards and the thing that folds it back up
            // should stay where the finger last saw it.
            chevron = if (canExpand) {
                ImageView(context).apply {
                    setColorFilter(palette.foregroundSubtle)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    // Padding rather than size: the mark is small and a small mark is a
                    // small thing to hit, so the target around it is the thumb's business
                    // and the drawing inside it is the eye's. All of it below and beside
                    // the glyph, which puts the glyph itself on the line of the words.
                    setPadding(dp(8), 0, 0, dp(6))
                    TiltEffect.apply(this)
                    setOnClickListener { toggle() }
                }.also {
                    head.addView(it, LinearLayout.LayoutParams(
                        dp(CHEVRON_DP) + dp(8), dp(CHEVRON_DP) + dp(6)
                    ).apply { gravity = Gravity.TOP })
                }
            } else null

            view.addView(head, wide())

            // Under the words rather than under the whole row, so an opened row's picture
            // begins where its text begins instead of reaching back under the mark.
            picture = entry.image?.let { bitmap ->
                ImageView(context).apply {
                    setImageBitmap(bitmap)
                    // Its own proportions, at whatever width the panel has: a photograph
                    // squared off to a fixed box is a photograph with its edges cut off.
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    maxHeight = dp(PICTURE_MAX_DP)
                    visibility = GONE
                }.also {
                    words.addView(it, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) })
                }
            }

            apply(expandedKeys.contains(entry.key))

            // The swipe is handed to the tilt rather than set beside it: a view has one
            // touch listener, and applying the tilt afterwards would quietly throw the
            // swipe away. See TiltEffect.apply.
            val swipe = if (entry.clearable) SwipeToDismiss(view, entry) else null
            TiltEffect.apply(view) { _, event -> swipe?.onTouch(event) ?: false }
            view.setOnClickListener {
                Haptics.tap(view)
                close()
                onOpenNotification?.invoke(entry)
            }
        }

        /** Shows the whole of it, or as much of it as one line holds. */
        fun apply(expanded: Boolean) {
            body?.let {
                it.text = if (expanded) entry.fullText else entry.text
                it.maxLines = if (expanded) EXPANDED_LINES else 1
                it.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
            }
            picture?.visibility = if (expanded) VISIBLE else GONE
            // Two marks rather than one turned over: a chevron that spins is an animation
            // about itself, and what is wanted is the answer to "which way does this go
            // now". It changes on the touch, before the row has finished moving.
            chevron?.setImageDrawable(SvgIcon.fromAsset(
                context,
                if (expanded) "$ICONS/appbar.chevron.up.svg"
                else "$ICONS/appbar.chevron.down.svg"
            ))
        }

        /**
         * Opens the row, or folds it, from a tap on the chevron.
         *
         * Through the same machinery the drag uses rather than a straight swap, so a tap
         * and a drag arrive at the same place the same way: both measure the two ends, and
         * both travel between them. [endDrag] does the rest.
         */
        fun toggle() {
            val opening = !expandedKeys.contains(entry.key)
            if (beginDrag(opening)) endDrag(commit = true)
        }

        // ------------------------------------------------ opened under the finger

        /** The two ends of this row's travel, in pixels, while it is being dragged. */
        private var shutHeight = 0
        private var openHeight = 0
        private var draggingOpen = false

        /**
         * Takes hold of the row for a drag, and says whether there is a drag to be had.
         *
         * Both ends are measured up front and the content is then left in its *open* state
         * for the whole gesture, with the row's own height doing the showing: the finger
         * uncovers what is already there rather than the row growing text a line at a time,
         * which is what makes it read as one movement instead of a jump.
         *
         * The row clips what will not fit, as any view group does with its children, so the
         * part still to come is simply not drawn yet.
         */
        fun beginDrag(opening: Boolean): Boolean {
            if (!canExpand || view.width <= 0) return false
            apply(false)
            shutHeight = measureSelf()
            apply(true)
            openHeight = measureSelf()
            if (openHeight <= shutHeight) {
                apply(expandedKeys.contains(entry.key))
                return false
            }
            draggingOpen = opening
            lockHeight(if (opening) shutHeight else openHeight)
            return true
        }

        /** Follows the finger, [travelled] pixels from where the drag began. */
        fun dragBy(travelled: Float) {
            if (openHeight <= shutHeight) return
            val height = if (draggingOpen) shutHeight + travelled else openHeight - travelled
            lockHeight(height.toInt().coerceIn(shutHeight, openHeight))
        }

        /**
         * Lets go. [commit] is whether the finger went far enough to mean it; either way the
         * row finishes the journey it was on rather than snapping, and hands its height back
         * to the layout once it has arrived.
         */
        fun endDrag(commit: Boolean) {
            val expanded = if (commit) draggingOpen else !draggingOpen
            if (expanded) expandedKeys.add(entry.key) else expandedKeys.remove(entry.key)
            if (commit) Haptics.tap(view)
            chevron?.setImageDrawable(SvgIcon.fromAsset(
                context,
                if (expanded) "$ICONS/appbar.chevron.up.svg"
                else "$ICONS/appbar.chevron.down.svg"
            ))
            val from = view.layoutParams?.height?.takeIf { it > 0 } ?: view.height
            val to = if (expanded) openHeight else shutHeight
            android.animation.ValueAnimator.ofInt(from, to).apply {
                duration = SETTLE_MS
                addUpdateListener { lockHeight(it.animatedValue as Int) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        apply(expanded)
                        unlockHeight()
                    }

                })
                start()
            }
        }

        /** How tall this row wants to be as it currently stands, at the width it has. */
        private fun measureSelf(): Int {
            view.measure(
                MeasureSpec.makeMeasureSpec(view.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            return view.measuredHeight
        }

        private fun lockHeight(px: Int) {
            val params = view.layoutParams ?: return
            if (params.height == px) return
            params.height = px
            view.layoutParams = params
        }

        private fun unlockHeight() {
            val params = view.layoutParams ?: return
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT
            view.layoutParams = params
        }
    }

    /**
     * A sideways drag takes one notification off the list.
     *
     * The gesture has to be taken off the scroll view to work at all - a row inside a
     * ScrollView never sees a second MOVE otherwise, because the scroll claims the stream
     * the moment the finger leaves the slop circle. So the parent is asked to stand off as
     * soon as the drag is clearly horizontal, and only then; a drag that turns out to be
     * vertical is still the list's.
     *
     * The row's own animator is left alone throughout. The tilt owns it - it cancels and
     * re-aims it on every press and release - so a dismissal driven through the same
     * animator would be cancelled halfway across by the finger lifting, leaving the row
     * stranded off-centre and still in the list. A ValueAnimator of its own cannot be.
     */
    private inner class SwipeToDismiss(
        private val row: View,
        private val entry: NotificationListenerService.ShadeEntry
    ) {
        private var startX = 0f
        private var startY = 0f
        private var swiping = false
        private var gone = false

        /** @return whether this gesture now belongs to the swipe rather than to the row. */
        fun onTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    swiping = false
                    gone = false
                }
                MotionEvent.ACTION_MOVE -> if (!gone) {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!swiping &&
                        kotlin.math.abs(dx) > touchSlop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    ) {
                        swiping = true
                        row.isPressed = false
                        row.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        // Flat while it travels: a row that is both tilted into the screen
                        // and sliding out of it is two gestures at once. The tilt has
                        // already started its animation for this event by the time this
                        // runs - it is handed the event first, see the call site - so it
                        // is cancelled here before it has had a frame to draw in.
                        row.animate().cancel()
                        row.rotationX = 0f
                        row.rotationY = 0f
                        row.scaleX = 1f
                        row.scaleY = 1f
                        row.translationX = dx
                        // Fading as it goes, so a row half way across reads as on its way
                        // out rather than merely displaced.
                        val width = row.width.takeIf { it > 0 } ?: 1
                        row.alpha = 1f - (kotlin.math.abs(dx) / width).coerceIn(0f, 0.85f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping && !gone) {
                    val dx = event.rawX - startX
                    if (kotlin.math.abs(dx) > row.width * DISMISS_FRACTION) {
                        gone = true
                        slide(dx, if (dx > 0) row.width.toFloat() else -row.width.toFloat()) {
                            onDismissNotification?.invoke(entry)
                            // Taken out of the list here as well as at the source: the
                            // listener will report it gone on its own, but not for up to
                            // two seconds, and a row that stays put after being flicked
                            // away is a row the gesture did not work on.
                            latest = latest.filterNot { it.key == entry.key }
                            drawnSignature = null
                            rebuild()
                        }
                    } else {
                        slide(dx, 0f) {}
                    }
                }
            }
            // Claimed only once it is a swipe, which keeps it clear of the click listener:
            // a notification flicked off the list must not also open.
            return swiping
        }

        private fun slide(from: Float, to: Float, done: () -> Unit) {
            val width = row.width.takeIf { it > 0 } ?: 1
            android.animation.ValueAnimator.ofFloat(from, to).apply {
                duration = DISMISS_MS
                addUpdateListener {
                    val x = it.animatedValue as Float
                    row.translationX = x
                    row.alpha = 1f - (kotlin.math.abs(x) / width).coerceIn(0f, 1f)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = done()
                })
                start()
            }
        }
    }

    /**
     * When a notification arrived, written the way the phone wrote it: the time if it was
     * today, the day if it was this week, and the date if it was longer ago than that.
     */
    private fun stampFor(postedAt: Long): String {
        if (postedAt <= 0L) return ""
        val then = Calendar.getInstance().apply { timeInMillis = postedAt }
        val now = Calendar.getInstance()
        val age = now.timeInMillis - postedAt
        // Inside the hour, how long ago rather than when: "9m" is the answer to the
        // question somebody is actually asking of something that has just arrived, where
        // "19:47" sitting beside a clock reading 19:53 is arithmetic they have to do
        // themselves. Under a minute has no number worth printing and says so.
        if (age in 0 until HOUR_MS) {
            val minutes = age / 60_000L
            return if (minutes < 1L) "now" else "${minutes}m"
        }
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return wp81TimeFormat(context).format(then.time)
        if (age in 0 until WEEK_MS) {
            return SimpleDateFormat("EEE", Locale.getDefault()).format(then.time)
        }
        return SimpleDateFormat("dd/MM", Locale.getDefault()).format(then.time)
    }

    /**
     * Forgets the marks this panel has cached.
     *
     * An app installed, removed or re-skinned by an icon pack is one whose rows are now
     * wearing the wrong mark, and the cache above would otherwise hold the old answer
     * until the launcher was restarted.
     */
    fun invalidateApps() {
        marks.clear()
        drawnSignature = null
        if (open) rebuild()
    }

    // ---------------------------------------------------------------- colours

    fun applyPalette(p: WP81Palette) {
        palette = p
        panel.setBackgroundColor(p.background)
        clearAllRow.paint(p.foreground)
        allSettingsRow.paint(p.foreground)
        emptyLine.setTextColor(p.foregroundSubtle)
        grabber.setBackgroundColor(p.accent)
        grabberHandle.setBackgroundColor(p.onAccent())
        for (action in quickActions) action.refresh(force = true)
        // Its marks are squares of the accent like the headings' are, so it is bound again
        // rather than repainted: [marks] is cleared below and the next bind re-reads it.
        miniPlayer.paint()
        // The rows carry the palette in their text colours and their marks are squares of
        // the accent, so both are built again rather than repainted one label at a time.
        marks.clear()
        miniPlayer.bind(playing)
        drawnSignature = null
        rebuild()
    }

    companion object {
        private const val TAG = "WP81ActionCenter"

        /** Where the phone's own icon set lives, spelled as everything else spells it. */
        private const val ICONS = "custom_icons_8"

        private const val STATUS_SP = 11.5f
        private const val QUICK_LABEL_SP = 12.5f
        private const val COMMAND_SP = 11.5f
        private const val TITLE_SP = 15f
        private const val BODY_SP = 13.5f

        /**
         * The same two, for the ongoing section at the foot of the panel.
         *
         * A download that is running and a track that is playing are not being read, they
         * are being checked on - so they are set to be glanced at, and the room they give
         * back goes to the notifications above them that are actually waiting for someone.
         */
        private const val COMPACT_TITLE_SP = 13f
        private const val COMPACT_BODY_SP = 12f

        private const val QUICK_HEIGHT_DP = 72
        private const val MARK_DP = 30

        /** The cover on the mini player, and how much of that square its fallback mark fills. */
        private const val ART_DP = 64
        private const val ART_GLYPH_DP = 36

        /** The app's mark beside the track, and the ink on it. See [MARK_GLYPH_DP]. */
        private const val BADGE_DP = 18
        private const val BADGE_GLYPH_DP = 10

        /** A transport key's target. The drawing inside it is smaller by its padding. */
        private const val TRANSPORT_DP = 35

        /** The scrubber: the line itself, the strip a thumb gets, and the grip while held. */
        private const val SCRUB_DP = 3f
        private const val SCRUB_TARGET_DP = 12
        private const val SCRUB_GRIP_DP = 5f

        /**
         * How often the player's position is worked out again while the panel is down.
         *
         * Half a second rather than a whole one: the seconds shown would otherwise skip
         * one in two, which is a clock that visibly stutters.
         */
        private const val MEDIA_TICK_MS = 500L

        /**
         * How large the *visible* mark on a heading's square is, whatever it was drawn on.
         *
         * The app list's proportion - twenty-four of its forty-two - carried over to this
         * square, so a mark is the same share of its ground in both places. See [placeInk].
         */
        private const val MARK_GLYPH_DP = 17

        /** The heading's mark in the ongoing section, and the ink on it. See [MARK_DP]. */
        private const val COMPACT_MARK_DP = 20
        private const val COMPACT_MARK_GLYPH_DP = 12

        /** How tall an opened row's picture may get before it is held back. */
        private const val PICTURE_MAX_DP = 220

        /**
         * How many lines an opened row will show.
         *
         * A limit rather than none at all: a notification carrying a whole mail, or a
         * conversation forty messages long, would otherwise be a row taller than several
         * screens with everything under it pushed out of reach.
         */
        private const val EXPANDED_LINES = 12
        private const val GRABBER_DP = 22

        /** How far the panel has to be pushed up before letting go closes it. */
        private const val CLOSE_TRAVEL_DP = 40f

        /** How far a row has to be dragged before letting go turns it over. */
        private const val EXPAND_TRAVEL_DP = 20f

        /** How long a row takes to finish opening or closing once the finger has gone. */
        private const val SETTLE_MS = 130L

        /** How large the mark that opens a row is drawn. */
        private const val CHEVRON_DP = 20

        /** How much of its own height the panel must be pulled down to stay down. */
        private const val OPEN_COMMIT_FRACTION = 0.2f

        /** How much of its own width a row must cross to be dismissed, not sprung back. */
        private const val DISMISS_FRACTION = 0.3f

        /** How long a row takes to finish crossing, or to spring back. */
        private const val DISMISS_MS = 150L

        private const val HOUR_MS = 60L * 60 * 1000
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        private const val IN_MS = 260L
        private const val OUT_MS = 190L
    }
}
