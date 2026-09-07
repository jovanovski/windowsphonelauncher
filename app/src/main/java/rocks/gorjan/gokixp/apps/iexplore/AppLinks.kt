package rocks.gorjan.gokixp.apps.iexplore

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * An app on this phone that claims an address the browser has landed on.
 *
 * [label] is what that app calls itself, so an offer can say "open in Spotify" rather than
 * "open in app". Both it and [packages] hold more than one entry only where several apps
 * claim the same address, which is a chooser rather than an app: there is no single name
 * to write into the offer then, and [label] is null.
 */
internal data class AppLink(val packages: List<String>, val label: String?)

/**
 * Which addresses an app on this phone would rather have than a browser would.
 *
 * An open.spotify.com link where Spotify is installed, a youtube.com link where YouTube
 * is. The browser offers those to the app instead of keeping them - see
 * MetroIEApp.offerApp - and this works out whether there is anything to offer, and to
 * whom.
 *
 * The obvious way to ask is useless on its own. `queryIntentActivities` on an ACTION_VIEW
 * of the address is answered by every browser on the phone, and by this launcher, which
 * registers itself as one so that a link tapped anywhere lands in Internet Explorer (see
 * the manifest). Asked that way every address on the web has an app that opens it, the
 * offer is never off, and half of what it offers is the browser the page is already in.
 *
 * What is wanted is the apps that claim *this* address rather than addresses in general,
 * which is every handler less the ones that also answer a bare `http:` - the definition of
 * a browser, and the one the platform uses itself.
 *
 * Everything here is called from the main thread except [lookUp], which is the part that
 * walks the package manager.
 */
