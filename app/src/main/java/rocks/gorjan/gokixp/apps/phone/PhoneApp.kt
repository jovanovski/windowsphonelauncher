package rocks.gorjan.gokixp.apps.phone

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.people.ContactFace
import rocks.gorjan.gokixp.apps.people.HubApp
import rocks.gorjan.gokixp.apps.people.dayHeadingOf
import rocks.gorjan.gokixp.apps.people.dayOf
import rocks.gorjan.gokixp.apps.people.momentOf
import rocks.gorjan.gokixp.apps.people.timeOf
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.PhoneHistory
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToPageText

/**
 * Phone, as Windows Phone 8.1 had it: the speed dial, the call log and the keypad.
 *
 * These three used to be pages of the People hub in this shell, which folded the phone and
 * the address book into one panorama on the argument that both were about the same list of
 * people. They are - and the phone still kept them apart, because what you do with them is
 * not the same thing at all. Looking somebody up is a search; ringing them back is a
 * command, and a program you open to give one command should not open on four thousand
 * contacts.
 *
 * So: favourites first, because the people you actually ring are four or five; the history
 * behind it, because the second commonest thing is ringing back whoever just called; and
 * the keypad on the strip, which is where the phone kept it - a number pad is what you
 * reach for when the address book has failed you, which is not often enough to spend a
 * swipe of the panorama on.
 *
 * Everything about a *person* rather than about a call is somewhere else. Their card is
 * People's, and a conversation is Messaging's; this app asks the host to open them, which
 * is the honest arrangement now that they are separate programs. See [onShowContact] and
 * [onShowThread].
 */
