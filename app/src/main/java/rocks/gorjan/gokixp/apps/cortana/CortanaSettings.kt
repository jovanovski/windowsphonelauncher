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
     * Which of the two shapes of request a service reads. See [CortanaAgent.bodyFor].
     */
    enum class Wire { OPENAI, ANTHROPIC }

    /**
     * Which model the sparkle asks.
     *
     * Copilot by default, for the same reason the engine defaults to Bing: it is the thing
     * Cortana actually became.
     *
     * Each of these can be reached two ways, and which one happens depends on whether the
     * user has given this app a key of their own. Without one, the question leaves as a
     * URL with the question already in it, so the model is answering by the time the page
     * has finished opening - a chat window that opened empty and left the user to paste
     * their own question back in would make the button a bookmark rather than a way of
     * asking something. With one, the question never leaves: it is posted to the service's
     * own API and the answer arrives in a conversation on this screen, which is the thing
     * Cortana actually did and the only version of it that can carry a follow-up.
     *
     * The chat and model addresses are held here rather than in the network file because
     * they are facts about the service in the same way its name is, and one of them is not
     * a constant at all - see [chatEndpoint].
     */
    enum class Agent(
        val label: String,
        private val template: String,
        /** Which body shape this one reads. */
        val wire: Wire,
        /** Where a conversation is posted. Null where it has to be built - see [chatEndpoint]. */
        private val chatUrl: String?,
        /** Where the list of models comes from, on the same terms. */
        private val modelsUrl: String?,
        /** What it is asked before the user has chosen otherwise. */
        val defaultModel: String,
        /** Where a person goes to get a key, for the line on the settings page that offers. */
        val keyUrl: String
    ) {
        /**
         * Copilot, by way of the service underneath it.
         *
         * Copilot itself has no key to paste and never has: Microsoft publishes an API for
         * managing Copilot seats and none at all for talking to it. What Copilot is built
         * on is Microsoft Foundry, which does take a key - so that is what this one asks,
         * and the models behind it are the same models. The cost is one extra thing to
         * type, because a Foundry key belongs to a resource the user named themselves and
         * the address cannot be known without it. See [chatEndpoint] and
         * [getFoundryResource].
         */
        COPILOT(
            "Copilot", "https://copilot.microsoft.com/?q=", Wire.OPENAI,
            chatUrl = null, modelsUrl = null,
            defaultModel = "gpt-5.6-sol",
            keyUrl = "https://ai.azure.com/"
        ),
        CHATGPT(
            "ChatGPT", "https://chatgpt.com/?q=", Wire.OPENAI,
            chatUrl = "https://api.openai.com/v1/chat/completions",
            modelsUrl = "https://api.openai.com/v1/models",
            defaultModel = "gpt-5.6-sol",
            keyUrl = "https://platform.openai.com/api-keys"
        ),
        CLAUDE(
            "Claude", "https://claude.ai/new?q=", Wire.ANTHROPIC,
            chatUrl = "https://api.anthropic.com/v1/messages",
            modelsUrl = "https://api.anthropic.com/v1/models",
            defaultModel = "claude-opus-5",
            keyUrl = "https://console.anthropic.com/settings/keys"
        ),
        GROK(
            "Grok", "https://grok.com/?q=", Wire.OPENAI,
            chatUrl = "https://api.x.ai/v1/chat/completions",
            modelsUrl = "https://api.x.ai/v1/models",
            defaultModel = "grok-4.6",
            keyUrl = "https://console.x.ai/"
        );

        fun urlFor(query: String): String = template + encode(query)

        /**
         * Where a conversation with this one is posted, or null if it cannot be worked out.
         *
         * Fixed for three of them. Foundry's is a per-customer address built around the name
         * of a resource in somebody's Azure account, so a phone that has not been told that
         * name has nowhere to send anything - which is a missing setting rather than an
         * error, and is why this is nullable rather than throwing.
         */
        fun chatEndpoint(foundryResource: String): String? =
            chatUrl ?: foundryHost(foundryResource)?.plus("/openai/v1/chat/completions")

        /** The same, for the list of models. */
        fun modelsEndpoint(foundryResource: String): String? =
            modelsUrl ?: foundryHost(foundryResource)?.plus("/openai/v1/models")

        /**
         * The headers that say who is asking.
         *
         * Three different answers to the same question, which is the clearest sign that
         * "OpenAI-compatible" is a convention rather than a standard: OpenAI and xAI take a
         * bearer token, Anthropic takes a named key alongside the version of its API that
         * the body is written against, and Foundry takes a header of its own. The Anthropic
         * version is pinned deliberately - it is the promise that the shape parsed in
         * [CortanaAgent.deltaIn] will not change underneath this app.
         */
        fun authHeaders(key: String): List<Pair<String, String>> = when (this) {
            CLAUDE -> listOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)
            COPILOT -> listOf("api-key" to key)
            else -> listOf("Authorization" to "Bearer $key")
        }

        /** A Foundry resource name turned into the host it stands for, if there is one. */
        private fun foundryHost(resource: String): String? = resource.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "https://$it.services.ai.azure.com" }

        private companion object {
            /** The version of Anthropic's API this app's parsing is written against. */
            const val ANTHROPIC_VERSION = "2023-06-01"
        }
    }

    fun getEngine(): Engine = read(KEY_ENGINE, Engine.entries, Engine.BING)

    fun setEngine(engine: Engine) = prefs.edit { putString(KEY_ENGINE, engine.name) }

    fun getAgent(): Agent = read(KEY_AGENT, Agent.entries, Agent.COPILOT)

    fun setAgent(agent: Agent) = prefs.edit { putString(KEY_AGENT, agent.name) }

    /**
     * The user's own key for one of the four services, or nothing.
     *
     * Per service rather than one key for whichever is current, because they are four
     * unrelated accounts: somebody who pays for two of them and switches between them would
     * otherwise be re-pasting a key every time they changed their mind, and the one being
     * overwritten is not recoverable from anywhere on the phone.
     *
     * Nothing ships in this file, and that is not a corner being cut. A key built into a
     * launcher is one account answering for every install, against one rate limit and one
     * bill, sitting in a public repository for anyone to lift - the same reason the
     * keyboard's GIF search asks for one of its own. It is also what makes the feature
     * honest: the conversation is on the user's account, and nothing they type goes anywhere
     * this app can see.
     *
     * Stored in the launcher's own preferences alongside everything else the shell
     * remembers, which means it is readable by anything that can read this app's data
     * directory and is carried by a settings export. On a phone that is the same protection
     * the account's own app gets; it is worth knowing about rather than worth pretending
     * otherwise, and the settings page says so.
     */
    fun getKey(agent: Agent): String = prefs.getString(KEY_KEY + agent.name, "").orEmpty()

    fun setKey(agent: Agent, key: String) = prefs.edit { putString(KEY_KEY + agent.name, key.trim()) }

    /** Whether the chosen service has everything it needs to be talked to. */
    fun canConverse(agent: Agent = getAgent()): Boolean =
        getKey(agent).isNotEmpty() && agent.chatEndpoint(getFoundryResource()) != null

    /**
     * Which model of the chosen service's answers.
     *
     * Per service, like the key, and for the same reason - "the model" is not one setting
     * with four possible values, it is four settings. Kept as text rather than as a choice
     * out of a list this app holds, because the list belongs to the service and changes
     * several times a year: see [CortanaAgent.models], which asks rather than assumes.
     */
    fun getModel(agent: Agent): String =
        prefs.getString(KEY_MODEL + agent.name, null)?.takeIf { it.isNotBlank() }
            ?: agent.defaultModel

    fun setModel(agent: Agent, model: String) =
        prefs.edit { putString(KEY_MODEL + agent.name, model.trim()) }

    /**
     * The name of the Microsoft Foundry resource behind the Copilot setting.
     *
     * One string, and only that one service needs it: a Foundry key is issued against a
     * resource the customer created and named, and the address to send to is built out of
     * that name. See [Agent.COPILOT].
     */
    fun getFoundryResource(): String = prefs.getString(KEY_FOUNDRY, "").orEmpty()

    fun setFoundryResource(resource: String) =
        prefs.edit { putString(KEY_FOUNDRY, resource.trim()) }

    /**
     * Whether a question for the model is answered here or handed to the model's own app.
     *
     * Only asked once there is a key, because without one there is nothing to decide - the
     * hand-off is the only thing that can happen. With one, both are reasonable and they are
     * genuinely different things: the conversation on this screen is Cortana, remembers what
     * was said a moment ago and costs the user's own credit; the hand-off lands in an app
     * that is already signed in, with the history and the extras that come with it.
     *
     * Defaults to answering here. Somebody who has gone to the trouble of pasting a key has
     * said what they want the button to do.
     */
    fun getAgentAnswersHere(): Boolean = prefs.getBoolean(KEY_ANSWER_HERE, true)

    fun setAgentAnswersHere(here: Boolean) = prefs.edit { putBoolean(KEY_ANSWER_HERE, here) }

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

        /** Both of these are prefixes: the service's own name is put on the end. */
        const val KEY_KEY = "cortana_key_"
        const val KEY_MODEL = "cortana_model_"

        const val KEY_FOUNDRY = "cortana_foundry_resource"
        const val KEY_ANSWER_HERE = "cortana_answer_here"
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
