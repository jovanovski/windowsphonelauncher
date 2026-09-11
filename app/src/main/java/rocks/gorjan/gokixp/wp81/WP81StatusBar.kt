package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Windows Phone status bar: the strip along the top of the shell, above everything.
 *
 * ```
 *   +--------------------------------+
 *   |  (Android's own status bar)    |
 *   +--------------------------------+
 *   |  ..iI  H+          [#]   18:30 |   always on screen
 *   +--------------------------------+
 *   |  the wall, the app list, ...   |
 *
 *   ... and once the Action Center is pulled out of it:
 *
 *   |  ..iI  H+          [#]   18:30 |
 *   |  3                   86% 16/04 |   the second line, revealed
 *   +--------------------------------+
 * ```
 *
 * Two lines, because the phone's was two: the top one is the bar itself - signal, bearer,
 * whichever radios are worth a mark, battery, clock - and the second is what pulling the
 * bar down added, the carrier on the
 * left and the exact charge with the date on the right. The second line is only ever seen
 * while the Action Center is out, which is the whole reason the phone's bar was worth
 * pulling at all: the bar itself never showed a percentage. See [setExpanded].
 *
 * It is the Action Center's header and stays put when the panel slides away behind it -
 * which is why the two share a setting rather than having one each. See
 * `WP81Settings.getWP81ActionCenter`.
 *
 * **What it costs to be right.** Everything up here is read from the platform, and two of
 * the readings are not cheap: the signal strength and the bearer are IPC into telephony.
 * The strip is refreshed from the shell's two-second notification tick, so those two are
 * throttled to [SLOW_MS] and only the clock is re-read every time - see [refresh]. Nothing
 * here is observed with a listener, which would be the other way of doing it and would mean
 * a callback registered for the life of the launcher to keep a line of text current.
 */