internal class AppLinks(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * What has already been worked out, keyed by the whole address rather than the host:
     * an app claims paths and not merely sites - youtube.com/watch is a video and
     * youtube.com/about is a web page - so the host alone is not the question that was
     * asked.
     *
     * A null value is an answer as much as an [AppLink] is. It says this address was
     * looked at and nothing wanted it, which is what keeps a page that reloads itself, or
     * that rewrites its own address as it is scrolled, from asking the package manager
     * again every time it does so. An app installed while the browser is open is therefore
     * not noticed on a page already looked at, only on the next fresh one - which is one
     * page load away, and not worth watching the package manager for.
     *
     * Least recently used first out: a browsing session's worth of addresses is small, but
     * a browser left open on a page that navigates itself is not.
     */
    private val known = object : LinkedHashMap<String, AppLink?>(MAX_KNOWN, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppLink?>) =
            size > MAX_KNOWN
    }

    /** Addresses being looked at right now, so two page events do not ask twice. */
    private val asking = mutableSetOf<String>()

    /**
     * Every app that answers a bare `http:`, which is what a browser is and all a browser
     * is: an app that claims the scheme with no host in particular. This launcher is one
     * of them.
     *
     * Worked out once and kept for as long as the browser window is open. It is a fact
     * about what is installed rather than about the page, and nobody installs a browser
     * from inside another browser without leaving it - which closes this window and takes
     * the answer with it. The cost of being one session behind is an offer to open a page
     * in a browser, which is the one thing the offer is trying not to be, so it is worth
     * being slightly stale about rather than asking on every page that lands.
     */
    private val browsers: Set<String> by lazy {
        // `http:` and nothing after it. A filter that names hosts cannot match an address
        // that has none, so what answers this is exactly the apps that claim the scheme
        // itself - and an app that claims the scheme itself is a browser.
        val bare = browsable(Uri.fromParts("http", "", null))
        context.packageManager
            .queryIntentActivities(bare, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    /**
     * What is already known about [url], for a caller that cannot wait - the command list
     * behind the dots is built the moment they are tapped.
     *
     * Null both for an address nothing claims and for one that has not been looked at yet.
     * The two come to the same thing here: no command.
     */
    fun cached(url: String): AppLink? = known[url]

    /**
     * Works out who claims [url] and hands the answer back on the main thread.
     *
     * Off the main thread because it is a walk through every app on the phone in another
     * process, twice over on the first call, and it runs on every page that lands.
     */
    fun resolve(url: String, then: (AppLink?) -> Unit) {
        if (known.containsKey(url)) {
            then(known[url])
            return
        }
        if (!asking.add(url)) return
        Thread {
            val link = lookUp(url)
            main.post {
                asking.remove(url)
                known[url] = link
                then(link)
            }
        }.start()
    }

    /**
     * Hands [url] to the app the offer was made about. False where nothing took it.
     *
     * [Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER] is Android's own way of saying "only if a
     * real app wants this": with it, an address that only browsers claim throws
     * [ActivityNotFoundException] rather than opening in one. It is the whole safeguard
     * against the offer landing back where it started - this launcher is a browser, and is
     * the default one on the phones this runs on, so without the flag "open in Spotify"
     * on a phone that has just had Spotify uninstalled would open the page it is already
     * showing in a second tab of the browser making the offer.
     *
     * The flag arrived in Android 11 and this app runs on 10, where the same promise has
     * to be made by hand: the intent is addressed to the package the offer named, which
     * rules out every other app rather than every browser. Where several claim the
     * address there is no chooser to raise that the flag is not doing the filtering for,
     * so it goes to the first - the order the package manager returned them in, which is
     * its own idea of which is preferred.
     */
    fun open(link: AppLink, url: String): Boolean {
        val intent = browsable(Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        } else {
            intent.setPackage(link.packages.firstOrNull() ?: return false)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // The app went between the offer being made and it being taken, or it never
            // really claimed the address and only the browsers did. Either way the caller
            // says so rather than the browser silently doing nothing.
            Log.w(TAG, "Nothing took $url", e)
            false
        }
    }

    /**
     * Who claims [url], or null where nobody but a browser does. Off the main thread.
     */
    private fun lookUp(url: String): AppLink? {
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            // Only the web is offered. Everything else a page can hold - tel:, mailto:,
            // intent:// - is already handed straight to whatever owns it and never
            // becomes a page in the first place. See MetroIEApp.handleScheme.
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null

            val pm = context.packageManager
            // MATCH_DEFAULT_ONLY, because the question is what the phone would actually
            // start if this address were followed - not what could conceivably take it.
            val claimants = pm.queryIntentActivities(
                browsable(uri), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
                // This launcher is named as well as being in [browsers], where it already
                // is: the offer must never be "open this page in the browser you are
                // reading it in", and that must not rest on a manifest entry somewhere
                // else staying the way it is today.
                .filter { it != context.packageName && it !in browsers }
                .distinct()
            if (claimants.isEmpty()) return null
            // Several, and there is no name to put on the offer: what tapping it gets is
            // the phone's own chooser, and "open in Spotify" would be a promise about
            // which app is coming that this is in no position to keep.
            if (claimants.size > 1) return AppLink(claimants, null)
            return AppLink(claimants, labelOf(claimants.first()))
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask who opens $url", e)
            return null
        }
    }

    /** What an app calls itself. Null where it will not say, and then the offer will not. */
    private fun labelOf(packageName: String): String? = try {
        val pm = context.packageManager
        // The application's label rather than the resolved activity's. They are usually
        // the same, but an activity that has one of its own has it for its own window
        // title - "Open link", "Share to Instagram" - which is not the app's name.
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            .takeIf { it.isNotBlank() }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * An ACTION_VIEW of [uri] shaped the way a link followed on a page is shaped.
     *
     * BROWSABLE is the category that says an address came off the web rather than from
     * somewhere the phone already trusts, and it is what an app that claims web addresses
     * declares. Asking without it also turns up the apps that will accept an address
     * handed to them from elsewhere, which is not a claim on this link.
     */
    private fun browsable(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    companion object {
        private const val TAG = "AppLinks"

        /**
         * How many addresses are remembered. See [known].
         *
         * Larger than a session of reading gets through, and small enough that a page
         * left open rewriting its own address all afternoon cannot grow it without bound.
         */
        private const val MAX_KNOWN = 100
    }
}
