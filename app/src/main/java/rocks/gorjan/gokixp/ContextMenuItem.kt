package rocks.gorjan.gokixp

/**
 * One line of a context menu.
 *
 * The menus themselves are built where they are raised. There used to be a
 * ContextMenuItems object here holding a builder per surface - the desktop, a desktop
 * icon, the Start menu, the taskbar, the Recycle Bin, My Computer - and not one of them
 * had a caller left once the phone shell became the only shell.
 */
data class ContextMenuItem(
    val title: String,
    val isEnabled: Boolean = true,
    val hasSubmenu: Boolean = false,
    val hasCheckbox: Boolean = false,
    val isChecked: Boolean = false,
    val action: (() -> Unit)? = null,
    val subActionIcon: Int? = null,  // Drawable resource ID
    val subAction: (() -> Unit)? = null
)
