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
     */
    private lateinit var allList: ContactList

    /**
     * The page the list sits on, so a word can be put above it.
     *
     * The list itself has no room for one - it is a recycler of rows, and its empty case is
     * simply no rows - and the one thing this app must be able to say is that it has not
     * been allowed to read the book at all. See [bindContacts].
     */
    private lateinit var allPage: LinearLayout

    private var contacts: List<PeopleStore.Contact> = emptyList()

    /** The editor waiting for a picture, while the gallery is open over the app. */
    private var awaitingPhoto: Editor? = null

    /** People a directory answered with, shown under the book while a search is on. */
    private var directoryHits: List<PeopleStore.Contact> = emptyList()

    /** The directory search waiting for the typing to settle. See [searchDirectory]. */
    private var directorySearch: Runnable? = null

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

        allList = ContactList(context, palette).apply {
            onPick = { person ->
                // Somebody found in a directory has no card to open - they are not on this
                // phone - so tapping them offers to put them on it, with what the directory
                // knew already filled in. See PeopleStore.Contact.directory.
                if (person.directory) {
                    showEditor(
                        null,
                        prefillNumber = person.number,
                        prefillName = person.name
                    )
                } else {
                    showProfile(person.id)
                }
            }
            onLongPress = { person, anchor -> showContactMenu(person, anchor) }
            // The keyboard's search key opens whoever is at the top of what is left, which
            // is the person somebody typing three letters was typing towards.
            onSearchSubmit = { _, found ->
                found.firstOrNull()?.let { person ->
                    if (person.directory) {
                        showEditor(null, prefillNumber = person.number, prefillName = person.name)
                    } else {
                        showProfile(person.id)
                    }
                }
            }
            // Every account's directory, asked as the letters arrive. A colleague in a work
            // account's company directory is in none of the queries that read this phone -
            // there is nothing here to read - so a search that only filtered what was
            // already loaded could never find them. See searchDirectories.
            onQueryChanged = { typed -> searchDirectory(typed) }
            // Leaving search puts the directory's people away with it. They were answers
            // to a question that is no longer being asked, and a list of everybody with
            // four colleagues stuck on the end of it is not the address book.
            onSearchChanged = { on -> if (!on) forgetDirectoryHits() }
        }
        allPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        allPage.addView(allList, LinearLayout.LayoutParams(MATCH, 0, 1f))
        // Nameless: there is one section, and a strip reading "all" under a wordmark
        // reading "people" is a heading for a page nobody can leave. See
        // MetroPanorama.rebuildStrip, which draws no strip when nothing is named.
        panorama.addPage("", allPage)

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
        allList.setJumpListHost(root)

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
    private fun searchContacts() = allList.beginSearch()

    /**
     * The shell's search key, while the book itself is what is on screen.
     *
     * Withdrawn under a page opened over it - a profile, the editor. Those are somewhere
     * the user went into, neither has a search, and a key that searched the contacts from
     * inside the editor would be an exit dressed as a filter. Cortana has it back until
     * they come out. See [rocks.gorjan.gokixp.wp81.WP81Searchable].
     */
    override fun searchAction(): (() -> Unit)? =
        if (overlays.isNotEmpty() || !::allList.isInitialized) null else ({ searchContacts() })

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
            contacts = emptyList()
            bindContacts()
            return
        }
        PeopleStore.all(context) { people ->
            contacts = people
            bindContacts()
        }
    }

    private fun bindContacts() {
        // The one thing an empty list cannot say for itself. Taken down again the moment
        // the permission arrives, which is what makes the offer worth tapping.
        while (allPage.childCount > 1) allPage.removeViewAt(0)
        if (!PeopleStore.canRead(context)) {
            allList.setItems(emptyList())
            allPage.addView(
                note("no access to your contacts.  tap to allow") {
                    onRequestPermissions(PeopleStore.permissions())
                },
                0,
                LinearLayout.LayoutParams(MATCH, WRAP).apply { marginEnd = dp(PAGE_MARGIN_DP) }
            )
            return
        }
        allList.setItems(contacts + directoryHits)
    }

    /** Drops the directory's answers and puts the list back to the book alone. */
    private fun forgetDirectoryHits() {
        directorySearch?.let { root.removeCallbacks(it) }
        directorySearch = null
        if (directoryHits.isEmpty()) return
        directoryHits = emptyList()
        allList.setItems(contacts)
    }

    /**
     * Asks the directories about what is being typed, once the typing has settled.
     *
     * Debounced, because each of these is a network round trip and somebody typing a name
     * would otherwise send one per letter. Short queries are not asked at all - see
     * [PeopleStore.searchDirectories] - and the answer is dropped if the field has moved on
     * since, so a slow directory cannot put stale people under a query they do not match.
     */
    private fun searchDirectory(typed: String) {
        directorySearch?.let { root.removeCallbacks(it) }
        val wanted = typed.trim()
        if (wanted.isEmpty()) {
            if (directoryHits.isNotEmpty()) {
                directoryHits = emptyList()
                allList.setItems(contacts)
            }
            return
        }
        val run = Runnable {
            PeopleStore.searchDirectories(context, wanted) { found ->
                // The field has moved on while the network was thinking.
                if (allList.searchText().trim() != wanted) return@searchDirectories
                // Anybody already in the book is already in the list, and a colleague who
                // is both would otherwise appear twice.
                directoryHits = found.filter { person ->
                    contacts.none { it.name.equals(person.name, ignoreCase = true) }
                }
                allList.setItems(contacts + directoryHits)
            }
        }
        directorySearch = run
        root.postDelayed(run, DIRECTORY_DEBOUNCE_MS)
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
    fun showProfile(contactId: Long) {
        PeopleStore.detail(context, contactId) { detail ->
            if (detail == null) {
                notify("That contact is no longer on this phone")
                refresh()
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
        // account that overwrites it - and this is where somebody would look.
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
        prefillName: String?
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
            existing?.let {
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
                showFace(it.contact)
            }
            if (!prefillNumber.isNullOrBlank()) addPhone(prefillNumber, "mobile")
            // What a directory knew about somebody being saved off it. Split on the first
            // space, which is the same guess the phone's own editor makes and the same one
            // this editor's two fields ask the user to make.
            if (!prefillName.isNullOrBlank()) {
                given.setText(prefillName.substringBefore(' ').trim())
                family.setText(prefillName.substringAfter(' ', "").trim())
            }
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

        private fun showFace(person: PeopleStore.Contact) {
            faceInitials.text = person.initials.ifBlank { "photo" }
            val uri = person.photoUri ?: return
            ContactFeed.load(context, uri) { bitmap ->
                if (bitmap != null) showBitmap(bitmap)
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
        prefillName: String? = null
    ) {
        if (!PeopleStore.canWrite(context)) {
            onRequestPermissions(PeopleStore.permissions())
            return
        }
        pushOverlay(Editor(existing, prefillNumber, prefillName).page)
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
        ::allList.isInitialized && allList.handleBack()

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

        /** How long the typing has to settle before a directory is asked about it. */
        const val DIRECTORY_DEBOUNCE_MS = 400L

        const val TAG = "WP81People"
    }
}
