package rocks.gorjan.gokixp.wp81.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81Settings

/**
 * How the accent and the light-or-dark reach the keyboard, now that it lives somewhere else.
 *
 * The keyboard runs in a process of its own. That is what makes it a keyboard rather than a
 * part of the launcher wearing a keyboard's clothes: an input method has to answer questions
 * about the text box it is typing into, and when the box belongs to the launcher and the
 * keyboard is in the launcher too, the thread that must answer is the thread that is waiting
 * for the answer. Nothing comes back and the phone stops for two seconds. Two processes are
 * two threads, and the question is answered in microseconds like anybody else's.
 *
 * The bill for that is this file. The keyboard's own settings moved into the keyboard's own
 * preferences file and are read and written on one side of the line - see
 * [WP81Settings.migrateKeyboardSettings] - but the accent and the background are the
 * *launcher's*, chosen on the launcher's settings page, and the keyboard has to wear them.
 * A preference file cannot carry them across: SharedPreferences reads the file once per
 * process and never looks again, so the keyboard's copy is right when its process starts and
 * frozen from then on.
 *
 * So the launcher says so out loud. [publish] is the launcher's half, called where the
 * appearance is committed; [watch] is the keyboard's, and [palette] is what the keyboard
 * builds its colours from - the last thing it was told, or what was on disk when its process
 * began, which is correct because that read was fresh.
 *
 * A broadcast is fast enough here and would not be everywhere. Picking an accent is a tap on
 * a colour, twenty times at the very most; the keyboard's own settings - the key height
 * slider being dragged with the keyboard live underneath it - fire many times a second, and
 * those never come through here at all. They are in the keyboard's process already, where a
 * preference listener answers them with no message passing of any kind.
 */
internal object KeyboardAppearance {

    private const val ACTION = "rocks.gorjan.gokixp.wp81.keyboard.APPEARANCE"
    private const val EXTRA_ACCENT = "accent"
    private const val EXTRA_DARK = "dark"

    /** The last appearance heard about, or null until the first read. */
    private var accent: Int? = null
    private var dark = true

    /**
     * The launcher's half: says what the appearance has become.
     *
     * Explicit to this package, so it is a message to the keyboard rather than something
     * shouted at the phone. Nothing else can hear it and nothing else would want to.
     */
    fun publish(context: Context, accent: Int, dark: Boolean) {
        context.sendBroadcast(
            Intent(ACTION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_ACCENT, accent)
                .putExtra(EXTRA_DARK, dark)
        )
    }

    /**
     * The keyboard's half: listens, and calls [onChanged] once the new colours are in hand.
     *
     * @return the receiver, to be handed back to [stopWatching] when the keyboard goes away.
     */
    fun watch(context: Context, onChanged: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(heard: Context?, intent: Intent?) {
                val told = intent ?: return
                if (!told.hasExtra(EXTRA_ACCENT)) return
                accent = told.getIntExtra(EXTRA_ACCENT, 0)
                dark = told.getBooleanExtra(EXTRA_DARK, true)
                onChanged()
            }
        }
        // Not exported. The launcher and the keyboard are the same application, so this is a
        // message between two of its own processes and the system is told to keep it that
        // way - an input method is bound by every application on the phone, and a receiver
        // of its that anybody could reach would be a way to repaint it from outside.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }

    /** Undoes [watch]. Safe to call with a receiver that is already gone. */
    fun stopWatching(context: Context, receiver: BroadcastReceiver?) {
        if (receiver == null) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered, which is not worth a crash in the process that holds
            // text entry for the whole phone.
        }
    }

    /**
     * The colours to draw with.
     *
     * Falls back to the file for as long as nothing has been published, which covers the
     * ordinary case completely: the keyboard's process reads it when the process starts, and
     * a read at that moment is a read of what is really there.
     */
    fun palette(settings: WP81Settings): WP81Palette =
        accent?.let { WP81Palette.of(it, dark) } ?: WP81Palette.from(settings)
}
