package rocks.gorjan.gokixp.apps.people

import android.accounts.Account
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.ContactFeed
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.MetroPageHeader
import rocks.gorjan.gokixp.wp81.MetroPanorama
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.applyToField

/**
 * People: the address book, and nothing else.
 *
 * This used to be the whole of the phone half of this shell - a panorama with favourites,
 * the call log, the conversations and the book on four pages of it, on the argument that
 * all four were about the same list of people. They are, and Windows Phone still shipped
 * them as three programs, because what you *do* with a person is not one thing. Ringing
 * somebody is a command, reading what they said is a place you sit, and looking them up is
 * a search - and one program that did all three opened on whichever of them you did not
 * want. So the calls went to [rocks.gorjan.gokixp.apps.phone.PhoneApp] and the
 * conversations to [rocks.gorjan.gokixp.apps.messaging.MessagingApp], and what is left
 * here is what People always was: everybody, their cards, and the editor that writes one.
 *
 * Everything the app knows comes from the phone's own address book through [PeopleStore],
 * and everything it changes is written back there: a contact created here is a contact, in
 * an account, that every other app on the phone can see. That is the point of it. A
 * launcher that kept its own private list of people would have made an address book that
 * goes away when it does.
 *
 * The People tile opens this: the tile is a wall of the faces in *this* book, and tapping
 * it should land inside the app that wall belongs to.
 */
