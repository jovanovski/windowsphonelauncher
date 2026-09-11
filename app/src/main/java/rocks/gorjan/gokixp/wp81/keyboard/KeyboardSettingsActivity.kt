package rocks.gorjan.gokixp.wp81.keyboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroSlider
import rocks.gorjan.gokixp.wp81.MetroToggle
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField
import rocks.gorjan.gokixp.wp81.WP81Settings

/**
 * The keyboard's settings, as a page of the phone's own Settings.
 *
 * A whole screen rather than a panel inside the keyboard. The first attempt put these on a
 * page that replaced the keys, reached by holding a key, on the reasoning that a setting you
 * notice while typing should be adjustable without leaving what you are typing in. That is
 * true and it was still wrong: Windows Phone's settings were pages - a big lowercase title, a
 * column of rows, the back key to leave - and a settings screen that is a keyboard-shaped
 * rectangle at the bottom of somebody else's app is not that. It also had nowhere to grow, and
 * there will be more of these.
 *
 * So it is an Activity, styled like every other page in the shell, and reached two ways: by
 * holding the full stop on the keyboard, and from Android's own keyboard settings, because it is
 * declared as this input method's `settingsActivity` in `res/xml/method.xml`.
 *
 * Built from the shell's own furniture - [MetroPageHeader], [MetroToggle], [MetroSlider] -
 * rather than new controls or anything from Material, for the same reason as everywhere else:
 * a page that invented its own switch would look like a different application.
 */
class KeyboardSettingsActivity : Activity() {

    private lateinit var palette: WP81Palette
    private lateinit var themeManager: WP81Settings

    private lateinit var holdValue: TextView
    private lateinit var vibrationValue: TextView
    private lateinit var keyHeightValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeManager = WP81Settings(this)
        // Before a single setting is read, and for the same reason the keyboard does it in
        // its own onCreate: this page can be opened from the launcher's settings without the
        // keyboard ever having been turned on, so it can be the first thing in this process
        // to touch the keyboard's file. Migrating afterwards would copy the old values over
        // whatever was changed here and quietly undo it.
        themeManager.migrateKeyboardSettings()
        // Through [KeyboardAppearance] like everything else in this process: the accent is
        // the launcher's, chosen on the launcher's page, and this page is not in the
        // launcher's process any more.
        palette = KeyboardAppearance.palette(themeManager)

        // The page is the background, so the system bars are painted to match rather than
        // sitting as two strips of a different black at either end.
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        page.addView(
            MetroPageHeader(this, palette).apply {
                setTitle("keyboard")
                onBack = { finish() }
            },
            wide()
        )

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(section("languages"))
        column.addView(languagesNote())
        column.addView(languageList())
        column.addView(section("typing"))
        column.addView(autocorrectRow())
        column.addView(autoCapsRow())
        column.addView(holdRow())
        column.addView(slideKeysRow())
        column.addView(vibrationRow())
        column.addView(soundRow())
        column.addView(keyPreviewRow())
        column.addView(joystickRow())
        column.addView(shortBottomRow())
        column.addView(keyHeightRow())
        column.addView(testBox())
        column.addView(section("gifs"))
        column.addView(giphyRow())
        column.addView(section("dictation"))
        column.addView(offlineVoiceRow())
        column.addView(section("about"))
        column.addView(about())

        page.addView(ScrollView(this).apply { addView(column, wide()) }, wide())

