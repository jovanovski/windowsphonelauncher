package rocks.gorjan.gokixp

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole of what this phone remembers, as one JSON document.
 *
 * [PrefsBackup] does one preference file; this does the app. The difference matters
 * because the app writes several - the launcher's own `taskbar_widget_prefs`, the
 * keyboard's `wp81_keyboard`, and one each for Files, Zune and the games - and a backup
 * that carried only the first would restore a Start screen with a keyboard set back to
 * its defaults and a music library that had forgotten its playlists. The desktop
 * launcher's Registry Editor exported exactly that one file, and this is the same idea
 * done properly.
 *
 * The files are found rather than listed. `shared_prefs` is where Android puts every one
 * of them, so reading the directory is the only account of "all of them" that stays true
 * when the next app adds a file of its own - a hard-coded list is a list somebody has to
 * remember to add to, and the failure is silent.
 */
object SettingsBackup {

    private const val TAG = "SettingsBackup"

    /** How this document is shaped, so a future change can tell what it is reading. */
    private const val FORMAT = 1

    private const val KEY_FORMAT = "format"
    private const val KEY_CREATED = "created"
    private const val KEY_APP = "app"
    private const val KEY_PREFS = "prefs"

    /** Where the last backup to each destination got to, so settings can say when. */
    const val KEY_LAST_DRIVE_BACKUP = "wp81_last_backup_drive"
    const val KEY_LAST_FILE_BACKUP = "wp81_last_backup_file"

    /**
     * Entries that belong to the phone rather than to its settings.
     *
     * One list doing both halves of the job, because they are the same judgement: a
     * preference that is not worth writing into a backup is not one a restore should
     * destroy either. So these are skipped on the way out and survive the clear on the way
     * back in - a restore leaves them exactly as it found them.
     *
     * The clipboard history is text the user copied out of their own messages and
     * passwords. It is content the keyboard is holding, not a preference about how the
     * keyboard behaves, and content has no business travelling on in a settings backup -
     * the same rule that retired MSN Messenger's read stamps; see MainActivity's sweep.
     * Nor should restoring last month's settings empty the clipboard somebody is midway
     * through using.
     *
     * The two backup stamps are here because they are this phone's history: carried in a
     * backup, restoring March's copy would report that the last backup was in March, which
     * is true of the file and false of the phone.
     */
    private val DEVICE_KEYS = setOf(
        "wp81_kb_clipboard",
        KEY_LAST_DRIVE_BACKUP,
        KEY_LAST_FILE_BACKUP
    )

    /**
     * Preference files that are in `shared_prefs` but are not this app's settings.
     *
     * A library that keeps state does it in a file beside the app's own, and Google's
     * sign-in is the one that matters here: it writes `com.google.android.gms.signin`,
     * which is a note about an account on *this* phone. Carried in a backup and put back
     * on another one, it is a claim to be signed in as somebody whose token this device
     * does not have - Drive then fails in a way that looks like the launcher being broken.
     * WebView writes its own file too, and it is nobody's settings.
     *
     * Matched by prefix, because none of these are one file: the point is that a library's
     * files are named after the library's package, and this app's - `taskbar_widget_prefs`,
     * `wp81_keyboard`, `zune_prefs` and the rest - are named after what is in them. The
     * app's own package is `rocks.gorjan.*`, so nothing of its own can match.
     *
     * Applied when a backup is read as well as when one is written. A file handed to the
     * launcher by a user is a file that could name anything, and a backup is not a licence
     * to write into another library's state.
     */
    private val FOREIGN_PREFIXES = listOf(
        "com.", "org.", "androidx.", "android.", "WebView", "google"
    )

    /**
     * Every preference file the app has written, as one document.
     *
     * Read through [Context.getSharedPreferences] rather than off the XML, so what is
     * captured is what the app itself would read - including anything an editor is still
     * holding in memory.
     */
    fun snapshot(context: Context): String {
        val prefs = linkedMapOf<String, Any?>()
        for (name in preferenceFiles(context)) {
            val file = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs[name] = PrefsBackup.toMap(file, DEVICE_KEYS)
        }

        val document = linkedMapOf<String, Any?>(
            KEY_FORMAT to FORMAT,
            KEY_CREATED to System.currentTimeMillis(),
            KEY_APP to appVersion(context),
            KEY_PREFS to prefs
        )
        Log.i(TAG, "Backed up ${prefs.size} preference files")
        return GsonBuilder().setPrettyPrinting().create().toJson(document)
    }

