package rocks.gorjan.gokixp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeping the copy on Google Drive up to date without being asked.
 *
 * Backup started as four taps on a page, which is the honest shape for a restore - it
 * replaces everything and nobody should be able to do that by accident - but the wrong
 * shape for a backup. A copy of the Start screen is worth having on the day the phone goes
 * in the river, and on that day what matters is not whether the user was willing to back up
 * but whether they remembered to. So this is the switch that remembers for them.
 *
 * Drive only, and that is not a shortcut. The file export goes through the system's
 * document picker: the user chooses where it lands, every time, which is exactly what makes
 * it useful and exactly what makes it impossible to do while nobody is looking. Drive is
 * the destination this app can write to on its own - one file, replaced each time - so it
 * is the one a daily backup can use. See [GoogleDriveHelper].
 *
 * Nothing here says anything in the notification shade. A backup that worked is not news,
 * and a backup that failed - no account, no network, a phone that has been in a drawer -
 * would otherwise say so every single day until it was noticed. The truth is on the backup
 * page instead, where it was already being told: "back up to google drive" carries the date
 * of the last copy that actually got there, automatic or hand-made. Somebody who goes
 * looking can see the answer, and somebody who does not is not nagged about it.
 */
object AutoBackup {

    /**
     * Whether the launcher keeps Drive up to date by itself.
     *
     * Off until asked for. It signs the user into an account and puts their settings on
     * somebody else's computer once a day - not a thing to start doing because a launcher
     * was installed.
     *
     * Deliberately *not* in [SettingsBackup.DEVICE_KEYS], so it travels in a backup: a new
     * phone restored from this one should go on keeping itself backed up, which is what its
     * owner already said they wanted. It needs a Google account signed in to do anything,
     * and until there is one the attempts fail quietly and the page keeps showing the old
     * date - see [runIfDue].
     */
    const val KEY_ENABLED = "wp81_auto_backup"

    /**
     * How often, and how old a copy has to be before another one is worth making.
     *
     * The alarm is booked a day after the last copy that got there. [DUE_AFTER_MS] is the
     * question asked by whoever arrives - the alarm, or the launcher coming to the
     * foreground - and it is the hour short of a day rather than the day itself, so that
     * the foreground path can pick up a backup the alarm is about to be woken for anyway.
     * Which is worth having: a phone in Doze can have its wakeup held for hours, and a
     * phone that was switched off overnight has no wakeup left to hold. Both are answered
     * by the first time somebody picks the phone up, when there is a screen, a network and
     * an unlocked account to work with.
     *
     * An hour either way costs nothing. The copy on Drive is replaced, not added to.
     */
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val DUE_AFTER_MS = 23L * 60 * 60 * 1000

    /**
     * When to try again after a failure, and the floor under every booking.
     *
     * A failed attempt leaves the stamp where it was, so the next booking would be for a
     * moment already past and the alarm would come straight back - a phone with no account
     * on it would spend the day retrying. Two hours is the shortest this will ever retry,
     * which is often enough to catch a phone that was simply out of signal at three in the
     * morning and rare enough to be invisible.
     */
    private const val RETRY_MS = 2L * 60 * 60 * 1000