class PeopleApp(
    context: Context,
    palette: WP81Palette,
    onRequestPermissions: (Array<String>) -> Unit,
    onNotify: (String, String) -> Unit,
    /** The gallery, for a contact's picture. Its result comes back to [onPhotoPicked]. */
    private val photoPicker: ActivityResultLauncher<String>,
    /** Opens Messaging on a conversation with one number. A conversation is not a card. */
    private val onShowThread: (String) -> Unit
) : HubApp(context, palette, onRequestPermissions, onNotify) {

    override val appName = "People"

    private lateinit var panorama: MetroPanorama

    /**
     * Everybody, on the app list's own page.
     *
     * A [ContactList] rather than a column of rows: this is the one page in the shell that
     * can run to four thousand people, and it is the one the phone gave letter squares, a
     * jump grid and a search band. All of which the app list already has - see
     * [rocks.gorjan.gokixp.wp81.MetroIndexList], which the two of them share.
     *
     * The page itself is [ContactsPage] rather than anything of this app's own, because
     * Phone stands on the same one: the address book is one book, and the page that shows
     * it should not be two pages that have to be kept alike by hand.
     */
    private lateinit var contactsPage: ContactsPage

    /** The editor waiting for a picture, while the gallery is open over the app. */
    private var awaitingPhoto: Editor? = null

    /**
     * Another program's errand in here, while there is one. See [beginVisit].
     *
     * [base] is how many pages were already open when it began: backing out to that depth
     * is the end of the errand, whatever the book had open before Phone asked for a card.
     */
    private class Visit(val base: Int, val onEnded: () -> Unit)

    private var visit: Visit? = null

    /**
     * Pages asked for and not yet on screen - a card being read, which takes a trip to the
     * provider. While one is on its way the app is between two pages rather than back at
     * the list, and an errand is not over just because nothing is showing for a moment.
     */
    private var pagesOnTheWay = 0

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
        // as the page is pulled underneath. Lowercase, as everything on this platform is.
        panorama.setTitle("people")

        contactsPage = ContactsPage(context, palette, onRequestPermissions).apply {
            // Somebody found in a directory opens onto the directory's card for them, the
            // same as anybody in the book opens onto theirs - with saving them to the phone
            // on its strip. See PeopleStore.directoryDetail.
            onPick = { person -> showProfile(person) }
            onHeld = { person, anchorY -> showContactMenu(person, anchorY) }
        }
        // Nameless: there is one section, and a strip reading "all" under a wordmark
        // reading "people" is a heading for a page nobody can leave. See
        // MetroPanorama.rebuildStrip, which draws no strip when nothing is named.
        panorama.addPage("", contactsPage)

        column.addView(panorama, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = buildAppBar()
        appBar = bar
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        // The list stops above the strip rather than running under it, so the last row is
        // reachable instead of sitting behind the buttons.
        column.setPadding(0, 0, 0, dp(MetroAppBar.HEIGHT_DP))

        // The jump grid measures itself against the whole display, because that is what it
        // covers - so it is put up over the app rather than inside the page, which is only
        // as tall as the panorama leaves it.
        contactsPage.setJumpListHost(root)

        addOverlayFurniture()

        refresh()
        return root
    }

    /**
     * Looking somebody up.
     *
     * Reached from the shell's search key and from nowhere else in this app - see
     * [searchAction], and [buildAppBar] for what used to be on the strip.
     */
    private fun searchContacts() = contactsPage.beginSearch()

    /**
     * The shell's search key, while the book itself is what is on screen.
     *
     * Withdrawn under a page opened over it - a profile, the editor. Those are somewhere
     * the user went into, neither has a search, and a key that searched the contacts from
     * inside the editor would be an exit dressed as a filter. Cortana has it back until
     * they come out. See [rocks.gorjan.gokixp.wp81.WP81Searchable].
     */
    override fun searchAction(): (() -> Unit)? =
        if (overlays.isNotEmpty() || !::contactsPage.isInitialized) null
        else ({ searchContacts() })

    /**
     * The strip: a new contact, and the rest behind the dots.
     *
     * A person who is not in the book yet is the one thing this app is for that is not
     * already a row on the page - everything you can do *to* somebody is on their own card
     * or behind a long press, which is where the phone put them.
     *
     * Search was the first ring here and is not any more: the shell's search key is lit for
     * this app and opens the same field - see [searchAction]. A magnifier on the strip an
     * inch above a magnifier on the key is the same command drawn twice, and the one that
     * goes is the one the user has to look at the screen to find.
     */
    private fun buildAppBar(): MetroAppBar {
        val bar = MetroAppBar(context, palette)
        bar.addCommand(ADD_ICON) { showEditor(null, prefillNumber = null) }
        bar.menu = {
            listOf(MetroAppBar.Item("refresh") { refresh() })
        }
        return bar
    }

    // ---------------------------------------------------------------- reading

    /**
     * Re-reads the book and fills the list from it.
     *
     * Called on the way in, after a permission is answered, and after anything is written
     * - a contact saved, starred or deleted. It is one short query; there is no state worth
     * keeping in sync by hand when re-asking the phone is this cheap.
     */
    fun refresh() {
        if (!PeopleStore.canRead(context)) {
            contactsPage.setContacts(emptyList())
            return
        }
        PeopleStore.all(context) { people -> contactsPage.setContacts(people) }
    }

    // ---------------------------------------------------------------- visits

    /**
     * Marks what is about to be opened as another program's errand.
     *
     * Phone and Messaging send somebody here to see a card or to save a number, and backing
     * out of that page should put the user back where they came from - the call they were
     * looking at, the conversation they were in. What it did instead was land on the whole
     * address book, which is a place nobody asked to go. So the host says an errand has
     * begun, and [onEnded] is how it hears that the errand is over.
     *
     * Called before the page is opened, so its depth is counted from where the book stood.
     */
    fun beginVisit(onEnded: () -> Unit) {
        visit = Visit(overlays.size, onEnded)
    }

    /** People opened on its own account: whatever errand there was, it is not this one. */
    fun forgetVisit() {
        visit = null
    }

    override fun dismissOverlay(view: View) {
        super.dismissOverlay(view)
        settleVisit()
    }

    /**
     * Ends the errand if it has been backed out of.
     *
     * A turn of the main loop later, not now. Saving a contact closes the editor and then
     * opens the card it made, and editing one closes the card and opens the editor - both
     * in the same breath - and judging in between would send the user back to Phone with a
     * page still on its way in behind them.
     */
    private fun settleVisit() {
        val current = visit ?: return
        root.post {
            if (visit !== current) return@post
            if (overlays.size > current.base || pagesOnTheWay > 0) return@post
            visit = null
            current.onEnded()
        }
    }

    // ---------------------------------------------------------------- profile

    /**
     * One person's page: their face, their name, and every way of reaching them.
     *
     * The action rows are written as the phone wrote them - the value on one line and what
     * kind of thing it is under it, with the verbs as rings beside it - because "call
     * mobile" and "text mobile" over the very same number twice is the number said twice
     * to offer two things.
     *
     * Also the way in from outside: the other two programs send somebody here when the
     * question stops being about a call or a conversation and starts being about a person.
     */
    fun showProfile(person: PeopleStore.Contact) {
        if (!person.directory) {
            showProfile(person.id)
            return
        }
        // Never null: a directory that will not open the card still said who this was.
        pagesOnTheWay++
        PeopleStore.directoryDetail(context, person) {
            pagesOnTheWay--
            buildProfile(it)
        }
    }

    fun showProfile(contactId: Long) {
        pagesOnTheWay++
        PeopleStore.detail(context, contactId) { detail ->
            pagesOnTheWay--
            if (detail == null) {
                notify("That contact is no longer on this phone")
                refresh()
                // Nothing is coming, so an errand waiting on this card is over.
                settleVisit()
                return@detail
            }
            buildProfile(detail)
        }
    }

    private fun buildProfile(detail: PeopleStore.Detail) {
        val person = detail.contact
        val page = overlayPage()
        val header = MetroPageHeader(context, palette).apply {
            setName(person.name)
            onBack = { dismissOverlay(page) }
        }
        page.addView(header, wide())

        val scroll = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
        }
        scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))

        // The picture, large, at the head of the page. This is the one place the app gives
        // a face a line of its own rather than a row's worth of it.
        column.addView(
            faceView(person, big = true),
            LinearLayout.LayoutParams(dp(PROFILE_FACE_DP), dp(PROFILE_FACE_DP)).apply {
                topMargin = dp(4)
                bottomMargin = dp(16)
            }
        )

        if (detail.phones.isEmpty() && detail.emails.isEmpty()) {
            column.addView(note("no numbers or addresses for this contact"), wide())
        }
        for (phone in detail.phones) {
            column.addView(
                reachRow(phone, listOf(
                    reachKey(MESSAGE_ICON) { onShowThread(phone.value) },
                    reachKey(CALL_ICON) { place(phone.value) }
                )),
                wide()
            )
        }
        for (address in detail.emails) {
            column.addView(
                reachRow(address, listOf(
                    reachKey(EMAIL_ICON) { email(address.value) }
                )),
                wide()
            )
        }

        // Which account this person is filed under, in the small type a footnote gets. It
        // matters exactly once - when an edit does not stick because the row belongs to an
        // account that overwrites it - and this is where somebody would look. For somebody
        // out of a directory it is the account whose directory it was, which is the one
        // thing that says why a person who is not on the phone has a card on it.
        detail.account?.let {
            column.addView(TextView(context).apply {
                text = it.name
                typeface = font(R.font.segoeui_regular)
                textSize = 12f
                setTextColor(palette.foregroundSubtle)
                setPadding(0, dp(22), 0, 0)
            }, wide())
        }

        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // The page's own strip. Every page in this shell that can be acted on has one, and
        // a profile is the page where most of the acting happens.
        val bar = MetroAppBar(context, palette)
        if (person.directory) {
            // Nothing to star, edit or delete: none of those has a row on this phone to be
            // done to, and the directory is not this phone's to change. What there is, is
            // the offer to write them down here - with everything the card knows about
            // them already filled in, rather than the one number they rang from.
            bar.addCommand(ADD_ICON) {
                dismissOverlay(page)
                showEditor(null, prefillNumber = null, template = detail)
            }
            page.addView(bar, wide())
            pushOverlay(page)
            return
        }
        // Held here rather than read back off the contact, because the contact is a
        // snapshot taken when the page was built and starring somebody does not rebuild it.
        var starred = person.starred
        lateinit var star: ImageView
        star = bar.addCommand(STAR_ICON) {
            starred = !starred
            // Painted before the write rather than after it. Nothing else on this page
            // changes when somebody is starred, so there is nothing to re-read and no
            // reason to close the page and open it again - which is what this used to do,
            // and it read as the profile flinching every time the star was tapped.
            bar.setCommandOn(star, starred)
            PeopleStore.setStarred(context, person.id, starred) {
                // The list behind is what changes: its own star goes on or off. So does
                // the Phone app's wall of favourites, which is the other thing being
                // starred is for - it re-reads the book when it is opened.
                refresh()
            }
        }
        // Filled where they are a favourite, outlined where they are not - the same way
        // the strip says any mode is in force.
        bar.setCommandOn(star, starred)
        bar.addCommand(EDIT_ICON) {
            dismissOverlay(page)
            showEditor(detail, prefillNumber = null)
        }
        bar.menu = {
            listOf(MetroAppBar.Item("delete contact") { confirmDelete(person, page) })
        }
        page.addView(bar, wide())

        pushOverlay(page)
    }

    /**
     * A way of reaching somebody, and the keys for the things done with it.
     *
     * The verbs used to be the rows: "call mobile" over the number, and then "text mobile"
     * over the very same number again - which is the number said twice to offer two
     * things, and a person with three of them a page of six rows that reads as a list of
     * near-duplicates. So the number is the row now and the verbs are keys beside it,
     * which is also the only arrangement in which the two are plainly about the same
     * number rather than about two that happen to match.
     *
     * An address takes the same shape for one key rather than two, so that a page of
     * numbers and addresses is one column of rows and one column of keys, instead of two
     * kinds of row that happen to be about the same person.
     *
     * The words are left inert on purpose. Everything that can be done here is under a
     * key, and a row that quietly did one of them when tapped would be a further thing to
     * know about with nothing on screen to say which it was.
     */
    private fun reachRow(entry: PeopleStore.Entry, keys: List<View>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
        }

        // The number or address large and its kind underneath, which is the other way up
        // from the rows this replaced - the verb was the command and the value a fact
        // about it, and with the verbs gone the value is what the row is for.
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(context).apply {
            text = entry.value
            typeface = font(R.font.segoeui_regular)
            textSize = 19f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foreground)
            includeFontPadding = false
        }, wide())
        words.addView(TextView(context).apply {
            text = entry.label
            typeface = font(R.font.segoeui_regular)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(palette.foregroundSubtle)
            setPadding(0, dp(3), 0, 0)
        }, wide())
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Rings, which is what a command looks like in this shell - the app bar is a row
        // of them, and these are the same command in the same clothes, put next to the
        // thing it applies to instead of at the foot of the page. Added last and against
        // the end of the row, so a row with one ring keeps it under the last ring of the
        // rows with two.
        for ((index, key) in keys.withIndex()) {
            row.addView(key, ring(first = index == 0))
        }
        return row
    }

    private fun confirmDelete(person: PeopleStore.Contact, page: View) {
        ask(
            "delete contact",
            "${person.name} will be removed from this phone and from any account they sync with.",
            "delete"
        ) {
            PeopleStore.delete(context, person) { gone ->
                if (!gone) notify("That contact could not be deleted")
                dismissOverlay(page)
                refresh()
            }
        }
    }

    /**
     * The commands for somebody in the list.
     *
     * Short, and every one of them a thing you would want without opening them first -
     * anything that needs their page is on their page. Starring is here because it is the
     * one change worth making from a list: it moves somebody onto the wall the Phone app
     * opens on, and having to open a profile to do it is what stops people using
     * favourites at all.
     */
    private fun showContactMenu(person: PeopleStore.Contact, anchor: View) =
        showContactMenu(person, anchorYOf(anchor))

    /** The same, for a list that reports where its row ended rather than handing it over. */
    private fun showContactMenu(person: PeopleStore.Contact, anchorY: Float) {
        if (person.directory) {
            // Somebody in a directory has no favourite to be and nothing on this phone to
            // delete. What they can be is saved.
            showMenu(
                person.name,
                listOf(
                    WP81ContextMenu.Item("call") { chooseNumber(person) { place(it) } },
                    WP81ContextMenu.Item("text") { chooseNumber(person) { onShowThread(it) } },
                    WP81ContextMenu.Item("save to contacts") {
                        PeopleStore.directoryDetail(context, person) {
                            showEditor(null, prefillNumber = null, template = it)
                        }
                    }
                ),
                anchorY
            )
            return
        }
        showMenu(
            person.name,
            listOf(
                WP81ContextMenu.Item("call") { chooseNumber(person) { place(it) } },
                WP81ContextMenu.Item("text") { chooseNumber(person) { onShowThread(it) } },
                WP81ContextMenu.Item(
                    if (person.starred) "remove from favourites" else "add to favourites"
                ) {
                    PeopleStore.setStarred(context, person.id, !person.starred) { refresh() }
                },
                WP81ContextMenu.Item("delete") { confirmDeleteFromList(person) }
            ),
            anchorY
        )
    }

    private fun confirmDeleteFromList(person: PeopleStore.Contact) {
        ask(
            "delete contact",
            "${person.name} will be removed from this phone and from any account they sync with.",
            "delete"
        ) {
            PeopleStore.delete(context, person) { gone ->
                if (!gone) notify("That contact could not be deleted")
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- editor

    /**
     * The page that writes somebody down, whether they exist yet or not.
     *
     * Held as an object rather than built by a function because it has to be reachable
     * from outside: choosing a picture leaves the app entirely, and what comes back is a
     * URI that has to find its way to the editor that asked for it.
     */
    private inner class Editor(
        private val existing: PeopleStore.Detail?,
        prefillNumber: String?,
        /**
         * A card this contact is being copied from - somebody out of a directory, being
         * saved to the phone. Fills the fields the way [existing] does, and is still saved
         * as somebody new.
         */
        template: PeopleStore.Detail? = null
    ) {
        val page: LinearLayout = overlayPage()
        private val face = ImageView(context)
        private val faceInitials = TextView(context)
        private val given = field("first name", InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        private val family = field("last name", InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        private val phoneRows = mutableListOf<Pair<EditText, TextView>>()
        private val emailRows = mutableListOf<Pair<EditText, TextView>>()
        private val phoneColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        private val emailColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        private var photo: Bitmap? = null

        private var account: Account? = null
        private var accounts: List<Account?> = listOf(null)
        private val accountLabel = TextView(context)

        init {
            val header = MetroPageHeader(context, palette).apply {
                setTitle(if (existing == null) "new contact" else "edit")
                onBack = { close() }
            }
            page.addView(header, wide())

            val scroll = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PAGE_MARGIN_DP), 0, dp(PAGE_MARGIN_DP), dp(24))
            }
            scroll.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))

            column.addView(photoSquare(), LinearLayout.LayoutParams(
                dp(EDITOR_FACE_DP), dp(EDITOR_FACE_DP)).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            })

            // Only for somebody who does not exist yet. Moving an existing contact between
            // accounts is a different operation from editing one, and offering it here
            // would be offering to make a copy without saying so.
            if (existing == null) {
                column.addView(label("save to"), wide())
                column.addView(accountRow(), wide())
                // Asked for rather than assumed: working out which accounts sync contacts
                // is a walk of every raw contact on the phone, and the editor should be on
                // screen before that finishes. It opens saying "phone", which is the right
                // answer for a device with no accounts and the honest one until the read
                // comes back.
                PeopleStore.accounts(context) { choices ->
                    accounts = choices
                    account = rememberedAccount(choices)
                    accountLabel.text = PeopleStore.labelOf(account)
                }
            }

            column.addView(label("name"), wide())
            column.addView(given)
            column.addView(family)

            column.addView(label("phone"), wide())
            column.addView(phoneColumn, wide())
            column.addView(adder("add phone") { addPhone("", "mobile") }, wide())

            column.addView(label("email"), wide())
            column.addView(emailColumn, wide())
            column.addView(adder("add email") { addEmail("", "personal") }, wide())

            page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val bar = MetroAppBar(context, palette)
            bar.addCommand(SAVE_ICON) { save() }
            bar.addCommand(CANCEL_ICON) { close() }
            page.addView(bar, wide())

            // Filled last, so every field it writes into is already in the page.
            (existing ?: template)?.let {
                given.setText(it.givenName.ifBlank {
                    // A contact synced without a structured name still has a display name,
                    // and losing it on the way into the editor is how an edit of a phone
                    // number ends up deleting somebody's name.
                    if (it.familyName.isBlank()) it.contact.name else ""
                })
                family.setText(it.familyName)
                for (phone in it.phones) {
                    addPhone(phone.value, PeopleStore.nearestPhoneLabel(phone.label))
                }
                for (address in it.emails) {
                    addEmail(address.value, PeopleStore.nearestEmailLabel(address.label))
                }
                // A copied card's picture is kept as well as shown, so it is saved with them.
                // An existing contact's is only shown: it is already theirs.
                showFace(it.contact, keep = existing == null)
            }
            if (!prefillNumber.isNullOrBlank()) addPhone(prefillNumber, "mobile")
            if (phoneRows.isEmpty()) addPhone("", "mobile")
        }

        /** The picture, and the whole square as the way to change it. */
        private fun photoSquare(): View {
            val holder = FrameLayout(context)
            holder.background = ContactFace.placeholder(context, palette.accent)
            faceInitials.apply {
                text = "photo"
                typeface = font(R.font.segoeui_regular)
                textSize = 15f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
            }
            holder.addView(faceInitials, FrameLayout.LayoutParams(MATCH, MATCH))
            face.scaleType = ImageView.ScaleType.CENTER_CROP
            face.visibility = View.GONE
            holder.addView(face, FrameLayout.LayoutParams(MATCH, MATCH))
            holder.isClickable = true
            holder.setOnClickListener {
                Haptics.tap(it)
                choosePhoto()
            }
            TiltEffect.apply(holder)
            return holder
        }

        private fun showFace(person: PeopleStore.Contact, keep: Boolean) {
            faceInitials.text = person.initials.ifBlank { "photo" }
            val uri = person.photoUri ?: return
            ContactFeed.load(context, uri) { bitmap ->
                if (bitmap == null) return@load
                // Not over one the user has picked in the meantime.
                if (keep && photo == null) photo = bitmap
                showBitmap(bitmap)
            }
        }

        private fun showBitmap(bitmap: Bitmap) {
            face.setImageBitmap(bitmap)
            face.visibility = View.VISIBLE
            faceInitials.visibility = View.GONE
        }

        private fun choosePhoto() {
            awaitingPhoto = this
            try {
                photoPicker.launch("image/*")
            } catch (e: Exception) {
                Log.w(TAG, "No gallery to pick a contact picture from", e)
                awaitingPhoto = null
                notify("There is nothing on this phone to pick a picture with")
            }
        }

        /** The picture the gallery handed back, decoded and shown before it is written. */
        fun onPhotoPicked(uri: Uri) {
            val bitmap = try {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                ) { decoder, _, _ ->
                    // Software, and mutable-free: this is scaled and compressed on the way
                    // into the provider, and a hardware bitmap cannot be read back.
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the chosen picture", e)
                notify("That picture could not be read")
                return
            }
            photo = bitmap
            showBitmap(bitmap)
        }

        private fun accountRow(): View {
            accountLabel.apply {
                text = PeopleStore.labelOf(account)
                typeface = font(R.font.segoeui_regular)
                textSize = 17f
                setTextColor(palette.foreground)
                setPadding(0, dp(8), 0, dp(12))
                isClickable = true
                setOnClickListener { pickAccount(this) }
                TiltEffect.apply(this)
            }
            return accountLabel
        }

        private fun pickAccount(anchor: View) {
            val choices = accounts
            if (choices.size <= 1) return
            showMenu(
                "save to",
                choices.map { choice ->
                    WP81ContextMenu.Item(PeopleStore.labelOf(choice)) {
                        account = choice
                        accountLabel.text = PeopleStore.labelOf(choice)
                        // Written down as it is picked rather than as the contact is
                        // saved: choosing where contacts go is a decision about all of
                        // them, and somebody who changes it and then abandons the contact
                        // has still made that decision.
                        rememberAccount(choice)
                    }
                },
                anchor
            )
        }

        private fun addPhone(value: String, label: String) =
            addEntry(phoneColumn, phoneRows, value, label, PeopleStore.PHONE_LABELS,
                InputType.TYPE_CLASS_PHONE)

        private fun addEmail(value: String, label: String) =
            addEntry(emailColumn, emailRows, value, label, PeopleStore.EMAIL_LABELS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)

        /**
         * One value and the word for what kind it is.
         *
         * The kind is a tap rather than a dropdown: WP8.1 had no combo boxes, and there are
         * four choices. Tapping it walks round them, which is quicker than any list of four
         * would be and needs no second surface.
         */
        private fun addEntry(
            column: LinearLayout,
            rows: MutableList<Pair<EditText, TextView>>,
            value: String,
            label: String,
            labels: List<String>,
            inputType: Int
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val entry = EditText(context).apply {
                setText(value)
                setSingleLine()
                textSize = 17f
                typeface = font(R.font.segoeui_regular)
                this.inputType = inputType
                setPadding(dp(10), dp(9), dp(10), dp(9))
                palette.applyToField(this)
            }
            val kind = TextView(context).apply {
                text = label
                typeface = font(R.font.segoeui_regular)
                textSize = 13f
                setTextColor(palette.accent)
                setPadding(dp(10), dp(9), dp(2), dp(9))
                isClickable = true
                setOnClickListener {
                    val next = labels[(labels.indexOf(text.toString()) + 1).mod(labels.size)]
                    text = next
                }
                TiltEffect.apply(this)
            }
            row.addView(entry, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(kind, LinearLayout.LayoutParams(dp(KIND_DP), WRAP))
            column.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4)
            })
            rows.add(entry to kind)
        }

        private fun adder(text: String, onTap: () -> Unit): View = TextView(context).apply {
            this.text = "+  $text"
            typeface = font(R.font.segoeui_regular)
            textSize = 15f
            setTextColor(palette.accent)
            setPadding(0, dp(10), 0, dp(6))
            isClickable = true
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
            TiltEffect.apply(this)
        }

        private fun label(text: String): View = TextView(context).apply {
            this.text = text
            typeface = font(R.font.segoeui_semibold)
            textSize = 12f
            letterSpacing = 0.06f
            setTextColor(palette.accent)
            setPadding(0, dp(18), 0, dp(4))
        }

        private fun field(hint: String, flags: Int): EditText = EditText(context).apply {
            this.hint = hint
            setSingleLine()
            textSize = 17f
            typeface = font(R.font.segoeui_regular)
            inputType = InputType.TYPE_CLASS_TEXT or flags
            setPadding(dp(10), dp(9), dp(10), dp(9))
            palette.applyToField(this)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        }

        private fun draft(): PeopleStore.Draft = PeopleStore.Draft(
            givenName = given.text.toString(),
            familyName = family.text.toString(),
            phones = phoneRows.map {
                PeopleStore.Entry(it.first.text.toString(), it.second.text.toString())
            }.filter { it.value.isNotBlank() },
            emails = emailRows.map {
                PeopleStore.Entry(it.first.text.toString(), it.second.text.toString())
            }.filter { it.value.isNotBlank() },
            photo = photo
        )

        private fun save() {
            if (!PeopleStore.canWrite(context)) {
                onRequestPermissions(PeopleStore.permissions())
                return
            }
            val draft = draft()
            if (draft.isEmpty) {
                notify("Nothing to save yet")
                return
            }
            if (existing == null) {
                PeopleStore.create(context, account, draft) { id ->
                    PeopleStore.forgetAccounts()
                    close()
                    refresh()
                    if (id == null) notify("That contact could not be saved")
                    else showProfile(id)
                }
                return
            }
            val rawId = existing.rawId
            if (rawId == null) {
                notify("This contact is read-only on this phone")
                return
            }
            PeopleStore.update(context, rawId, draft) { ok ->
                close()
                refresh()
                if (!ok) notify("That contact could not be saved")
                else showProfile(existing.contact.id)
            }
        }

        fun close() {
            hideKeyboard()
            if (awaitingPhoto === this) awaitingPhoto = null
            dismissOverlay(page)
        }
    }

    /**
     * Which account a new contact opens on.
     *
     * Whatever was picked last, if that account is still on the phone; the first of them
     * otherwise, which puts Google at the top - see [PeopleStore.accounts]. A stored
     * choice of the phone itself is remembered as such and is not the same as never having
     * chosen, which is why this reads the key's presence rather than its value.
     */
    private fun rememberedAccount(choices: List<Account?>): Account? {
        val stored = prefs.getString(KEY_NEW_CONTACT_ACCOUNT, null)
            ?: return choices.firstOrNull()
        if (stored.isEmpty()) return null
        val type = stored.substringBefore(SEPARATOR)
        val name = stored.substringAfter(SEPARATOR, "")
        return choices.filterNotNull().firstOrNull { it.type == type && it.name == name }
            ?: choices.firstOrNull()
    }

    private fun rememberAccount(account: Account?) {
        prefs.edit().putString(
            KEY_NEW_CONTACT_ACCOUNT,
            if (account == null) "" else account.type + SEPARATOR + account.name
        ).apply()
    }

    private fun showEditor(
        existing: PeopleStore.Detail?,
        prefillNumber: String?,
        template: PeopleStore.Detail? = null
    ) {
        if (!PeopleStore.canWrite(context)) {
            onRequestPermissions(PeopleStore.permissions())
            return
        }
        pushOverlay(Editor(existing, prefillNumber, template).page)
    }

    /**
     * Writing down a number that arrived from somewhere else.
     *
     * The way in for Phone and Messaging: a call from a number the book has never heard of
     * and a text from one are both a person waiting to be written down, and the page that
     * writes one is here.
     */
    fun showNewContact(number: String?) = showEditor(null, prefillNumber = number)

    /** The gallery's answer, on its way back to whichever editor asked for it. */
    fun onPhotoPicked(uri: Uri?) {
        val editor = awaitingPhoto ?: return
        awaitingPhoto = null
        if (uri == null) return
        editor.onPhotoPicked(uri)
    }

    // ---------------------------------------------------------------- navigation

    /**
     * The list answers for its own jump grid and its own search band, both of which are
     * steps inside that page rather than ways out of the app.
     */
    override fun handleBackInProgram(): Boolean =
        ::contactsPage.isInitialized && contactsPage.handleBack()

    private companion object {
        /** Spelled here as every class in this shell spells it, beside the marks it names. */
        const val ICON_DIR = "custom_icons_8"
        const val EDIT_ICON = "$ICON_DIR/appbar.edit.svg"
        const val STAR_ICON = "$ICON_DIR/appbar.star.svg"
        const val SAVE_ICON = "$ICON_DIR/appbar.check.svg"
        const val CANCEL_ICON = "$ICON_DIR/appbar.cancel.svg"
        /** Mail. The hard-edged envelope, which is the one drawn as this shell draws. */
        const val EMAIL_ICON = "$ICON_DIR/appbar.email.hardedge.svg"

        const val PROFILE_FACE_DP = 176
        const val EDITOR_FACE_DP = 132

        /** How wide the kind of a number or address is, beside the field for it. */
        const val KIND_DP = 66

        /** Where new contacts go. This app's own, and nothing else reads it. */
        const val KEY_NEW_CONTACT_ACCOUNT = "wp81_people_new_contact_account"

        const val TAG = "WP81People"
    }
}
