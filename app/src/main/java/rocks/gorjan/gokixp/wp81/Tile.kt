package rocks.gorjan.gokixp.wp81

/**
 * A tile's footprint, measured in small-tile cells.
 *
 * Windows Phone drew Start on a four-column grid and offered three footprints on it: the
 * 1x1, the 2x2, and the 4x2 that filled the row. This launcher grew a handful of sizes in
 * between them, and then let the wall be set to anything from three cells across to
 * twelve - at which point a fixed list of footprints stops being able to follow it. On a
 * six-column wall there was no way to say "three across" or "the whole row", and the
 * resize drag jumped from two cells straight to four because four was the next size the
 * list had.
 *
 * So a size is any pair of spans rather than one of a list, and the drag moves a cell at a
 * time whatever the wall is set to. The names below survive as the sizes the wall is built
 * with and the ones the tiles are drawn for - a tile still reads its own footprint to
 * decide what it has room for - but they are points on a range now rather than the whole
 * of it.
 */
data class TileSize(val cols: Int, val rows: Int) {

    /**
     * What this footprint is written down as.
     *
     * The eight footprints that used to be the whole list keep the names they were stored
     * under, so a wall arranged before sizes were free reads back exactly as it was left.
     * Anything else is written as its spans - "3x2" - which [fromName] reads either way.
     */
    val name: String get() = LEGACY_NAMES[this] ?: "${cols}x$rows"

    /** The next footprint the resize chevron offers. */
    fun next(): TileSize {
        val at = CYCLE.indexOf(this)
        return if (at < 0) SMALL else CYCLE[(at + 1) % CYCLE.size]
    }

    /**
     * Whether the tile has room for words as well as a glyph.
     *
     * Only the 1x1 does not: everything else can carry at least a label, and a
     * notification or a track title cropped to one line.
     */
    val canShowText: Boolean
        get() = cols > 1 || rows > 1

    /**
     * Whether there is room for a subtitle under the title.
     *
     * True of everything but the 1x1: a one-row strip is short, but two compact lines
     * still fit once the app-name label steps aside for them - see [isStrip].
     */
    val hasTwoTextLines: Boolean
        get() = canShowText

    /**
     * A one-row tile that is more than one cell across.
     *
     * Its height is spoken for by the content, so the app-name label along the bottom
     * gives way whenever a notification or a track is being shown.
     */
    val isStrip: Boolean
        get() = rows == 1 && cols > 1

    companion object {
        val SMALL = TileSize(1, 1)

        // One-row strips, two to four cells across. Wide enough for a line of text and a
        // control beside it, without giving up a whole band of the grid to one tile.
        val SMALL_WIDE = TileSize(2, 1)
        val SMALL_WIDE_3 = TileSize(3, 1)
        val SMALL_WIDE_4 = TileSize(4, 1)

        val MEDIUM = TileSize(2, 2)

        // Two columns run down the page, three and four rows deep. The counterpart to the
        // strips: those spend a whole band of the grid on one line, these spend half the
        // width on a column of it, which is what a run of pictures or a list wants.
        val MEDIUM_TALL_3 = TileSize(2, 3)
        val MEDIUM_TALL_4 = TileSize(2, 4)

        val WIDE = TileSize(4, 2)

        /**
         * How tall a tile may be dragged.
         *
         * Width has a ceiling of its own - the wall is only so many cells across - but
         * the grid grows rows on demand, so height needs one stated. Twice the tallest
         * footprint the wall shipped with: past that a tile is not a tile, it is a page.
         */
        const val MAX_ROWS = 8

        /** The order the resize chevron steps through. */
        private val CYCLE = listOf(
            SMALL, SMALL_WIDE, SMALL_WIDE_3, SMALL_WIDE_4,
            MEDIUM, MEDIUM_TALL_3, MEDIUM_TALL_4, WIDE
        )

        /** The names the footprints that used to be an enum are stored under. */
        private val LEGACY_NAMES = mapOf(
            SMALL to "SMALL",
            SMALL_WIDE to "SMALL_WIDE",
            SMALL_WIDE_3 to "SMALL_WIDE_3",
            SMALL_WIDE_4 to "SMALL_WIDE_4",
            MEDIUM to "MEDIUM",
            MEDIUM_TALL_3 to "MEDIUM_TALL_3",
            MEDIUM_TALL_4 to "MEDIUM_TALL_4",
            WIDE to "WIDE"
        )

        fun fromName(name: String?): TileSize {
            if (name.isNullOrEmpty()) return MEDIUM
            for ((size, stored) in LEGACY_NAMES) if (stored == name) return size
            val parts = name.split('x')
            if (parts.size != 2) return MEDIUM
            val cols = parts[0].toIntOrNull() ?: return MEDIUM
            val rows = parts[1].toIntOrNull() ?: return MEDIUM
            return forSpan(cols, rows)
        }

        /**
         * The size a resize drag of this many cells is asking for.
         *
         * Both spans are taken as they come - a drag that has reached three cells across
         * and two down wants a 3x2, whether or not that is a shape the wall was ever
         * built with. Only the ceilings are applied here; the floor on width is the
         * grid's, which knows how many columns it has. See [MAX_ROWS].
         */
        fun forSpan(cols: Int, rows: Int): TileSize =
            TileSize(cols.coerceAtLeast(1), rows.coerceIn(1, MAX_ROWS))
    }
}

