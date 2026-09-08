package rocks.gorjan.gokixp.apps.people

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.MetroAppBar
import rocks.gorjan.gokixp.wp81.PeopleStore
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81ContextMenu
import rocks.gorjan.gokixp.wp81.WP81InputDialog
import rocks.gorjan.gokixp.wp81.WP81Palette
import rocks.gorjan.gokixp.wp81.WP81Program
import rocks.gorjan.gokixp.wp81.WP81Searchable

/**
 * What People, Phone and Messaging still share, now that they are three programs.
 *
 * They were one - a panorama with favourites, a call log, conversations and the address
 * book on four pages of it - and splitting them the way Windows Phone had them left three
 * apps that are each about the same list of people. Every one of them puts pages over
 * itself, anchors a command list to a row, asks before deleting something, rings a number
 * and draws somebody's face. Written once here rather than three times over, because three
 * copies of a page stack is three chances for back to mean something different depending
 * on which of these apps you happen to be standing in.
 *
 * Not a panorama and not an app bar: what each program *is* belongs to the program. This
 * is the furniture underneath - the overlay stack, the menu, the prompt, and the handful of
 * commands that are about a person rather than about a page.
 *
 * It lives in People's package because People is what the other two were carved off, and
 * the address book is the thing all three are really about.
 */