        // Held clear of the status bar and the gesture handle.
        //
        // Not optional on this target: an app built against Android 15 or later is laid out
        // edge to edge whether it asks to be or not, so a page that does nothing about it
        // draws its title underneath the clock. The colours behind both bars are already the
        // page's own, set above, so this only has to move the content - which is why it is
        // padding on the page rather than a window flag.
        // The keyboard is part of that. This page is the one place in the shell that expects
        // to be looked at *while* the keyboard is up - that is what the test box is for - so
        // the bottom padding follows whichever is taller, the navigation bar or the keyboard
        // over it. Without it the page keeps its full height, the scroller believes it has
        // room it does not have, and the key-height slider ends up underneath the very keys
        // it is resizing.
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        setContentView(page)
    }

    // ---------------------------------------------------------------- rows

    /**
     * Whether a space may replace a word by itself.
     *
     * The note underneath matters as much as the switch. Somebody turning this off wants to
     * know they are not turning suggestions off with it, and somebody turning it on wants to
     * know what they are agreeing to.
     */
    private fun autocorrectRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardAutocorrect(), animated = false)
            onChanged = { themeManager.setWP81KeyboardAutocorrect(it) }
        }
        return row(
            "correct words automatically",
            "a space replaces the word with the keyboard's best guess. " +
                "suggestions are offered either way, and the first of them is always " +
                "exactly what you typed.",
            toggle
        )
    }

    /** Whether shift comes on by itself at the start of a sentence. */
    private fun autoCapsRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardAutoCapitalise(), animated = false)
            onChanged = { themeManager.setWP81KeyboardAutoCapitalise(it) }
        }
        return row(
            "capitalise sentences",
            "shift turns itself on after a full stop, and for the first letter of a field.",
            toggle
        )
    }

    private fun languagesNote(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), 0)
        addView(
            detail(
                "turn on as many as you like. the globe in the navigation bar moves " +
                    "between them, and the space bar says which one you are typing in."
            ),
            wide()
        )
    }

    /**
     * The languages, in a box of their own that scrolls.
     *
     * Twenty-two of them is longer than the rest of this page put together, and a list that
     * long inside the page simply pushes everything else off the bottom - somebody looking
     * for the autocorrect switch would scroll past Greek and Ukrainian to reach it. So the
     * list gets a fixed height and scrolls inside itself, which also makes it *look* like a
     * list of things to choose from rather than like the page having gone on too long.
     *
     * The height is a fraction of the screen rather than a number of rows: rows are as tall
     * as the text in them and that changes with the language, and the point of the box is
     * that the sections after it stay visible, which is a claim about the screen.
     */
    private fun languageList(): View {
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (layout in Layouts.ALL_LANGUAGES) addView(languageRow(layout), wide())
        }

        return object : ScrollView(this) {
            // A scroller inside a scroller, both vertical. Without this the page takes the
            // drag and the inner list never moves - the parent is entitled to intercept, and
            // by default it does. Claiming the gesture on the way down and giving it back at
            // the end means a drag that starts on a language scrolls the languages, and one
            // that starts anywhere else scrolls the page.
            override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != android.view.MotionEvent.ACTION_UP &&
                        event.actionMasked != android.view.MotionEvent.ACTION_CANCEL
                )
                return super.onTouchEvent(event)
            }
        }.apply {
            // A ScrollView takes focus when it is laid out, and the page scrolls to whatever
            // has focus - which put the top of this box above the screen and took the heading
            // that names it with it. The box is scrolled by dragging it; it has no business
            // holding focus.
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isVerticalScrollBarEnabled = true
            // The bar is what says "there is more below this". Without it a box that happens
            // to end on a row boundary looks like the whole list.
            isScrollbarFadingEnabled = false
            addView(rows, wide())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * LANGUAGE_LIST_SHARE).toInt()
            )
        }
    }

    /**
     * One language, on or off.
     *
     * The switch writes the setting and then tells Android, which are two different things -
     * declaring a language is not the same as the system offering it. See [KeyboardLanguages].
     *
     * Turning off the last one is refused rather than prevented: the switch springs back, so
     * it is obvious what happened and why, where a switch that simply would not move looks
     * broken.
     */
    private fun languageRow(layout: KeyboardLayout): View {
        val toggle = MetroToggle(this, palette)
        toggle.set(KeyboardLanguages.isEnabled(themeManager, layout), animated = false)
        toggle.onChanged = { on ->
            val applied = KeyboardLanguages.setEnabled(this, themeManager, layout, on)
            if (!applied) toggle.set(true, animated = true)
        }
        return row(layout.name, whatItBrings(layout), toggle)
    }

    /**
     * What turning a language on actually gets you, said plainly.
     *
     * Twenty-two languages ship a layout and one of them ships a word list, because a word
     * list is two to three megabytes and putting twenty-two in the app would cost more than
     * everything else in it put together. A language without one types perfectly well and
     * simply makes no suggestions - which is a real difference and one somebody is entitled
     * to know about before they turn it on rather than after they have typed a paragraph
     * waiting for a correction that was never coming.
     *
     * `tools/dictbuild/build_all.sh` can build a list for any of the twenty-two; what is
     * missing is a way to get one onto a phone without carrying them all in the APK.
     *
     * Dictation is the same shape of answer and a different list: Vosk publishes offline
     * models for about half of these and, as ever, not for Macedonian.
     */
    private fun whatItBrings(layout: KeyboardLayout): String {
        val words = hasDictionary(layout.language)
        val size = voskModels.sizeOf(layout.language)

        // Short enough for one line at this width, deliberately: a two-line note fits three
        // languages in the box and turns a list into a wall.
        val suggestions = if (words) "suggestions" else "no word list"
        val dictation =
            if (size > 0) "offline dictation, $size MB"
            else "dictation via the phone"

        return "$suggestions \u00b7 $dictation"
    }

    /** Only ever asked for [VoskModels.sizeOf] here - nothing on this page downloads one. */
    private val voskModels by lazy { VoskModels(this) }

    /** Whether a word list for [language] is in the app. See `Dictionary.load`. */
    private fun hasDictionary(language: String): Boolean = language in shippedDictionaries

    /**
     * The word lists actually in the APK, asked of the assets rather than listed here.
     *
     * A hardcoded list would be a second place to remember when one is added or dropped, and
     * the sort of thing that goes stale silently - the row would promise suggestions that
     * never came. One directory listing on the way into this page settles it.
     */
    private val shippedDictionaries: Set<String> by lazy {
        try {
            assets.list("keyboard").orEmpty()
                .filter { it.endsWith(".trie") }
                .map { it.removeSuffix(".trie") }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Whether a keystroke buzzes, and how hard.
     *
     * The switch says what it says: on means the keys vibrate, and the strength is right
     * underneath it where somebody who has just turned it on will look. Off means off, and
     * there is nothing to set.
     *
     * The far left of the slider is **the phone's own** rather than the weakest explicit
     * setting, and that is a real distinction rather than a label: there it goes through
     * `Haptics` and picks up whatever waveform the manufacturer tuned for a keystroke, and it
     * follows the system's touch-feedback switch. Anywhere else it drives the vibrator
     * directly and follows this slider instead. Left is where it starts, and the phone's tick
     * is a light one, so "further right is firmer" holds across the join.
     *
     * Nothing buzzes while the slider is moving - a control that fired on every pixel of
     * travel would be unusable. It buzzes once, when the finger comes off, which is when you
     * want to feel what you have chosen.
     */
    private fun vibrationRow(): View {
        val setting = themeManager.getWP81KeyboardVibration()
        vibrationValue = detail(vibrationText(setting))

        val slider = MetroSlider(this).apply {
            applyPalette(palette)
            visibility = if (setting == 0) View.GONE else View.VISIBLE
            value = sliderPositionOf(setting)
            onValueChanged = { fraction ->
                val strength = strengthOf(fraction)
                vibrationValue.text = vibrationText(strength)
                themeManager.setWP81KeyboardVibration(strength)
                KeyboardHaptics.refresh(this@KeyboardSettingsActivity, themeManager)
            }
            setOnTouchListener { view, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    KeyboardHaptics.key(view)
                }
                false
            }
        }

        val toggle = MetroToggle(this, palette).apply {
            set(setting != 0, animated = false)
            onChanged = { on ->
                // Turning it back on lands where the slider was left rather than at a
                // default, so switching it off to think and on again is not a reset.
                val next = if (on) strengthOf(slider.value) else 0
                themeManager.setWP81KeyboardVibration(next)
                KeyboardHaptics.refresh(this@KeyboardSettingsActivity, themeManager)
                vibrationValue.text = vibrationText(next)
                slider.visibility = if (on) View.VISIBLE else View.GONE
                if (on) KeyboardHaptics.key(this)
            }
        }

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("vibrate on every key"), wide())
            addView(vibrationValue, wide())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                toggle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = pad() }
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
            addView(top, wide())
            addView(slider, wide())
        }
    }

    /** What the current setting says under the heading. */
    private fun vibrationText(strength: Int): String = when {
        strength == WP81Settings.WP81_KB_VIBRATION_SYSTEM -> "the phone's own"
        strength <= 0 -> "off"
        else -> "$strength%"
    }

    /**
     * Where the slider sits for a setting.
     *
     * Off and "the phone's own" are both the far left: off does not show the slider at all,
     * and when the switch is turned back on the left is exactly where it should land.
     */
    private fun sliderPositionOf(setting: Int): Float =
        if (setting <= 0) 0f else setting / WP81Settings.WP81_KB_VIBRATION_MAX.toFloat()

    /** And what a slider position means. The far left is the phone's own, not the weakest. */
    private fun strengthOf(fraction: Float): Int {
        if (fraction <= 0f) return WP81Settings.WP81_KB_VIBRATION_SYSTEM
        val raw = (fraction * WP81Settings.WP81_KB_VIBRATION_MAX).toInt()
        // In fives, so dragging gives 40 rather than 38.
        return ((raw / STEP_PERCENT) * STEP_PERCENT).coerceAtLeast(STEP_PERCENT)
    }

    /** Whether a keystroke clicks. */
    private fun soundRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardSound(), animated = false)
            onChanged = { on ->
                themeManager.setWP81KeyboardSound(on)
                KeyboardSounds.refresh(this@KeyboardSettingsActivity, themeManager)
                // Heard once as it is switched on, which is the only way to know what has
                // been agreed to without going and typing something. After a moment, not at
                // once: switching it on is what opens the sound pool, and a file is not
                // playable the instant it is handed over to be loaded - a preview fired on
                // the same frame would be the one click nobody hears.
                if (on) postDelayed({ KeyboardSounds.tap() }, PREVIEW_DELAY_MS)
            }
        }
        return row(
            "sound on every key",
            "the phone's own keypress click. it plays on silent too, and follows the " +
                "volume keys rather than the touch sounds setting.",
            toggle
        )
    }

    /**
     * Whether a hold on shift or `&123` can be slid across the keys it brings up.
     *
     * Under the hold slider rather than beside the other gestures, because it is the other
     * thing a hold does and its whole feel depends on the number above it: a long hold makes
     * the slide something you have to wait for, and a short one makes it something an ordinary
     * press falls into.
     */
    private fun slideKeysRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardSlideKeys(), animated = false)
            onChanged = { themeManager.setWP81KeyboardSlideKeys(it) }
        }
        return row(
            "slide from shift and &123",
            "hold either key to bring up the capitals or the symbols, slide onto the one " +
                "you want and let go. it is typed, and the keyboard goes back to where it " +
                "was. letting go without moving still locks shift, or stays on the symbols.",
            toggle
        )
    }

    /** Whether a pressed key lifts its letter clear of the finger. */
    private fun keyPreviewRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardKeyPreview(), animated = false)
            onChanged = { themeManager.setWP81KeyboardKeyPreview(it) }
        }
        return row(
            "show the letter above the key",
            "a thumb covers the key it is pressing, so the letter appears just above it " +
                "while the key is held. the key itself lights up either way.",
            toggle
        )
    }

    /**
     * Whether the caret joystick is showing.
     *
     * The note says the second half out loud, because it is a thing being taken away as well
     * as one being added, and somebody who has learned to slide the space bar is entitled to
     * know why it stopped rather than to find out by pressing space and getting a caret.
     */
    private fun joystickRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardJoystick(), animated = false)
            onChanged = { themeManager.setWP81KeyboardJoystick(it) }
        }
        return row(
            "cursor joystick",
            "a dot in the gap above the bottom row. hold it and drag to move the cursor " +
                "through the text. while it is on, sliding the space bar types a space " +
                "instead of moving the cursor.",
            toggle
        )
    }

    /**
     * How tall the keys are.
     *
     * The only setting on this page whose effect is invisible from the page, which is why it
     * is the only one with something to type into underneath it - see [testBox]. The two are
     * next to each other on purpose: the slider is useless without somewhere to watch it
     * work, and a test box three sections away is a test box nobody finds.
     *
     * Written on every step of the drag rather than when the finger lifts. That is a
     * preference write and a whole keyboard re-measured several times a second, which would
     * be indefensible for a setting nobody was watching - and is exactly right for this one,
     * because the keyboard is on screen underneath and the point is to see the keys move
     * under the finger. Stepping in fives keeps it to a couple of dozen writes for a full
     * sweep of the slider, and the guard below drops the rest.
     */
    private fun keyHeightRow(): View {
        val start = themeManager.getWP81KeyboardKeyHeight()
        keyHeightValue = detail(keyHeightText(start))
        var applied = start

        val slider = MetroSlider(this).apply {
            applyPalette(palette)
            value = keyHeightFractionOf(start)
            onValueChanged = { fraction ->
                val percent = keyHeightOf(fraction)
                if (percent != applied) {
                    applied = percent
                    keyHeightValue.text = keyHeightText(percent)
                    themeManager.setWP81KeyboardKeyHeight(percent)
                }
            }
        }

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("key height"), wide())
            addView(keyHeightValue, wide())
        }
        val reset = TextView(this).apply {
            this.text = "reset"
            textSize = DETAIL_SP
            typeface = ResourcesCompat.getFont(
                this@KeyboardSettingsActivity, R.font.segoeui_regular
            )
            setTextColor(palette.accent)
            isClickable = true
            setOnClickListener {
                applied = WP81Settings.WP81_KB_KEY_HEIGHT_DEFAULT
                slider.value = keyHeightFractionOf(applied)
                keyHeightValue.text = keyHeightText(applied)
                themeManager.setWP81KeyboardKeyHeight(applied)
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                reset,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = pad() }
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad(), 0)
            addView(top, wide())
            addView(slider, wide())
        }
    }

    /**
     * Somewhere to type, so the settings above can be seen doing something.
     *
     * A settings page for a keyboard has an odd problem: every other page on the phone can
     * show you what a switch did, and this one cannot, because the thing it configures is not
     * on screen while you are reading about it. So the page carries a field of its own. Tap
     * it and the keyboard being configured comes up underneath, and the key height, the click,
     * the letter flag and the suggestion bar are all right there to be tried against the
     * controls that set them.
     *
     * It goes under the key-height slider rather than at the top or the bottom of the page,
     * because that is the setting that needs it: the page scrolls, and with the keyboard up
     * the slider and the field are the two things that have to be on screen together.
     *
     * Nothing is done with what is typed into it, and nothing is kept.
     */
    private fun testBox(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), 0, pad(), pad())
        addView(
            detail("tap here to try the keyboard while you change these."),
            wide().apply { bottomMargin = pad() / 2 }
        )
        addView(
            EditText(this@KeyboardSettingsActivity).apply {
                hint = "type something"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setLines(TEST_BOX_LINES)
                gravity = Gravity.TOP or Gravity.START
                textSize = LABEL_SP
                typeface = ResourcesCompat.getFont(
                    this@KeyboardSettingsActivity, R.font.segoeui_regular
                )
                includeFontPadding = false
                setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
                palette.applyToField(this)
            },
            wide()
        )
    }

    /** What the current setting says under the heading. */
    private fun keyHeightText(percent: Int): String =
        if (percent == WP81Settings.WP81_KB_KEY_HEIGHT_DEFAULT) "the phone's own"
        else "$percent%"

    private fun keyHeightFractionOf(percent: Int): Float {
        val span = WP81Settings.WP81_KB_KEY_HEIGHT_MAX - WP81Settings.WP81_KB_KEY_HEIGHT_MIN
        return (percent - WP81Settings.WP81_KB_KEY_HEIGHT_MIN).toFloat() / span
    }

    private fun keyHeightOf(fraction: Float): Int {
        val span = WP81Settings.WP81_KB_KEY_HEIGHT_MAX - WP81Settings.WP81_KB_KEY_HEIGHT_MIN
        val raw = WP81Settings.WP81_KB_KEY_HEIGHT_MIN + (fraction * span).toInt()
        // In fives, so dragging gives 115 rather than 113 - and so a full sweep of the
        // slider is a couple of dozen writes rather than one per pixel of travel.
        return (raw / STEP_PERCENT * STEP_PERCENT)
            .coerceIn(WP81Settings.WP81_KB_KEY_HEIGHT_MIN, WP81Settings.WP81_KB_KEY_HEIGHT_MAX)
    }

    /** Whether the bottom row is shorter than the letters above it. */
    private fun shortBottomRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardShortBottomRow(), animated = false)
            onChanged = { themeManager.setWP81KeyboardShortBottomRow(it) }
        }
        return row(
            "shorter bottom row",
            "nothing on it is a letter, so it does not need a letter's height. " +
                "turn this off for four even rows.",
            toggle
        )
    }

    /**
     * Which engine dictates.
     *
     * The note says what the trade actually is, because it is not obvious from the switch and
     * it is the reason the switch exists: one of these keeps your voice on the phone and the
     * other is usually better at understanding it.
     */
    private fun offlineVoiceRow(): View {
        val toggle = MetroToggle(this, palette).apply {
            set(themeManager.getWP81KeyboardOfflineVoice(), animated = false)
            onChanged = { themeManager.setWP81KeyboardOfflineVoice(it) }
        }
        return row(
            "dictate on the phone",
            "speech is recognised locally and nothing is sent anywhere. needs a 40 MB " +
                "language model, downloaded once. turn this off to use the phone's own " +
                "recogniser, which is usually more accurate and usually not local. " +
                "languages with no offline model - Macedonian among them - use it either way.",
            toggle
        )
    }

    /** How long a key must be held before it offers the symbol in its corner. */
    private fun holdRow(): View {
        holdValue = detail("${themeManager.getWP81KeyboardHoldMs()} ms")
        val slider = MetroSlider(this).apply {
            applyPalette(palette)
            value = fractionOf(themeManager.getWP81KeyboardHoldMs())
            onValueChanged = { fraction ->
                val millis = millisOf(fraction)
                holdValue.text = "$millis ms"
                themeManager.setWP81KeyboardHoldMs(millis)
            }
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
            addView(label("hold to show symbols"), wide())
            addView(holdValue, wide())
            addView(slider, wide())
        }
        return holder
    }

    /**
     * The GIPHY key the emoji panel's `gif` half searches with.
     *
     * A field here rather than a constant in the build, because these are issued per person:
     * one compiled into the app would be one key answering for everybody who installed it,
     * against one rate limit, and it would be sitting in the source for anyone to take. So the
     * app ships without one, and this is where somebody puts theirs.
     *
     * Written on every keystroke rather than behind a save button. There is nothing to
     * validate against - the only test of a key is whether GIPHY answers - and a settings page
     * where one row alone has to be confirmed is a row people will leave unconfirmed.
     */
    private fun giphyRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), pad())
        addView(label("giphy key"), wide())
        addView(
            detail(
                "the gif key beside abc searches giphy, which needs a key of your own. " +
                    "tap here to get one - it is free - then paste it in below."
            ).apply {
                isClickable = true
                setOnClickListener { open(GIPHY_DEVELOPERS) }
            },
            wide()
        )
        addView(
            EditText(this@KeyboardSettingsActivity).apply {
                setText(themeManager.getWP81KeyboardGiphyKey())
                hint = "paste your key here"
                // A key is a run of random characters: nothing to correct, nothing to
                // capitalise, and nothing worth offering to the dictionary that learns words.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
                textSize = LABEL_SP
                typeface = ResourcesCompat.getFont(
                    this@KeyboardSettingsActivity, R.font.segoeui_regular
                )
                includeFontPadding = false
                setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
                palette.applyToField(this)
                doAfterTextChanged {
                    themeManager.setWP81KeyboardGiphyKey(it?.toString().orEmpty())
                }
            },
            wide().apply { topMargin = pad() / 2 }
        )
    }

    /** Opens [url] in whatever the phone browses with, or does nothing if it browses with nothing. */
    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // No browser. Nothing useful to say about it on a settings page.
        }
    }

    private fun about(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), pad() / 2, pad(), pad())
        addView(
            detail(
                "word lists from the OpenSubtitles frequency lists (CC BY-SA 3.0) and " +
                    "dwyl/english-words. next-word predictions counted from the " +
                    "OpenSubtitles corpus itself, via OPUS. emoji names from Unicode CLDR."
            ),
            wide()
        )
    }

    private fun row(title: String, note: String, control: View): View {
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title), wide())
            addView(detail(note), wide())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                control,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = pad() }
            )
        }
    }

    // ---------------------------------------------------------------- furniture

    /** A section heading: small, capitalised by the eye rather than by the text, in the accent. */
    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = SECTION_SP
        typeface = ResourcesCompat.getFont(this@KeyboardSettingsActivity, R.font.segoeui_semibold)
        setTextColor(palette.accent)
        includeFontPadding = false
        setPadding(pad(), pad(), pad(), 0)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = LABEL_SP
        typeface = ResourcesCompat.getFont(this@KeyboardSettingsActivity, R.font.segoeui_regular)
        setTextColor(palette.foreground)
    }

    private fun detail(text: String) = TextView(this).apply {
        this.text = text
        textSize = DETAIL_SP
        typeface = ResourcesCompat.getFont(this@KeyboardSettingsActivity, R.font.segoeui_regular)
        setTextColor(palette.foregroundSubtle)
    }

    private fun wide() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun pad() = (PAD_DP * resources.displayMetrics.density).toInt()

    /**
     * How much of the screen the language box takes.
     *
     * A little over a third: enough that four or five languages are visible at once, which is
     * what makes it read as a list, and little enough that the "typing" heading underneath it
     * is on screen at the same time - which is the whole reason the box exists.
     */
    private val LANGUAGE_LIST_SHARE = 0.45f


    /** The slider runs the range the setting allows, so its ends are the real limits. */
    private fun fractionOf(millis: Int): Float =
        (millis - WP81Settings.WP81_KB_HOLD_MIN).toFloat() /
            (WP81Settings.WP81_KB_HOLD_MAX - WP81Settings.WP81_KB_HOLD_MIN)

    private fun millisOf(fraction: Float): Int {
        val span = WP81Settings.WP81_KB_HOLD_MAX - WP81Settings.WP81_KB_HOLD_MIN
        val raw = WP81Settings.WP81_KB_HOLD_MIN + (fraction * span).toInt()
        // Rounded to something a person would recognise, so dragging gives 350, not 347.
        return (raw / STEP_MS) * STEP_MS
    }

    private companion object {

        /** Where a GIPHY key comes from. See [giphyRow]. */
        const val GIPHY_DEVELOPERS = "https://developers.giphy.com/dashboard/"

        const val PAD_DP = 24f
        const val SECTION_SP = 12f
        const val LABEL_SP = 17f
        const val DETAIL_SP = 13f
        const val STEP_MS = 25

        /** Tall enough to type a sentence into and watch it wrap, short enough to spare. */
        const val TEST_BOX_LINES = 3

        /** Long enough for the click to have loaded, short enough to still be an answer. */
        const val PREVIEW_DELAY_MS = 250L

        /** The strength slider moves in fives, for the same reason the hold moves in steps. */
        const val STEP_PERCENT = 5
    }
}
