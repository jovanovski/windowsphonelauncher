package rocks.gorjan.gokixp.apps.battery

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Which apps spent the day's charge, as closely as an app on this side of the wall can say.
 *
 * Android does not hand out per-app battery figures. The numbers the platform's own
 * battery screen prints come from `BatteryStatsManager`, which is guarded by a signature
 * permission that no third-party app can hold - there is no version of this that is the
 * real thing, and pretending otherwise would be a table of invented numbers.
 *
 * What can be known is how long each app was actually on the screen, which usage access
 * gives once the user grants it in Settings. The screen and what is driving it are most of
 * what a phone spends its day on, so time in front of the user divides the day's drain
 * about as well as anything available - and the app says outright that this is what it is
 * doing rather than dressing an estimate up as a measurement.
 *
 * Read from events rather than from the aggregated buckets. `queryAndAggregateUsageStats`
 * answers in whole days when it is asked about one, so "the last 24 hours" would quietly
 * become "today and yesterday"; the event stream can be counted between two exact
 * timestamps, which is the question actually being asked.
 */
object AppDrain {

    /**
     * One app's share of the day.
     *
     * [points] is percentage points of battery, estimated - see the class comment - and
     * [share] is the fraction of all screen time it accounts for, which is the figure that
     * is actually measured.
     */
    data class Use(
        val packageName: String,
        val label: String,
        val foregroundMs: Long,
        val share: Float,
        val points: Float
    )

    /** Whether the user has given this app usage access. */
    fun granted(context: Context): Boolean = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        Log.w(TAG, "could not ask about usage access", e)
        false
    }

    /**
     * The page in Settings where usage access is granted.
     *
     * Not the per-app page: `ACTION_USAGE_ACCESS_SETTINGS` takes a package extra on some
     * builds and ignores it on others, and one that ignores it and is handed one shows a
     * blank screen. The list is one tap further and always works.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * What was on screen over the last [sinceMs] to now, busiest first.
     *
     * [drained] is how many points of battery went in the same stretch - see
     * BatteryStore.drained - and is what each app's share is applied to.
     *
     * Empty where usage access has not been given, where nothing has been used, or where
     * the platform simply has no events to hand back. All three are the same thing as far
     * as the page is concerned: there is nothing to show, and it says so rather than
     * drawing an empty table.
     */
    fun since(context: Context, sinceMs: Long, drained: Int): List<Use> {
        if (!granted(context)) return emptyList()
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val totals = try {
            foregroundTimes(usage, sinceMs, now)
        } catch (e: Exception) {
            Log.w(TAG, "could not read usage events", e)
            return emptyList()
        }
        val all = totals.values.sum().toFloat()
        if (all <= 0f) return emptyList()

        val pm = context.packageManager
        return totals.entries
            .filter { it.value >= MIN_MS }
            .sortedByDescending { it.value }
            .take(MOST)
            .mapNotNull { (packageName, ms) ->
                // Its name as the user knows it. An app that has been uninstalled since it
                // was used is dropped rather than listed by package: a line of
                // "com.example.thing" in a list of app names is a bug as far as anyone
                // reading it is concerned.
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                val share = ms / all
                Use(
                    packageName = packageName,
                    label = label,
                    foregroundMs = ms,
                    share = share,
                    points = drained * share
                )
            }
    }

    /**
     * How long each package held the screen between two moments.
     *
     * Counted by pairing each resume with the pause or stop that follows it. Two things
     * that stream cannot say, and both are left as they are rather than guessed around: a
     * session already running when the window opens is counted only from its first event
     * inside it, and one still running at the end is counted up to now.
     */
    private fun foregroundTimes(
        usage: UsageStatsManager,
        from: Long,
        to: Long
    ): Map<String, Long> {
        val events = usage.queryEvents(from, to)
        val event = UsageEvents.Event()
        val open = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        while (events.getNextEvent(event)) {
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[packageName] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val started = open.remove(packageName) ?: continue
                    val spent = event.timeStamp - started
                    if (spent > 0) totals[packageName] = (totals[packageName] ?: 0L) + spent
                }
            }
        }
        for ((packageName, started) in open) {
            val spent = to - started
            if (spent > 0) totals[packageName] = (totals[packageName] ?: 0L) + spent
        }
        return totals
    }

    private const val TAG = "AppDrain"

    /** Under a minute on screen in a whole day is not a line in a list. */
    private const val MIN_MS = 60L * 1000L

    /** How many apps the page names. Past this it is a log rather than an answer. */
    private const val MOST = 8
}
