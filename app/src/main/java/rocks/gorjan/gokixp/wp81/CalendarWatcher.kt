package rocks.gorjan.gokixp.wp81

import android.os.Handler
import android.os.Looper

/**
 * Ticks while the Start screen is up, so the calendar tile notices the day moving.
 *
 * This replaces the desktop's `CalendarDataProvider`, which the phone borrowed. That class
 * queried the calendar every thirty seconds and handed back a phrase - "in 20 minutes" -
 * for a panel that no longer exists. The tile never used the answer: it re-reads the day
 * itself, because a tile shows a name and a time rather than a phrase. So all that was
 * wanted from 585 lines was the thirty seconds, and the query behind it was work whose
 * result was thrown away.
 *
 * Deliberately no permission check of its own. The tile's refresh does it, and doing it
 * twice would only mean two answers to keep in step.
 */
class CalendarWatcher {

    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Calls [onTick] now, and every 30 seconds after, on the main thread. */
    fun start(onTick: () -> Unit) {
        stop()
        val runnable = object : Runnable {
            override fun run() {
                onTick()
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        tick = runnable
        handler.post(runnable)
    }

    fun stop() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
    }

    private companion object {
        const val INTERVAL_MS = 30_000L
    }
}
