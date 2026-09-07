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
 * What it cannot be is the assistant. Cortana was a service, the service is gone, and the
 * half of her that mattered - the notebook, the reminders, the voice - was never something
 * a launcher could stand in for. What is left is the part the screen was actually for: you
 * have a question, and you want it to go somewhere. So the microphone is replaced by two
 * buttons, and they are the honest version of what Cortana did - one sends the question to
 * a search engine, one sends it to a model. Which engine and which model are the user's
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

    // ---------------------------------------------------------------- construction

    /** Rebuilds the program in a new theme. See [WP81Program]. */
    override fun applyPalette(palette: WP81Palette): View {
        this.palette = palette
        return createView()
    }

    fun createView(): View {
        root = FrameLayout(context)

        backdrop = CortanaBackdropView(context, palette)
        root.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        column.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        // The ring and the greeting sit together about a quarter of the way down, which is
        // where the phone put them: high enough that the keyboard never reaches them, low
        // enough that the screen does not read as a header. The two spacers are what fix
        // that proportion, and they are weighted rather than measured so it holds on a
        // screen of any height - and closes up correctly when the keyboard takes half of it.
        column.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, TOP_WEIGHT))
        column.addView(buildFace(), LinearLayout.LayoutParams(MATCH, WRAP))
        column.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, BOTTOM_WEIGHT))

        column.addView(buildAskBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        // The fog's accent wash belongs under the ring, and where the ring is depends on
        // how tall the screen turned out to be - so the backdrop is told once the two of
        // them have actually been laid out against each other.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (root.height > 0 && ring.height > 0) {
                backdrop.setRingCentre((ring.y + ring.height / 2f) / root.height)
            }
        }

        return root
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

        // A question for a model always leaves the launcher, whatever the browser setting
        // says, because leaving is the point: handed to Android as an intent, the question
        // is caught by the Copilot or ChatGPT or Claude app if one is installed, and lands
        // in a session that is already signed in. Answering it in the phone's own WebView
        // would be the one route that guarantees a signed-out web page - a model that has
        // to be logged into again before it will answer is not an assistant, it is a form.
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

    // ---------------------------------------------------------------- settings

    /**
     * The three questions this app has to ask.
     *
     * A page of its own rather than a strip of commands: these are settings, they are
     * remembered, and each of them is one answer out of several - which is a page with
     * round marks on it, not a menu.
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

        column.addView(label("search engine"), wide())
        column.addView(
            choices(
                CortanaSettings.Engine.entries.map { it to it.label },
                chosen = settings.getEngine(),
                onPick = { settings.setEngine(it) }
            ), wide()
        )

        column.addView(label("ai agent"), wide())
        column.addView(
            choices(
                CortanaSettings.Agent.entries.map { it to it.label },
                chosen = settings.getAgent(),
                onPick = {
                    settings.setAgent(it)
                    // The sparkle key names whichever model it is going to ask, and a
                    // screen reader that went on saying "ask Copilot" after the answer had
                    // been changed would be the one place in the app that lied about it.
                    rebuildAskBarDescriptions()
                }
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
        column.addView(
            note(
                "Questions for the agent always go out to the phone, so that its own app " +
                    "can answer them if you have one installed."
            ), wide()
        )

        column.addView(
            note(
                "Cortana's own service closed in 2023, so nothing here is her. What she " +
                    "did with a typed question was send it somewhere, and this is where " +
                    "yours goes."
            ), wide()
        )

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
            // Which model the sparkle asks may have changed while that page was up, and
            // the button's description says which one by name.
            rebuildAskBarDescriptions()
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
        // Not hideKeyboard(): every arrival asks for the keyboard, and this runs first on
        // the way to that - see MainActivity.showCortanaDialog. Putting it away here and
        // raising it again a line later is a flicker.
        greeting = CortanaGreetings.random(MainActivity.getUserName(context))
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

    private fun note(message: String) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 14f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(26), dp(8), dp(6))
        setLineSpacing(0f, 1.1f)
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** The phone's own placeholder, word for word. */
        const val HINT = "ask me anything"

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
    }
}
