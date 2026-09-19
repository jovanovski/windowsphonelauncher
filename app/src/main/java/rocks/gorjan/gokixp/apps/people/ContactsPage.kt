package rocks.gorjan.gokixp.apps.people

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The address book's page, wherever it happens to be standing.
 *
 * This is People's one page - everybody, under letter squares, with the jump grid and the
 * search band the phone gave the book - lifted out of [PeopleApp] so that Phone can put
 * the same page on its panorama rather than a second list that merely resembles it. The
 * argument is [ContactList]'s own, one level up: two alphabetical lists of people in one
 * shell that differ in what they do with a held row, or in whether they ask the accounts'
 * directories, are two books that disagree about who is in them.
 *
 * What the page holds is the list, the line that says the book cannot be read at all -
 * which the list has no room for, its empty case being simply no rows - and the directory
 * search, which is the one part of looking somebody up that is not filtering.
 *
 * What it does *not* hold is what a tap means. Opening somebody is a page in People and a
 * trip to People from Phone, and holding somebody offers a different handful of commands
 * in each: see [onPick] and [onHeld]. The rest is the same page in both.
 *
 * The contacts themselves are handed in rather than read here - see [setContacts]. Both
 * apps already read the book for something else (the wall of favourites, the wall of
 * faces), and a page that went and asked again would be a second query for a list the
 * program in front of it is already holding.
 */
@SuppressLint("ViewConstructor")
class ContactsPage(
    context: Context,
    private val palette: WP81Palette,
    /** Contacts, asked for at the moment the page has to say it has nothing to show. */
    private val onRequestPermissions: (Array<String>) -> Unit
) : LinearLayout(context) {

    val list = ContactList(context, palette)

    /** Somebody tapped - out of the book, or out of a directory that answered. */
    var onPick: ((PeopleStore.Contact) -> Unit)? = null

    /** Somebody held, with the row's bottom edge for a command list to hang from. */
    var onHeld: ((PeopleStore.Contact, Float) -> Unit)? = null

    private var contacts: List<PeopleStore.Contact> = emptyList()

    /** People a directory answered with, shown under the book while a search is on. */
    private var directoryHits: List<PeopleStore.Contact> = emptyList()

    /** The directory search waiting for the typing to settle. See [searchDirectory]. */
    private var directorySearch: Runnable? = null

    init {
        orientation = VERTICAL
        list.onPick = { person -> onPick?.invoke(person) }
        list.onLongPress = { person, anchorY -> onHeld?.invoke(person, anchorY) }
        // The keyboard's search key opens whoever is at the top of what is left, which is
        // the person somebody typing three letters was typing towards.
        list.onSearchSubmit = { _, found -> found.firstOrNull()?.let { onPick?.invoke(it) } }
        // Every account's directory, asked as the letters arrive. A colleague in a work
        // account's company directory is in none of the queries that read this phone -
        // there is nothing here to read - so a search that only filtered what was already
        // loaded could never find them. See PeopleStore.searchDirectories.
        list.onQueryChanged = { typed -> searchDirectory(typed) }
        // Leaving search puts the directory's people away with it. They were answers to a
        // question that is no longer being asked, and a list of everybody with four
        // colleagues stuck on the end of it is not the address book.
        list.onSearchChanged = { on -> if (!on) forgetDirectoryHits() }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * Where the jump grid is put up.
     *
     * It measures itself against the whole display, because that is what it covers - so it
     * goes over the program rather than inside the page, which is only as tall as the
     * panorama leaves it.
     */
    fun setJumpListHost(host: ViewGroup) = list.setJumpListHost(host)

    /**
     * Looking somebody up.
     *
     * [animated] is the band folding the letters away under the reader's eye, which is
     * right when search is asked for on the page they are already on and wrong when the
     * page is arriving from a swipe of the panorama at the same moment.
     */
    fun beginSearch(animated: Boolean = true) = list.beginSearch(animated = animated)

    /**
     * The jump grid and the search band, both of which are steps inside this page rather
     * than ways out of the program standing on it.
     */
    fun handleBack(): Boolean = list.handleBack()

    // ---------------------------------------------------------------- the book

    /** The book, as the program that owns this page last read it. */
    fun setContacts(people: List<PeopleStore.Contact>) {
        contacts = people
        bind()
    }

    private fun bind() {
        // The one thing an empty list cannot say for itself. Taken down again the moment
        // the permission arrives, which is what makes the offer worth tapping.
        while (childCount > 1) removeViewAt(0)
        if (!PeopleStore.canRead(context)) {
            list.setItems(emptyList())
            addView(
                metroNote(context, palette, "no access to your contacts.  tap to allow") {
                    onRequestPermissions(PeopleStore.permissions())
                },
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(PAGE_MARGIN_DP)
                }
            )
            return
        }
        list.setItems(contacts + directoryHits)
    }

    /** Drops the directory's answers and puts the list back to the book alone. */
    private fun forgetDirectoryHits() {
        directorySearch?.let { removeCallbacks(it) }
        directorySearch = null
        if (directoryHits.isEmpty()) return
        directoryHits = emptyList()
        list.setItems(contacts)
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
        directorySearch?.let { removeCallbacks(it) }
        val wanted = typed.trim()
        if (wanted.isEmpty()) {
            if (directoryHits.isNotEmpty()) {
                directoryHits = emptyList()
                list.setItems(contacts)
            }
            return
        }
        val run = Runnable {
            PeopleStore.searchDirectories(context, wanted) { found ->
                // The field has moved on while the network was thinking.
                if (list.searchText().trim() != wanted) return@searchDirectories
                // Anybody already in the book is already in the list, and a colleague who
                // is both would otherwise appear twice.
                directoryHits = found.filter { person ->
                    contacts.none { it.name.equals(person.name, ignoreCase = true) }
                }
                list.setItems(contacts + directoryHits)
            }
        }
        directorySearch = run
        postDelayed(run, DIRECTORY_DEBOUNCE_MS)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** The panorama's own left margin, matched on the right so the page is even. */
        const val PAGE_MARGIN_DP = 22

        /** How long the typing has to settle before a directory is asked about it. */
        const val DIRECTORY_DEBOUNCE_MS = 400L
    }
}
