package rocks.gorjan.gokixp

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

class ContextMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var onItemClickListener: ((ContextMenuItem) -> Unit)? = null
    private var onMenuHiddenListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.context_menu_background)
        // Elevation is set in XML (160dp) to be above start menu (150dp)
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    fun showMenu(items: List<ContextMenuItem>, x: Float, y: Float) {
        // Trigger haptic feedback when opening context menu
        Helpers.performHapticFeedback(context)
        
        // Clear existing items
        removeAllViews()
        
        // Add new items
        items.forEach { item ->
            addMenuItem(item)
        }

        // Re-apply the background each time so a Plus! 95 theme's menu colour is picked up
        // (the menu lives outside main_background, so the theme-wide tint walk never reaches it).

        // Position the menu
        positionMenu(x, y)
        
        // Show the menu
        visibility = VISIBLE
        
        // Ensure it renders above everything else
        bringToFront()
    }
    
    fun hideMenu() {
        visibility = GONE
        onMenuHiddenListener?.invoke()
    }

    fun setOnItemClickListener(listener: (ContextMenuItem) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnMenuHiddenListener(listener: () -> Unit) {
        onMenuHiddenListener = listener
    }

    /**
     * Kept for the one caller that still passes a flag. There is a single background now -
     * the drawn Windows 98 3D border it used to build belongs to a theme that ships in the
     * desktop launcher.
     */
    fun setThemeBackground(isWindows98: Boolean) {
        updateBackground()
    }

    /**
     * The face the menu is set in.
     *
     * Asked of MainActivity before, which chose between four themes' fonts. There is one
     * theme here, so the font is simply Segoe - the same one the rest of the phone shell
     * is drawn in.
     */
    private val menuTypeface by lazy {
        ResourcesCompat.getFont(context, R.font.segoeui_regular)
    }

    private fun updateExistingMenuItemFonts(isWindows98: Boolean) {
        // Update fonts for all existing menu items
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is LinearLayout) {
                val textView = child.findViewById<TextView>(R.id.menu_item_text)
                textView?.let { tv -> tv.typeface = menuTypeface }
            }
        }
    }

    private fun addMenuItem(item: ContextMenuItem) {
        when {
            item.title.isEmpty() -> {
                // Add divider - different styles for different themes
                // One divider style. The Windows 98 alternative - a two-pixel rule in
                // #909090 over #FFFFFF - belonged to a theme in the desktop launcher.
                val divider = View(context)
                val dividerParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
                dividerParams.setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
                divider.layoutParams = dividerParams
                divider.setBackgroundColor(context.getColor(R.color.context_menu_divider))
                addView(divider)
            }
            else -> {
                // Add menu item
                val itemView = LayoutInflater.from(context)
                    .inflate(R.layout.context_menu_item, this, false) as LinearLayout
                
                val textView = itemView.findViewById<TextView>(R.id.menu_item_text)
                val arrowView = itemView.findViewById<TextView>(R.id.menu_item_arrow)
                val checkboxView = itemView.findViewById<TextView>(R.id.menu_item_checkbox)
                val separatorView = itemView.findViewById<View>(R.id.menu_item_separator)
                val subActionIconView = itemView.findViewById<ImageView>(R.id.menu_item_sub_action_icon)

                textView.text = item.title

                textView.typeface = menuTypeface
                if (item.isEnabled) {
                    textView.setTextColor(context.getColorStateList(R.color.context_menu_text_selector))
                } else {
                    textView.setTextColor(context.getColor(R.color.context_menu_text_disabled))
                }
                
                // Set background
                itemView.setBackgroundResource(
                    if (item.isEnabled) R.drawable.context_menu_item_enabled
                    else R.drawable.context_menu_item_disabled
                )
                
                // Show/hide checkbox
                checkboxView.visibility = if (item.hasCheckbox) VISIBLE else GONE
                if (item.hasCheckbox) {
                    checkboxView.visibility = VISIBLE
                    if (item.isChecked) {
                        checkboxView.alpha = 1.0f
                        checkboxView.setTextColor(context.getColor(android.R.color.black))
                    } else {
                        checkboxView.alpha = 0.2f
                        checkboxView.setTextColor(context.getColor(android.R.color.black))
                    }
                }
                
                // Show/hide submenu arrow
                arrowView.visibility = if (item.hasSubmenu) VISIBLE else GONE

                // Show/hide sub-action icon and separator
                if (item.subActionIcon != null && item.subAction != null) {
                    separatorView.visibility = VISIBLE
                    subActionIconView.visibility = VISIBLE
                    subActionIconView.setImageResource(item.subActionIcon)

                    // Apply enabled/disabled state to sub-action icon
                    if (item.isEnabled) {
                        subActionIconView.alpha = 1.0f
                        subActionIconView.isEnabled = true
                    } else {
                        subActionIconView.alpha = 0.5f
                        subActionIconView.isEnabled = false
                    }

                    // Set click listener for sub-action icon
                    if (item.isEnabled) {
                        subActionIconView.setOnClickListener {
                            // Play click sound for menu options
                            (context as? MainActivity)?.let { mainActivity ->
                                mainActivity.playClickSound()
                            }
                            item.subAction.invoke()
                            onItemClickListener?.invoke(item)
                            hideMenu()
                        }
                    }
                } else {
                    separatorView.visibility = GONE
                    subActionIconView.visibility = GONE
                }

                // Set click listener
                if (item.isEnabled && item.action != null) {
                    itemView.setOnClickListener {
                        // Play click sound for menu options
                        (context as? MainActivity)?.let { mainActivity ->
                            mainActivity.playClickSound()
                        }
                        item.action.invoke()
                        onItemClickListener?.invoke(item)
                        hideMenu()
                    }
                } else {
                    // Consume clicks on disabled items to prevent them from passing through
                    itemView.setOnClickListener {
                        // Do nothing, just consume the click
                    }
                }

                addView(itemView)
            }
        }
    }
    
    private fun positionMenu(x: Float, y: Float) {
        // Force measure to get actual dimensions
        measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val menuWidth = measuredWidth
        val menuHeight = measuredHeight
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // Smart positioning logic like Windows
        var finalX = x
        var finalY = y

        // Handle horizontal positioning
        if (x + menuWidth + 250 > screenWidth) {
            // Would go off right edge - position to the left of click point
            finalX = (x - menuWidth).coerceAtLeast(0f)
        }

        // Handle vertical positioning
        if (y + menuHeight > screenHeight) {
            // Would go off the bottom edge - position above click point
            finalY = (y - menuHeight).coerceAtLeast(0f)
        }

        // Ensure menu doesn't go off left edge
        if (finalX < 0) {
            finalX = 0f
        }

        // Ensure menu doesn't go off top edge
        if (finalY < 0) {
            finalY = 0f
        }

        // Apply position
        translationX = finalX
        translationY = finalY
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }



    /**
     * One background. This chose between a drawn Windows 98 border and the XP/Vista
     * bitmap; only the bitmap is reachable, since the phone's programs draw in Vista
     * chrome.
     */
    private fun updateBackground() {
        setBackgroundResource(R.drawable.context_menu_background)
    }
}