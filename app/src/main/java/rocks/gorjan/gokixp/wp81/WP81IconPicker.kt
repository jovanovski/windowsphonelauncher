package rocks.gorjan.gokixp.wp81

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.R

/**
 * The Metro icon picker.
 *
 * Replaces the Vista "Change Icon" window for this shell: a lowercase page heading, two
 * commands along the top for resetting or browsing, and a flat grid of the theme's icon
 * set below. No window chrome, no tabbed dialog.
 *
 * Icons arrive in batches from the host rather than all at once - the bundled sets run to
 * several hundred files and decoding them up front stalls the page-in.
 */
@SuppressLint("ViewConstructor")
class WP81IconPicker(
    context: Context,
    private var palette: WP81Palette
) : FrameLayout(context) {

    /** An icon the user can choose: [path] is what gets persisted. */
    data class Choice(val path: String, val drawable: Drawable)

    var onPicked: ((String) -> Unit)? = null
    var onBrowse: (() -> Unit)? = null
    var onResetToDefault: (() -> Unit)? = null

    /**
     * Tapping "icon pack", which refills the grid from the pack the user has on.
     *
     * A command rather than a few hundred more squares below the bundled set. The set this
     * page offers is the phone's own - flat white glyphs, drawn for these tiles - and
     * pouring a pack's full-colour artwork in after it would bury the one thing this page
     * is for under the other thing. The command switches sources; it does not merge them.
     * Hidden unless there is a pack on. See [setIconPackAvailable].
     */
    var onIconPack: ((Boolean) -> Unit)? = null

    /** Which source the grid is filled from, so the command can go back the other way. */
    private var showingPack = false

    private val heading = TextView(context)
    private val resetCommand = TextView(context)
    private val browseCommand = TextView(context)
    private val packCommand = TextView(context)
    private val grid = RecyclerView(context)
    private val choices = mutableListOf<Choice>()
    private val adapter = Adapter()

    init {
        visibility = GONE
        isClickable = true

        heading.typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        heading.textSize = 34f
        heading.text = "choose icon"
        heading.includeFontPadding = false
        heading.setPadding(dp(22), dp(20), dp(22), dp(12))

        for ((command, label) in listOf(
            resetCommand to "use default",
            browseCommand to "browse",
            packCommand to "icon pack",
        )) {
            command.text = label
            command.textSize = 15f
            command.typeface = ResourcesCompat.getFont(context, R.font.segoeui_regular)
            command.setPadding(dp(14), dp(10), dp(14), dp(10))
            command.isClickable = true
            TiltEffect.apply(command)
        }
        resetCommand.setOnClickListener { onResetToDefault?.invoke() }
        browseCommand.setOnClickListener { onBrowse?.invoke() }
        packCommand.setOnClickListener {
            // Flipped here rather than by the host, so the word on the command is never out
            // of step with what the grid below it is showing.
            setIconPackShowing(!showingPack)
            onIconPack?.invoke(showingPack)
        }
        packCommand.visibility = GONE

        val commands = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
            addView(resetCommand)
            // Room between them: two commands set in the same face and touching read as
            // one phrase - and the left of the two throws away what the right of them is
            // for, so a mis-tap costs the user their choice.
            addView(browseCommand, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(COMMAND_GAP_DP) })
            addView(packCommand, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(COMMAND_GAP_DP) })
        }

        grid.layoutManager = GridLayoutManager(context, COLUMNS)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(commands, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyPalette(palette)
    }

    fun show(forLabel: String) {
        heading.text = forLabel.lowercase().ifEmpty { "choose icon" }
        setIconPackShowing(false)
        choices.clear()
        adapter.notifyDataSetChanged()
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dismiss() {
        visibility = GONE
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    /** Offers the pack's own icons as a source, for as long as a pack is on. */
    fun setIconPackAvailable(available: Boolean) {
        packCommand.visibility = if (available) VISIBLE else GONE
    }

    /**
     * Says which source the grid is showing, in the word on the command itself.
     *
     * The command is the only way between the two sets, so it has to name where it goes
     * rather than what it is: a command still reading "icon pack" over a grid full of the
     * pack's icons is a page with no way back to the phone's own.
     */
    private fun setIconPackShowing(showing: Boolean) {
        showingPack = showing
        packCommand.text = if (showing) "windows phone" else "icon pack"
    }

    /**
     * Empties the grid so the host can refill it from somewhere else.
     *
     * [show] does this too, but only on the way in. Switching between the bundled set and
     * an icon pack happens with the page already up, and appending the second source to
     * the first is how a grid ends up holding both.
     */
    fun clearChoices() {
        choices.clear()
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
    }

    /** Appends a decoded batch. Called repeatedly as the host works through the set. */
    fun addChoices(batch: List<Choice>) {
        if (batch.isEmpty()) return
        val start = choices.size
        choices.addAll(batch)
        adapter.notifyItemRangeInserted(start, batch.size)
    }

    fun applyPalette(p: WP81Palette) {
        palette = p
        setBackgroundColor(p.background)
        heading.setTextColor(p.foreground)
        for (command in listOf(resetCommand, browseCommand, packCommand)) {
            command.setTextColor(p.foreground)
            command.setBackgroundColor(p.inactive)
        }
        adapter.notifyDataSetChanged()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
            val image = ImageView(frame.context)

            init {
                frame.addView(image, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
                    val pad = dp(10)
                    setMargins(pad, pad, pad, pad)
                })
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                frame.isClickable = true
                TiltEffect.apply(frame)
                frame.setOnClickListener {
                    choices.getOrNull(bindingAdapterPosition)?.let { onPicked?.invoke(it.path) }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val frame = FrameLayout(parent.context)
            val side = parent.measuredWidth / COLUMNS
            frame.layoutParams = RecyclerView.LayoutParams(side, side)
            return Holder(frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.image.setImageDrawable(choices[position].drawable)
            holder.frame.setBackgroundColor(palette.inactive)
        }

        override fun getItemCount() = choices.size
    }

    companion object {
        /** Space between "use default" and "browse". */
        private const val COMMAND_GAP_DP = 22

        private const val COLUMNS = 4
    }
}
