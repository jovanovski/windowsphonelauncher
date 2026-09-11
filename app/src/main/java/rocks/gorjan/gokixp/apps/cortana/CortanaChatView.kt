package rocks.gorjan.gokixp.apps.cortana

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The conversation, where the ring and the greeting were.
 *
 * Cortana's screen was not a search box with a history; it was one page that changed what it
 * was showing. You arrived at a ring and a greeting, asked something, and the page became the
 * exchange - your question, her answer, and the next question under it, scrolling as it went.
 * That is the whole shape of this view: it is swapped in over the greeting when there is
 * something to say and swapped back out when there is not, and the box along the bottom of
 * the screen never moves, because it is the same box either way.
 *
 * The bubbles are the shell's own, from [rocks.gorjan.gokixp.apps.messaging.MessageThread]:
 * square, no tails, yours on the right in the accent and hers on the left in a grey lifted
 * off the page. Deliberately identical to a text conversation, because on this phone that is
 * what a conversation looks like and Cortana was not a special case - and because which side
 * a block of text is on is the only thing that needs to say who said it.
 *
 * She never speaks aloud here, which is not an omission. The phone read her answers out when
 * she had been asked out loud and stayed quiet when the question was typed - the point being
 * that you could ask her something in a meeting. Everything on this screen is typed.
 */
