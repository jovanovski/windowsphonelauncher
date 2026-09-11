package rocks.gorjan.gokixp.apps.cortana

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.cortana.shazam.SongRecogniser
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroChoiceRow
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPageTransition
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81Program
import rocks.gorjan.gokixp.wp81.applyToField

/**
 * Cortana, as the phone's search key opened her.
 *
 * On Windows Phone 8.1 the third capacitive key stopped being "search" and became "ask
 * Cortana" - the same key, opening a screen with a ring on it instead of a list of
 * results. This is that screen: fog, a breathing circle, a greeting that is different
 * every time, and one box along the bottom.
 *
 * What it cannot be is all of her. Cortana was a service, the service is gone, and the
 * parts of her that reached into the phone - the notebook, the reminders, the voice - were
 * never something a launcher could stand in for. What can be rebuilt is the part the screen
 * was actually for: you have a question, and it goes somewhere and comes back.
 *
 * So the microphone is replaced by two buttons. One sends the question to a search engine.
 * The other asks a model, and what happens then depends on whether the user has given this
 * app a key of their own. Without one it hands the question to Copilot or ChatGPT or Claude
 * as an intent, which lands in an app that is already signed in - honest, and the end of the
 * matter. With one, the ring and the greeting give the middle of the screen over to the
 * exchange itself: question, answer, and a follow-up that is answered in the light of both,
 * which is the thing Cortana did that a link with a question in it cannot. See [converse]
 * and [CortanaAgent].
 *
 * Which engine, which service, which model, and where the answer arrives are all the user's
 * business, which is what the cog in the corner is for.
 *
 * Everything here is drawn rather than laid out from a resource, like the rest of this
 * shell's programs, and the whole page takes the phone's accent: the ring, the greeting,
 * the marks in settings. The screenshot this was built from is green because that phone's
 * accent was green.
 */
