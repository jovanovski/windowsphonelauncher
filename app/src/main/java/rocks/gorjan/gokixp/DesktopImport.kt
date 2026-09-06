package rocks.gorjan.gokixp

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Brings a Windows Phone 8.1 setup across from the desktop launcher it used to live in.
 *
 * Windows Phone was a theme inside `rocks.gorjan.gokixp` before it became this app. That
 * launcher still holds the Start screen as it stood - tiles and their order, sizes,
 * colours, the hidden set, the accent, the background, and the icons picked by hand - and
 * it sets a copy aside on the update that removes the theme, precisely so this can read it
 * afterwards. See its `WP8Migration` and `WP8MigrationProvider`.
 *
 * The read is guarded on that side by a signature permission, so this only works between
 * two builds signed with the same key.
 */
object DesktopImport {

    private const val TAG = "DesktopImport"

    /** The launcher Windows Phone used to be a theme in. */
    const val DESKTOP_PACKAGE = "rocks.gorjan.gokixp"

    private const val AUTHORITY = "rocks.gorjan.gokixp.migration"
    private const val COLUMN_PATH = "path"

    /** That an import has already been offered, so it is offered once and not every launch. */
    const val KEY_IMPORT_OFFERED = "desktop_import_offered"

    /** The preference files the phone shell wrote outside the launcher's main one. */
    private val SIDE_PREFS = listOf("wp81_keyboard", "zune_prefs", "wp81_files")

    /**
     * What the desktop launcher is holding, or null when there is nothing to take.
     *
     * Null covers every ordinary case as well as failure: the desktop launcher is not
     * installed, it is too old to have a provider, this phone was never running the phone
     * theme, or the permission was refused because the two builds are signed differently.
     * None of those is worth troubling the user about - there is simply nothing to import.
     */
    fun available(context: Context): List<String>? = try {
        context.contentResolver
            .query(Uri.parse("content://$AUTHORITY/"), null, null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(COLUMN_PATH)
                val paths = mutableListOf<String>()
                while (cursor.moveToNext()) paths.add(cursor.getString(column))
                paths.takeIf { it.isNotEmpty() }
            }
    } catch (e: Exception) {
        Log.d(TAG, "Nothing to import from the desktop launcher: ${e.message}")
        null
    }

    /**
     * Copies the setup in, replacing what is here.
     *
     * Replacing, not merging: [PrefsBackup.restore] clears before it writes, because a
     * half-merged Start screen - some tiles from there, some from here, two ideas about
     * which is where - is worse than either. So this is meant for a launcher that has not
     * been arranged yet, which is why it is offered once, on first run.
     *
     * The files matter as much as the preferences. The icon map stores *paths*
     * (`imported_icons/<file>.png`) and the Start background is a bare file, so preferences
     * alone would arrive as a Start screen of broken icons on a blank background.
     */
    fun importFrom(context: Context, paths: List<String>): Boolean {
        return try {
            // Preferences first: if this fails there is no point copying the files it
            // would have pointed at.
            val mainJson = readText(context, "prefs.json")
                ?: run { Log.w(TAG, "The snapshot has no prefs.json"); return false }
            PrefsBackup.restore(
                context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE),
                mainJson
            )

            for (name in SIDE_PREFS) {
                val json = readText(context, "$name.json") ?: continue
                PrefsBackup.restore(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE), json)
            }

            // Then what the preferences refer to by path.
            paths.filter { it.startsWith("files/") }.forEach { path ->
                val destination = File(context.filesDir, path.removePrefix("files/"))
                destination.parentFile?.mkdirs()
                copy(context, path, destination)
            }

            Log.i(TAG, "Imported the Windows Phone setup from the desktop launcher")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not import from the desktop launcher", e)
            false
        }
    }

    private fun readText(context: Context, path: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/$path"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun copy(context: Context, path: String, destination: File) {
        try {
            context.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/$path"))
                ?.use { input -> destination.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            // One unreadable icon is not worth failing the whole import over: the tile it
            // belongs to falls back to the app's own artwork, which is what it would have
            // shown had the icon never been picked.
            Log.w(TAG, "Skipped $path", e)
        }
    }
}
