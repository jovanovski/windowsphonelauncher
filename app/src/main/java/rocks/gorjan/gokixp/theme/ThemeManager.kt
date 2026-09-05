package rocks.gorjan.gokixp.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.getSafeInt
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
 * Centralized theme management class.
 * Handles theme selection, persistence, and resource mapping.
 *
 * BACKWARD COMPATIBILITY:
 * - Uses existing SharedPreferences key "selected_theme"
 * - Preserves string values "Windows XP" and "Windows Classic"
 * - No breaking changes to user settings
 */
class ThemeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)


    // There is no selected theme to read. The stored `selected_theme` key is left in
    // preferences untouched - a Start screen imported from the desktop launcher carries it,
    // and an import should stay a faithful copy - but it gets no vote on which shell runs,
    // because there is only one.


    // ========== The app's own icon ==========



    // ========== Plus! 95 theme support ==========

    data class Plus95Theme(
        val slug: String,
        val displayName: String,
        val menuColor: Int,
        val busyAsset: String?,
        /** Asset played by MainActivity.playClickSound in place of the default UI click. */
        val soundAsset: String?,
        /** Asset played as the startup sound at launch and whenever this theme is applied. */
        val startupAsset: String?
    )




    /**
     * Always null here.
     *
     * Microsoft Plus! dressed up Windows Classic, and Classic ships in the desktop
     * launcher. The callers - the window chrome, the context menu, the click and startup
     * sounds - all already treat null as "no Plus! theme", which is the only answer this
     * launcher can give. Kept as a seam rather than unpicked from five files at once.
     */
    fun getActivePlus95(): Plus95Theme? = null

    fun plus95Path(slug: String, filename: String): String = "plus95/$slug/$filename"




    /** Windows are always drawn in Vista chrome here; the phone had no chrome of its own. */
    fun isVistaChrome(): Boolean = true

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


    /**
     * The theme name that legacy raw-string `when (selectedTheme)` blocks should branch on.
     *
     * A number of sites still read the "selected_theme" pref directly and switch on the
     * string with a silent `else -> XP` fallback. Those are not compiler-checked, so
     * WP8.1 would quietly fall through to XP assets. In-window callers should use this
     * instead of the raw pref so WP8.1 resolves to Vista.
     */
    fun chromeThemeString(): String = "Windows Vista"

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
     * The news feeds the News tile reads, by id.
     *
     * Unset means the default rather than none: a News tile that has never been given a
     * feed would otherwise sit there empty with no hint that it wants configuring.
     */
    fun getWP81NewsFeeds(): Set<String> =
        prefs.getStringSet(KEY_WP81_NEWS_FEEDS, null)
            ?: setOf(rocks.gorjan.gokixp.wp81.NewsSources.DEFAULT_ID)

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
        prefs.getBoolean(KEY_WP81_KB_AUTOCORRECT, false)

    fun setWP81KeyboardAutocorrect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_KB_AUTOCORRECT, enabled) }
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
        prefs.getBoolean(KEY_WP81_KB_AUTOCAPS, false)

    fun setWP81KeyboardAutoCapitalise(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_KB_AUTOCAPS, enabled) }
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
        prefs.getString(KEY_WP81_KB_LANGUAGES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(WP81_KB_DEFAULT_LANGUAGE)

    fun setWP81KeyboardLanguages(ids: Set<String>) {
        // Never none. A keyboard with no language is a keyboard with no letters, and the
        // setting that produced it would be impossible to undo from the keyboard itself.
        val kept = ids.ifEmpty { setOf(WP81_KB_DEFAULT_LANGUAGE) }
        prefs.edit { putString(KEY_WP81_KB_LANGUAGES, kept.joinToString(",")) }
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
        prefs.getBoolean(KEY_WP81_KB_SHORT_BOTTOM, true)

    fun setWP81KeyboardShortBottomRow(shorter: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_KB_SHORT_BOTTOM, shorter) }
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
        prefs.getBoolean(KEY_WP81_KB_OFFLINE_VOICE, true)

    fun setWP81KeyboardOfflineVoice(offline: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_KB_OFFLINE_VOICE, offline) }
    }

    /**
     * How long a key must be held before it offers what is behind it, in milliseconds.
     *
     * Worth having as a setting rather than a constant because the right value is a property
     * of the hand and not of the keyboard: too short and reaching for a letter produces its
     * symbol, too long and the symbol feels like it is being withheld.
     */
    fun getWP81KeyboardHoldMs(): Int =
        prefs.getSafeInt(KEY_WP81_KB_HOLD_MS, WP81_KB_HOLD_DEFAULT)
            .coerceIn(WP81_KB_HOLD_MIN, WP81_KB_HOLD_MAX)

    fun setWP81KeyboardHoldMs(millis: Int) {
        prefs.edit { putInt(KEY_WP81_KB_HOLD_MS, millis.coerceIn(WP81_KB_HOLD_MIN, WP81_KB_HOLD_MAX)) }
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
        prefs.getSafeInt(KEY_WP81_KB_VIBRATION, WP81_KB_VIBRATION_SYSTEM)
            .coerceIn(WP81_KB_VIBRATION_SYSTEM, WP81_KB_VIBRATION_MAX)

    fun setWP81KeyboardVibration(strength: Int) {
        prefs.edit {
            putInt(
                KEY_WP81_KB_VIBRATION,
                strength.coerceIn(WP81_KB_VIBRATION_SYSTEM, WP81_KB_VIBRATION_MAX)
            )
        }
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
        prefs.getString(KEY_WP81_KB_GIPHY_KEY, "").orEmpty().trim()

    fun setWP81KeyboardGiphyKey(key: String) {
        prefs.edit { putString(KEY_WP81_KB_GIPHY_KEY, key.trim()) }
    }

    fun isWP81Dark(): Boolean = prefs.getBoolean(KEY_WP81_DARK, true)

    fun setWP81Dark(dark: Boolean) {
        prefs.edit { putBoolean(KEY_WP81_DARK, dark) }
    }

    // ========== Resource Mapping Methods ==========
    // These methods centralize all theme-specific resource lookups

    /**
     * Gets the theme style resource ID for the given theme.
     */
    // Deliberately keyed on the theme, not its chrome: WP8.1 borrows Vista's window
    // chrome but needs its own style for the accent colour and the Segoe weights.
    fun getThemeStyleRes(): Int = R.style.Theme_GokiXP_WP81









    fun getIEIcon(): Int = R.drawable.ie7




    fun getRegeditIcon(): Int = R.drawable.regedit_icon_vista

    fun getSolitareIcon(): Int = R.drawable.solitare_icon_vista


    fun getWinampIcon(): Int = R.drawable.winamp_icon_xp

    fun getWmpIcon(): Int = R.drawable.wmp_vista_icon


    fun getMinesweeperIcon(): Int = R.drawable.minesweeper_icon_vista


    fun getNotepadIcon(): Int = R.drawable.notepad_icon_vista


    fun getClockIcon(): Int = R.drawable.icon_clock_vista

    fun getMyComputerIcon(): Int = R.drawable.my_computer_vista_icon











    fun getMaximizeIcon(): Int = R.drawable.vista_title_bar_maximize

    fun getRestoreIcon(): Int = R.drawable.vista_title_bar_restore



    // ========== Icon Resource Mappings ==========

    /**
     * Gets the folder icon drawable resource ID for the given theme.
     */
    fun getFolderIconRes(): Int = R.drawable.folder_vista



    // ========== Font Resource Mappings ==========



    // ========== Scrollbar Styling ==========


    companion object {
        private const val KEY_SELECTED_THEME = "selected_theme"
        const val KEY_WP81_ACCENT = "wp81_accent"
        const val KEY_WP81_KB_AUTOCORRECT = "wp81_kb_autocorrect"
        const val KEY_WP81_KB_AUTOCAPS = "wp81_kb_autocaps"
        const val KEY_WP81_KB_OFFLINE_VOICE = "wp81_kb_offline_voice"
        const val KEY_WP81_KB_SHORT_BOTTOM = "wp81_kb_short_bottom"
        const val KEY_WP81_KB_LANGUAGES = "wp81_kb_languages"

        /** The one every keyboard starts with. Matches Layouts.EN_QWERTY's id. */
        const val WP81_KB_DEFAULT_LANGUAGE = "en_qwerty"
        const val KEY_WP81_KB_HOLD_MS = "wp81_kb_hold_ms"
        const val KEY_WP81_KB_VIBRATION = "wp81_kb_vibration"

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
        const val KEY_WP81_DARK = "wp81_background_dark"
        const val KEY_WP81_START_BACKGROUND = "wp81_start_background"
        const val KEY_WP81_START_BACKGROUND_FOCUS_X = "wp81_start_background_focus_x"
        const val KEY_WP81_START_BACKGROUND_BLUR = "wp81_start_background_blur"
        const val KEY_WP81_START_BACKGROUND_DRIFT = "wp81_start_background_drift"
        const val KEY_WP81_NEWS_FEEDS = "wp81_news_feeds"
        const val KEY_WP81_TILE_COLORS = "wp81_tile_colors"
        const val KEY_WP81_HIDE_TILE_COLORS = "wp81_hide_tile_colors"
        const val KEY_WP81_TILE_COUNTS = "wp81_tile_counts"
        const val KEY_WP81_COLUMNS = "wp81_columns"
        const val KEY_WP81_HIDDEN_TILES = "wp81_hidden_tiles"

        /** WP8.1 shipped Cyan as the out-of-box accent. */
        val WP81_DEFAULT_ACCENT: Int = 0xFF1BA1E2.toInt()

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
        const val KEY_PLUS95_THEME = "plus95_theme"
        const val PLUS95_DEFAULT = "default"

        val PLUS95_THEMES: List<Plus95Theme> = listOf(
            Plus95Theme("architecture", "Architecture", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("baseball", "Baseball", 0xFFD0A870.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("cityscape", "Cityscape", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("dangerous_creatures", "Dangerous Creatures", 0xFF707070.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("falling_leaves", "Falling Leaves", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("fashion", "Fashion", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("garfield", "Garfield", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("geometry", "Geometry", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("golf", "Golf", 0xFFE0C8A0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("inside_your_computer", "Inside your Computer", 0xFFA8C8A8.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("jazz", "Jazz", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("jungle", "Jungle", 0xFFB8A068.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("leonardo_da_vinci", "Leonardo da Vinci", 0xFFBFA59F.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("mystery", "Mystery", 0xFF687868.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("nature", "Nature", 0xFFD8C0A0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("rock_n_roll", "Rock 'n' Roll", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("sci_fi", "Sci-Fi", 0xFFC0C0C0.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("science", "Science", 0xFF8399B1.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("space", "Space", 0xFF809098.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("sports", "Sports", 0xFFB0E0A0.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("the_60s_usa", "The 60's USA", 0xFFD068D8.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("the_golden_era", "The Golden Era", 0xFFB8C8B8.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("travel", "Travel", 0xFF908070.toInt(), null, "menu.ogg", "start.ogg"),
            Plus95Theme("tropical_interlude", "Tropical Interlude", 0xFFB0A888.toInt(), "busy.png", "menu.ogg", "start.ogg"),
            Plus95Theme("underwater", "Underwater", 0xFF3868C8.toInt(), "busy.png", "menu.ogg", "start.ogg"),
        )

        /**
         * The one launcher alias the manifest ships enabled. Anything else reported as
         * COMPONENT_ENABLED_STATE_DEFAULT is therefore off. Must track android:enabled in
         * AndroidManifest.xml.
         */
        private const val DEFAULT_LAUNCHER_ALIAS = ".LauncherIconXP"

        const val CLASSIC_GRAY: Int = 0xFFD3CEC7.toInt()
    }
}