@SuppressLint("ViewConstructor")
internal class CortanaChatView(
    context: Context,
    private val palette: WP81Palette
) : ScrollView(context) {

    /**
     * Everything said so far, in order, which is also exactly what is sent on the next
     * question - see [CortanaAgent.ask]. Held here rather than beside the views because the
     * views are the same list drawn, and two copies of a conversation is one too many.
     */
    private val said = mutableListOf<CortanaAgent.Turn>()

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(PAGE_MARGIN_DP), dp(TOP_PAD_DP), dp(PAGE_MARGIN_DP), dp(BOTTOM_PAD_DP))
    }

    /** Every bubble on the page, so they can be re-measured when the page is. */
    private val bubbles = mutableListOf<TextView>()

    private val main = Handler(Looper.getMainLooper())

    init {
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        addView(
            column,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) applyBubbleWidths()
        }
    }

    /** What has been said, for the next request. */
    fun turns(): List<CortanaAgent.Turn> = said.toList()

    fun isEmpty(): Boolean = said.isEmpty()

    /**
     * Starts again with nothing on the page.
     *
     * The waiting ellipsis is stopped along with everything else, and this is the only place
     * it can be: it is a runnable that reposts itself until an answer arrives, so a bubble
     * cleared away while it was still thinking would leave a callback rescheduling itself
     * against a view nobody can see, for as long as the process lives.
     */
    fun clear() {
        main.removeCallbacksAndMessages(null)
        arriving = null
        said.clear()
        bubbles.clear()
        column.removeAllViews()
    }

    /** The same, for the page being taken off screen rather than emptied. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacksAndMessages(null)
    }

    /** A question, on the right in the accent. */
    fun addMine(text: String) {
        said += CortanaAgent.Turn(mine = true, text = text)
        column.addView(bubble(text, mine = true).first, wide())
        applyBubbleWidths()
        toBottom()
    }

    /**
     * Opens an answer, and hands back the way to fill it in.
     *
     * The bubble exists before a single character of the answer does, which is the point: a
     * question that vanished into an empty page for three seconds reads as a button that did
     * nothing. Until the first characters arrive it carries a working ellipsis, which is the
     * only animation on this page and the only thing standing in for the ring's old job of
     * saying that she is thinking.
     */
    fun beginHers(): Hers {
        // Whatever was arriving is not any more. Asking a second question before the first
        // answer has finished is ordinary, and the answer to the first is then unwanted -
        // but its ellipsis is a runnable that reposts itself until it is told to stop, so a
        // bubble left behind here would sit above the new question thinking for ever.
        arriving?.abandon()
        val (holder, view) = bubble("", mine = false)
        column.addView(holder, wide())
        applyBubbleWidths()
        toBottom()
        return Hers(holder, view).also { arriving = it }
    }

    /** The answer currently being written in, if there is one. See [beginHers]. */
    private var arriving: Hers? = null

    /**
     * An answer being written into the page as it arrives.
     *
     * Everything on it runs on the main thread - see [CortanaAgent.Listener] - so there is
     * no locking here and none is needed. It keeps its own copy of the text rather than
     * reading the view back, because the view holds a formatted [CharSequence] and appending
     * to what a TextView reports is how a stream picks up an ellipsis it thought it had
     * removed.
     */
    inner class Hers(private val holder: View, private val view: TextView) {

        private val text = StringBuilder()
        private var thinking = true

        /** The ellipsis, which runs until the first characters arrive. */
        private var dots = 0
        private val tick = object : Runnable {
            override fun run() {
                if (!thinking) return
                dots = (dots + 1) % (THINKING_DOTS + 1)
                view.text = ".".repeat(dots)
                main.postDelayed(this, THINKING_MS)
            }
        }

        init {
            view.text = ""
            main.postDelayed(tick, THINKING_MS)
        }

        fun append(delta: String) {
            if (thinking) stopThinking()
            val wasAtBottom = atBottom()
            text.append(delta)
            // The string rather than the builder. A TextView keeps the CharSequence it is
            // handed, and handing it one that is about to be appended to again is how a
            // view and its own text stop agreeing about how long it is.
            view.text = text.toString()
            // Only if the reader was already at the newest words. Somebody who has scrolled
            // up to re-read the question is being carried away from it otherwise, several
            // times a second, by an answer they can look at when it has finished.
            if (wasAtBottom) toBottom()
        }

        /**
         * The answer is complete: it becomes part of what has been said.
         *
         * Recorded here and not a character earlier. A half-arrived answer that got into the
         * history would be sent back on the next question as though she had said it, and the
         * follow-up would be answered against a sentence that stops mid-word.
         */
        fun done() {
            stopThinking()
            said += CortanaAgent.Turn(mine = false, text = text.toString())
            applyBubbleWidths()
            if (arriving === this) arriving = null
        }

        /**
         * It is not going to finish.
         *
         * What did arrive stays - a failure four sentences in is not a reason to take four
         * sentences away from somebody who has read them - and the reason goes underneath in
         * the failure colour, where a message that did not send says the same kind of thing.
         * Nothing is added to the history: what is on the page is a fragment, and the model
         * should not be told on the next question that it said one.
         *
         * A failure before anything arrived leaves an empty bubble, so that one is taken
         * away and the note stands on its own.
         */
        fun fail(reason: String) {
            stopThinking()
            if (text.isEmpty()) {
                bubbles.remove(view)
                column.removeView(holder)
            }
            column.addView(note(reason), wide())
            applyBubbleWidths()
            toBottom()
            if (arriving === this) arriving = null
        }

        /**
         * It has been given up on, with nothing to say about why.
         *
         * What arrived stays if anything did - it was said, and it was read - but an answer
         * that never started leaves an empty bubble, and an empty bubble abandoned in the
         * middle of a conversation is not a record of anything. Nothing goes into the
         * history either way: a fragment is not something she said.
         */
        fun abandon() {
            stopThinking()
            if (text.isEmpty()) {
                bubbles.remove(view)
                column.removeView(holder)
            }
            if (arriving === this) arriving = null
        }

        private fun stopThinking() {
            if (!thinking) return
            thinking = false
            main.removeCallbacks(tick)
            view.text = text.toString()
        }
    }

    /**
     * A line under the conversation that is not part of it.
     *
     * Used for what went wrong, and left-aligned with her bubbles because that is where it
     * comes from - but plain rather than filled, because a failure is the app talking about
     * the service and not the service talking.
     */
    private fun note(text: String): View = TextView(context).apply {
        this.text = text
        typeface = font(R.font.segoeui_regular)
        textSize = NOTE_SP
        setTextColor(FAILED)
        setPadding(dp(2), dp(4), dp(2), dp(6))
    }

    /**
     * One thing said.
     *
     * Held to copy it. An answer is frequently the thing somebody wanted to put somewhere
     * else - a command, an address, a sentence they asked to have written - and this screen
     * has no other way out of it: there is no forward, no share and no selection. Confirmed
     * by the tick rather than by a toast, because Cortana's window is its own and the shell's
     * notifications belong to the shell.
     */
    private fun bubble(text: String, mine: Boolean): Pair<View, TextView> {
        val side = if (mine) Gravity.END else Gravity.START
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = side
            setPadding(0, dp(3), 0, dp(3))
        }
        val view = TextView(context).apply {
            this.text = text
            typeface = font(R.font.segoeui_regular)
            textSize = BUBBLE_SP
            setPadding(dp(12), dp(9), dp(12), dp(10))
            setBackgroundColor(if (mine) palette.accent else hers())
            setTextColor(if (mine) palette.onAccent() else palette.foreground)
            setTextIsSelectable(false)
            setOnLongClickListener {
                copy(this.text.toString())
                Haptics.tap(it)
                true
            }
        }
        bubbles.add(view)
        holder.addView(
            view,
            LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = side }
        )
        return holder to view
    }

    private fun copy(text: String) {
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Cortana", text))
    }

    /** The fill her answers sit in: the page, lifted just enough to be a block on it. */
    private fun hers(): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, HERS_LIFT)

    /**
     * How wide a bubble is allowed to get.
     *
     * Measured against this page rather than the display, like the messaging app's, because
     * Cortana is a window and a window is not always the whole screen.
     */
    private fun applyBubbleWidths() {
        val room = column.width - column.paddingLeft - column.paddingRight
        if (room <= 0) return
        val max = (room * BUBBLE_SHARE).toInt()
        for (bubble in bubbles) bubble.maxWidth = max
    }

    private fun atBottom(): Boolean {
        val content = getChildAt(0) ?: return true
        return content.height - (scrollY + height) < dp(BOTTOM_SLACK_DP)
    }

    /**
     * Posted rather than scrolled here.
     *
     * The bubble that is being scrolled to has just been added and has not been measured, so
     * the page does not yet know it is any taller than it was - a scroll on this line lands
     * at the bottom of the page as it was a moment ago, which is the top of the new bubble.
     */
    private fun toBottom() {
        post { fullScroll(FOCUS_DOWN) }
    }

    private fun font(res: Int): Typeface? = ResourcesCompat.getFont(context, res)

    private fun wide() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** The page's gutter, which is Cortana's own rather than the messaging app's. */
        const val PAGE_MARGIN_DP = 16
        const val TOP_PAD_DP = 10
        const val BOTTOM_PAD_DP = 10

        const val BUBBLE_SP = 16f
        const val NOTE_SP = 12f

        /** As the messaging app: wide enough to read, narrow enough to have a side. */
        const val BUBBLE_SHARE = 0.76f
        const val HERS_LIFT = 0.16f

        /** How near the newest words the reader has to be to be carried along by them. */
        const val BOTTOM_SLACK_DP = 120

        /** The waiting ellipsis: how many dots it counts to, and how fast. */
        const val THINKING_DOTS = 3
        const val THINKING_MS = 380L

        /** Windows Phone's own red, which is what a message that did not send is written in. */
        const val FAILED = 0xFFE51400.toInt()
    }
}
