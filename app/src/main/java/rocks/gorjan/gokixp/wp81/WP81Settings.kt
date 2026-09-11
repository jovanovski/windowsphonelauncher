package rocks.gorjan.gokixp.wp81

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.SettingsBackup
import rocks.gorjan.gokixp.getSafeInt
import rocks.gorjan.gokixp.getSafeLong
import rocks.gorjan.gokixp.R

/**
 * Where the user's hand-picked icons are kept.
 *
 * This was `AppTheme.customIconsKey`, one per theme, because an icon is chosen for the
 * shell that is on screen. There is one shell now, so there is one key - but it keeps the
 * phone's original spelling, because that is what is already in `taskbar_widget_prefs` on
 * every phone this has run on.
 */
const val CUSTOM_ICONS_KEY = "custom_icons_wp8"

/**
 * The desktop launcher's icon keys, read only when a setup is carried over from it.
 *
 * A Start screen arriving from the desktop launcher may have its phone icons filed under
 * whichever desktop theme was current when they were picked - Vista's is where the writes
 * went, XP's is what the shell carried into memory when it was entered from XP. Both are
 * swept into [CUSTOM_ICONS_KEY] once; see MainActivity.migrateWP81CustomIconsIfNeeded.
 */
val DESKTOP_CUSTOM_ICON_KEYS = listOf("custom_icons_vista", "custom_icons_xp", "custom_icons_98")

/**
 * Everything the Windows Phone shell remembers.
 *
 * This was WP81Settings, and it was a theme registry: which of four shells was running,
 * and which artwork, font and layout each of them wanted. There is one shell now, so what
 * is left is its settings - the accent, the Start background and its blur and drift, tile
 * colours and counts, the column count, the hidden tiles, the news feeds and the whole
 * keyboard block.
 *
 * The preference file and every key spelling are deliberately unchanged. They are what is
 * already on disk, and what an import from the desktop launcher will be carrying.
 *
 * BACKWARD COMPATIBILITY:
 * - Uses existing SharedPreferences key "selected_theme"
 * - Preserves string values "Windows XP" and "Windows Classic"
 * - No breaking changes to user settings
 */
