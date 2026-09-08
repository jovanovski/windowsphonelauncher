package rocks.gorjan.gokixp.apps.welcome

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.MetroToggle
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81Program

/**
 * Welcome, as Windows Phone would have said it.
 *
 * The same thing the desktop themes put in a window after an update - what this is, who
 * made it, how to get around, and what changed - laid out as a panorama instead of a
 * dialog with a picture and two buttons.
 *
 * The host plays the startup jingle as it opens - the desktop welcome played its theme
 * on a loop, and this is the phone's, once.
 *
 * The release notes are fetched rather than bundled, from the same GitHub releases the
 * desktop welcome reads - so there is one list of what changed and it is never a build
 * behind.
 */
class WelcomeApp(
    private val context: Context,
    private var palette: WP81Palette,
    private val versionName: String,
    private val onOpenLink: (String) -> Unit,
    private val loadReleaseNotes: ((String) -> Unit) -> Unit,
    private val permissions: List<Permission> = emptyList(),
    /**
     * Opens the download for a version waiting to be installed, or null when there is none.
     *
     * A var because the launcher looks for updates on its own clock and the answer often
     * lands after this page is already up - see [setUpdateAvailable].
     */
    private var onDownloadUpdate: (() -> Unit)? = null,
    /**
     * Looks for a new version now, calling back on the main thread when the answer is in.
     *
     * The launcher looks on its own hourly clock; this is the same look, asked for by hand.
     * Whatever it finds is the host's to announce - the band across the top, and the banner
     * above if there is something to download - so all this page does is say it is asking.
     */
    private val onCheckForUpdates: ((() -> Unit) -> Unit)? = null,
    /**
     * Whether to open on the release notes instead of on the front page.
     *
     * True when the launcher has just been updated, which is the one time this program
     * opens by itself with something specific to say: somebody who already knows what this
     * is wants what changed, not the introduction they read a version ago. A first install
     * still opens on the welcome, where the introduction is the point.
     *
     * A var because it is spent on the first build - see [createView]. A theme change
     * rebuilds the whole program, and that is not an update.
     */
    private var startOnReleaseNotes: Boolean = false
) : WP81Program {

    /**
     * One thing the launcher needs permission to do.
     *
     * The check and the asking both live with the host - which Android permission, which
     * settings screen, which role - because those are the activity's business. This page
     * only draws the answer and passes the tap back.
     */
    class Permission(
        val name: String,
        /** What stops working without it, in the user's terms rather than the platform's. */
        val why: String,
        val isOn: () -> Boolean,
        /**
         * Asks for it, or opens the screen that owns it.
         *
         * There is no "turn off" counterpart: an app cannot revoke its own permissions, so
         * a switch that is already on sends the user to the settings page where they can.
         */
        val onTap: () -> Unit
    )

    private lateinit var root: FrameLayout
    private lateinit var notes: TextView

    /** The version line, which doubles as the check-now button. See [checkForUpdates]. */
    private lateinit var versionLabel: TextView

    /** Whether a check asked for here is still out, so a second tap does not send another. */
    private var checking = false

    /** Everything under the banner, kept so the banner can be put in and taken out of it. */
    private lateinit var pageColumn: LinearLayout
    private var updateBanner: View? = null

    /** The switches, kept so [refresh] can put them back where the system actually is. */
    private val switches = mutableListOf<Pair<MetroToggle, Permission>>()

    /**
     * Rebuilds the program in a new theme. See [WP81Program].
     */
    override fun applyPalette(palette: WP81Palette): View {
        this.palette = palette
        return createView()
    }

    fun createView(): View {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }

        pageColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addUpdateBanner()
        pageColumn.addView(TextView(context).apply {
            text = "welcome"
            typeface = font(R.font.segoeui_semilight)
            textSize = 30f
            setTextColor(palette.foregroundSubtle)
            setPadding(dp(MARGIN_DP), dp(14), dp(MARGIN_DP), dp(2))
            includeFontPadding = false
        }, wide())

        val panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // Gathered before they are added so the notes can be found by name below: which
        // number that section is depends on whether there are permissions to show.
        val sections = buildList {
            add("about" to buildWelcome())
            add("tips & tricks" to buildTips())
            if (permissions.isNotEmpty()) add("permissions" to buildPermissions())
            add(NOTES_SECTION to buildNotes())
        }
        for ((title, content) in sections) panorama.addPage(title, page(content))
        // Straight to what changed, when the launcher has just been updated. Not animated:
        // this is where the program opens, not somewhere it was swiped to.
        if (startOnReleaseNotes) {
            startOnReleaseNotes = false
            panorama.goTo(sections.indexOfFirst { it.first == NOTES_SECTION }, animated = false)
        }
        pageColumn.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(pageColumn, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    /**
     * Says a new version is waiting, or stops saying it.
     *
     * Called by the host when its hourly look answers, which is usually after this page has
     * been built: on a fresh install the welcome opens a second after launch, well before
     * GitHub has replied. So the banner arrives rather than being there from the start.
     */
    fun setUpdateAvailable(onDownload: (() -> Unit)?) {
        onDownloadUpdate = onDownload
        if (!::pageColumn.isInitialized) return
        updateBanner?.let { pageColumn.removeView(it) }
        updateBanner = null
        addUpdateBanner()
    }

    /**
     * The strip across the top of the page, when there is something to download.
     *
     * In the accent, which is what Windows Phone painted the thing it wanted looked at,
     * and full width above the header rather than a line inside the about page: the person
     * who came here for the release notes should not have to find it.
     */
    private fun addUpdateBanner() {
        val onDownload = onDownloadUpdate ?: return
        val banner = TextView(context).apply {
            text = UPDATE_TEXT
            typeface = font(R.font.segoeui_semibold)
            textSize = 15f
            setTextColor(palette.onAccent())
            setBackgroundColor(palette.accent)
            setPadding(dp(MARGIN_DP), dp(12), dp(MARGIN_DP), dp(12))
            isClickable = true
            setOnClickListener { onDownload() }
            rocks.gorjan.gokixp.wp81.TiltEffect.apply(this)
        }
        pageColumn.addView(banner, 0, wide())
        updateBanner = banner
    }

    private fun page(content: View): View = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun buildWelcome(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(MARGIN_DP), dp(28))
        }

        versionLabel = TextView(context).apply {
            text = versionText()
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            setTextColor(palette.accent)
            setPadding(0, 0, 0, dp(10))
        }
        // Tapping the version asks for the update check now instead of waiting on the
        // launcher's hourly one - unlabelled, the way the desktop about box hid the same
        // thing behind its version number, and harmless to find by accident.
        onCheckForUpdates?.let { check ->
            versionLabel.isClickable = true
            versionLabel.setOnClickListener { checkForUpdates(check) }
            rocks.gorjan.gokixp.wp81.TiltEffect.apply(versionLabel)
        }
        column.addView(versionLabel, wide())

        column.addView(body(WELCOME_TEXT), wide())

        // As rows: a phone is a poor place to aim at a word in the middle of a paragraph.
        // The address is not among them - it is in the text above, where Linkify has
        // already made it tappable, and twice is once too many.
        column.addView(link("the source, on GitHub", GITHUB_URL), wide())
        column.addView(link("donate to project", COFFEE_URL), wide())
        return column
    }

    /**
     * How to drive the thing.
     *
     * A page rather than a numbered list in the middle of the welcome paragraph, which is
     * where these used to be and where nobody read them twice. Nothing here is discoverable
     * by pressing things at random - a long press that resizes, a search button that means
     * two different things - which is the whole reason for writing them down.
     */
    private fun buildTips(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(MARGIN_DP), dp(28))
        }
        for ((title, text) in TIPS) {
            column.addView(TextView(context).apply {
                this.text = title
                typeface = font(R.font.segoeui_semibold)
                textSize = 12f
                setTextColor(palette.accent)
                setPadding(0, dp(16), 0, dp(3))
                includeFontPadding = false
            }, wide())
            column.addView(body(text), wide())
        }
        return column
    }

    /**
     * Everything the launcher needs permission to do, and whether it has it.
     *
     * On one page because that is the question people actually have - "why is the weather
     * tile empty" - and answering it a permission at a time, each behind whichever screen
     * Android files it under, is how a launcher ends up half working with no way to tell.
     *
     * A switch that is already on opens the system's own page rather than pretending it
     * can be turned off here: an app cannot revoke its own permissions.
     */
    private fun buildPermissions(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(MARGIN_DP), dp(28))
        }

        column.addView(body(PERMISSIONS_TEXT), wide())
        switches.clear()

        for (permission in permissions) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }

            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply {
                text = permission.name
                typeface = font(R.font.segoeui_regular)
                textSize = 17f
                setTextColor(palette.foreground)
                includeFontPadding = false
            }, wide())
            labels.addView(TextView(context).apply {
                text = permission.why
                typeface = font(R.font.segoeui_regular)
                textSize = 12f
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(2), 0, 0)
            }, wide())
            row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))

            val toggle = MetroToggle(context, palette).apply {
                set(permission.isOn(), animated = false)
                onChanged = {
                    // The switch is a request, not a fact. It is put back to whatever the
                    // system says the moment the page is looked at again - see refresh -
                    // so a permission that was refused does not stay showing as granted.
                    permission.onTap()
                }
            }
            row.addView(toggle, LinearLayout.LayoutParams(dp(52), dp(26)))
            switches.add(toggle to permission)

            column.addView(row, wide())
        }
        return column
    }

    private fun versionText() = "version $versionName"

    /**
     * Asks the host to look for a new version, and says so while it looks.
     *
     * The answer arrives as the band across the top - a new version, or that there is none -
     * because that is where this launcher says everything else about updates. The line
     * itself only goes back to being the version number.
     */
    private fun checkForUpdates(check: ((() -> Unit) -> Unit)) {
        if (checking) return
        checking = true
        versionLabel.text = CHECKING_TEXT
        check {
            checking = false
            // A theme change between the tap and the answer rebuilds the page; the field
            // points at the new line by then, which is the one to put back.
            if (::versionLabel.isInitialized) versionLabel.text = versionText()
        }
    }

    /**
     * Puts every switch back where the system is.
     *
     * Called when the launcher comes back to the front, which is the moment after the user
     * has been off answering one of Android's own prompts.
     */
    fun refresh() {
        switches.forEach { (toggle, permission) -> toggle.set(permission.isOn(), animated = true) }
    }

    private fun buildNotes(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(MARGIN_DP), dp(28))
        }
        notes = body("reading the release notes…")
        column.addView(notes, wide())
        loadReleaseNotes { text -> notes.text = text }
        return column
    }

    private fun body(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_semilight)
        textSize = 15f
        setTextColor(palette.foreground)
        setLineSpacing(0f, 1.05f)
        // Anything that looks like an address is one, the way the desktop welcome does it.
        Linkify.addLinks(this, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        // And the name of whoever wrote it, which looks like nothing at all but is the
        // one link in the paragraph worth having. Added after Linkify, which replaces
        // whatever spans it finds on the text rather than adding to them.
        linkName(this)
        movementMethod = LinkMovementMethod.getInstance()
        setLinkTextColor(palette.accent)
    }

    /** Makes the author's name in [view] open his site. */
    private fun linkName(view: TextView) {
        val text = view.text?.toString() ?: return
        val start = text.indexOf(AUTHOR)
        if (start < 0) return
        val spannable = android.text.SpannableString(view.text)
        spannable.setSpan(
            object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = onOpenLink(AUTHOR_URL)

                override fun updateDrawState(paint: android.text.TextPaint) {
                    super.updateDrawState(paint)
                    paint.color = palette.accent
                    paint.isUnderlineText = false
                }
            },
            start, start + AUTHOR.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        view.text = spannable
    }

    private fun link(label: String, url: String) = TextView(context).apply {
        text = label
        typeface = font(R.font.segoeui_semilight)
        textSize = 17f
        setTextColor(palette.accent)
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { onOpenLink(url) }
        rocks.gorjan.gokixp.wp81.TiltEffect.apply(this)
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val MARGIN_DP = 22

        /** The section an update opens on. See the constructor's startOnReleaseNotes. */
        const val NOTES_SECTION = "release notes"

        /** What the banner says. See [addUpdateBanner]. */
        const val UPDATE_TEXT = "New version available, tap to download"

        /** What the version line says while a check is out. See [checkForUpdates]. */
        const val CHECKING_TEXT = "checking for updates…"

        val GITHUB_URL = "https://github.com/${rocks.gorjan.gokixp.MainActivity.GITHUB_REPO}/"
        const val COFFEE_URL = "https://buymeacoffee.com/jovanovski"

        const val AUTHOR = "Gorjan Jovanovski"
        const val AUTHOR_URL = "https://gorjan.rocks"

        /**
         * The desktop welcome's own text, less the line about the music - there is none
         * here - and less the tips that were about a desktop: there is no wallpaper to
         * long-press and no window to swipe closed on a Start screen.
         */
        const val PERMISSIONS_TEXT =
            "This launcher replaces your home screen, so most of what it shows comes from " +
                "somewhere Android guards. Nothing here is required - every switch you " +
                "leave off simply means that one tile or app stays empty.\n\n" +
                "Turning one on takes you to Android's own prompt or settings page. " +
                "Coming back here, the switches show what you actually granted.\n"

        /**
         * The tips, each under the name of the thing it is about.
         *
         * Titled rather than numbered because a number is only an order, and somebody
         * scrolling back to find the one about folders is looking for the word.
         */
        val TIPS = listOf(
            "pin to start" to
                "Press and hold any app in the list and choose pin to start. It becomes a " +
                    "tile, and an app can have as many of them as you like.",
            "move, resize, group" to
                "Hold a tile and the wall goes into edit mode with that tile already in " +
                    "your hand. Drag the chevron on its bottom corner to resize it, the pin " +
                    "on the top corner to unpin it, and drop one tile onto another to make " +
                    "a folder. Tap an empty space to finish.",
            "cortana" to
                "The search button at the bottom of the screen is Cortana. She searches the " +
                    "web, puts a question to your favourite AI, or listens to whatever is " +
                    "playing and tells you what it is.",
            "search inside an app" to
                "In an app that can search its own contents, the search button turns your " +
                    "accent colour - tap it there and you are searching the app rather than " +
                    "the web. Holding it always brings up Cortana, wherever you are.",
            "settings" to
                "Press and hold the Start button.",
            "everything installed" to
                "Swipe left from Start for the full app list.",
            "live tiles" to
                "People, Weather, Files & Photos and News turn themselves over with whatever " +
                    "is new - a face, tomorrow's forecast, a photo, a headline.",
            "the keyboard" to
                "Enable this app's keyboard in Android's settings and you get the Windows " +
                    "Phone keyboard everywhere, not just here."
        )

        const val WELCOME_TEXT =
            "This is a passion project from Gorjan Jovanovski, a developer who grew up " +
                "with the Metro design, and wanted the same experience but on a modern usable phone.\n\n" +
                "Swipe across for tips and tricks, for the permissions the launcher asks for, " +
                "and for what changed in this version.\n\n" +
                "All the copyrighted information belongs to their respective authors; the " +
                "aim here is to recreate nostalgia for fun.\n\n" +
                "For any feature requests, drop me an email at hey@gorjan.rocks\n\n" +
                "Thanks for using Windows Phone, and donate to keep the project alive!"
    }
}
