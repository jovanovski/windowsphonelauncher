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
    private val permissions: List<Permission> = emptyList()
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

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
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
        panorama.addPage("about", page(buildWelcome()))
        if (permissions.isNotEmpty()) panorama.addPage("permissions", page(buildPermissions()))
        panorama.addPage("release notes", page(buildNotes()))
        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
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

        column.addView(TextView(context).apply {
            text = "version $versionName"
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            setTextColor(palette.accent)
            setPadding(0, 0, 0, dp(10))
        }, wide())

        column.addView(body(WELCOME_TEXT), wide())

        // As rows: a phone is a poor place to aim at a word in the middle of a paragraph.
        // The address is not among them - it is in the text above, where Linkify has
        // already made it tappable, and twice is once too many.
        column.addView(link("the source, on GitHub", GITHUB_URL), wide())
        column.addView(link("donate to project", COFFEE_URL), wide())
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

        const val WELCOME_TEXT =
            "This is a passion project from Gorjan Jovanovski, a developer who grew up " +
                "with the Metro design, and wanted the same experience but on a modern usable phone.\n\n" +
                "A few tips:\n" +
                "1) Long press on the start icon to access settings.\n" +
                "2) Hold a tile to move it, resize it, or paint it another colour using the buttons on the bottom.\n" +
                "3) Swipe left from Start for everything installed.\n" +
                "4) Tap the corner of a tile to turn it over.\n" +
                "5) Enable the app's keyboard to get a Windows Phone keyboard too'.\n\n" +
                "All the copyrighted information belongs to their respective authors; the " +
                "aim here is to recreate nostalgia for fun.\n\n" +
                "For any feature requests, drop me an email at hey@gorjan.rocks\n\n" +
                "Thanks for using Windows Phone, and donate to keep the project alive!"
    }
}
