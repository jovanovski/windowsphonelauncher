package rocks.gorjan.gokixp.apps.cortana

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import rocks.gorjan.gokixp.MainActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The half of Cortana that was a service, rebuilt out of somebody else's model.
 *
 * Cortana was never a program on the phone. The ring and the greeting were, and everything
 * behind them - the understanding, the answer, the follow-up that knew what "he" referred
 * to - happened in a data centre that has been switched off for years. What this file does
 * is put a different data centre behind the same ring, on the user's own account, so that
 * the screen can do the one thing it was for: be asked something, answer, and be asked
 * about the answer.
 *
 * Four services, and no key for any of them ships with this app - see [CortanaSettings.
 * getKey] for why that is not a shortcut being taken. Between them they speak two shapes of
 * request. ChatGPT, Grok and Microsoft's Foundry all take OpenAI's `chat/completions` body,
 * which is the closest thing this field has to a lingua franca; Claude takes Anthropic's
 * `messages` body, which differs in three ways that matter here and are handled in
 * [bodyFor]. There is no client library for either: both are one POST with a JSON body, and
 * a launcher that pulled in two vendor SDKs to send two POSTs would be carrying several
 * megabytes to save about forty lines.
 *
 * Streamed, on the same terms the services offer it - a line of server-sent events, each
 * carrying the next few characters. That is not a flourish. An answer of any length takes
 * several seconds to finish and about three hundred milliseconds to *start*, and a bubble
 * that fills in as it arrives is the difference between an assistant thinking and an app
 * that has hung. Cortana spoke her answers as she had them, which is the same thing in the
 * medium she had.
 *
 * Everything here runs on a small pool and hands its results back on the main thread, like
 * [rocks.gorjan.gokixp.wp81.keyboard.GifSearch] and [rocks.gorjan.gokixp.wp81.NewsFeed]
 * before it. There is no coroutine machinery in this project and one file is not the place
 * to introduce it.
 */
internal object CortanaAgent {

    /** One thing said, by one of the two of them. The whole of what a conversation is. */
    data class Turn(val mine: Boolean, val text: String)

    /**
     * What the screen wants to be told while an answer is arriving.
     *
     * Three things, and every one of them lands on the main thread: the next few characters,
     * the fact that there are no more, and the fact that there will not be any. A stream
     * that fails halfway has already delivered text, so [onFailed] does not mean the bubble
     * is empty - see [CortanaChatView.Hers.fail], which keeps what arrived and says what
     * went wrong underneath it.
     */
    interface Listener {
        fun onDelta(text: String)
        fun onDone()
        fun onFailed(message: String)
    }

    /**
     * A question in flight, and the only thing that can stop one.
     *
     * Held by the screen so that leaving the conversation, or asking something else before
     * the last answer finished, does not leave a stream writing into a bubble that is no
     * longer there. Cancelling does two things because one is not enough: the flag stops
     * the loop between events, and the disconnect is what breaks a read that is sitting
     * waiting for the next one. The disconnect goes on a thread of its own because closing
     * a socket is network work and the main thread is not allowed to do any.
     */
    class Ask internal constructor() {
        @Volatile
        internal var cancelled = false

        @Volatile
        internal var connection: HttpURLConnection? = null

        fun cancel() {
            if (cancelled) return
            cancelled = true
            val open = connection ?: return
            connection = null
            Thread { runCatching { open.disconnect() } }.start()
        }
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Asks whichever service is configured, with the whole conversation behind the question.
     *
     * The history is sent entire, every time. That is how a model is given a memory: it has
     * none of its own between requests, and the follow-up that Cortana was known for -
     * asking who somebody is and then asking how old *he* is - only works because the first
     * exchange is still in the second request. It is also why a long conversation costs more
     * than a short one, which is the user's own account being spent and so is said plainly
     * on the settings page.
     */
    fun ask(context: Context, history: List<Turn>, listener: Listener): Ask {
        val settings = CortanaSettings(context)
        val agent = settings.getAgent()
        val key = settings.getKey(agent)
        val model = settings.getModel(agent)
        val endpoint = agent.chatEndpoint(settings.getFoundryResource())
        val persona = personaFor(context)
        val ask = Ask()

        if (key.isEmpty() || endpoint == null) {
            // Not reachable from the button - the screen checks before it opens a
            // conversation at all - but a stream that started without a key would fail
            // forty characters later with the service's own words for it, which are worse.
            main.post { listener.onFailed("I need a ${agent.label} key first. It's in settings.") }
            return ask
        }

        executor.execute {
            try {
                stream(agent, endpoint, key, model, persona, history, ask, listener)
            } catch (e: Exception) {
                // A cancelled stream throws on the way out, because cancelling *is* closing
                // the socket underneath a blocked read. That is the asked-for outcome and
                // not something to put on screen.
                if (!ask.cancelled) {
                    main.post { listener.onFailed(reachFailure(agent, e)) }
                }
            } finally {
                ask.connection = null
            }
        }
        return ask
    }

