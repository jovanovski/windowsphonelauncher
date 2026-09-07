package rocks.gorjan.gokixp.apps.cortana

import android.content.Context
import androidx.core.content.edit
import rocks.gorjan.gokixp.MainActivity
import java.net.URLEncoder

/**
 * Where a typed question is sent, and in what.
 *
 * Three questions, and they are genuinely three: which search engine answers the
 * magnifier, which model answers the sparkle, and whether either answer arrives in the
 * phone's own browser or in the one Android would have used. Somebody who wants Google
 * results read in Internet Explorer is not asking anything strange, and folding any two
 * of these into one switch would make that combination unreachable.
 *
 * Kept in the launcher's own preference file alongside everything else the shell
 * remembers, rather than in a file of its own: this is one more thing the phone knows
 * about itself, and a settings backup that carried the accent but not the search engine
 * would be a restore that quietly changed where the search key goes.
 */
class CortanaSettings(context: Context) {

    private val prefs =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Where the magnifier sends what was typed.
     *
     * Bing by default, which is not a preference so much as a fact about what this app is:
     * Cortana was Bing with a name and a personality, and a Cortana that opened Google
     * would be a costume rather than the thing itself. The other two are here because the
     * phone this runs on is not actually a Lumia and its owner may well have opinions.
     */
    enum class Engine(val label: String, private val template: String) {
        BING("Bing", "https://www.bing.com/search?q="),
        GOOGLE("Google", "https://www.google.com/search?q="),
        DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=");

        fun urlFor(query: String): String = template + encode(query)
    }

    /**
     * Which model the sparkle asks.
     *
     * Copilot by default, for the same reason the engine defaults to Bing: it is the thing
     * Cortana actually became. Each of these takes the question in the URL, so the model
     * is already answering by the time the page has finished opening - a chat window that
     * opened empty and left the user to paste their own question back in would make the
     * button a bookmark rather than a way of asking something.
     */
    enum class Agent(val label: String, private val template: String) {
        COPILOT("Copilot", "https://copilot.microsoft.com/?q="),
        CHATGPT("ChatGPT", "https://chatgpt.com/?q="),
        CLAUDE("Claude", "https://claude.ai/new?q="),
        GROK("Grok", "https://grok.com/?q=");

        fun urlFor(query: String): String = template + encode(query)
    }

    fun getEngine(): Engine = read(KEY_ENGINE, Engine.entries, Engine.BING)

    fun setEngine(engine: Engine) = prefs.edit { putString(KEY_ENGINE, engine.name) }

    fun getAgent(): Agent = read(KEY_AGENT, Agent.entries, Agent.COPILOT)

    fun setAgent(agent: Agent) = prefs.edit { putString(KEY_AGENT, agent.name) }

    /**
     * Whether a page of *search results* opens in the phone's browser or is handed to
     * Android.
     *
     * Internet Explorer by default. A result that opens inside the launcher stays inside
     * the launcher - back returns to Cortana, and to Start after that - where handing it
     * out lands the user in Chrome with the phone left somewhere behind it. That is the
     * right default for a shell that is pretending to be a whole phone, and the wrong one
     * for anybody who signs into things in their real browser, which is why it is a switch.
     *
     * Deliberately not the shell's own "open links in Internet Explorer" setting. That one
     * is about links arriving from elsewhere - a tapped URL in another app - and answering
     * both questions with one switch would mean somebody who wants their mail's links in
     * Chrome cannot also have their own searches in IE.
     *
     * Search only, and this is why there is no matching setting for the models: a question
     * for Copilot has to leave as an intent, so that Android can give it to the Copilot app
     * if one is installed. Answering it in a WebView would take the one route that is
     * guaranteed to land in a signed-out browser page - see [CortanaApp.ask].
     */
    fun getSearchOpensInIe(): Boolean = prefs.getBoolean(KEY_OPEN_IN_IE, true)

    fun setSearchOpensInIe(inIe: Boolean) = prefs.edit { putBoolean(KEY_OPEN_IN_IE, inIe) }

    /**
     * Reads a stored enum by name, falling back where the name is not one this build has.
     *
     * By name rather than by ordinal: an engine added to the middle of the list later would
     * otherwise silently reassign everybody's saved choice to its neighbour.
     */
    private fun <T : Enum<T>> read(key: String, values: List<T>, fallback: T): T {
        val stored = prefs.getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }

    private companion object {
        const val KEY_ENGINE = "cortana_engine"
        const val KEY_AGENT = "cortana_agent"
        const val KEY_OPEN_IN_IE = "cortana_open_in_ie"
    }
}

/** Shared by both templates above: a question is one query parameter, so it is escaped. */
private fun encode(query: String): String = URLEncoder.encode(query, "UTF-8")

/**
 * What Cortana says when she is opened.
 *
 * The phone drew a new one each time rather than one fixed line, which is most of what
 * made the screen feel like something rather than a search box: a greeting that is the
 * same every time stops being read after the second day, and one that is not makes the
 * user glance at it. That is the whole trick, and it is worth copying exactly.
 *
 * Two kinds. Some are a salutation with the user's name over a question - "Hi, Darren!" /
 * "How can I help?" - which is the shape the phone is remembered for and the one on the
 * screenshot this was drawn from. The rest are a single line, because a greeting that
 * always found room for the name would wear it out; the phone mixed the two.
 *
 * The lines are the ones Cortana actually used, as far as they can be recovered - the
 * strings themselves are long gone with the service, so this is the set the community
 * documented rather than a resource file lifted off a phone.
 */
object CortanaGreetings {

    /**
     * A greeting, already resolved into the one or two lines it is drawn as.
     *
     * The name is substituted here rather than at draw time so that the view has nothing
     * to know about who the user is.
     */
    data class Greeting(val salutation: String?, val question: String)

    /** With the name in it. `%s` is whatever the phone has been told to call its owner. */
    private val WITH_NAME = listOf(
        "Hi, %s!" to "How can I help?",
        "Hey, %s!" to "What's up?",
        "Hi, %s!" to "What can I do for you?",
        "Hello, %s!" to "What are you looking for?",
        "Yay, it's %s!" to "How can I help?",
        "Hi, %s!" to "What's on your mind?"
    )

    /** Without it. Shorter, and the ones that sound like an assistant rather than a host. */
    private val WITHOUT_NAME = listOf(
        "How can I help?",
        "What can I do for you?",
        "What's on your mind?",
        "Ready when you are.",
        "I'm all ears.",
        "Ask me anything.",
        "Go ahead, I'm listening.",
        "At your service.",
        "Let's get started.",
        "What would you like to know?"
    )

    /**
     * One at random, addressed to [name].
     *
     * Named ones are drawn a little more often than not, which is the balance the phone
     * struck: the name is the reason the screen feels addressed to somebody, and a two in
     * three chance of seeing it is often enough to be the thing you remember and rare
     * enough that it never reads as a form letter.
     *
     * A phone that has not been told a name has nothing to be friendly with, so it takes
     * the plain set - "Hi, User!" is worse than no salutation at all.
     */
    fun random(name: String?): Greeting {
        val usable = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("User", true) }
        if (usable != null && Math.random() < NAMED_SHARE) {
            val (salutation, question) = WITH_NAME.random()
            return Greeting(String.format(salutation, usable), question)
        }
        return Greeting(null, WITHOUT_NAME.random())
    }

    /** How often the name is used, where there is one. See [random]. */
    private const val NAMED_SHARE = 0.66
}
