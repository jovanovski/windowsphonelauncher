package rocks.gorjan.gokixp.apps.notepad

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import rocks.gorjan.gokixp.R
import rocks.gorjan.gokixp.apps.notepad.NoteFormat.Tool
import rocks.gorjan.gokixp.wp81.Haptics
import rocks.gorjan.gokixp.wp81.SvgIcon
import rocks.gorjan.gokixp.wp81.TiltEffect
import rocks.gorjan.gokixp.wp81.WP81Palette

/**
 * The formatting strip: a thinner row standing on the note's app bar while the note is being
 * written.
 *
 * Not part of the app bar. The app bar holds what is done to a note - a picture, the bin -
 * and is always there; this is about the words, and comes up with the caret and goes with
 * it. The same surface, so the two read as one piece of chrome, with a hairline between; and
 * bare glyphs rather than the bar's rings, smaller, so that the strip reads as the lesser of
 * the two and the rings stay the bar's own.
 *
 * A command lights in the accent while it is in force at the caret - see NoteFormat.active -
 * which is how "bold, for what I type next" can be seen before anything has been typed.
 */
@SuppressLint("ViewConstructor")
internal class NoteFormatBar(
    context: Context,
    private val palette: WP81Palette,
    private val onTool: (Tool) -> Unit
) : LinearLayout(context) {

    private val buttons = LinkedHashMap<Tool, View>()
    private var lit: Set<Tool> = emptySet()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.chrome)
        // A tap between the buttons is the strip's, not the note's underneath it.
        isClickable = true

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        for (tool in Tool.entries) {
            val button = button(tool)
            buttons[tool] = button
            row.addView(button, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // The same surface as the bar it stands on, but not the same thing.
        addView(View(context).apply { setBackgroundColor(palette.inactive) },
            LayoutParams(LayoutParams.MATCH_PARENT, 1))
        paint()
    }

    /** Lights the commands in force at the caret, and puts the rest back in the strip's ink. */
    fun setActive(active: Set<Tool>) {
        if (active == lit) return
        lit = active
        paint()
    }

    /**
     * One command: the icon set's glyph where it has one, and a label in the same ink where
     * it does not - it has nothing for a heading's size or a numbered list.
     *
     * Never focusable, so a press leaves the caret in the note and the keyboard up. The
     * keypad's lighter tick rather than the shell's: these are pressed in the middle of
     * typing, as often as keys, and see Haptics for why that is a different buzz.
     */
    private fun button(tool: Tool): View {
        val icon = ICONS[tool]
        val view: View = if (icon != null) {
            ImageView(context).apply {
                setImageDrawable(SvgIcon.fromAsset(context, "$ICON_DIR/$icon"))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } else {
            TextView(context).apply {
                text = LABELS.getValue(tool)
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = ResourcesCompat.getFont(context, R.font.segoeui_semibold)
                includeFontPadding = false
            }
        }
        view.isClickable = true
        view.isFocusable = false
        view.contentDescription = DESCRIPTIONS.getValue(tool)
        view.setOnClickListener {
            Haptics.key(it)
            onTool(tool)
        }
        TiltEffect.apply(view)
        return view
    }

    private fun paint() {
        for ((tool, view) in buttons) {
            val ink = if (tool in lit) palette.accent else palette.onChrome
            when (view) {
                is ImageView -> view.imageTintList = ColorStateList.valueOf(ink)
                is TextView -> view.setTextColor(ink)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Modern UI Icons, the set the rest of the shell's marks come from. */
        const val ICON_DIR = "custom_icons_8"

        val ICONS = mapOf(
            Tool.BOLD to "appbar.text.bold.svg",
            Tool.ITALIC to "appbar.text.italic.svg",
            Tool.UNDERLINE to "appbar.text.underline.svg",
            Tool.BULLETS to "appbar.list.svg",
            Tool.LINK to "appbar.link.svg"
        )

        val LABELS = mapOf(
            Tool.H1 to "H1",
            Tool.H2 to "H2",
            Tool.H3 to "H3",
            // The pilcrow: a paragraph, which is what normal text is between the headings.
            Tool.NORMAL to "¶",
            Tool.NUMBERS to "1."
        )

        val DESCRIPTIONS = mapOf(
            Tool.BOLD to "bold",
            Tool.ITALIC to "italic",
            Tool.UNDERLINE to "underline",
            Tool.H1 to "heading 1",
            Tool.H2 to "heading 2",
            Tool.H3 to "heading 3",
            Tool.NORMAL to "normal text",
            Tool.BULLETS to "bulleted list",
            Tool.NUMBERS to "numbered list",
            Tool.LINK to "link"
        )
    }
}