/**
 * What a tile launches when tapped.
 *
 * Tiles are backed by the launcher's existing [rocks.gorjan.gokixp.DesktopIcon] list -
 * the desktop icons and folders the user already had become the Start screen - plus
 * anything they pin from the app list.
 */
data class Tile(
    /** Matches DesktopIcon.id for migrated icons, or the package name for pinned apps. */
    val id: String,
    val label: String,
    val packageName: String,
    var size: TileSize,
    /** Position in the packing order. Lower comes first. */
    var index: Int,
    val kind: Kind
) {
    enum class Kind {
        APP, SYSTEM_APP, FOLDER, MY_COMPUTER, RECYCLE_BIN, URL_SHORTCUT,

        /**
         * The one live tile that is the shell's own rather than any program's.
         *
         * What is left here once the programs have taken theirs back: the day the phone is
         * standing in. It has no app behind it to be the tile of - the calendar reads the
         * phone's own events - so the shell provides it, and it cannot be unpinned. See
         * [isBuiltIn]. The air outside used to stand beside it and is now one of the
         * Weather app's own readings; see WeatherStore.airQuality.
         */
        LIVE_CALENDAR,

        /**
         * The clock, which is the Alarms tile: the time, the date, and an alarm coming.
         */
        LIVE_CLOCK,

        /** The forecast, which is the Weather tile. */
        LIVE_WEATHER,

        /** The headlines, which is the News tile. */
        LIVE_NEWS,

        /**
         * The camera roll, turning over one picture at a time - the Files tile.
         *
         * The one live tile whose content the phone already has - and the one that shows
         * a picture rather than a reading, so its faces carry no words and no wash. See
         * PhotoFeed.
         */
        LIVE_PHOTOS,

        /**
         * The address book, as the wall of faces Windows Phone put on Start - the People
         * tile.
         *
         * The other tile whose content the phone already has, and the only one that is a
         * grid rather than a face: it fills itself with contact pictures and turns them
         * over a square at a time. See PeopleMosaicView, which draws it, and ContactFeed,
         * which reads them.
         */
        LIVE_PEOPLE,

        /**
         * The charge, drawn into the cell that holds it - the Battery tile.
         *
         * The one live tile whose reading is a picture as much as a number: the mark is a
         * battery and the level is painted inside it, so the smallest tile on the wall
         * still answers the question without room for a figure. Which makes it the only
         * widget that has something to show at 1x1 - see TileView.showsLive, where every
         * other one stands down to its icon. See BatteryFaceView.
         */
        LIVE_BATTERY,

        /**
         * The launcher's own settings (Display Properties).
         *
         * Also a built-in, for a blunt reason: the desktop themes reach settings by
         * right-clicking the wallpaper or via the Start menu, and the phone shell has
         * neither. Without a tile there is no way in at all.
         */
        SETTINGS;

        /**
         * The live tile one of the shell's own programs has instead of an icon.
         *
         * Six of them, and each is that program's tile rather than a widget standing
         * beside it: Alarms shows the time, Files the camera roll, Battery the charge, and
         * Weather, News and People the three things they are named after. Live in every way
         * the shell's own two are - they draw their content instead of a mark, and the host
         * hands it to them by kind rather than by name - but pinned, moved, resized and
         * unpinned like any other program's tile, because that is what they are. See
         * WP81TileHost.PROGRAM_WIDGETS, which is where a package name becomes one of
         * these.
         */
        val isProgramWidget: Boolean
            get() = this == LIVE_CLOCK || this == LIVE_WEATHER || this == LIVE_NEWS ||
                this == LIVE_PHOTOS || this == LIVE_PEOPLE || this == LIVE_BATTERY

        /** Whether the tile draws a reading of its own rather than a mark. */
        val isLiveWidget: Boolean
            get() = this == LIVE_CALENDAR || isProgramWidget

        /**
         * Tiles the shell provides rather than the user pinning them.
         *
         * They can be moved and resized but not removed: they are rebuilt on every
         * refresh, so unpinning one would only make it reappear. A program's live tile is
         * not one of these - see [isProgramWidget].
         */
        val isBuiltIn: Boolean
            get() = this == LIVE_CALENDAR || this == SETTINGS
    }
}
