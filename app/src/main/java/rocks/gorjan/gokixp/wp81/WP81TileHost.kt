package rocks.gorjan.gokixp.wp81

import android.content.Context
import rocks.gorjan.gokixp.DesktopIcon
import rocks.gorjan.gokixp.IconType
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

/**
 * What goes on a Start screen, and how each tile is painted.
 *
 * This used to sit in MainActivity, which was fine while the phone was the only thing
 * showing tiles. The car screen shows the same wall on a second display, and the one
 * thing it must not do is keep its own idea of what is pinned and what colour it is -
 * a tile added or recoloured on the phone has to be on the car screen too, without
 * anyone remembering to add it twice.
 *
 * So the wall's *contents* live here, reachable from anywhere with a Context, while what
 * a tap does about them stays with the caller: opening a window is the launcher's job on
 * the phone, and on the car screen most tiles have nowhere to open at all.
 *
 * The seams are the things only the launcher can answer - its live icon list, whether a
 * rename is in force, and which way up the arrangement is being read. A read-only surface
 * such as the car passes no writers, so nothing it does can disturb the phone's wall.
 */
class WP81TileHost(
    private val context: Context,
    /** The launcher's live icon list, or a copy loaded from preferences. */
    private val icons: () -> MutableList<DesktopIcon>,
    /** Writes the icon list back. Left out by a surface that only reads. */
    private val saveIcons: () -> Unit = {},
    /** Writes a freshly numbered arrangement back. Left out by a read-only surface. */
    private val persistTiles: (List<Tile>) -> Unit = {},
    /** A name the user typed for this app, if they typed one. */
    private val displayName: (packageName: String, original: String) -> String =
        { _, original -> original },
    /**
     * Which of the two arrangements to read.
     *
     * The phone keeps one for each way it is held. The car screen is wide, and so looks
     * like the sideways one, but it is not the same wall: reading the landscape
     * arrangement there would tie the car's layout to a phone orientation the user may
     * never have arranged. It reads the upright one, which is the one that is always kept.
     */
    private val landscape: () -> Boolean = { false },
    /**
     * Built-in tiles this surface does not want.
     *
     * Defaults to the set the user hid on the phone, which is right for the phone and
     * wrong for the car: hiding the clock on a phone that already shows one in its status
     * bar says nothing about wanting no clock in the car, where there is no status bar to
     * read. A surface that wants its own selection passes it.
     */
    private val hiddenTiles: (WP81Settings) -> Set<String> = { it.getWP81HiddenTiles() }
) {

    private val themeManager = WP81Settings(context)

    private val prefs =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /** Read once per rebuild rather than per tile: it parses a string every time. */
    private var tileColors: Map<String, Int> = emptyMap()

    /** Whether those colours are currently held back so the wallpaper shows. */
    private var tileColorsHidden = false

    /** Picks up colour settings. Call before building or repainting a wall. */
    fun refreshColors() {
        tileColors = themeManager.getWP81TileColors()
        tileColorsHidden = themeManager.getWP81HideTileColors()
    }

    /**
     * Where this icon sits on the wall being read, and how big.
     *
     * Reading sideways falls back to the upright placement while there is none of its own,
     * so the first turn of the phone lands on the wall the user already knows rather than
     * on an alphabetical one. Writing never falls back.
     */
    private var DesktopIcon.wp81TileIndex: Int?
        get() = if (landscape()) tileIndexLandscape ?: tileIndex else tileIndex
        set(value) {
            if (landscape()) tileIndexLandscape = value else tileIndex = value
        }

    private var DesktopIcon.wp81TileSize: String?
        get() = if (landscape()) tileSizeLandscape ?: tileSize else tileSize
        set(value) {
            if (landscape()) tileSizeLandscape = value else tileSize = value
        }

    fun buildTiles(): List<Tile> {
        val placements = loadBuiltInPlacements()
        val widgets = builtInTiles(placements)
        var migrated = false
        // Only what sits at the top level: icons filed inside a folder belong to that
        // folder's page, not to Start. The Recycle Bin and My Computer are desktop
        // furniture with no phone counterpart, so they are left off entirely - the icons
        // themselves survive for when the user switches back to a desktop theme.
        val ordered = icons().filter {
            it.parentFolderId == null &&
                it.type != IconType.RECYCLE_BIN &&
                it.type != IconType.MY_COMPUTER
        }.sortedWith(
            compareBy(
                { it.wp81TileIndex ?: Int.MAX_VALUE },
                { it.portraitGridIndex ?: Int.MAX_VALUE },
                { it.name.lowercase() }
            )
        )
        val tiles = ordered.mapIndexed { position, icon ->
            if (icon.wp81TileIndex == null) {
                icon.wp81TileIndex = position
                icon.wp81TileSize = TileSize.MEDIUM.name
                migrated = true
            }
            Tile(
                id = icon.id,
                label = displayName(icon.packageName, icon.name)
                    .replace("\\n", " ").replace("\n", " "),
                packageName = icon.packageName,
                size = TileSize.fromName(icon.wp81TileSize),
                index = icon.wp81TileIndex ?: position,
                kind = kindFor(icon)
            )
        }
        if (migrated) saveIcons()

        // Built-ins and user tiles share one ordering, sorted by their stored positions,
        // then renumbered densely so the packer sees a clean sequence.
        val all = (widgets + tiles).sortedBy { it.index }
        all.forEachIndexed { i, tile -> tile.index = i }

        // A first run has just invented positions for the built-ins; write them down now
        // so they are not re-invented on the next refresh.
        if (placements.size < widgets.size) persistTiles(all)
        return all
    }

    /**
     * The tiles the shell always provides, pinned above the user's own.
     *
     * Two of them now. Both are part of the shell rather than the user's arrangement:
     * they always show current content and cannot be unpinned, so they are rebuilt on
     * every refresh instead of being persisted as desktop icons.
     *
     * The five that used to stand here belong to the programs they were about - see
     * [PROGRAM_WIDGETS] - and Welcome was never live at all. What each of those was
     * showing became a tile of its own program's, at the place this had it; see
     * MainActivity.connectWP81ProgramTiles.
     */
    fun builtInTiles(
        placements: MutableMap<String, Pair<TileSize, Int>>
    ): List<Tile> {
        // Defaults, used only for a tile that has never been placed. Seeded with negative
        // indices so a first run sorts them ahead of any tiles the user already had; the
        // dense renumber that follows turns those into ordinary positions.
        val defaults = listOf(
            Triple(WIDGET_CALENDAR, "Calendar", Tile.Kind.LIVE_CALENDAR)
        )
        // Placements for tiles this no longer provides, so a wall built before they were
        // handed back does not go on holding room for them.
        RETIRED_WIDGETS.forEach { placements.remove(it) }

        val hidden = hiddenTiles(themeManager)
        return defaults.filterNot { (id, _, _) -> id in hidden }
            .mapIndexed { position, (id, label, kind) ->
                val stored = placements[id]
                // The calendar wants room for what is on; the index is a number.
                val defaultSize = when (kind) {
                    Tile.Kind.LIVE_CALENDAR -> TileSize.WIDE
                    else -> TileSize.MEDIUM
                }
                Tile(
                    id = id,
                    label = label,
                    packageName = id,
                    size = stored?.first ?: defaultSize,
                    index = stored?.second ?: (position - defaults.size),
                    kind = kind
                )
            }
    }

    /**
     * Which kind of tile an icon becomes.
     *
     * A program with a live tile has it wherever the icon is - out on the wall, or filed
     * in a folder. A folder opens into a band of the wall's own grid and the page a nested
     * one opens is a Start screen of its own, so both are fed exactly as the wall is:
     * there is no place to pin one of these where it is only a picture of itself.
     */
    fun kindFor(icon: DesktopIcon): Tile.Kind =
        when (icon.type) {
            IconType.FOLDER -> Tile.Kind.FOLDER
            IconType.MY_COMPUTER -> Tile.Kind.MY_COMPUTER
            IconType.RECYCLE_BIN -> Tile.Kind.RECYCLE_BIN
            IconType.URL_SHORTCUT -> Tile.Kind.URL_SHORTCUT
            IconType.APP ->
                PROGRAM_WIDGETS[icon.packageName]
                    ?: if (MainActivity.isSystemApp(icon.packageName)) Tile.Kind.SYSTEM_APP
                    else Tile.Kind.APP
        }

    /**
     * What a tile should actually be painted, which is nothing while colours are hidden.
     *
     * Null rather than the accent: a tile with no colour of its own is a window onto the
     * Start background, where one painted the accent would be a solid block of it - and
     * seeing the wallpaper is the entire point of the switch. See TileView.onDraw.
     */
    fun colorFor(tile: Tile): Int? {
        if (tileColorsHidden) return null
        tileColors[tile.id]?.let { return it }
        // Failing one of its own, a tile filed in a folder wears the folder's: a folder is
        // one thing on the wall and opens into one band, and a row of tiles inside it in
        // the plain accent read as having escaped from somewhere else. Painting one of
        // them still overrides this - what the user set by hand is never guessed over.
        val parent = icons().firstOrNull { it.id == tile.id }?.parentFolderId ?: return null
        return tileColors[parent]
    }

    /**
     * What a live tile that shows one steady reading has to say right now.
     *
     * The clock and the calendar. The rest are absent on purpose: the weather, the news,
     * the pictures and the faces are runs rather than single readings, and arrive through
     * setLiveWidgetRotation and setPeopleMosaic instead.
     */
    fun liveContent(
        tile: Tile,
        calendarSummary: (TileSize) -> TileView.Reading? = { null }
    ): TileView.Reading? {
        val now = java.util.Date()
        val locale = java.util.Locale.getDefault()
        return when (tile.kind) {
            Tile.Kind.LIVE_CLOCK -> {
                // No leading zero on the hour: a tile is read at a glance rather than
                // lined up in a column, and "9:05" is how the time is said.
                val timePattern =
                    if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
                val small = tile.size == TileSize.SMALL
                val weekday = java.text.SimpleDateFormat("EEE", locale).format(now)
                    .lowercase(locale)
                // The date over the time, both on the front: a clock is the one tile whose
                // reading is worth having at a glance, and turning it over to find out
                // which day it is means waiting for it to come back round to the time.
                // The 1x1 has room for the weekday alone; anything bigger takes the date.
                val date =
                    if (small) weekday
                    else weekday + " " + java.text.SimpleDateFormat("d MMM", locale)
                        .format(now).lowercase(locale)
                TileView.Reading(
                    number = java.text.SimpleDateFormat(timePattern, locale).format(now),
                    caption = date
                )
            }

            Tile.Kind.LIVE_CALENDAR -> calendarSummary(tile.size)

            else -> null
        }
    }

    fun loadBuiltInPlacements(): MutableMap<String, Pair<TileSize, Int>> =
        // The upright arrangement stands in until the screen has been arranged on its
        // side, exactly as an icon's own placement does.
        (if (landscape()) storedBuiltInPlacements(sideways = true) else null)
            ?: storedBuiltInPlacements(sideways = false)
            ?: mutableMapOf()

    /**
     * One arrangement's stored placements, with no standing in for the other.
     *
     * Null where that arrangement has never been written, which is what [loadBuiltInPlacements]
     * falls back on - and what anything moving these placements elsewhere has to know
     * about, since writing an upright position into the sideways arrangement would settle
     * a wall the user has never laid out.
     */
    fun storedBuiltInPlacements(sideways: Boolean): MutableMap<String, Pair<TileSize, Int>>? {
        val raw = prefs.getString(
            if (sideways) KEY_BUILTIN_TILES_LANDSCAPE else KEY_BUILTIN_TILES, null
        ) ?: return null
        val result = mutableMapOf<String, Pair<TileSize, Int>>()
        for (entry in raw.split(";")) {
            val parts = entry.split(":")
            if (parts.size != 3) continue
            val index = parts[2].toIntOrNull() ?: continue
            result[parts[0]] = TileSize.fromName(parts[1]) to index
        }
        return result
    }

    fun saveBuiltInPlacements(
        placements: Map<String, Pair<TileSize, Int>>,
        sideways: Boolean = landscape()
    ) {
        val raw = placements.entries.joinToString(";") { (id, p) ->
            "$id:${p.first.name}:${p.second}"
        }
        val key = if (sideways) KEY_BUILTIN_TILES_LANDSCAPE else KEY_BUILTIN_TILES
        prefs.edit().putString(key, raw).apply()
    }


    // ------------------------------------------------------------------ weather

    private fun cachedWeatherJson(): org.json.JSONObject? = try {
        prefs.getString("weather_data", null)?.let { org.json.JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun weatherUnit(): String = WeatherStore.unit(context)

    /**
     * The one word a tile face has room for.
     *
     * The grouping itself is [WeatherCodes]'s, and so is the mark below - the Weather app
     * that opens out of this tile reads them from there too, and a table copied into two
     * files is a table that will be edited in one of them.
     */
    fun weatherWord(code: Int): String? = WeatherCodes.word(code)

    /**
     * Everything the weather tile says, as one face.
     *
     * Where it is, what the sky is doing, what it is out there and how far the day moves.
     * The tile used to turn through three readings - now, the next thing the sky did, and
     * tomorrow - which meant a glance at it landed on whichever of the three it happened
     * to be showing; this is all of it at once, and the tile stopped turning. See
     * WeatherFaceView, which lays it out and drops what a short tile has no room for.
     *
     * The figure carries its scale here where the run of faces did not. Three readings in
     * a row printed the letter three times to say one thing; one reading prints it once,
     * and a tile that says 31 without saying of what is a tile somebody has to remember a
     * setting to read.
     *
     * Null when there is no cached reading at all, which leaves the tile as it was.
     */
    fun weatherFace(): WeatherFaceView.Reading? {
        val cached = cachedWeatherJson() ?: return null
        return try {
            val current = cached.getJSONObject("current")
            // The cache is metric whoever wrote it - see WeatherStore - so every figure out
            // of it is converted here rather than at the point it is drawn.
            val unit = weatherUnit()
            val daily = cached.optJSONObject("daily")
            // Today's two ends, which is what a range on a tile is: not the week's, and not
            // the next twelve hours', but how far the day the reader is standing in moves.
            val high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)
            val low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)
            WeatherFaceView.Reading(
                // What the Weather app is showing, which is where the reading came from.
                place = WeatherStore.selected(context)?.name.orEmpty(),
                condition = weatherWord(current.optInt("weather_code", -1)),
                temperature =
                    WeatherStore.temperature(current.getDouble("temperature_2m"), unit).toString(),
                unit = "\u00b0$unit",
                high = degrees(high, unit),
                low = degrees(low, unit)
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not read the weather", e)
            null
        }
    }

    // ------------------------------------------------------------------ battery

    /**
     * What the battery tile draws: the charge, and whether it is climbing.
     *
     * Read from the platform rather than from a cache, because there is nothing to cache -
     * the level is a sticky broadcast that is always current, and going to get it costs
     * one call. Written down on the way past, so that a wall left up all afternoon is also
     * the thing keeping the record the app's chart is drawn from. See BatteryStore.
     *
     * Null on a phone that will not say, which leaves the tile wearing its mark.
     */
    fun batteryFace(): BatteryFaceView.Reading? {
        val reading = BatteryStore.read(context) ?: return null
        if (!reading.known) return null
        BatteryStore.record(context, reading)
        return BatteryFaceView.Reading(reading.percent, reading.charging)
    }

    /** One end of the day, in the scale the tile is set in, or null where there is none. */
    private fun degrees(celsius: Double?, unit: String): String? {
        if (celsius == null || celsius.isNaN()) return null
        return "${WeatherStore.temperature(celsius, unit)}\u00b0"
    }

    // ----------------------------------------------------------------- calendar

    /**
     * The date, and what is next on it.
     *
     * The date needs nothing but a clock, so a surface with no access to the event caches
     * still gets a real calendar tile rather than a label; [caption] is what the next
     * appointment would have filled in.
     */
    fun calendarSummary(size: TileSize, caption: String? = null): TileView.Reading {
        val locale = java.util.Locale.getDefault()
        val now = java.util.Date()
        val calendar = java.util.Calendar.getInstance()
        val small = size == TileSize.SMALL

        // The day of the month as the number with the weekday against it - "Sun 30" -
        // which is how a date is read, the number being what is looked for and the
        // weekday what places it. Capitalised where the rest of the shell is lower case:
        // a weekday is a name, and the calendar tile is the one place it stands alone.
        // Spelled out wherever the tile is wide enough to carry it - four cells or more,
        // which the wall can now reach at more than one footprint.
        val weekdayPattern = if (size.cols >= 4 && !size.isStrip) "EEEE" else "EEE"
        val weekday = java.text.SimpleDateFormat(weekdayPattern, locale).format(now)
            // Not every locale hands back a capital - "dom.", "lun." - so it is done here
            // rather than left to the format.
            .replaceFirstChar { it.titlecase(locale) }

        return TileView.Reading(
            number = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString(),
            // The 1x1 has room for the date and nothing else.
            caption = if (small) null else caption ?: "no events",
            aside = weekday
        )
    }

    // -------------------------------------------------------------------- art

    private val iconProvider by lazy { MonochromeIconProvider(context) }

    /**
     * The icons the user chose by hand, as packageName to the file they picked.
     *
     * Kept under the phone's own key - see theme.CUSTOM_ICONS_KEY. Read here
     * so the car's tiles wear the same art the phone's do; reading any other theme's key
     * would dress the car in a desktop's icons and miss every one picked on a tile.
     */
    private val customIcons: Map<String, String> by lazy {
        val raw = prefs.getString(KEY_CUSTOM_ICONS, "").orEmpty()
        if (raw.isEmpty()) emptyMap()
        else raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /**
     * The mark on a tile.
     *
     * The provider prefers an app's themed monochrome layer, then its notification
     * silhouette, and falls back to the app's own icon - which is loaded here rather than
     * carried on the DesktopIcon, so that a wall of tiles costs one drawable per tile
     * actually shown instead of one per app installed.
     */
    fun glyphFor(tile: Tile): MonochromeIconProvider.Glyph? {
        // An icon the user chose outranks everything derived - the app's themed monochrome
        // layer, its notification silhouette, and the built-in glyphs for system tiles.
        // Full colour, because a picture someone picked is not a silhouette to be tinted.
        customIcons[tile.packageName]?.let { path ->
            runCatching { loadIconFromPath(context, path) }.getOrNull()?.let { drawable ->
                return MonochromeIconProvider.Glyph.FullColor(
                    drawable, iconProvider.ratioFor("custom:${tile.packageName}", drawable))
            }
        }

        val fixed = when (tile.kind) {
            Tile.Kind.FOLDER -> rocks.gorjan.gokixp.R.drawable.wp81_glyph_folder
            Tile.Kind.MY_COMPUTER -> rocks.gorjan.gokixp.R.drawable.wp81_glyph_computer
            Tile.Kind.URL_SHORTCUT -> rocks.gorjan.gokixp.R.drawable.wp81_glyph_ie
            // Live widgets draw their readings; there is no mark to resolve.
            else -> null
        }
        if (fixed != null) {
            val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, fixed)
            return drawable?.let { MonochromeIconProvider.Glyph.Monochrome(it, iconProvider.ratioFor("fixed:${tile.packageName}", it)) }
        }
        if (tile.kind != Tile.Kind.APP && tile.kind != Tile.Kind.SYSTEM_APP) return null
        val fallback = try {
            val pm = context.packageManager
            pm.getApplicationInfo(tile.packageName, 0).loadIcon(pm)
        } catch (e: Exception) {
            null
        }
        return iconProvider.glyphFor(tile.packageName, fallback, isTile = true)
    }

    companion object {

        /**
         * The launcher's icon list, read straight from preferences.
         *
         * The launcher keeps this list in memory and hands it over; anything else - the
         * car screen, in particular - has no MainActivity to ask and reads the same JSON
         * itself. Only what a tile needs is recovered: the art is left as a placeholder
         * because the wall resolves its own marks through the glyph provider, and loading
         * a drawable per installed app to throw them all away would cost seconds.
         */
        /**
         * Turns a stored icon path into something drawable.
         *
         * Four kinds of path end up in the mappings: a file the user imported, which
         * lives in the app's own storage; an SVG from the Windows Phone set, which nothing
         * in the platform will decode but whose path data it will draw; an ordinary image
         * in the assets; and one icon named out of an installed icon pack, which lives in
         * another package's resources and is not a file this app can open at all.
         */
        fun loadIconFromPath(context: Context, iconPath: String): android.graphics.drawable.Drawable? {
            if (iconPath.startsWith(rocks.gorjan.gokixp.IconPack.PATH_PREFIX)) {
                return rocks.gorjan.gokixp.IconPack.fromPath(context, iconPath)
            }
            return if (iconPath.endsWith(".svg", ignoreCase = true) &&
                    !iconPath.startsWith("$IMPORTED_ICONS_DIR/")
            ) {
                SvgIcon.fromAsset(context, iconPath)
            } else {
                val stream = if (iconPath.startsWith("$IMPORTED_ICONS_DIR/")) {
                    java.io.File(context.filesDir, iconPath).inputStream()
                } else {
                    context.assets.open(iconPath)
                }
                stream.use {
                    android.graphics.drawable.Drawable.createFromStream(it, iconPath)
                }
            }
        }

        const val IMPORTED_ICONS_DIR = "imported_icons"

        /** Where the Start screen's hand-picked icons are kept. */
        val KEY_CUSTOM_ICONS: String = CUSTOM_ICONS_KEY

        fun loadIcons(context: Context): MutableList<DesktopIcon> {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("desktop_icons", null) ?: return mutableListOf()
            val blank = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                val raw: List<Map<String, Any>> = com.google.gson.Gson().fromJson(json, type)
                raw.mapNotNull { data ->
                    try {
                        val packageName = data["packageName"] as String
                        // The player is called Music, as the phone called it.
                        val name = (data["name"] as String)
                            .let { if (packageName == "system.zune" && it == "Zune") "Music" else it }
                        val typeStr = data["type"] as? String
                        val iconType = try {
                            typeStr?.let { IconType.valueOf(it) } ?: IconType.APP
                        } catch (e: Exception) {
                            when (packageName) {
                                "recycle.bin" -> IconType.RECYCLE_BIN
                                "my.computer" -> IconType.MY_COMPUTER
                                else -> IconType.APP
                            }
                        }
                        DesktopIcon(
                            name = name,
                            packageName = packageName,
                            icon = blank,
                            x = 0f,
                            y = 0f,
                            id = data["id"] as String,
                            type = iconType,
                            parentFolderId = data["parentFolderId"] as? String,
                            portraitGridIndex = (data["portraitGridIndex"] as? Double)?.toInt(),
                            landscapeGridIndex = (data["landscapeGridIndex"] as? Double)?.toInt(),
                            targetUrl = data["targetUrl"] as? String,
                            tileSize = data["tileSize"] as? String,
                            tileIndex = (data["tileIndex"] as? Double)?.toInt(),
                            tileSizeLandscape = data["tileSizeLandscape"] as? String,
                            tileIndexLandscape = (data["tileIndexLandscape"] as? Double)?.toInt()
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.toMutableList()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "could not read the icon list", e)
                mutableListOf()
            }
        }

        private const val TAG = "WP81TileHost"

        const val WIDGET_CLOCK = "wp81.widget.clock"
        const val WIDGET_CALENDAR = "wp81.widget.calendar"
        const val WIDGET_NEWS = "wp81.widget.news"
        const val WIDGET_PHOTOS = "wp81.widget.photos"
        const val WIDGET_PEOPLE = "wp81.widget.people"
        /** Retired: the air is one of the Weather app's readings now. See RETIRED_WIDGETS. */
        const val WIDGET_AQI = "wp81.widget.aqi"
        const val WIDGET_WEATHER = "wp81.widget.weather"
        const val WIDGET_SETTINGS = "wp81.widget.settings"

        /** The Welcome tile's id, which was the package the shell knows it by. */
        const val WIDGET_WELCOME = "system.welcome"

        /**
         * The programs whose tile on the wall is a live one, and what it shows.
         *
         * The shell used to keep these five as widgets of its own, standing beside the
         * programs they were about: a Weather tile and a Weather app, a News tile and the
         * reader it opened. They were always the same thing said twice, and the tile was
         * the half that could not be unpinned. So each is the program's own tile now -
         * pinned from the app list like any other, and drawing what the program has to say
         * instead of its mark.
         *
         * Alarms takes the clock because a clock is what its three pages are all about,
         * and Files takes the camera roll because Files is where the pictures on this
         * phone actually are.
         */
        val PROGRAM_WIDGETS: Map<String, Tile.Kind> = mapOf(
            "system.alarms" to Tile.Kind.LIVE_CLOCK,
            "system.weather" to Tile.Kind.LIVE_WEATHER,
            "system.news" to Tile.Kind.LIVE_NEWS,
            "system.files" to Tile.Kind.LIVE_PHOTOS,
            "system.people" to Tile.Kind.LIVE_PEOPLE,
            "system.battery" to Tile.Kind.LIVE_BATTERY
        )

        /**
         * Widget ids the shell no longer places: the five above, Welcome, and Settings.
         *
         * Kept as a list because a placement outlives the tile it was for - it sits in
         * preferences until something takes it out - and because the one-time move onto
         * the programs' own tiles reads the positions out of it first.
         */
        val RETIRED_WIDGETS: List<String> = listOf(
            WIDGET_CLOCK, WIDGET_WEATHER, WIDGET_NEWS, WIDGET_PHOTOS, WIDGET_PEOPLE,
            WIDGET_WELCOME, WIDGET_SETTINGS, WIDGET_AQI
        )

        const val KEY_BUILTIN_TILES = "wp81_builtin_tiles"
        const val KEY_BUILTIN_TILES_LANDSCAPE = "wp81_builtin_tiles_landscape"
    }
}
