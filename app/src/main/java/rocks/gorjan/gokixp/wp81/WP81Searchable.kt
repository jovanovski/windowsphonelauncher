package rocks.gorjan.gokixp.wp81

/**
 * A program with a search of its own for the shell's search key.
 *
 * The third key means Cortana everywhere on this phone, which is right on the Start screen
 * and wrong the moment somebody is standing in front of four thousand contacts with a
 * magnifying glass drawn at the bottom of the screen that will not search them. So a
 * program that has somewhere for that key to go says so: the shell lights the key in the
 * accent while that program is in front, and a tap goes where the program asked instead of
 * to Cortana.
 *
 * Holding the key is still Cortana, wherever the user is - see [WP81NavBar.onSearchLongPress].
 * That is what makes handing the tap over safe at all: she is never more than a hold away,
 * and no app can take her off the phone by claiming the key.
 *
 * The answer is asked for rather than handed over, because it changes with the screen the
 * program is showing - the address book searches contacts, the profile page opened over it
 * searches nothing - and a program that pushed its offer would have to remember to push it
 * again on every way back out. [onSearchOfferChanged] says only that the answer may have
 * changed; what it is now is a question for [searchAction].
 */
interface WP81Searchable {

    /**
     * What the search key should do on the screen this program is showing now, or null if
     * this screen has no search - in which case the key is Cortana's, as it is everywhere
     * else.
     */
    fun searchAction(): (() -> Unit)?

    /**
     * Set by the host, and called by the program whenever [searchAction] might answer
     * differently: a page opened over the app, a page closed off it.
     *
     * Fired without knowing whether this program is the one in front, because it cannot
     * know - it does not own its window. The host is the one that can tell, and asks the
     * program in front what the key means now.
     */
    var onSearchOfferChanged: (() -> Unit)?
}