    /** One run at a time: the alarm and the launcher coming forward can land together. */
    private val running = AtomicBoolean(false)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        sync(context)
    }

    /**
     * Puts the phone's booking in step with the setting.
     *
     * Called whenever anything it depends on moves - the switch, a backup that succeeded, a
     * restart, an update, a restore - and it is safe to call at any time: the old booking is
     * always taken down first, so nothing here can leave two alarms in flight.
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        val manager = alarms(app) ?: return

        pending(app, create = false)?.let {
            manager.cancel(it)
            it.cancel()
        }
        if (!isEnabled(app)) {
            Log.d(TAG, "Daily backup is off; nothing booked")
            return
        }

        val operation = pending(app, create = true) ?: return
        val at = nextRun(app, System.currentTimeMillis())
        // Not setExact: a backup is not an appointment, and the exact-alarm permission is
        // for things the user would notice being late. Allowed through Doze all the same,
        // because "when the phone is left alone overnight" is precisely when this runs -
        // and the allowance is what buys the few seconds of network the upload needs.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        Log.d(TAG, "Next automatic backup in ${(at - System.currentTimeMillis()) / 60000} min")
    }

    /**
     * The moment the next copy is worth making.
     *
     * A day after the last one that got there, floored at [RETRY_MS] from now so that a
     * stamp in the past - a failure, a phone that has been off for a week, a switch just
     * turned on - books a retry rather than an alarm for a moment that has already gone.
     */
    private fun nextRun(context: Context, now: Long): Long {
        val last = lastBackup(context)
        val due = if (last > 0L) last + INTERVAL_MS else now + RETRY_MS
        return maxOf(due, now + RETRY_MS)
    }

    /**
     * Makes a copy if the one on Drive is old enough to be worth replacing, and books the
     * next attempt either way.
     *
     * The due check is done here rather than trusted to the alarm, because this is reached
     * from two directions: the booking, and the launcher coming back to the foreground. The
     * second is what makes this reliable on a phone that is asleep at the hour its backup
     * was booked for - Doze can defer a wakeup for hours, but the phone being picked up ends
     * Doze, and by then there is a screen on, a network and an unlocked keystore for the
     * account. It also means a phone that was switched off for a fortnight has a fresh copy
     * within moments of being turned on rather than a day later.
     *
     * Cheap to call: it reads two preferences and does nothing at all unless a backup is
     * actually owed, so the foreground path can sit in onResume without costing anything.
     *
     * Returns whether a copy was actually started, which is the contract [onDone] hangs
     * off: it is called exactly when this returns true, and never otherwise. A caller
     * holding something open across the work - the receiver holds the broadcast - has to
     * know which of the two happened, and "the callback did not come" is not an answer that
     * arrives in time to be useful.
     */
    fun runIfDue(context: Context, onDone: ((String?) -> Unit)? = null): Boolean {
        val app = context.applicationContext
        if (!isEnabled(app)) return false
        if (System.currentTimeMillis() - lastBackup(app) < DUE_AFTER_MS) return false
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "A backup is already running")
            return false
        }
        Thread {
            val failure = try {
                backUp(app)
            } finally {
                running.set(false)
            }
            // After the stamp has moved, so success books tomorrow and failure books the
            // retry. Both go through the same call, which is the only place a booking is
            // made - see [sync].
            sync(app)
            onDone?.invoke(failure)
        }.start()
        return true
    }

    /**
     * Writes the copy, and says what went wrong if anything did.
     *
     * Blocks, and must not be called on the main thread. The stamp is written only on the
     * way out of a successful upload: it is the answer to "when was this phone last backed
     * up", and an attempt is not a backup.
     */
    private fun backUp(context: Context): String? {
        val drive = GoogleDriveHelper(context)
        if (!drive.isSignedIn()) {
            Log.i(TAG, "Daily backup skipped: no Google account with Drive permission")
            return "not signed in to google drive"
        }
        return try {
            drive.upload(SettingsBackup.snapshot(context))
            prefs(context).edit {
                putLong(SettingsBackup.KEY_LAST_DRIVE_BACKUP, System.currentTimeMillis())
            }
            Log.i(TAG, "Daily backup written to Drive")
            null
        } catch (e: Exception) {
            Log.w(TAG, "The daily backup did not go", e)
            e.message ?: "drive would not take the file"
        }
    }

    private fun lastBackup(context: Context): Long =
        prefs(context).getSafeLong(SettingsBackup.KEY_LAST_DRIVE_BACKUP, 0L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private fun alarms(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * The booking, as one pending intent.
     *
     * Its own request code, well clear of the alarms app's ranges - two pending intents
     * that differ only in ways which are easy to get wrong are two that will one day be the
     * same one. `FLAG_NO_CREATE` is how [sync] asks whether there is a booking to take down
     * without making one in the act of asking.
     */
    private fun pending(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, AutoBackupReceiver::class.java).apply {
            action = ACTION_DUE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            if (create) flags else flags or PendingIntent.FLAG_NO_CREATE
        )
    }

    const val ACTION_DUE = "rocks.gorjan.gokixp.AUTO_BACKUP_DUE"

    private const val REQUEST_CODE = 810_001
    private const val TAG = "AutoBackup"
}

/**
 * The daily backup coming due, and everything the system says that invalidates it.
 *
 * Exported, because a reboot and an update are broadcasts from outside the app and a
 * manifest receiver cannot hear them otherwise. Both wipe what AlarmManager was holding, so
 * in both cases the booking has to be made again from what the user asked for - the same
 * arrangement, and for the same reason, as the alarms app's boot receiver.
 *
 * Being exported means anything on the phone can send [AutoBackup.ACTION_DUE] here too, and
 * that is harmless by construction rather than by luck: this receiver decides nothing. It
 * asks [AutoBackup.runIfDue], which makes a copy only if the one on Drive is already a day
 * old, so the most a caller can achieve by shouting at it is the backup that was going to
 * happen anyway - and only if the user turned the switch on in the first place.
 */
class AutoBackupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AutoBackup.ACTION_DUE -> {
                // A receiver has about ten seconds and this one talks to the network, so
                // the broadcast is held open across the upload and released on the far side
                // of it. Without that the process is a candidate for death the moment
                // onReceive returns, which on a bad day is halfway through the file.
                val pending = goAsync()
                val started = AutoBackup.runIfDue(app) {
                    try {
                        pending.finish()
                    } catch (e: Exception) {
                        Log.d(TAG, "The broadcast was already finished", e)
                    }
                }
                if (!started) {
                    // Nothing was owed - the alarm was early, or the user made a copy by
                    // hand in the meantime. The booking still has to be renewed here, since
                    // the one that just went off was the last one there was.
                    AutoBackup.sync(app)
                    pending.finish()
                }
            }
            else -> {
                Log.d(TAG, "Rebooking the daily backup after ${intent.action}")
                AutoBackup.sync(app)
            }
        }
    }

    private companion object {
        const val TAG = "AutoBackup"
    }
}
