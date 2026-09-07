package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.AppInfo
import rocks.gorjan.gokixp.R
import java.util.concurrent.Executors

/**
 * The Windows Phone 8.1 app list - the alphabetical page you reach by swiping left from
 * Start.
 *
 * Everything that makes it that page - the letter squares, the jump grid behind them, the
 * held letter at the top, the search band that takes the rail's place - is
 * [MetroIndexList], which the People hub's own list is built on too. What is left here is
 * the part that is about *apps*: a row is an icon and a name, an app with flat artwork
 * wears it in white on a square of the accent, and a search that matches nothing installed
 * is handed on to the web.
 */
@SuppressLint("ViewConstructor")
class AppListView(
    context: Context,
    palette: WP81Palette,
    private val iconProvider: MonochromeIconProvider
) : MetroIndexList<AppInfo>(context, palette) {

    /**
     * Opening an app, as against choosing one.
     *
     * [onPick] - the list's own, from [MetroIndexList] - is set while the list is being
     * used to *choose* an app rather than open one, filling a folder for instance, and
     * takes precedence. The same list serves both jobs without a second screen that looks
     * identical and behaves differently.
     */
    var onLaunch: ((AppInfo) -> Unit)? = null

    /**
     * The mark to draw for one of the shell's own Metro apps, or null for anything else.
     *
     * The host decides which apps are the shell's own - it is the only thing that knows
     * what a package opens into - and hands back the drawable to draw. Everything else is
     * asked of [MonochromeIconProvider], which finds the app's own flat artwork where it
     * has any; see [resolveGlyph]. This outranks the provider, because a glyph written for this
     * shell says what the program *is* where a themed icon only says who made it.
     *
     * Volatile because it is asked on the worker that resolves artwork, and set here.
     */
    @Volatile
    var metroGlyph: ((AppInfo) -> Int?)? = null

    /**
     * Pressing search with a query that matched nothing installed.
     *
     * The list has said everything it can at that point; where the query goes next is the
     * host's business, not this view's.
     */
    var onSearchWeb: ((String) -> Unit)? = null

    override val searchHint: String get() = "search apps"

    /**
     * Whether the apps the user has put away are on show.
     *
     * Off every time the page is built. Hiding an app is the user saying they do not want
     * to see it, and a launcher that came back up still showing the lot would have made
     * that a one-time filter rather than a setting.
     */
    private var showingHidden = false

    /** The packages the user has hidden. See [setApps]. */
    private var hiddenPackages: Set<String> = emptySet()

    /** Every app there is, hidden ones included, filed. See [showApps]. */
    private var allApps: List<AppInfo> = emptyList()

    /** The rail's second command: the hidden apps, shown or put back. */
    private val hiddenToggle: ImageView

    init {
        build()
        hiddenToggle = addRailButton(eye(EYE_HIDE)) {
            showingHidden = !showingHidden
            hiddenToggle.setImageDrawable(eye(if (showingHidden) EYE_SHOW else EYE_HIDE))
            showApps()
        }
        onSearchSubmit = { typed, matches ->
            when {
                // The one at the top, however many there are. The list is already sorted
                // by how well each answers the query, so the first of them is the app the
                // user was typing towards - waiting for the field to narrow to exactly one
                // meant pressing search usually did nothing.
                matches.isNotEmpty() -> onLaunch?.invoke(matches.first())
                // Nothing on the phone answers to it, so the phone is not where the answer
                // is. Pressing search having been shown an empty list is a clear enough
                // request to look further afield.
                typed.isNotBlank() -> onSearchWeb?.invoke(typed)
            }
        }
    }

    /** One of the eyes from the phone's own icon set, drawn in the ring's ink. */
    private fun eye(name: String) = SvgIcon.fromAsset(context, "$ICON_DIR/$name")

    fun setApps(apps: List<AppInfo>, hidden: Set<String> = emptySet()) {
        // A fresh list is the one moment the art can have changed underneath the cache:
        // it is what an install, an uninstall, a chosen icon and a theme swap all end in.
        // Anything still being resolved is about the old list, and is counted out by the
        // batch rather than filed under the new one.
        glyphs.clear()
        pending.clear()
        queued.clear()
        swept = false
        batch++
        allApps = apps.sortedBy { it.name.lowercase() }
        hiddenPackages = hidden
        // Every name folded once here, rather than once per app per letter typed: the
        // search reads all of them, and reads them again the moment the next key goes
        // down. See [matches]. Hidden apps included, because a search made while they are
        // on show is a search of what is on show.
        lowered.clear()
        allApps.forEach { lowered[it.packageName] = it.name.lowercase() }
        showApps()
    }

    /**
     * Files whichever apps are on show, which is all of them or all but the hidden.
     *
     * The hidden ones are held rather than dropped, so the toggle is this list re-filing
     * what it already has - no second walk of the package manager, and no artwork resolved
     * twice - and the row itself says which they are by being drawn faint. See
     * [AppHolder.bind].
     */
    private fun showApps() {
        val visible =
            if (showingHidden || hiddenPackages.isEmpty()) allApps
            else allApps.filterNot { it.packageName in hiddenPackages }
        setItems(visible)
        // Artwork for whatever has just appeared. The sweep in [AppHolder.bind] runs once,
        // off the first row ever drawn, and these were not in the list when it did.
        if (swept) visible.forEach { request(it, urgent = false) }
    }

    /**
     * The mark for one app, once it has been resolved.
     *
     * Emptied whenever the list is set again, which is what every change to an app's
     * artwork ends in. See [setApps]. Read and written on the main thread only; what
     * fills it runs on [resolver].
     */
    private val glyphs = mutableMapOf<String, MonochromeIconProvider.Glyph?>()

    /** Each app's name in lower case, keyed by package. Filled by [setApps]. */
    private val lowered = mutableMapOf<String, String>()

    /**
     * Where an app's artwork is resolved.
     *
     * Resolving means asking the package manager for an icon and then rasterising it to
     * find where its ink sits: tens of milliseconds for one app. This list rebinds every
     * row on screen after every letter typed into its search field, and the rows a search
     * brings up are from all over the alphabet, so they are exactly the ones nothing has
     * resolved yet - a dozen icons' worth of decoding between one frame and the next,
     * which is what the field stuttered on as it was typed into. Worse than it sounds,
     * too: this shell's keyboard runs in this same process, so the stall froze the keys
     * as well as the list. So no artwork is resolved on the main thread at all. A row
     * draws what has been resolved and asks for what has not.
     *
     * One thread, because this is a queue and not a race, and a daemon, because a
     * half-measured icon is not worth holding the process open for.
     */
    private val resolver = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wp81-app-glyphs").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Apps waiting to be resolved, rows on screen at the front. Main thread only. */
    private val pending = ArrayDeque<AppInfo>()

    /** What is in [pending], so a second ask for the same app is not a second resolve. */
    private val queued = mutableSetOf<String>()

    private var resolving = false

    /** Whether the sweep through the rest of the list has been started. See [request]. */
    private var swept = false

    /** Which list the answers coming back belong to. See [setApps]. */
    private var batch = 0

    /** Every row ever made, so a glyph landing can find the one waiting for it. */
    private val holders = mutableListOf<AppHolder>()

    /**
     * Asks for one app's artwork.
     *
     * [urgent] is a row asking for what it is about to draw, and goes to the front. The
     * rest of the list is swept behind it so that a search which jumps to the far end of
     * the alphabet finds its matches already measured - but the rows actually on screen
     * cannot be made to wait behind a hundred apps nobody is looking at.
     */
    private fun request(app: AppInfo, urgent: Boolean) {
        if (glyphs.containsKey(app.packageName)) return
        if (!queued.add(app.packageName)) {
            // Already waiting. Nothing to do unless it is now wanted sooner than where it
            // is standing, in which case it is taken out of the queue and put at the head.
            if (!urgent) return
            pending.removeAll { it.packageName == app.packageName }
        }
        if (urgent) pending.addFirst(app) else pending.addLast(app)
        pump()
    }

    /** Starts the next resolve, unless the worker is inside one already. */
    private fun pump() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        queued.remove(next.packageName)
        resolving = true
        val asked = batch
        resolver.execute {
            val glyph = try {
                resolveGlyph(next)
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve artwork for ${next.packageName}", e)
                null
            }
            // The ink box is measured here too, on the thread that can afford it: the row
            // asks for it the moment it has a glyph to place, and measuring it means
            // rasterising the artwork all over again. See [placeGlyph].
            (glyph as? MonochromeIconProvider.Glyph.Monochrome)?.let {
                iconProvider.inkFor("ink:${next.packageName}", it.drawable)
            }
            main.post {
                resolving = false
                if (asked == batch) {
                    glyphs[next.packageName] = glyph
                    holders.forEach { it.glyphArrived(next.packageName) }
                }
                pump()
            }
        }
    }

    private fun resolveGlyph(app: AppInfo): MonochromeIconProvider.Glyph? {
        metroGlyph?.invoke(app)?.let { res ->
            val drawable = AppCompatResources.getDrawable(context, res) ?: return null
            return MonochromeIconProvider.Glyph.Monochrome(
                drawable, iconProvider.ratioFor("res:$res", drawable))
        }
        return iconProvider.glyphFor(app.packageName, ownCopyOf(app.icon))
    }

    /**
     * This list's own instance of an app's icon.
     *
     * The drawable on an [AppInfo] came from the icon store, and Start's tiles are holding
     * that same instance. Measuring artwork sets its bounds and draws it, and that now
     * happens on a worker thread, so the list takes a copy rather than reaching into a
     * picture something else is drawing. The artwork is shared; only the bounds are not.
     */
    private fun ownCopyOf(icon: android.graphics.drawable.Drawable?) =
        icon?.constantState?.newDrawable(context.resources) ?: icon

    /**
     * Puts one glyph on its accent square at [GLYPH_DP], wherever its ink happens to sit
     * inside the artwork it arrived in.
     *
     * Nothing about the source is taken on trust. Every source pads itself differently - a
     * themed layer keeps the adaptive-icon safe zone, a notification silhouette fills its
     * bounds, this shell's own glyphs cover about half of theirs - and some pad so heavily
     * that the mark is a speck in the middle of a mostly empty picture. Worse, the padding
     * is not always symmetrical, so the ink is not always in the middle of it. So the
     * measured ink box is what gets placed: its longer side is scaled to [GLYPH_DP] and its
     * centre put at the square's centre, which makes every mark in the list the same size
     * and on the same axis whatever it was drawn on.
     *
     * Placed by matrix rather than by padding, because a glyph that covers a seventh of its
     * canvas needs a canvas seven times the square to show at the right size, and no amount
     * of padding can give it one. What hangs over the edge is that artwork's own margin,
     * and the square clips it.
     */
    private fun placeGlyph(
        view: ImageView,
        drawable: android.graphics.drawable.Drawable,
        packageName: String
    ) {
        // Keyed by package, which is what the provider forgets by when an app's artwork
        // changes - and what this list resolves one glyph per, so the two stay in step.
        val ink = iconProvider.inkFor("ink:$packageName", drawable)
        val canvasW = drawable.intrinsicWidth.toFloat()
        val canvasH = drawable.intrinsicHeight.toFloat()
        // Artwork that drew nothing, or that will not say how large it is: an ImageView
        // ignores the matrix for a drawable with no intrinsic size, so there is nothing to
        // place and the plain fit is the only honest answer.
        if (ink == null || canvasW <= 0f || canvasH <= 0f) {
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        val inkW = ink.width() * canvasW
        val inkH = ink.height() * canvasH
        val scale = dp(GLYPH_DP) / maxOf(inkW, inkH)
        val centre = dp(ICON_DP) / 2f
        view.scaleType = ImageView.ScaleType.MATRIX
        view.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                centre - scale * (ink.left * canvasW + inkW / 2f),
                centre - scale * (ink.top * canvasH + inkH / 2f)
            )
        }
    }

    override fun letterOf(item: AppInfo): Char = bucketOf(item.name)

    override fun matches(item: AppInfo, query: String): Boolean =
        (lowered[item.packageName] ?: item.name.lowercase()).contains(query)

    override fun createHolder(): ItemHolder = AppHolder().also { holders.add(it) }

    private inner class AppHolder : ItemHolder(LinearLayout(context)) {

        private val icon = ImageView(context)
        private val name = TextView(context)
        private var bound: AppInfo? = null

        /** Whether this row's press began in the system's own gesture strip. */
        private var fromEdge = false

        init {
            val root = view as LinearLayout
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP))
            root.setPadding(0, 0, rowEdge(), 0)
            root.isClickable = true

            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))

            name.textSize = 16f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            root.addView(name, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })

            TiltEffect.apply(root) { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    val edge = dp(SYSTEM_GESTURE_DP)
                    val width = resources.displayMetrics.widthPixels
                    fromEdge = event.rawX < edge || event.rawX > width - edge
                }
                false
            }
            root.setOnClickListener {
                bound?.let { app ->
                    if (onPick != null) pick(app) else onLaunch?.invoke(app)
                }
            }
            // The tick is the row's to give, not the framework's. A claimed long press is
            // answered with one automatically, and that fires the moment the listener says
            // it handled the press - including when all it did was refuse an edge drag, so
            // a back gesture buzzed on its way past. Turned off here and given by hand
            // below, ignoring the view's own setting rather than the user's.
            root.isHapticFeedbackEnabled = false
            root.setOnLongClickListener {
                // Not from the very edge of the screen. That is where the system's back
                // gesture starts, and a back drag begins as a finger held still against
                // the edge for as long as it takes to decide - which is long enough for a
                // row under it to call that a press-and-hold. The menu then opened on the
                // way out of the list and was still standing over Start when the drag
                // landed there.
                //
                // Claimed rather than declined, so the release that follows is not read as
                // a tap and does not launch whatever the finger happened to be resting on.
                if (fromEdge) return@setOnLongClickListener true
                root.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
                bound?.let { app -> held(app, root) }
                true
            }
        }

        override fun bind(item: AppInfo) {
            bound = item
            name.text = item.name
            name.setTextColor(palette.foreground)
            // A hidden app on show is still a hidden app. Faint rather than marked, so the
            // list reads as what it is - the ordinary page with the put-away ones behind
            // it - without a badge on every row explaining itself.
            view.alpha = if (item.packageName in hiddenPackages) HIDDEN_ALPHA else 1f
            drawGlyph(item)
            // A row being drawn at all is the list arriving on screen, and that is the
            // moment the rest of it is worth measuring: a search a keystroke later can ask
            // for any app in it. Started from here rather than from setApps, so a launcher
            // does not spend its first seconds on a page nobody has opened.
            if (!swept) {
                swept = true
                items().forEach { request(it, urgent = false) }
            }
        }

        /**
         * Draws whatever artwork [item] has resolved to, and asks for it if it has none.
         *
         * Flat artwork - this shell's own glyph, an app's themed monochrome layer, or its
         * notification silhouette - is drawn the way the phone drew the programs it came
         * with: white on a square of the accent. An app with none of those keeps the icon
         * it was installed with, unboxed, which is also what WP8.1 did with art a developer
         * had not drawn for a tile.
         */
        private fun drawGlyph(item: AppInfo) {
            if (!glyphs.containsKey(item.packageName)) {
                // Nothing resolved yet. The slot is left empty for the frame or two the
                // worker takes rather than filled with something that would be swapped out
                // in front of the reader.
                drawPlain(null)
                request(item, urgent = true)
                return
            }
            when (val glyph = glyphs[item.packageName]) {
                is MonochromeIconProvider.Glyph.Monochrome -> {
                    icon.setImageDrawable(glyph.drawable)
                    icon.imageTintList = ColorStateList.valueOf(palette.onAccent())
                    icon.setBackgroundColor(palette.accent)
                    placeGlyph(icon, glyph.drawable, item.packageName)
                }
                is MonochromeIconProvider.Glyph.FullColor -> drawPlain(glyph.drawable)
                null -> drawPlain(null)
            }
        }

        /**
         * The app's own icon, filling the slot rather than sitting on a square: it is a
         * picture, not a mark, and there is nothing for it to line up with. Null is a row
         * with nothing to show yet, which is the same slot with nothing in it.
         */
        private fun drawPlain(drawable: android.graphics.drawable.Drawable?) {
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.setImageDrawable(drawable)
            icon.imageTintList = null
            icon.background = null
        }

        /** A glyph has landed; take it if this row is still the one waiting for it. */
        fun glyphArrived(packageName: String) {
            val item = bound ?: return
            if (item.packageName == packageName) drawGlyph(item)
        }
    }

    private companion object {
        const val TAG = "WP81AppList"

        const val ROW_DP = 62

        /** The square a Metro app's glyph sits on, and the box every other icon fills. */
        const val ICON_DP = 42

        /**
         * How large the *visible* mark on an accent square is, whatever it was drawn on.
         *
         * Every mark is put at this size rather than at whatever its own padding happened
         * to give; see [placeGlyph]. Twenty-four of the square's forty-two: four more than
         * where this shell's own glyphs sat on their own, which was a mark with more square
         * around it than the square needed.
         */
        const val GLYPH_DP = 24

        /** The phone's own icon set, which the rail's eyes are drawn from. */
        const val ICON_DIR = "custom_icons_8"
        const val EYE_HIDE = "appbar.eye.hide.svg"
        const val EYE_SHOW = "appbar.eye.svg"

        /** What is left of a hidden app's row when it is being shown anyway. */
        const val HIDDEN_ALPHA = 0.7f
    }
}
