package rocks.gorjan.gokixp.wp81

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * Hands the phone's theme to the author's other apps, so the phone is one phone.
 *
 * Windows Phone's accent was never a launcher setting. It was set once in Settings > theme
 * and every app on the device wore it - tiles, headings, the caret, the send button - which
 * is why a WP8.1 phone looked like one object rather than like a Start screen with a pile of
 * unrelated programs behind it. An app of the author's that draws its own WP8.1 chrome and
 * then picks its own accent is the one thing that reads as wrong on a phone this launcher is
 * running: two Windows Phones on one screen, in different colours.
 *
 * So the launcher publishes the two knobs from Settings > theme and the others follow. One
 * row, three columns, read-only; the reader is expected to take [COLUMN_ACCENT] and
 * [COLUMN_DARK] and build its own palette out of them, exactly as [WP81Palette.of] does here.
 *
 * **Guarded by a `normal` permission, not a signature one** - unlike the desktop launcher's
 * migration provider, which hands over the whole Start screen and is rightly locked to one
 * signing key. What is here is a colour and a light switch. There is nothing in it to protect
 * and nothing that could be done with it, and a signature guard would quietly stop working the
 * day one of the two apps is released under a different key from the other. `normal` still
 * gives the contract a name and keeps it out of the hands of anything that has not asked for
 * it in its manifest.
 *
 * **Live.** The cursor is registered against [CONTENT_URI] and [WP81Settings] notifies it on
 * every change, so a reader that keeps a `ContentObserver` sees the accent turn at the moment
 * the user taps it here rather than the next time it happens to look.
 */
class WP81ThemeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * The theme as it stands: one row, always.
     *
     * The live preferences rather than a copy set aside, which is the other difference from
     * the migration provider - this is not a handover that happened once, it is what the phone
     * looks like right now.
     *
     * The projection is ignored. Three columns of two ints and a string is smaller than the
     * cost of honouring a projection over it, and a reader asking for a column that is not
     * there gets a clearer failure from a full row than from a cursor built to its own
     * mistaken idea of the schema.
     */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        return try {
            val settings = WP81Settings(context)
            val accent = settings.getWP81Accent()
            val cursor = MatrixCursor(arrayOf(COLUMN_ACCENT, COLUMN_ACCENT_NAME, COLUMN_DARK))
            cursor.addRow(
                arrayOf<Any?>(
                    accent,
                    WP81Settings.WP81_ACCENTS.firstOrNull { it.second == accent }?.first,
                    if (settings.isWP81Dark()) 1 else 0
                )
            )
            cursor.setNotificationUri(context.contentResolver, CONTENT_URI)
            cursor
        } catch (e: Exception) {
            // A reader that gets null treats it as "no phone theme to follow" and keeps its
            // own, which is the same thing it does when this launcher is not installed. There
            // is nothing here worth failing somebody else's screen over.
            Log.w(TAG, "Could not read the theme", e)
            null
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.theme"

    // Read-only. The accent is set on the phone, in Settings > theme, and nowhere else - an
    // app that could set it from outside would be an app that can repaint the Start screen.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val TAG = "WP81ThemeProvider"

        /**
         * Must track `applicationId` in build.gradle.kts, not the namespace.
         *
         * The two differ here: the sources say `rocks.gorjan.gokixp` because this app grew out
         * of the desktop launcher, while the app it installs as is `rocks.gorjan.gokiwp`. An
         * authority is resolved against the installed package, so it is the latter - and the
         * manifest writes it as `${applicationId}.theme` for the same reason.
         */
        const val AUTHORITY = "rocks.gorjan.gokiwp.theme"

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/theme")

        /** The accent, as an ARGB int. One of [WP81Settings.WP81_ACCENTS]. */
        const val COLUMN_ACCENT = "accent"

        /**
         * The accent's name on the phone's own picker - "Lime", "Cobalt".
         *
         * For a reader that wants to say which colour is in force rather than only draw it.
         * Null if the accent is not one of the twenty, which the picker cannot currently
         * produce but a restored backup from some later version could.
         */
        const val COLUMN_ACCENT_NAME = "accent_name"

        /** 1 for the Dark background, 0 for Light. */
        const val COLUMN_DARK = "dark"

        /**
         * Tell whoever is following that the theme has turned.
         *
         * Called from [WP81Settings] rather than from the picker, so that it covers every way
         * the setting can move - the theme page, a restored backup, an import from the desktop
         * launcher - rather than only the one the user taps.
         */
        fun notifyChanged(context: Context) {
            try {
                context.contentResolver.notifyChange(CONTENT_URI, null)
            } catch (e: Exception) {
                // Nobody is listening, or the resolver is gone with the process. Neither is
                // worth failing a settings write over: the next reader queries and gets the
                // new value regardless.
                Log.d(TAG, "Could not announce the theme change: ${e.message}")
            }
        }
    }
}
