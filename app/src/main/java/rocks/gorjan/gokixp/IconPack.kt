package rocks.gorjan.gokixp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.xmlpull.v1.XmlPullParser
import kotlin.math.absoluteValue

/**
 * An icon pack installed from the Play Store, read the way every other launcher reads one.
 *
 * There is no Android API for this. What there is instead is a convention that came out of
 * ADW and Apex years ago and that every pack on the store still ships to: the pack is an
 * ordinary APK, it advertises itself with one of a handful of well-known intent filters,
 * and inside it is an `appfilter.xml` mapping launcher components to drawable names.
 *
 *     <item component="ComponentInfo{com.android.chrome/...Main}" drawable="chrome"/>
 *
 * So all of this is lookups against another package's resources: find the packs, parse the
 * mapping, and ask that package's [Resources] for the named drawable.
 *
 * The mapping never covers every app. That is what [iconBacks], [iconMask], [iconUpon] and
 * [scale] are for - the pack's recipe for dressing an app it has no artwork for, by
 * shrinking the app's own icon onto the pack's backplate and clipping it to the pack's
 * silhouette. Without that step a pack looks half-applied: the apps it knows about styled,
 * everything else raw beside them. See [dressed].
 *
 * Instances are shared between the wall, which resolves on the main thread, and the app
 * list, which resolves on a worker - so the parse and the component lookups are guarded.
 * Everything read after the parse is only read.
 */
