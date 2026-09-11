package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import rocks.gorjan.gokixp.MainActivity

/**
 * What the battery is doing, and what it has been doing for the last day.
 *
 * Android will tell anybody the charge as it stands - it is a sticky broadcast, no
 * permission, no service to bind - and will tell nobody what it was an hour ago. There is
 * no history to read: `BatteryStatsManager` is a signature permission, and what the
 * platform's own battery screen draws is behind a wall this app is on the wrong side of.
 *
 * So the history is one this shell keeps itself. The launcher hears the broadcast whenever
 * the level moves, writes down the reading, and a day of them is what the app's chart is
 * drawn from. That has one honest consequence worth stating: the record has holes in it
 * wherever the launcher's process was not alive, and the chart joins the dots across them
 * rather than pretending to know what happened in between.
 *
 * Kept small on purpose. A day of samples is a few hundred triples of numbers, written as
 * one string in the preferences the shell already has open - a database for this would be
 * a schema, a thread and a migration for something that is thrown away every 26 hours.
 */
object BatteryStore {

    /**
     * The battery as it stands.
     *
     * [percent] is the only field that is always there; everything else is what the
     * platform happened to put in the broadcast, and phones differ about which of them
     * they bother to fill in. The app drops a reading it has no figure for rather than
     * printing a dash.
     */
    data class Reading(
        /** 0-100, or -1 where the phone did not say. */
        val percent: Int,
        /** Taking charge right now. What the tile animates on. */
        val charging: Boolean,
        /** On a charger, which is not the same as charging: a full battery is neither. */
        val plugged: Boolean,
        /** Full, and being held there. */
        val full: Boolean,
        /** "ac", "usb", "wireless", or null while nothing is connected. */
        val plug: String?,
        /** Degrees Celsius, or NaN. */
        val temperature: Float,
        /** Volts, or NaN. */
        val voltage: Float,
        /** "good", "overheated", "cold"… or null where the phone did not say. */
        val health: String?,
        /** "Li-ion", "Li-poly"… as the phone spells it. */
        val technology: String?
    ) {
        /** Whether there is a figure at all. A reading without one is not worth drawing. */
        val known: Boolean get() = percent in 0..100
    }

    /** One entry in the record: what the charge was, and whether it was going up. */
    data class Sample(val at: Long, val percent: Int, val charging: Boolean)

    /**
     * The battery right now, read from the sticky broadcast.
     *
     * Null only where there is no broadcast to read at all, which happens on an emulator
     * with the battery service disabled and nowhere else.
     */
    fun read(context: Context): Reading? {
        val intent = try {
            // A null receiver asks for the last broadcast rather than subscribing, which
            // is the documented way to read a sticky one and costs nothing to unregister.
            context.applicationContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Log.w(TAG, "could not read the battery", e)
            null
        } ?: return null
        return from(intent)
    }

