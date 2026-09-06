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
 * exist on a phone. This page carries only what this shell can actually change: the Start
 * background image, the accent colour, and Light versus Dark.
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
     * Held on one of the bundled wallpapers.
     *
     * Carries the image and the bottom edge of the tile in this view's coordinates, so the
     * host can hang a command list off it. A tap on a wallpaper is what Start is wearing;
     * a hold is what the phone is wearing, which is a different question and belongs on a
     * command list rather than on a tile of its own.
     */
    var onWallpaperLongPress: ((String, Float) -> Unit)? = null

    /** Fired while the blur slider moves, 0 (sharp) to 1. */
    var onBlurChanged: ((Float) -> Unit)? = null

    /** Fired when the drift checkbox is toggled. */
    var onDriftChanged: ((Boolean) -> Unit)? = null

    /** Fired when the hide-tile-colours checkbox is toggled. */
    var onHideTileColorsChanged: ((Boolean) -> Unit)? = null

    /** Fired when the tiles are set to count their notifications, or only to mark them. */
    var onTileCountsChanged: ((Boolean) -> Unit)? = null

    /** Fired when the wall is set to a different number of columns. */
    var onColumnsPicked: ((Int) -> Unit)? = null

    /** Fired when the switch for following links in Internet Explorer is toggled. */
    var onOpenLinksInIeChanged: ((Boolean) -> Unit)? = null

    /** Tapping the row that asks the phone to send its links to the launcher. */
    var onDefaultBrowser: (() -> Unit)? = null

    /** Tapping the row that asks the phone for its app history. */

    /** Tapping the back arrow beside the title. */
    var onBack: (() -> Unit)? = null

    private val column = LinearLayout(context)
    private val header = MetroPageHeader(context, palette)
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
    private val blurLabel = TextView(context).apply {
        text = "blur"
        typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
        textSize = 12f
        setPadding(dp(24), dp(6), dp(24), dp(2))
        visibility = GONE
    }
    private val blurSlider = MetroSlider(context).apply { visibility = GONE }

    private val driftRow = CheckRow("drift wallpaper") { on -> onDriftChanged?.invoke(on) }

    /**
     * Puts every tile the user has painted back to the accent, for as long as it is on.
     *
     * A painted tile is a solid block - that is the whole point of painting one - and a
     * solid block is a hole in the photograph behind the wall. Turning them off is not the
     * same as unpainting them: the colours are kept, and the switch is here, beside the
     * picture it is in the way of, rather than in the tile menu where undoing it would
     * mean visiting every tile that had one.
     *
     * Always offered, unlike the background's own switches: a wall of painted tiles is
     * worth putting back to the accent on a plain Start screen too, and a switch that
     * appears only once a picture is set is one the user has no way of finding.
     */
    private val hideColorsRow =
        CheckRow("hide custom tile colors") { on -> onHideTileColorsChanged?.invoke(on) }

    /**
     * Whether a link opens here or in the phone's own browser.
     *
     * The same setting the desktop themes keep in Display Properties, and the same stored
     * answer - a link from a tile, a news story or the update goes to Internet Explorer
     * under both shells or under neither. It is only offered there, which under this theme
     * is a page the user has no way of reaching.
     */
    private val openLinksRow =
        CheckRow("open links in internet explorer") { on -> onOpenLinksInIeChanged?.invoke(on) }

    /**
     * Where the rest of the phone's links go.
     *
     * A command rather than a switch, because it is not this page's to set: Android asks
     * the user itself and can be told otherwise from its own settings at any time. So the
     * row says where things stand and opens the question - see [setDefaultBrowser].
     */
    private val defaultBrowserRow = ActionRow("default browser") { onDefaultBrowser?.invoke() }

    private var selectedAccent: Int = palette.accent
    private var selectedDark: Boolean = palette.isDark
    private var selectedBackground: String? = null

    /** The page's own scroller, so the theme picker can bring itself into view. */
    private val scroll = ScrollView(context)

    init {
        isClickable = true
        column.orientation = LinearLayout.VERTICAL
        // A hair of air under the last thing on the page. Scrolled to the end, the theme
        // picker sat hard against the bottom edge, which reads as the page having been cut
        // off rather than finished.
        column.setPadding(0, 0, 0, dp(BOTTOM_GAP_DP))

        header.setTitle("settings")
        header.onBack = { onBack?.invoke() }
        column.addView(header, wide())

        column.addView(sectionLabel("background"), wide())
        // Two mutually exclusive choices of one word each: a column of them wasted a
        // screenful of height on a decision that fits across one row.
        backgroundRow.orientation = LinearLayout.HORIZONTAL
        backgroundRow.addView(themeRow("Dark", true), share())
        backgroundRow.addView(themeRow("Light", false), share())
        column.addView(backgroundRow, wide())

        column.addView(sectionLabel("accent color"), wide())
        buildAccentGrid()
        column.addView(accentGrid, wide())

        // The word the three answers share is said once, in the heading: at a third of the
        // width each there is no room to repeat "columns" beside every marker, and three
        // rows of one word would spend a screenful of height on one decision.
        column.addView(sectionLabel("tile columns"), wide())
        val columnsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (count in COLUMN_CHOICES) columnsRow.addView(columnsOption(count), share())
        column.addView(columnsRow, wide())

        // A section of its own, and a pair rather than a switch. What a tile does with an
        // unread notification is not "numbers, or nothing": with the number off it still
        // marks the tile, with a dot. A checkbox called "notification numbers" left the
        // other half of that unsaid, so turning it off read as turning notifications off.
        // Naming both answers says what the wall will actually look like either way.
        column.addView(sectionLabel("notifications"), wide())
        val countsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        countsRow.addView(countsOption("numbers", true), share())
        countsRow.addView(countsOption("dots", false), share())
        column.addView(countsRow, wide())

        column.addView(sectionLabel("start background"), wide())
        wallpaperStrip.orientation = LinearLayout.HORIZONTAL
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(wallpaperStrip)
            setPadding(dp(22), 0, dp(22), dp(28))
            clipToPadding = false
        }
        column.addView(scroller, wide())

        blurSlider.onValueChanged = { v -> onBlurChanged?.invoke(v) }
        column.addView(blurLabel, wide())
        column.addView(blurSlider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(22), 0, dp(22), dp(20))
        })

        column.addView(driftRow.view, wide())
        hideColorsRow.setVisible(true)
        column.addView(hideColorsRow.view, wide())


        // Its own section: it is the only thing on this page that is not about what Start
        // looks like, and filing it under the wallpaper's switches would bury it.
        column.addView(sectionLabel("links"), wide())
        openLinksRow.setVisible(true)
        column.addView(openLinksRow.view, wide())
        column.addView(defaultBrowserRow.view, wide())

        // There is no "theme" section. This launcher is the Windows Phone shell and has
        // nothing to switch to - the desktop themes it once listed ship as a separate app
        // now, and picking one here could only lead somewhere that is not installed.

        scroll.isFillViewport = true
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 2. Everything that is not a section heading is stood in from the edge, so the
        // headings are the only things on the page's own margin and each one visibly has a
        // group of settings hanging under it. Applied here, once, rather than at each of a
        // dozen call sites - and added to whatever margin a row already had rather than
        // replacing it, so the slider and the wallpaper strip keep their own.
        indentSettingRows()

        applyPalette(palette)
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
     * Both lists start closed. The accent grid used to open itself whenever the colour in
     * use was not in the first row, which is most of them - so the page opened as a wall of
     * swatches with the settings under it pushed off the bottom, every time. What is on is
     * still marked; finding it is a tap on "more colors", and that is the tap the person
     * who wants to change it was going to make anyway.
     */
    fun onOpened() {
        setAccentExpanded(false)
        scroll.scrollTo(0, 0)
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
        wallpaperStrip.addView(browseTile())
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

    private fun browseTile(): View = StripTile(context).apply {
        isClickable = true
        setBackgroundColor(palette.inactive)
        // Never the chosen one - it is a way of choosing, not a choice - so it stands back
        // with the rest of the unchosen squares.
        restAt(UNSELECTED_SCALE)
        addView(TextView(context).apply {
            text = "browse"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            setTextColor(palette.foreground)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { onBrowse?.invoke() }
        TiltEffect.apply(this)
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(120)).apply { marginEnd = dp(8) }
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

    /** Says whether the phone is sending its links here, in the row's second line. */
    fun setDefaultBrowser(held: Boolean) {
        defaultBrowserRow.setDetail(
            if (held) "links open in internet explorer" else "links open somewhere else"
        )
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
        hideTileColors: Boolean
    ) {
        blurLabel.visibility = if (hasBackground) VISIBLE else GONE
        blurSlider.visibility = if (hasBackground) VISIBLE else GONE
        blurSlider.value = blur
        driftRow.setVisible(hasBackground)
        driftRow.set(drift)
        hideColorsRow.set(hideTileColors)
    }

    /**
     * A row that does something when it is tapped, with a line under it saying where things
     * stand.
     *
     * The shape WP8.1 used for anything it could not answer itself - a setting that lives
     * somewhere else, or one the system has to be asked for. It has no marker, because
     * there is nothing here that is on or off.
     */
    private inner class ActionRow(text: String, private val onTap: () -> Unit) {

        val view = LinearLayout(context)
        private val label = TextView(context)
        private val detail = TextView(context)

        init {
            view.orientation = LinearLayout.VERTICAL
            view.setPadding(dp(24), dp(6), dp(24), dp(18))
            view.isClickable = true
            view.setOnClickListener { onTap() }
            TiltEffect.apply(view)

            label.text = text
            label.textSize = 17f
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
     * A switch on a line of its own: a ticked square when on, an empty one when off.
     *
     * Square, where the Dark/Light and column rows are round, because this one is not one
     * of a set: nothing else is unchosen by turning it on. See [markerDrawable].
     */
    private inner class CheckRow(text: String, private val onChanged: (Boolean) -> Unit) {

        val view = LinearLayout(context)
        private val marker = View(context)
        private val label = TextView(context)
        private var isOn = false

        init {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            view.setPadding(dp(24), dp(6), dp(24), dp(18))
            view.isClickable = true
            view.visibility = GONE
            view.setOnClickListener {
                isOn = !isOn
                repaint()
                onChanged(isOn)
            }
            TiltEffect.apply(view)

            view.addView(marker, LinearLayout.LayoutParams(dp(20), dp(20)))

            label.text = text
            label.textSize = 17f
            label.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            view.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })
        }

        fun set(value: Boolean) {
            isOn = value
            repaint()
        }

        fun setVisible(visible: Boolean) {
            view.visibility = if (visible) VISIBLE else GONE
        }

        fun repaint() {
            marker.background = markerDrawable(round = false, on = isOn)
            label.setTextColor(palette.foreground)
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
    }

    // ---------------------------------------------------------------- appearance

    fun applyPalette(p: WP81Palette) {
        palette = p
        selectedAccent = p.accent
        selectedDark = p.isDark
        setBackgroundColor(p.background)
        header.applyPalette(p)
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child is TextView && child.tag == TAG_SECTION) {
                child.setTextColor(p.accent)
            }
        }
        blurLabel.setTextColor(p.accent)
        accentMoreLabel.setTextColor(p.foreground)
        accentChevron.imageTintList =
            android.content.res.ColorStateList.valueOf(p.foreground)
        driftRow.repaint()
        hideColorsRow.repaint()
        openLinksRow.repaint()
        defaultBrowserRow.repaint()
        repaintCountRows()
        repaintColumnRows()
        blurSlider.applyPalette(p)
        repaintThemeRows()
        repaintAccentSwatches()
        repaintWallpaperTiles()
    }

    /**
     * Stands every setting in from the page's left edge, leaving the headings on it.
     *
     * The page was one flat column: a heading and the rows under it began at the same
     * margin, so "tile columns" and "start background" read as two more rows rather than
     * as the names of what followed. An indent is enough to show which is which - the headings
     * hang out to the left, and each group is visibly a group.
     */
    private fun indentSettingRows() {
        val inset = dp(SECTION_INSET_DP)
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            // The title bar is the page's own, not a setting; headings mark the edge.
            if (child === header || child.tag == TAG_SECTION) continue
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            lp.leftMargin += inset
            child.layoutParams = lp
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG_SECTION = "wp81_section"

        /**
         * The widths the wall can be set to, narrowest first.
         *
         * Four is WP8.1's own; three is a wall of bigger tiles, and six is what the phone
         * itself offered on its larger screens under the name "show more tiles". The row
         * is built from this list, so the first entry is the one that carries the page's
         * left margin. See TileGridLayout.columns.
         */
        private val COLUMN_CHOICES = listOf(3, 4, 6)

        /** How far a setting stands in from the heading above it. See indentSettingRows. */
        private const val SECTION_INSET_DP = 12

        /** Swatches to a row, and so also how many stay out when the rest roll up. */
        private const val ACCENTS_PER_ROW = 5

        /** Air under the foot of the page, so the last setting is not on the edge. */
        private const val BOTTOM_GAP_DP = 20

        /** How far back a square of the strip stands while it is not the chosen one. */
        private const val UNSELECTED_SCALE = 0.88f
    }
}
