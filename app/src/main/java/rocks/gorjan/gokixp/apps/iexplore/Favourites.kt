package rocks.gorjan.gokixp.apps.iexplore

/**
 * A saved website.
 *
 * This and the two keys below used to live on `InternetExplorerApp`, the desktop browser,
 * because it wrote the format first and the phone's browser was a second view of the same
 * list. The desktop browser ships in the other launcher now, so the shared parts have a
 * file of their own rather than a home that no longer exists.
 *
 * The key names and the JSON shape are deliberately unchanged: they are what is already in
 * `taskbar_widget_prefs` on every phone this has run on, and renaming either would lose
 * everybody's favourites on upgrade.
 */
data class Favourite(
    val name: String,
    val url: String,
    val isDefault: Boolean = false
)

/** Where the favourites and the last page open are kept. */
internal object BrowserPrefs {
    const val KEY_FAVOURITES = "ie_favourites"
    const val KEY_LAST_URL = "ie_last_url"
}