class PhoneApp(
    context: Context,
    palette: WP81Palette,
    onRequestPermissions: (Array<String>) -> Unit,
    onNotify: (String, String) -> Unit,
    /**
     * Asks the system to make this the phone.
     *
     * The host does the asking because the answer arrives as an activity result, and this
     * is not an activity. See [isThePhone] for what holding it buys.
     */
    private val onBecomeDialer: () -> Unit,
    /** Opens People on somebody's card. Their card is not this app's page. */
    private val onShowContact: (Long) -> Unit,
    /** Opens People's editor on a number this phone does not have a name for. */
    private val onNewContact: (String) -> Unit,
    /** Opens Messaging on a conversation with one number. */
    private val onShowThread: (String) -> Unit
) : HubApp(context, palette, onRequestPermissions, onNotify) {

    override val appName = "Phone"

    private lateinit var panorama: MetroPanorama

    private lateinit var favouritesColumn: LinearLayout
    private lateinit var historyColumn: LinearLayout

    /**
     * The calls the history holds but has not built rows for yet.
     *
     * Nobody scrolls to the end of a call log before deciding whether to, so the section
     * builds a screenful and the next as the reader reaches the bottom of the last.
     */
    private val pendingCalls = mutableListOf<PhoneHistory.Entry>()

    private var contacts: List<PeopleStore.Contact> = emptyList()
    private var history: List<PhoneHistory.Entry> = emptyList()

    /** Whether the last read of the log threw rather than coming back empty. */
    private var historyFailed = false

    /** The one history row that is open, if any. See [toggleHistoryActions]. */
    private var openHistoryActions: OpenRow? = null

    /**
     * Which day the calls built into the history so far belong to.
     *
     * Held across the chunks the list is built in rather than worked out from them: the
     * rows arrive a screenful at a time and a heading has to be written when the day
     * changes *between* two of them, which the chunk that starts the new day cannot see on
     * its own. [NO_DAY] until the first row, so that day gets a heading like every other.
     */
    private var lastHistoryDay = NO_DAY

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
        // as the sections are pulled past underneath. Lowercase, as everything on this
        // platform is.
        panorama.setTitle("phone")

        favouritesColumn = sectionColumn()
        historyColumn = sectionColumn()

        panorama.addPage("favourites", page(favouritesColumn) {})
        panorama.addPage("history", page(historyColumn) { extendHistory() })

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = buildAppBar()
        appBar = bar
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // The sections stop above the strip rather than running under it, so the last row
        // of a list is reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        addOverlayFurniture()

        // Favourites first, which is the whole argument for having the section: the people
        // you actually ring are four or five, and every other way of reaching them starts
        // with scrolling past everybody you do not.
        panorama.goTo(PAGE_FAVOURITES, animated = false)

        refresh()
        return root
    }

    /**
     * The strip: the keypad, and the rest behind the dots.
     *
     * A keypad is the one thing this app is for that is not a person already on a page, so
     * it is the ring. Everything else is a command about the app rather than about a call
     * - which of the phone's roles it holds, and clearing the log.
     */
    private fun buildAppBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        bar.addCommand(KEYPAD_ICON) { showDialer() }
        bar.menu = {
            buildList {
                add(MetroAppBar.Item("refresh") { refresh() })
                if (!isThePhone()) {
                    add(MetroAppBar.Item("default phone app") { onBecomeDialer() })
                } else if (needsBluetoothName()) {
                    // Only while it is missing, and only to somebody who already holds the
                    // phone role - which is the awkward case the request with the role does
                    // not reach: it is asked as the role is taken, and anybody who took it
                    // before this existed was never asked. Rather than a dialog on the way
                    // past, or one over a live call, it is a command that says what it is.
                    add(MetroAppBar.Item("name bluetooth devices") {
                        onRequestPermissions(
                            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT))
                    })
                }
                if (panorama.currentPage() == PAGE_HISTORY && history.isNotEmpty()) {
                    add(MetroAppBar.Item("delete all") { confirmClearHistory() })
                }
            }
        }
        return bar
    }

    // ---------------------------------------------------------------- reading

    /**
     * Re-reads the book and the log, and fills both sections from them.
     *
     * Called on the way in, after a permission is answered, and after anything is written
     * - somebody starred, a call cleared. It is two short queries; there is no state worth
     * keeping in sync by hand when re-asking the phone is this cheap.
     */
    fun refresh() {
        if (!PeopleStore.canRead(context)) {
            contacts = emptyList()
            bindFavourites()
        } else {
            PeopleStore.all(context) { people ->
                contacts = people
                bindFavourites()
            }
        }
        // Asked for itself. The call log is a permission of its own, and a phone that has
        // said yes to contacts should not have the history go blank over it.
        readHistory()
    }

    private fun readHistory() {
        if (!PhoneHistory.hasAccess(context)) {
            history = emptyList()
            historyFailed = false
            bindHistory()
            return
        }
        PhoneHistory.recent(context) { result ->
            history = result.entries
            historyFailed = result.failed
            bindHistory()
        }
    }

    // ---------------------------------------------------------------- favourites

    /**
     * The starred people, as a wall of faces rather than another list.
     *
     * The same argument the People tile makes, one level in: these are four or five people
     * and the app already has a section that is a list. A grid of pictures is quicker to
     * hit and it is what the phone's own speed dial looked like - and where somebody has no
     * picture the square is the accent with their initials on it, which is exactly what the
     * tile does with the same person.
     *
     * Who is starred is still the address book's fact and is set in People; this page is
     * where being starred is *for*.
     */
    private fun bindFavourites() {
        favouritesColumn.removeAllViews()
        if (!PeopleStore.canRead(context)) {
            favouritesColumn.addView(
                note("no access to your contacts.  tap to allow") {
                    onRequestPermissions(PeopleStore.permissions())
                }, wide())
            return
        }
        val favourites = contacts.filter { it.starred }
        if (favourites.isEmpty()) {
            favouritesColumn.addView(
                note("nobody starred yet.  open somebody in People and tap the star"), wide())
            return
        }
        // In the order they were last arranged into, with anybody starred since put at the
        // end. Alphabetical between people the arrangement says nothing about, so a wall
        // that has never been touched is in an order that has a reason.
        val arrangement = savedArrangement()
        val ordered = favourites.sortedWith(
            compareBy(
                { arrangement.indexOf(keyOf(it)).let { at -> if (at < 0) Int.MAX_VALUE else at } },
                { it.name.lowercase() }
            )
        )
        val grid = FavouritesGrid(
            context,
            FAVOURITE_COLUMNS,
            dp(FAVOURITE_GAP_DP)
        ) { keys -> saveArrangement(keys) }
        for (person in ordered) grid.addTile(favouriteTile(person))
        favouritesColumn.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(FAVOURITE_GAP_DP)
        })
    }

    /**
     * What a favourite is filed under in the saved arrangement.
     *
     * The lookup key, which survives the provider re-merging a contact and changing its
     * id - which is exactly what editing one does. An id is the fallback for the rare row
     * that has no key, and it is at least stable until that happens.
     */
    private fun keyOf(person: PeopleStore.Contact): String =
        person.lookupKey ?: person.id.toString()

    private fun savedArrangement(): List<String> =
        prefs.getString(KEY_FAVOURITE_ORDER, "").orEmpty()
            .split(SEPARATOR).filter { it.isNotBlank() }

    private fun saveArrangement(keys: List<String>) {
        prefs.edit().putString(KEY_FAVOURITE_ORDER, keys.joinToString(SEPARATOR)).apply()
    }

    /**
     * One square: their face, their name across the foot of it, the way a tile is set.
     *
     * Tapping opens their card rather than ringing them. A wall of faces is easy to brush
     * with a thumb, and this app already puts a call behind a word for exactly that reason
     * on the page next door - see [bindHistory]. Ringing is the first thing on the hold
     * menu, where a deliberate press finds it.
     */
    private fun favouriteTile(person: PeopleStore.Contact): FavouritesGrid.Tile {
        val tile = FavouritesGrid.Tile(context)
        tile.key = keyOf(person)
        tile.isClickable = true
        tile.setOnClickListener { onShowContact(person.id) }
        // No long-click listener: the hold on this wall is the grid's, because a hold is
        // where a drag starts. What a hold that goes nowhere does is this, and the grid
        // fires it once it knows the finger stayed put.
        tile.onHeld = { showFavouriteMenu(person, tile) }
        TiltEffect.apply(tile)

        tile.addView(faceView(person, big = true), FrameLayout.LayoutParams(MATCH, MATCH))

        // The name sits on a band rather than straight on the picture: a photograph is
        // whatever colour it happens to be, and white type over the light half of one is
        // not type.
        val name = TextView(context).apply {
            text = person.name
            typeface = font(R.font.segoeui_regular)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(android.graphics.Color.WHITE)
            includeFontPadding = false
            setPadding(dp(8), dp(6), dp(8), dp(7))
            setBackgroundColor(NAME_BAND)
        }
        tile.addView(name, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return tile
    }

    /**
     * What a favourite offers when held.
     *
     * Ringing first, because that is what the wall is for and the tap deliberately does
     * not do it. Taking somebody off the wall is here rather than on their card because it
     * is a fact about this wall as much as about them, and because a wall you cannot edit
     * where you are looking at it is a wall nobody rearranges.
     */
    private fun showFavouriteMenu(person: PeopleStore.Contact, anchor: View) {
        showMenu(
            person.name,
            listOf(
                WP81ContextMenu.Item("call") { chooseNumber(person) { place(it) } },
                WP81ContextMenu.Item("text") { chooseNumber(person) { onShowThread(it) } },
                WP81ContextMenu.Item("view contact") { onShowContact(person.id) },
                WP81ContextMenu.Item("remove from favourites") {
                    PeopleStore.setStarred(context, person.id, false) { refresh() }
                }
            ),
            anchor
        )
    }

    // ---------------------------------------------------------------- history

    /**
     * The call log, folded the way the phone folded it.
     *
     * Tapping a row opens what you can do about it underneath, rather than ringing them
     * back on the spot. A history is a list of people you have already called once, so the
     * odds of a stray tap dialling somebody are high enough that the call is worth putting
     * behind a word - and the other two things you would want, their calls and their card,
     * had nowhere to be until the row could open.
     */
    private fun bindHistory() {
        historyColumn.removeAllViews()
        openHistoryActions = null
        lastHistoryDay = NO_DAY
        if (!PhoneHistory.hasAccess(context)) {
            historyColumn.addView(
                note("no access to your call history.  tap to allow") {
                    onRequestPermissions(PhoneHistory.permissions())
                }, wide())
            return
        }
        // The offer to take the phone over, at the top of the section it is about. Only
        // while somebody else has it: once this is the phone there is nothing to say.
        if (!isThePhone()) {
            historyColumn.addView(
                note("another app is handling calls.  tap to make Phone your dialler") {
                    onBecomeDialer()
                }, wide())
        }
        if (historyFailed) {
            historyColumn.addView(note("the call log could not be read"), wide())
            return
        }
        if (history.isEmpty()) {
            historyColumn.addView(note("no calls yet"), wide())
            return
        }
        pendingCalls.clear()
        pendingCalls.addAll(history)
        extendHistory()
    }

    /** Adds the next screenful of calls, if there are any left. */
    private fun extendHistory() {
        var built = 0
        while (pendingCalls.isNotEmpty() && built < CHUNK) {
            val entry = pendingCalls.removeAt(0)
            // A heading each time the day changes, which - the log being newest first - is
            // the only direction it can change in. The first call of all gets one too: the
            // top of the list is the start of a day as much as any line further down is.
            val day = dayOf(entry.at)
            if (day != lastHistoryDay) {
                val heading = dayHeadingOf(entry.at)
                if (heading.isNotBlank()) {
                    historyColumn.addView(
                        dayHeading(heading, first = lastHistoryDay == NO_DAY), wide())
                }
                lastHistoryDay = day
            }
            historyColumn.addView(historyRow(entry), wide())
            built++
        }
    }

    /**
     * The line that says which day the calls under it happened on.
     *
     * In the accent, which is what the phone put its list headings in and what makes them
     * findable at a scroll - the eye goes down the coloured line and stops at the day it
     * wants. Set smaller and in the lighter face than the names it stands over, because it
     * is still not one of the things in the list; it is the shelf they are on.
     */
    private fun dayHeading(label: String, first: Boolean) = TextView(context).apply {
        text = label
        typeface = font(R.font.segoeui_semilight)
        textSize = 15f
        setTextColor(palette.accent)
        includeFontPadding = false
        // Air above, so a heading belongs to what follows it rather than floating between
        // two days - except at the very top, where there is nothing above to be parted from.
        setPadding(0, dp(if (first) 6 else 22), dp(16), dp(6))
    }

    private fun historyRow(entry: PhoneHistory.Entry): View {
        val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            isClickable = true
            setOnLongClickListener {
                showHistoryMenu(entry, this)
                true
            }
            TiltEffect.apply(this)
        }
        row.addView(directionArrow(entry), LinearLayout.LayoutParams(dp(ARROW_DP), dp(ARROW_DP))
            .apply { marginEnd = dp(10) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = entry.title + if (entry.count > 1) "  (${entry.count})" else ""
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(
                if (entry.direction == PhoneHistory.Direction.MISSED) palette.accent
                else palette.foreground
            )
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            // The clock, and how long it lasted. Which day it was is written once over the
            // day's rows instead of on each of them - see [dayHeading]. The number is not
            // repeated here either: it is either the title of the row already, or it is one
            // tap away on their card.
            this.text = listOfNotNull(
                timeOf(context, entry.at).takeIf { it.isNotBlank() },
                lasted(entry.seconds)
            ).joinToString("  ·  ")
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        // Squared off with the top of the title rather than centred on the whole block,
        // so that the rings beside it line up with the name and not with the gap under it.
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f).apply { gravity = Gravity.TOP })

        // Texting and ringing back, on the name's own line: they are about the call the
        // title names, and set below it they would read as two more of the words in the
        // block underneath rather than as the two commands the row is mostly opened for.
        val rings = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        // Placing the quieter of the two where a hand arrives first leaves the call - the
        // one act that cannot be taken back - at the end of the row.
        rings.addView(reachKey(MESSAGE_ICON) { onShowThread(entry.number) }, ring(first = true))
        rings.addView(reachKey(CALL_ICON) { place(entry.number) }, ring(first = false))
        row.addView(rings, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP })
        holder.addView(row, wide())

        // Built once and hidden, rather than made on each tap: a row that has been opened
        // and shut is the common case, and rebuilding four buttons every time is work
        // done in the middle of an animation.
        val actions = historyActions(entry)
        actions.visibility = View.GONE
        holder.addView(actions, wide())

        val open = OpenRow(rings, actions)
        row.setOnClickListener {
            Haptics.tap(it)
            toggleHistoryActions(open)
        }
        return holder
    }

    /**
     * What a call's row shows once it has been opened.
     *
     * Two views rather than one, and in different parents: the rings belong on the title's
     * line and the words belong under it, and they still have to come and go together.
     */
    private class OpenRow(private val rings: View, private val words: View) {
        val isOpen: Boolean get() = words.visibility == View.VISIBLE

        fun setOpen(open: Boolean) {
            val how = if (open) View.VISIBLE else View.GONE
            rings.visibility = how
            words.visibility = how
        }
    }

    /**
     * The other two things a call in the list is a way to.
     *
     * Ringing back and texting are rings on the title's line - see [historyRow] - because
     * they are the two the row is usually opened for and they are the two a profile draws
     * that way. What is left has no mark of its own and would not be read as one: the
     * whole of what has passed between you, and their card, or for a number the phone does
     * not know the offer to make one. Those stay words, under the title, indented to it.
     */
    private fun historyActions(entry: PhoneHistory.Entry): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ARROW_DP + 10), 0, 0, dp(10))
        }
        row.addView(actionWord("call history") { showNumberHistory(entry) })
        val id = entry.contactId
        if (id != null) row.addView(actionWord("view contact") { onShowContact(id) })
        else row.addView(actionWord("add contact") { onNewContact(entry.number) })
        return row
    }

    private fun actionWord(label: String, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        typeface = font(R.font.segoeui_regular)
        textSize = 14f
        // One line always: these are labels, and a squeezed "view contact" folded in two
        // is a taller row rather than a more readable one.
        maxLines = 1
        setTextColor(palette.accent)
        setPadding(0, dp(6), dp(14), dp(6))
        isClickable = true
        setOnClickListener {
            Haptics.tap(it)
            onTap()
        }
        TiltEffect.apply(this)
    }

    /** One open row at a time: two sets of buttons on screen is two rows asking to be read. */
    private fun toggleHistoryActions(row: OpenRow) {
        val already = row.isOpen
        openHistoryActions?.setOpen(false)
        openHistoryActions = if (already) null else row.also { it.setOpen(true) }
    }

    /**
     * Which way a call went, as an arrow.
     *
     * A missed one is the same arrow in the accent: the direction is the same fact about
     * it, and what is different is that it wants answering.
     */
    private fun directionArrow(entry: PhoneHistory.Entry): ImageView = ImageView(context).apply {
        setImageDrawable(
            rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(
                context,
                if (entry.direction == PhoneHistory.Direction.OUTGOING) OUTGOING_ICON
                else INCOMING_ICON
            )
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(
            if (entry.direction == PhoneHistory.Direction.MISSED) palette.accent
            else palette.foregroundSubtle
        )
    }

    /**
     * Everything that has passed between this phone and one number.
     *
     * The history page folds a run of calls into one line, which is the right summary and
     * exactly the wrong thing when the question is when you spoke and for how long. So this
     * is the same log unfolded and narrowed to one person - every call, in order, with its
     * own length.
     */
    private fun showNumberHistory(entry: PhoneHistory.Entry) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setName(entry.title)
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

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
        column.addView(note("reading the call log…"), wide())

        val bar = MetroAppBar(context, palette)
        bar.addCommand(CALL_ICON) { place(entry.number) }
        page.addView(bar, wide())
        pushOverlay(page)

        PhoneHistory.forNumber(context, entry.number) { calls ->
            column.removeAllViews()
            if (calls.isEmpty()) {
                column.addView(note("no calls with this number"), wide())
                return@forNumber
            }
            for (call in calls) column.addView(callRow(call), wide())
        }
    }

    /** One call on one person's page: which way it went, when, and how long it lasted. */
    private fun callRow(entry: PhoneHistory.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        row.addView(directionArrow(entry), LinearLayout.LayoutParams(dp(ARROW_DP), dp(ARROW_DP))
            .apply { marginEnd = dp(12) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = when (entry.direction) {
                PhoneHistory.Direction.OUTGOING -> "outgoing"
                PhoneHistory.Direction.INCOMING -> "incoming"
                PhoneHistory.Direction.MISSED -> "missed"
            }
            typeface = font(R.font.segoeui_regular)
            textSize = 16f
            setTextColor(
                if (entry.direction == PhoneHistory.Direction.MISSED) palette.accent
                else palette.foreground
            )
            includeFontPadding = false
        }, wide())
        text.addView(TextView(context).apply {
            this.text = listOfNotNull(
                momentOf(context, entry.at).takeIf { it.isNotBlank() },
                lasted(entry.seconds)
            ).joinToString("  ·  ")
            typeface = font(R.font.segoeui_regular)
            textSize = 12f
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    /**
     * How long a call lasted, or nothing at all.
     *
     * A missed call has no length, and "0:00" beside it is a measurement of something that
     * never happened. Minutes and seconds up to an hour, then hours - the same shape the
     * timer on the call screen uses, so a call reads the same in the log as it did while
     * it was happening.
     */
    private fun lasted(seconds: Long): String? {
        if (seconds <= 0L) return null
        val minutes = seconds / 60
        return if (minutes >= 60) {
            String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
        } else {
            String.format("%d:%02d", minutes, seconds % 60)
        }
    }

    private fun confirmClearHistory() {
        ask(
            "delete all",
            "This clears every call from the phone's history, not just the ones shown here.",
            "delete"
        ) {
            PhoneHistory.clear(context) { readHistory() }
        }
    }

    private fun showHistoryMenu(entry: PhoneHistory.Entry, anchor: View) {
        showMenu(
            entry.title,
            buildList {
                add(WP81ContextMenu.Item("text") { onShowThread(entry.number) })
                add(WP81ContextMenu.Item("call") { place(entry.number) })
                val id = entry.contactId
                if (id != null) add(WP81ContextMenu.Item("view contact") { onShowContact(id) })
                else add(WP81ContextMenu.Item("save to contacts") { onNewContact(entry.number) })
                // Last, under the things the row is usually held for. It is the one command
                // here that is about the number as a piece of text rather than about the
                // person - pasting it into a message, into a form, reading it out to
                // somebody - and a withheld caller never reaches this list, so there is
                // always a number there to take.
                add(WP81ContextMenu.Item("copy number") { copyNumber(entry.number) })
            },
            anchor
        )
    }

    // ---------------------------------------------------------------- keypad

    /** Puts the panorama on the call history, for something that arrived asking for it. */
    fun showHistory() {
        if (!::panorama.isInitialized) return
        panorama.goTo(PAGE_HISTORY, animated = false)
    }

    /**
     * The dialpad, for a number that is nobody yet.
     *
     * The phone kept this behind a button on the Phone app's strip rather than giving it a
     * section, and it is right: a keypad is what you reach for when the address book has
     * failed you, which is not often enough to spend a swipe of the panorama on.
     *
     * The keys are the calculator's - a field of flat rectangles sized off the width, with
     * the letters under the digits the way every phone has drawn them since the rotary
     * dial was replaced.
     */
    fun showDialer(prefill: String? = null) {
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            // The pad is the keypad; the page is the dialer. What is on screen is a number
            // being put together and the people it might be, and only one part of that is
            // keys - naming the whole page after them named it after its furniture.
            setTitle("dialer")
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        // Who that could be, as it is typed. The one thing a modern keypad can do that the
        // phone's could not, and the answer to the commonest reason for opening one: a
        // number half-remembered that turns out to be somebody already in the book - or a
        // name being spelled out on the keys, which is the other half of what a keypad has
        // always been for. See PeopleStore.keypadMatches.
        //
        // In a scroller of its own, taking whatever height is left between the number and
        // the keys: five people is more than fits on a short phone under a keypad, and a
        // list that pushed the keys off the bottom of the screen would have answered the
        // question by taking away the thing that asked it.
        val matches = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val matchScroller = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(matches, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        page.addView(matchScroller, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // One grid, held at the foot of the page: the number, the digits under it, and
        // under those the three keys that are about the number rather than about a digit.
        // They used to be three separate things with the page's spare height shared out
        // between them, which drew lines across the keypad where there are none - the
        // number is what the pad is filling in, and the call key is on the pad rather
        // than beside it.
        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Only the last row's own margin below it, because the rows carry the rest.
            setPadding(dp(KEYPAD_MARGIN_DP), dp(6), dp(KEYPAD_MARGIN_DP), dp(10))
        }

        // The number, in a key of its own at the head of the pad. It used to sit on the
        // black under the title, where it read as a caption about the page rather than as
        // the thing the page fills in; in the keys' own grey, directly over them, it is
        // the top of the pad.
        //
        // A field rather than a label, because a number is got wrong in the middle far
        // more often than at the end - a digit dropped from a dialling code was otherwise
        // six presses of delete and the whole thing typed again. A tap puts the caret
        // where the finger is and the keys write there. The soft keyboard never comes up:
        // this pad *is* the keyboard, and a second one would cover what it types into.
        val typed = EditText(context).apply {
            typeface = font(R.font.segoeui_light)
            textSize = 32f
            gravity = Gravity.CENTER
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            showSoftInputOnFocus = false
            isCursorVisible = true
            // Set before the padding: a view takes its padding from whatever background
            // it is handed, and this one has insets to report. See [keyFace].
            background = keyFace(keyFill())
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setText(prefill.orEmpty())
            setSelection(text.length)
        }
        // Not applyToField, which is the white box a form has. This one is a key, drawn in
        // the page's own colours, so only the caret and the band behind a selection need
        // saying.
        palette.applyToPageText(typed)
        keys.addView(typed, wideCell())

        // The rows on screen, by who is on them.
        //
        // Kept so that a keystroke can change the list without rebuilding it. Emptying the
        // column and filling it again is the obvious way and the wrong one: most of an
        // answer is usually the same people as the answer before it, and tearing their
        // rows down only to build the same rows again throws away every face that had
        // finished decoding - which is a list that flashes on every key pressed.
        val rows = LinkedHashMap<String, View>()

        fun showMatches(found: List<PeopleStore.Reachable>) {
            val wanted = found.map { "${it.contact.id}:${it.number}" }
            // The people who are no longer an answer. Taken out first, so what is left is
            // already in the right order more often than not.
            for (gone in rows.keys - wanted.toSet()) {
                matches.removeView(rows.remove(gone))
            }
            for ((index, person) in found.withIndex()) {
                val id = wanted[index]
                val standing = rows[id]
                if (standing == null) {
                    val row = matchRow(person)
                    rows[id] = row
                    matches.addView(row, index, wide())
                } else if (matches.indexOfChild(standing) != index) {
                    // Moved rather than remade: a row that has slid up the list is the
                    // same row, and its face has already been decoded once.
                    matches.removeView(standing)
                    matches.addView(standing, index)
                }
            }
        }

        // Which search the list above the number is waiting for. The book is read on its
        // own thread, and a slow answer to "07" must not land on top of the right answer
        // to "0712345" that was asked for after it.
        var pending = 0
        fun repaint() {
            val typedNow = typed.text.toString()
            pending++
            val token = pending
            // What is standing is left standing until the book answers. It belongs to the
            // digits of a keystroke ago, which is a moment out of date rather than wrong -
            // and a list that empties itself between every pair of answers is a list that
            // blinks at the user for the whole time they are typing.
            if (typedNow.count { it.isDigit() } < MATCH_FROM) {
                showMatches(emptyList())
                return
            }
            PeopleStore.keypadMatches(context, typedNow) { found ->
                if (token != pending) return@keypadMatches
                showMatches(found)
            }
        }
        typed.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = repaint()
        })

        for (row in KEYS) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in row) line.addView(
                keyView(
                    key,
                    onTap = { write(typed, key.digit) },
                    onHold = { second -> write(typed, second) }
                ),
                keyCell(dp(KEY_DP))
            )
            keys.addView(line, wide())
        }

        // Call in the middle of the last row: it is the one thing this page is for, and it
        // sits between the two keys that are about the number rather than the call -
        // saving it on one side, taking it back a digit on the other. Under the 0, where
        // the thumb already is.
        val foot = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        foot.addView(footKey(ADD_ICON, keyFill()) {
            val number = typed.text.toString()
            if (number.isNotEmpty()) {
                dismissOverlay(page)
                onNewContact(number)
            }
        }, keyCell(dp(FOOT_DP)))
        // Green rather than the accent, and the same green the call screen answers in -
        // this is the other end of the same act. See CALL_GREEN, which says why the two
        // colours in this shell that are not the user's choice are not the user's choice.
        foot.addView(footKey(CALL_ICON, CALL_GREEN, android.graphics.Color.WHITE) {
            val number = typed.text.toString()
            if (number.isNotEmpty()) place(number)
        }, keyCell(dp(FOOT_DP)))
        val back = footKey(BACKSPACE_ICON, keyFill()) { erase(typed) }
        back.setOnLongClickListener {
            typed.setText("")
            true
        }
        foot.addView(back, keyCell(dp(FOOT_DP)))
        keys.addView(foot, wide())

        page.addView(keys, wide())

        repaint()
        pushOverlay(page)
    }

    /**
     * Writes one character where the caret is, which is where the last tap put it.
     *
     * Through the field's own text rather than by keeping the number here and setting it
     * back afterwards: the caret belongs to the field, and a number rewritten from outside
     * puts it back at the end on every key pressed - which is the whole of what tapping
     * into the middle was for.
     */
    private fun write(field: EditText, digit: Char) {
        val from = field.selectionStart.coerceAtLeast(0)
        val to = field.selectionEnd.coerceAtLeast(0)
        field.text.replace(minOf(from, to), maxOf(from, to), digit.toString())
    }

    /** Takes out what is selected, or the character before the caret when nothing is. */
    private fun erase(field: EditText) {
        val from = field.selectionStart.coerceAtLeast(0)
        val to = field.selectionEnd.coerceAtLeast(0)
        when {
            from != to -> field.text.delete(minOf(from, to), maxOf(from, to))
            from > 0 -> field.text.delete(from - 1, from)
        }
    }

    /**
     * One key of the pad, a third of a row wide.
     *
     * [height] is the key as it is drawn; the cell is that plus a whole gap, because the
     * gap lives inside the cell rather than between the cells - see [keyFace]. The columns
     * still line up with the columns above them and the space between any two rows is the
     * same space; the difference is that all of it can be pressed.
     */
    private fun keyCell(height: Int) =
        LinearLayout.LayoutParams(0, height + dp(KEY_GAP_DP), 1f)

    /** A key that is the whole row: the number at the head of the pad. */
    private fun wideCell() = LinearLayout.LayoutParams(MATCH, WRAP)

    /**
     * One person the keys pressed could mean, and the thing you would do about them.
     *
     * The card is a tap on the name away, which opens People - somebody found on the
     * keypad is a person, and a person's page is not this app's. The ring is the call,
     * which is what the pad was opened to make.
     */
    private fun matchRow(found: PeopleStore.Reachable): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(KEYPAD_MARGIN_DP), dp(MATCH_ROW_PAD_DP), dp(KEYPAD_MARGIN_DP),
                dp(MATCH_ROW_PAD_DP))
        }

        // The same square the contact list puts at the head of a row, at the same size:
        // somebody found on the keypad is the same person the list shows, and a face that
        // was a different size here would say they were a different kind of thing.
        row.addView(
            ContactFace(context, palette).apply {
                setLetterSize(MATCH_FACE_SP)
                show(found.contact)
            },
            LinearLayout.LayoutParams(dp(MATCH_FACE_DP), dp(MATCH_FACE_DP))
        )

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onShowContact(found.contact.id)
            }
            TiltEffect.apply(this)
        }
        words.addView(TextView(context).apply {
            text = found.contact.name
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, wide())
        // The number they were found by, and not only for show: somebody with a work and a
        // home number matched on one of them, and the ring acts on that one.
        words.addView(TextView(context).apply {
            text = found.number
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(16)
        })

        // The ring, and only the one. A keypad is somebody about to make a call - that is
        // what they opened it to do - and a second ring beside it for writing to them
        // instead would be answering a question nobody asked here.
        row.addView(reachKey(CALL_ICON) { place(found.number) }, ring(first = true))
        return row
    }

    /**
     * One key: what it types, what is written under it, and what holding it types instead.
     *
     * [held] is the plus on the zero and nothing anywhere else. It is the one thing on a
     * telephone keypad that has never had a key of its own - there are twelve keys and
     * thirteen things to type - and every phone since has put it under a long press on the
     * nought, which is why the plus is printed there in the first place.
     */
    private data class Key(val digit: Char, val letters: String, val held: Char? = null)

    private fun keyView(key: Key, onTap: () -> Unit, onHold: (Char) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Before the padding, which a view otherwise takes from whatever background it
            // is handed - and this one has insets to report.
            background = keyFace(keyFill())
            setPadding(0, dp(KEY_PAD_DP), 0, dp(KEY_PAD_DP))
            isClickable = true
            setOnClickListener {
                Haptics.key(it)
                // A telephone key is felt and heard. The tick alone is what a calculator
                // does, and this is not one.
                DialTones.press(key.digit)
                onTap()
            }
            key.held?.let { second ->
                setOnLongClickListener {
                    // No tone, and no buzz of its own: there is no tone for a plus - it is
                    // not something the line can hear, it is how a country code is written
                    // down - and the framework gives the shell's tick as it claims a hold.
                    onHold(second)
                    true
                }
            }
            TiltEffect.apply(this)

            addView(TextView(context).apply {
                text = key.digit.toString()
                typeface = font(R.font.segoeui_semilight)
                textSize = KEY_DIGIT_SP
                includeFontPadding = false
                setTextColor(palette.foreground)
                gravity = Gravity.CENTER
                // Centred by the ink rather than by the line it sits on.
                //
                // A line box is the same height whatever is written in it, and centring
                // that centres the space rather than the mark. It makes no difference to
                // the digits, which all fill their line the same way - and all the
                // difference to the star, which is drawn up at the cap height with
                // nothing below the middle of the em, so a centred line box left it
                // sitting visibly above the middle of its own key.
                val ink = android.graphics.Rect()
                paint.getTextBounds(key.digit.toString(), 0, 1, ink)
                val metrics = paint.fontMetrics
                translationY =
                    (metrics.ascent + metrics.descent) / 2f - (ink.top + ink.bottom) / 2f
            }, wide())
            // The letters are what tells a keypad from a calculator, and they are set
            // small and quiet because nobody reads them - they are recognised.
            //
            // Gone rather than invisible on the keys that have none: the key is a fixed
            // height now - see KEY_DP - so a line held open for nothing no longer keeps
            // the rows level, it only pushes the star and the hash up off the middle of
            // their own keys.
            addView(TextView(context).apply {
                text = key.letters
                typeface = font(R.font.segoeui_regular)
                textSize = KEY_LETTERS_SP
                letterSpacing = 0.12f
                includeFontPadding = false
                setTextColor(palette.foregroundSubtle)
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
                visibility = if (key.letters.isBlank()) View.GONE else View.VISIBLE
            }, wide())
        }

    /**
     * A key's own paint, held half a gap in from the edge of the cell it is drawn in.
     *
     * What makes a keypad with no dead ground on it. The gaps used to be margins - space
     * between the keys that belonged to neither of them, so a thumb landing a millimetre
     * wide of a key hit nothing at all and the number stayed as it was. The gap is now
     * inside the key that owns it: the same picture, and every pixel of the pad answers.
     */
    private fun keyFace(colour: Int): Drawable =
        InsetDrawable(ColorDrawable(colour), dp(KEY_GAP_DP) / 2)

    /**
     * One of the three across the foot of the keypad.
     *
     * [ink] is said rather than worked out from [fill], because the one key that is not
     * drawn in the page's own colours is not drawn in the palette's either: green is a
     * colour this shell chose, and what reads on it is a decision that belongs with the
     * green rather than with a rule about fills.
     */
    private fun footKey(
        icon: String, fill: Int, ink: Int = palette.foreground, onTap: () -> Unit
    ): View =
        ImageView(context).apply {
            setImageDrawable(rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(context, icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            // The background first: it carries insets, and a view takes its padding from
            // whatever background it is handed. See [keyFace].
            background = keyFace(fill)
            // Measured against the key rather than the cell: the cell grew by a gap when
            // the gap moved inside it, and the same numbers would have grown the mark.
            setPadding(dp(16), dp(16), dp(16), dp(16))
            imageTintList = android.content.res.ColorStateList.valueOf(ink)
            isClickable = true
            setOnClickListener {
                Haptics.key(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

    /** The key grey: the foreground a little way over the background, as the calculator's. */
    private fun keyFill(): Int = androidx.core.graphics.ColorUtils.blendARGB(
        palette.background, palette.foreground, KEY_FILL_ALPHA)

    // ---------------------------------------------------------------- helpers

    /**
     * Whether the in-call list of outputs would have to say "bluetooth" rather than a name.
     *
     * Before Android 12 there is nothing to ask for - the old permission is granted at
     * install - so there is nothing to offer either. See CallCentre.bluetoothName.
     */
    private fun needsBluetoothName(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PAGE_FAVOURITES = 0
        const val PAGE_HISTORY = 1

        /** No day yet, for [lastHistoryDay]. Not zero: that is what an undated call gives. */
        const val NO_DAY = Long.MIN_VALUE

        /** Spelled here as every class in this shell spells it, beside the marks it names. */
        const val ICON_DIR = "custom_icons_8"
        const val KEYPAD_ICON = "$ICON_DIR/appbar.dial.svg"

        /**
         * The delete key's mark: a cross backed into a shoulder, pointing left.
         *
         * A plain left arrow is the same mark the back key wears, and on a keypad whose
         * other two foot keys add and call, an arrow reads as "go back" rather than as
         * "take one off". This is the shape every telephone keypad has drawn there.
         */
        const val BACKSPACE_ICON = "$ICON_DIR/appbar.clear.inverse.reflect.horizontal.svg"

        /**
         * Which way a call went.
         *
         * Left for one this phone made and right for one it received - the log read as an
         * account of what arrived and what left, rather than of who was pointing where.
         */
        const val OUTGOING_ICON = "$ICON_DIR/appbar.arrow.left.svg"
        const val INCOMING_ICON = "$ICON_DIR/appbar.arrow.right.svg"

        /** The band a favourite's name sits on, over whatever their picture happens to be. */
        const val NAME_BAND = 0x99000000.toInt()

        const val ARROW_DP = 18

        const val FAVOURITE_COLUMNS = 3
        const val FAVOURITE_GAP_DP = 10

        /** How the favourites are arranged. Written by this app, read by nothing else. */
        const val KEY_FAVOURITE_ORDER = "wp81_people_favourite_order"

        const val KEYPAD_MARGIN_DP = 16
        const val KEY_GAP_DP = 8
        const val FOOT_DP = 62
        const val KEY_FILL_ALPHA = 0.122f

        /**
         * A digit key, at three quarters of the size it was drawn at first.
         *
         * The pad was a hand's worth of screen and the list of people it finds had what
         * was left, which on a full name and number is not enough. Everything about the
         * key comes down together - the air above and below the digit and both sizes of
         * type - because a key with the same type on it in a shorter box is not a smaller
         * key, it is a cramped one.
         */
        const val KEY_PAD_DP = 9
        const val KEY_DIGIT_SP = 22.5f
        const val KEY_LETTERS_SP = 10.5f

        /**
         * How tall a digit key is drawn.
         *
         * Said rather than left to the words on it, because the star and the hash have no
         * words: a key that took its height from its contents was a key that stood shorter
         * than its neighbours the moment the line of letters under the digit went away, so
         * the line was kept open and empty and their glyphs sat above the middle of their
         * own keys ever after. With the height fixed, the letters can simply go, and every
         * key centres what is actually on it.
         *
         * The number is what the four rows come to at these sizes, with the air above and
         * below the digit that [KEY_PAD_DP] asks for.
         */
        const val KEY_DP = 60

        /**
         * A face beside somebody the keypad found, and the row it sits in.
         *
         * ContactList's own numbers - see ContactList.FACE_DP and its ROW_DP, which is
         * this padding either side of this square. Two lists in one shell whose rows are
         * a different height with their faces at different sizes are two lists that
         * merely resemble each other.
         */
        const val MATCH_FACE_DP = 42
        const val MATCH_FACE_SP = 15f
        const val MATCH_ROW_PAD_DP = 10

        val KEYS = listOf(
            listOf(Key('1', ""), Key('2', "ABC"), Key('3', "DEF")),
            listOf(Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO")),
            listOf(Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ")),
            listOf(Key('*', ""), Key('0', "+", held = '+'), Key('#', ""))
        )
    }
}