class CortanaApp(
    private val context: Context,
    private var palette: WP81Palette,
    /**
     * Sends a question on its way.
     *
     * The app has decided what URL it wants and whether it should open in the phone's own
     * browser; only the launcher's Activity can actually do either, so it is handed both.
     */
    private val onOpenUrl: (url: String, inIe: Boolean) -> Unit,
    /** Whether the launcher currently holds the microphone permission. */
    private val hasMicrophone: () -> Boolean,
    /** Asks for it. Only the launcher's Activity can put that prompt up. */
    private val onAskForMicrophone: () -> Unit
) : WP81Program {

    private val settings = CortanaSettings(context)
    private val recogniser = SongRecogniser()

    private lateinit var root: FrameLayout
    private lateinit var backdrop: CortanaBackdropView
    private lateinit var ring: CortanaRingView
    private lateinit var greetingLabel: TextView
    private lateinit var input: EditText

    /**
     * The part of the screen between the cog and the box, which is one of two things.
     *
     * Either the ring with a greeting under it, or the conversation - never both, and never
     * neither. Cortana's screen was a single page that changed what it was showing rather
     * than a stack of them, which is why this is a swap rather than another overlay: the box
     * along the bottom belongs to both states and must not move when one becomes the other.
     */
    private lateinit var middle: FrameLayout
    private lateinit var greetingPane: LinearLayout
    private lateinit var chat: CortanaChatView

    /**
     * The answer currently arriving, if one is.
     *
     * Held so it can be stopped. A stream writes into a bubble for as long as the model
     * keeps talking, and leaving the conversation, asking something else, or closing the
     * window are all things that happen while it is still going.
     */
    private var inFlight: CortanaAgent.Ask? = null

    /**
     * What the middle of the screen is currently saying.
     *
     * Held because three different things can put words there - the greeting, a listen in
     * progress, a song that was named - and each of them has to know what to undo.
     */
    private var face: Face = Face.Greeting

    /** Pages stacked over the main screen, newest last. Only ever settings. See [handleBack]. */
    private val overlays = mutableListOf<View>()

    /**
     * The greeting on screen.
     *
     * Held rather than re-rolled at every rebuild, because a theme change is not a fresh
     * arrival: coming back from the colour picker to find Cortana greeting you a second
     * time, differently, would say the app had been restarted when it had not. It is rolled
     * again by [greetAfresh], which is what an actual arrival calls.
     */
    private var greeting = CortanaGreetings.random(MainActivity.getUserName(context))

    /**
     * The name the greeting on screen was built with.
     *
     * Kept so that a name typed in settings can be spotted on the way back out - see
     * [refreshGreetingForName]. The greeting itself does not say: two thirds of them carry
     * the name and the rest do not, so a line without one is no evidence of anything.
     */
    private var greetingName = MainActivity.getUserName(context)

    // ---------------------------------------------------------------- construction

    /**
     * Rebuilds the program in a new theme. See [WP81Program].
     *
     * A conversation does not survive it. The page is built again from the ground up, and
     * what a rebuild costs is the place the user was in - which for this program is the
     * exchange on screen. Only the stream has to be put down deliberately: everything else
     * a rebuild orphans is a view, and a view nobody holds is collected, where an answer
     * still arriving would go on writing into bubbles that are no longer on the screen.
     */
    override fun applyPalette(palette: WP81Palette): View {
        this.palette = palette
        inFlight?.cancel()
        inFlight = null
        return createView()
    }

    fun createView(): View {
        // The pages that were stacked over the last root belong to a view tree that is about
        // to be thrown away. Kept, they would leave back with something to dismiss that is
        // not on screen - a press that appears to do nothing at all.
        overlays.clear()
        settingsPage = null
        settingsColumn = null

        root = FrameLayout(context)

        backdrop = CortanaBackdropView(context, palette)
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        column.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        middle = FrameLayout(context)
        greetingPane = buildGreetingPane()
        middle.addView(greetingPane, FrameLayout.LayoutParams(MATCH, MATCH))
        chat = CortanaChatView(context, palette).apply { visibility = View.GONE }
        middle.addView(chat, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(middle, LinearLayout.LayoutParams(MATCH, 0, 1f))

        column.addView(buildAskBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // The fog's accent wash belongs under the ring, and where the ring is depends on
        // how tall the screen turned out to be - so the backdrop is told once the two of
        // them have actually been laid out against each other.
        //
        // Measured through the window rather than off the ring's own position, because the
        // ring is now two containers down and its `y` is a distance from the top of the
        // greeting rather than from the top of the page. Only while it is actually on
        // screen: a conversation puts the ring away, and the wash stays where it was rather
        // than sliding to the top of the page behind the bubbles.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (root.height > 0 && ring.height > 0 && ring.isShown) {
                val ringAt = IntArray(2)
                val rootAt = IntArray(2)
                ring.getLocationInWindow(ringAt)
                root.getLocationInWindow(rootAt)
                val centre = (ringAt[1] - rootAt[1]) + ring.height / 2f
                backdrop.setRingCentre(centre / root.height)
            }
        }

        return root
    }

    /**
     * The ring and the greeting, held a quarter of the way down.
     *
     * Where the phone put them: high enough that the keyboard never reaches them, low enough
     * that the screen does not read as a header. The two spacers are what fix that
     * proportion, and they are weighted rather than measured so it holds on a screen of any
     * height - and closes up correctly when the keyboard takes half of it.
     */
    private fun buildGreetingPane(): LinearLayout {
        val pane = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, TOP_WEIGHT))
        pane.addView(buildFace(), LinearLayout.LayoutParams(MATCH, WRAP))
        pane.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, BOTTOM_WEIGHT))
        return pane
    }

    /**
     * The cog, and nothing else. Cortana's screen has one command and it is not a strip.
     *
     * Top right, where the phone kept the one command a chromeless page was allowed. In the
     * page's own foreground - white on a dark phone, black on a light one - because it is
     * the only thing on this screen that can be pressed apart from the box, and a greyed
     * cog on a page with nothing else on it reads as a disabled control rather than as a
     * discreet one.
     */
    private fun buildTopBar(): View {
        val bar = FrameLayout(context)
        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // The note goes to the left of the cog, which puts the thing that is used in the
        // more reachable of the two places and the thing that is configured once in the
        // corner. Both up here rather than on the bar below, because neither is about what
        // is in the box: one changes the phone's mind, the other asks a question the box
        // has no words for.
        listenKey = topKey(R.drawable.wp81_glyph_music_note, "name the song playing") {
            toggleListening()
        }
        keys.addView(listenKey, topKeySize())
        keys.addView(
            topKey(R.drawable.wp81_glyph_settings, "settings") { showSettings() },
            topKeySize()
        )

        bar.addView(keys, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END or Gravity.TOP
            marginEnd = dp(6)
            topMargin = dp(6)
        })
        return bar
    }

    /**
     * How big one of the top keys is.
     *
     * Taller than it is wide, which is what brings the pair together: the glyphs are the
     * same size either way, and squeezing the horizontal padding is the only way to close
     * the gap between them without either shrinking the marks or overlapping the two touch
     * targets. Full height is kept because the vertical padding is what holds them clear of
     * the status bar above.
     */
    private fun topKeySize() =
        LinearLayout.LayoutParams(dp(TOP_KEY_WIDTH_DP), dp(TOP_KEY_HEIGHT_DP))

    /** One of the two keys in the top corner. */
    private fun topKey(glyph: Int, describedAs: String, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(glyph)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(palette.foreground)
            // Worked out from the key's own size rather than given, so the glyph stays
            // square and the same size on both keys however the touch target is shaped.
            val padX = dp((TOP_KEY_WIDTH_DP - TOP_KEY_GLYPH_DP) / 2)
            val padY = dp((TOP_KEY_HEIGHT_DP - TOP_KEY_GLYPH_DP) / 2)
            setPadding(padX, padY, padX, padY)
            isClickable = true
            contentDescription = describedAs
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    /** The note, kept so it can go accent-coloured while she is listening. */
    private var listenKey: ImageView? = null

    /** The ring, and what she has to say. */
    private fun buildFace(): View {
        val face = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        ring = CortanaRingView(context, palette.accent)
        face.addView(ring, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        greetingLabel = TextView(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = GREETING_SP
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(palette.accent)
            // Segoe Light at this size carries a lot of leading of its own, and two lines
            // set solid read as one paragraph rather than as a greeting and a question.
            setLineSpacing(0f, GREETING_LEADING)
            includeFontPadding = false
        }
        face.addView(greetingLabel, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(GREETING_GAP_DP)
            marginStart = dp(PAGE_MARGIN_DP)
            marginEnd = dp(PAGE_MARGIN_DP)
        })

        showGreeting()

        return face
    }

    // ---------------------------------------------------------------- the middle

    /** What the words under the ring are currently doing. */
    private sealed interface Face {
        data object Greeting : Face
        data object Listening : Face
        data object Answered : Face
    }

    private fun showGreeting() {
        face = Face.Greeting
        greetingLabel.text = listOfNotNull(greeting.salutation, greeting.question)
            .joinToString("\n")
    }

    /**
     * Puts the middle of the screen into whatever the recogniser is doing.
     *
     * One place rather than three, because the three states have to undo each other
     * exactly: a listen that follows a named song has to clear the artwork, and a song that
     * follows a listen has to stop the ring turning.
     */
    private fun sayInstead(text: String, state: Face) {
        face = state
        greetingLabel.text = text
    }

    // ---------------------------------------------------------------- listening

    /**
     * The music key: starts a listen, or abandons the one under way.
     *
     * The same key for both, because it is the same question being answered yes and then
     * no. A separate stop button would be a second control that exists for eight seconds
     * at a time.
     */
    private fun toggleListening() {
        if (recogniser.isListening) {
            recogniser.stop()
            endListening()
            showGreeting()
            return
        }
        if (!hasMicrophone()) {
            // The prompt is the launcher's to raise. Nothing is started here: the user is
            // about to be asked a question, and a microphone opening behind that question
            // would be answering it on their behalf.
            sayInstead("I need to be allowed to listen first.", Face.Answered)
            onAskForMicrophone()
            return
        }
        startListening()
    }

    /** Begins a listen. The permission has already been checked - see [toggleListening]. */
    fun startListening() {
        if (recogniser.isListening) return
        hideKeyboard()
        // A listen says what it is doing in the middle of the page, which is where the
        // conversation is - so the conversation ends here. That is not a compromise: naming
        // a song finishes by handing the phone to a browser, so it was never going to be a
        // thing done in the middle of talking to her.
        endConversation()
        sayInstead("Listening\u2026", Face.Listening)
        listenKey?.imageTintList = ColorStateList.valueOf(palette.accent)

        recogniser.start(object : SongRecogniser.Listener {
            override fun onListening() {
                ring.setListening(true)
            }

            override fun onRecognised(searchTerm: String) {
                endListening()
                searchForSong(searchTerm)
            }

            override fun onNotRecognised() {
                endListening()
                sayInstead("I didn't catch that one.", Face.Answered)
            }

            override fun onFailed(message: String) {
                endListening()
                sayInstead(message, Face.Answered)
            }
        })
    }

    /** Puts the mark and the key back to how they look when nothing is happening. */
    private fun endListening() {
        ring.setListening(false)
        listenKey?.imageTintList = ColorStateList.valueOf(palette.foreground)
    }

    /**
     * A song, named - and looked up without being asked twice.
     *
     * Nothing is shown here. Being told the title of a song is almost never what somebody
     * wanted; they wanted the song, or who it is by, or where to play it, and every one of
     * those is on the other side of a search. A screen that answered with the name and then
     * waited to be tapped would be putting a step in front of the thing that was actually
     * being asked for.
     *
     * Through the box rather than around it, so the search goes wherever the user's own
     * engine setting says and lands in the browser they chose - a song is a search like any
     * other, including in being cleared from the box once it has been asked.
     */
    private fun searchForSong(searchTerm: String) {
        // Felt, because it is about to stop being seen. The answer arrives anywhere up to
        // twelve seconds after the note was pressed - long enough that the user has looked
        // away, or put the phone down, or is still holding it at a speaker - and the only
        // thing that happens on screen is the browser taking the screen over. A tick is
        // what says "found it" to somebody who is not watching.
        //
        // The shell's own tick, not one of this app's devising: see [Haptics] for why there
        // is only ever the one, and why it goes through a view rather than the vibrator -
        // it picks up whatever waveform the phone was tuned with and stays silent for a
        // user who has turned touch feedback off.
        Haptics.tap(root)

        // Back to the greeting first: the browser is about to cover this screen, and what
        // it should say when the user comes back past it is hello - not "Listening..." from
        // a listen that finished a page ago.
        showGreeting()
        input.setText(searchTerm)
        ask(toAgent = false)
    }

    /**
     * The box along the bottom, and the two things that can be done with what is in it.
     *
     * The phone had a text field and a microphone. The microphone is the one part of this
     * screen that cannot be honestly reproduced - there is no assistant behind it to
     * listen - so the space it occupied is given to the two buttons that say where a typed
     * question should go. They are the same size and shape the microphone was, sitting in
     * the same place, because that is where a thumb resting on this screen already is.
     */
    private fun buildAskBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The strip's own ground, so the box and its buttons read as one piece of
            // furniture standing on the fog rather than as three things floating on it.
            setBackgroundColor(palette.chrome)
            setPadding(dp(BAR_PAD_DP), dp(BAR_PAD_DP), dp(BAR_PAD_DP), dp(BAR_PAD_DP))
        }

        input = EditText(context).apply {
            hint = HINT
            setSingleLine()
            // Go rather than Search: this key does what the magnifier does, and the phone
            // labelled the same key on the same box "go".
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = INPUT_SP
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // The shell's one text box, from the one place it is described.
            palette.applyToField(this)
            typeface = font(R.font.segoeui_regular)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    // The keyboard's own key means the plain search, which is the thing
                    // this box has always been for. Somebody who wanted the model has a
                    // button an inch away saying so.
                    ask(toAgent = false)
                    true
                } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f))

        bar.addView(
            askButton(R.drawable.wp81_nav_search, "search", MAGNIFY_INSET_DP) {
                ask(toAgent = false)
            },
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                marginStart = dp(BAR_PAD_DP)
            }
        )
        val sparkle = askButton(
            R.drawable.wp81_glyph_ai, "ask ${settings.getAgent().label}", BUTTON_INSET_DP
        ) {
            ask(toAgent = true)
        }
        askKey = sparkle
        bar.addView(
            sparkle,
            LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)).apply {
                marginStart = dp(BAR_PAD_DP)
            }
        )

        return bar
    }

    /**
     * One of the two square keys beside the box.
     *
     * Filled in the accent rather than left as a glyph on the strip. There are two of them
     * and they do different things with the same words, so they have to be seen as buttons
     * before they can be read as which button - and the accent is what the phone filled a
     * committed action with.
     *
     * The inset is per key rather than shared, because the two glyphs do not fill their own
     * artwork by the same amount: the magnifier is a Modern UI icon drawn with a wide
     * margin built into its viewport, and the sparkle fills its own edge to edge. Padding
     * them equally makes the magnifier look like the smaller of two buttons rather than
     * like the same button with a different mark on it, so the margin already inside the
     * artwork is taken back out here.
     */
    private fun askButton(
        glyph: Int,
        describedAs: String,
        insetDp: Int,
        onTap: () -> Unit
    ): View =
        ImageView(context).apply {
            setImageResource(glyph)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(palette.onAccent())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(palette.accent)
            }
            val inset = dp(insetDp)
            setPadding(inset, inset, inset, inset)
            isClickable = true
            contentDescription = describedAs
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    // ---------------------------------------------------------------- asking

    /**
     * Sends what is in the box, to the engine or to the model.
     *
     * An empty box is not an error and gets no complaint - the buttons are simply what
     * they look like when there is nothing to send, which is nothing. A toast saying "type
     * something first" is a scolding for a tap that was almost certainly a fidget.
     */
    private fun ask(toAgent: Boolean) {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return

        // The question the model can answer here, answered here. This is the one branch
        // that does not end in a browser: the key is the user's, the conversation is on
        // this page, and it can be followed up - which is the thing the phone did that a
        // link with a question in it cannot. See [converse].
        //
        // The ring does not flare on the way out of this one, which it does for everything
        // else here. The flare exists to fill the beat before a window opens over the
        // screen; nothing opens over this, the ring is put away in the same frame, and the
        // waiting bubble is what says the question landed.
        if (toAgent && settings.canConverse() && settings.getAgentAnswersHere()) {
            input.setText("")
            converse(query)
            return
        }

        // Otherwise the question leaves the launcher - and a question for a model leaves
        // whatever the browser setting says, because leaving is the point: handed to Android
        // as an intent, the question is caught by the Copilot or ChatGPT or Claude app if
        // one is installed, and lands in a session that is already signed in. Answering it
        // in the phone's own WebView would be the one route that guarantees a signed-out web
        // page - a model that has to be logged into again before it will answer is not an
        // assistant, it is a form.
        //
        // A page of search results has no such app behind it and nothing to be signed into,
        // so that one is the user's choice: see CortanaSettings.getSearchOpensInIe.
        val url: String
        val inIe: Boolean
        if (toAgent) {
            url = settings.getAgent().urlFor(query)
            inIe = false
        } else {
            url = settings.getEngine().urlFor(query)
            inIe = settings.getSearchOpensInIe()
        }

        // She answers before the browser does. Opening a window takes a beat, and the ring
        // flaring is what fills it - see CortanaRingView.acknowledge.
        ring.acknowledge()
        hideKeyboard()

        // The question has been asked, so the box stops holding it. Coming back past the
        // browser to a field still full of the last thing searched for means selecting it
        // all and deleting it before the next question can be typed - and the box is the
        // first thing this screen offers, now that the keyboard comes up on arrival. An
        // empty one is ready; a full one is a chore.
        input.setText("")

        onOpenUrl(url, inIe)
    }

    // ---------------------------------------------------------------- conversing

    /**
     * Asks the model, on this page, with everything already said behind the question.
     *
     * This is the part of Cortana that was never a program on the phone. She kept the
     * exchange: you asked who somebody was and then asked how old *he* was, and the second
     * question was answered because the first one was still there. A model has no memory
     * between requests either, so the same trick is played the same way - the whole
     * conversation goes up with every question. See [CortanaAgent.ask].
     *
     * Anything still arriving is dropped first. Asking a second question before the first
     * answer has finished is a perfectly ordinary thing to do, and it means the first answer
     * is no longer wanted; left running, it would go on writing into a bubble above the new
     * question for another twenty seconds.
     *
     * The keyboard stays up, unlike everywhere else on this screen. A conversation is a
     * thing you are in the middle of, and putting the keyboard away after each question
     * would make every follow-up start with tapping the box again.
     */
    private fun converse(query: String) {
        inFlight?.cancel()
        showConversation()
        chat.addMine(query)
        val hers = chat.beginHers()
        inFlight = CortanaAgent.ask(context, chat.turns(), object : CortanaAgent.Listener {
            override fun onDelta(text: String) = hers.append(text)

            override fun onDone() {
                hers.done()
                inFlight = null
            }

            override fun onFailed(message: String) {
                hers.fail(message)
                inFlight = null
            }
        })
    }

    /** Puts the conversation where the ring was. */
    private fun showConversation() {
        if (chat.visibility == View.VISIBLE) return
        greetingPane.visibility = View.GONE
        chat.visibility = View.VISIBLE
    }

    /**
     * Ends it, and gives the screen back to the ring.
     *
     * Everything goes: the stream that was arriving, the bubbles, and the history behind
     * them. A conversation is a session - it was on the phone too - and one that survived
     * being left would mean the next question, asked days later, was answered in the light
     * of something the user has long forgotten saying.
     *
     * Says whether there was one, because back has to know whether it has just done
     * something or should carry on out of the app.
     */
    private fun endConversation(): Boolean {
        inFlight?.cancel()
        inFlight = null
        if (!::chat.isInitialized || chat.visibility != View.VISIBLE) return false
        chat.clear()
        chat.visibility = View.GONE
        greetingPane.visibility = View.VISIBLE
        return true
    }

    // ---------------------------------------------------------------- settings

    /**
     * What she needs to be told: a name to use, and where a question goes.
     *
     * A page of its own rather than a strip of commands: these are settings and they are
     * remembered, and three of the four are one answer out of several - which is a page
     * with round marks on it, not a menu.
     */
    private fun showSettings() {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Opaque. The fog is lovely and it is the wrong thing to read a list of radio
            // buttons off - a settings page is a page, and Metro pages are flat.
            setBackgroundColor(palette.background)
            // Swallows anything that misses a row, so a stray tap does not reach the
            // greeting screen underneath and put the keyboard up behind this page.
            isClickable = true
        }

        val header = MetroPageHeader(context, palette).apply {
            setTitle("settings")
            onBack = { handleBack() }
        }
        page.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(28))
        }

        settingsColumn = column
        fillSettings(column)

        page.addView(
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )

        hideKeyboard()
        settingsPage = page
        pushOverlay(page)
    }

    /** The settings page, and the column its rows go in. Both held for the reasons above. */
    private var settingsPage: View? = null
    private var settingsColumn: LinearLayout? = null

    /**
     * Everything on the settings page, built from what is currently saved.
     *
     * Built rather than bound, and built again whenever the chosen service changes. Half of
     * this page is about one service - its key, its model, whether it answers here - and
     * those rows say the wrong thing the moment a different one is picked. Rebuilding is
     * also what the rest of this shell does with a page whose contents depend on a choice
     * made on it, and it costs a couple of dozen views.
     */
    private fun fillSettings(column: LinearLayout) {
        column.removeAllViews()

        column.addView(label("what to call you"), wide())
        column.addView(nameField(), LinearLayout.LayoutParams(MATCH, dp(BUTTON_DP)))

        column.addView(label("search engine"), wide())
        column.addView(
            choices(
                CortanaSettings.Engine.entries.map { it to it.label },
                chosen = settings.getEngine(),
                onPick = { settings.setEngine(it) }
            ), wide()
        )

        column.addView(label("open search results in"), wide())
        column.addView(
            choices(
                listOf(true to "Internet Explorer", false to "the phone's browser"),
                chosen = settings.getSearchOpensInIe(),
                onPick = { settings.setSearchOpensInIe(it) }
            ), wide()
        )

        val agent = settings.getAgent()

        column.addView(label("ai agent"), wide())
        column.addView(
            choices(
                CortanaSettings.Agent.entries.map { it to it.label },
                chosen = agent,
                onPick = {
                    settings.setAgent(it)
                    // The sparkle key names whichever model it is going to ask, and a
                    // screen reader that went on saying "ask Copilot" after the answer had
                    // been changed would be the one place in the app that lied about it.
                    rebuildAskBarDescriptions()
                    fillSettings(column)
                }
            ), wide()
        )

        column.addView(label("${agent.label.lowercase()} key"), wide())
        column.addView(linked(keyBlurb(agent), agent.keyUrl), wide())
        column.addView(keyField(agent), LinearLayout.LayoutParams(MATCH, dp(BUTTON_DP)))

        if (agent == CortanaSettings.Agent.COPILOT) {
            column.addView(label("foundry resource"), wide())
            column.addView(
                detail(
                    "the name you gave the resource the key belongs to - the first part of " +
                        "its address, before .services.ai.azure.com."
                ), wide()
            )
            column.addView(foundryField(), LinearLayout.LayoutParams(MATCH, dp(BUTTON_DP)))
        }

        column.addView(label("model"), wide())
        column.addView(
            detail("which of ${agent.label}'s models answers. tap to change it."), wide()
        )
        column.addView(modelRow(agent), wide())

        column.addView(label("answers arrive"), wide())
        column.addView(
            detail(
                "with a key, she can answer here and be asked a follow-up. without one, " +
                    "the question is handed to ${agent.label} itself, which is what the " +
                    "sparkle has always done."
            ), wide()
        )
        column.addView(
            choices(
                listOf(true to "here, in the conversation", false to "in ${agent.label}"),
                chosen = settings.getAgentAnswersHere(),
                onPick = { settings.setAgentAnswersHere(it) }
            ), wide()
        )
    }

    /**
     * What the line above the key box says, and where tapping it goes.
     *
     * Three of them are the same sentence with a different name in it. Copilot is not, and
     * the difference is worth the paragraph: Microsoft has never published an API for
     * talking to Copilot - what they publish is Foundry, the service Copilot is built on -
     * so the key that goes in this box is a Foundry key and the models behind it are the
     * same models. Saying so is better than a box that quietly does not work.
     */
    private fun keyBlurb(agent: CortanaSettings.Agent): String = when (agent) {
        CortanaSettings.Agent.COPILOT ->
            "Copilot has no key of its own to paste. this one is a Microsoft Foundry key - " +
                "the service Copilot is built on, and the same models. tap here to get one."
        else ->
            "with a key of your own she answers here instead of handing the question over. " +
                "tap here to get one. it is billed to your ${agent.label} account, and " +
                "nothing typed here goes anywhere else."
    }

    /**
     * The key itself.
     *
     * Shown rather than starred out, like the keyboard's GIF key beside it. A key is a run
     * of characters nobody can read back from memory, and the only way to tell whether the
     * right one is in the box is to look at it - a masked field on a phone in somebody's own
     * hand protects nothing and turns "check the key" into "paste it again". It lives in the
     * launcher's preferences, which the settings export carries; see [CortanaSettings.getKey].
     *
     * Saved as it is typed, for the same reason the name box above it is: this page is left
     * by pressing back, and a field that commits on some other gesture is a field that
     * quietly loses what was put in it.
     */
    private fun keyField(agent: CortanaSettings.Agent): View = EditText(context).apply {
        setText(settings.getKey(agent))
        hint = "paste your ${agent.label} key here"
        setSingleLine()
        // Nothing here is a word: no correction, no capitals, and nothing worth teaching to
        // the dictionary that learns what you type.
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        textSize = INPUT_SP
        setPadding(dp(12), dp(10), dp(12), dp(10))
        palette.applyToField(this)
        typeface = font(R.font.segoeui_regular)
        doAfterTextChanged { settings.setKey(agent, it?.toString().orEmpty()) }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }
    }

    /** The other half of a Foundry address. See [CortanaSettings.getFoundryResource]. */
    private fun foundryField(): View = EditText(context).apply {
        setText(settings.getFoundryResource())
        hint = "resource name"
        setSingleLine()
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        textSize = INPUT_SP
        setPadding(dp(12), dp(10), dp(12), dp(10))
        palette.applyToField(this)
        typeface = font(R.font.segoeui_regular)
        doAfterTextChanged { settings.setFoundryResource(it?.toString().orEmpty()) }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }
    }

    /** The model in force, and the way to a page of the others. */
    private fun modelRow(agent: CortanaSettings.Agent): View = TextView(context).apply {
        text = settings.getModel(agent)
        typeface = font(R.font.segoeui_regular)
        textSize = INPUT_SP
        setTextColor(palette.accent)
        setPadding(0, dp(2), 0, dp(10))
        isClickable = true
        contentDescription = "model: ${settings.getModel(agent)}"
        setOnClickListener {
            Haptics.tap(it)
            showModelPage(agent)
        }
        TiltEffect.apply(this)
    }

    /**
     * Which model of the chosen service's answers.
     *
     * A box and a list, and the box is the one that matters. The list is asked for from the
     * service itself - see [CortanaAgent.models] - because no list written into a launcher
     * stays true for a season: these four rename their models several times a year, and a
     * page offering names that no longer resolve while the ones that do are unreachable is
     * worse than no page. But the request needs a key, and a service can be slow, or down,
     * or answer with a hundred entries of which the wanted one is a deployment name only the
     * user knows - so a name can always simply be typed.
     */
    private fun showModelPage(agent: CortanaSettings.Agent) {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }

        page.addView(
            MetroPageHeader(context, palette).apply {
                setTitle("model")
                onBack = { handleBack() }
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(28))
        }

        column.addView(label("${agent.label.lowercase()} model"), wide())
        column.addView(
            detail("the name ${agent.label} knows it by. pick one below, or type it here."),
            wide()
        )

        val field = EditText(context).apply {
            setText(settings.getModel(agent))
            setSelection(text.length)
            hint = agent.defaultModel
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = INPUT_SP
            setPadding(dp(12), dp(10), dp(12), dp(10))
            palette.applyToField(this)
            typeface = font(R.font.segoeui_regular)
            doAfterTextChanged { settings.setModel(agent, it?.toString().orEmpty()) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard()
                    true
                } else false
            }
        }
        column.addView(field, LinearLayout.LayoutParams(MATCH, dp(BUTTON_DP)))

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, wide())
        list.addView(detail("asking ${agent.label}\u2026"), wide())

        // The answer arrives on the main thread and may well arrive after this page has been
        // left, which needs no guarding: the views it fills are this page's, and a page that
        // has been dismissed is simply a set of views nobody is looking at.
        CortanaAgent.models(context) { names ->
            list.removeAllViews()
            when {
                names == null -> list.addView(
                    detail(
                        "I couldn't get the list from ${agent.label} - it needs a key first, " +
                            "and it has to be reachable. the box above still works."
                    ), wide()
                )
                names.isEmpty() -> list.addView(
                    detail("${agent.label} didn't offer any models."), wide()
                )
                else -> list.addView(
                    choices(
                        names.map { it to it },
                        chosen = settings.getModel(agent),
                        onPick = {
                            settings.setModel(agent, it)
                            field.setText(it)
                        }
                    ), wide()
                )
            }
        }

        page.addView(
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )

        hideKeyboard()
        pushOverlay(page)
    }

    /**
     * The one setting here that is not a choice: what she calls you.
     *
     * Cortana asked for a name the first time she was opened and kept it in the notebook,
     * and it is the whole reason the greeting reads as being addressed to somebody. There
     * is no notebook to put it in and nowhere else in the shell that says a name, so it is
     * a box at the top of this page.
     *
     * Saved as it is typed rather than behind a button. There is nothing to validate and
     * nothing that can go wrong with a name, so a save key would exist only to be
     * forgotten - and a page that is left by pressing back is one where a field that only
     * commits on some other gesture quietly loses what was typed into it.
     *
     * A phone that has not been told a name has "User" stored under it, which is a
     * placeholder rather than an answer; the box opens empty in that case, because the
     * point of a hint is that it is not text you have to clear before you can type.
     */
    private fun nameField(): View = EditText(context).apply {
        val stored = MainActivity.getUserName(context)
        setText(if (stored.equals("User", true)) "" else stored)
        setSelection(text.length)
        hint = NAME_HINT
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        textSize = INPUT_SP
        setPadding(dp(12), dp(10), dp(12), dp(10))
        // The shell's one text box, from the one place it is described - the same box the
        // question is typed into on the screen behind this one.
        palette.applyToField(this)
        typeface = font(R.font.segoeui_regular)
        doAfterTextChanged { MainActivity.setUserName(context, it?.toString().orEmpty()) }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }
    }

    /**
     * One setting with several answers, only one of which can be in force.
     *
     * Round marks, which is what says so - see [rocks.gorjan.gokixp.wp81.MetroMarker].
     * Built as a group rather than a row at a time because choosing one has to unchoose its
     * neighbours, and something has to be holding all of them to do that.
     */
    private fun <T> choices(
        options: List<Pair<T, String>>,
        chosen: T,
        onPick: (T) -> Unit
    ): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = mutableListOf<Pair<T, MetroChoiceRow>>()
        for ((value, name) in options) {
            val row = MetroChoiceRow(context, palette, name, round = true)
            row.set(value == chosen)
            row.onPicked = {
                onPick(value)
                for ((other, view) in rows) view.set(other == value)
            }
            rows += value to row
            column.addView(row, wide())
        }
        return column
    }

    // ---------------------------------------------------------------- pages

    /**
     * Closes whatever is open over the greeting, and says whether there was anything.
     *
     * The host asks before it closes the window: back means "out of settings" while
     * settings is open, and "out of Cortana" only once it is not.
     */
    fun handleBack(): Boolean {
        overlays.lastOrNull()?.let { top ->
            dismissOverlay(top)
            // Where the page just closed was the model list, the settings page is the one
            // underneath it and is now saying the model that was in force before it was
            // opened. Where it was settings itself, there is nothing left to correct.
            if (top === settingsPage) {
                settingsPage = null
                settingsColumn = null
            } else {
                settingsColumn?.let { fillSettings(it) }
            }
            // The name box may have had the keyboard up when back was pressed, and the
            // page it belongs to is now gone.
            hideKeyboard()
            // Which model the sparkle asks may have changed while that page was up, and
            // the button's description says which one by name.
            rebuildAskBarDescriptions()
            refreshGreetingForName()
            return true
        }
        if (recogniser.isListening) {
            // Back out of listening before backing out of Cortana. Holding a phone up at a
            // speaker is a thing somebody changes their mind about, and back is what they
            // will press when they do - leaving the app with the microphone still open for
            // another eight seconds would be the one way out that does not actually stop it.
            recogniser.stop()
            endListening()
            showGreeting()
            return true
        }
        // And out of the conversation before that. It is not an overlay - it is the middle
        // of this page rather than a page over it - but it is a place the user has gone to
        // and back is how this shell comes out of one.
        if (endConversation()) return true
        return false
    }

    private fun pushOverlay(view: View) {
        overlays.add(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        MetroPageTransition(view).playIn()
    }

    private fun dismissOverlay(view: View) {
        overlays.remove(view)
        MetroPageTransition(view).playOut { root.removeView(view) }
    }

    /**
     * Puts the agent's current name back on the sparkle key.
     *
     * Only the description, which is what a screen reader says and the only place the key
     * names anything - the glyph itself means "ask a model" whichever model that is.
     */
    private fun rebuildAskBarDescriptions() {
        askKey?.contentDescription = "ask ${settings.getAgent().label}"
    }

    /**
     * Puts a name just typed in settings into the greeting straight away.
     *
     * The greeting is the only thing in the shell that uses the name, so a page that took
     * one and handed the user back to "How can I help?" would look like a box that did
     * nothing. Rolled again rather than patched, because a third of the greetings have no
     * room for a name and the one on screen is quite likely to be one of them.
     *
     * Only when the name actually changed - an arrival is a new greeting and a settings
     * page is not - and only while the greeting is what the middle of the screen is
     * showing: a song that was just named is an answer the user asked for, and it does not
     * get thrown away because they went into settings afterwards.
     */
    private fun refreshGreetingForName() {
        val name = MainActivity.getUserName(context)
        if (name == greetingName) return
        greetingName = name
        greeting = CortanaGreetings.random(name)
        if (face == Face.Greeting && ::greetingLabel.isInitialized) showGreeting()
    }

    /** The sparkle key, kept only so its description can be corrected. See above. */
    private var askKey: View? = null

    // ---------------------------------------------------------------- host

    /**
     * A fresh arrival: a new greeting, and an empty box.
     *
     * Called when the search key is pressed while Cortana is already open behind
     * something. Opening her is a moment - it is why the greeting rotates at all - and a
     * window brought back with last week's question still in the box and the same line
     * over it would be a program being un-minimised rather than an assistant being asked.
     */
    fun greetAfresh() {
        recogniser.stop()
        // Whatever was being said is over. The search key means "ask Cortana something",
        // and the whole reason this app rerolls its greeting is that arriving at her is a
        // moment - one that would be undercut by finding last week's exchange still on the
        // page underneath it.
        endConversation()
        // Not hideKeyboard(): every arrival asks for the keyboard, and this runs first on
        // the way to that - see MainActivity.showCortanaDialog. Putting it away here and
        // raising it again a line later is a flicker.
        greetingName = MainActivity.getUserName(context)
        greeting = CortanaGreetings.random(greetingName)
        if (::greetingLabel.isInitialized) {
            endListening()
            showGreeting()
        }
        if (::input.isInitialized) input.setText("")
        while (overlays.isNotEmpty()) {
            val top = overlays.removeAt(overlays.size - 1)
            root.removeView(top)
        }
    }

    /**
     * Puts the cursor in the box and asks for the keyboard.
     *
     * Done on every arrival, however she was opened. Nearly everything this screen can do
     * begins with typing, and a box that has to be tapped before it will take a letter is a
     * step in front of all of it - the greeting and the ring are still there to be read,
     * above the keyboard, for the arrivals where looking was the point.
     *
     * Posted rather than done here. On the way in this is called before the window has been
     * laid out, and a field that is not on screen yet cannot take focus - the request is
     * dropped and the keyboard never comes. The same two-step the app list's own search box
     * uses, for the same reason: see MetroIndexList.showSearchKeyboard.
     */
    fun focusInput() {
        if (!::input.isInitialized) return
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun cleanup() {
        // The microphone must not outlive the window. A closed Cortana that is still
        // recording is the single worst bug this app could have.
        recogniser.stop()
        // Nor should an answer outlive the page it was being written onto. Nothing would
        // break if it did - the bubble is still a live object and would simply fill up
        // unseen - but it is somebody's account being spent on a page nobody is looking at.
        inFlight?.cancel()
        inFlight = null
        hideKeyboard()
    }

    /**
     * The launcher answering the microphone prompt this app asked it to raise.
     *
     * Granted, the listen the user was after starts straight away: they pressed the note,
     * were asked a question, and said yes - making them press it a second time would be
     * treating the permission as a separate errand from the thing they wanted.
     */
    fun onMicrophoneAnswered(granted: Boolean) {
        if (!::greetingLabel.isInitialized) return
        if (granted) startListening()
        else sayInstead("I can't listen without the microphone.", Face.Answered)
    }

    // ---------------------------------------------------------------- furniture

    private fun hideKeyboard() {
        if (!::root.isInitialized) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        input.clearFocus()
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_semibold)
        textSize = 12f
        letterSpacing = 0.06f
        setTextColor(palette.accent)
        setPadding(0, dp(22), 0, dp(4))
    }

    /**
     * The small print under a setting.
     *
     * Several of the rows on this page cannot be understood from their own name - a key box
     * with nothing above it is a box asking for a secret and giving no reason - so they get
     * a sentence. In the page's quiet colour, because it is there to be read once.
     */
    private fun detail(text: String) = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_regular)
        textSize = DETAIL_SP
        setTextColor(palette.foregroundSubtle)
        setLineSpacing(0f, DETAIL_LEADING)
        setPadding(0, 0, 0, dp(8))
    }

    /**
     * The same, where the sentence is also the way to the thing it is about.
     *
     * Accent-coloured, which is what says a thing can be tapped everywhere else in this
     * shell, and it leaves by intent rather than into the phone's own browser: a sign-in
     * page for a service the user has an account with belongs in the app or the browser that
     * is already signed into it, not in a WebView that is signed into nothing.
     */
    private fun linked(text: String, url: String) = detail(text).apply {
        setTextColor(palette.accent)
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onOpenUrl(url, false)
        }
        TiltEffect.apply(this)
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** The phone's own placeholder, word for word. */
        const val HINT = "ask me anything"

        /** The name box's. What she wants is a name, so that is all it asks for. */
        const val NAME_HINT = "your name"

        /**
         * Where the ring and the greeting sit, as a share of what is above the box.
         *
         * One to three puts the pair a quarter of the way down, which is where the phone
         * had them - measured off the screenshot this was drawn from, where the ring's
         * centre sits at 28% of everything above the box.
         */
        const val TOP_WEIGHT = 1f
        const val BOTTOM_WEIGHT = 3f

        const val PAGE_MARGIN_DP = 22
        const val GREETING_SP = 31f
        const val GREETING_LEADING = 1.06f
        const val GREETING_GAP_DP = 26

        /**
         * The cog's touch target, and how far the glyph sits inside it.
         *
         * Twice what it was. A 46dp key is the right size for a strip of them along the
         * bottom of the screen, where a thumb already knows where to look; up in a corner
         * of an otherwise empty page it was a speck, and the whole page has one command on
         * it - there is no crowding to be avoided by keeping it small.
         */
        /**
         * The two keys in the top corner: the glyph, and the target around it.
         *
         * The target is a good deal taller than the glyph and only a little wider, which is
         * what lets the note and the cog sit near enough to read as a pair while each stays
         * comfortably large to hit. Sized in dp rather than as a share of the screen -
         * unlike the ring, these are things a thumb touches, and a thumb is the same size
         * on every phone.
         */
        const val TOP_KEY_GLYPH_DP = 48
        const val TOP_KEY_WIDTH_DP = 64
        const val TOP_KEY_HEIGHT_DP = 92

        /** The box and the keys beside it are all one height, as they were on the phone. */
        const val BUTTON_DP = 48
        const val BUTTON_INSET_DP = 10

        /**
         * The magnifier's own inset, which draws it about 30% larger than the sparkle's.
         *
         * The difference is the empty margin inside `wp81_nav_search`'s viewport - see
         * [askButton]. Taking it out here rather than editing the artwork keeps that glyph
         * identical to the one on the navigation strip, which is the whole reason the
         * search key is recognisable as the search key.
         */
        const val MAGNIFY_INSET_DP = 6
        const val BAR_PAD_DP = 8

        const val INPUT_SP = 16f

        /** The small print under a setting, and how it is set. See [detail]. */
        const val DETAIL_SP = 12.5f
        const val DETAIL_LEADING = 1.08f
    }
}
