package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R

/**
 * Settings, as a Metro page.
 *
 * Deliberately not the launcher's Display Properties window: that is a Vista dialog full
 * of desktop concepts - screensavers, taskbar height, cursor, Plus! themes - none of which
 * exist on a phone. This page carries only what this shell can actually change.
 *
 * Two levels, the way the phone's own settings were. The page opens on a list of
 * categories - a name and, under it, the settings waiting behind it - and tapping one
 * turns to a page holding only those. It was one flat column before, and a column with a
 * dozen headings, four dozen accent swatches and a wallpaper strip in it is a page nobody
 * reads to the bottom of: the setting somebody came for was some unknown distance down a
 * scroll with no landmarks. A list of six names is a page you can take in at a glance,
 * and the line under each name is what makes it a list you can aim at rather than one you
 * have to open every row of - "blur" is not a word anybody guesses is under "wallpaper".
 *
 * Everything applies immediately. WP8.1 had no OK/Cancel here, and neither does this.
 */
@SuppressLint("ViewConstructor")
class WP81SettingsView(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    /** Fired whenever the user changes something; the host persists and repaints. */
    var onAccentPicked: ((Int) -> Unit)? = null
    var onDarkPicked: ((Boolean) -> Unit)? = null
    var onBackgroundPicked: ((String?) -> Unit)? = null

    /** Fired when the browse tile is tapped; the host runs the system image picker. */
    var onBrowse: (() -> Unit)? = null

    /**
     * Held on a wallpaper - one of the bundled set, or the picked one the browse square is
     * wearing.
     *
     * Carries the image and the bottom edge of the tile in this view's coordinates, so the
     * host can hang a command list off it. A tap on a wallpaper is what Start is wearing;
     * a hold is what the phone is wearing, which is a different question and belongs on a
     * command list rather than on a tile of its own.
     */
    var onWallpaperLongPress: ((String, Float) -> Unit)? = null

    /** Fired while the blur slider moves, 0 (sharp) to 1. */
    var onBlurChanged: ((Float) -> Unit)? = null

    /** Fired when the drift switch is toggled. */
    var onDriftChanged: ((Boolean) -> Unit)? = null

    /** Fired when the hide-tile-colours switch is toggled. */
    var onHideTileColorsChanged: ((Boolean) -> Unit)? = null

    /** Fired when the dim-all-tiles switch is toggled. */
    var onDimAllTilesChanged: ((Boolean) -> Unit)? = null

    /** Fired while the dim slider moves, 0 (untouched) to 1. */
    var onDimAmountChanged: ((Float) -> Unit)? = null

    /** Fired when the tiles are set to count their notifications, or only to mark them. */
    var onTileCountsChanged: ((Boolean) -> Unit)? = null

    /** Fired when the wall is set to a different number of columns. */
    var onColumnsPicked: ((Int) -> Unit)? = null

    /** Fired when the switch for following links in Internet Explorer is toggled. */
    var onOpenLinksInIeChanged: ((Boolean) -> Unit)? = null

    /** Fired when the navigation bar is set to wear the accent, or the page's ground. */
    var onAccentNavBarChanged: ((Boolean) -> Unit)? = null

    /** Fired when the navigation keys are taken off the screen, or given back. */
    var onHideNavBarChanged: ((Boolean) -> Unit)? = null

    /** Fired when the shell is set to take the whole display, or to leave the system bars. */
    var onFullscreenChanged: ((Boolean) -> Unit)? = null

    /** Fired when the pull-down is pointed at the Action Center, or back at Android's shade. */
    var onActionCenterChanged: ((Boolean) -> Unit)? = null

    /** Tapping the row that asks the phone to send its links to the launcher. */
    var onDefaultBrowser: (() -> Unit)? = null

    /** Tapping the row that opens the keyboard's own settings. */
    var onKeyboard: (() -> Unit)? = null

    /** Tapping the row that opens Android's own settings. */
    var onPhoneSettings: (() -> Unit)? = null

    /** Tapping the row that opens Welcome. */
    var onAbout: (() -> Unit)? = null

    /**
     * The four things the backup page can be asked to do.
     *
     * Four commands rather than one with a destination beside it, because a backup and a
     * restore are not the same kind of act and should not share a button: one of them
     * replaces everything the phone remembers, and a page where that is one tap away from
     * the harmless one is a page somebody will eventually mis-tap. The host asks before
     * either restore - see MainActivity.
     */
    var onBackUpToDrive: (() -> Unit)? = null
    var onBackUpToFile: (() -> Unit)? = null
    var onRestoreFromDrive: (() -> Unit)? = null
    var onRestoreFromFile: (() -> Unit)? = null

    /**
     * Tapping the icon pack row, with the row's bottom edge to hang the list of packs off.
     *
     * A command list rather than a page of its own, for the same reason the wallpaper's
     * other walls are one: what is being offered is a short list of names, and a phone
     * with two icon packs on it does not need a screen to itself to say so.
     */
    var onIconPack: ((Float) -> Unit)? = null

    /** Fired when the pack is invited onto the Start tiles, or sent back off them. */
    var onIconPackOnTilesChanged: ((Boolean) -> Unit)? = null

    /** Fired when the app list is set to open into its search box, or as a plain list. */
    var onAppListSearchFocusChanged: ((Boolean) -> Unit)? = null

    /** Fired when the Photos tile is set to turn through clips as well as stills. */
    var onPhotoTileVideosChanged: ((Boolean) -> Unit)? = null

    /**
     * The list of categories, which is what settings opens on.
     *
     * Its rows are [ActionRow]s at the larger size, because that is exactly what they are:
     * a name, a line under it, and a tap that goes somewhere. The line is [Category.detail]
     * - what is behind the row, rather than where any of it stands.
     */
    private val rootScroll = ScrollView(context)
    private val rootColumn = LinearLayout(context)

    /**
     * The name of the page, in the size the platform gave a program's own name.
     *
     * Not a [MetroPageHeader], which is the shape for a page that was pushed onto a stack
     * and carries the arrow back off it. Settings is not on anybody's stack - it is
     * reached from the key strip and from a tile, the way News and Music are - so it is
     * headed the way they are: the name alone, large, light and lower case, with no arrow
     * beside it. The way out is the shell's own back key, which is on screen throughout.
     *
     * The category pages behind it are pushed, and do carry the arrow. See [pageHeader].
     */
    private val header = TextView(context).apply {
        text = "settings"
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        textSize = APP_TITLE_SP
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        // Standing on the same margin as the rows under it, so the page has one left edge.
        setPadding(dp(24), dp(APP_TITLE_TOP_DP), dp(24), dp(APP_TITLE_BOTTOM_DP))
    }

    /**
     * The one page every category is shown on, dressed with that category's column.
     *
     * One page rather than six, because only ever one of them is on screen: the header,
     * the scroller and the turn are the same in each case, and the only difference is the
     * title and the settings hung under it. The columns themselves are built once, at
     * [categoryColumns], and swapped into [pageBody] as they are entered - so a row's
     * state, and the host's handle on it, outlive being looked at.
     */
    private val pageScroll = ScrollView(context)
    private val pageColumn = LinearLayout(context)
    private val pageHeader = MetroPageHeader(context, palette)
    private val pageBody = LinearLayout(context)
    private val pageTransition = MetroPageTransition(pageScroll)

    /** Which category is being looked at, or null on the list. See [handleBack]. */
    private var shownCategory: Category? = null

    private val categoryColumns = LinkedHashMap<Category, LinearLayout>()
    private val categoryRows = LinkedHashMap<Category, ActionRow>()

    private val accentGrid = LinearLayout(context)
    private val backgroundRow = LinearLayout(context)
    private val wallpaperStrip = LinearLayout(context)

    private val accentSwatches = mutableListOf<View>()
    private val accentMore = LinearLayout(context)
    private val accentMoreRow = LinearLayout(context)
    private val accentMoreLabel = TextView(context)
    private val accentChevron = ImageView(context)
    private var accentExpanded = false
    private val themeRows = mutableListOf<Pair<View, Boolean>>()
    private val columnRows = mutableListOf<Pair<View, Int>>()
    private var selectedColumns = 4
    private val countRows = mutableListOf<Pair<View, Boolean>>()
    private var selectedCounts = true
    private val wallpaperTiles = mutableListOf<Pair<StripTile, String?>>()

    /**
     * The square that opens the picker - and wears the picked picture while it is the one
     * Start has on.
     *
     * Held rather than rebuilt with the strip, because what it is wearing changes on a tap
     * elsewhere in the row: choosing a bundled wallpaper is what takes the picture off it.
     * A picked photograph has no square of its own in the strip - it is one file, replaced
     * by the next pick, not a library - so the square that goes and gets it is where it
     * shows. Otherwise the one wallpaper the user chose themselves was the only one they
     * could not see.
     */
    private val browseImage = ImageView(context)
    private val browseLabel = TextView(context)
    private val browseSquare = StripTile(context)

    /** Where the picked picture lives and a thumbnail of it. See [setCustomBackground]. */
    private var customBackground: String? = null
    private var customPreview: Drawable? = null

    /** Whether Start has a picture on at all, which is what the dim is a dim of. */
    private var hasStartBackground = false

    /** Whether the picked picture is the one Start has on, and so the one browse wears. */
    private val wearingCustomBackground: Boolean
        get() = customBackground != null && customBackground == selectedBackground

    private val blurLabel = TextView(context).apply {
        text = "blur"
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        textSize = 12f
        setPadding(dp(24), dp(6), dp(24), dp(2))
        visibility = GONE
    }
    private val blurSlider = MetroSlider(context).apply { visibility = GONE }

    private val driftRow = SwitchRow("drift wallpaper") { on -> onDriftChanged?.invoke(on) }

    /**
     * Puts every tile the user has painted back to the accent, for as long as it is on.
     *
     * A painted tile is a solid block - that is the whole point of painting one - and a
     * solid block is a hole in the photograph behind the wall. Turning them off is not the
     * same as unpainting them: the colours are kept, and the switch is here, on the
     * wallpaper page beside the picture it is in the way of, rather than in the tile menu
     * where undoing it would mean visiting every tile that had one.
     *
     * On the wallpaper page rather than under the accent, where it stood until now:
     * what it is for is the photograph. The accent is only where the tiles land when
     * their own colour is taken off them, and somebody who has just set a picture and
     * found a wall of solid squares in front of it is looking at this page, not at the
     * swatches.
     *
     * Always offered, unlike the background's own switches: a wall of painted tiles is
     * worth putting back to the accent on a plain Start screen too, and a switch that
     * appears only once a picture is set is one the user has no way of finding.
     */
    private val hideColorsRow =
        SwitchRow("hide custom tile colors") { on -> onHideTileColorsChanged?.invoke(on) }

    /**
     * Darkens every tile showing the photo, not only the ones with words on them.
     *
     * The wash under a tile's own words is there so white text can be read over whatever
     * the photograph happens to be doing - so it lands on the tiles that are saying
     * something and on no others, and a wall where three of forty are is a wall with
     * three darker squares in it. This asks for the tone on all of them. Filed under the
     * picture rather than under the tiles because it is a setting about how much of the
     * picture comes through, which is what the blur and the drift above it are too.
     */
    private val dimAllRow = SwitchRow("dim all tiles") { on ->
        // Shown from here rather than waiting on the host to hand the page its settings
        // back: the slider is the switch's own detail, and it should arrive with it.
        applyDimSliderVisibility()
        onDimAllTilesChanged?.invoke(on)
    }

    /**
     * How far the dim goes, offered only once it is on.
     *
     * A switch that darkens every tile is a switch with a strength: the tone that keeps a
     * headline readable is not the one somebody wants their photograph held down to. Kept
     * under the switch rather than beside the blur, because it is that switch's setting
     * and means nothing without it. See [blurSlider], which is shaped the same way.
     */
    private val dimLabel = TextView(context).apply {
        text = "dim"
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        textSize = 12f
        setPadding(dp(24), dp(0), dp(24), dp(2))
        visibility = GONE
    }
    private val dimSlider = MetroSlider(context).apply { visibility = GONE }

    /**
     * Whether a link opens here or in the phone's own browser.
     *
     * The same setting the desktop themes keep in Display Properties, and the same stored
     * answer - a link from a tile, a news story or the update goes to Internet Explorer
     * under both shells or under neither. It is only offered there, which under this theme
     * is a page the user has no way of reaching.
     */
    private val openLinksRow =
        SwitchRow("open links in Internet Explorer") { on -> onOpenLinksInIeChanged?.invoke(on) }

    /**
     * Whether the three keys along the bottom are painted in the accent.
     *
     * Filed under the accent rather than under the background, because it is a question
     * about where the colour goes rather than about what the page is: the strip is the
     * one piece of chrome that is on screen whatever the user is doing, and it is either
     * showing their colour or it is not. See [WP81NavBar.setAccented].
     */
    private val accentNavBarRow =
        SwitchRow("color the navigation bar") { on -> onAccentNavBarChanged?.invoke(on) }

    /**
     * Takes the three keys off the screen, and gives the wall the height they were on.
     *
     * The one setting on this page that removes something the user navigates with, so it
     * is worth saying what is left: Android's back gesture, its home gesture - which on a
     * launcher lands on Start - and the wall's own swipes, across to the app list and up
     * into its search. What goes with the keys is Cortana's hold, and she has a tile.
     *
     * Turning it on takes the colour switch below away with the keys: it paints a strip
     * that is no longer there, and a switch that visibly does nothing is worse than one
     * that is missing. Its stored answer is untouched, so the keys come back wearing
     * whatever they were. See [setScreenControls].
     */
    private val hideNavBarRow = SwitchRow("hide the navigation bar") { on ->
        // From here rather than on the host's way back, for the same reason the dim
        // slider appears with its own switch: the row it hides is directly below this
        // one and should go as the switch is thrown.
        accentNavBarRow.setVisible(!on)
        onHideNavBarChanged?.invoke(on)
    }

    /**
     * Hands the shell the whole display, with Android's status and navigation bars hidden.
     *
     * Separate from the switch that hides the keys and deliberately so: those keys are
     * this shell's own and this is the phone's chrome - the clock, the signal, the
     * battery, and the gesture bar at the foot of the screen. Either can be wanted without the other, and
     * both together is a wall with nothing on it but tiles.
     *
     * The bars come back on a swipe from the edge and leave again by themselves, which is
     * what Android does for anything running full screen.
     */
    private val fullscreenRow =
        SwitchRow("full screen") { on -> onFullscreenChanged?.invoke(on) }

    /**
     * The phone's status bar, and what a downward drag from the top of the wall opens.
     *
     * On, the shell wears a strip along the top - signal, carrier, battery, clock and date
     * - and pulling down on the wall brings the Action Center out of it, with its quick
     * actions, its two commands and its notifications grouped by the app that posted them.
     * Off, both go: the wall starts under Android's own status bar and the gesture asks the
     * system for its shade, which is what it did before either existed.
     *
     * One switch, because the strip is the panel's header and a bar left behind without it
     * would say the time and nothing else.
     *
     * A switch rather than a choice of two, because there is a default worth stating: the
     * gesture belongs to Windows Phone and this is what Windows Phone did with it. The
     * shade is not taken away by turning it on - it is where it always was, a swipe from
     * the status bar above. See `WP81Settings.getWP81ActionCenter`.
     */
    private val actionCenterRow =
        SwitchRow("status bar and action center") { on -> onActionCenterChanged?.invoke(on) }

    /**
     * Where the rest of the phone's links go.
     *
     * A command rather than a switch, because it is not this page's to set: Android asks
     * the user itself and can be told otherwise from its own settings at any time. So the
     * row says where things stand and opens the question - see [setDefaultBrowser].
     */
    private val defaultBrowserRow = ActionRow("default browser") { _ -> onDefaultBrowser?.invoke() }

    /**
     * The way through to the keyboard's settings.
     *
     * They are a page of their own - a whole Activity, styled like this one - and until
     * now the only ways to it were holding `&123` on the keyboard itself and Android's
     * list of input methods. Both mean already having the keyboard up, which is no use to
     * somebody who wants to turn its languages or its dictation on before they start
     * typing. The phone kept keyboard under settings, and so does this.
     */
    private val keyboardRow = ActionRow("keyboard", ROOT_LABEL_SP) { _ -> onKeyboard?.invoke() }

    /**
     * What this is, who wrote it, and what changed in it.
     *
     * Welcome, which is a program of its own with a panorama in it - so this is a row on
     * the list rather than a category, the way keyboard is. Until now the only way back
     * to it was the tile, and a tile is something a user can unpin: the release notes,
     * the permissions the launcher is asking for and the way around the shell all went
     * with it, with nothing left pointing at them. About is where a phone keeps those,
     * and it is where somebody looks for them.
     */
    /**
     * Android's own settings, which are the ones this page is not.
     *
     * Everything above this row is the launcher's: what Start looks like, what the tiles
     * do, where links open. None of it is the *phone's* - the volume, the network, the
     * clock, the permissions - and a shell that has taken over the home screen is a shell
     * that has taken away the usual way to those, which is the settings app's own icon in
     * a list somebody now reaches through this launcher.
     *
     * Named for what is behind it rather than "android settings", because the user's phone
     * is the subject and Android is an implementation detail of it. Windows Phone drew no
     * such line - its settings page held both, system and applications, under one heading -
     * so the nearest honest thing is to say which of the two this row hands over to.
     */
    private val phoneSettingsRow =
        ActionRow("phone settings", ROOT_LABEL_SP) { _ -> onPhoneSettings?.invoke() }
            .also { it.setDetail("android's own settings, outside the launcher") }

    private val aboutRow = ActionRow("about", ROOT_LABEL_SP) { _ -> onAbout?.invoke() }

    /**
     * Which icon pack is dressing the apps, and the way to change it.
     *
     * An [ActionRow] rather than a set of markers because the answers are not known until
     * the page is opened - they are whatever packs the phone happens to have - and a list
     * that is empty on most phones would be a section of settings saying nothing at all.
     * Its second line carries the answer, which is where this page puts every fact it has
     * read off the phone rather than out of its own preferences. See [setIconPack].
     */
    private val iconPackRow = ActionRow("icon pack") { onIconPack?.invoke(anchorYOf(it)) }

    /**
     * Whether the pack reaches Start, offered only once there is a pack to reach it.
     *
     * The wall is flat white glyphs on accent squares and a pack is full-colour artwork,
     * so this is a real choice about what Start looks like rather than a detail of the row
     * above - but it is meaningless without a pack, which is why it comes and goes with
     * one. See WP81Settings.getWP81IconPackOnTiles.
     */
    private val iconPackTilesRow =
        SwitchRow("use it on start tiles") { on -> onIconPackOnTilesChanged?.invoke(on) }

    /**
     * Whether swiping across to the app list arrives with the search box up.
     *
     * On, because that is what the swipe has always done here and it is the fast way to
     * open an app: the gesture and the typing are one movement. It is also the wrong
     * default for anybody who swipes across to *browse* - they get a keyboard over two
     * thirds of the list every time, and a rail of letters that has folded itself away.
     * Off, the list arrives as a list; search is still the key on the strip and the
     * button at the top of the rail. See WP81Shell.searchOnAppListOpen.
     */
    private val appListSearchRow =
        SwitchRow("focus the search box") { on -> onAppListSearchFocusChanged?.invoke(on) }

    /**
     * Whether the Photos tile plays the camera roll's clips as well as showing its stills.
     *
     * Filed under the program whose tile it is rather than under "tiles", which is the
     * wall's own settings - how many columns it has, what a notification looks like on it,
     * what the apps are wearing. This is a setting about what one program puts on its
     * tile, and the place to look for it is that program. See
     * WP81Settings.getWP81PhotoTileVideos.
     */
    private val photoTileVideosRow =
        SwitchRow("show videos on the live tile") { on -> onPhotoTileVideosChanged?.invoke(on) }

    /**
     * Backup and restore, as four rows on a page of their own.
     *
     * Everything this shell remembers is in its preference files - the Start screen and
     * the order of its tiles, the accent, the hand-picked icons, the keyboard, the news
     * feeds, the playlists - and until now the only copy of any of it was the one on the
     * phone. A launcher whose whole point is an arrangement the user built by hand should
     * not lose it to a wiped phone or a new one.
     *
     * Each row's second line is when that destination was last written to, which is the
     * question somebody actually opens this page with: not "can I back up" but "is what
     * is up there still worth anything". See [setBackupTimes].
     */
    private val backUpToDriveRow =
        ActionRow("back up to google drive") { _ -> onBackUpToDrive?.invoke() }

    private val backUpToFileRow =
        ActionRow("back up to a file") { _ -> onBackUpToFile?.invoke() }

    private val restoreFromDriveRow =
        ActionRow("restore from google drive") { _ -> onRestoreFromDrive?.invoke() }

    private val restoreFromFileRow =
        ActionRow("restore from a file") { _ -> onRestoreFromFile?.invoke() }

    private var selectedAccent: Int = palette.accent
    private var selectedDark: Boolean = palette.isDark
    private var selectedBackground: String? = null

    init {
        isClickable = true

        // -------------------------------------------------------------- the list
        rootColumn.orientation = LinearLayout.VERTICAL
        // A hair of air under the last thing on the page. Scrolled to the end, the last
        // row sat hard against the bottom edge, which reads as the page having been cut
        // off rather than finished.
        rootColumn.setPadding(0, 0, 0, dp(BOTTOM_GAP_DP))

        rootColumn.addView(header, wide())

        // -------------------------------------------------------------- theme
        // Dark or Light, and then how much of the screen this shell is drawing at all.
        // Both are questions about what the phone looks like rather than about the wall,
        // which is what everything under "tiles" and "wallpaper" is.
        val theme = categoryColumn(Category.THEME)
        theme.addView(sectionLabel("background"), wide())
        // Two mutually exclusive choices of one word each: a column of them wasted a
        // screenful of height on a decision that fits across one row.
        backgroundRow.orientation = LinearLayout.HORIZONTAL
        backgroundRow.addView(themeRow("Dark", true), share())
        backgroundRow.addView(themeRow("Light", false), share())
        theme.addView(backgroundRow, wide())

        // Whether there is a strip at all first, then what it looks like: the colour
        // switch belongs to the keys, so it reads as theirs when it sits under the switch
        // that decides whether they are there - and it is that switch that takes it away.
        theme.addView(sectionLabel("screen"), wide())
        theme.addView(hideNavBarRow.view, wide())
        theme.addView(accentNavBarRow.view, wide())
        theme.addView(fullscreenRow.view, wide())
        // What the wall's own pull-down opens. Filed with the other three because it is a
        // question of the same kind - which chrome the user gets, this shell's or the
        // phone's - rather than a question about notifications, which are the same
        // notifications either way.
        theme.addView(actionCenterRow.view, wide())

        // -------------------------------------------------------------- tiles
        val tiles = categoryColumn(Category.TILES)
        tiles.addView(sectionLabel("accent color"), wide())
        buildAccentGrid()
        tiles.addView(accentGrid, wide())

        // The word the answers share is said once, in the heading: at a share of the width
        // each there is no room to repeat "columns" beside every marker, and a row of one
        // word each would spend a screenful of height on one decision.
        tiles.addView(sectionLabel("tile columns"), wide())
        val columnsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (count in COLUMN_CHOICES) columnsRow.addView(columnsOption(count), share())
        tiles.addView(columnsRow, wide())

        // A pair rather than a switch. What a tile does with an unread notification is not
        // "numbers, or nothing": with the number off it still marks the tile, with a dot.
        // A checkbox called "notification numbers" left the other half of that unsaid, so
        // turning it off read as turning notifications off. Naming both answers says what
        // the wall will actually look like either way.
        tiles.addView(sectionLabel("notifications"), wide())
        val countsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        countsRow.addView(countsOption("numbers", true), share())
        countsRow.addView(countsOption("dots", false), share())
        tiles.addView(countsRow, wide())

        // The row opens the list of installed packs; the switch under it decides whether
        // the answer reaches Start as well as the app list.
        tiles.addView(sectionLabel("app icons"), wide())
        tiles.addView(iconPackRow.view, wide())
        tiles.addView(iconPackTilesRow.view, wide())

        // -------------------------------------------------------------- wallpaper
        val wallpaper = categoryColumn(Category.WALLPAPER)
        wallpaper.addView(sectionLabel("start background"), wide())
        wallpaperStrip.orientation = LinearLayout.HORIZONTAL
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(wallpaperStrip)
            setPadding(dp(22), 0, dp(22), dp(28))
            clipToPadding = false
        }
        wallpaper.addView(scroller, wide())
        // The one square of the strip that outlives a refill of it. See buildBrowseSquare.
        buildBrowseSquare()

        blurSlider.onValueChanged = { v -> onBlurChanged?.invoke(v) }
        wallpaper.addView(blurLabel, wide())
        wallpaper.addView(blurSlider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(22), 0, dp(22), dp(20))
        })

        // Both of these are the picture's own and stand or fall with it, so they start
        // off the page: the host hands the page its answers on the way in, and until it
        // has there is nothing for either switch to be reporting. See
        // [setBackgroundControls].
        driftRow.setVisible(false)
        wallpaper.addView(driftRow.view, wide())
        dimAllRow.setVisible(false)
        wallpaper.addView(dimAllRow.view, wide())
        dimSlider.onValueChanged = { v -> onDimAmountChanged?.invoke(v) }
        wallpaper.addView(dimLabel, wide())
        wallpaper.addView(dimSlider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(22), 0, dp(22), dp(20))
        })
        // Under the picture's own three, and unlike them always there: a wall of painted
        // tiles is worth putting back to the accent with no photograph behind it too.
        // See the row's own note for why it is on this page rather than under the accent.
        wallpaper.addView(hideColorsRow.view, wide())

        // -------------------------------------------------------------- links
        val browser = categoryColumn(Category.BROWSER)
        browser.addView(sectionLabel("links"), wide())
        browser.addView(openLinksRow.view, wide())
        browser.addView(defaultBrowserRow.view, wide())

        // -------------------------------------------------------------- files & photos
        val files = categoryColumn(Category.FILES)
        files.addView(sectionLabel("photos tile"), wide())
        files.addView(photoTileVideosRow.view, wide())

        // -------------------------------------------------------------- app list
        val appList = categoryColumn(Category.APP_LIST)
        appList.addView(sectionLabel("search"), wide())
        appList.addView(appListSearchRow.view, wide())

        // -------------------------------------------------------------- backup
        // Making a copy and putting one back, under two headings rather than four rows in
        // a row: the difference between them is the whole of what this page is about, and
        // the heading is what stops "restore from a file" being read as the fourth way of
        // saving one.
        val backup = categoryColumn(Category.BACKUP)
        backup.addView(sectionLabel("back up"), wide())
        backup.addView(backUpToDriveRow.view, wide())
        backup.addView(backUpToFileRow.view, wide())
        backup.addView(sectionLabel("restore"), wide())
        backup.addView(restoreFromDriveRow.view, wide())
        backup.addView(restoreFromFileRow.view, wide())
        // Seeded as never, so the rows read as rows rather than as blanks in the moment
        // before the host answers. It answers on the way into settings; see
        // MainActivity.refreshWP81BackupRows.
        setBackupTimes(0L, 0L)

        // The names, in the order the phone would have put them: what the shell looks
        // like, then what is on it, then the two programs that have settings of their
        // own, then the other page of the shell. Keyboard is a row rather than a category
        // because there is nothing here to show - the settings are an Activity of their
        // own, and this is the way to it.
        rootColumn.addView(categoryRow(Category.THEME).view, wide())
        rootColumn.addView(categoryRow(Category.TILES).view, wide())
        rootColumn.addView(categoryRow(Category.WALLPAPER).view, wide())
        rootColumn.addView(categoryRow(Category.BROWSER).view, wide())
        rootColumn.addView(categoryRow(Category.FILES).view, wide())
        rootColumn.addView(keyboardRow.view, wide())
        rootColumn.addView(categoryRow(Category.APP_LIST).view, wide())
        // Under the settings rather than among them: it is what to do about all of them
        // at once, and it is the row somebody looks for when they have a new phone.
        rootColumn.addView(categoryRow(Category.BACKUP).view, wide())
        // Then out of the launcher altogether. Below every setting this page can change,
        // because it changes none of them - it is the way to the other settings screen,
        // and putting it among the shell's own would read as one more of them.
        rootColumn.addView(phoneSettingsRow.view, wide())
        // Last, as it was on the phone: it is the one row that is not a setting at all.
        aboutRow.setDetail("tips & tricks, permissions, release notes")
        rootColumn.addView(aboutRow.view, wide())

        // "theme" here is dark against light, not a shell to switch to. This launcher is
        // the Windows Phone shell and has nothing to switch to - the desktop themes it
        // once listed ship as a separate app now, and offering one here could only lead
        // somewhere that is not installed.

        rootScroll.isFillViewport = true
        rootScroll.overScrollMode = OVER_SCROLL_NEVER
        rootScroll.addView(
            rootColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rootScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // -------------------------------------------------------------- the category page
        pageColumn.orientation = LinearLayout.VERTICAL
        pageColumn.setPadding(0, 0, 0, dp(BOTTOM_GAP_DP))
        pageHeader.onBack = { closeCategory() }
        pageColumn.addView(pageHeader, wide())
        pageBody.orientation = LinearLayout.VERTICAL
        pageColumn.addView(pageBody, wide())

        pageScroll.isFillViewport = true
        pageScroll.overScrollMode = OVER_SCROLL_NEVER
        pageScroll.visibility = GONE
        // Opaque and taking its own taps: it lies over the list rather than replacing it,
        // so that the list is simply there again as the page turns away - and nothing
        // through the gaps between rows should reach the list underneath.
        pageScroll.isClickable = true
        pageScroll.addView(
            pageColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pageScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Everything that is not a section heading is stood in from the edge, so the
        // headings are the only things on the page's own margin and each one visibly has a
        // group of settings hanging under it. Applied here, once, rather than at each of a
        // dozen call sites - and added to whatever margin a row already had rather than
        // replacing it, so the sliders and the wallpaper strip keep their own.
        for (col in categoryColumns.values) indentSettingRows(col)

        applyPalette(palette)
    }

    /**
     * The column a category's settings are built into, made the first time it is asked for.
     *
     * Detached until the category is entered - see [showCategory] - which is also why the
     * rows are held as fields rather than looked up: a setting the host seeds while its
     * category has never been opened has no parent to be found through.
     */
    private fun categoryColumn(category: Category): LinearLayout =
        categoryColumns.getOrPut(category) {
            LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        }

    /** One name on the list, with the line under it naming what is behind it. */
    private fun categoryRow(category: Category): ActionRow =
        categoryRows.getOrPut(category) {
            ActionRow(category.title, ROOT_LABEL_SP) { showCategory(category) }
                .also { it.setDetail(category.detail) }
        }

    /**
     * Turns to a category's page.
     *
     * The page lies over the list rather than replacing it, so backing out is the turn
     * away and the list is already behind it - with the scroll position the user left it
     * at, which is the thing a rebuilt list always loses.
     */
    private fun showCategory(category: Category) {
        shownCategory = category
        pageHeader.setTitle(category.title)
        pageBody.removeAllViews()
        pageBody.addView(categoryColumn(category), wide())
        pageScroll.scrollTo(0, 0)
        // Whatever was left open last time is folded away before it is shown again.
        if (category == Category.TILES) setAccentExpanded(false)
        pageTransition.playIn()
    }

    /** The way back to the list: the page turns away, and the list is under it. */
    private fun closeCategory() {
        if (shownCategory == null) return
        shownCategory = null
        // Emptied only once the turn is over, so the page has something to show while it
        // is leaving. A category entered during the turn re-fills it and MetroPageTransition
        // drops this, which is what stops the new page being emptied by the old one's exit.
        pageTransition.playOut { pageBody.removeAllViews() }
    }

    /**
     * Backs out of a category, and says whether there was one to back out of.
     *
     * False means the user is on the list, where back is the way out of settings
     * altogether - which is the host's to do. See WP81Shell.handleBack.
     */
    fun handleBack(): Boolean {
        if (shownCategory == null) return false
        closeCategory()
        return true
    }

    /**
     * The mark beside a setting, in the shape that says what kind of setting it is.
     *
     * [MetroMarker]'s, which is where it lives now that the Weather app and the News
     * reader draw their choices with it too - see that file for what round and square
     * each mean. Kept as a method here because a dozen call sites below say
     * `markerDrawable(round = ..., on = ...)` and none of them need to know where it
     * comes from.
     */
    private fun markerDrawable(round: Boolean, on: Boolean): Drawable =
        MetroMarker.drawable(context, palette, round, on)

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** One of an equal-width set sharing a row. */
    private fun share() = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        textSize = 12f
        setPadding(dp(24), dp(14), dp(24), dp(8))
        tag = TAG_SECTION
    }

    // ---------------------------------------------------------------- background

    private fun themeRow(label: String, dark: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Only the first of the pair carries the page's left margin; the second starts
            // where the row's own half begins.
            setPadding(if (dark) dp(24) else dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            setOnClickListener {
                selectedDark = dark
                repaintThemeRows()
                onDarkPicked?.invoke(dark)
            }
            TiltEffect.apply(this)
        }
        // One of a pair, so it is marked round. See [markerDrawable].
        val marker = View(context)
        row.addView(marker, LinearLayout.LayoutParams(dp(20), dp(20)))
        val text = TextView(context).apply {
            this.text = label
            textSize = 17f
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        }
        row.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        row.tag = marker
        themeRows.add(row to dark)
        return row
    }

    /** One of the widths, marked the way Dark and Light are. */
    private fun columnsOption(count: Int): View {
        val first = count == COLUMN_CHOICES.first()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (first) dp(24) else dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            setOnClickListener {
                selectedColumns = count
                repaintColumnRows()
                onColumnsPicked?.invoke(count)
            }
            TiltEffect.apply(this)
        }
        val marker = View(context)
        row.addView(marker, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(TextView(context).apply {
            text = count.toString()
            textSize = 17f
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        row.tag = marker
        columnRows.add(row to count)
        return row
    }

    /**
     * One of the two answers to what an unread tile shows, marked the way the widths are.
     *
     * Round markers, because these two are a set: choosing one unchooses the other. See
     * [markerDrawable].
     */
    private fun countsOption(label: String, counts: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (counts) dp(24) else dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            setOnClickListener {
                selectedCounts = counts
                repaintCountRows()
                onTileCountsChanged?.invoke(counts)
            }
            TiltEffect.apply(this)
        }
        val marker = View(context)
        row.addView(marker, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(TextView(context).apply {
            text = label
            textSize = 17f
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        row.tag = marker
        countRows.add(row to counts)
        return row
    }

    private fun repaintCountRows() {
        for ((row, counts) in countRows) {
            (row.tag as View).background =
                markerDrawable(round = true, on = counts == selectedCounts)
            ((row as LinearLayout).getChildAt(1) as TextView).setTextColor(palette.foreground)
        }
    }

    private fun repaintColumnRows() {
        for ((row, count) in columnRows) {
            (row.tag as View).background = markerDrawable(round = true, on = count == selectedColumns)
            ((row as LinearLayout).getChildAt(1) as TextView).setTextColor(palette.foreground)
        }
    }

    private fun repaintThemeRows() {
        for ((row, dark) in themeRows) {
            val marker = row.tag as View
            marker.background = markerDrawable(round = true, on = dark == selectedDark)
            ((row as LinearLayout).getChildAt(1) as TextView).setTextColor(palette.foreground)
        }
    }

    // ---------------------------------------------------------------- accent

    /**
     * The accents, five to a row, with everything past the first row rolled up.
     *
     * There are a few dozen of them: laid out in full they were the longest thing on the
     * page by a wide margin, and pushed the background and theme settings off the bottom
     * of a screen that has four sections in total.
     */
    private fun buildAccentGrid() {
        accentGrid.orientation = LinearLayout.VERTICAL
        accentGrid.setPadding(dp(20), 0, dp(20), dp(6))
        accentMore.orientation = LinearLayout.VERTICAL
        accentMore.visibility = View.GONE

        val perRow = ACCENTS_PER_ROW
        var row: LinearLayout? = null
        for ((i, entry) in rocks.gorjan.gokixp.wp81.WP81Settings.WP81_ACCENTS.withIndex()) {
            if (i % perRow == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                // The first row stays out; the rest wait behind the chevron.
                (if (i == 0) accentGrid else accentMore).addView(row, wide())
            }
            val (name, color) = entry
            val swatch = View(context).apply {
                setBackgroundColor(color)
                contentDescription = name
                isClickable = true
                setOnClickListener {
                    selectedAccent = color
                    repaintAccentSwatches()
                    onAccentPicked?.invoke(color)
                }
                TiltEffect.apply(this)
            }
            accentSwatches.add(swatch)
            row?.addView(swatch, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }

        accentMoreRow.orientation = LinearLayout.HORIZONTAL
        accentMoreRow.gravity = Gravity.CENTER_VERTICAL
        accentMoreRow.setPadding(dp(4), dp(10), dp(4), dp(10))
        accentMoreRow.isClickable = true
        accentMoreRow.setOnClickListener { setAccentExpanded(!accentExpanded) }
        TiltEffect.apply(accentMoreRow)

        accentMoreLabel.text = "more colors"
        accentMoreLabel.textSize = 15f
        accentMoreLabel.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        accentMoreRow.addView(accentMoreLabel, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        accentChevron.setImageResource(R.drawable.wp81_edit_resize)
        accentMoreRow.addView(accentChevron, LinearLayout.LayoutParams(dp(18), dp(18)))

        accentGrid.addView(accentMoreRow, wide())
        accentGrid.addView(accentMore, wide())
    }

    /**
     * Called each time the page is shown.
     *
     * On the list, at the top of it, with no category open. Settings is entered from the
     * key strip and from a tile, and both mean "take me to settings" rather than "take me
     * back to where I was in settings" - so whatever page was being looked at last time is
     * dropped rather than restored. Cut rather than turned away: nothing is on screen yet,
     * and an exit animation for a page the user cannot see is a page arriving in the
     * middle of somebody else's.
     *
     * The accent grid starts closed. It used to open itself whenever the colour in use was
     * not in the first row, which is most of them - so its page opened as a wall of
     * swatches with the settings under it pushed off the bottom, every time. What is on is
     * still marked; finding it is a tap on "more colors", and that is the tap the person
     * who wants to change it was going to make anyway.
     */
    fun onOpened() {
        setAccentExpanded(false)
        shownCategory = null
        pageScroll.animate().cancel()
        pageScroll.visibility = GONE
        pageScroll.alpha = 1f
        pageScroll.rotationY = 0f
        pageBody.removeAllViews()
        rootScroll.scrollTo(0, 0)
    }

    private fun setAccentExpanded(expanded: Boolean) {
        accentExpanded = expanded
        accentMore.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) accentChevron.animate().rotation(180f).setDuration(160).start()
        else accentChevron.rotation = 0f
    }

    private fun repaintAccentSwatches() {
        for ((i, swatch) in accentSwatches.withIndex()) {
            val selected =
                rocks.gorjan.gokixp.wp81.WP81Settings.WP81_ACCENTS[i].second == selectedAccent
            // The active accent stands full size; the rest sit back a little.
            swatch.scaleX = if (selected) 1f else 0.78f
            swatch.scaleY = if (selected) 1f else 0.78f
        }
    }

    // ---------------------------------------------------------------- start background

    /**
     * Fills the wallpaper strip. Called from the host once the drawables have been decoded
     * off the main thread - there are a few dozen and decoding them inline stutters.
     */
    fun setWallpapers(items: List<Pair<String, Drawable>>, current: String?) {
        selectedBackground = current
        wallpaperStrip.removeAllViews()
        wallpaperTiles.clear()

        wallpaperStrip.addView(wallpaperTile(null, null, "none"))
        // Browse sits right after "none", before the bundled set.
        wallpaperStrip.addView(browseSquare)
        // The one that is on leads the set. It is the answer to the question the strip is
        // asking - which of these is Start wearing - and a few dozen squares in, it was an
        // answer the user had to go looking for. Ordered here rather than as the strip is
        // tapped: moving a square out from under the finger that just chose it would be the
        // page rearranging itself as a reward for using it.
        val ordered = items.sortedBy { (path, _) -> if (path == current) 0 else 1 }
        for ((path, drawable) in ordered) {
            wallpaperStrip.addView(wallpaperTile(path, drawable, null))
        }
        repaintWallpaperTiles()
    }

    /**
     * One square of the wallpaper strip: a photo, "none", or "browse".
     *
     * Rests at the size its state calls for rather than at full size. The strip stands the
     * chosen wallpaper out by leaving it whole and standing every other square back a
     * little, and a press has to spring back to *that* - see [TiltEffect.Target]. Browse
     * was the square that never learnt it: it was not one of the wallpapers, so nothing
     * ever stood it back, and it sat visibly larger than the row it is part of.
     */
    private inner class StripTile(context: Context) : FrameLayout(context), TiltEffect.Target {

        private var resting = UNSELECTED_SCALE

        /**
         * Moves the square to the size its state now calls for.
         *
         * Springs rather than sets, once the strip is on screen. A tap is handled after
         * the finger has already lifted, so [TiltEffect] has read the old resting scale
         * and started springing the square back to it - a scale set from under that
         * animation is overwritten a frame later, and the chosen wallpaper only grew on
         * the *second* tap, once the spring already had the new size to aim at. Re-aiming
         * the spring is what makes the first tap show.
         *
         * Before the first layout there is no animation to fight and nothing to see, so
         * the strip fills at its resting sizes rather than growing into them.
         */
        fun restAt(scale: Float) {
            if (scale == resting && scaleX == scale) return
            resting = scale
            if (isLaidOut) {
                TiltEffect.settle(this)
            } else {
                scaleX = scale
                scaleY = scale
            }
        }

        override fun restingScale(): Float = resting
    }

    /** Assembles the browse square, once. What it shows is [repaintBrowseTile]'s. */
    private fun buildBrowseSquare() {
        browseImage.scaleType = ImageView.ScaleType.CENTER_CROP
        browseSquare.addView(
            browseImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        browseLabel.text = "browse"
        browseLabel.gravity = Gravity.CENTER
        browseLabel.textSize = 13f
        browseLabel.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
        browseSquare.addView(
            browseLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        browseSquare.isClickable = true
        browseSquare.setOnClickListener { onBrowse?.invoke() }
        // Wearing the picked picture it is a wallpaper like any other in the strip, so a
        // hold asks the same question of it: the phone's own walls, offered on a command
        // list. Bare it is a way of choosing rather than a choice, and a hold on it has
        // nothing to be about - answered false, so nothing ticks and no list opens.
        browseSquare.setOnLongClickListener {
            val picture = customBackground?.takeIf { wearingCustomBackground }
                ?: return@setOnLongClickListener false
            browseSquare.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            onWallpaperLongPress?.invoke(picture, anchorYOf(browseSquare))
            true
        }
        TiltEffect.apply(browseSquare)
        browseSquare.layoutParams =
            LinearLayout.LayoutParams(dp(72), dp(120)).apply { marginEnd = dp(8) }
        repaintBrowseTile()
    }

    /**
     * Says which wallpaper Start is now wearing, without refilling the strip.
     *
     * A pick made through the browse square lands on the theme rather than on this page:
     * no square was tapped, so the row went on standing the old choice out and the browse
     * square went on believing it was bare. The picture only appeared once settings was
     * closed and reopened and [setWallpapers] filled the strip afresh. Told the answer,
     * the row moves the selection where it belongs there and then.
     */
    fun setSelectedBackground(path: String?) {
        if (path == selectedBackground) return
        selectedBackground = path
        repaintWallpaperTiles()
    }

    /**
     * Hands the page the picture the user browsed for, and where it is kept.
     *
     * [preview] is a thumbnail of it, decoded by the host off the main thread the way the
     * bundled ones are. Null [path] means there has never been one. Whether it is actually
     * on show is a separate question - see [repaintBrowseTile].
     */
    fun setCustomBackground(path: String?, preview: Drawable?) {
        customBackground = path
        customPreview = preview
        repaintBrowseTile()
    }

    /**
     * Dresses the browse square in the picked picture, for as long as Start is wearing it.
     *
     * Only while it is the chosen background: the file stays on disk after the user moves
     * to a bundled wallpaper, and a square still showing it would be claiming the wall is
     * wearing something it is not. Wearing the picture it is the chosen square, so it
     * stands at full size like any other chosen one, and the word goes over a wash so it
     * can still be read off a photograph.
     */
    private fun repaintBrowseTile() {
        val worn = if (wearingCustomBackground) customPreview else null
        browseImage.setImageDrawable(worn)
        browseImage.visibility = if (worn != null) VISIBLE else GONE
        browseLabel.setBackgroundColor(
            if (worn != null) Color.argb((255 * BROWSE_SCRIM_ALPHA).toInt(), 0, 0, 0)
            else Color.TRANSPARENT
        )
        browseLabel.setTextColor(if (worn != null) Color.WHITE else palette.foreground)
        browseSquare.setBackgroundColor(palette.inactive)
        browseSquare.restAt(if (worn != null) 1f else UNSELECTED_SCALE)
    }

    /**
     * Offers the background's own controls, for a photo that is actually set.
     *
     * All of them, the tile-colour switch included: with no photo behind the wall there is
     * nothing for a painted tile to be in the way of, and the switch would be an offer to
     * throw away colours for no gain at all.
     */
    /** Seeds the link switch. */
    fun setOpenLinksInIe(on: Boolean) {
        openLinksRow.set(on)
    }

    /** Seeds the switch for how the app list opens. */
    fun setAppListSearchFocus(on: Boolean) {
        appListSearchRow.set(on)
    }

    /** Seeds the switch for what the Photos tile turns through. */
    fun setPhotoTileVideos(on: Boolean) {
        photoTileVideosRow.set(on)
    }

    /** Seeds the navigation bar switch. */
    fun setAccentNavBar(on: Boolean) {
        accentNavBarRow.set(on)
    }

    /**
     * Seeds the two switches that decide how much of the screen the shell is given.
     *
     * And puts the colour switch where [hideNavBarRow] would have put it, so a page built
     * with the keys already hidden opens without it rather than showing it until the
     * switch above it is touched.
     */
    fun setScreenControls(hideNavBar: Boolean, fullscreen: Boolean) {
        hideNavBarRow.set(hideNavBar)
        fullscreenRow.set(fullscreen)
        accentNavBarRow.setVisible(!hideNavBar)
    }

    /** Seeds the switch for what the pull-down opens. */
    fun setActionCenter(on: Boolean) {
        actionCenterRow.set(on)
    }

    /** Says whether the phone is sending its links here, in the row's second line. */
    fun setDefaultBrowser(held: Boolean) {
        defaultBrowserRow.setDetail(
            if (held) "links open in internet explorer" else "links open somewhere else"
        )
    }

    /**
     * Says whether the phone's keyboard is this one, in the row's second line.
     *
     * The row goes to the keyboard's settings either way: they are worth setting before it
     * is switched on, and the page says how to switch it on. This is only so that somebody
     * who has never got as far as Android's input method list can see that they have not.
     */
    fun setKeyboardEnabled(on: Boolean) {
        keyboardRow.setDetail(
            if (on) "languages, suggestions, dictation"
            else "not switched on in android's keyboard list yet"
        )
    }

    /**
     * Says which pack is on, in the row's second line, and offers the Start switch with it.
     *
     * [installed] is how many the phone has, and it is asked for so that a phone with none
     * says so rather than offering a row that opens an empty list. That is the one case
     * where the row is worth reading even though nothing is set: it is where somebody who
     * has never heard of an icon pack finds out that they are a thing to go and get.
     */
    fun setIconPack(name: String?, onTiles: Boolean, installed: Int) {
        iconPackRow.setDetail(
            when {
                name != null -> name
                installed > 0 -> "using each app's own icons"
                else -> "none installed - tap to find some"
            }
        )
        iconPackTilesRow.set(onTiles)
        iconPackTilesRow.setVisible(name != null)
    }

    /**
     * Says when each destination was last written to, in the two backup rows' second lines.
     *
     * Zero is a destination that has never been used, and it is said as that rather than
     * left blank: a row with nothing under it reads as a row that has not loaded yet.
     *
     * The restore rows carry no date. What is on the phone's Drive may have been put there
     * by another phone on the same account, and the file the user is about to pick is not
     * known until they pick it - so both say what restoring *does* instead, which is the
     * thing worth reading before tapping either of them. The date of the backup that was
     * actually found is put in front of the user by the host, in the prompt that asks
     * before anything is replaced.
     */
    fun setBackupTimes(drive: Long, file: Long) {
        backUpToDriveRow.setDetail(lastBackup(drive))
        backUpToFileRow.setDetail(lastBackup(file))
        restoreFromDriveRow.setDetail("replaces everything on this phone")
        restoreFromFileRow.setDetail("replaces everything on this phone")
    }

    private fun lastBackup(at: Long): String {
        if (at <= 0L) return "not backed up here yet"
        val stamp = android.text.format.DateUtils.formatDateTime(
            context,
            at,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_SHOW_TIME or
                android.text.format.DateUtils.FORMAT_ABBREV_ALL
        )
        return "last backed up $stamp"
    }

    /** Seeds the tile settings, which are not tied to whether a background is set. */
    fun setTileControls(counts: Boolean, columns: Int) {
        selectedCounts = counts
        selectedColumns = columns
        repaintCountRows()
        repaintColumnRows()
    }

    fun setBackgroundControls(
        hasBackground: Boolean,
        blur: Float,
        drift: Boolean,
        hideTileColors: Boolean,
        dimAllTiles: Boolean,
        dimAmount: Float
    ) {
        hasStartBackground = hasBackground
        blurLabel.visibility = if (hasBackground) VISIBLE else GONE
        blurSlider.visibility = if (hasBackground) VISIBLE else GONE
        blurSlider.value = blur
        driftRow.setVisible(hasBackground)
        driftRow.set(drift)
        hideColorsRow.set(hideTileColors)
        // One of the background's own, unlike the switch above it: with no photograph
        // behind the wall there is nothing showing through a tile to be darkened.
        dimAllRow.setVisible(hasBackground)
        dimAllRow.set(dimAllTiles)
        dimSlider.value = dimAmount
        applyDimSliderVisibility()
    }

    /** The strength is on the page only while there is a picture and the switch is on. */
    private fun applyDimSliderVisibility() {
        val show = hasStartBackground && dimAllRow.isOn()
        dimLabel.visibility = if (show) VISIBLE else GONE
        dimSlider.visibility = if (show) VISIBLE else GONE
    }

    /**
     * A row that does something when it is tapped, with a line under it saying where things
     * stand.
     *
     * The shape WP8.1 used for anything it could not answer itself - a setting that lives
     * somewhere else, or one the system has to be asked for. It has no marker, because
     * there is nothing here that is on or off.
     */
    private inner class ActionRow(
        text: String,
        labelSp: Float = ROW_LABEL_SP,
        private val onTap: (View) -> Unit
    ) {

        val view = LinearLayout(context)
        private val label = TextView(context)
        private val detail = TextView(context)

        init {
            view.orientation = LinearLayout.VERTICAL
            // A row on the list is given more air than one on a page: there it is one
            // setting among a group under a heading, and here it is the whole of what the
            // screen is offering, with nothing to be read as belonging to.
            val big = labelSp >= ROOT_LABEL_SP
            view.setPadding(dp(24), dp(if (big) 10 else 6), dp(24), dp(if (big) 22 else 18))
            view.isClickable = true
            view.setOnClickListener { onTap(view) }
            TiltEffect.apply(view)

            label.text = text
            label.textSize = labelSp
            label.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            view.addView(label, wide())

            detail.textSize = 13f
            detail.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            view.addView(detail, wide())

            repaint()
        }

        fun setDetail(text: String) {
            detail.text = text
        }

        fun repaint() {
            label.setTextColor(palette.foreground)
            detail.setTextColor(palette.foregroundSubtle)
        }
    }

    /**
     * A setting that is simply on or off, with the phone's own switch beside it.
     *
     * The one shape for every on/off setting here. There were two of them for a while -
     * this and a ticked square - split on a distinction the phone itself made, a square
     * being one of a set and a switch a thing that is running or is not. But none of the
     * squares on this page was ever one of a set: the drift, the dim and the tile colours
     * each stood alone, so what the shape actually said was "this setting is a lesser
     * kind of setting", which is not a thing any of them is. The sets that really are
     * sets - Dark against Light, the column counts, numbers against dots - carry the
     * round marker, which is the shape that means one of these.
     *
     * The whole row answers a tap, not just the switch: the control is a 46dp rectangle
     * at the far edge of the screen, and a row whose label does nothing makes the user
     * aim for it.
     */
    private inner class SwitchRow(text: String, private val onChanged: (Boolean) -> Unit) {

        val view = LinearLayout(context)
        private val label = TextView(context)
        private val toggle = MetroToggle(context, palette)

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            view.setPadding(dp(24), dp(6), dp(24), dp(18))
            view.isClickable = true
            view.setOnClickListener {
                // Through the switch rather than around it, so the bar slides for a tap on
                // the label exactly as it does for one on the switch itself - the tick
                // included, which the switch fires for itself and the row has to ask for.
                Haptics.tap(it)
                toggle.set(!toggle.isOn(), animated = true)
                onChanged(toggle.isOn())
            }
            TiltEffect.apply(view)

            label.text = text
            label.textSize = 17f
            label.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            view.addView(label, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            toggle.onChanged = { on -> onChanged(on) }
            view.addView(toggle, LinearLayout.LayoutParams(
                dp(MetroToggle.TRACK_W_DP), dp(MetroToggle.TRACK_H_DP)).apply {
                marginStart = dp(14)
            })

            repaint()
        }

        /** Shows where the setting stands, without reporting it back as a change. */
        fun set(value: Boolean) {
            toggle.set(value, animated = false)
        }

        /** Where the setting stands, for a row that has a detail of its own to show. */
        fun isOn(): Boolean = toggle.isOn()

        /** Takes the row off the page, for a setting that has stopped meaning anything. */
        fun setVisible(visible: Boolean) {
            view.visibility = if (visible) VISIBLE else GONE
        }

        fun repaint() {
            label.setTextColor(palette.foreground)
            toggle.applyPalette(palette)
        }
    }

    private fun wallpaperTile(path: String?, drawable: Drawable?, label: String?): View {
        val frame = StripTile(context).apply {
            isClickable = true
            setOnClickListener {
                selectedBackground = path
                repaintWallpaperTiles()
                onBackgroundPicked?.invoke(path)
            }
            // "none" is the absence of a wallpaper, so there is nothing to hold it for.
            if (path != null) setOnLongClickListener {
                performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
                onWallpaperLongPress?.invoke(path, anchorYOf(this))
                true
            }
            TiltEffect.apply(this)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(120)).apply {
                marginEnd = dp(8)
            }
        }
        if (drawable != null) {
            frame.addView(ImageView(context).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            frame.addView(TextView(context).apply {
                text = label.orEmpty()
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
                setTextColor(Color.WHITE)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        wallpaperTiles.add(frame to path)
        return frame
    }

    /** The bottom edge of [view], in this page's own coordinates. */
    private fun anchorYOf(view: View): Float {
        val viewLoc = IntArray(2)
        val selfLoc = IntArray(2)
        view.getLocationInWindow(viewLoc)
        getLocationInWindow(selfLoc)
        return (viewLoc[1] - selfLoc[1] + view.height).toFloat()
    }

    private fun repaintWallpaperTiles() {
        for ((tile, path) in wallpaperTiles) {
            val selected = path == selectedBackground
            tile.restAt(if (selected) 1f else UNSELECTED_SCALE)
            if (path == null) {
                tile.setBackgroundColor(if (selected) palette.accent else palette.inactive)
            }
        }
        // Whether the browse square is wearing the picked picture is the same question,
        // asked of a square that is not in the list: choosing any of these takes it off.
        repaintBrowseTile()
    }

    // ---------------------------------------------------------------- appearance

    fun applyPalette(p: WP81Palette) {
        palette = p
        selectedAccent = p.accent
        selectedDark = p.isDark
        setBackgroundColor(p.background)
        header.setTextColor(p.foreground)
        pageHeader.applyPalette(p)
        // The category page lies over the list, so it paints its own ground rather than
        // letting the rows underneath show between its own.
        pageScroll.setBackgroundColor(p.background)
        for (col in categoryColumns.values) {
            for (i in 0 until col.childCount) {
                val child = col.getChildAt(i)
                if (child is TextView && child.tag == TAG_SECTION) {
                    child.setTextColor(p.accent)
                }
            }
        }
        for (row in categoryRows.values) row.repaint()
        appListSearchRow.repaint()
        photoTileVideosRow.repaint()
        blurLabel.setTextColor(p.accent)
        dimLabel.setTextColor(p.accent)
        accentMoreLabel.setTextColor(p.foreground)
        accentChevron.imageTintList =
            android.content.res.ColorStateList.valueOf(p.foreground)
        driftRow.repaint()
        hideColorsRow.repaint()
        dimAllRow.repaint()
        openLinksRow.repaint()
        accentNavBarRow.repaint()
        hideNavBarRow.repaint()
        fullscreenRow.repaint()
        actionCenterRow.repaint()
        defaultBrowserRow.repaint()
        keyboardRow.repaint()
        aboutRow.repaint()
        iconPackRow.repaint()
        iconPackTilesRow.repaint()
        repaintCountRows()
        repaintColumnRows()
        blurSlider.applyPalette(p)
        dimSlider.applyPalette(p)
        repaintThemeRows()
        repaintAccentSwatches()
        repaintWallpaperTiles()
    }

    /**
     * Stands every setting in from the page's left edge, leaving the headings on it.
     *
     * A category page was one flat column: a heading and the rows under it began at the
     * same margin, so "tile columns" and "start background" read as two more rows rather
     * than as the names of what followed. An indent is enough to show which is which - the
     * headings hang out to the left, and each group is visibly a group.
     *
     * The list itself is not indented. Its rows have no headings to hang under, and are
     * the page's own margin.
     */
    private fun indentSettingRows(column: LinearLayout) {
        val inset = dp(SECTION_INSET_DP)
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            // Headings mark the edge; everything else stands in from it.
            if (child.tag == TAG_SECTION) continue
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.leftMargin += inset
            child.layoutParams = lp
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * The groups settings is split into, each a page of its own.
     *
     * Named as the phone would name them, in lower case, because that is how the title at
     * the top of the page is drawn and the row on the list should be the same words.
     *
     * [detail] is the line under the name on the list, and it says what is behind the row
     * rather than where any of it stands. A category is not a setting and has no state to
     * report - "dark" under "theme" answers a question nobody is asking, and says nothing
     * to the person who came looking for full screen. Naming the contents is what makes
     * the list navigable, and it is what the phone's own settings list did.
     *
     * Keyboard is not one of these. What is behind it is an Activity, not a column of
     * rows, so it is a row on the list that opens it directly - see [keyboardRow].
     */
    private enum class Category(val title: String, val detail: String) {
        THEME("theme", "dark or light, status bar, navigation bar"),
        TILES("tiles", "accent color, columns, notifications, icons"),
        WALLPAPER("wallpaper", "picture, blur, dim, drift, tile colors"),
        BROWSER("internet explorer", "where links open"),
        FILES("files & photos", "what the photos tile shows"),
        APP_LIST("app list", "opening into search"),
        BACKUP("backup", "keep a copy of your settings, and put one back")
    }

    companion object {
        private const val TAG_SECTION = "wp81_section"

        /** A setting's name, on a category page. */
        private const val ROW_LABEL_SP = 17f

        /**
         * A category's name, on the list.
         *
         * Larger than a setting's, because the list is the page's whole content and its
         * rows are the headings of everything behind them - see the phone's own settings,
         * where the name is type and the line under it is a footnote to it.
         */
        private const val ROOT_LABEL_SP = 24f

        /**
         * The page's own name, at the size the panorama gives a program's.
         *
         * Taken from MetroPanorama.APP_TITLE_SP rather than shared with it: that one is a
         * measurement of the panorama's slowest layer, and this page has no panorama. They
         * are the same number because they are the same thing on screen - the name of
         * where you are - and the platform wrote it at one size.
         */
        private const val APP_TITLE_SP = 69f
        private const val APP_TITLE_TOP_DP = 14
        private const val APP_TITLE_BOTTOM_DP = 6

        /**
         * The widths the wall can be set to, narrowest first.
         *
         * Four is WP8.1's own; three is a wall of bigger tiles, and six is what the phone
         * itself offered on its larger screens under the name "show more tiles". Five is
         * neither and is here because the step from four to six is a big one on a tall
         * screen - it halves the width of a medium tile - and an odd count is no harder
         * for the packer, which reads the number rather than a list of arrangements.
         *
         * The row is built from this list, so the first entry is the one that carries the
         * page's left margin. See TileGridLayout.columns.
         */
        private val COLUMN_CHOICES = listOf(3, 4, 5, 6)

        /** How far a setting stands in from the heading above it. See indentSettingRows. */
        private const val SECTION_INSET_DP = 12

        /** Swatches to a row, and so also how many stay out when the rest roll up. */
        private const val ACCENTS_PER_ROW = 5

        /** Air under the foot of the page, so the last setting is not on the edge. */
        private const val BOTTOM_GAP_DP = 20

        /** How far back a square of the strip stands while it is not the chosen one. */
        private const val UNSELECTED_SCALE = 0.88f

        /** How dark the ground under the word "browse" is, once it is over a photograph. */
        private const val BROWSE_SCRIM_ALPHA = 0.45f
    }
}