    /** Opens the connection, writes the question, and reads the answer as it comes. */
    private fun stream(
        agent: CortanaSettings.Agent,
        endpoint: String,
        key: String,
        model: String,
        persona: String,
        history: List<Turn>,
        ask: Ask,
        listener: Listener
    ) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            // Generous, and it is the gap between events rather than the whole answer: a
            // model that has been asked something hard can think for the better part of a
            // minute before the first character, and a stream cut off at that point looks
            // exactly like a service that is down.
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            for ((header, value) in agent.authHeaders(key)) setRequestProperty(header, value)
        }
        ask.connection = connection
        if (ask.cancelled) return

        connection.outputStream.use {
            it.write(bodyFor(agent, model, persona, history).toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            val body = connection.errorStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (!ask.cancelled) main.post { listener.onFailed(refusal(agent, code, body)) }
            return
        }

        var said = false
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                if (ask.cancelled) return
                val line = reader.readLine() ?: break
                // A stream of server-sent events is mostly blank lines and `event:` lines
                // naming what is about to arrive. Only the payload is of any use here: both
                // services put the type of the event inside the JSON as well, which is what
                // [deltaIn] reads, so the naming line is one thing fewer to keep in step.
                if (!line.startsWith(DATA_PREFIX)) continue
                val payload = line.substring(DATA_PREFIX.length).trim()
                if (payload.isEmpty()) continue
                // OpenAI's shape ends the stream with a sentinel that is not JSON. Anthropic
                // ends it by stopping, which the read above notices on its own.
                if (payload == DONE) break
                val delta = deltaIn(agent, payload) ?: continue
                if (delta.isEmpty()) continue
                said = true
                if (ask.cancelled) return
                main.post { listener.onDelta(delta) }
            }
        }

        if (ask.cancelled) return
        main.post {
            // A stream that closed having said nothing is a failure wearing a success's
            // clothes - almost always a model name the service does not have, answered with
            // an empty event stream rather than an error. An empty bubble would leave the
            // user with nothing at all to go on.
            if (said) listener.onDone()
            else listener.onFailed("${agent.label} sent nothing back. Check the model in settings.")
        }
    }

    // ---------------------------------------------------------------- the question

    /**
     * The request body, in whichever of the two shapes the service reads.
     *
     * Three differences, and they are the whole of why this is not one function. Anthropic
     * takes the standing instruction as its own top-level `system` field where OpenAI takes
     * it as the first message in the list; Anthropic requires a ceiling on the answer's
     * length where OpenAI treats one as optional; and the two disagree about what the field
     * is called, which is why none is sent to OpenAI at all - its newer models reject
     * `max_tokens` in favour of a differently named one, and an answer with no ceiling is
     * the right answer here anyway. Nothing else is sent: no temperature, no penalties, no
     * sampling settings. Those are dials for somebody tuning an application, and several of
     * the current models refuse a request that touches them.
     */
    private fun bodyFor(
        agent: CortanaSettings.Agent,
        model: String,
        persona: String,
        history: List<Turn>
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("stream", true)

        val messages = JSONArray()
        if (agent.wire == CortanaSettings.Wire.OPENAI) {
            messages.put(message("system", persona))
        } else {
            body.put("system", persona)
            body.put("max_tokens", ANSWER_CEILING)
        }
        for (turn in history) {
            messages.put(message(if (turn.mine) "user" else "assistant", turn.text))
        }
        body.put("messages", messages)
        return body
    }

    private fun message(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("content", text)

    /**
     * Who the model is being asked to be.
     *
     * This is the only part of the app that could reasonably be called a costume, and it is
     * worth being exact about what it is for. The point is not to fake a personality onto a
     * stranger; it is that the screen it answers on is Cortana's, and an assistant that
     * replies to "hi" with four hundred words of bulleted markdown on a phone-sized speech
     * bubble is wrong in a way that has nothing to do with character. Half of these lines
     * are about length and formatting for that reason.
     *
     * The other half is the personality Cortana actually had, as far as it can be described
     * rather than quoted: brief, warm, a little dry, never fawning. And one honest limit -
     * she cannot set a reminder, read your mail or tell you where you left the car, because
     * the notebook and the phone hooks she did all of that through do not exist here. A
     * model asked to be an assistant will cheerfully claim to have set an alarm that it has
     * not set, and being lied to by the phone is worse than being told no.
     *
     * The name is the one thing carried in from the phone itself - the same name the
     * greeting uses, out of the same box on the settings page. It is the whole of what the
     * notebook has left.
     */
    private fun personaFor(context: Context): String {
        val name = MainActivity.getUserName(context)
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals("User", true) }
        return buildString {
            append(PERSONA)
            if (name != null) append("\n\nThe person you are talking to is called $name.")
        }
    }

    // ---------------------------------------------------------------- the answer

    /**
     * The next few characters out of one event, or null if this event carried none.
     *
     * Most events carry none. Both services open and close the stream with events about the
     * stream - what model answered, how many tokens it cost, why it stopped - and a couple
     * of them per answer are the text itself. Anything unrecognised is skipped rather than
     * treated as an error: both formats gain event types over time, and an app that fell
     * over on one it had not heard of would break on a Tuesday for no visible reason.
     */
    private fun deltaIn(agent: CortanaSettings.Agent, payload: String): String? {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return if (agent.wire == CortanaSettings.Wire.OPENAI) {
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.textOrNull("content")
        } else {
            // Anthropic sends the text as deltas on a content block, and a block can be
            // something other than text - a model that thinks before it answers sends its
            // thinking the same way. Only the text is wanted, and the type of the delta is
            // what separates them.
            if (json.optString("type") != "content_block_delta") return null
            val delta = json.optJSONObject("delta") ?: return null
            if (delta.optString("type") != "text_delta") return null
            delta.textOrNull("text")
        }
    }

    /**
     * A string field, or null where there isn't one - including where there is a JSON null.
     *
     * The distinction is not pedantry here. `optString` renders a JSON null as the four
     * characters "null", and the first event of every OpenAI-shaped stream is the one that
     * announces the speaker with `content` explicitly set to null - so reading it the
     * ordinary way puts the word "null" at the front of every answer.
     */
    private fun JSONObject.textOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- what went wrong

    /**
     * A refused request, put in her own words.
     *
     * The status code is what is actually known, and the four that get a sentence of their
     * own are the four a person can do something about: a key that is wrong, an account
     * with nothing left in it, a service being leant on too hard, and a model name that does
     * not exist. Everything else keeps the service's own message, trimmed - it is usually a
     * plain English sentence, and inventing a vaguer one on top of it would be hiding the
     * only useful thing in the response.
     */
    private fun refusal(agent: CortanaSettings.Agent, code: Int, body: String): String {
        val said = messageIn(body)
        return when (code) {
            401, 403 -> "${agent.label} wouldn't take that key. It's in settings."
            402 -> "That ${agent.label} account has run out of credit."
            404, 400 -> said?.let { "${agent.label} said: $it" }
                ?: "${agent.label} didn't recognise that request. Check the model in settings."
            429 -> "${agent.label} is asking me to slow down. Try again in a moment."
            in 500..599 -> "${agent.label} is having trouble at their end."
            else -> said?.let { "${agent.label} said: $it" } ?: "${agent.label} said no ($code)."
        }
    }

    /**
     * The human-readable part of an error body, where there is one.
     *
     * Both services wrap it the same way - an `error` object with a `message` in it - and
     * anything that does not fit that is not worth showing raw: an HTML error page in a
     * speech bubble is noise, not information.
     */
    private fun messageIn(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        // Two shapes, and both are in use: an `error` object with a `message` in it, which
        // is what OpenAI and Anthropic send, and an `error` that is itself the sentence,
        // which is what xAI sends for several of its refusals. Anything else - an HTML error
        // page, a gateway's own body - is not worth putting in a speech bubble raw.
        val said = json.optJSONObject("error")?.textOrNull("message")
            ?: json.textOrNull("error")
            ?: return null
        return if (said.length > SAID_LIMIT) said.take(SAID_LIMIT).trimEnd() + "…" else said
    }

    /** A request that never got as far as an answer: no signal, no route, nothing listening. */
    private fun reachFailure(agent: CortanaSettings.Agent, e: Exception): String =
        if (e is java.net.SocketTimeoutException) "${agent.label} took too long to answer."
        else "I couldn't reach ${agent.label}."

    // ---------------------------------------------------------------- the model list

    /**
     * What the chosen service will currently answer to.
     *
     * Asked for rather than shipped, which is the only way this stays true. Every one of
     * these four renames its models several times a year, and a list built into a launcher
     * is a list that is wrong by the next release - the user would be choosing from names
     * that no longer resolve while the ones that do are unreachable. All four publish the
     * list at an address next to the one that answers questions, so the settings page asks.
     *
     * Sorted, because the services do not agree on an order and one of them returns a
     * hundred entries in something close to none. Handed back null - as distinct from an
     * empty list - where the list could not be got at all: the page says different things
     * about a service with no models and a service that would not say.
     */
    fun models(context: Context, onReady: (List<String>?) -> Unit) {
        val settings = CortanaSettings(context)
        val agent = settings.getAgent()
        val key = settings.getKey(agent)
        val endpoint = agent.modelsEndpoint(settings.getFoundryResource())
        if (key.isEmpty() || endpoint == null) {
            main.post { onReady(null) }
            return
        }
        executor.execute {
            val found = runCatching { fetchModels(agent, endpoint, key) }.getOrNull()
            main.post { onReady(found) }
        }
    }

    private fun fetchModels(
        agent: CortanaSettings.Agent,
        endpoint: String,
        key: String
    ): List<String>? {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = LIST_TIMEOUT_MS
            for ((header, value) in agent.authHeaders(key)) setRequestProperty(header, value)
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val body = BufferedReader(
                InputStreamReader(connection.inputStream, Charsets.UTF_8)
            ).use { it.readText() }
            val data = JSONObject(body).optJSONArray("data") ?: return null
            val names = mutableListOf<String>()
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { names += it }
            }
            return names.sorted()
        } finally {
            connection.disconnect()
        }
    }

    // ---------------------------------------------------------------- constants

    private const val DATA_PREFIX = "data:"
    private const val DONE = "[DONE]"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val LIST_TIMEOUT_MS = 15_000

    /**
     * How long an answer is allowed to be, where the service insists on being told.
     *
     * Only Anthropic requires it, and it is a guillotine rather than a target: an answer
     * that runs into it stops mid-word, and there is no cost to setting it high.
     *
     * High is exactly what it has to be, because the current models think before they
     * answer and the thinking is spent out of this same allowance. A ceiling sized for the
     * two sentences the instruction below asks for would be eaten by the reasoning that
     * precedes them, and what arrived in the bubble would be nothing at all.
     */
    private const val ANSWER_CEILING = 8192

    /** How much of a service's own error message is worth putting in a bubble. */
    private const val SAID_LIMIT = 200

    /** See [personaFor]. */
    private val PERSONA = """
        You are Cortana, the personal assistant from Windows Phone. You are running on a
        phone, inside a screen with a ring on it, and the person is typing to you.

        Speak the way she did: short, warm, direct, a little dry. One or two sentences for
        anything that can be answered in one or two sentences, and never more than a short
        paragraph unless you are explicitly asked to go long. You are being read in a chat
        bubble on a phone, so write plain sentences - no markdown, no headings, no bullet
        lists, no bold. If you need to give steps, say them as a sentence.

        You are not a search engine and not a manual. When someone asks a plain question,
        answer it and stop; do not pad, do not restate the question back, do not offer three
        follow-ups, and do not tell the person what a good question it was.

        You can talk about anything, but you cannot do anything to the phone. You have no
        reminders, no alarms, no calendar, no email, no location and no notebook - all of it
        went with the service you used to run on. If you are asked to set, remind, call,
        text, book or open something, say plainly that you can't do that any more. Never
        claim to have done it.

        If you don't know something, say so.
    """.trimIndent()
}
