package rocks.gorjan.gokixp.apps.messaging

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.people.ContactFace
import rocks.gorjan.gokixp.apps.people.HubApp
import rocks.gorjan.gokixp.apps.people.briefMomentOf
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MessageStore
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField

/**
 * Messaging: who has been in touch, and what was said.
 *
 * A program of its own rather than a page of the People hub, which is where this shell had
 * it and where the phone never did. A conversation is not a fact about a person the way a
 * number or an address is - it is a thing that goes on happening, arrives while you are
 * somewhere else and asks to be answered - and an address book that quietly held the
 * phone's whole message store was an address book you could not close without closing your
 * texts.
 *
 * One page, because there is one thing here: everybody who has written, most recent first,
 * and the whole of a conversation one tap in. What Windows Phone had beside it was a second
 * pivot for Messenger and Facebook chat, which this phone has no business pretending to.
 *
 * Everything about the *person* is People's - their card, and the offer to write them down
 * if the phone does not know them. This app asks the host to open it. See [onShowContact].
 */
class MessagingApp(
    context: Context,
    palette: WP81Palette,
    onRequestPermissions: (Array<String>) -> Unit,
    onNotify: (String, String) -> Unit,
    /**
     * Asks the system to make this the messaging app.
     *
     * The host does the asking because the answer arrives as an activity result, and this
     * is not an activity. Taking the role moves work rather than only moving a screen:
     * from that moment every text on the device is delivered here alone. See
     * MainActivity.requestSmsRole.
     */
    private val onBecomeMessenger: () -> Unit,
    /** Opens People on somebody's card. Their card is not this app's page. */
    private val onShowContact: (Long) -> Unit,
    /** Opens People's editor on a number this phone does not have a name for. */
    private val onNewContact: (String) -> Unit
) : HubApp(context, palette, onRequestPermissions, onNotify) {

    override val appName = "Messaging"

    private lateinit var panorama: MetroPanorama
    private lateinit var threadsColumn: LinearLayout

    /** The conversations the list holds but has not built rows for yet. */
    private val pendingThreads = mutableListOf<MessageStore.Conversation>()

    private var contacts: List<PeopleStore.Contact> = emptyList()
    private var conversations: List<MessageStore.Conversation> = emptyList()

    /** Whether the last read of the store threw rather than coming back empty. */
    private var messagesFailed = false

    /**
     * What tells this app a message has arrived.
     *
     * Held only while the app is on screen - see [createView] - because the store is
     * written to by whichever app on the phone delivers messages, and a launcher that
     * re-read it every time that happened while nobody was looking would be doing the
     * work of a page that is not open.
     */
    private var messagesWatch: android.database.ContentObserver? = null

    /** The conversation that is open, if one is. It re-reads when the app does. */
    private var openThread: MessageThread? = null

    // ---------------------------------------------------------------- construction

    override fun applyPalette(palette: WP81Palette): View {
        this.palette = palette
        return createView()
    }

    fun createView(): View {
        buildRoot()

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        panorama = MetroPanorama(context, palette).apply {
            setPadding(dp(PAGE_MARGIN_DP), 0, 0, 0)
            clipToPadding = false
            clipChildren = false
        }
        // The wordmark belongs to the panorama rather than sitting above it, so it drifts
        // as the section is pulled underneath. Lowercase, as everything on this platform is.
        panorama.setTitle("messaging")

        threadsColumn = sectionColumn()
        // Nameless, as People's one section is: there is nothing to swipe to, and a strip
        // reading "conversations" under a wordmark reading "messaging" is the same page
        // named twice. See MetroPanorama.rebuildStrip.
        panorama.addPage("", page(threadsColumn) { extendMessages() })

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = buildAppBar()
        appBar = bar
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // The list stops above the strip rather than running under it, so the last row is
        // reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        addOverlayFurniture()

        // The message store, watched while the app is on screen. A text arriving is
        // written down by whichever app on this phone delivers messages, and this is how
        // that reaches the list showing it - there is no broadcast to listen for.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                messagesWatch = MessageStore.watch(context) { readMessages() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                MessageStore.unwatch(context, messagesWatch)
                messagesWatch = null
            }
        })

        refresh()
        return root
    }

    /**
     * The strip: a new message, and the role behind the dots.
     *
     * Starting one with somebody the list has no row for is the only command here that is
     * about the app rather than about a conversation already on screen, so it is the ring.
     */
    private fun buildAppBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        bar.addCommand(ADD_ICON) { showNewMessage() }
        bar.menu = {
            buildList {
                add(MetroAppBar.Item("refresh") { refresh() })
                if (!MessageStore.isTheMessenger(context)) {
                    add(MetroAppBar.Item("default messaging app") { onBecomeMessenger() })
                }
            }
        }
        return bar
    }

    // ---------------------------------------------------------------- reading

    /**
     * Re-reads the store and the book, and fills the list from them.
     *
     * Called on the way in, after a permission is answered, and when the store says
     * something has changed. The book is read too, because a conversation's row is titled
     * with whoever the phone knows that number to be.
     */
    fun refresh() {
        openThread?.reload()
        if (!PeopleStore.canRead(context)) {
            contacts = emptyList()
        } else {
            PeopleStore.all(context) { people -> contacts = people }
        }
        readMessages()
    }

    private fun readMessages() {
        if (!MessageStore.canRead(context)) {
            conversations = emptyList()
            messagesFailed = false
            bindMessages()
            return
        }
        MessageStore.conversations(context) { result ->
            conversations = result.conversations
            messagesFailed = result.failed
            bindMessages()
        }
    }

    /**
     * Who has been in touch, most recently first.
     *
     * One row per person rather than per message, which is what a conversation is - and
     * the reason the store's own threads are not used as they stand: see
     * [MessageStore.keyOf]. Tapping a row opens the whole of it, because unlike a call
     * there is nothing a message row can do on its own that is worth putting behind a
     * word - reading it *is* the thing.
     */
    private fun bindMessages() {
        threadsColumn.removeAllViews()
        if (!MessageStore.canRead(context)) {
            threadsColumn.addView(
                note("no access to your messages.  tap to allow") {
                    onRequestPermissions(MessageStore.readPermissions())
                }, wide())
            return
        }
        // The offer to take the role, at the top of the list it is about. Only while
        // somebody else holds it: once this is the messaging app there is nothing to say.
        if (!MessageStore.isTheMessenger(context)) {
            threadsColumn.addView(
                note("another app is handling messages.  tap to make Messaging your " +
                    "messaging app") { onBecomeMessenger() }, wide())
        }
        if (messagesFailed) {
            threadsColumn.addView(note("your messages could not be read"), wide())
            return
        }
        if (conversations.isEmpty()) {
            threadsColumn.addView(
                note("no messages yet.  tap + to start one") { showNewMessage() }, wide())
            return
        }
        pendingThreads.clear()
        pendingThreads.addAll(conversations)
        extendMessages()
    }

    /** Adds the next screenful of conversations, if there are any left. */
    private fun extendMessages() {
        var built = 0
        while (pendingThreads.isNotEmpty() && built < CHUNK) {
            threadsColumn.addView(threadRow(pendingThreads.removeAt(0)), wide())
            built++
        }
    }

    /**
     * One conversation on the list: who, the last thing said, and when.
     *
     * A face at the head of it like the contact list, because this is a page about people
     * and the number it is really keyed on is the least interesting thing on the row. An
     * unread one is in the accent with the count beside it - the same way the call history
     * says a call was missed, which is the same fact about the same person.
     */
    private fun threadRow(thread: MessageStore.Conversation): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                showThread(thread.address, thread.contact)
            }
            setOnLongClickListener {
                showThreadMenu(thread, this)
                true
            }
            TiltEffect.apply(this)
        }
        row.addView(
            ContactFace(context, palette).apply {
                setLetterSize(15f)
                // The title rather than the contact, so a sender the book has never heard
                // of - a bank, a network - still gets its letters instead of a blank
                // square. A bare number gets neither; see PeopleStore.initialsOf.
                show(thread.title, thread.contact?.photoUri)
            },
            LinearLayout.LayoutParams(dp(THREAD_FACE_DP), dp(THREAD_FACE_DP))
        )

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = thread.title +
                if (thread.unread > 0) "  (${thread.unread})" else ""
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (thread.unread > 0) palette.accent else palette.foreground)
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            // Said by whom, as well as what: half a conversation is your own half, and a
            // list that showed only the words would keep answering a question nobody asked.
            this.text = if (thread.outgoing) "you:  ${thread.snippet}" else thread.snippet
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(10)
        })

        row.addView(TextView(context).apply {
            this.text = briefMomentOf(context, thread.at)
            typeface = font(R.font.segoeui_regular)
            textSize = 11f
            maxLines = 1
            setTextColor(palette.foregroundSubtle)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    private fun showThreadMenu(thread: MessageStore.Conversation, anchor: View) {
        showMenu(
            thread.title,
            buildList {
                add(WP81ContextMenu.Item("call") { place(thread.address) })
                val person = thread.contact
                if (person != null) {
                    add(WP81ContextMenu.Item("view contact") { onShowContact(person.id) })
                } else {
                    add(WP81ContextMenu.Item("save to contacts") { onNewContact(thread.address) })
                }
            },
            anchor
        )
    }

    // ---------------------------------------------------------------- conversation

    /**
     * Opens the conversation with one number.
     *
     * Everywhere in this shell that offers to text somebody comes here rather than handing
     * the number to another program: a launcher with its own messaging app that sent you
     * out to somebody else's to say a sentence would be two apps for one message store.
     * See [MessageThread] for what happens once you are in it.
     */
    fun showThread(
        address: String,
        person: PeopleStore.Contact?,
        draft: String? = null
    ) {
        if (address.isBlank()) return
        lateinit var page: MessageThread
        page = MessageThread(
            context = context,
            palette = palette,
            address = address,
            contact = person,
            draft = draft,
            onBack = { dismissOverlay(page) },
            onProfile = { onShowContact(it) },
            onAddContact = { onNewContact(it) },
            onRequestPermissions = onRequestPermissions,
            onMenu = { title, items, anchor -> showMenu(title, items, anchor) },
            onNotify = { message -> notify(message) }
        )
        openThread = page
        pushOverlay(page)
    }

    override fun dismissOverlay(view: View) {
        if (view === openThread) openThread = null
        super.dismissOverlay(view)
    }

    /**
     * The way in from outside the app: a notification tapped, an `sms:` link followed, a
     * picture message this app cannot show. The list is shown underneath either way, so
     * backing out of a conversation opened this way lands somewhere that makes sense
     * rather than closing the app.
     */
    fun showMessages(address: String? = null, draft: String? = null) {
        if (!address.isNullOrBlank()) showThread(address, null, draft)
    }

    /**
     * Starting one with somebody the list has no row for yet.
     *
     * The field at the top is both halves of the question: type a name and it narrows the
     * book, type a number and the first row offers to text it. Which is how the phone did
     * it, and it is the only sensible answer to a screen that has to serve "text my
     * brother" and "text the number on this delivery slip" with one control.
     */
    private fun showNewMessage() {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setTitle("new message")
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        val to = EditText(context).apply {
            hint = "name or number"
            typeface = font(R.font.segoeui_regular)
            textSize = 17f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(10), dp(9), dp(10), dp(9))
            palette.applyToField(this)
        }
        page.addView(to, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = dp(PAGE_MARGIN_DP)
            marginEnd = dp(PAGE_MARGIN_DP)
            bottomMargin = dp(10)
        })

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        page.addView(
            ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )

        fun repaint() {
            val typed = to.text?.toString()?.trim().orEmpty()
            val lower = typed.lowercase()
            column.removeAllViews()
            // A number typed out in full is somebody who is not in the book, which is half
            // of what this page is for. Offered as the first row rather than as a button
            // somewhere else, because at that point it is what the typing was for.
            if (typed.none { it.isLetter() } && typed.count { it.isDigit() } >= MATCH_FROM) {
                column.addView(pickRow(null, "text  $typed") {
                    hideKeyboard()
                    dismissOverlay(page)
                    showThread(typed, null)
                }, wide())
            }
            val found = contacts.filter { person ->
                lower.isEmpty() || person.name.lowercase().let { name ->
                    name.startsWith(lower) ||
                        name.split(' ', '-', '.').any { it.startsWith(lower) }
                }
            }.take(PICK_ROWS)
            if (found.isEmpty() && column.childCount == 0) {
                column.addView(note("nobody by that name"), wide())
            }
            for (person in found) {
                column.addView(pickRow(person, person.name) {
                    chooseNumber(person) { number ->
                        hideKeyboard()
                        dismissOverlay(page)
                        showThread(number, person)
                    }
                }, wide())
            }
        }
        to.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = repaint()
        })
        repaint()
        pushOverlay(page)
        to.requestFocus()
        to.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(to, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** A face and a line of type, for picking somebody out of a short list. */
    private fun pickRow(
        person: PeopleStore.Contact?,
        label: String,
        onTap: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }
        row.addView(
            ContactFace(context, palette).apply {
                setLetterSize(15f)
                show(person)
            },
            LinearLayout.LayoutParams(dp(THREAD_FACE_DP), dp(THREAD_FACE_DP))
        )
        row.addView(TextView(context).apply {
            text = label
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
        return row
    }

    private companion object {
        /** How many people a new message offers to pick from before you type. */
        const val PICK_ROWS = 40
    }
}