    /**
     * Puts a backup back, and says how many preference files it carried.
     *
     * Each file is replaced rather than merged, for the reason [DesktopImport] replaces:
     * half a Start screen from here and half from there is worse than either of the two it
     * came from. Files this phone has that the backup does not are left alone - a backup
     * says what it holds, not what the phone should stop holding.
     *
     * Throws on anything that is not a backup, so the caller can say so rather than the
     * user finding out by way of an emptied launcher.
     */
    fun restore(context: Context, json: String): Int {
        val document = Gson().fromJson(json, Map::class.java) as? Map<*, *>
            ?: throw IllegalArgumentException("That file is not a settings backup")

        val files = document[KEY_PREFS] as? Map<*, *>
            // A document with no `prefs` block is the desktop launcher's Registry Editor
            // export, or one of this app's own from before there were several files: one
            // flat map of the launcher's own preferences. It is still a backup worth
            // taking, so it is read as the one file it is.
            ?: mapOf(MainActivity.PREFS_NAME to document)

        var restored = 0
        for ((rawName, values) in files) {
            val name = rawName as? String ?: continue
            val map = values as? Map<*, *> ?: continue
            // A name with a separator in it would be a path, and a path out of a file the
            // user was handed is a write somewhere this app did not choose.
            if (name.isEmpty() || name.contains('/') || name.contains(File.separatorChar)) {
                Log.w(TAG, "Skipping a preference file with a suspect name: $name")
                continue
            }
            if (!isOwnFile(name)) {
                Log.w(TAG, "Skipping $name: not this app's own settings")
                continue
            }
            PrefsBackup.restore(
                context.getSharedPreferences(name, Context.MODE_PRIVATE), map, DEVICE_KEYS)
            restored++
        }

        if (restored == 0) throw IllegalArgumentException("That backup holds no settings")
        Log.i(TAG, "Restored $restored preference files")
        return restored
    }

    /**
     * When a backup was taken, or zero if it does not say.
     *
     * Read before a restore rather than after it, so the user is asked about a backup they
     * can date: "restore" and "restore last March's settings" are different questions, and
     * only one of them can be answered by somebody who has two phones and a file manager.
     * Zero covers a document from before this format as well as one that is not a backup at
     * all - neither is worth a separate answer, since the prompt simply says the date is
     * unknown and asks anyway.
     */
    fun createdAt(json: String): Long = try {
        val document = Gson().fromJson(json, Map::class.java) as? Map<*, *>
        (document?.get(KEY_CREATED) as? Number)?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** What a backup is called when the user is asked where to put it. */
    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "windows-phone-settings-$stamp.json"
    }

    /**
     * The names of the app's preference files, without the `.xml` Android keeps them under.
     *
     * `shared_prefs` has no public accessor, which is why this goes at the directory: the
     * alternative is a hard-coded list of the six files that exist today, and the seventh
     * would be missing from every backup with nothing to say so. If the directory cannot
     * be read at all, the launcher's own file is still worth having.
     */
    private fun preferenceFiles(context: Context): List<String> {
        val directory = File(context.dataDir, "shared_prefs")
        val listed = directory.listFiles { file -> file.name.endsWith(".xml") }
        if (listed == null) {
            Log.w(TAG, "Could not read $directory - backing up the launcher's own file only")
            return listOf(MainActivity.PREFS_NAME)
        }
        val own = listed.map { it.name.removeSuffix(".xml") }.filter { isOwnFile(it) }.sorted()
        // Nothing at all would mean a launcher that has never written a preference, which
        // is a backup of nothing rather than a failure - but its own file is always worth
        // asking for, since asking creates it and an empty one costs nothing.
        return own.ifEmpty { listOf(MainActivity.PREFS_NAME) }
    }

    /** Whether a preference file is the app's own settings rather than a library's state. */
    private fun isOwnFile(name: String): Boolean =
        FOREIGN_PREFIXES.none { name.startsWith(it, ignoreCase = true) }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
