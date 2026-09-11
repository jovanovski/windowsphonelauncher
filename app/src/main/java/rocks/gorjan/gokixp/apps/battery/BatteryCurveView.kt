package rocks.gorjan.gokixp.apps.battery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.BatteryStore
import rocks.gorjan.gokixp.wp81.WP81Palette
import java.util.Calendar

/**
 * The last day of charge, as a line.
 *
 * Fixed to the window rather than to the samples: the plot is always the full day and
 * always the full nought to a hundred, so the shape of it means the same thing every time
 * it is looked at. A chart that rescaled itself to whatever happened to be in it would
 * draw a quiet night and a hard afternoon as the same slope.
 *
 * Two things about the record are drawn honestly rather than smoothed over. Where the
 * phone was on charge the column behind the line is washed - that is the answer to "why
 * did it go up there" - and where the launcher was not running to write anything down the
 * line simply stops, because a straight segment across a gap is a claim about hours
 * nobody watched. See BatteryStore, which is where those gaps come from.
 */
@SuppressLint("ViewConstructor")
class BatteryCurveView(
    context: Context,
    private var palette: WP81Palette
) : View(context) {

    private var samples: List<BatteryStore.Sample> = emptyList()

    /** The two ends of the window the plot covers. Fixed, whatever the samples cover. */
    private var from = 0L
    private var to = 0L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(LINE_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RULE_DP)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        textSize = sp(LABEL_SP)
    }

    private val line = Path()
    private val area = Path()

    /** The samples to plot, and the window to plot them in. */
    fun setHistory(samples: List<BatteryStore.Sample>, from: Long, to: Long) {
        this.samples = samples
        this.from = from
        this.to = to
        invalidate()
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // As wide as it is given - a day fits on a screen, unlike an hourly forecast - and
        // as tall as the plot and its labels come to.
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            dp(HEIGHT_DP).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || to <= from) return

        val labelHeight = labelPaint.descent() - labelPaint.ascent()
        val left = 0f
        val right = width.toFloat()
        val top = dp(EDGE_DP)
        val bottom = height - labelHeight - dp(LABEL_GAP_DP)
        if (bottom <= top) return

        val span = (to - from).toFloat()
        fun xOf(at: Long) = left + (at - from) / span * (right - left)
        fun yOf(percent: Int) = bottom - (percent.coerceIn(0, 100) / 100f) * (bottom - top)

        drawCharging(canvas, top, bottom, ::xOf)
        drawRules(canvas, left, right, ::yOf)
        drawHours(canvas, bottom, labelHeight, ::xOf)
        drawLine(canvas, bottom, ::xOf, ::yOf)
    }

    /** A wash behind the stretches the phone spent on a charger. */
    private fun drawCharging(canvas: Canvas, top: Float, bottom: Float, xOf: (Long) -> Float) {
        washPaint.color = palette.accent
        washPaint.alpha = WASH_ALPHA
        var start: Long? = null
        for (sample in samples) {
            if (sample.charging && start == null) start = sample.at
            if (!sample.charging && start != null) {
                canvas.drawRect(xOf(start), top, xOf(sample.at), bottom, washPaint)
                start = null
            }
        }
        // Still on charge at the right hand edge, which is the common case: the phone is
        // most often looked at while it is plugged in.
        start?.let { canvas.drawRect(xOf(it), top, xOf(to), bottom, washPaint) }
    }

    /**
     * The scale, as three lines and nothing else.
     *
     * Quarters, unlabelled. A chart of a battery is read for its slope rather than for
     * values off it - the figure that matters is the one printed above the chart in type
     * the size of a thumb - and a column of axis numbers down the side would be four more
     * things to look at in aid of a question nobody is asking here.
     */
    private fun drawRules(canvas: Canvas, left: Float, right: Float, yOf: (Int) -> Float) {
        rulePaint.color = palette.foregroundSubtle
        for (percent in intArrayOf(0, 25, 50, 75, 100)) {
            rulePaint.alpha = if (percent == 0) EDGE_RULE_ALPHA else RULE_ALPHA
            val y = yOf(percent)
            canvas.drawLine(left, y, right, y, rulePaint)
        }
    }

    /** The clock, every six hours, plus the end the reader is standing at. */
    private fun drawHours(
        canvas: Canvas,
        bottom: Float,
        labelHeight: Float,
        xOf: (Long) -> Float
    ) {
        labelPaint.color = palette.foregroundSubtle
        val baseline = bottom + dp(LABEL_GAP_DP) - labelPaint.ascent()

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = from
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        // On to the next six-hour mark - midnight, six, noon, six - which is where a day
        // is divided in anybody's head.
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        calendar.set(Calendar.HOUR_OF_DAY, ((hour / TICK_HOURS) + 1) * TICK_HOURS)

        val nowLabel = "now"
        val nowWidth = labelPaint.measureText(nowLabel)
        val nowX = width - nowWidth
        while (calendar.timeInMillis < to) {
            val at = calendar.timeInMillis
            val text = hourLabel(calendar.get(Calendar.HOUR_OF_DAY))
            val x = xOf(at) - labelPaint.measureText(text) / 2f
            // Nothing is drawn where it would run into "now" at the right hand end, or
            // off the left: a label half off the edge is a label nobody can read.
            if (x >= 0f && x + labelPaint.measureText(text) < nowX - dp(LABEL_GAP_DP)) {
                canvas.drawText(text, x, baseline, labelPaint)
            }
            calendar.add(Calendar.HOUR_OF_DAY, TICK_HOURS)
        }
        labelPaint.color = palette.foreground
        canvas.drawText(nowLabel, nowX, baseline, labelPaint)
    }

    /**
     * The charge itself: a filled area under a line, broken wherever the record is.
     *
     * Each unbroken run is closed down to the floor and filled before the line over it is
     * stroked, so a gap in the record is a gap in the fill as well - the shape has to say
     * the same thing the line does.
     */
    private fun drawLine(
        canvas: Canvas,
        bottom: Float,
        xOf: (Long) -> Float,
        yOf: (Int) -> Float
    ) {
        if (samples.isEmpty()) return
        areaPaint.color = palette.accent
        areaPaint.alpha = AREA_ALPHA
        linePaint.color = palette.accent

        var run = mutableListOf<BatteryStore.Sample>()
        val runs = mutableListOf<List<BatteryStore.Sample>>()
        for (sample in samples) {
            val last = run.lastOrNull()
            if (last != null && sample.at - last.at > GAP_MS) {
                runs.add(run)
                run = mutableListOf()
            }
            run.add(sample)
        }
        if (run.isNotEmpty()) runs.add(run)

        for (points in runs) {
            line.reset()
            area.reset()
            // A run of one is a single reading with nothing to join it to. Drawn as a
            // short flat step rather than dropped: something was recorded there, and a
            // chart that leaves it out is a chart with an unexplained hole in it.
            for ((index, sample) in points.withIndex()) {
                val x = xOf(sample.at)
                val y = yOf(sample.percent)
                if (index == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, bottom)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            val last = points.last()
            // The final run is carried across to now at the level it last stood at, which
            // is what the battery has in fact been doing since the last sample.
            if (points === runs.last()) {
                val edge = xOf(to)
                line.lineTo(edge, yOf(last.percent))
                area.lineTo(edge, yOf(last.percent))
                area.lineTo(edge, bottom)
            } else {
                area.lineTo(xOf(last.at), bottom)
            }
            area.close()
            canvas.drawPath(area, areaPaint)
            canvas.drawPath(line, linePaint)
        }

        // Where it stands, at the end of the line.
        dotPaint.color = palette.accent
        val last = samples.last()
        canvas.drawCircle(xOf(to), yOf(last.percent), dp(DOT_DP), dotPaint)
    }

    /** An hour of the day, in whichever clock the phone is set to. */
    private fun hourLabel(hour: Int): String {
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            return "${if (hour < 10) "0$hour" else hour}:00"
        }
        val twelve = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$twelve${if (hour < 12) "am" else "pm"}"
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        /** The plot and the row of hours under it. */
        private const val HEIGHT_DP = 168f

        private const val EDGE_DP = 6f
        private const val LABEL_GAP_DP = 8f

        private const val LINE_DP = 2f
        private const val RULE_DP = 1f
        private const val DOT_DP = 4f

        private const val LABEL_SP = 12f

        /** How far apart the clock marks along the foot are. */
        private const val TICK_HOURS = 6

        /** The area under the line, and the wash behind a charge. */
        private const val AREA_ALPHA = 64
        private const val WASH_ALPHA = 28

        private const val RULE_ALPHA = 40
        private const val EDGE_RULE_ALPHA = 70

        /**
         * A hole in the record wider than this is drawn as a hole.
         *
         * Twice the interval at which a still battery is written down anyway, so an
         * ordinary quiet stretch joins up and a stretch where nothing was running does
         * not. See BatteryStore's own quiet interval.
         */
        private const val GAP_MS = 21L * 60L * 1000L
    }
}