    /** The same, out of a broadcast that has just arrived. */
    fun from(intent: Intent): Reading {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(
            BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        // Tenths of a degree and thousandths of a volt, which is how the platform sends
        // both. A phone that does not measure one of them sends nothing, not a zero -
        // hence the sentinel rather than a default of 0.
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        return Reading(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = plug != 0,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
            plug = when {
                plug and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
                plug and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
                plug and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
                else -> null
            },
            temperature = if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f,
            voltage = if (millivolts == Int.MIN_VALUE) Float.NaN else millivolts / 1000f,
            health = healthWord(intent.getIntExtra(
                BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
                ?.takeIf { it.isNotBlank() }
        )
    }

    private fun healthWord(health: Int): String? = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failing"
        else -> null
    }

    // ------------------------------------------------------------------ the record

    /**
     * Writes this reading down, if it says anything the last one did not.
     *
     * The broadcast arrives for reasons that have nothing to do with the charge - a
     * degree of temperature, a millivolt - and a sample per broadcast would be a day's
     * worth of identical numbers. So a sample is kept when the figure moves, when the
     * phone goes on or off charge, or when enough time has passed that a flat stretch
     * still deserves a point on the chart.
     *
     * Safe to call from anywhere and as often as anything likes: it reads one string,
     * usually decides against, and writes nothing.
     */
    fun record(context: Context, reading: Reading) {
        if (!reading.known) return
        val now = System.currentTimeMillis()
        val samples = history(context).toMutableList()
        val last = samples.lastOrNull()
        if (last != null &&
            last.percent == reading.percent &&
            last.charging == reading.charging &&
            now - last.at < QUIET_MS
        ) return
        // A clock that has gone backwards - a manual change, a time zone the phone
        // decided about late - would leave the record out of order and the chart drawn
        // through itself. The samples ahead of now are dropped rather than sorted in:
        // they are readings of a future that has not happened.
        if (last != null && now < last.at) samples.removeAll { it.at > now }
        samples.add(Sample(now, reading.percent, reading.charging))
        save(context, samples.filter { now - it.at <= KEEP_MS })
    }

    /** Everything written down that is still worth keeping, oldest first. */
    fun history(context: Context): List<Sample> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val samples = mutableListOf<Sample>()
        for (entry in raw.split(';')) {
            val parts = entry.split(',')
            if (parts.size != 3) continue
            // Seconds on disk, milliseconds in memory: a day of them is a third shorter
            // written this way, and a second is finer than any chart of a day can show.
            val at = (parts[0].toLongOrNull() ?: continue) * 1000L
            val percent = parts[1].toIntOrNull() ?: continue
            if (at < cutoff) continue
            samples.add(Sample(at, percent.coerceIn(0, 100), parts[2] == "1"))
        }
        return samples
    }

    /** The last [hours] of it, which is what the chart is drawn from. */
    fun history(context: Context, hours: Int): List<Sample> {
        val since = System.currentTimeMillis() - hours * 60L * 60L * 1000L
        return history(context).filter { it.at >= since }
    }

    private fun save(context: Context, samples: List<Sample>) {
        // Oldest first, and never more than the ceiling: a phone whose level jitters
        // between two figures can write a sample a minute, and the string this lives in
        // is read on every broadcast.
        val kept = samples.takeLast(MAX_SAMPLES)
        val raw = kept.joinToString(";") {
            "${it.at / 1000L},${it.percent},${if (it.charging) 1 else 0}"
        }
        prefs(context).edit().putString(KEY_HISTORY, raw).apply()
    }

    /**
     * How much charge was spent over a stretch of the record.
     *
     * The falls only. A day with a charge in the middle of it went 80 to 30 and then 30 to
     * 90, and the difference between its two ends says the phone gained ten points - which
     * is true and is not what "what did today cost" is asking. Adding up the falls gives
     * the fifty that were actually spent.
     */
    fun drained(samples: List<Sample>): Int {
        var spent = 0
        for (i in 1 until samples.size) {
            val fall = samples[i - 1].percent - samples[i].percent
            if (fall > 0) spent += fall
        }
        return spent
    }

    /**
     * How long the charge left is likely to last, in milliseconds, or null.
     *
     * Worked out from the record rather than asked of the platform: `computeChargeTime`
     * answers only for charging, and only on phones that implement it. This is the last
     * stretch of discharge, projected forward at the rate it actually ran at.
     *
     * Null wherever the answer would be invented - on charge, with too little record to
     * see a rate in, or where the rate is so slow the projection runs into next week.
     */
    fun timeLeft(context: Context, reading: Reading): Long? {
        if (reading.charging || !reading.known) return null
        val samples = history(context, RATE_HOURS)
        // Back to the last time the phone was on charge: what happened before that is
        // another discharge, at a rate that says nothing about this one.
        val run = samples.takeLastWhile { !it.charging }
        val first = run.firstOrNull() ?: return null
        val last = run.lastOrNull() ?: return null
        val spent = first.percent - last.percent
        val over = last.at - first.at
        if (spent <= 0 || over < MIN_RATE_MS) return null
        val perPoint = over / spent.toDouble()
        val left = (reading.percent * perPoint).toLong()
        return left.takeIf { it in 1 until MAX_PROJECTION_MS }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private const val TAG = "BatteryStore"

    private const val KEY_HISTORY = "wp81_battery_history"

    /** How much of the record is kept. A little over the day the chart shows. */
    private const val KEEP_MS = 26L * 60L * 60L * 1000L

    /** However still the battery is, a point on the chart at least this often. */
    private const val QUIET_MS = 10L * 60L * 1000L

    /**
     * The most samples the record holds.
     *
     * A day at one every ten minutes is 156; the rest of the room is for the level
     * actually moving, which on a phone being used hard is a point every minute or two.
     */
    private const val MAX_SAMPLES = 720

    /** How far back a rate of discharge is read from. */
    private const val RATE_HOURS = 6

    /** Under this much of a run, a rate is noise rather than a rate. */
    private const val MIN_RATE_MS = 12L * 60L * 1000L

    /** Past this, "how long left" is a guess dressed as an answer. */
    private const val MAX_PROJECTION_MS = 4L * 24L * 60L * 60L * 1000L
}
