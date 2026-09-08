package rocks.gorjan.gokixp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import rocks.gorjan.gokixp.wp81.CUSTOM_ICONS_KEY
import rocks.gorjan.gokixp.wp81.WP81TileHost
import java.io.File

/**
 * The icons a package is shown with, and the bitmaps they are drawn from.
 *
 * Three things that used to be spread through MainActivity: the map of icons the user
 * picked by hand, the LruCache of rendered squares, and the rendering itself. They belong
 * together - every one of them is keyed by package name, and changing an icon has to touch
 * all three or a stale bitmap is handed back.
 *
 * What is deliberately *not* here is where a system program's artwork comes from. That is
 * the shell's business - it knows Zune from Internet Explorer - so it arrives as
 * [systemIconFor], the same way WP81TileHost takes its seams.
 */
class IconStore(
    private val context: Context,
    /** A package's own artwork, or null if this is not a program the shell draws itself. */
    private val systemIconFor: (String) -> Drawable?,
    /**
     * Whether a package is an installed app, and so something an icon pack may dress.
     *
     * A seam for the same reason [systemIconFor] is one: the shell's own programs, its
     * folders and its recycle bin are not apps the phone has, they are pictures this
     * launcher draws, and an icon pack has nothing to say about them. Without this a pack
     * with a backplate would mount Zune's own mark on it. What counts as one of the
     * shell's is the shell's to know.
     */
    private val isPackable: (String) -> Boolean = { true },
) {

    /**
     * packageName -> icon path, either an asset path or "imported_icons/<file>.png".
     *
     * Concurrent because it is read from more than one thread: the app list resolves its
     * rows' artwork on a worker so that typing in its search field is not stalled behind a
     * package manager call, while everything else asks on the main thread - which is also
     * where the writes happen, when an icon is picked or the mappings are reloaded.
     */
    private val customIcons = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The icon pack dressing every app that has not been given an icon by hand, if any.
     *
     * Set by the shell from what is saved, and null for the phone's own artwork. Setting
     * it empties the rendered-bitmap cache, because every entry in it that came from a
     * package rather than from a hand-picked file was drawn under the previous answer.
     */
    var pack: IconPack? = null
        set(value) {
            // By identity, not by name. A pack that has just been updated comes back under
            // the same package with a new [IconPack] holding the new artwork, and comparing
            // names would keep the old one - and every bitmap already rendered from it.
            // The caller is what decides when to re-open; see MainActivity.applyIconPack.
            if (field === value) return
            field = value
            evictAll()
        }

    private val prefs get() = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- the picked icons

    fun load() {
        val stored = prefs.getString(CUSTOM_ICONS_KEY, "") ?: ""
        customIcons.clear()
        if (stored.isNotEmpty()) {
            stored.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) customIcons[parts[0]] = parts[1]
            }
        }
        Log.d(TAG, "Loaded ${customIcons.size} custom icon mappings")
    }

    fun save() {
        prefs.edit { putString(CUSTOM_ICONS_KEY, customIcons.entries.joinToString(";") { "${it.key}:${it.value}" }) }
    }

    fun has(packageName: String): Boolean = customIcons.containsKey(packageName)

    /** Records a hand-picked icon, or clears it when [path] is "default". */
    fun set(packageName: String, path: String) {
        if (path == "default") customIcons.remove(packageName) else customIcons[packageName] = path
        save()
    }

    // ---------------------------------------------------------------- drawing

    /**
     * The icon a package is shown with: the hand-picked one where there is one, then the
     * icon pack's if one is on, and otherwise whatever [systemIconFor] answers.
     *
     * That order is the one the user set it in. An icon chosen for a single app is an
     * answer about that app and outranks a pack chosen for all of them, which in turn
     * outranks the artwork the app happened to ship with.
     */
    fun iconFor(packageName: String, skipCustom: Boolean = false): Drawable? {
        if (!skipCustom) {
            customIcons[packageName]?.let { path ->
                try {
                    fromPath(path)?.let { return square(it, "custom_${packageName}_$path") }
                } catch (e: Exception) {
                    // A mapping pointing at a file that is no longer there is not a mapping.
                    Log.w(TAG, "Dropping unreadable custom icon for $packageName", e)
                    customIcons.remove(packageName)
                    save()
                }
            }
        }
        val system = systemIconFor(packageName)
        pack?.takeIf { isPackable(packageName) }?.let { active ->
            // Handed the system artwork so the pack can dress an app it has no icon for.
            // Squared under a key of its own: the pack's answer and the app's own are two
            // different pictures for one package and must not share a cache entry.
            active.dressed(packageName, system)?.let {
                return square(it, "pack_${active.packageName}_$packageName")
            }
        }
        return system?.let { square(it, "app_$packageName") }
    }

    /** Decodes an icon file, an asset, or one icon named out of an installed icon pack. */
    fun fromPath(iconPath: String): Drawable? = WP81TileHost.loadIconFromPath(context, iconPath)

    /**
     * Renders [drawable] centred in a square of [MainActivity.ICON_SIZE_PX].
     *
     * Cached when a [cacheKey] is given. The system programs' own artwork is squared
     * without one: it is built once when the app list is assembled and never looked up by
     * package, so caching it would only hold a second copy of a drawable already held.
     */
    fun square(drawable: Drawable, cacheKey: String? = null): Drawable {
        if (cacheKey != null) bitmaps.get(cacheKey)?.let { return it.toDrawable(context.resources) }

        val size = MainActivity.ICON_SIZE_PX
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        val scale = if (w > 0 && h > 0) minOf(size.toFloat() / w, size.toFloat() / h) else 1f
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()
        val left = (size - sw) / 2
        val top = (size - sh) / 2

        drawable.setBounds(left, top, left + sw, top + sh)
        drawable.draw(canvas)

        if (cacheKey != null) bitmaps.put(cacheKey, bitmap)
        return bitmap.toDrawable(context.resources)
    }

    // ---------------------------------------------------------------- housekeeping

    /**
     * Drops every cached bitmap belonging to a package so the next [iconFor] re-reads it.
     *
     * An in-place app update keeps the same package name, so without this the cache keeps
     * handing back the icon the app shipped with before the update - including when the
     * user picks "Default" in the Change Icon dialog.
     */
    fun invalidate(packageName: String) {
        val stale = bitmaps.snapshot().keys.filter {
            it == "app_$packageName" ||
                it.startsWith("custom_${packageName}_") ||
                // The pack's key carries the pack's name in the middle, so it is the tail
                // that identifies the app: "pack_<pack package>_<this package>".
                (it.startsWith("pack_") && it.endsWith("_$packageName"))
        }
        stale.forEach { bitmaps.remove(it) }
        Log.d(TAG, "Invalidated ${stale.size} cached icons for $packageName")
    }

    /** Trims the cache under memory pressure; [fraction] of its maximum is kept. */
    fun trim(fraction: Int = 4) = bitmaps.trimToSize(bitmaps.maxSize() / fraction)

    /** Drops every cached bitmap. For when the app goes to the background. */
    fun evictAll() {
        val was = bitmaps.size()
        bitmaps.evictAll()
        Log.d(TAG, "Cleared icon bitmap cache (was $was items)")
    }

    /**
     * Deletes imported icon files nothing points at any more.
     *
     * Reads the stored map rather than [customIcons] on purpose: this also runs after a
     * restore, when what is on disk is the authority and memory may not have caught up.
     */
    fun pruneImported() {
        val dir = File(context.filesDir, MainActivity.IMPORTED_ICONS_DIR)
        val files = dir.listFiles() ?: return
        val inUse = (prefs.getString(CUSTOM_ICONS_KEY, "") ?: "")
            .split(";")
            .mapNotNull { it.substringAfter(":", "").takeIf(String::isNotEmpty) }
            .toSet()
        files.forEach { file ->
            if ("${MainActivity.IMPORTED_ICONS_DIR}/${file.name}" !in inUse && file.delete()) {
                Log.d(TAG, "Removed unused imported icon: ${file.name}")
            }
        }
    }

    companion object {
        private const val TAG = "IconStore"

        /**
         * Every icon the launcher has squared off, for the life of the process.
         *
         * Process-wide rather than per-instance on purpose. The activity is recreated on a
         * configuration change, and an instance-level cache meant the new one started with
         * nothing and decoded, scaled and squared every installed app again - a second or
         * so of work on the way back in, and a second full set of icons in memory for as
         * long as the outgoing activity was still being collected.
         *
         * An eighth of the heap, and the memory-pressure callbacks still trim it.
         */
        private val bitmaps: LruCache<String, Bitmap> by lazy {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            Log.d(TAG, "Icon cache holds ${maxMemoryKb / 8}KB of ${maxMemoryKb}KB")
            object : LruCache<String, Bitmap>(maxMemoryKb / 8) {
                override fun sizeOf(key: String, bitmap: Bitmap) = bitmap.byteCount / 1024
            }
        }
    }
}
