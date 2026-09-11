package rocks.gorjan.gokixp.apps.battery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.BatteryStore
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81Program
import java.util.Locale

/**
 * The battery, opened out of the tile that shows it.
 *
 * The tile answers one question - how full - and it answers it at a glance, which is what
 * a tile is for. The three things it has no room for are the ones somebody actually opens
 * a battery screen to find out: whether the level is falling faster than usual, what
 * happened overnight, and what has been eating it.
 *
 * Two sections, because there are two questions. "battery" is the charge and the shape of
 * the last day; "usage" is what spent it. The second is an estimate and says so - see
 * [AppDrain] for why no app on this side of the platform can do better than that, and for
 * what it does instead.
 *
 * Nothing here polls. [BatteryStore] reads the sticky broadcast the platform already keeps
 * up to date, and the launcher hands this app a nudge whenever the level moves, so the
 * page open in front of somebody is the same reading as the tile behind it.
 */
class BatteryApp(
    private val context: Context,
    private var palette: WP81Palette,
    /** The shell's own toast, for the one thing that can fail. */
    private val onNotify: (String, String) -> Unit
) : WP81Program {

    private lateinit var root: FrameLayout
    private lateinit var panorama: MetroPanorama

    /** One strip per section, as the panorama expects: only one of them has a command. */
    private val bars = mutableListOf<MetroAppBar>()

    private lateinit var chargeColumn: LinearLayout
    private lateinit var usageColumn: LinearLayout

    // ---------------------------------------------------------------- construction

    override fun applyPalette(palette: WP81Palette): View {
        this.palette = palette
        return createView()
    }

    fun createView(): View {
        // Everything below is built fresh, and this runs a second time whenever the theme
        // changes under an open window - see [applyPalette]. Without the clear the strips
        // from the old palette stay in the list, and [showBarFor] then indexes a row that
        // belongs to a view tree nobody is looking at.
        bars.clear()
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        panorama.setTitle("battery")

        chargeColumn = sectionColumn()
        usageColumn = sectionColumn()

        panorama.addPage("charge", scroller(chargeColumn))
        panorama.addPage("usage", scroller(usageColumn))

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        repeat(PAGE_COUNT) { page ->
            val bar = MetroAppBar(context, palette)
            bar.addCommand(REFRESH_ICON) { bind(force = true) }
            bars.add(bar)
            root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))
        panorama.onPageSettled = { index -> showBarFor(index) }
        showBarFor(PAGE_CHARGE)

        bind(force = true)
        return root
    }

    /**
     * Rebinds the page from what the platform and the record say right now.
     *
     * Safe to call at any time, which is what lets the launcher hand it a nudge on every
     * battery broadcast rather than this app keeping a clock of its own.
     *
     * The two sections are not rebound on the same terms. The charge and the chart come
     * from a sticky broadcast and a string in the preferences, so they are redone every
     * time; the usage list is a day of the platform's own event stream, read and totalled,
     * and a list somebody is halfway down should not be torn out and rebuilt because the
     * battery moved a point. [force] is what the way in and the refresh ring pass - a page
     * that has just been built has to fill both sections whenever it was last read.
     */
    fun bind(force: Boolean = false) {
        val reading = BatteryStore.read(context)
        // Read *and written down* - a battery app open on the screen is the best chance
        // the record gets to be up to date, and the chart under the reading is drawn from
        // that record a line later.
        reading?.let { BatteryStore.record(context, it) }
        bindCharge(reading)
        val now = System.currentTimeMillis()
        if (force || now - usageBoundAt >= USAGE_QUIET_MS) {
            usageBoundAt = now
            bindUsage()
        }
    }

    /** When the usage list was last rebuilt. See [bind]. */
    private var usageBoundAt = 0L

    fun cleanup() {
        // Nothing is held: no clock, no receiver, no decoder. Kept so the window's close
        // listener reads like every other program's.
    }

    private fun showBarFor(page: Int) {
        for ((index, bar) in bars.withIndex()) {
            bar.visibility = if (index == page) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------- charge

    /**
     * The figure, what it is doing, and the shape of the day behind it.
     *
     * Set the way the weather app sets its temperature, and for the same reason: whoever
     * opened this has one question nine times out of ten, and it should be answered before
     * they have focused on anything else.
     */
    private fun bindCharge(reading: BatteryStore.Reading?) {
        chargeColumn.removeAllViews()
        if (reading == null || !reading.known) {
            chargeColumn.addView(note("the phone is not saying what the battery is doing"), wide())
            return
        }

        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(14), 0, 0)
        }

        val figure = TextView(context).apply {
            text = "${reading.percent}%"
            typeface = font(R.font.segoeui_light)
            textSize = 92f
            includeFontPadding = false
            setTextColor(palette.foreground)
        }
        hero.addView(figure, LinearLayout.LayoutParams(WRAP, WRAP))

        val figures = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            // Raised off the foot of the row by the numeral's own descent, which is empty
            // space, so the last line sits on the figure's baseline rather than under the
            // hole beneath it.
            setPadding(dp(12), 0, 0, figure.paint.fontMetrics.descent.toInt())
        }
        figures.addView(aside(stateWord(reading)), wide())
        BatteryStore.timeLeft(context, reading)?.let {
            figures.addView(aside("about ${duration(it)} left"), wide())
        }
        val day = BatteryStore.history(context, WINDOW_HOURS)
        val spent = BatteryStore.drained(day)
        if (spent > 0) figures.addView(aside("$spent% spent today"), wide())
        hero.addView(figures, LinearLayout.LayoutParams(0, WRAP, 1f))
        chargeColumn.addView(hero, wide())

        chargeColumn.addView(label("last 24 hours"), wide())
        if (day.size < MIN_PLOTTABLE) {
            chargeColumn.addView(
                note(
                    "nothing has been written down yet.  the shape of the day appears " +
                        "here as the phone is used"
                ),
                wide()
            )
        } else {
            val now = System.currentTimeMillis()
            val curve = BatteryCurveView(context, palette)
            curve.setHistory(day, now - WINDOW_MS, now)
            chargeColumn.addView(
                curve,
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        val details = buildList {
            if (!reading.temperature.isNaN()) {
                add(Detail("temperature", String.format(
                    Locale.getDefault(), "%.1f°C", reading.temperature)))
            }
            if (!reading.voltage.isNaN()) {
                add(Detail("voltage", String.format(
                    Locale.getDefault(), "%.2f V", reading.voltage)))
            }
            reading.health?.let { add(Detail("health", it)) }
            reading.technology?.let { add(Detail("cell", it)) }
        }
        if (details.isNotEmpty()) {
            chargeColumn.addView(label("the cell itself"), wide())
            chargeColumn.addView(grid(details), wide())
        }
    }

    /** What the battery is doing, in the fewest words that are actually true. */
    private fun stateWord(reading: BatteryStore.Reading): String = when {
        reading.full && reading.plugged -> "full"
        reading.charging -> reading.plug?.let { "charging on $it" } ?: "charging"
        reading.plugged -> "plugged in"
        else -> "on battery"
    }

    // ---------------------------------------------------------------- usage

    /**
     * What spent the day, and a straight account of how that was worked out.
     *
     * Three states. Without usage access there is nothing to show and a tap that goes and
     * asks for it; with it and nothing recorded, a line saying so; and otherwise the apps
     * themselves, longest first, with the estimate against each one and the note under
     * them explaining what kind of number it is.
     */
    private fun bindUsage() {
        usageColumn.removeAllViews()
        if (!AppDrain.granted(context)) {
            usageColumn.addView(label("what spent it"), wide())
            usageColumn.addView(
                actionNote(
                    "android does not let an app read the battery's own figures, so this " +
                        "is worked out from how long each app was on screen.  tap to let " +
                        "this launcher see that"
                ) { openUsageAccess() },
                wide()
            )
            return
        }

        val now = System.currentTimeMillis()
        val spent = BatteryStore.drained(BatteryStore.history(context, WINDOW_HOURS))
        val uses = AppDrain.since(context, now - WINDOW_MS, spent)
        usageColumn.addView(label("what spent it"), wide())
        if (uses.isEmpty()) {
            usageColumn.addView(note("nothing has been on screen long enough to count"), wide())
            return
        }
        for (use in uses) usageColumn.addView(usageRow(use, spent > 0), wide())
        usageColumn.addView(
            note(
                if (spent > 0) {
                    "the $spent% spent today, divided by time on screen.  the platform " +
                        "keeps its own per-app battery figures behind a permission no " +
                        "ordinary app can hold, so this is the closest honest answer"
                } else {
                    "nothing has been spent in the last day, so there is nothing to " +
                        "divide up.  what each app held the screen for is above"
                }
            ),
            wide()
        )
    }

    /** One app: its mark, its name, how long it held the screen, and its share of the day. */
    private fun usageRow(use: AppDrain.Use, showPoints: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), dp(4), dp(10))
        }

        row.addView(ImageView(context).apply {
            setImageDrawable(iconFor(use.packageName))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(ROW_ICON_DP), dp(ROW_ICON_DP)).apply {
            marginEnd = dp(14)
        })

        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(context).apply {
            text = use.label
            typeface = font(R.font.segoeui_semilight)
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
        }, wide())
        words.addView(TextView(context).apply {
            text = duration(use.foregroundMs) + " on screen"
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            setTextColor(palette.foregroundSubtle)
        }, wide())
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))

        // The estimate, where there is a drain to divide up at all. A phone that spent the
        // day on a charger spent nothing, and the column would then be either a row of
        // noughts or - worse - a share of screen time set in the same place, in the same
        // colour, meaning something else entirely. The time on screen is still there under
        // the name, which on such a day is the whole of the answer.
        if (showPoints) {
            row.addView(TextView(context).apply {
                text = "${Math.round(use.points)}%"
                typeface = font(R.font.segoeui_semilight)
                textSize = 20f
                gravity = Gravity.END
                setTextColor(palette.accent)
            }, LinearLayout.LayoutParams(dp(READING_DP), WRAP))
        }
        return row
    }

    private fun iconFor(packageName: String) = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }

    /**
     * Settings, at the page where usage access is given.
     *
     * Said out loud when there is nowhere to go: a build with the page missing would
     * otherwise answer a tap with nothing at all, which reads as the app being broken.
     */
    private fun openUsageAccess() {
        try {
            context.startActivity(AppDrain.settingsIntent())
        } catch (e: Exception) {
            onNotify("battery", "this phone has no usage access page")
        }
    }

    // ---------------------------------------------------------------- words

    /** A stretch of time, in the two units that are worth saying at this length. */
    private fun duration(ms: Long): String {
        val minutes = (ms / 60000L).coerceAtLeast(0)
        val hours = minutes / 60
        return when {
            hours >= 1 && minutes % 60 == 0L -> "${hours}h"
            hours >= 1 -> "${hours}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }

    // ---------------------------------------------------------------- furniture

    /** One reading in the grid: what it is, and what it says. */
    private data class Detail(val name: String, val value: String)

    /** Readings laid out two to a row, each under its own name. */
    private fun grid(items: List<Detail>): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (pair in items.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in pair) {
                row.addView(detailCell(item), LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            // A single item on the last row keeps its column rather than spreading across
            // both, so the grid stays a grid all the way down.
            if (pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(row, wide())
        }
        return grid
    }

    private fun detailCell(item: Detail): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), dp(14), dp(10))
            addView(TextView(context).apply {
                text = item.name
                typeface = font(R.font.segoeui_regular)
                textSize = 14f
                setTextColor(palette.foregroundSubtle)
            }, wide())
            addView(TextView(context).apply {
                text = item.value
                typeface = font(R.font.segoeui_semilight)
                textSize = 23f
                setTextColor(palette.foreground)
                setPadding(0, dp(1), 0, 0)
            }, wide())
        }

    /** One of the lines standing beside the figure. */
    private fun aside(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_regular)
        textSize = 17f
        gravity = Gravity.END
        setTextColor(palette.foreground)
        setPadding(0, dp(2), 0, 0)
    }

    private fun sectionColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), dp(PAGE_MARGIN_DP), dp(24))
    }

    private fun scroller(content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_semibold)
        textSize = 12f
        letterSpacing = 0.06f
        setTextColor(palette.accent)
        setPadding(0, dp(22), 0, dp(4))
    }

    private fun note(message: String) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 14f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(18), dp(8), dp(6))
        setLineSpacing(0f, 1.1f)
    }

    /** The same, where there is something to do about what it says. */
    @SuppressLint("ClickableViewAccessibility")
    private fun actionNote(message: String, onTap: () -> Unit) = note(message).apply {
        setTextColor(palette.accent)
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onTap()
        }
        TiltEffect.apply(this)
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val PAGE_CHARGE = 0
        const val PAGE_COUNT = 2

        const val PAGE_MARGIN_DP = 22

        /** How far back the chart and the usage estimate both look. */
        const val WINDOW_HOURS = 24
        const val WINDOW_MS = WINDOW_HOURS * 60L * 60L * 1000L

        /** Under this many samples there is no shape to draw, only a dot. */
        const val MIN_PLOTTABLE = 2

        /**
         * How long the usage list is left alone between rebuilds. See [bind].
         *
         * Long enough that reading it is not interrupted by the battery moving a point,
         * short enough that a page left open is not showing an hour-old account of the
         * day. The refresh ring overrides it.
         */
        const val USAGE_QUIET_MS = 60L * 1000L

        const val ROW_ICON_DP = 34
        const val READING_DP = 60

        const val REFRESH_ICON = "custom_icons_8/appbar.refresh.svg"
    }
}