class WP81Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The keyboard's own settings, kept in the keyboard's own file.
     *
     * **A preference file belongs to one process.** SharedPreferences holds the whole file in
     * memory and writes the whole of it back, so two processes editing one file do not
     * interleave their changes - the second one to write puts back its own stale idea of
     * every key the first one touched. Change the accent in the launcher, then move the key
     * height slider in the keyboard, and the accent silently goes back to what it was.
     *
     * The keyboard runs in a process of its own (see the manifest, and [KeyboardAppearance]
     * for why), so the line has to be drawn somewhere, and it is drawn here: everything
     * `wp81_kb_` is the keyboard's and lives in `wp81_keyboard`, which nothing outside that
     * process writes. Everything else stays where it was, written only by the launcher.
     *
     * The file is not new and the keys are spelled exactly as they were - it is the same one
     * the clipboard history and the emoji recents are already in, and it is already carried
     * by an import from the desktop launcher, so the settings keep travelling as they did.
     * What was written under the old arrangement is moved across once; see
     * [migrateKeyboardSettings].
     */
    private val keyboardPrefs =
        context.getSharedPreferences(KEYBOARD_PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Moves the keyboard's settings into the keyboard's file, once.
     *
     * Everything `wp81_kb_` used to be written alongside the launcher's own settings, because
     * there was one process and so it made no difference where anything sat. It makes a
     * difference now - see [keyboardPrefs] - so what is already on disk has to be carried
     * across, or turning the keyboard's process on would read as every keyboard setting on
     * every phone quietly resetting itself.
     *
     * **Called from the keyboard's process and nowhere else.** It is a write to the
     * keyboard's file, and the whole point of the file is that one process writes it. The
     * launcher does not need it done: nothing over there reads a `wp81_kb_` key, which is
     * what made the split possible in the first place.
     *
     * The originals are left where they are rather than deleted. They are dead weight of a
     * few hundred bytes, and they are also the copy a backup taken before all this carries -
     * so a restore onto an older build still finds its keyboard settings.
     *
     * Whether the move has happened is asked of the settings themselves - see
     * [KEYBOARD_SETTING_KEYS] - rather than recorded in a flag beside them, and the
     * difference matters. A flag has to live in one file or the other and both are wrong:
     * kept here it survives a restore that refills the launcher's file with older settings,
     * and goes on saying the move is done when what it was done to has just been replaced;
     * kept over there it would have to be written from this process, which is the one thing
     * the split exists to prevent. The settings answer it exactly - the keyboard's file
     * holding none of them while the launcher's holds some is precisely and only the state
     * of not having moved yet. A setting turned off is still a setting written, so nothing
     * here can bring back a switch that somebody turned off.
     */
    fun migrateKeyboardSettings() {
        if (keyboardPrefs.all.keys.any { it in KEYBOARD_SETTING_KEYS }) return
        val moving = prefs.all.filterKeys { it in KEYBOARD_SETTING_KEYS }
        if (moving.isEmpty()) return
        keyboardPrefs.edit {
            for ((key, value) in moving) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    // A set is the only other shape SharedPreferences holds, and the compiler
                    // cannot see that the values are strings, so it is checked rather than
                    // cast. Nothing here writes one today; the branch is what stops the next
                    // key that does from being dropped in silence.
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
        }
    }


    // There is no selected theme to read. The stored `selected_theme` key is left in
    // preferences untouched - a Start screen imported from the desktop launcher carries it,
    // and an import should stay a faithful copy - but it gets no vote on which shell runs,
    // because there is only one.


    // ========== The app's own icon ==========


    /**
     * Built-in tiles the user has hidden from Start.
     *
     * Settings is deliberately not hideable - it is the only way back to this screen, and
     * hiding it would strand the user with no way to change anything.
     */
    fun getWP81HiddenTiles(): Set<String> =
        prefs.getString(KEY_WP81_HIDDEN_TILES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    fun setWP81HiddenTiles(ids: Set<String>) {
        prefs.edit { putString(KEY_WP81_HIDDEN_TILES, ids.joinToString(",")) }
    }

    // ========== Icon pack ==========

    /**
     * The package of the icon pack dressing the apps, or null for their own artwork.
     *
     * Stored as a package name rather than as a copy of anything: a pack is an installed
     * app, its art is read out of it every time, and the day it is uninstalled the right
     * behaviour is for the phone's own icons to come back - which is what an unresolvable
     * package name gets for free. See IconPack.open.
     */
    fun getWP81IconPack(): String? =
        prefs.getString(KEY_WP81_ICON_PACK, null)?.takeIf { it.isNotBlank() }

    fun setWP81IconPack(packageName: String?) {
        prefs.edit {
            if (packageName.isNullOrBlank()) remove(KEY_WP81_ICON_PACK)
            else putString(KEY_WP81_ICON_PACK, packageName)
        }
    }

    /**
     * Whether the pack's artwork reaches the Start screen, and not only the app list.
     *
     * Off by default, and that is the shell's opinion rather than an arbitrary one: a
     * Windows Phone tile is a flat white glyph on an accent square, and an icon pack is a
     * wall of full-colour rounded rectangles - the two are different designs, not two
     * settings of one.
     *
     * The app list is not asked, and takes the pack whatever this says. Its rows are a
     * name with a mark beside it, at a size where a pack's artwork reads as artwork rather
     * than as a hole in a grid - the same reason a row shows a full-colour icon plainly
     * today while a tile puts every mark it can on the accent. See AppListView, and
     * MonochromeIconProvider.packOnTiles for where the two part company.
     */
    fun getWP81IconPackOnTiles(): Boolean =
        prefs.getBoolean(KEY_WP81_ICON_PACK_TILES, false)

    fun setWP81IconPackOnTiles(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_ICON_PACK_TILES, enabled) }
    }

    // ========== Windows Phone 8.1 accent + background ==========

    /** The accent colour driving tiles, headers and controls in the WP8.1 shell. */
    fun getWP81Accent(): Int = prefs.getInt(KEY_WP81_ACCENT, WP81_DEFAULT_ACCENT)

    fun setWP81Accent(color: Int) {
        prefs.edit { putInt(KEY_WP81_ACCENT, color) }
    }

    /**
     * Asset path of the Start background image, or null for a plain accent-on-black
     * (or white) Start screen. WP8.1 8.1 added exactly this - a photo behind the tiles.
     */
    fun getWP81StartBackground(): String? =
        prefs.getString(KEY_WP81_START_BACKGROUND, null)

    /** Horizontal framing of a Start background wider than the screen: 0 left, 1 right. */
    fun getWP81StartBackgroundFocusX(): Float =
        prefs.getFloat(KEY_WP81_START_BACKGROUND_FOCUS_X, 0.5f)

    /**
     * How much the Start background is blurred, 0 (sharp) to 1. Blurring pushes the photo
     * back so the tiles and their labels stay readable over it.
     */
    fun getWP81StartBackgroundBlur(): Float =
        prefs.getFloat(KEY_WP81_START_BACKGROUND_BLUR, 0f)

    fun setWP81StartBackgroundBlur(amount: Float) {
        prefs.edit { putFloat(KEY_WP81_START_BACKGROUND_BLUR, amount.coerceIn(0f, 1f)) }
    }

    /**
     * Whether the Start background wanders behind the tiles as the phone is moved.
     *
     * Off by default: it holds a sensor while Start is up, which is not something to sign
     * a user up for without being asked.
     */
    fun getWP81StartBackgroundDrift(): Boolean =
        prefs.getBoolean(KEY_WP81_START_BACKGROUND_DRIFT, false)

    fun setWP81StartBackgroundDrift(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_START_BACKGROUND_DRIFT, enabled) }
    }

    /**
     * Whether tiles the user painted are shown in the accent instead, so the Start
     * background can be seen through them.
     *
     * The colours themselves are left where they are - this hides them, it does not
     * unset them - so turning it back off restores the wall exactly as it was.
     */
    fun getWP81HideTileColors(): Boolean =
        prefs.getBoolean(KEY_WP81_HIDE_TILE_COLORS, false)

    fun setWP81HideTileColors(hidden: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_HIDE_TILE_COLORS, hidden) }
    }

    /**
     * Whether every tile showing the Start photo is darkened, not only the ones with words.
     *
     * A tile carrying content - a notification turned face up, what is playing, a reading -
     * is drawn over a little black so the white text on it can be read. That leaves the
     * wall in two tones: the tiles that happen to be saying something sit darker than the
     * ones beside them. On it, they all sit at that tone, which reads as one wall of
     * windows rather than a wall with patches. See TileView.drawFace.
     */
    fun getWP81DimAllTiles(): Boolean =
        prefs.getBoolean(KEY_WP81_DIM_ALL_TILES, false)

    fun setWP81DimAllTiles(dim: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_DIM_ALL_TILES, dim) }
    }

    /**
     * How strongly [getWP81DimAllTiles] darkens the photo, 0 (untouched) to 1 (black).
     *
     * Starts where the wash under a tile's own words already sits, so throwing the switch
     * and touching nothing else puts the whole wall at the tone a few tiles were wearing
     * rather than at some new one. See TileView.dimAmount.
     */
    fun getWP81DimAmount(): Float =
        prefs.getFloat(KEY_WP81_DIM_AMOUNT, TileView.CONTENT_SCRIM_ALPHA)

    fun setWP81DimAmount(amount: Float) {
        prefs.edit { putFloat(KEY_WP81_DIM_AMOUNT, amount.coerceIn(0f, 1f)) }
    }

    /**
     * Whether the three keys along the bottom wear the accent instead of the page.
     *
     * Off by default, which is the phone this shell is copying: WP8.1's keys were
     * capacitive and the strip under them was simply the bottom of the display. The
     * accent bar is the one Windows 10 Mobile offered once the keys were drawn rather
     * than printed, and it is worth having for the same reason it was then - the strip is
     * the only piece of chrome on screen at all times, so a user who wants their colour
     * in sight has nowhere else to put it.
     */
    fun getWP81AccentNavBar(): Boolean =
        prefs.getBoolean(KEY_WP81_ACCENT_NAV_BAR, false)

    fun setWP81AccentNavBar(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_ACCENT_NAV_BAR, enabled) }
    }

    /**
     * Whether the three keys along the bottom are drawn at all.
     *
     * Off by default: they are the shell's only navigation, and a phone that arrived
     * without them would be one where nothing on screen says how to get back to Start.
     *
     * On, the strip and the band under it go back to the wall, and everything the shell
     * draws grows into the height they were taking - which is most of what somebody
     * turning this on is after. What the keys did is not lost: Android's own back gesture
     * still goes back, the home gesture still lands on Start, and the wall's own swipes
     * still reach the app list and its search. Cortana is the one thing that was only on
     * the strip, and she has a tile. See `WP81Shell.setNavBarShown`.
     */
    fun getWP81HideNavBar(): Boolean =
        prefs.getBoolean(KEY_WP81_HIDE_NAV_BAR, false)

    fun setWP81HideNavBar(hidden: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_HIDE_NAV_BAR, hidden) }
    }

    /**
     * Whether the shell takes the whole display, with Android's own bars hidden.
     *
     * Off by default, and not because the wall would not look better without them: the
     * status bar is where the clock, the signal and the battery are, and a launcher that
     * hid all three the moment it was installed would be one the user has to go looking
     * through settings to explain. Windows Phone itself made the same call - the bar was
     * hidden by a swipe, not by default.
     *
     * On, the bars leave and come back on a swipe from the edge, the way Android returns
     * a transient bar to anything running full screen. The shell needs no layout of its
     * own for it: the top padding and the band at the bottom are taken from the insets the
     * bars report, and a hidden bar reports none. See `MainActivity.applyWP81Fullscreen`.
     */
    fun getWP81Fullscreen(): Boolean =
        prefs.getBoolean(KEY_WP81_FULLSCREEN, false)

    fun setWP81Fullscreen(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_FULLSCREEN, enabled) }
    }

    /**
     * Whether the shell wears the phone's status bar and Action Center.
     *
     * Two things under one switch, because they are one thing: the strip along the top -
     * signal, carrier, battery, clock and date - is the Action Center's header, and the
     * panel is what comes down out of it. Turned on, a downward drag from the top of the
     * wall opens that panel; turned off, the strip goes, the wall starts directly under
     * Android's own status bar, and the gesture asks the system for its shade instead.
     *
     * On by default, because it is the thing the gesture was always standing in for: a
     * downward drag from the top of Start is how Windows Phone opened the Action Center,
     * and until now it opened somebody else's. Nothing is lost by it - Android's shade is
     * still one swipe from the real status bar above, which is where every other app on
     * the phone finds it too.
     *
     * The panel is the launcher's own drawing over the launcher's own screen. It is not,
     * and cannot be, a replacement for the system shade: an app may not draw over that one,
     * and the notifications it lists are the ones the listener is already reading for the
     * tiles. See WP81ActionCenter and WP81StatusBar.
     */
    fun getWP81ActionCenter(): Boolean =
        prefs.getBoolean(KEY_WP81_ACTION_CENTER, true)

    fun setWP81ActionCenter(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_ACTION_CENTER, enabled) }
    }

    /**
     * Whether a tile says *how much* is waiting rather than merely that something is.
     *
     * On by default, because it is what Windows Phone did and it is strictly more than the
     * dot tells you. Off returns the mark to the corner, for anyone who wants the wall to
     * be a wall of colour rather than a wall of numbers.
     */
    fun getWP81TileCounts(): Boolean = prefs.getBoolean(KEY_WP81_TILE_COUNTS, true)

    fun setWP81TileCounts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_TILE_COUNTS, enabled) }
    }

    /**
     * How many cells the Start screen is wide.
     *
     * Four is the phone default; three makes every tile larger, since a cell is a share of
     * the width rather than a fixed size. Sizes are stored in cells, so a wall arranged at
     * four still reads at three - the tiles simply take more of the screen.
     */
    fun getWP81Columns(): Int = prefs.getInt(KEY_WP81_COLUMNS, 4)

    fun setWP81Columns(columns: Int) {
        prefs.edit { putInt(KEY_WP81_COLUMNS, columns) }
    }

    /**
     * Whether clips from the camera roll turn up on the Photos tile.
     *
     * On, because the roll is what the tile is a slideshow of and a phone camera records
     * both - a tile that skipped every clip would be showing a gappy version of a day out.
     * Off is for the wall rather than for the roll: a clip on a tile *plays*, and a home
     * screen with something moving on it is a home screen that is asking to be looked at.
     * Off, the tile is stills, and the clips are still in Files where they were.
     *
     * Read where the roll is asked for rather than where it is drawn - see
     * PhotoFeed.recent - so that turning it off gives the tile twenty photographs rather
     * than twenty items with the clips taken out.
     */
    fun getWP81PhotoTileVideos(): Boolean =
        prefs.getBoolean(KEY_WP81_PHOTO_TILE_VIDEOS, true)

    fun setWP81PhotoTileVideos(show: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_PHOTO_TILE_VIDEOS, show) }
    }

    /**
     * Whether swiping across to the app list arrives with the search box up.
     *
     * On, because that is what the swipe has always done and it is the quick way to open
     * an app: the gesture across and the typing are one movement, and the list is a page
     * of forty icons that nobody scrolls when they already know the name. It is the wrong
     * default for anybody who swipes across to *look* - they get a keyboard over two
     * thirds of the list and a rail of letters that has folded itself away - so it can be
     * turned off, and then the list arrives as a list. Search is still the key on the
     * strip and the button at the top of the rail either way.
     */
    fun getWP81AppListSearchFocus(): Boolean =
        prefs.getBoolean(KEY_WP81_APPLIST_SEARCH_FOCUS, true)

    fun setWP81AppListSearchFocus(focus: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_APPLIST_SEARCH_FOCUS, focus) }
    }

    /**
     * When a backup was last written to a file, and when to Google Drive.
     *
     * Two stamps rather than one, because they are two backups: somebody who keeps a copy
     * on their Drive and exports a file before a big change wants to know the age of each,
     * and a single "last backed up" would report whichever they did most recently and say
     * nothing about the other.
     *
     * Kept per phone rather than inside the backup - see SettingsBackup.DEVICE_KEYS, which
     * is what carries them across a restore. A stamp that travelled would mean restoring
     * last month's backup told you your last backup was last month, which is true of the
     * file and false of the phone.
     *
     * Zero is never.
     */
    fun getWP81LastDriveBackup(): Long =
        prefs.getSafeLong(SettingsBackup.KEY_LAST_DRIVE_BACKUP, 0L)

    fun setWP81LastDriveBackup(at: Long) {
        prefs.edit { putLong(SettingsBackup.KEY_LAST_DRIVE_BACKUP, at) }
    }

    fun getWP81LastFileBackup(): Long =
        prefs.getSafeLong(SettingsBackup.KEY_LAST_FILE_BACKUP, 0L)

    fun setWP81LastFileBackup(at: Long) {
        prefs.edit { putLong(SettingsBackup.KEY_LAST_FILE_BACKUP, at) }
    }

    /**
     * The news feeds the News tile reads, by id.
     *
     * Unset means the default rather than none: a News tile that has never been given a
     * feed would otherwise sit there empty with no hint that it wants configuring.
     */
    fun getWP81NewsFeeds(): Set<String> =
        prefs.getStringSet(KEY_WP81_NEWS_FEEDS, null) ?: setOf(NewsSources.DEFAULT_ID)

    /**
     * The feeds the user has added themselves, in the order they added them.
     *
     * JSON rather than the joined string the tile colours are kept as: a feed carries a
     * name the user can edit and an address they typed, and a name with a semicolon in it
     * would quietly take the feed after it away with it.
     *
     * Order is the order they were added, which is the order they were last shown in -
     * a list that sorted itself would move a feed while somebody was looking at it.
     */
    fun getWP81CustomNewsFeeds(): List<NewsSource> {
        val raw = prefs.getString(KEY_WP81_NEWS_CUSTOM, null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val url = entry.optString("url").orEmpty()
                if (url.isBlank()) return@mapNotNull null
                NewsSource(
                    id = entry.optString("id").ifBlank { NewsSources.customId(url) },
                    name = entry.optString("name").ifBlank { url },
                    url = url
                )
            }
        } catch (e: Exception) {
            Log.w("WP81Settings", "Unreadable custom news feeds", e)
            emptyList()
        }
    }

    fun setWP81CustomNewsFeeds(feeds: List<NewsSource>) {
        val array = org.json.JSONArray()
        for (feed in feeds) {
            array.put(org.json.JSONObject().apply {
                put("id", feed.id)
                put("name", feed.name)
                put("url", feed.url)
            })
        }
        prefs.edit { putString(KEY_WP81_NEWS_CUSTOM, array.toString()) }
    }

    /**
     * The feed an enabled id names, whichever list it is in.
     *
     * The one thing that has to know about both: everything downstream of it - the tile,
     * the reader - holds ids and should not care which kind of feed it has been handed.
     */
    fun getWP81NewsSource(id: String): NewsSource? =
        NewsSources.byId(id) ?: getWP81CustomNewsFeeds().firstOrNull { it.id == id }

    /**
     * Tiles the user has painted a colour of their own, by tile id.
     *
     * Absent means the accent, which is what almost every tile is - only the exceptions
     * are stored, so changing the accent still moves the whole wall except those.
     */
    fun getWP81TileColors(): Map<String, Int> {
        val raw = prefs.getString(KEY_WP81_TILE_COLORS, null) ?: return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            val color = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            parts[0] to color
        }.toMap()
    }

    /**
     * Tiles the user has turned the background picture off on, by tile id.
     *
     * The exceptions again, as with the colours: a picture is what a tile with one shows
     * unless it has been told otherwise, so the set is empty on a fresh install and stays
     * small. See TileView.showsBackdrop.
     */
    fun getWP81TilesWithoutPicture(): Set<String> =
        prefs.getStringSet(KEY_WP81_TILE_NO_PICTURE, null)?.toSet() ?: emptySet()

    /** Turns one tile's picture on or off. */
    fun setWP81TilePicture(tileId: String, shown: Boolean) {
        val hidden = getWP81TilesWithoutPicture().toMutableSet()
        if (shown) hidden.remove(tileId) else hidden.add(tileId)
        prefs.edit { putStringSet(KEY_WP81_TILE_NO_PICTURE, hidden) }
    }

    /** Paints one tile, or hands it back to the accent with null. */
    fun setWP81TileColor(tileId: String, color: Int?) {
        val colors = getWP81TileColors().toMutableMap()
        if (color == null) colors.remove(tileId) else colors[tileId] = color
        prefs.edit {
            putString(KEY_WP81_TILE_COLORS, colors.entries.joinToString(";") { "${it.key}:${it.value}" })
        }
    }

    fun setWP81NewsFeeds(ids: Set<String>) {
        // A copy: SharedPreferences does not defensively copy the set it is handed, and a
        // caller mutating it afterwards would quietly change what was stored.
        prefs.edit { putStringSet(KEY_WP81_NEWS_FEEDS, ids.toSet()) }
    }

    fun setWP81StartBackgroundFocusX(focusX: Float) {
        prefs.edit { putFloat(KEY_WP81_START_BACKGROUND_FOCUS_X, focusX.coerceIn(0f, 1f)) }
    }

    fun setWP81StartBackground(assetPath: String?) {
        prefs.edit {
            if (assetPath == null) remove(KEY_WP81_START_BACKGROUND)
            else putString(KEY_WP81_START_BACKGROUND, assetPath)
        }
    }

    /** True when the WP8.1 shell uses the dark (black) background rather than the light one. */
    // ---------------------------------------------------------------- keyboard

    /**
     * Whether the keyboard replaces a word by itself when you press space.
     *
     * **Off unless asked for.** Suggestions are always shown and are always one tap away; what
     * this controls is whether the keyboard acts on them without being told. A keyboard that
     * silently rewrites what someone typed is the single most complained-about thing a
     * keyboard does, and the sensible default for a keyboard somebody chose to install is to
     * offer rather than to insist.
     */
    fun getWP81KeyboardAutocorrect(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_AUTOCORRECT, false)

    fun setWP81KeyboardAutocorrect(enabled: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_AUTOCORRECT, enabled) }
    }

    /**
     * Whether the keyboard turns shift on by itself at the start of a sentence.
     *
     * **Off unless asked for**, like the correction setting above. A field can ask for this -
     * that is what `TYPE_TEXT_FLAG_CAP_SENTENCES` is - but plenty of fields ask for it out of
     * habit rather than because the text wants it, and a keyboard that capitalises without
     * being told is one somebody has to keep un-capitalising. Shift is one tap away either way.
     */
    fun getWP81KeyboardAutoCapitalise(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_AUTOCAPS, false)

    fun setWP81KeyboardAutoCapitalise(enabled: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_AUTOCAPS, enabled) }
    }

    /**
     * Which keyboard languages are turned on, as layout ids.
     *
     * English alone to begin with. A keyboard that shipped every language it knew would put
     * languages nobody reads into the globe's rotation, and the globe is only useful if what
     * it cycles through is short.
     *
     * Stored rather than read back from the system because the two are different questions:
     * this is what the user chose, and Android's enabled-subtype list is what was made of
     * that. See `KeyboardLanguages`, which keeps the second in step with the first.
     */
    fun getWP81KeyboardLanguages(): Set<String> =
        keyboardPrefs.getString(KEY_WP81_KB_LANGUAGES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(WP81_KB_DEFAULT_LANGUAGE)

    fun setWP81KeyboardLanguages(ids: Set<String>) {
        // Never none. A keyboard with no language is a keyboard with no letters, and the
        // setting that produced it would be impossible to undo from the keyboard itself.
        val kept = ids.ifEmpty { setOf(WP81_KB_DEFAULT_LANGUAGE) }
        keyboardPrefs.edit { putString(KEY_WP81_KB_LANGUAGES, kept.joinToString(",")) }
    }

    /**
     * Whether the bottom row is shorter than the letters above it.
     *
     * On by default. Nothing on that row is a letter - it is the space bar and the keys
     * either side - so it does not need a letter's target, and the height it gives back is
     * height the keyboard is not taking from whatever is being typed into. Off for anyone who
     * would rather have four even rows.
     */
    fun getWP81KeyboardShortBottomRow(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_SHORT_BOTTOM, true)

    fun setWP81KeyboardShortBottomRow(shorter: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_SHORT_BOTTOM, shorter) }
    }

    /**
     * Which engine dictates: Vosk on the phone, or the platform's own recogniser.
     *
     * **Vosk by default**, because that is the point: it runs on the phone and sends nothing
     * anywhere, where the platform's recogniser is Google's on most Android builds and
     * transcribes in the cloud. The platform one stays selectable, and stays as the fallback
     * whatever this says - Vosk publishes no Macedonian model, so for the language this
     * keyboard was built for it is the only dictation there is.
     */
    fun getWP81KeyboardOfflineVoice(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_OFFLINE_VOICE, true)

    fun setWP81KeyboardOfflineVoice(offline: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_OFFLINE_VOICE, offline) }
    }

    /**
     * How long a key must be held before it offers what is behind it, in milliseconds.
     *
     * Worth having as a setting rather than a constant because the right value is a property
     * of the hand and not of the keyboard: too short and reaching for a letter produces its
     * symbol, too long and the symbol feels like it is being withheld.
     */
    fun getWP81KeyboardHoldMs(): Int =
        keyboardPrefs.getSafeInt(KEY_WP81_KB_HOLD_MS, WP81_KB_HOLD_DEFAULT)
            .coerceIn(WP81_KB_HOLD_MIN, WP81_KB_HOLD_MAX)

    fun setWP81KeyboardHoldMs(millis: Int) {
        keyboardPrefs.edit { putInt(KEY_WP81_KB_HOLD_MS, millis.coerceIn(WP81_KB_HOLD_MIN, WP81_KB_HOLD_MAX)) }
    }

    /**
     * How hard a keystroke buzzes: [WP81_KB_VIBRATION_SYSTEM] for the phone's own, or a
     * strength from zero (silent) to a hundred.
     *
     * The default is the phone's, and that is not a hedge. `Haptics` goes through the view
     * rather than the vibrator on purpose - it picks up whatever waveform the manufacturer
     * tuned for a keystroke, and it stays quiet when the user has turned touch feedback off -
     * and a keyboard that reached for the vibrator by default would throw both of those away
     * for everybody in order to serve the few who want it stronger.
     */
    fun getWP81KeyboardVibration(): Int =
        keyboardPrefs.getSafeInt(KEY_WP81_KB_VIBRATION, WP81_KB_VIBRATION_SYSTEM)
            .coerceIn(WP81_KB_VIBRATION_SYSTEM, WP81_KB_VIBRATION_MAX)

    fun setWP81KeyboardVibration(strength: Int) {
        keyboardPrefs.edit {
            putInt(
                KEY_WP81_KB_VIBRATION,
                strength.coerceIn(WP81_KB_VIBRATION_SYSTEM, WP81_KB_VIBRATION_MAX)
            )
        }
    }

    /**
     * How tall the keys are against the height the phone's own were, as a percentage.
     *
     * A hundred is the phone's own proportions, which is where it starts and what every
     * measurement in the keyboard was taken at. It is the one piece of that geometry worth
     * making adjustable: how much of a screen a keyboard should take is a trade between the
     * size of a thumb and the size of what is being typed into, and neither of those is the
     * same on a four-inch phone in one hand as on a six-and-a-half-inch one in two.
     *
     * A percentage rather than a height in dp, so it means the same thing on every screen -
     * the keys stay a fraction of the width and in the phone's proportions, and this says how
     * much of one. See `KeyboardView.keyHeightScale`.
     */
    fun getWP81KeyboardKeyHeight(): Int =
        keyboardPrefs.getSafeInt(KEY_WP81_KB_KEY_HEIGHT, WP81_KB_KEY_HEIGHT_DEFAULT)
            .coerceIn(WP81_KB_KEY_HEIGHT_MIN, WP81_KB_KEY_HEIGHT_MAX)

    fun setWP81KeyboardKeyHeight(percent: Int) {
        keyboardPrefs.edit {
            putInt(
                KEY_WP81_KB_KEY_HEIGHT,
                percent.coerceIn(WP81_KB_KEY_HEIGHT_MIN, WP81_KB_KEY_HEIGHT_MAX)
            )
        }
    }

    /**
     * Whether a keystroke clicks.
     *
     * **Off unless asked for.** A vibration is felt by the person holding the phone; a sound
     * is heard by everybody in the room, and Android has defaulted keypress sounds to off for
     * long enough that a keyboard which arrives clicking reads as broken rather than as
     * faithful. Windows Phone shipped with one, and this is the switch that gives it back.
     *
     * There is no loudness to go with it, deliberately - one switch, and the volume keys do
     * the rest. It plays on the media stream, which is what makes it survive silent mode and
     * is also why it is mixed at half gain: a keypress sound written for the system stream's
     * own modest level is louder than intended anywhere else. See `KeyboardSounds`, which has
     * the whole of that trade written down.
     */
    fun getWP81KeyboardSound(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_SOUND, false)

    fun setWP81KeyboardSound(enabled: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_SOUND, enabled) }
    }

    /**
     * Whether a pressed key lifts its letter clear of the finger.
     *
     * **On**, which is the opposite way round from the two settings above and for a reason. A
     * thumb covers the key it is pressing, so the one thing you cannot see while typing on a
     * phone is what you have just typed - which is why every keyboard on every platform shows
     * this, and why somebody who has never opened these settings is better served with it
     * than without. It is also silent and invisible to anybody but the person typing, which
     * is what disqualified the click from the same treatment.
     *
     * See `KeyPreviewPopup`.
     */
    fun getWP81KeyboardKeyPreview(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_KEY_PREVIEW, true)

    fun setWP81KeyboardKeyPreview(enabled: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_KEY_PREVIEW, enabled) }
    }

    /**
     * Whether the caret joystick is showing, and the space bar has therefore stopped sliding.
     *
     * **Off unless asked for**, because it is a control the phone did not have and it takes
     * room on a keyboard that is otherwise exactly the phone's. Somebody who turns it on has
     * decided that placing the caret is worth a dot in the gutter, which is a decision only
     * they can make.
     *
     * One setting rather than two, and the second half is not a hidden extra: the space bar's
     * slide exists because there was nowhere else to put the gesture, and it costs a slop test
     * on every space typed. Once there is a control that does nothing but this, leaving the
     * slide on the space bar keeps only the occasional wrong space. See `JoystickView`.
     */
    fun getWP81KeyboardJoystick(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_JOYSTICK, false)

    fun setWP81KeyboardJoystick(enabled: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_JOYSTICK, enabled) }
    }

    /**
     * Whether a hold on shift or `&123` slides across the keys it brings up.
     *
     * On unless turned off, because it takes nothing away. Both keys keep every meaning they
     * had - a tap still switches, and a hold that ends where it started still locks shift or
     * stays on the symbol page - and the gesture is only what a finger that carries on moving
     * now does instead of nothing.
     *
     * A setting all the same, because it is a hold on two keys that are pressed more than any
     * others, and anybody whose hand rests on shift while they think would rather it did
     * nothing at all. See `KeyboardView.slideKeys`.
     */
    fun getWP81KeyboardSlideKeys(): Boolean =
        keyboardPrefs.getBoolean(KEY_WP81_KB_SLIDE_KEYS, true)

    fun setWP81KeyboardSlideKeys(enabled: Boolean) {
        keyboardPrefs.edit { putBoolean(KEY_WP81_KB_SLIDE_KEYS, enabled) }
    }

    /**
     * The GIPHY key the keyboard's GIF panel searches with, or empty for none.
     *
     * A setting rather than a constant in the source, because GIPHY issues these per person
     * and per application: one baked into a build is one key answering for everybody who
     * installs it, against one rate limit, and it would sit in the repository in plain sight.
     * So the app ships without one and each user pastes in their own - see the keyboard's
     * settings page, which is also where it says how to get one.
     */
    fun getWP81KeyboardGiphyKey(): String =
        keyboardPrefs.getString(KEY_WP81_KB_GIPHY_KEY, "").orEmpty().trim()

    fun setWP81KeyboardGiphyKey(key: String) {
        keyboardPrefs.edit { putString(KEY_WP81_KB_GIPHY_KEY, key.trim()) }
    }

    fun isWP81Dark(): Boolean = prefs.getBoolean(KEY_WP81_DARK, true)

    fun setWP81Dark(dark: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_DARK, dark) }
    }

    /**
     * Whether the phone's own launcher wall is kept in step with the Light/Dark setting.
     *
     * It is, until the user puts a picture there themselves. That wall is not usually
     * looked at - this shell is drawn over it - but it is what shows for the moment
     * between a program being asked for and being on screen, and a black flash in front
     * of a white Start screen is the one place the phone's wall is ever seen.
     *
     * Turned off for good by the first picture applied to it, because a picture somebody
     * chose is an answer to the same question and outranks the default.
     */
    fun keepsDeviceWallInStep(): Boolean =
        prefs.getBoolean(KEY_WP81_DEVICE_WALL_IN_STEP, true)

    fun setKeepsDeviceWallInStep(inStep: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_DEVICE_WALL_IN_STEP, inStep) }
    }

    // ========== Resource Mapping Methods ==========
    // These methods centralize all theme-specific resource lookups

    /**
     * Gets the theme style resource ID for the given theme.
     */
    // Deliberately keyed on the theme, not its chrome: WP8.1 borrows Vista's window
    // chrome but needs its own style for the accent colour and the Segoe weights.
    fun getThemeStyleRes(): Int = R.style.Theme_GokiXP_WP81


    /**
     * Art for a My Computer row carried in from the desktop launcher.
     *
     * Kept for the same reason the row is: a migrated wall still has to match what was
     * there. Nothing on the phone creates one - see the icon loader.
     */
    fun getMyComputerIcon(): Int = R.drawable.my_computer_vista_icon


    /** The folder art, for a folder with no icon of its own. */
    fun getFolderIconRes(): Int = R.drawable.folder_vista

    companion object {
        private const val KEY_SELECTED_THEME = "selected_theme"
        private const val KEY_WP81_APPLIST_SEARCH_FOCUS = "wp81_applist_search_focus"
        private const val KEY_WP81_PHOTO_TILE_VIDEOS = "wp81_photo_tile_videos"
        const val KEY_WP81_ACCENT = "wp81_accent"
        private const val KEY_WP81_DEVICE_WALL_IN_STEP = "wp81_device_wall_in_step"
        /**
         * The file the keyboard's own settings live in.
         *
         * Spelled here rather than taken from `WP81KeyboardService.KEYBOARD_PREFS`, which is
         * the same string: the service is the keyboard's process and this class is read in
         * the launcher's, and a settings class that has to load the keyboard to know where
         * its own file is would drag the dictionary into the launcher to answer a question
         * about a filename.
         */
        const val KEYBOARD_PREFS_FILE = "wp81_keyboard"

        /**
         * The keyboard's settings, named one by one rather than matched by their prefix.
         *
         * A prefix looks like the obvious test and is the wrong one. `wp81_kb_` is not the
         * mark of a setting, it is the mark of the keyboard, and the keyboard's file already
         * held `wp81_kb_clipboard` and `wp81_kb_emoji_recents` long before any of this - its
         * clipboard history and the emoji it has reached for, which are the keyboard's own
         * working notes and were never in the launcher's file at all. A move that asked
         * "does the keyboard's file hold anything `wp81_kb_`?" would find the clipboard,
         * conclude the settings were already across, and quietly leave every one of them
         * behind on a phone that had ever copied a line of text.
         *
         * This is the list of settings, and it is exact in both directions: it says what to
         * carry over, and its absence is what says the carrying has not happened yet.
         */
        val KEYBOARD_SETTING_KEYS = setOf(
            KEY_WP81_KB_AUTOCORRECT,
            KEY_WP81_KB_AUTOCAPS,
            KEY_WP81_KB_OFFLINE_VOICE,
            KEY_WP81_KB_SHORT_BOTTOM,
            KEY_WP81_KB_LANGUAGES,
            KEY_WP81_KB_HOLD_MS,
            KEY_WP81_KB_VIBRATION,
            KEY_WP81_KB_KEY_HEIGHT,
            KEY_WP81_KB_SOUND,
            KEY_WP81_KB_KEY_PREVIEW,
            KEY_WP81_KB_JOYSTICK,
            KEY_WP81_KB_SLIDE_KEYS,
            KEY_WP81_KB_GIPHY_KEY
        )

        const val KEY_WP81_KB_AUTOCORRECT = "wp81_kb_autocorrect"
        const val KEY_WP81_KB_AUTOCAPS = "wp81_kb_autocaps"
        const val KEY_WP81_KB_OFFLINE_VOICE = "wp81_kb_offline_voice"
        const val KEY_WP81_KB_SHORT_BOTTOM = "wp81_kb_short_bottom"
        const val KEY_WP81_KB_LANGUAGES = "wp81_kb_languages"

        /** The one every keyboard starts with. Matches Layouts.EN_QWERTY's id. */
        const val WP81_KB_DEFAULT_LANGUAGE = "en_qwerty"
        const val KEY_WP81_KB_HOLD_MS = "wp81_kb_hold_ms"
        const val KEY_WP81_KB_VIBRATION = "wp81_kb_vibration"
        const val KEY_WP81_KB_KEY_HEIGHT = "wp81_kb_key_height"
        const val KEY_WP81_KB_SOUND = "wp81_kb_sound"
        const val KEY_WP81_KB_KEY_PREVIEW = "wp81_kb_key_preview"
        const val KEY_WP81_KB_JOYSTICK = "wp81_kb_joystick"
        const val KEY_WP81_KB_SLIDE_KEYS = "wp81_kb_slide_keys"

        /**
         * Keystroke vibration: the phone's own, silent, or a strength in between.
         *
         * Minus one rather than a separate boolean because "the system's" is genuinely not a
         * point on this scale - it is a different mechanism, not a stronger or weaker version
         * of the same one - and two settings that can disagree about which is in force is the
         * shape that goes wrong.
         */
        const val WP81_KB_VIBRATION_SYSTEM = -1
        const val WP81_KB_VIBRATION_MAX = 100
        const val KEY_WP81_KB_GIPHY_KEY = "wp81_kb_giphy_key"

        /** The hold, in milliseconds: what it is by default and how far it can be moved. */
        const val WP81_KB_HOLD_DEFAULT = 350
        const val WP81_KB_HOLD_MIN = 150
        const val WP81_KB_HOLD_MAX = 900

        /**
         * The key height, as a percentage of the phone's own.
         *
         * The ends match `KeyboardView.MIN_HEIGHT_SCALE` and `MAX_HEIGHT_SCALE`, which is
         * where the reasoning for them lives: below the floor a row stops clearing Android's
         * minimum touch target, and above the ceiling the keyboard starts crowding out the
         * thing being typed into.
         */
        const val WP81_KB_KEY_HEIGHT_DEFAULT = 100
        const val WP81_KB_KEY_HEIGHT_MIN = 65
        const val WP81_KB_KEY_HEIGHT_MAX = 150
        const val KEY_WP81_DARK = "wp81_background_dark"
        const val KEY_WP81_START_BACKGROUND = "wp81_start_background"
        const val KEY_WP81_START_BACKGROUND_FOCUS_X = "wp81_start_background_focus_x"
        const val KEY_WP81_START_BACKGROUND_BLUR = "wp81_start_background_blur"
        const val KEY_WP81_START_BACKGROUND_DRIFT = "wp81_start_background_drift"
        const val KEY_WP81_NEWS_FEEDS = "wp81_news_feeds"
        const val KEY_WP81_NEWS_CUSTOM = "wp81_news_custom_feeds"
        const val KEY_WP81_TILE_COLORS = "wp81_tile_colors"
        const val KEY_WP81_TILE_NO_PICTURE = "wp81_tile_no_picture"
        const val KEY_WP81_HIDE_TILE_COLORS = "wp81_hide_tile_colors"
        const val KEY_WP81_DIM_ALL_TILES = "wp81_dim_all_tiles"
        const val KEY_WP81_DIM_AMOUNT = "wp81_dim_amount"
        const val KEY_WP81_ACCENT_NAV_BAR = "wp81_accent_nav_bar"
        const val KEY_WP81_HIDE_NAV_BAR = "wp81_hide_nav_bar"
        const val KEY_WP81_FULLSCREEN = "wp81_fullscreen"
        const val KEY_WP81_ACTION_CENTER = "wp81_action_center"
        const val KEY_WP81_TILE_COUNTS = "wp81_tile_counts"
        const val KEY_WP81_COLUMNS = "wp81_columns"
        const val KEY_WP81_HIDDEN_TILES = "wp81_hidden_tiles"
        const val KEY_WP81_ICON_PACK = "wp81_icon_pack"
        const val KEY_WP81_ICON_PACK_TILES = "wp81_icon_pack_tiles"

        /**
         * The accent a fresh install starts in: Red, from the palette below.
         *
         * WP8.1 itself shipped Cyan. This launcher does not, because its own mark is the
         * one on the app icon and in the drawer, and a wall that opens in the same blue as
         * every other phone's is a wall nobody remembers.
         */
        val WP81_DEFAULT_ACCENT: Int = 0xFFE51400.toInt()

        /**
         * The twenty accent colours Windows Phone 8.1 offered, in the order the
         * Settings > theme picker listed them.
         */
        val WP81_ACCENTS: List<Pair<String, Int>> = listOf(
            "Lime" to 0xFFA4C400.toInt(),
            "Green" to 0xFF60A917.toInt(),
            "Emerald" to 0xFF008A00.toInt(),
            "Teal" to 0xFF00ABA9.toInt(),
            "Cyan" to 0xFF1BA1E2.toInt(),
            "Cobalt" to 0xFF0050EF.toInt(),
            "Indigo" to 0xFF6A00FF.toInt(),
            "Violet" to 0xFFAA00FF.toInt(),
            "Pink" to 0xFFF472D0.toInt(),
            "Magenta" to 0xFFD80073.toInt(),
            "Crimson" to 0xFFA20025.toInt(),
            "Red" to 0xFFE51400.toInt(),
            "Orange" to 0xFFFA6800.toInt(),
            "Amber" to 0xFFF0A30A.toInt(),
            "Yellow" to 0xFFE3C800.toInt(),
            "Brown" to 0xFF825A2C.toInt(),
            "Olive" to 0xFF6D8764.toInt(),
            "Steel" to 0xFF647687.toInt(),
            "Mauve" to 0xFF76608A.toInt(),
            "Taupe" to 0xFF87794E.toInt(),
        )


        /**
         * The one launcher alias the manifest ships enabled. Anything else reported as
         * COMPONENT_ENABLED_STATE_DEFAULT is therefore off. Must track android:enabled in
         * AndroidManifest.xml.
         */
        private const val DEFAULT_LAUNCHER_ALIAS = ".LauncherIconXP"

    }
}