@SuppressLint("ViewConstructor")
class WP81StatusBar(
    context: Context,
    private var palette: WP81Palette,
    private val iconProvider: MonochromeIconProvider
) : LinearLayout(context) {

    private val signalBars = SignalBars(context)
    private val networkType = TextView(context)
    private val carrier = TextView(context)
    private val batteryGlyph = BatteryGlyph(context)

    /**
     * The two radios that get a mark of their own: bluetooth with something on the end of
     * it, and flight mode.
     *
     * Between the bearer and the notification marks, which is where the phone put them and
     * is the order they read in: the left of the strip works outwards from the network, and
     * these are two more facts about it before it turns into what is waiting for you.
     *
     * Off is GONE rather than faint, so the marks behind them close up: a row with a gap in
     * it where a radio would have been is a row that has to be counted rather than glanced
     * at. See [radioMark], and [refresh] for when they are read.
     */
    private val bluetoothMark = radioMark("$ICON_DIR/appbar.connection.bluetooth.svg")
    private val airplaneMark = radioMark("$ICON_DIR/appbar.plane.rotated.45.svg")

    /**
     * The marks of the apps with something waiting, in the order the Action Center lists
     * them - so the strip says what is behind it before it has been pulled.
     *
     * This is the one thing the phone's status bar did that its two lines of text did not:
     * a row of silhouettes after the bearer, one per app, which is how you knew there was
     * anything to come down for. Fed by the panel, which is what settles the order. See
     * [setNotificationMarks].
     */
    private val marks = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val clock = TextView(context)
    private val chargeAndDate = TextView(context)

    /** When the readings that are not the clock were last taken. See [refresh]. */
    private var slowReadAt = 0L

    private var expanded = false

    /**
     * A drag begun on the strip, which is how the Action Center is pulled out of it.
     *
     * The strip reports the gesture and nothing more - it does not know what is behind it,
     * and the panel is not its to open. [onPullStart] is handed the travel at the moment
     * the drag became one, signed: positive is downward, which is a panel being pulled
     * out, and negative is upward, which is one being pushed back. The host decides which
     * of those means anything where the user currently is. See `MainActivity.wireWP81ActionCenter`.
     */
    var onPullStart: ((Float) -> Unit)? = null

    /** How far the finger has travelled since the drag began, signed as above. */
    var onPullMove: ((Float) -> Unit)? = null

    /** The finger has lifted, at this travel. */
    var onPullEnd: ((Float) -> Unit)? = null

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var pullStartY = 0f
    private var pulling = false

    /** Extra at each end so the strip's content clears the display's corners. */
    private var cornerInset = 0

    /**
     * The band at the top of the strip the camera is punched through, if any.
     *
     * The shell decides it - it is the shell that has to reserve the height - and hands it
     * down because it is also what decides where inside the strip the first line goes. See
     * [contentTopPx] and `WP81Shell.setStatusBarTopInset`.
     */
    private var cameraBand = 0

    init {
        orientation = HORIZONTAL
        // Placed by hand from the top rather than centred in the band. It comes out in the
        // same place either way while there is one line - beside the camera on a phone with
        // a lens punched through the display, which is where the writing belongs and is why
        // this is not simply pinned to the top edge - but centring balances whatever it is
        // given, so the moment the second line came out the first rose to make room for it
        // and the clock jumped every time the Action Center was pulled. See [contentTopPx].
        gravity = Gravity.TOP
        applyPadding()

        for (label in listOf(networkType, carrier, clock, chargeAndDate)) {
            label.textSize = TEXT_SP
            label.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            label.includeFontPadding = false
            label.maxLines = 1
        }
        // The carrier is the one thing up here with no length limit - "Vodafone IE",
        // "T-Mobile Polska" - so it is the one that gives way when the strip is tight.
        // Everything else is a handful of characters that has to be read whole: a charge
        // cut to "86% 16/0" is worse than no date at all.
        carrier.ellipsize = TextUtils.TruncateAt.END

        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val leftTop = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leftTop.addView(signalBars, LayoutParams(dp(20), dp(13)))
        leftTop.addView(networkType, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        for (mark in listOf(bluetoothMark, airplaneMark)) {
            leftTop.addView(
                mark,
                LayoutParams(dp(MARK_DP), dp(MARK_DP)).apply { marginStart = dp(6) })
        }
        // Every mark carries the gap in front of it, and the row carries a little more, so
        // the first one stands off the bearer by more than the marks stand off each other:
        // the break between what the network is doing and what is waiting for you is worth
        // more than the break between two apps.
        leftTop.addView(marks, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4) })
        left.addView(leftTop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        left.addView(carrier, LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) })

        val right = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
        }
        val rightTop = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightTop.addView(batteryGlyph, LayoutParams(dp(22), dp(13)))
        rightTop.addView(clock, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        right.addView(rightTop, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END })
        chargeAndDate.gravity = Gravity.END
        // Wrapping, not filling. Filling made this column ask for the whole strip, which in
        // a row whose left is the weighted one left the right to be squeezed back to
        // whatever was over - and the date was what fell off the end of it.
        right.addView(chargeAndDate, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.END; topMargin = dp(1) })

        addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(right, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })

        carrier.visibility = GONE
        chargeAndDate.visibility = GONE

        applyPalette(palette)
        refresh(force = true)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * One of the strip's own marks: an icon from the set, hidden until it has something to
     * say, at the same optical size as the notification marks beside it.
     *
     * The ink is placed rather than the drawable simply fitted, because the set is drawn on
     * a seventy-six unit square with the mark somewhere in the middle of it - handed the
     * view's bounds it comes out at the size of its own margin. See
     * [MonochromeIconProvider.placeInk], which is what those notification marks go through
     * too, so the two kinds end up the same weight.
     *
     * A missing file leaves an empty view rather than throwing: it is one icon out of a
     * thousand and the strip has four other things to say.
     */
    private fun radioMark(asset: String): ImageView {
        val view = ImageView(context)
        val box = dp(MARK_DP)
        SvgIcon.fromAsset(context, asset)?.let { icon ->
            view.setImageDrawable(icon)
            view.setColorFilter(palette.foreground)
            iconProvider.placeInk(view, icon, "ink:$asset", box, box)
        }
        view.visibility = GONE
        return view
    }

    /**
     * Stands the strip's content off the ends by [px], for a display with rounded corners.
     *
     * Only ever more than nothing when this strip is drawn hard against the top of the
     * screen - which is what "full screen" does, and is where the phone's own status bar
     * would otherwise have been keeping it clear of the arc. See
     * `MainActivity.cornerSideInsetPx`, which is where the number comes from.
     */
    fun setCornerInset(px: Int) {
        if (cornerInset == px) return
        cornerInset = px
        applyPadding()
    }

    /**
     * The height of the band the camera sits in, from the shell that reserved it.
     *
     * Only ever more than nothing when this strip is drawn hard against the top of the
     * screen and there is a lens in the way - which is what "full screen" does on a phone
     * with one. See `WP81Shell.setStatusBarTopInset`.
     */
    fun setCameraBand(px: Int) {
        if (cameraBand == px) return
        cameraBand = px
        applyPadding()
    }

    /**
     * How far down the strip its first line begins.
     *
     * Centred in the band the strip is given while it has one line in it - so on a phone
     * whose camera has made that band taller than a line needs, the writing sits level with
     * the lens rather than above it. Held there when the second line comes out: the band
     * grows by exactly a line to take it - see `WP81Shell.statusBarHeightPx` - and the first
     * line stays where it was, which is the whole reason this is a number rather than a
     * gravity.
     *
     * Read from outside as well, because it is what decides how far a rounded corner still
     * reaches into this strip: the higher the writing sits, the steeper the part of the arc
     * it has to clear. See `MainActivity.cornerSideInsetPx`.
     */
    val contentTopPx: Int
        get() = maxOf(
            dp(TOP_PAD_DP),
            (maxOf(cameraBand, dp(HEIGHT_DP)) - dp(LINE_DP)) / 2
        )

    private fun applyPadding() {
        setPadding(
            dp(SIDE_PAD_DP) + cornerInset,
            contentTopPx,
            dp(SIDE_PAD_DP) + cornerInset,
            dp(TOP_PAD_DP)
        )
    }

    /**
     * Shows the second line, or folds it away.
     *
     * The strip's own height is not set here. It is a band the shell reserves, and what is
     * laid out below it has to move with it - so the shell owns the height and calls this
     * to fill it. See `WP81Shell.setStatusBarExpanded`.
     */
    fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        carrier.visibility = if (on) VISIBLE else GONE
        chargeAndDate.visibility = if (on) VISIBLE else GONE
        // Both lines are read from the same pass, and the second one has just come into
        // view carrying whatever it was told up to ten seconds ago.
        if (on) refresh(force = true)
    }

    /**
     * Takes hold of every gesture that starts on the strip.
     *
     * Consumed from the DOWN rather than once it has become a drag, which is what keeps the
     * rest of the gesture coming here: touches go on being delivered to the view that
     * claimed the press, so a finger that has long since left this forty-two dp band is
     * still reported to it - which is the whole trick, because the panel it is dragging out
     * is taller than the screen.
     *
     * Raw coordinates for the same reason: the strip does not move, but what it is dragging
     * does, and a gesture measured against a moving thing measures nothing.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pullStartY = ev.rawY
                pulling = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val travelled = ev.rawY - pullStartY
                if (!pulling && kotlin.math.abs(travelled) > touchSlop) {
                    pulling = true
                    onPullStart?.invoke(travelled)
                }
                if (pulling) onPullMove?.invoke(travelled)
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> if (pulling) {
                pulling = false
                onPullEnd?.invoke(ev.rawY - pullStartY)
            }
        }
        return true
    }

    /**
     * Re-reads what the strip is showing.
     *
     * The clock every time, because it is a field lookup and it is the one thing anybody
     * would notice going stale. The rest at [SLOW_MS] apart, because the signal and the
     * bearer are IPC and the charge is a broadcast read, and none of the three moves fast
     * enough to be worth asking about thirty times a minute.
     *
     * [force] takes all of them now: on the first draw, on a theme change, and when the
     * Action Center is being opened - somebody who has just pulled the panel down is
     * looking straight at this strip and should not be shown a reading from ten seconds ago.
     */
    fun refresh(force: Boolean = false) {
        val now = Date()
        clock.text = wp81TimeFormat(context).format(now)

        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - slowReadAt < SLOW_MS) return
        slowReadAt = elapsed

        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // Every one of these is refusable - the signal and the bearer want READ_PHONE_STATE,
        // which this app is granted when it is made the phone app and not before - so each
        // is read on its own and each falls back to showing nothing, rather than the whole
        // strip going blank because one of them was denied.
        signalBars.level = try {
            telephony?.signalStrength?.level ?: -1
        } catch (e: Exception) {
            -1
        }
        signalBars.invalidate()

        networkType.text = try {
            networkLabel(telephony?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN)
        } catch (e: Exception) {
            ""
        }
        carrier.text = try {
            telephony?.networkOperatorName.orEmpty()
        } catch (e: Exception) {
            ""
        }

        // Cheaper than the telephony reads above but on the same tick as them, because
        // neither moves without the user having just moved it: a radio switched at the
        // wall or a headset taken out of its case, and ten seconds is not long to wait to
        // see it. Both marks are dropped rather than dimmed when there is nothing to say.
        bluetoothMark.visibility = if (bluetoothConnected()) VISIBLE else GONE
        airplaneMark.visibility = if (airplaneOn()) VISIBLE else GONE

        val battery = BatteryStore.read(context)
        batteryGlyph.percent = if (battery?.known == true) battery.percent else -1
        // On the mains rather than strictly taking charge. `charging` alone is
        // BATTERY_STATUS_CHARGING, and a modern phone spends much of its time plugged in
        // without reporting it: adaptive charging holds at eighty per cent and reports
        // NOT_CHARGING, and a full battery reports FULL. All three are a cable in the
        // bottom of the phone, which is what the mark is telling the user about.
        batteryGlyph.charging = battery != null && (battery.charging || battery.plugged)
        batteryGlyph.invalidate()

        val date = SimpleDateFormat("dd/MM", Locale.getDefault()).format(now)
        chargeAndDate.text =
            if (battery?.known == true) "${battery.percent}%  $date" else date
    }

    /** How the phone writes the bearer beside the bars: "H+", "LTE", "5G". */
    private fun networkLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "H"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE -> "E"
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "G"
        else -> ""
    }

    /**
     * Whether the radios have been switched off at the wall.
     *
     * A settings row rather than a service, so there is nothing here to be refused, and it
     * is the same value the Action Center's flight mode tile reads - see
     * `WP81ActionCenter.airplaneOn`.
     */
    private fun airplaneOn(): Boolean = try {
        Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    } catch (e: Exception) {
        false
    }

    /**
     * Whether bluetooth is on *and* has something on the other end of it.
     *
     * On alone is the Action Center's question, because a tile is a switch and has to say
     * what the switch is set to. The strip is not a panel of switches: it says what the
     * phone is doing, and a radio talking to nothing is not doing anything worth a mark.
     * The phone's own bar drew it the same way.
     *
     * Asked twice, because the first way can be refused without saying so.
     * `getProfileConnectionState` wants BLUETOOTH_CONNECT, which this app asks for only
     * when it is made the phone app - see `MainActivity.ensureCallPermissions` - and
     * without it the platform answers "disconnected" to everything rather than answering
     * that it will not say. What is left is the audio routing, which needs no permission at
     * all and catches what is nearly always on the other end: a headset, a pair of earbuds,
     * a car. A watch goes unmarked until the permission is there, which is the right way
     * round for a mark nobody is relying on.
     */
    private fun bluetoothConnected(): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter ?: return false
        val on = try {
            adapter.isEnabled
        } catch (e: Exception) {
            false
        }
        if (!on) return false
        val paired = try {
            // The profile is a BluetoothProfile constant and the state that comes back is
            // a BluetoothAdapter one. They are the same numbers, but they are two different
            // sets and only one of them is what this call answers in.
            CONNECTED_PROFILES.any {
                adapter.getProfileConnectionState(it) == BluetoothAdapter.STATE_CONNECTED
            }
        } catch (e: SecurityException) {
            // Named rather than swept up with the rest, because a refused
            // BLUETOOTH_CONNECT is the expected way for this to end rather than a fault.
            false
        } catch (e: Exception) {
            false
        }
        return paired || bluetoothAudioAttached()
    }

    /** Whether anything the phone would play sound through is on the far end of a radio. */
    private fun bluetoothAudioAttached(): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return try {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type in BLUETOOTH_OUTPUTS }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Draws one mark per app with something waiting, in the order given.
     *
     * Capped at [MAX_MARKS]: past a handful the row is longer than the carrier's name and
     * stops being a glance. The phone stopped too, and said nothing about what it had left
     * out - the marks are a hint that there is something down there, and the panel is where
     * the count lives.
     *
     * Rebuilt whole rather than reconciled. It changes only when the set of apps with
     * notifications changes, which is rarely, and a handful of ImageViews is not worth the
     * bookkeeping that diffing them would take.
     */
    fun setNotificationMarks(apps: List<Pair<String, MonochromeIconProvider.Glyph?>>) {
        marks.removeAllViews()
        for ((packageName, glyph) in apps.take(MAX_MARKS)) {
            val box = dp(MARK_DP)
            val view = ImageView(context)
            when (glyph) {
                is MonochromeIconProvider.Glyph.Monochrome -> {
                    // A silhouette in the bar's own ink, the way every other mark up here
                    // is drawn. No accent square: the strip is one line of white on black
                    // and a row of coloured tiles in it would be a second status bar.
                    view.setColorFilter(palette.foreground)
                    view.setImageDrawable(glyph.drawable)
                    iconProvider.placeInk(
                        view, glyph.drawable, "ink:$packageName", box, box)
                }
                is MonochromeIconProvider.Glyph.FullColor -> {
                    // No silhouette to be had, so the app's own icon, small. Android's bar
                    // does the same for an app that ships no monochrome artwork.
                    view.clearColorFilter()
                    view.scaleType = ImageView.ScaleType.FIT_CENTER
                    view.setImageDrawable(glyph.drawable)
                }
                null -> continue
            }
            marks.addView(view, LayoutParams(box, box).apply { marginStart = dp(6) })
        }
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        signalBars.color = p.foreground
        batteryGlyph.color = p.foreground
        for (label in listOf(networkType, carrier, clock, chargeAndDate)) {
            label.setTextColor(p.foreground)
        }
        for (mark in listOf(bluetoothMark, airplaneMark)) mark.setColorFilter(p.foreground)
        refresh(force = true)
    }

    // ---------------------------------------------------------------- small drawn things

    /**
     * The five ascending bars, filled as far as the signal reaches.
     *
     * Drawn rather than taken from the icon set: the set has one wifi mark and no ladder,
     * and five rectangles whose heights are a straight line is not artwork worth keeping a
     * file for.
     */
    private class SignalBars(context: Context) : View(context) {
        /** 0 to 4 as the platform reports it, or -1 where it would not say. */
        var level = -1
        var color = Color.WHITE

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val bars = 5
            // Five bars and four gaps, each gap 40% of a bar's width.
            val unit = width / (bars + (bars - 1) * 0.4f)
            val gap = unit * 0.4f
            val lit = if (level < 0) 0 else level + 1
            for (index in 0 until bars) {
                val tall = height * (0.34f + 0.66f * index / (bars - 1f))
                val left = index * (unit + gap)
                paint.color = color
                // The unlit ones are not left out - the ladder is the scale, and a bar
                // that is missing is a bar the eye has to count to notice.
                paint.alpha = if (index < lit) 255 else 70
                canvas.drawRect(left, height - tall, left + unit, height.toFloat(), paint)
            }
        }
    }

    /**
     * The battery: an outline, a nub, and a fill as long as the charge.
     *
     * Except on the mains, where it is the phone's own charging mark instead. A cell drawn
     * full and a cell drawn full while charging are the same picture, and the bolt is the
     * one thing that says which of the two this is.
     */
    private class BatteryGlyph(context: Context) : View(context) {
        var percent = -1

        /** On the mains - see where this is set, which is not the same as taking charge. */
        var charging = false
        var color = Color.WHITE

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val body = RectF()
        private val fill = RectF()

        /** Read once: it is one small file and this view is redrawn every time the clock moves. */
        private val bolt = SvgIcon.fromAsset(context, "custom_icons_8/appbar.battery.charging.svg")

        /**
         * Where the bolt's ink actually sits inside its own canvas.
         *
         * The set is drawn on a seventy-six unit square with the mark somewhere in the
         * middle of it, so a drawable handed the view's bounds comes out at the size of its
         * *padding* rather than of its picture - which is why it arrived a third the size
         * of the outline it replaced. Measured once and kept.
         */
        private val boltInk = bolt?.let { MonochromeIconProvider.measureInk(it) }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return

            if (charging && bolt != null) {
                // The bounds are made large enough that the *ink* fills the view, and then
                // offset so the ink's centre lands on the view's centre. What hangs outside
                // is that artwork's own margin, and there is nothing drawn in it.
                if (boltInk != null && boltInk.width() > 0f && boltInk.height() > 0f) {
                    val side = minOf(width / boltInk.width(), height / boltInk.height())
                    val left = width / 2f - (boltInk.left + boltInk.width() / 2f) * side
                    val top = height / 2f - (boltInk.top + boltInk.height() / 2f) * side
                    bolt.setBounds(
                        left.toInt(), top.toInt(),
                        (left + side).toInt(), (top + side).toInt()
                    )
                } else {
                    bolt.setBounds(0, 0, width, height)
                }
                bolt.setTint(color)
                bolt.draw(canvas)
                return
            }

            val stroke = maxOf(1f, height * 0.09f)
            val nub = width * 0.07f
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            body.set(
                stroke / 2f,
                stroke / 2f,
                width - nub - stroke / 2f,
                height - stroke / 2f
            )
            canvas.drawRect(body, paint)

            paint.style = Paint.Style.FILL
            // The nub on the positive end, which is what makes the outline read as a cell
            // rather than as an empty box.
            canvas.drawRect(
                width - nub, height * 0.3f, width.toFloat(), height * 0.7f, paint)

            val charge = percent
            if (charge in 0..100) {
                val inset = stroke * 1.6f
                val room = body.width() - inset * 2f
                fill.set(
                    body.left + inset,
                    body.top + inset,
                    body.left + inset + room * (charge / 100f),
                    body.bottom - inset
                )
                if (fill.width() > 0f) canvas.drawRect(fill, paint)
            }
        }
    }

    companion object {
        /**
         * How tall the strip is.
         *
         * Fixed, the way the navigation bar's height is, and for the same reason: it is a
         * band the shell reserves rather than space the system hands it, so everything laid
         * out below has to know how much to stand clear of before any of it is measured.
         * One line of [TEXT_SP] with the padding above and below it - the second line is
         * not part of what is reserved, because it is only out while something is covering
         * everything that would have to move for it.
         */
        const val HEIGHT_DP = 28

        /**
         * How tall it becomes once the second line is showing.
         *
         * What is actually used is the difference between this and [HEIGHT_DP], which is
         * what the second line costs: the strip grows downwards by that much from whatever
         * it already was, so a band a camera has made taller grows by a line too rather
         * than staying put and squeezing two lines into one band's worth of room. See
         * `WP81Shell.statusBarHeightPx`.
         *
         * Only the Action Center ever sees it either way: the pages below are laid out
         * against [HEIGHT_DP] and stay there, because by the time the strip has grown the
         * panel is covering them anyway. See `WP81Shell.setStatusBarExpanded`.
         */
        const val EXPANDED_HEIGHT_DP = 46

        private const val TEXT_SP = 13.5f

        private const val SIDE_PAD_DP = 14

        /**
         * The least the first line ever sits from the strip's own top edge.
         *
         * The whole answer is [contentTopPx] - this, or half of what a camera's band left
         * over, whichever is more - and that is the one read from outside, because how far
         * down the writing sits is what decides how much of a rounded corner is still in
         * the way of it. See `MainActivity.cornerSideInsetPx`.
         */
        const val TOP_PAD_DP = 5

        /**
         * One line of the strip: what [HEIGHT_DP] is, less the padding above and below it.
         *
         * The second line is exactly this much again - see [EXPANDED_HEIGHT_DP] - which is
         * what lets the strip grow downwards by a line rather than rearranging itself
         * around a new middle. See [contentTopPx].
         */
        const val LINE_DP = HEIGHT_DP - 2 * TOP_PAD_DP

        /** How long the readings that are not the clock are allowed to stand. */
        private const val SLOW_MS = 10_000L

        /** Where the icon set lives, for the marks this strip draws from it. */
        private const val ICON_DIR = "custom_icons_8"

        /**
         * The bluetooth profiles worth calling connected.
         *
         * Every one of them is something in a user's ears or in their car - the kind of
         * link they would expect the bar to know about. GATT is deliberately not here: a
         * low-energy link is how a phone talks to a thermometer it paired with once, and a
         * mark that lights for that is a mark that is lit all day.
         */
        private val CONNECTED_PROFILES = intArrayOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.HEARING_AID
        )

        /** The output kinds that mean a bluetooth device is attached. See [bluetoothAudioAttached]. */
        private val BLUETOOTH_OUTPUTS = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            // LE Audio, which is what a new pair of earbuds is on a new phone.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }

        /** How large one app's mark is drawn in the strip. */
        private const val MARK_DP = 13

        /** How many of them are drawn before the row stops being a glance. */
        private const val MAX_MARKS = 6
    }
}

/**
 * The phone's clock, in whichever of the two ways this phone writes one.
 *
 * Shared, because the Action Center's list writes the same kind of stamp beside a
 * notification that arrived today as the strip writes for the time now, and two places
 * disagreeing about whether this phone is on a 24-hour clock is the sort of thing nobody
 * notices until they see 18:30 above 6:30 PM.
 */
internal fun wp81TimeFormat(context: Context): SimpleDateFormat = SimpleDateFormat(
    if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
    Locale.getDefault()
)
