package rocks.gorjan.gokixp.apps.alarms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import rocks.gorjan.gokixp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import rocks.gorjan.gokixp.wp81.metroLook

/**
 * The notice an alarm gives before it goes off.
 *
 * There is a particular moment this is for: awake before the alarm, and about to be
 * startled by one you no longer need. Without it the only ways out are to unlock the
 * phone, find the app and turn the alarm off - which turns off *every* morning, not this
 * one - or to wait and be woken by something you were already awake for.
 *
 * So it is a notification with one command on it, and that command is the narrow thing it
 * sounds like: not this morning, same time as usual after that. It goes away by itself
 * when the alarm comes due, because at that point it is either ringing or it is not, and
 * either way a notice about it is out of date.
 *
 * An ordinary notification on a channel of its own - it arrives with the phone's usual
 * notification sound, like anything else worth reading. Two hours' notice is long enough
 * that it lands while you are doing something else, and something that appeared in silence
 * is something you find afterwards rather than in time to act on it.
 *
 * A snooze puts the same notice back up - see [postSnooze] - reading the time the alarm is
 * now due rather than the time it was set for. It is the same question at a shorter range:
 * something is about to make a noise at you, here is when, and here is the one way to call
 * it off that does not involve waiting for it. Deliberately the same line in the shade
 * rather than a second one, because it is the same alarm.
 */
object AlarmWarning {

    /**
     * Puts the notice up for [occurrence], the moment the alarm is due.
     *
     * The time shown is the alarm's, not the notification's: the whole content is "the
     * thing you are about to be woken by happens at seven", and posting it at five does not
     * make five the interesting number.
     */
    fun post(context: Context, alarm: Alarm, occurrence: Long) =
        show(context, alarm, occurrence, snoozed = false)

    /**
     * The same notice, moved on to the snooze the alarm is now under.
     *
     * A phone that has been snoozed and put back down says nothing about what it is about
     * to do; the only record of the next few minutes is a row in an app nobody is going to
     * open at that hour. So the notice the alarm gave before it rang comes back, reading
     * the time it is due again - which is the one fact anybody wants after pressing snooze
     * and which, at three minutes a press, changes every time it is pressed.
     *
     * The same notification id as [post], so a snooze pressed four times moves one line in
     * the shade rather than leaving four. Marked ongoing, unlike the two-hour notice: that
     * one is a heads-up that has been read once it has been read, while this is the state
     * the alarm is in, and it lasts minutes rather than hours. Its Dismiss ends the snooze
     * outright - see [AlarmStore.dismissNext] - which makes it the only way to call off an
     * alarm that is coming back without waiting for it to arrive.
     */
    fun postSnooze(context: Context, alarm: Alarm, until: Long) =
        show(context, alarm, until, snoozed = true)

    private fun show(context: Context, alarm: Alarm, moment: Long, snoozed: Boolean) {
        ensureChannel(context)
        val pattern =
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val at = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(moment))

        val open = PendingIntent.getActivity(
            context, OPEN_CODE,
            Intent(context, rocks.gorjan.gokixp.MainActivity::class.java).apply {
                action = AlarmScheduler.ACTION_SHOW_ALARMS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Straight to the receiver rather than to a screen: dismissing an alarm you are
        // already awake for should not make you look at anything.
        val dismiss = PendingIntent.getBroadcast(
            context, dismissCode(alarm.id),
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_DISMISS_NEXT
                data = Uri.parse("gokixp://alarm/dismiss/${alarm.id}")
                putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.wp81_notify_alarm)
            .setContentTitle("Upcoming alarm at $at")
            // The name underneath, and only when there is one. With three alarms set, which
            // of them is coming is the one thing the line above cannot say; with an unnamed
            // alarm there is nothing to add and a second line saying "Alarm" would be the
            // notification repeating itself. A snooze says so here, because a time three
            // minutes from now is otherwise indistinguishable from an alarm set for it.
            .apply {
                val name = alarm.name.takeIf { it.isNotBlank() }
                val text = when {
                    snoozed && name != null -> "Snoozed - $name"
                    snoozed -> "Snoozed"
                    else -> name
                }
                if (text != null) setContentText(text)
            }
            .metroLook(context)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setWhen(moment)
            // The two-hour notice is swipeable on purpose. Somebody who flicks it away has
            // read it and decided to let the alarm ring, which is a perfectly good answer,
            // and one the alarm needs no help to carry out. A snooze is not a heads-up but
            // a state the phone is in for the next few minutes, so it stays put - and it
            // takes itself down when the alarm comes back round.
            .setOngoing(snoozed)
            // The two-hour notice arrives with the phone's usual notification sound - see
            // the note on the channel - because it is no use if it is not noticed. A snooze
            // is the other way about: the phone has this second stopped making as much
            // noise as it knows how, in a dark room, and a chime on top of that is the app
            // answering a button press it was already asked to answer quietly.
            //
            // A channel's importance is the user's and cannot be talked down per
            // notification, so silence is asked for the only way the platform offers: the
            // notice is made the sole child of a group whose alerting is the summary's
            // business, and no summary is ever posted. A group of its own per alarm, so
            // two alarms snoozed at once are still two lines rather than a bundle.
            .apply {
                if (snoozed) {
                    setGroup("$SNOOZE_GROUP${alarm.id}")
                    setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
            }
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Dismiss", dismiss).build()
            )
            .build()

        try {
            notifications(context).notify(notificationId(alarm.id), notification)
        } catch (e: Exception) {
            Log.w(TAG, "The system would not take the upcoming-alarm notification", e)
        }
    }

    /** Takes it down: the alarm has come due, been dismissed, been edited or been deleted. */
    fun clear(context: Context, alarmId: Long) {
        try {
            notifications(context).cancel(notificationId(alarmId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear the upcoming-alarm notification", e)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = notifications(context)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        // A channel cannot be changed once it exists - importance, sound and vibration are
        // the user's from that moment on, and an app that could quietly turn its own
        // notifications back up would make that setting worthless. This notice started life
        // silent and at low importance, so making it an ordinary one means a new channel
        // and taking the old one away, rather than an edit that would do nothing at all on
        // any phone that already had it.
        manager.deleteNotificationChannel(RETIRED_CHANNEL)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL, "Upcoming alarms", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "A notice two hours before an alarm rings"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun notifications(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** One line per alarm, so two alarms coming up are two notices rather than one. */
    private fun notificationId(alarmId: Long): Int = ID_BASE + (alarmId % 100).toInt()

    private fun dismissCode(alarmId: Long): Int = DISMISS_BASE + (alarmId % 100).toInt()

    /** See the note in [show]: what makes the snooze notice arrive without a sound. */
    private const val SNOOZE_GROUP = "wp81_alarm_snooze_"

    private const val CHANNEL = "wp81_alarms_upcoming_audible"

    /** The silent, low-importance channel this notice used to be on. See [ensureChannel]. */
    private const val RETIRED_CHANNEL = "wp81_alarms_upcoming"
    private const val ID_BASE = 8300
    private const val DISMISS_BASE = 8400
    private const val OPEN_CODE = 8499

    private const val TAG = "WP81Alarms"
}
