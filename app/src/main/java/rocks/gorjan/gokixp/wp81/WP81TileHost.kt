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
            Triple(WIDGET_AQI, "Air quality", Tile.Kind.LIVE_AQI),
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

    /** Whether the user opted in to air quality. */
    fun showAqi(): Boolean = prefs.getBoolean("show_aqi", false)

    /** The last air quality reading that was fetched, if there is one. */
    fun cachedAqi(): Int? = try {
        prefs.getInt("aqi_data", -1).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    fun aqiLabel(aqi: Int): String = when {
        aqi <= 26 -> "Good"
        aqi <= 33 -> "Fair"
        aqi <= 66 -> "Moderate"
        aqi <= 100 -> "Poor"
        else -> "Very poor"
    }

    /**
     * What a live tile that shows one steady reading has to say right now.
     *
     * The clock, the calendar and the index. The rest are absent on purpose: the weather,
     * the news, the pictures and the faces are runs rather than single readings, and
     * arrive through setLiveWidgetRotation and setPeopleMosaic instead.
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

            Tile.Kind.LIVE_AQI -> {
                val aqi = if (showAqi()) cachedAqi() else null
                // The index is the reading, and the caption over it says both what the
                // number is and what it amounts to. Lower case: this shell shouts at nobody.
                if (aqi == null) TileView.Reading("--", "aqi", "tap to enable")
                else TileView.Reading(
                    number = aqi.toString(),
                    caption = "${aqiLabel(aqi).lowercase(locale)} aqi"
                )
            }

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
     * The same conditions as a mark rather than a word, for a face that has no room for
     * the word - which is the forecast panel, where three of them stand in a row and the
     * label under each is already spoken for by which day it is.
     *
     * [night] is true of tonight's low, and of the reading taken now when it is taken
     * after dark: a forecast for tomorrow is about tomorrow's daylight, whatever hour it
     * is being read at.
     */
    fun weatherGlyph(code: Int, night: Boolean = false): Int? =
        WeatherCodes.glyph(code, night)

    /**
     * One of the readings the weather tile shows: the figure, the sky it was taken under,
     * and which of them it is.
     *
     * [name] is the plain word - "now", "noon", "tonight", "tomorrow" - which is what a
     * column in the panel is headed by, having a column's width for it. [spelled] is the
     * same reading said in full, for a face with a whole tile to say it on: "noon" alone
     * does not say that the figure under it is a maximum. Where the word is already the
     * whole of it, the two are the same.
     */
    private data class WeatherReading(
        val temperature: Int,
        val code: Int,
        val name: String,
        val spelled: String = name,
        /**
         * Whether this reading falls after dark: the one taken now, when it is taken at
         * night, and tonight's low always.
         */
        val night: Boolean = false
    )

    /**
     * What there is to say about the weather, in the order the tile says it.
     *
     * Now, then what the sky does next, then tomorrow's high. The middle of the three
     * turns over during the day: while the day can still reach its peak it is today's
     * high, and once that peak is behind us it is tonight's low instead. A high already
     * spent is a number that can only be higher than the reading beside it, for a reason
     * that has passed; the night after it has not happened yet, and the tile is worth
     * three readings either way. See [todayHighAhead] and [tonightReading].
     *
     * Empty when there is no cached reading at all, and one long when the forecast has not
     * arrived with it - or two, in an evening whose hourly figures have run out and can no
     * longer say what tonight does.
     */
    private fun weatherReadings(): List<WeatherReading> {
        val cached = cachedWeatherJson() ?: return emptyList()
        return try {
            val current = cached.getJSONObject("current")
            // The cache is metric whoever wrote it - see WeatherStore - so every figure
            // out of it is converted here rather than at the point it is drawn. This is the
            // only place the scale is applied, and the tile prints no letter to say which
            // one it is, so the number has to be right on its own.
            val unit = weatherUnit()
            val readings = mutableListOf(
                WeatherReading(
                    WeatherStore.temperature(current.getDouble("temperature_2m"), unit),
                    current.optInt("weather_code", -1),
                    "now",
                    night = current.optInt("is_day", 1) == 0
                )
            )
            val daily = cached.optJSONObject("daily")
            val highs = daily?.optJSONArray("temperature_2m_max")
            val codes = daily?.optJSONArray("weather_code")
            if (highs != null && codes != null && highs.length() >= 2) {
                if (todayHighAhead(cached)) {
                    // Headed "noon" rather than "today", which the reading beside it is
                    // also of: what separates the two is the hour, not the day.
                    readings += WeatherReading(
                        WeatherStore.temperature(highs.getDouble(0), unit),
                        codes.optInt(0, -1),
                        "noon",
                        "max today"
                    )
                } else {
                    tonightReading(cached, unit)?.let { readings += it }
                }
                readings += WeatherReading(
                    WeatherStore.temperature(highs.getDouble(1), unit),
                    codes.optInt(1, -1),
                    "tomorrow",
                    "max tomorrow"
                )
            }
            readings
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not read the weather", e)
            emptyList()
        }
    }

    /**
     * Whether today's high is still to come.
     *
     * The daily block carries the figure but not the hour it falls on, so the hourly
     * temperatures are what separate an afternoon still ahead from one already over: find
     * the hour today reaches its highest - the last of them, if the peak is flat - and see
     * whether it is behind the current hour.
     *
     * True whenever the answer cannot be read - a cache saved before the hourly figures
     * were asked for, or one left over from another day - which leaves the tile as it was
     * rather than hiding a face on a guess.
     */
    private fun todayHighAhead(cached: org.json.JSONObject): Boolean {
        return try {
            val hourly = cached.optJSONObject("hourly") ?: return true
            val times = hourly.optJSONArray("time") ?: return true
            val temperatures = hourly.optJSONArray("temperature_2m") ?: return true

            val now = localHour(cached)
            val today = now.substring(0, 10)

            var peak = Double.NEGATIVE_INFINITY
            var peakHour: String? = null
            for (i in 0 until minOf(times.length(), temperatures.length())) {
                val time = times.optString(i)
                if (!time.startsWith(today)) continue
                val temperature = temperatures.optDouble(i, Double.NaN)
                if (temperature.isNaN()) continue
                // >= rather than >: a peak held over several hours has not passed until
                // the last hour holding it has.
                if (temperature >= peak) {
                    peak = temperature
                    peakHour = time
                }
            }

            // The peak landing on the current hour still counts as ahead - it is being
            // reached now, not spent.
            peakHour?.let { it.substring(0, minOf(it.length, 13)) >= now } ?: true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not tell when today's high falls", e)
            true
        }
    }

    /**
     * The hour it is where the weather is, as "yyyy-MM-ddTHH".
     *
     * Times come back in the location's own zone (timezone=auto), which is not necessarily
     * the phone's, so an hour of the forecast is held against this rather than against the
     * device clock: the wall clock there is a UTC formatting of an instant shifted by the
     * offset the response states.
     *
     * Cut to the hour so that it sorts against the response's own times, which are longer
     * - it falls below every minute of its own hour and above all of the one before.
     */
    private fun localHour(cached: org.json.JSONObject): String {
        val offsetMillis = cached.optLong("utc_offset_seconds", 0L) * 1000L
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH", java.util.Locale.US)
        stamp.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return stamp.format(java.util.Date(System.currentTimeMillis() + offsetMillis))
    }

    /**
     * Tonight's low, and the sky it falls under.
     *
     * The daily block has no such reading. Its minimum is the whole day's, and by the
     * evening that figure is this morning's - already spent, which is the same fault that
     * takes the day's high out of the run. So the hours answer it instead: from this one
     * until the sun is up again, the coldest of them, under whatever the sky is doing at
     * that hour.
     *
     * Morning is tomorrow's sunrise where the response states one and nine o'clock where
     * it does not. The window is only ever asked for once today's peak has passed, so it
     * runs from an afternoon or an evening forward into the night rather than across a
     * whole day.
     *
     * Null when the hourly figures are missing, or have nothing left between now and the
     * morning: a tile two readings wide is better than a low nobody looked up.
     */
    private fun tonightReading(cached: org.json.JSONObject, unit: String): WeatherReading? {
        return try {
            val hourly = cached.optJSONObject("hourly") ?: return null
            val times = hourly.optJSONArray("time") ?: return null
            val temperatures = hourly.optJSONArray("temperature_2m") ?: return null

            val daily = cached.optJSONObject("daily")
            val sunrise = daily?.optJSONArray("sunrise")?.optString(1).orEmpty()
            val date = daily?.optJSONArray("time")?.optString(1).orEmpty()
            val morning = when {
                sunrise.isNotEmpty() -> sunrise
                date.isNotEmpty() -> "${date}T09:00"
                else -> return null
            }

            val now = localHour(cached)
            var lowest = Double.NaN
            var at = -1
            for (i in 0 until minOf(times.length(), temperatures.length())) {
                val time = times.optString(i)
                // The hour it is now is tonight's as much as the ones after it - what is
                // left of it is still to come - and the stamp sorts below it, so it is in.
                if (time < now || time > morning) continue
                val temperature = temperatures.optDouble(i, Double.NaN)
                if (temperature.isNaN()) continue
                // < rather than <=: a trough held flat for hours belongs to the first of
                // them, whose sky is the nearer of the two to now.
                if (at < 0 || temperature < lowest) {
                    lowest = temperature
                    at = i
                }
            }
            if (at < 0) return null

            WeatherReading(
                WeatherStore.temperature(lowest, unit),
                hourly.optJSONArray("weather_code")?.optInt(at, -1) ?: -1,
                "tonight",
                "low tonight",
                night = true
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "could not read tonight's low", e)
            null
        }
    }

    /**
     * The readings the weather tile turns over, one face each.
     *
     * What a tile falls back on rather than what it prefers: a tile with two cells or more
     * shows the lot of them at once and never asks for this - see [weatherPanel]. This is
     * the 1x1 and the strips, which have room for one reading at a time.
     *
     * A run of faces rather than one reading, which is why this does not come out of
     * [liveContent] - the caller hands it to setLiveWidgetRotation instead.
     *
     * Each is labelled. A temperature with no label is a number, and three of them in turn
     * without labels are numbers that appear to disagree; the 1x1 shortens the labels
     * rather than dropping them, because "max tomorrow" does not fit across it and
     * "tomorrow" says the necessary half.
     */
    fun weatherFaces(size: TileSize): List<TileView.LiveFace> {
        val small = size == TileSize.SMALL
        return weatherReadings().map { reading ->
            TileView.LiveFace(
                // The degree and no letter. Which scale it is in was settled once, in the
                // Weather app's own settings, and a wall that repeated the answer on every
                // face would be answering a question nobody is still asking - the room goes
                // to the number, which is what the tile is for.
                title = "${reading.temperature}°",
                // What the sky is doing, and under it which reading this is. That order
                // because the weather is what the tile is about and the label is only
                // which of the run it is showing.
                detail = listOfNotNull(
                    weatherWord(reading.code),
                    if (small) reading.name else reading.spelled
                ).joinToString("\n")
            )
        }
    }

    /**
     * The same readings as columns, for a tile with the width to hold them side by side.
     *
     * Nothing is dropped to make them fit: the panel sets itself to the column it has, and
     * a tile too narrow for that is a tile that should be turning faces instead. The
     * labels are the plain words - a column is headed by the day, not by what the figure
     * is of, which the row of them says once by being a row.
     *
     * Takes no size, unlike [weatherFaces]: what a column can hold is a question the panel
     * answers for itself once it knows how wide the tile made it.
     */
    fun weatherPanel(): List<ForecastPanelView.Column> {
        return weatherReadings().map { reading ->
            ForecastPanelView.Column(
                label = reading.name,
                glyph = weatherGlyph(reading.code, reading.night),
                // Bare, as on a turning face - and more so here, where three of them are
                // read across one row and the letter would be printed three times to say
                // one thing. See [weatherFaces].
                reading = "${reading.temperature}°"
            )
        }
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
        val weekdayPattern = if (size == TileSize.WIDE) "EEEE" else "EEE"
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
        return iconProvider.glyphFor(tile.packageName, fallback)
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
         * Three kinds of path end up in the mappings: a file the user imported, which
         * lives in the app's own storage; an SVG from the Windows Phone set, which nothing
         * in the platform will decode but whose path data it will draw; and an ordinary
         * image in the assets.
         */
        fun loadIconFromPath(context: Context, iconPath: String): android.graphics.drawable.Drawable? {
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
            "system.people" to Tile.Kind.LIVE_PEOPLE
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
            WIDGET_WELCOME, WIDGET_SETTINGS
        )

        const val KEY_BUILTIN_TILES = "wp81_builtin_tiles"
        const val KEY_BUILTIN_TILES_LANDSCAPE = "wp81_builtin_tiles_landscape"
    }
}
