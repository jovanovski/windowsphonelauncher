package rocks.gorjan.gokixp.wp81

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/** One story, as a tile shows it and as tapping it opens it. */
data class NewsStory(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
    /** The story's picture, if the feed offered one. Drawn behind the headline. */
    val image: String,
    /** When it was published, or 0 when the feed did not say. */
    val publishedAt: Long = 0L
)

/** A feed the user can turn on. */
data class NewsSource(val id: String, val name: String, val url: String)

/**
 * The feeds on offer.
 *
 * Public RSS rather than a news API: no key to obtain, hold or leak, no quota to run out
 * at the worst moment, and the request is identical for every user of the launcher -
 * nothing about the phone or the person holding it goes anywhere.
 *
 * Kept to outlets that have published a stable feed for years and are unlikely to move
 * it. A feed that dies simply contributes nothing; the tile carries on with the others.
 */
object NewsSources {

    val ALL: List<NewsSource> = listOf(
        NewsSource("bbc", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        NewsSource("guardian", "The Guardian", "https://www.theguardian.com/world/rss"),
        NewsSource("npr", "NPR World", "https://feeds.npr.org/1004/rss.xml"),
        NewsSource("aljazeera", "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        NewsSource("dw", "Deutsche Welle", "https://rss.dw.com/rdf/rss-en-world"),
        NewsSource("sky", "Sky News", "https://feeds.skynews.com/feeds/rss/world.xml"),
        NewsSource("verge", "The Verge", "https://www.theverge.com/rss/index.xml"),
        NewsSource("ars", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index")
    )

    /** The one that is on when the user has never said otherwise. */
    const val DEFAULT_ID = "bbc"

    /**
     * What marks an id as naming a feed the user added rather than one of these.
     *
     * The address itself is the id behind it, rather than a number counted off a list:
     * adding the same feed twice is then the same feed rather than two, and an id left
     * behind in the enabled set by a feed that was deleted cannot come back later pointing
     * at whichever feed happens to have inherited its number.
     */
    const val CUSTOM_PREFIX = "custom:"

    fun customId(url: String): String = CUSTOM_PREFIX + url.trim()

    fun byId(id: String): NewsSource? = ALL.firstOrNull { it.id == id }
}

/**
 * Reads the enabled feeds and keeps a shuffled run of stories for the News tile.
 *
 * Shuffled rather than concatenated: with three feeds on, running them in order would
 * give the tile twenty minutes of one outlet before it reached the next, which is not
 * what turning three of them on was meant to produce. Interleaved by position first -
 * everybody's top story, then everybody's second - so the front pages lead, and shuffled
 * within each round so no outlet is permanently first.
 */
class NewsFeed(
    private val onUpdated: () -> Unit,
    /**
     * What an id in the enabled set names.
     *
     * Asked of the caller rather than looked up in [NewsSources], because the list of
     * feeds is no longer fixed: the ones the user has added themselves are in settings,
     * and a feed reader that read settings to find out what a feed is would be two things.
     * The built-in ones, for a caller that has nothing of its own to add.
     */
    private val sourceById: (String) -> NewsSource? = { NewsSources.byId(it) }
) {

    @Volatile
    private var stories: List<NewsStory> = emptyList()

    /**
     * The same stories, kept as their feeds delivered them.
     *
     * The tile wants one run with everything mixed together; a reader wants each outlet's
     * own front page, in the order that outlet put it in. Both come from the one fetch.
     */
    @Volatile
    private var bySource: Map<String, List<NewsStory>> = emptyMap()

    @Volatile
    private var fetchedAt = 0L

    @Volatile
    private var fetching = false

    /** Which feeds the last fetch was for, so turning one on re-reads immediately. */
    @Volatile
    private var fetchedFor: List<String> = emptyList()

    /** Which feeds the fetch under way is reading, so a change made during one is seen. */
    @Volatile
    private var fetchingFor: List<String> = emptyList()

    /** A set asked for while a fetch was running, to be read as soon as it is over. */
    @Volatile
    private var queued: List<String>? = null

    private val main = Handler(Looper.getMainLooper())

    /** The current run of stories. Empty until the first fetch lands. */
    fun stories(): List<NewsStory> = stories

    /** Each enabled outlet's own stories, keyed by its name, in feed order. */
    fun bySource(): Map<String, List<NewsStory>> = bySource

    /** Whether a fetch is under way, so a reader can say so rather than look empty. */
    fun isFetching(): Boolean = fetching

    /**
     * Fetches, unless the same feeds were read recently enough that the answer would be
     * the same.
     *
     * The interval is deliberately long: a Start screen is glanced at, not read, and the
     * news does not turn over fast enough to justify waking the radio more often. Changing
     * which feeds are on bypasses it, because then the answer *has* changed.
     */
    fun refreshIfStale(enabled: List<String>, force: Boolean = false) {
        if (fetching) {
            // Asked for a set the fetch under way is not reading - a feed turned on while
            // the last one was still in the air. Dropping it left the new outlet unread
            // until something else happened to ask again, which in practice meant closing
            // the reader and opening it. Held instead, and read the moment this one lands.
            if (enabled != fetchingFor) queued = enabled
            return
        }
        val sources = enabled.mapNotNull { sourceById(it) }
        if (sources.isEmpty()) {
            if (stories.isNotEmpty()) {
                stories = emptyList()
                bySource = emptyMap()
                fetchedFor = emptyList()
                main.post { onUpdated() }
            }
            return
        }
        val changed = enabled != fetchedFor
        val fresh = stories.isNotEmpty() &&
            SystemClock.elapsedRealtime() - fetchedAt < REFRESH_MS
        if (!force && !changed && fresh) return

        fetching = true
        fetchingFor = enabled
        Thread {
            val gathered = sources.map { source ->
                try {
                    fetch(source)
                } catch (e: Exception) {
                    Log.w("NewsFeed", "Could not read ${source.name}", e)
                    emptyList()
                }
            }
            val merged = newestFirst(gathered)
            if (merged.isNotEmpty()) {
                stories = merged
                bySource = sources.mapIndexed { i, source ->
                    source.name to gathered.getOrElse(i) { emptyList() }
                }.filter { it.second.isNotEmpty() }.toMap()
                fetchedAt = SystemClock.elapsedRealtime()
                fetchedFor = enabled
            }
            // Cleared last, so a set asked for right at the end is queued rather than
            // racing a second fetch against this one.
            val next = queued
            queued = null
            fetching = false
            // Announced when there is something new, and when there was nothing to begin
            // with: a reader that asked and got nothing is waiting on an answer, and "the
            // feeds had nothing" is one - left unsaid it goes on saying it is reading them.
            // A failed refresh over stories already on screen says nothing, because nothing
            // about them has changed.
            if (merged.isNotEmpty() || stories.isEmpty()) main.post { onUpdated() }
            next?.let { main.post { refreshIfStale(it, force = true) } }
        }.start()
    }

    /**
     * The newest first, whoever published it.
     *
     * Sorting by time rather than interleaving by position mixes the outlets anyway - they
     * publish all day - and answers the question the run is actually for, which is what has
     * just happened rather than which paper said it. A story with no date on it goes last:
     * absent is not new.
     */
    private fun newestFirst(feeds: List<List<NewsStory>>): List<NewsStory> =
        feeds.flatten()
            .sortedWith(compareByDescending<NewsStory> { it.publishedAt }.thenBy { it.title })
            .take(MAX_STORIES)

    private fun fetch(source: NewsSource): List<NewsStory> {
        val connection = open(source.url)
        try {
            if (connection.responseCode != 200) {
                Log.w("NewsFeed", "${source.name} returned ${connection.responseCode}")
                return emptyList()
            }
            return connection.inputStream.use { parse(it, source.name) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Pulls the title, summary and link out of each entry.
     *
     * Handles RSS and Atom together: `item`/`entry` and `description`/`summary` are the
     * same idea under two names, and an Atom link carries its address in an attribute
     * rather than as text. A pull parser rather than a DOM - this wants a few short
     * strings out of a few dozen kilobytes and has no use for a tree.
     */
    private fun parse(input: java.io.InputStream, sourceName: String): List<NewsStory> {
        val items = mutableListOf<NewsStory>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var inItem = false
        var title = ""
        var summary = ""
        var link = ""
        var image = ""
        var published = ""
        var tag: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    when {
                        tag.equals("item", true) || tag.equals("entry", true) -> {
                            inItem = true
                            title = ""
                            summary = ""
                            link = ""
                            image = ""
                            published = ""
                        }
                        // Feeds attach pictures in whichever of the three ways their
                        // publisher settled on years ago. The first one found wins, since
                        // a feed that offers several offers the same picture at several
                        // sizes and the first is the one it led with.
                        inItem && image.isEmpty() && (
                            tag.equals("media:thumbnail", true) ||
                                tag.equals("media:content", true) ||
                                tag.equals("enclosure", true)
                            ) -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            val type = parser.getAttributeValue(null, "type").orEmpty()
                            val medium = parser.getAttributeValue(null, "medium").orEmpty()
                            val looksLikeImage = type.startsWith("image") ||
                                medium == "image" ||
                                tag.equals("media:thumbnail", true)
                            if (url.isNotBlank() && looksLikeImage) image = url
                        }
                        // Atom puts the address on the tag: <link href="..."/>
                        inItem && tag.equals("link", true) && link.isEmpty() -> {
                            parser.getAttributeValue(null, "href")?.let { link = it }
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (!inItem) continue
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    when {
                        tag.equals("title", true) -> title += text
                        tag.equals("description", true) || tag.equals("summary", true) ->
                            summary += text
                        tag.equals("link", true) -> link += text
                        // RSS says pubDate, Atom says published or updated, and the older
                        // feeds say dc:date. All four mean the same thing.
                        published.isEmpty() && (
                            tag.equals("pubDate", true) ||
                                tag.equals("published", true) ||
                                tag.equals("updated", true) ||
                                tag.equals("dc:date", true)
                            ) -> published = text
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                        inItem = false
                        if (title.isNotBlank()) {
                            items.add(
                                NewsStory(
                                    title = clean(title),
                                    summary = clean(summary),
                                    link = link.trim(),
                                    source = sourceName,
                                    image = image.trim(),
                                    publishedAt = parseDate(published)
                                )
                            )
                        }
                        if (items.size >= PER_SOURCE) return items
                    }
                    tag = null
                }
            }
        }
        return items
    }

    /**
     * A feed's idea of a date, as milliseconds.
     *
     * Tried against the handful of shapes feeds actually use rather than one: RFC-822 with
     * and without seconds, with a zone name or an offset, and ISO-8601 for the Atom feeds.
     * Anything else is treated as undated, which puts it at the back rather than at the
     * front with a date of zero pretending to be 1970.
     */
    private fun parseDate(value: String): Long {
        val text = value.trim()
        if (text.isEmpty()) return 0L
        for (pattern in DATE_PATTERNS) {
            try {
                val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                format.isLenient = true
                return format.parse(text)?.time ?: continue
            } catch (e: Exception) {
                // Next shape.
            }
        }
        Log.w("NewsFeed", "Unreadable date: $text")
        return 0L
    }

    companion object {

        /**
         * Feeds put markup in their summaries; a tile shows text.
         *
         * On the companion because it is not only the stories that arrive written for a
         * web page: so does the title a feed calls itself by, and the one thing worse than
         * a section named "Al Jazeera &#8211; Breaking News" is two answers to what that
         * means.
         */
        fun clean(value: String): String =
            numbered(value.replace(Regex("<[^>]*>"), " "))
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /**
         * The characters a feed writes as a number: `&#8211;` for a dash, `&#8217;` for an
         * apostrophe, and the same again in hex. Left alone if the number is not one.
         */
        private fun numbered(value: String): String = NUMBERED.replace(value) { match ->
            val hex = match.groupValues[1].isNotEmpty()
            val code = match.groupValues[2].toIntOrNull(if (hex) 16 else 10)
            if (code == null || code !in 1..0x10FFFF) match.value
            else String(Character.toChars(code))
        }

        private val NUMBERED = Regex("&#(x?)([0-9a-fA-F]+);")

        private const val USER_AGENT = "Mozilla/5.0 (Android) GokiXP live tile"
        private const val TIMEOUT_MS = 10_000

        /** How many moves of an address to follow before calling it a loop. */
        private const val MAX_REDIRECTS = 3

        /**
         * A connection to [url], with the redirects followed that Java will not follow.
         *
         * HttpURLConnection follows a redirect only while the protocol stays the same, and
         * a feed address is as likely as not to be http pointing at https - either because
         * the publisher moved years ago and left the old address working, or because that
         * is how somebody typed it. Left to itself the reader sees a 301 and reports the
         * feed as unreadable, which is the one thing it is not.
         */
        fun open(url: String): HttpURLConnection {
            var address = URL(url)
            var hops = 0
            while (true) {
                val connection = (address.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    // Some feeds refuse the default Java agent outright.
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                if (connection.responseCode !in 300..399 || hops++ >= MAX_REDIRECTS) {
                    return connection
                }
                // Relative locations are legal, and some feeds send them.
                val moved = connection.getHeaderField("Location")
                if (moved.isNullOrBlank()) return connection
                connection.disconnect()
                address = URL(address, moved)
            }
        }

        /**
         * How deep into each feed to read.
         *
         * A section is a front page and is meant to be scrolled; a dozen stories was a
         * glance. Feeds run to a few dozen items and weigh tens of kilobytes, so reading
         * the lot costs nothing the request has not already paid for - the rows are what
         * cost, and those are built a screenful at a time as the reader gets to them.
         */
        private const val PER_SOURCE = 60

        /** And how long the newest-first run is. That one is a summary, not a section. */
        private const val MAX_STORIES = 20

        private val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ss"
        )

        private const val REFRESH_MS = 30 * 60 * 1000L
    }
}

/**
 * Reads an address someone has typed, to say whether there is a feed at it.
 *
 * A feed the user adds is the one part of the News app where the address is not known to
 * be good, and a bad one saved is a section that is silently never there - the reader
 * builds its sections out of what came back, so a feed that answers with nothing simply
 * does not appear, with no line anywhere saying why. So it is read once on the way in.
 *
 * The feed's own title is what it ends up called: an outlet has already named itself, and
 * asking somebody to name a newspaper is asking them to do the feed's typing.
 */
object NewsFeedCheck {

    /** What came of the reading. */
    sealed class Result {
        /** There is a feed at [url], and it calls itself [name]. */
        data class Feed(val name: String, val url: String) : Result()

        /** There is not, and [reason] is the line to put in front of the user. */
        data class NotAFeed(val reason: String) : Result()
    }

    /**
     * Reads [address] on a thread, and answers on the main one.
     *
     * The address it answers with is the one that finally replied rather than the one
     * typed, so a feed that has moved is stored where it now lives and not re-walked
     * through its own redirect every half hour for the life of the phone.
     */
    fun check(address: String, onDone: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        fun answer(result: Result) { main.post { onDone(result) } }

        val url = normalise(address)
        if (url.isEmpty()) {
            answer(Result.NotAFeed("type the address of a feed"))
            return
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = NewsFeed.open(url)
                val code = connection.responseCode
                if (code != 200) {
                    answer(Result.NotAFeed("that address answered $code"))
                    return@Thread
                }
                val (title, stories) = connection.inputStream.use { inspect(it) }
                if (stories == 0) {
                    answer(Result.NotAFeed("nothing at that address reads as a feed"))
                    return@Thread
                }
                answer(Result.Feed(name(title, url), connection.url.toString()))
            } catch (e: Exception) {
                Log.w("NewsFeedCheck", "Could not read $url", e)
                answer(Result.NotAFeed("could not read that address"))
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    /** What was typed, as an address: "bbc.co.uk/rss" is meant as https. */
    fun normalise(address: String): String {
        val typed = address.trim()
        if (typed.isEmpty()) return ""
        return if (typed.contains("://")) typed else "https://$typed"
    }

    /** The site an address belongs to, which is how a feed is shown under its name. */
    fun host(url: String): String = try {
        URL(url).host.orEmpty().removePrefix("www.").ifBlank { url }
    } catch (e: Exception) {
        url
    }

    /**
     * The feed's own title, and how many stories are in it.
     *
     * Only as far as the first few: the question is whether this document is a feed, and a
     * document that has produced three stories is one - reading the other fifty to find out
     * again would be the whole fetch done twice. Titles inside a story are passed over; the
     * one wanted is the channel's, which RSS and Atom both put before the stories.
     */
    private fun inspect(input: java.io.InputStream): Pair<String, Int> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var title = ""
        var named = false
        var stories = 0
        var inItem = false
        var tag: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag.equals("item", true) || tag.equals("entry", true)) {
                        inItem = true
                        stories++
                        if (named && stories >= ENOUGH) return title to stories
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (!inItem && !named && tag.equals("title", true)) {
                        title += parser.text?.trim().orEmpty()
                    }
                }

                XmlPullParser.END_TAG -> {
                    when {
                        parser.name.equals("item", true) ||
                            parser.name.equals("entry", true) -> inItem = false
                        // The channel's title is the first one closed outside a story.
                        // Anything after it - a picture's caption, most often - is a title
                        // for something that is not the feed.
                        !inItem && parser.name.equals("title", true) &&
                            title.isNotBlank() -> named = true
                    }
                    tag = null
                }
            }
        }
        return title to stories
    }

    /**
     * What to call the feed.
     *
     * A feed's title is written for a list of search results rather than for the top of a
     * section: "BBC News - World" and "Ars Technica - All content" are each a name followed
     * by a note saying which of that outlet's feeds this one is. The name is the part in
     * front of the dash. Where there is nothing usable in front of it, or no title at all,
     * the site it came from is a better answer than a blank.
     */
    private fun name(title: String, url: String): String {
        val text = NewsFeed.clean(title)
        val outlet = when {
            // "Ars Technica - All content": the outlet, and then which of its feeds this
            // is. Nearly every title with a dash in it is written that way round.
            text.contains(" - ") -> text.substringBefore(" - ")
            text.contains(" – ") -> text.substringBefore(" – ")
            text.contains(" — ") -> text.substringBefore(" — ")
            // "World news | The Guardian": the same two things the other way about, which
            // is what a bar means nearly everywhere a feed uses one.
            text.contains(" | ") -> text.substringAfterLast(" | ")
            else -> text
        }.trim()
        val chosen = outlet.ifBlank { text }.ifBlank { host(url) }
        return if (chosen.length <= MAX_NAME) chosen
        else chosen.take(MAX_NAME).trimEnd() + "…"
    }

    /** How many stories are enough to call something a feed. */
    private const val ENOUGH = 3

    /**
     * How long a name may be.
     *
     * It is a section heading on a panorama, where it is set large and lowercase, and a
     * heading that runs past the edge of the screen takes the next section's name with it.
     */
    private const val MAX_NAME = 28
}