abstract class HubApp(
    protected val context: Context,
    protected var palette: WP81Palette,
    /**
     * Asks the host for permissions. Contacts, the call log, messages and calling are
     * asked for where they are first needed rather than in a heap on first run - an
     * address book is not something to demand from somebody who has opened the app to
     * look at it.
     */
    protected val onRequestPermissions: (Array<String>) -> Unit,
    /** Puts a band across the top of the shell. Titled with [appName]; see [notify]. */
    private val onNotify: (String, String) -> Unit
) : WP81Program, WP81Searchable {

    /**
     * What this program calls itself when it has something to say.
     *
     * The three of them post the same kinds of complaint - nothing to copy to, nothing on
     * the phone that dials - and a band that said "People" over a message typed in
     * Messaging would be naming an app the user is not in.
     */
    protected abstract val appName: String

    protected lateinit var root: FrameLayout
    protected lateinit var contextMenu: WP81ContextMenu
    protected lateinit var dialog: WP81InputDialog

    /** The strip along the foot of the program itself, where the program has one. */
    protected var appBar: MetroAppBar? = null

    /**
     * Pages stacked over the program, newest last.
     *
     * Tracked rather than counted off the root, for the reason Zune tracks its own: the
     * menu and the prompt live in the same parent, and "the last child" is only sometimes
     * a page somebody navigated into.
     */
    protected val overlays = mutableListOf<View>()

    /** Whether this session has already asked to be allowed to place calls. See [place]. */
    private var askedToCall = false

    /**
     * The things these apps remember that the phone's own stores do not - how the
     * favourites are arranged, and which account new contacts go to. One file, because
     * they were one app and the arrangement did not move when they stopped being one.
     */
    protected val prefs = context.getSharedPreferences(
        rocks.gorjan.gokixp.MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    override var onSearchOfferChanged: (() -> Unit)? = null

    /** No search unless the program says otherwise. See [WP81Searchable]. */
    override fun searchAction(): (() -> Unit)? = null

    // ---------------------------------------------------------------- construction

    /**
     * The frame every one of these is built in, with the menu and the prompt over it.
     *
     * Called by the program's own `createView` once it has put its pages in, because both
     * of these have to be added after whatever they will be shown over - and brought to
     * the front again as they are shown, since pages arrive on top of them afterwards.
     */
    protected fun buildRoot(): FrameLayout {
        root = FrameLayout(context).apply { setBackgroundColor(palette.background) }
        return root
    }

    /** The menu and the prompt, added over whatever the program has built. */
    protected fun addOverlayFurniture() {
        contextMenu = WP81ContextMenu(context, palette)
        root.addView(contextMenu, FrameLayout.LayoutParams(MATCH, MATCH))

        dialog = WP81InputDialog(context, palette)
        root.addView(dialog, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** A section's column: the rows themselves, padded off both edges of the page. */
    protected fun sectionColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), dp(PAGE_MARGIN_DP), dp(24))
        // A favourite being carried leaves its slot, and the column's own right-hand
        // margin is the first thing it crosses. Without this it is cut off at the edge of
        // the wall it is being dragged across.
        clipChildren = false
    }

    protected fun page(column: LinearLayout, onNearEnd: () -> Unit): GrowingScroll =
        GrowingScroll(onNearEnd).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
        }

    /**
     * A page that says when the reader is getting near the end of what has been built.
     *
     * Measured against the height of the content rather than a row count, so it holds
     * however tall the rows come out - a section of calls and a section of conversations
     * have different rows and both use this.
     */
    protected inner class GrowingScroll(
        private val onNearEnd: () -> Unit
    ) : ScrollView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            val content = getChildAt(0) ?: return
            if (content.height - (t + height) < height) onNearEnd()
        }
    }

    // ---------------------------------------------------------------- navigation

    /** A page over the app: full height, its own background, and it swallows stray taps. */
    protected fun overlayPage(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        // Anything that falls past the rows stops here, so the panorama underneath does not
        // page sideways while a page is open on top of it.
        isClickable = true
    }

    protected fun pushOverlay(view: View) {
        overlays.add(view)
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        rocks.gorjan.gokixp.wp81.MetroPageTransition(view).playIn()
        // The program is no longer the screen in front, so the key it was lending the
        // shell goes back to Cortana. See searchAction.
        onSearchOfferChanged?.invoke()
    }

    protected open fun dismissOverlay(view: View) {
        overlays.remove(view)
        rocks.gorjan.gokixp.wp81.MetroPageTransition(view).playOut { root.removeView(view) }
        // Announced now rather than when the page has finished turning out: the key
        // belongs to the screen the user is on their way to, which is the same reasoning
        // the shell's own strip is set on, not the one leaving.
        onSearchOfferChanged?.invoke()
    }

    /**
     * Back, from the inside out.
     *
     * The window one of these lives in reads back as "put the program away", which is
     * right at the top of it and wrong everywhere below. So everything opened over the
     * program is closed first, one press at a time, and only a press with nothing left to
     * close leaves.
     */
    open fun handleBack(): Boolean {
        if (dialog.isShowing()) {
            dialog.dismiss()
            return true
        }
        if (contextMenu.isShowing()) {
            contextMenu.dismiss()
            return true
        }
        if (appBar?.isMenuOpen() == true) {
            appBar?.closeMenu()
            return true
        }
        if (handleBackInProgram()) return true
        overlays.lastOrNull()?.let { top ->
            // A page carries its own strip, and a command list open on that strip is the
            // innermost thing on screen - so it is what back closes, before the page it
            // belongs to.
            if (barOf(top)?.closeMenu() == true) return true
            hideKeyboard()
            dismissOverlay(top)
            return true
        }
        return false
    }

    /**
     * Anything of the program's own that back should close before the page stack.
     *
     * The address book's jump grid and its search band are both steps inside a page rather
     * than ways out of the app, and neither is an overlay.
     */
    protected open fun handleBackInProgram(): Boolean = false

    /** A page's own command strip, if it has one. See [handleBack]. */
    private fun barOf(page: View): MetroAppBar? {
        val group = page as? android.view.ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? MetroAppBar)?.let { return it }
        }
        return null
    }

    protected fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    /**
     * The command list and the prompt, put up over whatever is on screen.
     *
     * Always through these rather than through the views themselves. Both are built with
     * the app and every page is added over them, so a menu opened from inside a profile -
     * or the prompt that asks before a contact is deleted - would otherwise go up behind
     * the page that asked for it. Brought to the front as they are shown, which is the
     * only moment their depth matters.
     */
    protected fun showMenu(title: String?, items: List<WP81ContextMenu.Item>, anchor: View) =
        showMenu(title, items, anchorYOf(anchor))

    protected fun showMenu(title: String?, items: List<WP81ContextMenu.Item>, anchorY: Float) {
        contextMenu.bringToFront()
        contextMenu.show(title, items, anchorY)
    }

    /** Where a view sits in the app's own coordinates, for anything anchored to it. */
    protected fun anchorYOf(anchor: View): Float {
        val position = IntArray(2)
        anchor.getLocationInWindow(position)
        return position[1].toFloat()
    }

    protected fun ask(title: String, question: String, accept: String, onAccept: () -> Unit) {
        dialog.bringToFront()
        dialog.confirm(title, question, accept, onAccept)
    }

    // ---------------------------------------------------------------- commands

    /** A band across the top of the shell, in this program's name. See [appName]. */
    protected fun notify(message: String) = onNotify(appName, message)

    /**
     * Rings a number.
     *
     * Placed by the launcher itself where it has been allowed to, and handed to the phone's
     * own dialler where it has not - which is also what happens on a device with no
     * telephony at all. The permission is asked for at the moment somebody actually tries
     * to make a call, because that is the moment the request explains itself.
     *
     * In all three programs rather than only in Phone: a call placed from a contact's card
     * or from a conversation is the same call, and routing it through the Phone app's
     * window first would open a program to close it again a moment later.
     */
    protected fun place(number: String) {
        if (number.isBlank()) return
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val allowed = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        // Placed through Telecom rather than thrown at whatever answers a tel: intent,
        // once this app is the phone. It is the same call either way - the difference is
        // that this one is asked for directly, so there is no moment where the request is
        // out in the system looking for somebody to take it.
        if (allowed && isThePhone()) {
            try {
                telecom()?.placeCall(uri, android.os.Bundle())
                return
            } catch (e: Exception) {
                Log.w(TAG, "Telecom would not place the call; falling back to an intent", e)
            }
        }
        // Asked for once, at the moment somebody actually tries to make a call - which is
        // the moment the request explains itself. Asking and dialling in the same breath
        // would put the system's prompt over the phone's dialler, so the first refusal is
        // just the prompt; from then on the number goes to the dialler with the call one
        // tap away, which is what somebody who said no to this asked for.
        if (!allowed && !askedToCall) {
            askedToCall = true
            onRequestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE))
            return
        }
        try {
            context.startActivity(
                Intent(if (allowed) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        } catch (e: Exception) {
            Log.w(TAG, "Could not place a call", e)
            try {
                context.startActivity(Intent(Intent.ACTION_DIAL, uri))
            } catch (e2: Exception) {
                Log.w(TAG, "Nothing on this phone dials", e2)
                notify("There is nothing on this phone to call with")
            }
        }
    }

    /**
     * Puts a number on the clipboard.
     *
     * Silent when it works. Android has shown what was copied itself since 13, and a word
     * of our own over the top of that is the same news twice - see MessageThread.copy,
     * which takes a message the same way and for the same reason.
     */
    protected fun copyNumber(number: String) {
        if (number.isBlank()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            if (clipboard == null) {
                notify("This phone has nowhere to copy to")
                return
            }
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("number", number))
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy a number", e)
            notify("That could not be copied")
        }
    }

    protected fun email(address: String) = open(Intent(
        Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(address))), "email")

    protected fun open(intent: Intent, what: String) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Nothing on this phone handles $what", e)
            notify("There is nothing on this phone to $what with")
        }
    }

    /**
     * Whether this launcher is the phone.
     *
     * Android hands every call on the device to one app, chosen by this role, and there is
     * no smaller version of it - an app cannot show its own calls and leave the rest to
     * somebody else. So holding it is what makes the call screen exist at all, and until
     * it is held a call placed from here is handed on to whoever does hold it.
     */
    fun isThePhone(): Boolean = try {
        val roles = context.getSystemService(android.app.role.RoleManager::class.java)
        roles?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true
    } catch (e: Exception) {
        Log.w(TAG, "Could not ask whether this app is the phone", e)
        false
    }

    private fun telecom(): android.telecom.TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager

    /**
     * Which of somebody's numbers to use.
     *
     * Asked only when there is a question: one number is not a choice, and putting a list
     * of one in front of somebody is a dialog that exists to be dismissed.
     */
    protected fun chooseNumber(person: PeopleStore.Contact, onPicked: (String) -> Unit) {
        PeopleStore.detail(context, person.id) { detail ->
            val numbers = detail?.phones.orEmpty()
            when {
                numbers.isEmpty() -> notify("${person.name} has no number to reach them on")
                numbers.size == 1 -> onPicked(numbers.first().value)
                else -> showMenu(
                    person.name,
                    numbers.map { phone ->
                        WP81ContextMenu.Item("${phone.label}  ·  ${phone.value}") {
                            onPicked(phone.value)
                        }
                    },
                    root.height / 3f
                )
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Somebody's face at the size a page wants it. See [ContactFace]. */
    protected fun faceView(person: PeopleStore.Contact, big: Boolean): View =
        ContactFace(context, palette).apply {
            setLetterSize(if (big) 40f else 20f)
            show(person)
        }

    /**
     * One of the rings a row puts beside a number.
     *
     * The app bar's own button - see [MetroAppBar.ring] - drawn on a page rather than on a
     * strip, so it takes the page's foreground for its ink. The ring shape is drawn white
     * precisely so a tint can decide what colour it really is, which is also how the strip
     * marks a command that is in force.
     */
    protected fun reachKey(icon: String, onTap: () -> Unit): View =
        MetroAppBar.ring(
            context,
            palette.foreground,
            rocks.gorjan.gokixp.wp81.SvgIcon.fromAsset(context, icon),
            REACH_GLYPH_INSET_DP
        ).apply {
            setOnClickListener {
                Haptics.tap(it)
                onTap()
            }
        }

    /** How one of those rings stands off what comes before it. */
    protected fun ring(first: Boolean) =
        LinearLayout.LayoutParams(dp(REACH_KEY_DP), dp(REACH_KEY_DP)).apply {
            marginStart = dp(if (first) REACH_KEY_INSET_DP else REACH_KEY_GAP_DP)
        }

    protected fun note(message: String, onTap: (() -> Unit)? = null) = TextView(context).apply {
        text = message
        typeface = font(R.font.segoeui_regular)
        textSize = 15f
        setTextColor(palette.foregroundSubtle)
        setPadding(0, dp(18), dp(16), dp(18))
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
            TiltEffect.apply(this)
        }
    }

    protected fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    protected fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    protected fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val PAGE_MARGIN_DP = 22

        /** Rows built at a time, and added again as the reader nears the end of them. */
        const val CHUNK = 30

        const val ICON_DIR = "custom_icons_8"
        const val ADD_ICON = "$ICON_DIR/appbar.add.svg"
        const val CALL_ICON = "$ICON_DIR/appbar.phone.svg"
        /** Texting, drawn as the call screen draws it - see CallScreen's own MESSAGE_ICON. */
        const val MESSAGE_ICON = "$ICON_DIR/appbar.message.svg"

        /**
         * The rings beside a number or an address on a page, at the app bar's own size and
         * with its glyph sitting the same way inside them.
         */
        const val REACH_KEY_DP = 44
        const val REACH_GLYPH_INSET_DP = 5

        /** Between the words and the first ring, and between the rings. */
        const val REACH_KEY_INSET_DP = 14
        const val REACH_KEY_GAP_DP = 14

        /** The face on a row, at the size the contact list sets one. */
        const val THREAD_FACE_DP = 42

        /** How many digits are typed before a number is guessed at rather than filtered. */
        const val MATCH_FROM = 3

        /**
         * What separates the parts of the two things these apps remember.
         *
         * A unit separator, because the things being joined are a contact lookup key and
         * an account name, and there is no printable character neither of them can hold.
         */
        const val SEPARATOR = "\u001f"

        private const val TAG = "WP81Hub"
    }
}