class IconPack private constructor(
    private val context: Context,
    val packageName: String,
    val label: String,
    private val res: Resources,
) {

    /** A pack the phone has, before it is opened. */
    data class Installed(val packageName: String, val label: String)

    // ---------------------------------------------------------------- the mapping

    /** Guards the parse and the component cache; see the class comment. */
    private val lock = Any()

    private var parsed = false

    /** "pkg/class" -> drawable name. What appfilter actually says. */
    private val byComponent = mutableMapOf<String, String>()

    /**
     * package -> drawable name, first entry wins.
     *
     * A safety net rather than the real mapping. Packs are authored against whatever
     * launcher activity an app had when the artist made the pack, and apps rename theirs -
     * so a pack that plainly does have art for an app can miss it on a component match
     * alone. Matching on the package as well recovers those, and the component map is
     * still asked first so an app with two launcher activities keeps its two icons.
     */
    private val byPackage = mutableMapOf<String, String>()

    /** Backplates the pack draws under an app's own icon; one is chosen per app. */
    private var iconBacks = emptyList<String>()

    /** The silhouette an app's own icon is clipped to. Its opaque part is what is cut. */
    private var iconMask: String? = null

    /** Artwork laid over the finished icon - a gloss, a frame, a fold. */
    private var iconUpon: String? = null

    /** How far an app's own icon is shrunk before it is dressed. */
    private var scale = 1f

    /** packageName -> its launcher component, or null when it has none. */
    private val components = mutableMapOf<String, ComponentName?>()

    /**
     * Reads `appfilter.xml`, once.
     *
     * Two places to look, because packs differ: compiled into `res/xml`, which is where
     * the tooling puts it, or left in `assets`, which is where the older packs have it.
     */
    private fun parse() {
        synchronized(lock) {
            if (parsed) return
            parsed = true
            val parser = openAppFilter() ?: run {
                Log.w(TAG, "$packageName has no appfilter.xml")
                return
            }
            try {
                parser.use { read(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read $packageName's appfilter.xml", e)
            }
            Log.d(TAG, "$packageName: ${byComponent.size} icons, " +
                "${iconBacks.size} backs, mask=${iconMask != null}, scale=$scale")
        }
    }

    private fun openAppFilter(): CloseableParser? {
        val id = res.getIdentifier(APP_FILTER, "xml", packageName)
        if (id != 0) {
            return runCatching {
                val xml = res.getXml(id)
                CloseableParser(xml) { xml.close() }
            }.getOrNull()
        }
        return runCatching {
            val stream = res.assets.open("$APP_FILTER.xml")
            val pull = Xml.newPullParser()
            pull.setInput(stream, null)
            CloseableParser(pull) { stream.close() }
        }.getOrNull()
    }

    private fun read(parser: XmlPullParser) {
        val backs = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            componentKeyOf(component)?.let { key ->
                                byComponent[key] = drawable
                                byPackage.putIfAbsent(key.substringBefore('/'), drawable)
                            }
                        }
                    }
                    // img1, img2, ... - a pack may offer several backplates and expects
                    // them spread across the apps it has no artwork for.
                    "iconback" -> for (i in 0 until parser.attributeCount) {
                        parser.getAttributeValue(i)?.takeIf { it.isNotBlank() }?.let(backs::add)
                    }
                    "iconmask" -> iconMask = parser.getAttributeValue(null, "img1")
                    "iconupon" -> iconUpon = parser.getAttributeValue(null, "img1")
                    "scale" -> parser.getAttributeValue(null, "factor")
                        ?.toFloatOrNull()
                        ?.takeIf { it > 0f }
                        ?.let { scale = it }
                }
            }
            event = parser.next()
        }
        iconBacks = backs
    }

    /**
     * "pkg/class" out of whatever an appfilter entry wrote, or null when it is not one.
     *
     * The documented spelling is `ComponentInfo{pkg/class}` and most entries use it, but
     * packs are hand-written and plain `pkg/class` turns up too. What is always skipped is
     * the pack's own markers - `:BACKGROUND`, `:CALENDAR` and the like - which name a role
     * rather than an app and would otherwise be filed as artwork for a package called ":".
     */
    private fun componentKeyOf(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("ComponentInfo{")
            .removeSuffix("}")
            .trim()
        if (trimmed.startsWith(":")) return null
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return null
        return trimmed
    }

    // ---------------------------------------------------------------- resolving

    /**
     * The pack's own artwork for a package, or null when it has none for it.
     *
     * Deliberately separate from [dressed]: a caller that wants to know whether the pack
     * genuinely covers an app - rather than whether it can dress it - asks this.
     */
    fun iconFor(appPackage: String): Drawable? =
        drawableNameFor(appPackage)?.let(::drawableNamed)

    /** What the pack calls its artwork for an app, without going and decoding it. */
    fun drawableNameFor(appPackage: String): String? {
        parse()
        // Resolved before the lock is taken, not inside it: that is a call into the package
        // manager, and holding the lock across it would put the main thread to sleep behind
        // a binder round trip the app list happened to start first.
        val component = componentOf(appPackage)
        return synchronized(lock) {
            component?.let { byComponent["${it.packageName}/${it.className}"] }
                ?: byPackage[appPackage]
        }
    }

    /**
     * Whether the pack has anything to say about this app - its own artwork for it, or a
     * treatment to put the app's own icon through.
     *
     * The cheap half of [dressed], for callers that have to decide whose answer wins
     * before they are willing to pay for one. The tiles are the case: a pack's word beats
     * the themed monochrome layer, but only where the pack actually has a word, and
     * finding that out by rendering the icon and looking at what came back would cost a
     * composite per tile per rebuild.
     */
    fun covers(appPackage: String): Boolean {
        if (drawableNameFor(appPackage) != null) return true
        return synchronized(lock) {
            iconBacks.isNotEmpty() || iconMask != null || iconUpon != null
        }
    }

    /**
     * How the pack would have this app look: its own artwork where it has any, otherwise
     * [base] shrunk onto the pack's backplate and clipped to its silhouette.
     *
     * Null when the pack has neither - no entry and no backplate or mask to build one
     * with - which is the pack saying nothing about this app, and leaves the caller's own
     * resolution untouched rather than replacing it with a copy of what it already had.
     */
    fun dressed(appPackage: String, base: Drawable?): Drawable? {
        iconFor(appPackage)?.let { return it }
        if (base == null) return null
        if (iconBacks.isEmpty() && iconMask == null && iconUpon == null) return null
        return composite(appPackage, base)
    }

    /** One of the pack's drawables by name, for the icon picker and the mapping alike. */
    fun drawableNamed(name: String): Drawable? {
        for (type in DRAWABLE_TYPES) {
            val id = res.getIdentifier(name, type, packageName)
            if (id == 0) continue
            runCatching { ResourcesCompat.getDrawable(res, id, null) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Every icon in the pack, for picking one by hand.
     *
     * `drawable.xml` is the pack's own catalogue and is what its `<category>` headings and
     * ordering live in, so it is read first. Packs that ship none still have a complete
     * list in the mapping itself - every drawable the pack ever names - so that stands in.
     */
    fun contents(): List<String> {
        readCatalogue()?.let { if (it.isNotEmpty()) return it }
        parse()
        return synchronized(lock) { byComponent.values.distinct().sorted() }
    }

    private fun readCatalogue(): List<String>? {
        val parser = runCatching {
            val id = res.getIdentifier(DRAWABLE_LIST, "xml", packageName)
            if (id != 0) {
                val xml = res.getXml(id)
                CloseableParser(xml) { xml.close() }
            } else {
                val stream = res.assets.open("$DRAWABLE_LIST.xml")
                val pull = Xml.newPullParser()
                pull.setInput(stream, null)
                CloseableParser(pull) { stream.close() }
            }
        }.getOrNull() ?: return null

        return runCatching {
            val names = mutableListOf<String>()
            parser.use { p ->
                var event = p.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && p.name == "item") {
                        p.getAttributeValue(null, "drawable")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(names::add)
                    }
                    event = p.next()
                }
            }
            names.distinct()
        }.getOrNull()
    }

    // ---------------------------------------------------------------- dressing

    /**
     * Builds the pack's treatment of an app it has no artwork for.
     *
     * The order is the convention's: the backplate at the bottom, the app's own icon
     * shrunk by [scale] over it and clipped to the mask, then the overlay on top. The
     * clip is done on a layer of its own - masking the composite would take a bite out
     * of the backplate too, which is the opposite of what a mask is for.
     */
    private fun composite(appPackage: String, base: Drawable): Drawable {
        val size = MainActivity.ICON_SIZE_PX
        return try {
            val out = createBitmap(size, size)
            val canvas = Canvas(out)

            backFor(appPackage)?.let { draw(canvas, it, size, size) }

            val layer = createBitmap(size, size)
            val layerCanvas = Canvas(layer)
            val inset = ((size * (1f - scale.coerceIn(0.1f, 1f))) / 2f).toInt()
            base.setBounds(inset, inset, size - inset, size - inset)
            base.draw(layerCanvas)

            iconMask?.let(::drawableNamed)?.let { mask ->
                val cut = createBitmap(size, size)
                draw(Canvas(cut), mask, size, size)
                layerCanvas.drawBitmap(cut, 0f, 0f, maskPaint())
                cut.recycle()
            }

            canvas.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()

            iconUpon?.let(::drawableNamed)?.let { draw(canvas, it, size, size) }
            out.toDrawable(context.resources)
        } catch (e: Exception) {
            Log.w(TAG, "Could not dress $appPackage with $packageName", e)
            base
        }
    }

    /**
     * Which backplate this app gets, when the pack offers several.
     *
     * By the package name's hash rather than at random, so an app keeps the same
     * backplate for as long as the pack is on: chosen freshly each time, the wall would
     * reshuffle its own backplates on every rebuild.
     */
    private fun backFor(appPackage: String): Drawable? {
        if (iconBacks.isEmpty()) return null
        return drawableNamed(iconBacks[appPackage.hashCode().absoluteValue % iconBacks.size])
    }

    private fun draw(canvas: Canvas, drawable: Drawable, w: Int, h: Int) {
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
    }

    /** A package's launcher component, which is the key appfilter is written against. */
    private fun componentOf(appPackage: String): ComponentName? {
        synchronized(lock) {
            if (components.containsKey(appPackage)) return components[appPackage]
        }
        // Null is a real answer - a package with no launcher activity - and is cached as
        // one, so an app that has none is not asked about again on every rebuild.
        val component = runCatching {
            context.packageManager.getLaunchIntentForPackage(appPackage)?.component
        }.getOrNull()
        synchronized(lock) { components[appPackage] = component }
        return component
    }

    companion object {
        private const val TAG = "IconPack"
        private const val APP_FILTER = "appfilter"
        private const val DRAWABLE_LIST = "drawable"

        /** Packs ship their art under either, and a few use both. */
        private val DRAWABLE_TYPES = listOf("drawable", "mipmap")

        /**
         * Cuts the app's icon down to the pack's silhouette. See [composite].
         *
         * Built per call rather than held: two threads composite at once here - the wall
         * on the main thread while the app list resolves its rows on a worker - and a
         * Paint is not something to hand to both of them. It costs an allocation against
         * three full-size bitmaps, which is nothing.
         */
        private fun maskPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        /**
         * How a pack says it is one.
         *
         * Every one of these is some launcher's own theme action, and packs declare the
         * lot of them so as to be found by all of those launchers. They are asked for
         * together and the answers folded into one list, so a pack that names three
         * appears once.
         */
        private val THEME_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "ch.deletescape.lawnchair.ICONPACK",
            "com.dlto.atom.launcher.THEME",
            "net.oneplus.launcher.icons.ACTION_PICK_ICON",
        )

        /** The Play Store's own listing of them, for a phone that has none installed. */
        const val STORE_SEARCH = "https://play.google.com/store/search?q=icon%20pack&c=apps"

        /**
         * Every icon pack the phone has, by name, sorted the way they will be listed.
         *
         * Needs the package visibility the launcher already holds: without
         * QUERY_ALL_PACKAGES this comes back empty on Android 11 and later, because a
         * theme activity is not something the manifest can declare a `<queries>` intent
         * for and still see every pack that exists.
         */
        fun installed(context: Context): List<Installed> {
            val pm = context.packageManager
            val found = mutableMapOf<String, Installed>()
            for (action in THEME_ACTIONS) {
                val matches = runCatching {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
                }.getOrElse { emptyList() }
                for (match in matches) {
                    val pkg = match.activityInfo?.packageName ?: continue
                    if (pkg in found) continue
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrNull().orEmpty().ifBlank { pkg }
                    found[pkg] = Installed(pkg, label)
                }
            }
            return found.values.sortedBy { it.label.lowercase() }
        }

        /** Opens a pack for reading, or null when it is not installed any more. */
        fun open(context: Context, packageName: String): IconPack? = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            IconPack(
                context.applicationContext,
                packageName,
                pm.getApplicationLabel(info).toString().ifBlank { packageName },
                pm.getResourcesForApplication(info),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Icon pack $packageName cannot be opened", e)
            null
        }

        /**
         * The stored path for one icon chosen by hand out of a pack.
         *
         * No colons anywhere in it, and that is not cosmetic: the hand-picked mappings are
         * kept as `package:path` pairs joined by semicolons, so a path with a colon in it
         * is not merely ugly, it is dropped on the next read. See IconStore.load.
         */
        fun pathFor(packPackage: String, drawable: String) = "$PATH_PREFIX$packPackage/$drawable"

        /** Decodes what [pathFor] wrote, or null when [path] is some other kind of icon. */
        fun fromPath(context: Context, path: String): Drawable? {
            if (!path.startsWith(PATH_PREFIX)) return null
            val rest = path.removePrefix(PATH_PREFIX)
            val pack = rest.substringBeforeLast('/', "")
            val drawable = rest.substringAfterLast('/', "")
            if (pack.isEmpty() || drawable.isEmpty()) return null
            return open(context, pack)?.drawableNamed(drawable)
        }

        const val PATH_PREFIX = "iconpack/"
    }

    /** An XmlPullParser and the thing that has to be closed after it, as one. */
    private class CloseableParser(
        private val parser: XmlPullParser,
        private val close: () -> Unit,
    ) {
        fun <T> use(block: (XmlPullParser) -> T): T = try {
            block(parser)
        } finally {
            runCatching { close() }
        }
    }
}
