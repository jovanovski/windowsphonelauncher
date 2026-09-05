package rocks.gorjan.gokixp

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.SoundPool
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.VideoView
import android.text.TextWatcher
import android.text.Editable
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import rocks.gorjan.gokixp.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.isNotEmpty
import kotlin.math.abs
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.SpannableString
import android.util.LruCache
import android.content.ComponentCallbacks2
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.SpannableStringBuilder
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.text.util.Linkify
import java.io.File
import java.io.InputStream
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import rocks.gorjan.gokixp.theme.*
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.view.isEmpty
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity(), AppChangeListener {

    val themeManager by lazy { ThemeManager(this) }

    private lateinit var binding: ActivityMainBinding
    // Was assigned in the desktop's setup, which is gone; there is nothing to wait for
    // before it can exist, so it is simply built here.
    private val handler = Handler(Looper.getMainLooper())
    private var cachedAppList: List<AppInfo>? = null

    /**
     * The menu a program's own window opens on a long press.
     *
     * Nothing to do with the Start screen's - that is the shell's [wp81.WP81ContextMenu].
     * This one belongs to the windows that Metro programs run in, which is why it outlived
     * the desktop that introduced it. Handed to each window in showXDialog.
     */
    private lateinit var contextMenu: ContextMenuView
    var isStartMenuVisible = false

    // Back gesture tracking
    private var isBackGestureInProgress = false
    private var potentialBackGestureStartTime = 0L
    private val BACK_GESTURE_EDGE_THRESHOLD_DP = 5 // Touch within 20dp from edge is potential back gesture
    private val BACK_GESTURE_TIMEOUT_MS = 300L // If no back gesture confirmed within 300ms, allow touch

    // Update checker
    private val updateCheckHandler = Handler(Looper.getMainLooper())
    private var updateCheckRunnable: Runnable? = null
    private val UPDATE_CHECK_INTERVAL = 3600000L // 1 hour in milliseconds
    private var updateDownloadLink: String? = null

    /** The version waiting to be installed, or null. Shown on the Welcome tile. */
    private var updateAvailableVersion: String? = null
    
    // App detection
    private var lastKnownAppCount = 0
    private var appCheckRunnable: Runnable? = null
    private val APP_CHECK_INTERVAL = 30000L // 30 seconds
    private val ICON_REFRESH_DEBOUNCE_MS = 250L // Coalesces bursts of package-change callbacks
    private var isContextMenuVisible = false
    /** Whether the shell has been put up in this activity. See [initializeTheme]. */
    private var shellApplied = false
    private val desktopIcons = mutableListOf<DesktopIcon>()
    private var wallpaperSlideRunnable: Runnable? = null
    private var wallpaperSlidePositionMs = 0L // elapsed within the slide cycle, so it resumes where it stopped
    private lateinit var floatingWindowManager: FloatingWindowManager
    private val customIconMappings = mutableMapOf<String, String>() // packageName -> customIconPath
    private val customNameMappings = mutableMapOf<String, String>() // packageName -> customName

    // Foldable device state
    private var isFoldableUnfolded = false


    // Watches for apps being added, removed or updated while the launcher is running
    private var launcherAppsCallback: LauncherApps.Callback? = null

    /**
     * The LauncherApps the callback above is registered on.
     *
     * Held because it has to be the *same object* to unregister. LauncherApps is a
     * per-Context service and [attributionContext] mints a fresh Context on every call, so
     * asking for the service again in onDestroy handed back a different instance with an
     * empty callback list: the registration was never removed, the framework went on
     * holding the callback, and the callback holds this activity. Every theme switch -
     * which is a recreate - leaked an entire activity that way, with its icon cache, its
     * view tree and its wallpaper, and left another live callback rebuilding the app list
     * and the tiles of a screen nobody is looking at on every package change.
     */
    private var launcherAppsService: LauncherApps? = null

    // Packages whose icons changed, waiting for the coalesced refresh in refreshIconsForPackage
    private val pendingIconRefreshes = mutableSetOf<String>()
    private var iconRefreshRunnable: Runnable? = null

    // System apps configuration
    private val systemAppActions = mutableMapOf<String, (AppInfo?) -> Unit>() // packageName -> action function with optional AppInfo

    // Permission request codes
    private val CALENDAR_PERMISSION_REQUEST_CODE = 1003
    private val AUDIO_PERMISSION_REQUEST_CODE = 200

    // When the wallpaper selection dialog is open, its Browse button sets this so the
    // picked image updates the dialog's live preview instead of jumping to the target dialog.
    private var onWallpaperImagePicked: ((Uri) -> Unit)? = null

    // Image picker launcher for wallpaper selection
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            // What the pick was *for* is recorded in SharedPreferences rather than held in
            // a lambda. The system picker is another activity, and this one can be
            // recreated behind it; an in-memory handler is then gone by the time the result
            // arrives, and the pick silently falls through to the wallpaper flow - which is
            // how choosing a tile icon ended up asking where to apply a wallpaper.
            when (val target = consumePendingImagePick()) {
                null -> {
                    val previewHandler = onWallpaperImagePicked
                    if (previewHandler != null) {
                        onWallpaperImagePicked = null
                        previewHandler(selectedUri)
                    } else {
                        handleSelectedImage(selectedUri)
                    }
                }
                PICK_TARGET_WP81_BACKGROUND -> applyPickedWP81Background(selectedUri)
                else -> {
                    if (target.startsWith(PICK_TARGET_WP81_ICON_PREFIX)) {
                        applyPickedWP81Icon(
                            target.removePrefix(PICK_TARGET_WP81_ICON_PREFIX), selectedUri)
                    } else {
                        handleSelectedImage(selectedUri)
                    }
                }
            }
        }
    }

    /**
     * The system's own "make this your browser?" prompt, and what it answered.
     *
     * Registered rather than fired and forgotten so the settings that offered it can say
     * where things stand the moment the user comes back from it - see [refreshDefaultBrowserUi].
     */
    private val defaultBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshDefaultBrowserUi()
    }

    /**
     * The phone's Usage access screen, and what it was left at.
     *
     * It reports no result of its own - it is a list of switches, not a prompt - so the
     * answer is read back off the phone when the user returns. See [hasUsageAccess].
     */
    private val usageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        wp81Shell?.settingsPage?.setLastAppAccess(hasUsageAccess())
    }

    /** Set by whichever settings surface is open, so it can be told the answer. */
    private var refreshDefaultBrowser: (() -> Unit)? = null

    private fun refreshDefaultBrowserUi() {
        refreshDefaultBrowser?.invoke()
    }




    /**
     * The phone's Notepad, when the shell is wearing Windows Phone.
     *
     * Its own instance beside the desktop one rather than an interface over the two: they
     * share the notes and nothing else, and only one of them can be open at a time - the
     * theme decides which. Both are offered the picture, and whichever is up takes it.
     */
    private var metroNotepadAppInstance:
        rocks.gorjan.gokixp.apps.notepad.MetroNotepadApp? = null

    /**
     * The two games, when the shell is wearing Windows Phone.
     *
     * Held for the same reason the notepad is: the back key has to reach whatever the game
     * has open over itself before the window treats it as a way out, and the clock in each
     * of them has to be stopped when the window closes.
     */
    /**
     * Files, when the shell is wearing Windows Phone.
     *
     * Held for the reason the rest of them are: the back key has to reach the folder it is
     * standing in, the prompt over it and the select mode it may be in, before the window
     * treats the key as a way out of the app.
     */
    private var metroFilesAppInstance:
        rocks.gorjan.gokixp.apps.files.MetroFilesApp? = null

    private var metroMinesweeperInstance:
        rocks.gorjan.gokixp.apps.minesweeper.MetroMinesweeperApp? = null
    private var metroSolitaireInstance:
        rocks.gorjan.gokixp.apps.solitare.MetroSolitaireApp? = null

    private val notepadGalleryPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        metroNotepadAppInstance?.onImageSelected(uri)
    }

    /**
     * The gallery, for a contact's picture.
     *
     * Its own launcher rather than a share of the notepad's: a launcher can only be
     * registered before the activity is started, so they cost nothing to keep apart, and
     * routing two apps' pictures through one callback is how a note ends up with somebody's
     * face in it.
     */
    private val peoplePhotoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            peopleAppInstance?.onPhotoPicked(uri)
        }

    /**
     * The system's own question about which app is the phone.
     *
     * Whatever the answer, the app is told to look again: the offer to take the role is on
     * the history page, and it should be gone by the time the user is back looking at it.
     */
    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        ensureCallPermissions()
        peopleAppInstance?.refresh()
    }

    /**
     * The permission the call screen wants but can do without.
     *
     * Asked for here rather than on the way into a call, because here is where it makes
     * sense of itself: the user has just said this app should handle their calls, and one
     * of the things that buys them is their headset's own name in the list of outputs.
     * Asking mid-call - the only other moment it matters - would put a system dialog over
     * a ringing telephone.
     *
     * Refused, nothing breaks: the list says "bluetooth" and the route still works. Which
     * is why it is asked once, with the role, and never again. See CallCentre.bluetoothName.
     */
    private fun ensureCallPermissions() {
        // Before Android 12 the old bluetooth permission is granted at install and there
        // is nothing to ask for.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) != true) return
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
            PEOPLE_PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Asks to become the default phone app.
     *
     * Only ever from a tap in People - never on a launch, never on a timer. Taking this
     * role means every call on the device comes through this app's screen, including ones
     * placed from somewhere else entirely, and that is not a thing to ask for on the way
     * past. The system puts its own dialog in front of the request, which is the consent
     * that matters; this only decides when the question gets asked.
     */
    private fun requestDialerRole() {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles == null || !roles.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
            showNotification("People", "This device has no phone app to be")
            return
        }
        if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) {
            ensureCallPermissions()
            peopleAppInstance?.refresh()
            return
        }
        try {
            dialerRoleLauncher.launch(
                roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not ask to be the phone", e)
            showNotification("People", "This phone would not offer the choice")
        }
    }

    /**
     * The system's own question about which app handles messages.
     *
     * Whatever the answer, two things follow: the permissions that only mean something
     * once the role is held are asked for, and the app looks again - the offer to take the
     * role is on the messages page, and it should be gone by the time the user is back
     * looking at it.
     */
    private val smsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        ensureMessagePermissions()
        peopleAppInstance?.refresh()
    }

    /**
     * Asks to become the phone's messaging app.
     *
     * Only ever from a tap in People. Taking this role is a larger thing than taking the
     * phone one, because it moves work rather than only moving a screen: from that moment
     * every text message on the device is delivered to this app alone, and one it fails to
     * write down or announce is a message nobody ever sees. It also ends multimedia
     * messages, which this app does not do - see MmsDeliverReceiver. The system's own
     * dialog is the consent that matters; this only decides when it gets asked.
     */
    private fun requestSmsRole() {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles == null || !roles.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
            showNotification("People", "This device has no messaging app to be")
            return
        }
        if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
            ensureMessagePermissions()
            peopleAppInstance?.refresh()
            return
        }
        try {
            smsRoleLauncher.launch(
                roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS))
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not ask to be the messaging app", e)
            showNotification("People", "This phone would not offer the choice")
        }
    }

    /**
     * The permissions a messaging app cannot work without.
     *
     * Taking the role normally grants these outright, the way the phone role grants
     * READ_PHONE_STATE - but a phone that does not is a phone where messages arrive at an
     * app that is not allowed to be told about them, which fails silently and looks exactly
     * like no messages arriving. So they are checked afterwards and asked for if missing.
     */
    private fun ensureMessagePermissions() {
        val wanted = arrayOf(
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.RECEIVE_MMS,
            android.Manifest.permission.RECEIVE_WAP_PUSH,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isEmpty()) return
        androidx.core.app.ActivityCompat.requestPermissions(
            this, wanted.toTypedArray(), PEOPLE_PERMISSION_REQUEST_CODE)
    }

    // ========== The phone and messaging roles, and the one theme that can hold them ==========








    private val notepadCameraPickerLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success) {
            // Camera captured successfully, URI is already set
            metroNotepadAppInstance?.onImageSelected(pendingCameraUri)
        }
        pendingCameraUri = null
    }

    private var pendingCameraUri: Uri? = null





    // Sound system
    private lateinit var soundPool: SoundPool
    private val soundIds = mutableMapOf<Int, Int>() // Maps resource ID to sound ID
    private var chargingReceiver: BroadcastReceiver? = null

    // Easter egg sounds (sorted by filename)
    private val eggSounds = listOf(
        R.raw.developers1,
        R.raw.developers2,
        R.raw.ilovethiscompany
    )


    // Permission error update functions for wallpaper dialog
    private var updateEmailPermissionError: (() -> Unit)? = null
    private var updateNotificationDotsPermissionError: (() -> Unit)? = null

    /**
     * Attribution tags are an API 30 diagnostics feature. On Android 10 there is no
     * equivalent, so fall back to the plain context.
     */
    private fun Context.attributionContext(tag: String): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) createAttributionContext(tag) else this

    
    companion object {
        const val PREFS_NAME = "taskbar_widget_prefs"  // Public constant for shared preferences name
        private const val KEY_DESKTOP_ICONS = "desktop_icons"

        /** How often the Windows Phone 8.1 Start screen refreshes and flips its live tiles. */
        private const val WP81_LIVE_TILE_INTERVAL_MS = 12_000L

        /**
         * How far ahead the clock and calendar tiles look for an alarm.
         *
         * A day: the mark says "there is one coming" about the stretch those two tiles
         * are already describing, and a week's notice of Tuesday's alarm would leave it
         * permanently lit for anyone who repeats one.
         */
        private const val WP81_ALARM_HORIZON_MS = 24L * 60 * 60 * 1000

        // Stable ids for the built-in live widgets, so they never collide with a real
        // package name or a desktop icon id.
        /**
         * Whether Start has played its entrance in this process.
         *
         * Deliberately not per-activity: an activity recreated behind a window the user is
         * already looking at is not an arrival, and treating it as one is what made coming
         * home flash.
         */
        private var wp81EntrancePlayed = false

        /**
         * Whether a windowed program was covering the shell last time the count changed.
         *
         * Per-process for the same reason: what matters is whether the program the user
         * just closed was there, not which activity was underneath it.
         */
        private var wp81ProgramOnScreen = false

        /** The icons offered when a Windows Phone tile is being given a new one. */
        private const val WP81_ICON_FOLDER = "custom_icons_8"

        /** Inside this, an appointment is said as a countdown. See wp81EventWhen. */
        private const val RELATIVE_EVENT_MINUTES = 120L

        private const val WP81_WIDGET_CLOCK = "wp81.widget.clock"
        private const val WP81_WIDGET_CALENDAR = "wp81.widget.calendar"
        private const val WP81_WIDGET_NEWS = "wp81.widget.news"
        private const val WP81_WIDGET_PHOTOS = "wp81.widget.photos"
        private const val WP81_WIDGET_PEOPLE = "wp81.widget.people"
        private const val WP81_WIDGET_AQI = "wp81.widget.aqi"
        private const val WP81_WIDGET_WEATHER = "wp81.widget.weather"
        private const val WP81_WIDGET_SETTINGS = "wp81.widget.settings"
        private const val KEY_WP81_BUILTIN_TILES = "wp81_builtin_tiles"

        /** Set once the phone's custom icons have been moved off the desktop themes' keys. */
        private const val KEY_WP81_ICONS_SPLIT = "wp81_custom_icons_split"

        /** The same, for the phone on its side. See wp81Landscape. */
        private const val KEY_WP81_BUILTIN_TILES_LANDSCAPE = "wp81_builtin_tiles_landscape"

        /** What the in-flight system image pick is for; see imagePickerLauncher. */
        private const val KEY_PENDING_IMAGE_PICK = "pending_image_pick"
        private const val PICK_TARGET_WP81_BACKGROUND = "wp81_background"
        private const val PICK_TARGET_WP81_ICON_PREFIX = "wp81_icon:"

        /** How long an in-app notification stays on screen. */
        private const val NOTIFICATION_DURATION_MS = 7000L

        /**
         * How high the phone's toast and its task switcher sit, in dp.
         *
         * Both are above floating_windows_container's 50dp, which is what puts them over
         * an open program rather than behind it, and the toast is above the switcher: a
         * program announcing something while the switcher is up is still worth reading.
         * See liftWP81Overlays.
         */
        private const val RECENTS_ELEVATION_DP = 55f
        private const val TOAST_ELEVATION_DP = 60f

        /** Icons handed to the picker per batch, so the grid fills as it decodes. */
        private const val WP81_ICON_BATCH = 40

        /** Slider movement smaller than this reuses the cached blur rather than re-scaling. */
        private const val BLUR_QUANTISATION = 0.02f
        private const val KEY_PINNED_APPS = "pinned_apps"
        private const val KEY_HIDDEN_APPS = "hidden_apps"

        /**
         * Programs that were part of the shell once and are not any more.
         *
         * Retiring one is a matter of adding its package here; see
         * [purgeRetiredSystemApps], which takes it back out of whatever the user had
         * done with it.
         */
        private val RETIRED_SYSTEM_APPS = setOf("system.msn")

        /**
         * The repository this launcher updates itself from, and shows release notes for.
         *
         * Its own, not the desktop launcher's. Left pointing at windowslauncher, every
         * update check here would offer the desktop launcher's APK - which is a different
         * app with a different application id, so it would install alongside rather than
         * over, and the person would end up with two launchers and no update.
         */
        const val GITHUB_REPO = "jovanovski/windowsphonelauncher"

        /**
         * Programs that belong to the phone shell and to nothing else.
         *
         * Each is built out of WP8.1's own furniture and has a desktop counterpart that
         * already does the job - Winamp and Windows Media Player play music, Internet
         * Explorer reads the web - so on Windows 98, XP or Vista they would be
         * anachronisms standing next to the real thing. They are left out of the app list
         * there, and an icon for one is not drawn on the desktop either: the icon itself
         * survives, because the tile has to be there again the moment the phone shell is.
         *
         * The counterpart of the rule that keeps the Recycle Bin and My Computer off Start.
         */
        private val WINDOWS_PHONE_ONLY_APPS = setOf(
            "system.zune", "system.news", "system.welcome", "system.calculator",
            "system.people", "system.alarms", "system.weather", "system.files"
        )

        /** Whether this program exists only under the Windows Phone 8.1 shell. */
        fun isWindowsPhoneOnlyApp(packageName: String): Boolean =
            packageName in WINDOWS_PHONE_ONLY_APPS

        /**
         * The other direction: programs kept off the phone shell.
         *
         * The mirror of the rule above, and so far it holds one thing. The Phone Dialer is
         * a Windows 98 window with a keypad drawn on it, which is exactly right on a
         * desktop and an anachronism under Windows Phone - where People is the phone app,
         * keypad and all. Both survive; which of them the app list offers depends on which
         * shell is asking.
         */
        private val DESKTOP_ONLY_APPS = setOf("system.dialer")

        fun isDesktopOnlyApp(packageName: String): Boolean =
            packageName in DESKTOP_ONLY_APPS

        /** Which of [RETIRED_SYSTEM_APPS] have already been swept out of the user's arrangement. */
        private const val KEY_RETIRED_APPS_PURGED = "retired_system_apps_purged"
        private const val KEY_SOUND_MUTED = "sound_muted"
        private const val KEY_PLAY_EMAIL_SOUND = "play_email_sound"
        private const val KEY_SHOW_NOTIFICATION_DOTS = "show_notification_dots"
        private const val KEY_CLOCK_24_HOUR = "clock_24_hour"
        private const val KEY_KNOWN_APPS = "known_apps"
        private const val KEY_ROVER_VISIBLE = "rover_visible"
        private const val KEY_RECYCLE_BIN_VISIBLE = "recycle_bin_visible"
        private const val KEY_MY_COMPUTER_VISIBLE = "my_computer_visible"
        private const val KEY_SHORTCUT_ARROW_VISIBLE = "shortcut_arrow_visible"
        private const val KEY_WALLPAPER_XP_PATH = "wallpaper_xp_path"
        private const val KEY_WALLPAPER_XP_URI = "wallpaper_xp_uri"
        private const val KEY_WALLPAPER_CLASSIC_PATH = "wallpaper_classic_path"
        private const val KEY_WALLPAPER_CLASSIC_URI = "wallpaper_classic_uri"
        private const val KEY_WALLPAPER_VISTA_PATH = "wallpaper_vista_path"
        private const val KEY_WALLPAPER_VISTA_URI = "wallpaper_vista_uri"
        private const val KEY_WALLPAPER_XP_FOCUS_X = "wallpaper_xp_focus_x"
        private const val KEY_WALLPAPER_CLASSIC_FOCUS_X = "wallpaper_classic_focus_x"
        private const val KEY_WALLPAPER_VISTA_FOCUS_X = "wallpaper_vista_focus_x"
        private const val KEY_SLIDE_WALLPAPER_ENABLED = "slide_wallpaper_enabled"
        private const val KEY_SLIDE_WALLPAPER_DURATION = "slide_wallpaper_duration" // whole 0->max->0 cycle, in seconds
        private const val DEFAULT_SLIDE_WALLPAPER_DURATION = 10
        private const val KEY_CURSOR_VISIBLE = "cursor_visible"
        private const val KEY_ICON_TEXT_BACKGROUND_VISIBLE = "icon_text_background_visible"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_NAMES = "custom_names"
        private const val KEY_WEATHER_DATA = "weather_data"
        private const val KEY_WEATHER_TIMESTAMP = "weather_timestamp"
        private const val KEY_WEATHER_UNIT = "weather_unit"
        private const val KEY_AQI_DATA = "aqi_data"
        private const val KEY_AQI_TIMESTAMP = "aqi_timestamp"
        private const val KEY_QUICK_GLANCE_VISIBLE = "quick_glance_visible"
        private const val KEY_AGENT_X = "agent_x"
        private const val KEY_AGENT_Y = "agent_y"
        private const val KEY_CURRENT_AGENT = "current_agent_id"
        private const val KEY_WIDGET_X = "widget_x"
        private const val KEY_WIDGET_Y = "widget_y"
        private const val KEY_SHOW_CALENDAR_EVENTS = "show_calendar_events"
        private const val KEY_IE_HOMEPAGE = "ie_homepage"
        private const val KEY_SWIPE_RIGHT_APP = "swipe_right_app"
        private const val KEY_WEATHER_APP = "weather_app"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_START_BANNER_98 = "start_banner_98"
        private const val KEY_GESTURE_BAR_VISIBLE = "gesture_bar_visible"
        private const val KEY_CHRISTMAS_LIGHTS_VISIBLE = "christmas_lights_visible"
        private const val KEY_CHRISTMAS_LIGHTS_MARGIN = "christmas_lights_margin"
        private const val KEY_TASKBAR_HEIGHT_OFFSET = "taskbar_height_offset"
        private const val KEY_SHOWN_WELCOME_FOR_VERSION = "shown_welcome_for_version"
        private const val KEY_SYSTEM_TRAY_VISIBLE = "system_tray_visible"
        private const val KEY_SELECTED_SCREENSAVER = "selected_screensaver"
        private const val KEY_SCREENSAVER_TIMEOUT = "screensaver_timeout"
        private const val KEY_LAST_GOOGLE_DRIVE_SYNC = "last_google_drive_sync"
        private const val KEY_WINDOW_STATES = "window_states"
        private const val KEY_TAP_TO_HIDE_ICONS = "tap_to_hide_icons"
        private const val KEY_OPEN_URLS_IN_IE = "open_urls_in_ie"
        private const val KEY_SHOW_AQI = "show_aqi"

        /**
         * The last app the launcher sent the user to, for back-back on Start.
         *
         * Kept in preferences rather than in a field because a launcher is killed
         * between visits more often than any other app on the phone, and the app you
         * were in five minutes ago is exactly the one you want when you come back.
         */
        private const val KEY_LAST_LAUNCHED_APP = "last_launched_app"

        /**
         * How long the second press of a back-back on Start may lag the first.
         *
         * Longer than the framework's double-tap timeout: these are two deliberate
         * presses of a key at the bottom edge of the screen, not two taps of a finger
         * that never left the glass.
         */
        private const val WP81_BACK_AGAIN_MS = 400L

        /** Set once the offer of usage access has been made, so it is made only once. */
        private const val KEY_ASKED_USAGE_ACCESS = "asked_usage_access"

        // How far back the phone's app history is read went with the reading of it, into
        // rocks.gorjan.gokixp.wp81.RecentAppsStore.

        private const val AIRCARE_URL = "https://getaircare.com"

        // Screensaver types
        private const val SCREENSAVER_NONE = 0
        private const val SCREENSAVER_3D_PIPES = 1
        private const val SCREENSAVER_UNDERWATER = 2
        private const val DEFAULT_SCREENSAVER_TIMEOUT = 30 // Default 30 seconds
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

        /** Asked for by the Photos tile, the first time it is tapped. */
        private const val PHOTOS_PERMISSION_REQUEST_CODE = 1005

        /** And by the People tile, on the same terms. */
        private const val CONTACTS_PERMISSION_REQUEST_CODE = 1006

        /**
         * Anything the People app asks for from inside itself.
         *
         * One code for the lot - writing contacts, the call log, placing a call - because
         * the answer is always the same: read everything again and let the page the user
         * is on show what it can now do. Which of them was granted is a question the app
         * asks the system directly, at the moment it matters.
         */
        private const val PEOPLE_PERMISSION_REQUEST_CODE = 1007

        /** This app's own missed-call notification, asking for the page its call is on. */
        const val ACTION_SHOW_CALL_HISTORY = "rocks.gorjan.gokixp.action.SHOW_CALL_HISTORY"

        /** The messages section, asked for by name. */
        const val ACTION_SHOW_MESSAGES = "rocks.gorjan.gokixp.action.SHOW_MESSAGES"

        /** And one conversation on it - what a message notification opens. */
        const val ACTION_SHOW_MESSAGE_THREAD =
            "rocks.gorjan.gokixp.action.SHOW_MESSAGE_THREAD"

        /** Who the conversation is with, and what to put in the box when it opens. */
        const val EXTRA_MESSAGE_ADDRESS = "address"
        const val EXTRA_MESSAGE_DRAFT = "draft"

        /**
         * What an address that means "a message" looks like.
         *
         * All four, though this app only sends the first two: an `mmsto:` link tapped on a
         * phone where People is the messaging app has nowhere else to go, and opening the
         * conversation with that person is a better answer to it than nothing happening.
         */
        private val MESSAGE_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

        /** What the Photos tile opens. */
        private const val WP81_PHOTOS_PACKAGE = "com.google.android.apps.photos"

        /**
         * How long the camera roll is trusted before it is read again.
         *
         * Long enough that returning to Start does not re-query MediaStore every time,
         * short enough that a picture taken a few minutes ago turns up on the tile.
         */
        private const val WP81_PHOTOS_MAX_AGE_MS = 5 * 60 * 1000L

        /**
         * How long the address book is trusted before it is read again.
         *
         * Longer than the camera roll's: people are added to a phone far less often than
         * pictures are taken, and the tile only wants to know who is in there.
         */
        private const val WP81_PEOPLE_MAX_AGE_MS = 30 * 60 * 1000L

        // System app package name prefix
        private const val SYSTEM_APP_PREFIX = "system."

        // Icons the user imported from their device live here, under filesDir.
        // Icon mappings store them as "imported_icons/<file>.png" so they're told apart from asset icons.
        private const val IMPORTED_ICONS_DIR = "imported_icons"

        // Standard size icons are rendered at
        private const val ICON_SIZE_PX = 288

        /**
         * Longest edge a bundled wallpaper is decoded to when it is only being previewed.
         *
         * The largest thing that shows one is the XP picker's 138x102dp monitor; the phone's
         * strip is smaller still. See [loadWallpaperPreview].
         */
        private const val WALLPAPER_PREVIEW_PX = 512

        private var instance: MainActivity? = null

        /**
         * Every app icon the launcher has squared off, for the life of the process.
         *
         * A process-wide cache rather than a field on the activity, because a theme switch
         * is a recreate: one per activity meant the new one started with nothing and
         * decoded, scaled and squared every installed app again - a second or so of work
         * on the way into a theme, and a second full set of icons in memory for as long as
         * the outgoing activity was still being collected. The contents do not depend on
         * the theme: system programs take their artwork straight from the theme without
         * coming through here, and a hand-picked icon is keyed by the file it came from.
         *
         * An eighth of the heap, and the memory-pressure callbacks still trim it.
         */
        private val iconBitmapCache: LruCache<String, Bitmap> by lazy {
            val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt() // KB
            val cacheSize = maxMemory / 8 // Use 1/8th of available memory
            Log.d("MainActivity", "Initializing icon cache with size: ${cacheSize}KB (max memory: ${maxMemory}KB)")

            object : LruCache<String, Bitmap>(cacheSize) {
                override fun sizeOf(key: String, bitmap: Bitmap): Int {
                    return bitmap.byteCount / 1024 // Size in KB
                }
            }
        }

        fun getInstance(): MainActivity? = instance

        // Check if a package name is a system app
        fun isSystemApp(packageName: String): Boolean {
            return packageName.startsWith(SYSTEM_APP_PREFIX)
        }
        
        // Get user name from SharedPreferences (accessible to other parts of the app)
        fun getUserName(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_USER_NAME, "User") ?: "User"
        }

        // Safe getter for integer preferences that handles type mismatches from corrupted imports
        private fun android.content.SharedPreferences.safeGetInt(key: String, defaultValue: Int): Int {
            return try {
                getInt(key, defaultValue)
            } catch (e: ClassCastException) {
                // Handle corrupted data from incorrect import
                Log.w("MainActivity", "Corrupted int preference for key: $key, resetting to default", e)
                edit().remove(key).apply()
                defaultValue
            }
        }

        // Grid constants (deprecated - will be calculated dynamically)
        @Deprecated("Use calculateGridRows() instead")
        private const val GRID_ROWS = 8
        @Deprecated("Use calculateGridColumns() instead")
        private const val GRID_COLUMNS = 5

        // Orientation enum
        enum class ScreenOrientation {
            PORTRAIT,
            LANDSCAPE
        }

        // Start banner cycling order: 98 -> me -> 2000 -> 95 -> back to 98
        private val START_BANNER_CYCLE = arrayOf(
            "start_banner_98",
            "start_banner_me",
            "start_banner_2000",
            "start_banner_95"
        )

        // Map banner names to resource IDs
        private val BANNER_RESOURCE_MAP = mapOf(
            "start_banner_98" to R.drawable.start_banner_98,
            "start_banner_me" to R.drawable.start_banner_me,
            "start_banner_2000" to R.drawable.start_banner_2000,
            "start_banner_95" to R.drawable.start_banner_95
        )
    }



    // There is no theme-change notification, because there is no theme change. The
    // desktop launcher rebuilt itself in place when the user picked another shell and had
    // to tell every view about it; this launcher has one shell for its whole life.


    /**
     * Walks a view tree and swaps any ColorDrawable / GradientDrawable whose color is the
     * stock Classic gray (#d3cec7) with `newColor`. Intended to tint taskbar, start menu,
     * dialog, and system tray gray surfaces without editing each XML.
     */
    fun applyPlus95MenuColor(root: View, newColor: Int) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            tintClassicGrayBackground(v, newColor)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            }
        }
    }

    /**
     * Colours that count as a repaintable "Classic gray" surface: the stock gray plus every
     * Plus! menu colour. Matching all of them — not just the stock gray — lets us re-tint
     * surfaces that a previous Plus! theme already painted. Otherwise switching between Plus!
     * themes, or reverting to Default, left stale colours behind until an app restart
     * re-inflated the layouts fresh (the reported "needs a restart to take effect" bug).
     */
    private val plus95RepaintableColors: Set<Int> by lazy {
        ThemeManager.PLUS95_THEMES.mapTo(mutableSetOf(ThemeManager.CLASSIC_GRAY)) { it.menuColor }
    }

    private fun tintClassicGrayBackground(view: View, newColor: Int) {
        val bg = view.background ?: return
        when (bg) {
            is android.graphics.drawable.ColorDrawable -> {
                if (bg.color in plus95RepaintableColors) {
                    view.setBackgroundColor(newColor)
                }
            }
            is android.graphics.drawable.GradientDrawable -> {
                if (gradientColorIsRepaintable(bg)) {
                    bg.mutate()
                    (bg as android.graphics.drawable.GradientDrawable).setColor(newColor)
                }
            }
            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until bg.numberOfLayers) {
                    val layer = bg.getDrawable(i)
                    if (layer is android.graphics.drawable.GradientDrawable && gradientColorIsRepaintable(layer)) {
                        layer.mutate()
                        (layer as android.graphics.drawable.GradientDrawable).setColor(newColor)
                    }
                }
            }
        }
    }

    private fun gradientColorIsRepaintable(drawable: android.graphics.drawable.GradientDrawable): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        val stateList = drawable.color ?: return false
        return stateList.defaultColor in plus95RepaintableColors
    }

    // ========== Theme-Specific Resource Helper Methods ==========

    /**
     * Gets the wallpaper storage keys for the current theme.
     * Returns Pair(path_key, uri_key)
     */
    private fun getCurrentThemeWallpaperKeysTypeSafe(): Pair<String, String> =
        Pair(KEY_WALLPAPER_VISTA_PATH, KEY_WALLPAPER_VISTA_URI)

    /**
     * Gets the wallpaper X focus storage key for the current theme.
     */
    private fun getCurrentThemeWallpaperFocusXKey(): String = KEY_WALLPAPER_VISTA_FOCUS_X








    override fun onCreate(savedInstanceState: Bundle?) {
        // Set theme before calling super.onCreate()
        setTheme(themeManager.getThemeStyleRes())

        super.onCreate(savedInstanceState)
        instance = this

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get SharedPreferences for onCreate initialization
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize floating window manager with container
        val floatingWindowsContainer = findViewById<android.widget.FrameLayout>(R.id.floating_windows_container)
        floatingWindowManager = FloatingWindowManager(this, floatingWindowsContainer)

        // The window context menu. Was picked up by the desktop's setup, which no longer
        // runs; the view is still in the layout and Metro programs are still handed it.
        contextMenu = findViewById(R.id.context_menu)

        // Setup foldable device detection
        setupFoldableDeviceDetection()

        // Enable edge-to-edge display after content view is set
        enableEdgeToEdge()

        // Initialize sound system
        initializeSoundPool()

        // Setup charging detection
        setupChargingDetection()

        // Initialize system apps
        initializeSystemApps()

        // Start notification monitoring (after handler is initialized)
        startNotificationMonitoring()
        
        // Set up modern back press handling
        setupBackPressHandling()

        // Migrate custom mappings from old preferences file if needed
        migrateCustomMappingsIfNeeded()

        // Give the phone shell back the icons it filed under a desktop theme's key
        migrateWP81CustomIconsIfNeeded()
        // A call notification left over from a process that was killed while a call was up.
        rocks.gorjan.gokixp.apps.people.GokiInCallService.clearIfIdle(this)

        // And the alarms, put back. AlarmBootReceiver does this after a restart, but a
        // launcher that has just been reinstalled, restored from a backup or force-stopped
        // has no reason to have been told about one - and this is the first moment
        // anything of this app's is running to notice.
        rocks.gorjan.gokixp.apps.alarms.AlarmScheduler.sync(this)
        rocks.gorjan.gokixp.apps.alarms.TaskScheduler.sync(this)
        rocks.gorjan.gokixp.apps.alarms.Countdown.resync(this)

        // Take programs that have since left the shell out of the user's arrangement,
        // before anything reads that arrangement back in
        purgeRetiredSystemApps()

        // Load custom icon mappings first so they're available when loading desktop icons
        loadCustomIconMappings()

        // Load saved desktop icons (now with custom mappings available)
        loadDesktopIcons()

        // Load custom name mappings
        loadCustomNameMappings()
        
        // Initialize theme after wallpaper and UI setup
        initializeTheme()

        // Check if this is a theme change from SharedPreferences (survives process death)
        val isThemeChangingFromPrefs = prefs.getBoolean("theme_changing", false)

        // Only play startup sound if this is a fresh app launch or a theme change
        if (isThemeChangingFromPrefs) {
            Handler(Looper.getMainLooper()).postDelayed({
                playStartupSound()
                // Clear the theme changing flag from SharedPreferences
                prefs.edit { putBoolean("theme_changing", false) }
            }, 1000)
        }

        // Inset the whole launcher up to the system navigation bar (e.g. 3-button navigation),
        // so the desktop fills up to the nav bar instead of drawing under it.
        setupNavigationBarInsets()

        // Request notification permission on first launch (Android 13+)
        requestNotificationPermissionIfNeeded()
        
        // Set up app install/uninstall listener
        AppInstallReceiver.setListener(this)

        // Set up the LauncherApps callback that also catches in-place app updates
        registerLauncherAppsCallback()
        
        // Handle pending app installation/removal from broadcast receiver
        handlePendingPackageAction()

        // Handle a URL shared into the launcher on cold start, and one it was sent to open
        // as the phone's browser.
        handleSharedUrlIntent(intent)
        handleViewUrlIntent(intent)
        handleDialIntent(intent)
        handleCallHistoryIntent(intent)
        handleMessageIntent(intent)
        handleAlarmsIntent(intent)
        handleWeatherIntent(intent)

        // Initialize app detection
        initializeAppDetection()

        // Start update checker (checks immediately and then every hour)
        startUpdateChecker()

        // Show welcome screen if this is the first launch for this version
        Handler(Looper.getMainLooper()).postDelayed({
            showWelcomeScreenIfNeeded()

        }, 1000) // Delay to ensure UI is fully loaded
    }
    








    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
            
        soundPool = SoundPool.Builder()
            .setMaxStreams(5) // Maximum concurrent sounds
            .setAudioAttributes(audioAttributes)
            .build()
        
        // Add load completion listener to prevent blocking UI
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status != 0) {
                Log.w("MainActivity", "Sound failed to load with status: $status")
            }
        }
        
        // Create attributed context for audio loading
        val audioContext =
            attributionContext("system")

        // Preload all sounds
        soundIds[R.raw.startup] = soundPool.load(audioContext, R.raw.startup, 1)
        soundIds[R.raw.startup_98] = soundPool.load(audioContext, R.raw.startup_98, 1)
        soundIds[R.raw.startup_95] = soundPool.load(audioContext, R.raw.startup_95, 1)
        soundIds[R.raw.startup_2000] = soundPool.load(audioContext, R.raw.startup_2000, 1)
        soundIds[R.raw.startup_vista] = soundPool.load(audioContext, R.raw.startup_vista, 1)
        soundIds[R.raw.startup_8] = soundPool.load(audioContext, R.raw.startup_8, 1)
        soundIds[R.raw.shutdown] = soundPool.load(audioContext, R.raw.shutdown, 1)
        soundIds[R.raw.shutdown_98] = soundPool.load(audioContext, R.raw.shutdown_98, 1)
        soundIds[R.raw.shutdown_2000] = soundPool.load(audioContext, R.raw.shutdown_2000, 1)
        soundIds[R.raw.shutdown_vista] = soundPool.load(audioContext, R.raw.shutdown_vista, 1)
        soundIds[R.raw.click] = soundPool.load(audioContext, R.raw.click, 1)
        soundIds[R.raw.click_vista] = soundPool.load(audioContext, R.raw.click_vista, 1)
        soundIds[R.raw.recycle] = soundPool.load(audioContext, R.raw.recycle, 1)
        soundIds[R.raw.ding] = soundPool.load(audioContext, R.raw.ding, 1)
        soundIds[R.raw.ding_vista] = soundPool.load(audioContext, R.raw.ding_vista, 1)
        soundIds[R.raw.bubble] = soundPool.load(audioContext, R.raw.bubble, 1)
        // The phone's alert. Asked for by showNotification whenever the shell is up, and
        // never loaded, so playSound had nothing to play and every WP8.1 toast was silent.
        soundIds[R.raw.bubble_8] = soundPool.load(audioContext, R.raw.bubble_8, 1)
        soundIds[R.raw.charge_on] = soundPool.load(audioContext, R.raw.charge_on, 1)
        soundIds[R.raw.charge_on_vista] = soundPool.load(audioContext, R.raw.charge_on_vista, 1)
        soundIds[R.raw.charge_on_8] = soundPool.load(audioContext, R.raw.charge_on_8, 1)
        soundIds[R.raw.charge_off] = soundPool.load(audioContext, R.raw.charge_off, 1)
        soundIds[R.raw.charge_off_vista] = soundPool.load(audioContext, R.raw.charge_off_vista, 1)
        soundIds[R.raw.charge_off_8] = soundPool.load(audioContext, R.raw.charge_off_8, 1)
        soundIds[R.raw.num_1] = soundPool.load(audioContext, R.raw.num_1, 1)
        soundIds[R.raw.num_2] = soundPool.load(audioContext, R.raw.num_2, 1)
        soundIds[R.raw.num_3] = soundPool.load(audioContext, R.raw.num_3, 1)
        soundIds[R.raw.num_4] = soundPool.load(audioContext, R.raw.num_4, 1)
        soundIds[R.raw.num_5] = soundPool.load(audioContext, R.raw.num_5, 1)
        soundIds[R.raw.num_6] = soundPool.load(audioContext, R.raw.num_6, 1)
        soundIds[R.raw.num_7] = soundPool.load(audioContext, R.raw.num_7, 1)
        soundIds[R.raw.num_8] = soundPool.load(audioContext, R.raw.num_8, 1)
        soundIds[R.raw.num_9] = soundPool.load(audioContext, R.raw.num_9, 1)
        soundIds[R.raw.num_other] = soundPool.load(audioContext, R.raw.num_other, 1)
        soundIds[R.raw.youve_got_mail] = soundPool.load(audioContext, R.raw.youve_got_mail, 1)
        soundIds[R.raw.error_xp] = soundPool.load(audioContext, R.raw.error_xp, 1)
        soundIds[R.raw.warning_xp] = soundPool.load(audioContext, R.raw.warning_xp, 1)
        soundIds[R.raw.information_xp] = soundPool.load(audioContext, R.raw.information_xp, 1)

        // Preload egg sounds
        for (resourceId in eggSounds) {
            soundIds[resourceId] = soundPool.load(audioContext, resourceId, 1)
        }
        
        Log.d("MainActivity", "SoundPool initialized with ${soundIds.size} sounds")
    }
    
    private fun playSound(soundResourceId: Int, bypassMute: Boolean = false) {
        // Check mute state unless bypassing (for unmute confirmation)
        if (!bypassMute && isSoundMuted()) {
            Log.d("MainActivity", "Sound resource $soundResourceId not played - sound is muted")
            return
        }

        try {
            val soundId = soundIds[soundResourceId]
            if (soundId != null) {
                // Play sound asynchronously to avoid blocking UI
                Thread {
                    try {
                        val streamId = soundPool.play(soundId, 1f, 1f, 1, 0, 1.0f)
                        if (streamId == 0) {
                            Log.e("MainActivity", "Failed to play sound resource $soundResourceId (may not be loaded yet)")
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error in sound playback thread for resource $soundResourceId", e)
                    }
                }.start()
            } else {
                Log.w("MainActivity", "Sound resource $soundResourceId not found in preloaded sounds playing")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error playing sound resource $soundResourceId", e)
        }
    }
    

    private fun setupChargingDetection() {
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {

                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> playSound(R.raw.charge_on_8)
                    Intent.ACTION_POWER_DISCONNECTED -> playSound(R.raw.charge_off_8)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(chargingReceiver, filter)
        Log.d("MainActivity", "Charging detection setup complete")
    }

    private fun initializeSystemApps() {
        // Register Internet Explorer
        systemAppActions["system.internet_explorer"] = { appInfo ->
            showInternetExplorerDialog(appInfo = appInfo)
        }

        // Register Notepad
        systemAppActions["system.notepad"] = { appInfo ->
            showNotepadDialog()
        }

        // Register Zune.
        systemAppActions["system.zune"] = { _ ->
            showZuneDialog()
        }

        // And News, on the same terms.
        systemAppActions["system.news"] = { _ ->
            showNewsDialog()
        }

        // Calculator, on the same terms: registered whatever the theme, offered only
        // under Windows Phone 8.1.
        systemAppActions["system.calculator"] = { _ ->
            showCalculatorDialog()
        }

        // People, likewise. It is also what the People tile opens - see openWP81People.
        systemAppActions["system.people"] = { _ ->
            showPeopleDialog()
        }

        // Welcome. Tapping it while an update is waiting goes to the update instead: the
        // tile is showing the update, and what a tile shows is what tapping it should be
        // about.
        systemAppActions["system.welcome"] = { _ ->
            val update = updateDownloadLink
            if (!update.isNullOrEmpty()) openUrlShortcut(update)
            else showWelcomeDialogWP81()
        }

        // Register Minesweeper
        systemAppActions["system.minesweeper"] = { appInfo ->
            showMinesweeperDialog(appInfo = appInfo)
        }

        // Register Solitare
        systemAppActions["system.solitare"] = { appInfo ->
            showSolitareDialog(appInfo = appInfo)
        }

        // Alarms, on the same terms as the other Metro programs: registered whatever the
        // theme, offered only under Windows Phone 8.1, so a tile pinned before a theme
        // switch still opens rather than doing nothing.
        systemAppActions["system.alarms"] = { _ ->
            showAlarmsDialog()
        }

        // Weather, on the same terms. It is also what the Start screen's weather tile
        // opens, the way the News tile opens News - see openWP81Weather.
        systemAppActions["system.weather"] = { _ ->
            showWeatherDialog()
        }

        // Files, on the same terms again. Windows Phone only: the desktop themes reach the
        // same storage through My Computer, in their own chrome - see WINDOWS_PHONE_ONLY_APPS.
        systemAppActions["system.files"] = { _ ->
            showFilesDialog()
        }

        Log.d("MainActivity", "System apps initialized: ${systemAppActions.size} apps")
    }

    private fun getSystemAppsList(): List<AppInfo> {
        val systemApps = mutableListOf<AppInfo>()

        // Internet Explorer - scale icon to match app icon size
        val ieDrawable = AppCompatResources.getDrawable(this, themeManager.getIEIcon())
        if (ieDrawable != null) {
            systemApps.add(AppInfo(
                name = "Internet Explorer",
                exeName = "iexplore.exe",
                packageName = "system.internet_explorer",
                icon = createSquareDrawable(ieDrawable),
                minWindowWidthDp = 360
            ))
        }

        // Registry Editor - scale icon to match app icon size
        val regeditDrawable = AppCompatResources.getDrawable(this,themeManager.getRegeditIcon())
        if (regeditDrawable != null) {
            systemApps.add(AppInfo(
                name = "Registry Editor",
                exeName = "regedit.exe",
                packageName = "system.registry_editor",
                icon = createSquareDrawable(regeditDrawable)
            ))
        }

        // Dialer - scale icon to match app icon size
        val dialerDrawable = AppCompatResources.getDrawable(this,R.drawable.dialer_icon)
        if (dialerDrawable != null) {
            systemApps.add(AppInfo(
                name = "Phone Dialer",
                exeName = "dialer.exe",
                packageName = "system.dialer",
                icon = createSquareDrawable(dialerDrawable)
            ))
        }

        // Notepad - scale icon to match app icon size
        val notepadDrawable = AppCompatResources.getDrawable(this,themeManager.getNotepadIcon())
        if (notepadDrawable != null) {
            systemApps.add(AppInfo(
                name = "Notepad",
                exeName = "notepad.exe",
                packageName = "system.notepad",
                icon = createSquareDrawable(notepadDrawable)
            ))
        }

        // Winamp - scale icon to match app icon size
        val winampDrawable = AppCompatResources.getDrawable(this,themeManager.getWinampIcon())
        if (winampDrawable != null) {
            systemApps.add(AppInfo(
                name = "Winamp",
                exeName = "winamp.exe",
                packageName = "system.winamp",
                icon = createSquareDrawable(winampDrawable)
            ))
        }

        // Windows Media Player - scale icon to match app icon size
        val wmpDrawable = AppCompatResources.getDrawable(this,themeManager.getWmpIcon())
        if (wmpDrawable != null) {
            systemApps.add(AppInfo(
                name = "Windows Media Player",
                exeName = "wmplayer.exe",
                packageName = "system.wmp",
                icon = createSquareDrawable(wmpDrawable)
            ))
        }

        // Music - the phone's player, and the one program here with no desktop counterpart:
        // it is a Windows Phone app through and through, and on a Windows 98 desktop it
        // would be an anachronism sitting next to Winamp doing the same job. Called Music
        // because that is what the phone called it; the package is still system.zune,
        // which is what everything already pinned is filed under.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_headphones)?.let { glyph ->
            systemApps.add(AppInfo(
                name = "Music",
                exeName = "zune.exe",
                packageName = "system.zune",
                icon = createSquareDrawable(glyph)
            ))
        }

        // Welcome, which the desktop themes show as a window after an update. Windows
        // Phone only: the desktop has its own, in its own chrome.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_welcome)?.let { glyph ->
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Welcome",
                exeName = "welcome.exe",
                packageName = "system.welcome",
                icon = createSquareDrawable(tinted)
            ))
        }

        // Calculator. Windows Phone only: the desktop themes have no calculator to be a
        // second copy of, and this one is the phone's keypad rather than a program window.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_calculator)?.let { glyph ->
            // Drawn white for tiles; the app list is not always dark, so it takes the
            // accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Calculator",
                exeName = "calc.exe",
                packageName = "system.calculator",
                icon = createSquareDrawable(tinted)
            ))
        }

        // People, which is this shell's phone app as well as its address book - the two
        // things Windows Phone kept in separate programs that were always about the same
        // list. It takes the Phone Dialer's place here; see DESKTOP_ONLY_APPS.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_people)?.let { glyph ->
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "People",
                exeName = "people.exe",
                packageName = "system.people",
                icon = createSquareDrawable(tinted)
            ))
        }

        // News, the reader behind the News tile. Windows Phone only, for the same reason
        // Zune is: it is built out of this shell's own furniture and would be an
        // anachronism on a desktop that already has Internet Explorer.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_news)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "News",
                exeName = "news.exe",
                packageName = "system.news",
                icon = createSquareDrawable(tinted)
            ))
        }

        // Alarms, which is also the phone's stopwatch and its countdown - three things
        // that all answer to a clock, on one panorama, the way the phone had them. Windows
        // Phone only: the desktop themes have their own Clock, in their own chrome.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_clock)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Alarms",
                exeName = "alarms.exe",
                packageName = "system.alarms",
                icon = createSquareDrawable(tinted)
            ))
        }

        // Weather, the forecast behind the weather tile. Windows Phone only, for the same
        // reason News is: it is built out of this shell's own furniture, and the desktop
        // themes show their weather in the taskbar readout instead.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_weather)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Weather",
                exeName = "weather.exe",
                packageName = "system.weather",
                icon = createSquareDrawable(tinted)
            ))
        }

        // Files, the app Windows Phone 8.1 finally got in 2014 - a plain list of what is
        // on the phone. Windows Phone only: the desktop themes have My Computer, which is
        // the same storage in a window with drive letters on it, and two file managers in
        // one app list is one more than anybody needs.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_files)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Files",
                exeName = "files.exe",
                packageName = "system.files",
                icon = createSquareDrawable(tinted)
            ))
        }

        // Minesweeper - scale icon to match app icon size
        val minesweeperDrawable = AppCompatResources.getDrawable(this,themeManager.getMinesweeperIcon())
        if (minesweeperDrawable != null) {
            systemApps.add(AppInfo(
                name = "Minesweeper",
                exeName = "minesweeper.exe",
                packageName = "system.minesweeper",
                icon = createSquareDrawable(minesweeperDrawable)
            ))
        }

        // Solitare - scale icon to match app icon size
        val solitareDrawable = AppCompatResources.getDrawable(this,themeManager.getSolitareIcon())
        if (solitareDrawable != null) {
            systemApps.add(AppInfo(
                name = "Solitaire",
                exeName = "solitare.exe",
                packageName = "system.solitare",
                icon = createSquareDrawable(solitareDrawable)
            ))
        }

        // Pinball - scale icon to match app icon size
        val pinballDrawable = AppCompatResources.getDrawable(this, R.drawable.pinball)
        if (pinballDrawable != null) {
            systemApps.add(AppInfo(
                name = "Pinball",
                exeName = "pinball.exe",
                packageName = "system.pinball",
                icon = createSquareDrawable(pinballDrawable)
            ))
        }


        // Clock - scale icon to match app icon size
        val clockDrawable = AppCompatResources.getDrawable(this,themeManager.getClockIcon())
        if (clockDrawable != null) {
            systemApps.add(AppInfo(
                name = "Clock",
                exeName = "clock.exe",
                packageName = "system.clock",
                icon = createSquareDrawable(clockDrawable)
            ))
        }

        // The phone's own programs, kept off every desktop - see WINDOWS_PHONE_ONLY_APPS,
        // which the desktop icon loader reads too, so the two can never drift apart - and
        // the desktop's own, kept off the phone.
        return systemApps.filterNot { isDesktopOnlyApp(it.packageName) }
    }

    fun launchSystemApp(packageName: String) {
        // Find the AppInfo for this system app
        val systemApps = getSystemAppsList()
        val appInfo = systemApps.find { it.packageName == packageName }

        // Noted before anything is opened, and noted whether the window is new or was
        // already standing behind something else - going back into a program is being in
        // it, which is the whole of what the switcher is recording.
        //
        // Here rather than at each of the two dozen tiles, rows and menu items that open
        // one of these, for the same reason the last launched app is noted on startActivity
        // rather than at every launch site: this is the one door they all go through. These
        // programs are windows inside this activity, so the phone's own history never sees
        // them - see RecentAppsStore.
        wp81Recents.noteSystemApp(packageName)

        // Check if this app is already open and bring it to front if so
        if (floatingWindowManager.findAndFocusWindow(packageName)) {
            Log.d("MainActivity", "Brought existing window to front: $packageName")
            return
        }

        val action = systemAppActions[packageName]
        if (action != null) {
            action.invoke(appInfo)
            Log.d("MainActivity", "Launched system app: $packageName")
        } else {
            Log.w("MainActivity", "No action registered for system app: $packageName")
        }
    }




    private fun openCalendarApp() {
        try {
            // Try to open the calendar app
            val calendarIntent = Intent(Intent.ACTION_MAIN)
            calendarIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            // First try Google Calendar
            calendarIntent.setPackage("com.google.android.calendar")
            try {
                startActivity(calendarIntent)
                return
            } catch (e: Exception) {
                // Google Calendar not available, try system calendar
            }
            
            // Try system calendar
            calendarIntent.setPackage("com.android.calendar")
            try {
                startActivity(calendarIntent)
                return
            } catch (e: Exception) {
                // System calendar not available
            }
            
            // Fallback: try to open any calendar app
            val genericCalendarIntent = Intent(Intent.ACTION_VIEW)
            genericCalendarIntent.data = android.provider.CalendarContract.CONTENT_URI
            genericCalendarIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(genericCalendarIntent)
            
        } catch (e: Exception) {
            // All calendar opening methods failed
            Log.e("MainActivity", "Failed to open calendar app", e)
        }
    }
    
    
    
    
    
    private fun loadAppIcon(packageName: String): Drawable? {
        // Handle special virtual items that don't have real packages
        if (packageName == "recycle.bin") {
            // Return the recycle bin icon from resources instead of looking in package manager
            return AppCompatResources.getDrawable(this, R.drawable.recycle)
        }

        if(packageName.startsWith("folder_")){
            // Return appropriate folder icon based on theme
            return AppCompatResources.getDrawable(this, themeManager.getFolderIconRes())
        }

        // Handle system apps
        if (isSystemApp(packageName)) {
            return when (packageName) {
                "system.internet_explorer" ->AppCompatResources.getDrawable(this, themeManager.getIEIcon())
                "system.notepad" ->AppCompatResources.getDrawable(this, themeManager.getNotepadIcon())
                "system.clock" ->AppCompatResources.getDrawable(this, themeManager.getClockIcon())
                "system.solitare" ->AppCompatResources.getDrawable(this, themeManager.getSolitareIcon())
                "system.minesweeper" ->AppCompatResources.getDrawable(this, themeManager.getMinesweeperIcon())
                "system.pinball" ->AppCompatResources.getDrawable(this, R.drawable.pinball)
                "system.registry_editor" ->AppCompatResources.getDrawable(this, themeManager.getRegeditIcon())
                "system.winamp" ->AppCompatResources.getDrawable(this, themeManager.getWinampIcon())
                "system.zune" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_headphones)
                "system.news" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_news)
                "system.welcome" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_welcome)
                "system.calculator" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_calculator)
                "system.people" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_people)
                "system.alarms" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_clock)
                "system.weather" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_weather)
                "system.files" -> AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_files)
                "system.wmp" ->AppCompatResources.getDrawable(this, themeManager.getWmpIcon())
                else -> null
            }
        }
        
        return try {
            try {
                val launcherContext =
                    attributionContext("system")
                val launcherApps = launcherContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val user = android.os.Process.myUserHandle()
                val activities = launcherApps.getActivityList(packageName, user)
                activities.firstOrNull()?.let { activityInfo ->
                    val icon = activityInfo.getBadgedIcon(resources.displayMetrics.densityDpi)
                    return icon
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "LauncherApps failed for $packageName", e)
            }

            // Final fallback to standard method
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val icon = appInfo.loadIcon(packageManager)
            Log.d("MainActivity", "Loaded icon for $packageName using standard PackageManager")
            return icon
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading icon for $packageName", e)
            null
        }
    }
    



    
    






    private fun isOpenUrlsInIeEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_OPEN_URLS_IN_IE, false) // Default to the system default browser
    }

    fun isShowAqiEnabled(): Boolean = wp81TileHost.showAqi()








    private fun getCurrentThemeWallpaperKeys(): Pair<String, String> {
        return getCurrentThemeWallpaperKeysTypeSafe()
    }

    
    
    


    


    
    

    
    






    
    




    
    
    
    /**
     * Something was installed, removed or renamed, so the app list is read again.
     *
     * This was the desktop Start menu's loader, filling a RecyclerView through AppsAdapter
     * and caching the result. The phone has one app list and its shell owns it, so the job
     * is now the phone's refresh - which is what every caller of this meant anyway.
     */
    private fun loadInstalledApps() {
        cachedAppList = null
        refreshWP81AppList()
    }

    private fun loadAppsInBackground(): List<AppInfo> {
        val packageManager = packageManager
        val appInfoMap = mutableMapOf<String, AppInfo>()

        // Add system apps first (with icons loaded since they're from resources)
        getSystemAppsList().forEach { systemApp ->
            appInfoMap[systemApp.packageName] = systemApp
        }

        // Get all apps with launcher intents
        // Load icons immediately (cached for performance) - provides smooth scrolling
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfoList = packageManager.queryIntentActivities(mainIntent, 0)

            Log.d("MainActivity", "Loading ${resolveInfoList.size} apps with cached icons for smooth scrolling")

            resolveInfoList.forEach { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (!appInfoMap.containsKey(packageName)) {
                    // Load icon immediately - uses cache so it's fast on subsequent loads
                    val icon = getAppIcon(packageName, skipCustom = true) ?: resolveInfo.loadIcon(packageManager)
                    appInfoMap[packageName] = AppInfo(
                        name = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = packageName,
                        icon = icon
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading apps", e)
        }

        return appInfoMap.values.toList().sortedBy { it.name.lowercase() }
    }





    
    
    
    
    




    /**
     * The app that handed the launcher the link the browser is showing, if any.
     *
     * Kept for as long as that page is: it is where back goes when the page runs out of
     * history. See [returnToLinkCaller].
     */
    private var linkCallerPackage: String? = null

    /**
     * Whether the desktop browser's window is standing in for another app's link.
     *
     * The phone's browser can say this per tab, because it has tabs; the desktop one is a
     * single window with a single page in it, so the window is the unit. Set by every
     * [showInternetExplorerDialog] - a link opened from inside the launcher clears it
     * again - and cleared when the window closes.
     */
    private var ieWindowOpenedByLink = false

    private fun setupBackPressHandling() {
        // Modern back press handling for Android 13+ (API 33+)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Reset the gesture flag when back is completed

                when {
                    // The task switcher, which is the one part of the shell that is drawn
                    // over the windows rather than under them - so unlike everything below
                    // it, an open window does not own back ahead of it. See
                    // liftWP81Overlays.
                    wp81Shell?.closeRecents() == true -> {
                        Log.d("MainActivity", "Back pressed (modern): closing the task switcher")
                    }
                    // WP8.1 shell, but only when nothing is open on top of it: dismiss the
                    // jump list, leave tile edit mode, or page back from the app list to
                    // Start. A window on screen owns back before the shell does.
                    floatingWindowManager.getFrontVisibleWindow() == null &&
                        wp81Shell?.handleBack() == true -> {
                        Log.d("MainActivity", "Back pressed (modern): handled by WP8.1 shell")
                    }
                    isStartMenuVisible -> {
                        // If start menu is open, close it
                        Log.d("MainActivity", "Back pressed (modern): closing start menu")
                    }
                    floatingWindowManager.getFrontVisibleWindow() != null -> {
                        val frontWindow = floatingWindowManager.getFrontVisibleWindow()

                        // The phone's browser answers first: back there closes the app
                        // bar menu, drops out of the address field, or steps back through
                        // the pages, and only leaves the window when none of those apply.
                        val metroIE =
                            if (frontWindow?.windowIdentifier == "system.internet_explorer")
                                metroIEAppInstance
                            else null

                        if (metroIE != null && metroIE.handleBack()) {
                            Log.d("MainActivity", "Back pressed (modern): handled by IE (phone)")
                        } else {
                            if (frontWindow?.windowIdentifier == "system.news" &&
                                newsAppInstance?.handleBack() == true
                            ) {
                                Log.d("MainActivity", "Back pressed (modern): handled by News")
                            } else if (frontWindow?.windowIdentifier == "system.weather" &&
                                weatherAppInstance?.handleBack() == true
                            ) {
                                // A day, the place search, the settings or a command list
                                // was open over the panorama. Backing out of one of those
                                // is a step inside the app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by Weather")
                            } else if (frontWindow?.windowIdentifier == "system.alarms" &&
                                alarmsAppInstance?.handleBack() == true
                            ) {
                                // An editor, a sound list or a command list was open over
                                // the panorama. Backing out of one of those is a step
                                // inside the app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by Alarms")
                            } else if (frontWindow?.windowIdentifier == "system.people" &&
                                peopleAppInstance?.handleBack() == true
                            ) {
                                // A profile, the editor, the keypad or a command list was
                                // open over the panorama. Backing out of one of those is a
                                // step inside the app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by People")
                            } else if (frontWindow?.windowIdentifier == "system.files" &&
                                metroFilesAppInstance?.handleBack() == true
                            ) {
                                // A prompt, a hold menu, select mode or a folder above the
                                // one being shown. Backing out of any of those is a step
                                // inside the app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by Files")
                            } else if (frontWindow?.windowIdentifier == "system.notepad" &&
                                metroNotepadAppInstance?.handleBack() == true
                            ) {
                                // A menu, a rename or the note itself was open over the
                                // list. Backing out of one of those is a step inside the
                                // app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by Notepad")
                            } else if (frontWindow?.windowIdentifier == "system.minesweeper" &&
                                metroMinesweeperInstance?.handleBack() == true
                            ) {
                                // The strip's own command list was open over the field.
                                Log.d("MainActivity", "Back pressed (modern): handled by Minesweeper")
                            } else if (frontWindow?.windowIdentifier == "system.solitare" &&
                                metroSolitaireInstance?.handleBack() == true
                            ) {
                                Log.d("MainActivity", "Back pressed (modern): handled by Solitaire")
                            } else if (frontWindow?.windowIdentifier == "system.zune" &&
                            zuneAppInstance?.handleBack() == true
                        ) {
                            // A record, a sheet or the play queue was open over the player.
                            // Backing out of one of those is a step inside the app rather
                            // than a way out of it.
                            Log.d("MainActivity", "Back pressed (modern): handled by Zune")
                        } else if (frontWindow?.windowIdentifier == "system.zune") {
                                // Zune keeps playing when you leave it, so backing out of
                                // it means what it means everywhere else on a phone: put
                                // it away, do not shut it down. Its window stays, and the
                                // tile on Start opens it again where it left off.
                                Log.d("MainActivity", "Back pressed (modern): minimising Zune")
                                frontWindow.minimize()
                            } else {
                                // Close the front-most window. If it is the browser showing
                                // a link from another app and the page has nowhere left to
                                // go - which is what every branch above just failed to find
                                // - then closing it is the end of that app's errand, and
                                // the screen goes back to the app rather than to the desktop.
                                val leaving = ieWindowOpenedByLink &&
                                    frontWindow?.windowIdentifier == "system.internet_explorer"
                                Log.d("MainActivity", "Back pressed (modern): closing front window")
                                floatingWindowManager.closeFrontWindow()
                                if (leaving) returnToLinkCaller()
                            }
                        }
                    }
                    // Back on Start has nowhere to go, so a second quick press is
                    // free to mean something: back to the app you just left.
                    wp81BackAgain() -> {
                        Log.d("MainActivity", "Back pressed (modern): switched to last app")
                    }
                    else -> {
                        // If start menu is closed, do nothing
                        // This prevents the home screen from closing/restarting
                        Log.d("MainActivity", "Back pressed (modern): ignored (home screen)")
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    isBackGestureInProgress = false
                }, 500)

            }

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                // Mark that back gesture has started
                isBackGestureInProgress = true
                Log.d("MainActivity", "Back gesture started")
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                // Back gesture is in progress - keep blocking touches
                // No action needed, just keep the flag set
            }

            override fun handleOnBackCancelled() {
                // User cancelled the back gesture - re-enable touches
                isBackGestureInProgress = false
                potentialBackGestureStartTime = 0L
                Log.d("MainActivity", "Back gesture cancelled")
            }
        })
    }

    
    
    
    
    private fun requestNotificationPermissionIfNeeded() {
        // Only request on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

            if (!alreadyRequested) {
                Log.d("MainActivity", "First launch - requesting notification permission")

                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {

                    // Mark as requested so we don't ask again
                    prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }

                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    Log.d("MainActivity", "Notification permission already granted")
                    prefs.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
                }
            } else {
                Log.d("MainActivity", "Notification permission already requested previously")
            }
        } else {
            Log.d("MainActivity", "Android version < 13, notification permission not required")
        }
    }






    private fun hideContextMenu() {
        if (::contextMenu.isInitialized) {
            contextMenu.hideMenu()
            isContextMenuVisible = false
        }
        
    }
    

    
    










    
    

    
    








    private fun openAppInfo(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            hideContextMenu()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening app info for package: $packageName", e)
        }
    }


    private fun saveCustomIconMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val themeKey = rocks.gorjan.gokixp.theme.CUSTOM_ICONS_KEY

        val jsonString = customIconMappings.entries.joinToString(";") { "${it.key}:${it.value}" }
        Log.d("MainActivity", "Saving custom icons to $themeKey: $jsonString")

        prefs.edit {
            putString(themeKey, jsonString)
        }
    }

    // Migrate all settings from old separate SharedPreferences files to current PREFS_NAME
    // This function can be deleted in a future version after users have migrated
    private fun migrateCustomMappingsIfNeeded() {
        val newPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // List of old SharedPreferences files to migrate from
        val oldPrefsToMigrate = listOf(
            "launcher_prefs",
            "agent_settings",
            "quick_glance_position",
            "GokiXP"  // Internet Explorer homepage
        )

        var migrationNeeded = false

        // Check if any old prefs files have data that's not in the new prefs
        oldPrefsToMigrate.forEach { oldPrefsName ->
            val oldPrefs = getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
            if (oldPrefs.all.isNotEmpty()) {
                // Check if any key from old prefs is missing in new prefs
                oldPrefs.all.keys.forEach { key ->
                    if (!newPrefs.contains(key)) {
                        migrationNeeded = true
                    }
                }
            }
        }

        if (migrationNeeded) {
            Log.d("MainActivity", "Migrating settings from old SharedPreferences files to $PREFS_NAME")
            newPrefs.edit {
                oldPrefsToMigrate.forEach { oldPrefsName ->
                    val oldPrefs = getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
                    val allOldPrefs = oldPrefs.all

                    if (allOldPrefs.isNotEmpty()) {
                        Log.d("MainActivity", "Migrating from $oldPrefsName (${allOldPrefs.size} keys)")

                        allOldPrefs.forEach { (key, value) ->
                            if (!newPrefs.contains(key)) {
                                when (value) {
                                    is String -> {
                                        putString(key, value)
                                        Log.d("MainActivity", "  Migrated String: $key")
                                    }
                                    is Boolean -> {
                                        putBoolean(key, value)
                                        Log.d("MainActivity", "  Migrated Boolean: $key = $value")
                                    }
                                    is Int -> {
                                        putInt(key, value)
                                        Log.d("MainActivity", "  Migrated Int: $key = $value")
                                    }
                                    is Long -> {
                                        putLong(key, value)
                                        Log.d("MainActivity", "  Migrated Long: $key = $value")
                                    }
                                    is Float -> {
                                        putFloat(key, value)
                                        Log.d("MainActivity", "  Migrated Float: $key = $value")
                                    }
                                    else -> {
                                        Log.w("MainActivity", "  Unknown type for key $key: ${value?.javaClass?.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Log.d("MainActivity", "Migration completed successfully")
        }
    }

    /**
     * Moves Windows Phone's hand-picked icons out of the desktop themes' sets.
     *
     * Until this release the Start screen had no icon key of its own: it took whichever
     * one its window chrome pointed at, which is Vista's, so a tile icon overwrote a Vista
     * desktop icon for the same app and the car screen - reading XP's - showed neither.
     *
     * An icon from the phone's own set (assets/custom_icons_8) can only have been chosen
     * from a tile, so those move across and are struck out of the desktop set they were
     * polluting. Icons the user picked from the shared Programs folder or imported from
     * their gallery could have come from either shell, so they are left where they are
     * rather than guessed at and taken away from a desktop that may be showing them.
     */
    private fun migrateWP81CustomIconsIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_WP81_ICONS_SPLIT, false)) return

        fun parse(key: String): MutableMap<String, String> =
            (prefs.getString(key, "") ?: "").split(";")
                .mapNotNull { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap(mutableMapOf())

        fun serialise(mappings: Map<String, String>) =
            mappings.entries.joinToString(";") { "${it.key}:${it.value}" }

        val phoneKey = rocks.gorjan.gokixp.theme.CUSTOM_ICONS_KEY
        val phoneIcons = parse(phoneKey)
        val moved = mutableMapOf<String, String>()

        // Vista's is where the writes went, XP's is what the car screen was reading and
        // what the shell carried into memory when it was entered from XP. Both are swept,
        // and so is Classic's, for a setup carried over from the desktop launcher.
        val donors = rocks.gorjan.gokixp.theme.DESKTOP_CUSTOM_ICON_KEYS

        prefs.edit {
            for (donorKey in donors) {
                val donor = parse(donorKey)
                val phonePicks = donor.filterValues { it.startsWith("$WP81_ICON_FOLDER/") }
                if (phonePicks.isEmpty()) continue
                phonePicks.keys.forEach { donor.remove(it) }
                // An icon already chosen under the new key wins - it is the more recent answer.
                phonePicks.forEach { (pkg, path) -> moved.putIfAbsent(pkg, path) }
                putString(donorKey, serialise(donor))
            }
            if (moved.isNotEmpty()) {
                moved.forEach { (pkg, path) -> phoneIcons.putIfAbsent(pkg, path) }
                putString(phoneKey, serialise(phoneIcons))
            }
            putBoolean(KEY_WP81_ICONS_SPLIT, true)
        }

        if (moved.isNotEmpty()) {
            Log.d("MainActivity", "Moved ${moved.size} Windows Phone icons to $phoneKey")
        }
    }

    /**
     * Takes a retired program back out of everything the user had put it in.
     *
     * A program that leaves the shell leaves its traces behind: a desktop shortcut, a
     * tile on Start, a pin in the Start menu, an icon or a name the user chose for it by
     * hand. All of those are filed under the package name and nothing prunes them, so
     * without this a shortcut to a program that no longer exists survives as its old
     * name under Internet Explorer's icon - that is the fallback in [loadDesktopIcons] -
     * and tapping it does nothing whatever, there being no action registered any more.
     *
     * The packages already swept are written down rather than a single "done" flag, so
     * that retiring the next program is one entry in [RETIRED_SYSTEM_APPS] and not
     * another migration.
     */
    private fun purgeRetiredSystemApps() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val swept = (prefs.getString(KEY_RETIRED_APPS_PURGED, "") ?: "")
            .split(",").filter { it.isNotEmpty() }.toSet()
        val retiring = RETIRED_SYSTEM_APPS - swept
        if (retiring.isEmpty()) return

        // Nothing is written unless the whole sweep gets through: edit {} applies at the
        // end of the block, so a throw half way leaves the arrangement as it was and the
        // packages unmarked, to be tried again on the next launch.
        try {
            prefs.edit {
                // Desktop shortcuts, the icons filed inside folders, and - a Start screen
                // tile being a desktop icon under Windows Phone 8.1 - the tiles as well.
                val json = prefs.getString(KEY_DESKTOP_ICONS, null)
                if (json != null) {
                    val gson = Gson()
                    val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val icons: List<Map<String, Any>> = gson.fromJson(json, type)
                    val kept = icons.filterNot { it["packageName"] in retiring }
                    if (kept.size != icons.size) putString(KEY_DESKTOP_ICONS, gson.toJson(kept))
                }

                // Pins in the Start menu, and anything the user had hidden from the app list.
                for (key in listOf(KEY_PINNED_APPS, KEY_HIDDEN_APPS)) {
                    purgeListedPackages(prefs, key, ",", retiring) { it }
                }

                // Icons chosen by hand - one set per theme - and renamed shortcuts. Both
                // are "package:value" pairs, and a renamed one escapes the colons in the
                // value, so the package is always what stands before the first.
                for (key in listOf(rocks.gorjan.gokixp.theme.CUSTOM_ICONS_KEY) + KEY_CUSTOM_NAMES) {
                    purgeListedPackages(prefs, key, ";", retiring) { it.substringBefore(":") }
                }

                // The gestures that are pointed at one particular program.
                for (key in listOf(KEY_SWIPE_RIGHT_APP, KEY_WEATHER_APP)) {
                    if (prefs.getString(key, null) in retiring) remove(key)
                }

                // MSN Messenger read the phone's messages, and stamped when each
                // correspondent was last read. Nothing is left to read those stamps, and
                // message data has no business travelling on in a settings backup.
                if ("system.msn" in retiring) {
                    prefs.all.keys.filter { it.startsWith("last_read_") }.forEach { remove(it) }
                }

                putString(KEY_RETIRED_APPS_PURGED, (swept + retiring).joinToString(","))
            }
            Log.d("MainActivity", "Swept retired programs out of the user arrangement: $retiring")
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not sweep retired programs, will retry next launch", e)
        }
    }

    /**
     * Drops the retired packages from one of the delimited lists the shell keeps.
     *
     * [packageOf] pulls the package out of an entry, which for the plain lists is the
     * entry itself and for the mapped ones is the half before the colon.
     */
    private fun android.content.SharedPreferences.Editor.purgeListedPackages(
        prefs: android.content.SharedPreferences,
        key: String,
        separator: String,
        retiring: Set<String>,
        packageOf: (String) -> String
    ) {
        val entries = (prefs.getString(key, "") ?: "").split(separator).filter { it.isNotEmpty() }
        val kept = entries.filterNot { packageOf(it) in retiring }
        if (kept.size != entries.size) putString(key, kept.joinToString(separator))
    }

    private fun loadCustomIconMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val themeKey = rocks.gorjan.gokixp.theme.CUSTOM_ICONS_KEY

        Log.d("MainActivity", "Loading custom icon mappings from $themeKey")

        // Try to load theme-specific mappings first
        val jsonString = prefs.getString(themeKey, "") ?: ""
        Log.d("MainActivity", "Theme-specific mappings found: ${jsonString.isNotEmpty()}")

        // TEMPORARILY DISABLED: If no theme-specific mapping exists, try to migrate from legacy storage
        // This migration might be causing cross-theme pollution
        /*
        if (jsonString.isEmpty()) {
            val legacyString = prefs.getString(KEY_CUSTOM_ICONS, "") ?: ""
            Log.d("MainActivity", "Legacy mappings found: ${legacyString.isNotEmpty()}")
            if (legacyString.isNotEmpty()) {
                // Migrate legacy mappings to current theme
                jsonString = legacyString

                // Save to theme-specific key
                prefs.edit {
                    putString(themeKey, legacyString)
                    // Don't remove legacy key yet in case both themes were used
                }
                Log.d("MainActivity", "Migrated legacy mappings to $themeKey")
            }
        }
        */

        customIconMappings.clear()
        if (jsonString.isNotEmpty()) {
            jsonString.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    customIconMappings[parts[0]] = parts[1]
                }
            }
        }
        Log.d("MainActivity", "Loaded ${customIconMappings.size} custom icon mappings: ${customIconMappings.keys}")
    }
    
    private fun saveCustomNameMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            // Convert map to JSON string (simple approach)
            val jsonString =
                customNameMappings.entries.joinToString(";") { "${it.key}:${it.value.replace(":", "&#58;").replace(";", "&#59;")}" }
            putString(KEY_CUSTOM_NAMES, jsonString)
        }
    }
    
    private fun loadCustomNameMappings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_CUSTOM_NAMES, "") ?: ""
        
        customNameMappings.clear()
        if (jsonString.isNotEmpty()) {
            jsonString.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size >= 2) {
                    val packageName = parts[0]
                    val customName = parts.drop(1).joinToString(":").replace("&#58;", ":").replace("&#59;", ";")
                    customNameMappings[packageName] = customName
                }
            }
        }
    }
    
    fun getCustomOrOriginalName(packageName: String, originalName: String): String {
        return customNameMappings[packageName] ?: originalName
    }
    
    /**
     * Loads an icon referenced by an icon mapping. Paths either point into the bundled assets
     * or, for icons the user imported from their device, into [IMPORTED_ICONS_DIR] under filesDir.
     */
    private fun loadIconFromPath(iconPath: String): Drawable? =
        rocks.gorjan.gokixp.wp81.WP81TileHost.loadIconFromPath(this, iconPath)

    /**
     * Copies an image the user picked from their device into the app's own icon storage,
     * downsampled to icon size and re-encoded as PNG so transparency is preserved.
     * Returns the path to store in the icon mappings, or null if the image couldn't be read.
     */
    private fun importCustomIconFromUri(uri: Uri): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options, ICON_SIZE_PX, ICON_SIZE_PX)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 // keep the alpha channel

            val decoded = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            // Fit inside the icon size without upscaling; createSquareDrawable pads the rest
            val scale = minOf(
                ICON_SIZE_PX.toFloat() / decoded.width,
                ICON_SIZE_PX.toFloat() / decoded.height,
                1f
            )
            val bitmap = if (scale < 1f) {
                decoded.scale(
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1)
                )
            } else {
                decoded
            }

            val iconsDir = File(filesDir, IMPORTED_ICONS_DIR).apply { mkdirs() }
            val iconFile = File(iconsDir, "icon_${System.currentTimeMillis()}.png")
            iconFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()

            Log.d("MainActivity", "Imported custom icon from $uri to ${iconFile.name}")
            "$IMPORTED_ICONS_DIR/${iconFile.name}"
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to import custom icon from $uri", e)
            null
        }
    }

    /**
     * Deletes imported icon files that no theme's icon mappings reference any more,
     * so replacing a custom icon doesn't leave the old image behind forever.
     */
    private fun pruneUnusedImportedIcons() {
        val iconsDir = File(filesDir, IMPORTED_ICONS_DIR)
        val files = iconsDir.listFiles() ?: return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inUse = listOf(rocks.gorjan.gokixp.theme.CUSTOM_ICONS_KEY)
            .flatMap { key -> (prefs.getString(key, "") ?: "").split(";") }
            .mapNotNull { entry -> entry.substringAfter(":", "").takeIf { it.isNotEmpty() } }
            .toSet()

        files.forEach { file ->
            if ("$IMPORTED_ICONS_DIR/${file.name}" !in inUse) {
                if (file.delete()) Log.d("MainActivity", "Removed unused imported icon: ${file.name}")
            }
        }
    }

    /**
     * Central function to get app icon - returns custom icon if available, otherwise default icon
     * This function handles theme awareness and should be used from all places (desktop, command list, app list)
     */
    fun getAppIcon(packageName: String, skipCustom: Boolean = false): Drawable? {

        if(!skipCustom) {
            // First check if there's a custom icon mapping for current theme
            val customIconPath = customIconMappings[packageName]
            if (customIconPath != null) {
                try {
                    val drawable = loadIconFromPath(customIconPath)
                    if (drawable != null) {
                        // Create a square drawable with consistent sizing and cache it
                        val cacheKey = "custom_${packageName}_${customIconPath}"
                        return createSquareDrawable(drawable, cacheKey)
                    }
                } catch (e: Exception) {
                    Log.w(
                        "MainActivity",
                        "Failed to load custom icon for $packageName, falling back to default",
                        e
                    )
                    // Remove invalid mapping
                    customIconMappings.remove(packageName)
                    saveCustomIconMappings()
                }
            }
        }

        // Fall back to default app icon with caching
        return loadAppIcon(packageName)?.let {
            val cacheKey = "app_${packageName}"
            createSquareDrawable(it, cacheKey)
        }
    }
    
    private fun createSquareDrawable(originalDrawable: Drawable, cacheKey: String? = null): Drawable {
        val iconSize = ICON_SIZE_PX // Standard size for desktop icons

        // Check cache first if we have a cache key
        if (cacheKey != null) {
            val cachedBitmap = iconBitmapCache.get(cacheKey)
            if (cachedBitmap != null) {
                return cachedBitmap.toDrawable(resources)
            }
        }

        // Create a bitmap with square dimensions
        val bitmap = createBitmap(iconSize, iconSize)
        val canvas = Canvas(bitmap)

        // Calculate scaling to fit the drawable in the square while maintaining aspect ratio
        val originalWidth = originalDrawable.intrinsicWidth
        val originalHeight = originalDrawable.intrinsicHeight

        val scale = if (originalWidth > 0 && originalHeight > 0) {
            minOf(iconSize.toFloat() / originalWidth, iconSize.toFloat() / originalHeight)
        } else {
            1f
        }

        val scaledWidth = (originalWidth * scale).toInt()
        val scaledHeight = (originalHeight * scale).toInt()

        // Center the drawable in the square
        val left = (iconSize - scaledWidth) / 2
        val top = (iconSize - scaledHeight) / 2
        val right = left + scaledWidth
        val bottom = top + scaledHeight

        // Set bounds and draw
        originalDrawable.setBounds(left, top, right, bottom)
        originalDrawable.draw(canvas)

        // Cache the bitmap if we have a cache key
        if (cacheKey != null) {
            iconBitmapCache.put(cacheKey, bitmap)
        }

        // Create drawable from bitmap
        return bitmap.toDrawable(resources)
    }

    /**
     * Drops every cached bitmap belonging to a package so the next getAppIcon() call re-reads it
     * from the system. An in-place app update keeps the same package name, so without this the
     * LruCache keeps handing back the icon the app shipped with before the update - including
     * when the user picks "Default" in the Change Icon dialog.
     */
    private fun invalidateIconCache(packageName: String) {
        val staleKeys = iconBitmapCache.snapshot().keys.filter { key ->
            key == "app_$packageName" || key.startsWith("custom_${packageName}_")
        }
        staleKeys.forEach { iconBitmapCache.remove(it) }

        // The cached app list holds the old drawables too, so it has to be rebuilt
        cachedAppList = null

        Log.d("MainActivity", "Invalidated ${staleKeys.size} cached icons for $packageName")
    }

    /** A package's artwork changed, so the tiles wearing it are redrawn. */
    private fun repaintDesktopIconsFor(packageName: String) {
        wp81IconProvider.invalidate(packageName)
        refreshWP81Tiles()
    }

    /**
     * Repaints every place a package's icon is shown (desktop shortcuts, folder contents, start
     * menu and the pinned commands list) with the icon it currently has.
     *
     * The cache is dropped straight away so nothing can read a stale icon in the meantime, but the
     * repaint itself is coalesced: onPackageChanged also fires for component enable/disable, which
     * some apps do in bursts, and rebuilding the app list on each one would be wasteful.
     */
    private fun refreshIconsForPackage(packageName: String) {
        invalidateIconCache(packageName)
        pendingIconRefreshes.add(packageName)

        iconRefreshRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            iconRefreshRunnable = null
            val packages = pendingIconRefreshes.toList()
            pendingIconRefreshes.clear()

            packages.forEach { repaintDesktopIconsFor(it) }
            loadInstalledApps()
        }
        iconRefreshRunnable = runnable
        handler.postDelayed(runnable, ICON_REFRESH_DEBOUNCE_MS)
    }

    /**
     * Package changes arrive through LauncherApps rather than the manifest receiver alone:
     * PACKAGE_ADDED/REPLACED broadcasts are unreliable for manifest receivers on modern Android,
     * and an in-place update leaves the app count unchanged so the periodic checker never notices
     * it either. Registered for the activity's lifetime, which is also the icon cache's lifetime.
     */
    private fun registerLauncherAppsCallback() {
        try {
            val launcherApps = attributionContext("system")
                .getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            val callback = object : LauncherApps.Callback() {
                override fun onPackageAdded(packageName: String, user: UserHandle) {
                    onAppInstalled(packageName)
                }

                override fun onPackageRemoved(packageName: String, user: UserHandle) {
                    onAppRemoved(packageName)
                }

                override fun onPackageChanged(packageName: String, user: UserHandle) {
                    // Fired when an app is updated in place - its icon may have changed
                    onAppReplaced(packageName)
                }

                override fun onPackagesAvailable(
                    packageNames: Array<out String>,
                    user: UserHandle,
                    replacing: Boolean
                ) {
                    packageNames.forEach { refreshIconsForPackage(it) }
                }

                override fun onPackagesUnavailable(
                    packageNames: Array<out String>,
                    user: UserHandle,
                    replacing: Boolean
                ) {
                    packageNames.forEach { invalidateIconCache(it) }
                }
            }

            // Own handler rather than the shared `handler` field, which isn't created until
            // later in onCreate - callbacks are delivered on the main thread either way
            launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
            launcherAppsCallback = callback
            launcherAppsService = launcherApps
            Log.d("MainActivity", "LauncherApps package callback registered")
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not register LauncherApps callback", e)
        }
    }

    private fun unregisterLauncherAppsCallback() {
        val callback = launcherAppsCallback ?: return
        val launcherApps = launcherAppsService
        launcherAppsCallback = null
        launcherAppsService = null
        try {
            // The instance it went on, never a freshly fetched one - see launcherAppsService.
            launcherApps?.unregisterCallback(callback)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not unregister LauncherApps callback", e)
        }
    }

    


    private fun uninstallApp(appInfo: AppInfo) {
        try {
            // Check if this is a system app (cannot be uninstalled by regular users)
            val packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0)
            val isSystemApp = packageInfo.applicationInfo?.let { applicationInfo ->
                (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } ?: false
            
            if (isSystemApp) {
                showNotification("Error", "Cannot uninstall system app: ${appInfo.name}")
                return
            }
            
            // Launch the system uninstall dialog
            val uninstallIntent = Intent(Intent.ACTION_DELETE)
            uninstallIntent.data = "package:${appInfo.packageName}".toUri()
            uninstallIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(uninstallIntent)
            
            // Hide the start menu
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error uninstalling app: ${appInfo.packageName}", e)
            showNotification("Error", "Cannot uninstall ${appInfo.name}")
        }
    }


    private fun showWallpaperTargetDialog(
        wallpaperItem: WallpaperItem? = null,
        uri: Uri? = null,
        drawable: Drawable? = null
    ) {
        // Create Windows-style dialog with correct theme from start
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle("Apply Wallpaper To")


        // Create content view from XML layout
        val contentView = layoutInflater.inflate(R.layout.wallpaper_target_dialog_content, null)
        windowsDialog.setContentView(contentView)

        // Get references to UI elements
        val launcherCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.launcher_checkbox)
        val homeScreenCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.home_screen_checkbox)
        val lockScreenCheckbox = contentView.findViewById<android.widget.CheckBox>(R.id.lock_screen_checkbox)
        val applyButton = contentView.findViewById<TextView>(R.id.apply_button)

        // One button background; the Windows Classic alternative went with its shell.
        val buttonBackground = run {
            R.drawable.button_xp_background
        }
        applyButton.setBackgroundResource(buttonBackground)

        // Apply theme fonts to the entire dialog content
        applyThemeFontsToDialog(contentView)

        // Apply button click handler
        applyButton.setOnClickListener {
            playClickSound()

            if (launcherCheckbox.isChecked) {
                if (wallpaperItem != null) {
                    applyCustomWallpaper(wallpaperItem)
                } else if (drawable != null) {
                    applyWallpaperDrawable(drawable, uri)
                }
            }

            if (homeScreenCheckbox.isChecked || lockScreenCheckbox.isChecked) {
                if (wallpaperItem != null) {
                    applyWallpaperToDevice(wallpaperItem, homeScreenCheckbox.isChecked, lockScreenCheckbox.isChecked)
                } else if (drawable != null) {
                    applyWallpaperToDeviceFromDrawable(drawable, homeScreenCheckbox.isChecked, lockScreenCheckbox.isChecked)
                }
            }

            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set close listener to restore cursor if dialog is closed without applying
        windowsDialog.setOnCloseListener {
        }

        // Set context menu reference and show as floating window
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
    }

    /**
     * Opens the browser.
     *
     * One browser now: the phone's, which is the same engine and the same favourites with
     * the page given the whole screen. The desktop's window - a title bar and eight
     * buttons across the top - went with the desktop.
     */
    private fun showInternetExplorerDialog(
        initialUrl: String? = null,
        appInfo: AppInfo? = null,
        fromAnotherApp: Boolean = false
    ) {
        // The phone's browser keeps this per tab, because it has tabs.
        ieWindowOpenedByLink = false
        showMetroIEDialog(initialUrl, fromAnotherApp)
    }















    /** Opens the notes: the same notes, as a panorama with a page each. */
    private fun showNotepadDialog() = showMetroNotepadDialog()

    /**
     * Opens the phone's Notepad.
     *
     * Full-screen and chromeless like Zune, News and the browser. One window only: the
     * notes are a place rather than a document, and a second copy of the list would be two
     * views of one file with no way to tell which one had the newest keystroke in it.
     */
    private fun showMetroNotepadDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.notepad")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.notepad"

        val notepadApp = rocks.gorjan.gokixp.apps.notepad.MetroNotepadApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onShowNotification = { title, message -> showNotification(title, message) },
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) },
            galleryPickerLauncher = notepadGalleryPickerLauncher,
            onCameraCapture = { uri ->
                pendingCameraUri = uri
                notepadCameraPickerLauncher.launch(uri)
            },
            onShowFullscreenImage = { uri -> showFullscreenImage(uri) }
        )
        metroNotepadAppInstance = notepadApp

        val notepadView = notepadApp.createView()
        windowsDialog.setContentView(notepadView)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_notepad)
        windowsDialog.setTitle("Notepad")
        windowsDialog.setOnCloseListener {
            notepadApp.cleanup()
            metroNotepadAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(notepadView)
    }

    /**
     * Opens the phone's Minesweeper.
     *
     * Full screen and chromeless like the rest of the shell's own programs, and one window
     * only: a second copy would be a second game running its own clock behind the first.
     */
    private fun showMetroMinesweeperDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.minesweeper")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.minesweeper"

        val game = rocks.gorjan.gokixp.apps.minesweeper.MetroMinesweeperApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        )
        metroMinesweeperInstance = game

        val view = game.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_minesweeper)
        windowsDialog.setTitle("Minesweeper")
        windowsDialog.setOnCloseListener {
            game.cleanup()
            metroMinesweeperInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /** Opens the phone's Solitaire. The same window rules as Minesweeper above. */
    private fun showMetroSolitaireDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.solitare")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.solitare"

        val game = rocks.gorjan.gokixp.apps.solitare.MetroSolitaireApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        )
        metroSolitaireInstance = game

        val view = game.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_solitaire)
        windowsDialog.setTitle("Solitaire")
        windowsDialog.setOnCloseListener {
            game.cleanup()
            metroSolitaireInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }


    private fun showFullscreenImage(uri: Uri) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        try {
            // First, decode the bitmap
            val inputStream = contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                dialog.dismiss()
                return
            }

            // Read EXIF orientation and rotate if needed
            try {
                val exifInputStream = contentResolver.openInputStream(uri)
                val exif = exifInputStream?.use {
                    androidx.exifinterface.media.ExifInterface(it)
                }

                val orientation = exif?.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                ) ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

                val rotationAngle = when (orientation) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

                if (rotationAngle != 0f) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationAngle)
                    val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle()
                    }
                    bitmap = rotatedBitmap
                }
            } catch (exifException: Exception) {
                // Continue with unrotated bitmap if EXIF reading fails
                Log.e("MainActivity", "Error reading EXIF data", exifException)
            }

            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            dialog.dismiss()
            return
        }

        imageView.setOnClickListener {
            playClickSound()
            dialog.dismiss()
        }

        dialog.setContentView(imageView)
        dialog.show()
    }


    /**
     * Gets the button background drawable resource for the current theme
     */
    private fun getThemedButtonBackground(): Int = R.drawable.button_xp_background

    /**
     * Generic dialog for renaming items with Windows XP/98 styling
     * @param title Dialog title
     * @param initialText Initial text to show in the input field
     * @param hint Placeholder hint text (optional)
     * @param onOk Callback when OK is clicked, receives the new text
     */
    private fun showRenameDialog(
        title: String,
        initialText: String,
        hint: String = "",
        onOk: (String) -> Unit
    ) {
        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.setTitle(title)

        val contentView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
        }

        // Create EditText
        val editText = EditText(this).apply {
            setText(initialText)
            selectAll()
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            highlightColor = "#7a94f4".toColorInt()
            textSize = 12f
            setBackgroundResource(R.drawable.win98_edit_text_border)
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            isSingleLine = true
            if (hint.isNotEmpty()) {
                setHint(hint)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dpToPx()
            }
        }

        contentView.addView(editText)

        // Create buttons container
        val buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        // Create OK button
        val okButton = TextView(this).apply {
            text = "OK"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 4.dpToPx())
            background = ContextCompat.getDrawable(this@MainActivity, getThemedButtonBackground())
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
        }

        // Create Cancel button
        val cancelButton = TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.BLACK)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(20.dpToPx(), 4.dpToPx(), 20.dpToPx(), 4.dpToPx())
            background = ContextCompat.getDrawable(this@MainActivity, getThemedButtonBackground())
            backgroundTintList = null
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        buttonsContainer.addView(okButton)
        buttonsContainer.addView(cancelButton)
        contentView.addView(buttonsContainer)

        windowsDialog.setContentView(contentView)
        windowsDialog.setWindowSize(250, null)

        // OK button handler
        okButton.setOnClickListener {
            playClickSound()
            val newText = editText.text.toString().trim()
            if (newText.isNotEmpty()) {
                onOk(newText)
            }
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Cancel button handler
        cancelButton.setOnClickListener {
            playClickSound()
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
            floatingWindowManager.removeWindow(windowsDialog)
        }

        // Set context menu reference
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)

        // Show keyboard
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }



    /** Opens Minesweeper: the same game, on a page rather than in a window. */
    private fun showMinesweeperDialog(appInfo: AppInfo? = null) = showMetroMinesweeperDialog()

    /** Opens Solitaire: the same deck, dealt onto a page. */
    private fun showSolitareDialog(appInfo: AppInfo? = null) = showMetroSolitaireDialog()





    private var zuneAppInstance: rocks.gorjan.gokixp.apps.zune.ZuneApp? = null

    /**
     * Opens Zune.
     *
     * Borderless, and maximised because every maximisable window is under this theme, so
     * what appears is a full-screen phone app rather than a program in a frame. That is
     * not decoration: the whole design is built on having the screen to itself, and in a
     * 358x420 window with a title bar it would look like a mistake.
     */
    private fun showZuneDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.zune")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.zune"

        val zuneApp = rocks.gorjan.gokixp.apps.zune.ZuneApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onRequestPermissions = {
                val permission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
                requestPermissions(arrayOf(permission), AUDIO_PERMISSION_REQUEST_CODE)
            },
            hasAudioPermission = {
                val permission =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
                checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        )
        zuneAppInstance = zuneApp

        val zuneView = zuneApp.createView()
        windowsDialog.setContentView(zuneView)
        windowsDialog.setBorderless()
        // No size of its own: the forced maximise fills the container, which is the screen
        // minus the navigation keys. Stating a size in screen percent instead made the
        // frame taller than the space it lives in, and a window bigger than its overlay is
        // centred rather than clamped - so it hung off the top and the bottom at once.
        //
        // Nothing about its geometry is worth remembering either: it is always full-screen,
        // and a saved position could only ever be wrong.
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_headphones)
        windowsDialog.setTitle("Music")
        windowsDialog.setOnCloseListener {
            zuneApp.cleanup()
            zuneAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(zuneView)
    }

    private var newsAppInstance: rocks.gorjan.gokixp.apps.news.NewsApp? = null

    private var alarmsAppInstance: rocks.gorjan.gokixp.apps.alarms.AlarmsApp? = null

    private var weatherAppInstance: rocks.gorjan.gokixp.apps.weather.WeatherApp? = null

    /**
     * Opens Calculator.
     *
     * Full-screen and chromeless like Zune and News, and one window only: the keypad has
     * no notion of a second sum going on somewhere else, and a calculator opened twice
     * would be two calculators disagreeing about what is in memory.
     */
    private fun showCalculatorDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.calculator")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.calculator"

        val calculator = rocks.gorjan.gokixp.apps.calculator.CalculatorApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        )

        val view = calculator.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_calculator)
        windowsDialog.setTitle("Calculator")
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    private var peopleAppInstance: rocks.gorjan.gokixp.apps.people.PeopleApp? = null

    /**
     * Opens People - the phone's address book, and its phone app.
     *
     * Full-screen and chromeless like Zune and News, and one window only: there is one
     * address book, and a second copy of this open somewhere else would be a second view
     * of it that disagrees with the first the moment either one saves anything.
     *
     * The permission is not demanded here. The app opens either way and each section says
     * what it is missing and offers to ask for it, which is the only honest order to do
     * this in: somebody who opened People to look at their contacts has said nothing yet
     * about the call log.
     */
    private fun showPeopleDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.people")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.people"

        val people = rocks.gorjan.gokixp.apps.people.PeopleApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onRequestPermissions = { permissions ->
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, permissions, PEOPLE_PERMISSION_REQUEST_CODE)
            },
            photoPicker = peoplePhotoPickerLauncher,
            onBecomeDialer = { requestDialerRole() },
            onBecomeMessenger = { requestSmsRole() },
            onNotify = { title, message -> showNotification(title, message) }
        )
        peopleAppInstance = people

        val view = people.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_people)
        windowsDialog.setTitle("People")
        windowsDialog.setOnCloseListener {
            peopleAppInstance = null
            // The wall on Start is the same address book. Anything added, starred or
            // deleted in here has to reach it, and the way out of the app is the moment
            // to say so.
            refreshWP81People(force = true)
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * Opens Welcome, the phone's version of the window the desktop themes show after an
     * update.
     */
    private fun showWelcomeDialogWP81() {
        if (floatingWindowManager.findAndFocusWindow("system.welcome")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.welcome"

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        val welcomeApp = rocks.gorjan.gokixp.apps.welcome.WelcomeApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            versionName = version,
            onOpenLink = { url ->
                if (url.startsWith("mailto:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    } catch (e: Exception) {
                        Log.w("MainActivity", "No mail app for $url", e)
                    }
                } else {
                    openUrlShortcut(url)
                }
            },
            loadReleaseNotes = { onReady -> fetchWP81ReleaseNotes(onReady) }
        )

        val view = welcomeApp.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_welcome)
        windowsDialog.setTitle("Welcome")
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * The release notes, from the same GitHub releases the desktop welcome reads.
     *
     * One list of what changed, fetched rather than bundled, so it is never a build behind
     * what is actually out.
     */
    private fun fetchWP81ReleaseNotes(onReady: (String) -> Unit) {
        Thread {
            val text = try {
                val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    "Could not reach GitHub for the release notes."
                } else {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val releases = Gson().fromJson(response, com.google.gson.JsonArray::class.java)
                    if (releases == null || releases.size() == 0) {
                        "No release notes yet."
                    } else {
                        buildString {
                            for (i in 0 until releases.size()) {
                                val release = releases[i].asJsonObject
                                val name = release.get("name")?.asString
                                    ?: release.get("tag_name")?.asString ?: "Unknown version"
                                val body = release.get("body")?.asString.orEmpty()
                                append(name).append("\n")
                                if (body.isNotEmpty()) append(body).append("\n")
                                if (i < releases.size() - 1) append("\n")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not read the release notes", e)
                "Could not load the release notes."
            }
            runOnUiThread { onReady(text) }
        }.start()
    }

    /**
     * Turns a full-screen program in the way the shell's own pages turn.
     *
     * These are pages as far as the user is concerned - they fill the screen, they are
     * reached from Start and left with back - so they arrive the same way the settings and
     * folder pages do rather than simply being there. Deferred a frame: the window sizes
     * itself on the next pass, and a turn measured against a view with no height yet
     * pivots around the wrong place.
     */
    private fun turnWP81PageIn(view: View) {
        if (wp81Shell == null) return
        view.post { rocks.gorjan.gokixp.wp81.MetroPageTransition(view).playIn() }
    }

    private var metroIEAppInstance: rocks.gorjan.gokixp.apps.iexplore.MetroIEApp? = null

    /**
     * Opens the phone's Internet Explorer.
     *
     * Full-screen and chromeless like Zune and News. One window only: a phone browser is a
     * place rather than a document, so a second address arriving while it is open is a
     * navigation in the browser that is already there, not another copy of it.
     */
    private fun showMetroIEDialog(initialUrl: String? = null, fromAnotherApp: Boolean = false) {
        val open = floatingWindowManager.findWindowByIdentifier("system.internet_explorer")
        if (open != null) {
            floatingWindowManager.findAndFocusWindow("system.internet_explorer")
            if (initialUrl != null) {
                metroIEAppInstance?.navigateToUrl(initialUrl, fromAnotherApp)
            }
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.internet_explorer"

        val ieApp = rocks.gorjan.gokixp.apps.iexplore.MetroIEApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onShowNotification = { title, message, onTap ->
                showNotification(title, message, onTap)
            },
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) },
            onReturnToLinkCaller = { returnToLinkCaller() },
            // The last tab closed. A browser with nothing open in it is nothing to look
            // at, so the window goes the same way it goes when back runs out.
            onRequestClose = { windowsDialog.closeWindow() }
        )
        metroIEAppInstance = ieApp

        val ieView = ieApp.createView(initialUrl, fromAnotherApp)
        windowsDialog.setContentView(ieView)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_ie)
        windowsDialog.setTitle("Internet Explorer")
        windowsDialog.setOnCloseListener {
            ieApp.cleanup()
            metroIEAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(ieView)
    }

    /**
     * Opens the News reader.
     *
     * Full-screen and chromeless like Zune - it is a phone app, and the panorama it is
     * built on needs the screen to itself.
     *
     * [openAt] is the story the tile was showing when it was tapped, where it was opened
     * that way: the reader runs down to it and marks it rather than landing at the top of
     * a page of fifty with the tapped headline somewhere on it. Nothing from the app list
     * or the taskbar, which is a request for the news rather than for one story.
     */
    private fun showNewsDialog(openAt: rocks.gorjan.gokixp.wp81.NewsStory? = null) {
        if (floatingWindowManager.findAndFocusWindow("system.news")) {
            // Already open, and tapping the tile again is still a request for the story on
            // its face - the reader it brings forward goes to it as a fresh one would.
            openAt?.let { newsAppInstance?.reveal(it) }
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.news"

        val newsApp = rocks.gorjan.gokixp.apps.news.NewsApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            feed = wp81NewsFeed,
            onOpenStory = { story ->
                if (story.link.isBlank()) showNotification("News", "That story has no link")
                else openUrlShortcut(story.link)
            },
            onRefresh = {
                wp81NewsFeed.refreshIfStale(
                    themeManager.getWP81NewsFeeds().toList().sorted(), force = true)
            },
            enabledFeeds = { themeManager.getWP81NewsFeeds() },
            onFeedsChanged = { ids ->
                themeManager.setWP81NewsFeeds(ids)
                // Forced: the answer has changed, whatever the last fetch was and whenever
                // it happened. The tile is told straight away too, so it says what it is
                // doing rather than sitting on the old stories until the new ones land.
                refreshWP81News()
                refreshWP81NewsFeeds(force = true)
            }
        )
        newsAppInstance = newsApp

        val newsView = newsApp.createView()
        windowsDialog.setContentView(newsView)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_news)
        windowsDialog.setTitle("News")
        windowsDialog.setOnCloseListener { newsAppInstance = null }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(newsView)
        // After the window is up, so the page it scrolls is one that has been laid out.
        openAt?.let { newsApp.reveal(it) }

        // Asked for on the way in, whatever the tile has been doing: opening a reader is a
        // request for what is current, and forced so it does not sit on half-hour-old
        // stories because the tile happened to fetch them recently.
        wp81NewsFeed.refreshIfStale(
            themeManager.getWP81NewsFeeds().toList().sorted(), force = true)
    }

    /**
     * Opens Alarms - which is the phone's stopwatch and countdown as well.
     *
     * Full-screen and chromeless like Zune, News and People: it is a phone app, and the
     * panorama it is built on needs the screen to itself. One window only, and an open one
     * is refreshed rather than replaced - the alarms are a single list, and a second view
     * of it would disagree with the first the moment either switched one off.
     */
    private fun showAlarmsDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.alarms")) {
            // It may have been sitting behind something for a while, and an alarm can have
            // gone off, snoozed itself or turned itself off in the meantime.
            alarmsAppInstance?.refresh()
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.alarms"

        val alarms = rocks.gorjan.gokixp.apps.alarms.AlarmsApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onNotify = { title, message -> showNotification(title, message) }
        )
        alarmsAppInstance = alarms

        val view = alarms.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_clock)
        windowsDialog.setTitle("Alarms")
        windowsDialog.setOnCloseListener {
            // The redraw and any sound preview stop with the window. The alarms themselves
            // deliberately do not: they are the point of the app, and they are not this
            // window's to end.
            alarms.cleanup()
            alarmsAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * Opens Weather.
     *
     * Full-screen and chromeless like Zune, News, People and Alarms: it is a phone app,
     * and the panorama it is built on needs the screen to itself. One window only, and an
     * open one is rebound rather than replaced - there is one forecast on the phone, and a
     * second view of it would disagree with the first the moment either refreshed.
     *
     * The permission is not demanded on the way in. The app opens either way and says on
     * the page itself that the phone has not been allowed to give its position, with the
     * offer to allow it - somebody who has pinned Lisbon has said nothing about wanting
     * to be located, and would be asked for nothing.
     */
    private fun showWeatherDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.weather")) {
            // It may have been behind something for a while, and the hourly update - or
            // the tile's own tick - can have replaced the forecast underneath it.
            weatherAppInstance?.bind()
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.weather"

        val weather = rocks.gorjan.gokixp.apps.weather.WeatherApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onNotify = { title, message -> showNotification(title, message) },
            onAskForLocation = { handleWeatherTempRefresh() },
            // A forecast fetched in here is the phone's forecast, so the wall behind the
            // window is brought up to date with it rather than left until the next tick.
            onWeatherChanged = { refreshWP81Weather() }
        )
        weatherAppInstance = weather

        val view = weather.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_weather)
        windowsDialog.setTitle("Weather")
        windowsDialog.setOnCloseListener {
            weather.cleanup()
            weatherAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * Opens Files.
     *
     * Full-screen and chromeless like the rest of the phone's own programs. One window
     * only: there is one filesystem, and a second view of it would be showing a folder
     * that the first one had just emptied.
     */
    private fun showFilesDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.files")) {
            // It may have been sitting behind something while a download landed or a
            // photo was taken, and the folder it is standing in has moved on without it.
            metroFilesAppInstance?.refresh()
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.files"

        val files = rocks.gorjan.gokixp.apps.files.MetroFilesApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onOpen = { file -> openFileFromFiles(file) },
            onNotify = { title, message -> showNotification(title, message) }
        )
        metroFilesAppInstance = files

        val view = files.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_files)
        windowsDialog.setTitle("Files")
        windowsDialog.setOnCloseListener {
            files.cleanup()
            metroFilesAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * A file tapped in Files, handed to whatever opens that kind of file.
     *
     * Out to the phone rather than into one of this launcher's own windows, and
     * deliberately: Windows Phone's Files app had no viewers of its own either - it handed
     * a picture to Photos and a song to Music and got out of the way. The windows that
     * could take one here are Vista-framed programs from the desktop themes, and opening a
     * photograph in one of those from underneath the phone shell would be the anachronism
     * that everything else in this shell is arranged to avoid.
     */
    private fun openFileFromFiles(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val type = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, type)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The chooser rather than the intent itself: a file manager is exactly where
            // somebody wants to say which app opens this one, and a default set from a
            // tap in some other app months ago is not that answer.
            val chooser = Intent.createChooser(view, "Open with")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (e: Exception) {
            // Commonest on a memory card: the provider is declared over the phone's own
            // storage, and a card is a volume it does not cover.
            Log.w("MainActivity", "Could not open ${file.name}", e)
            showNotification("Files", "Nothing on this phone opens ${file.name}")
        }
    }

    /**
     * Opens Alarms because something outside the launcher asked for it.
     *
     * The status bar's alarm icon is the one that does: tapping it goes to whichever app
     * set the next alarm, and on this phone that is this one. See AlarmScheduler, which
     * hands the system this intent along with every alarm it books.
     */
    private fun handleAlarmsIntent(intent: Intent?) {
        if (intent?.action != rocks.gorjan.gokixp.apps.alarms.AlarmScheduler.ACTION_SHOW_ALARMS) return
        intent.action = Intent.ACTION_MAIN
        showAlarmsDialog()
    }

    /**
     * Opens Weather because a rain warning in the shade was tapped.
     *
     * The action is cleared on the way through for the same reason the alarms one is: the
     * intent outlives the tap, and a launcher brought to the front again later would
     * otherwise open the app a second time on an intent nobody had just acted on.
     */
    private fun handleWeatherIntent(intent: Intent?) {
        if (intent?.action != rocks.gorjan.gokixp.apps.weather.RainNotifier.ACTION_SHOW_WEATHER) return
        intent.action = Intent.ACTION_MAIN
        showWeatherDialog()
    }






    /**
     * Shows the welcome screen once per app version
     */
    private fun showWelcomeScreenIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Get current app version
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }

        // Check if welcome was already shown for this version
        val shownForVersion = prefs.getString(KEY_SHOWN_WELCOME_FOR_VERSION, null)

        if (shownForVersion != currentVersion) {
            // The phone's own welcome. A Vista dialog with a picture and two buttons over
            // a Start screen would be a window from another operating system, which is
            // why this branched before; there is only the one shell to greet now.
            showWelcomeDialogWP81()


            // Save that we've shown it for this version
            prefs.edit { putString(KEY_SHOWN_WELCOME_FOR_VERSION, currentVersion) }
        }
    }


    /**
     * The bundled wallpapers, by name - and by picture only where one is going to be drawn.
     *
     * [previewPx] is the longest edge the caller is going to draw one at; zero decodes
     * nothing at all. This used to decode every asset at full resolution unconditionally,
     * for both callers. Seventy-two wallpapers between 800x600 and 1290x2796 come to some
     * hundreds of megabytes, and the XP picker - which draws none of them, it is a list of
     * names - paid it in full every time it opened. It was the largest thing in the heap.
     */
    private fun loadWallpapers(previewPx: Int = 0): List<WallpaperItem> {
        val wallpapers = mutableListOf<WallpaperItem>()

        // Load all wallpapers from assets in alphabetical order
        try {
            val assetManager = assets
            val wallpaperFiles = (assetManager.list("wallpapers") ?: arrayOf()).sorted()

            for (fileName in wallpaperFiles) {
                if (fileName.matches(".*\\.(png|jpg|jpeg|webp)$".toRegex(RegexOption.IGNORE_CASE)) && fileName != "README.txt") {
                    val filePath = "wallpapers/$fileName"
                    wallpapers.add(WallpaperItem(
                        name = fileName.substringBeforeLast("."),
                        drawable = if (previewPx > 0) loadWallpaperPreview(filePath, previewPx) else null,
                        isCurrent = false,
                        filePath = filePath,
                        isBuiltIn = false
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to load wallpapers", e)
        }

        return wallpapers
    }

    /**
     * One bundled wallpaper, decoded small enough for a preview and no smaller.
     *
     * Every surface that shows these shows them tiny - the XP picker's 138x102dp monitor,
     * the phone settings page's strip of squares - so they are sampled down on the way in
     * rather than decoded whole and scaled by the draw. A wallpaper at 1290x2796 is 14MB
     * held to fill a thumbnail.
     */
    private fun loadWallpaperPreview(
        path: String,
        maxPx: Int = WALLPAPER_PREVIEW_PX
    ): Drawable? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, maxPx, maxPx)
        }
        val decoded = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
        // Sampling only halves, so a tall wallpaper still lands well above the square it is
        // going in - 1344x2992 asked down to 252 comes back 336x748, a megabyte apiece and
        // seventy of them in the phone's strip. Scaled the rest of the way and the
        // intermediate dropped, each one costs what it draws.
        decoded?.let { bitmap ->
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxPx) return@let bitmap
            val scale = maxPx / longest.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        }?.toDrawable(resources)
    } catch (e: Exception) {
        Log.w("MainActivity", "Failed to load wallpaper preview: $path", e)
        null
    }

    private fun applyCustomWallpaper(wallpaperItem: WallpaperItem) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

        // All wallpapers are now from assets
        wallpaperItem.filePath?.let { filePath ->
            prefs.edit {
                putString(pathKey, filePath)
                // Clear URI when setting asset wallpaper
                remove(uriKey)
            }
            applyCustomWallpaperFromAssets(filePath)
        }
    }
    


    private fun applyCustomWallpaperFromAssets(filePath: String) {
        try {
            val inputStream = assets.open(filePath)
            val drawable = Drawable.createFromStream(inputStream, filePath)
            inputStream.close()

            if (drawable != null) {
                applyWallpaperDrawable(drawable)
                Log.d("MainActivity", "Applied custom wallpaper: $filePath")
            } else {
                throw Exception("Failed to create drawable from asset")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to apply custom wallpaper: $filePath", e)
        }
    }

    /**
     * Pans an ImageView's image horizontally using a focusX in [0,1] (0.5 == CENTER_CROP).
     * Uses MATRIX scaleType and clamps so the image always covers the view.
     */
    private fun applyWallpaperFocusXToImageView(imageView: ImageView, focusX: Float) {
        val drawable = imageView.drawable ?: return
        val imgWidth = drawable.intrinsicWidth.toFloat()
        val imgHeight = drawable.intrinsicHeight.toFloat()
        if (imgWidth <= 0f || imgHeight <= 0f) return

        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            imageView.post { applyWallpaperFocusXToImageView(imageView, focusX) }
            return
        }

        val scale = maxOf(viewWidth / imgWidth, viewHeight / imgHeight)
        val scaledImgWidth = imgWidth * scale
        val scaledImgHeight = imgHeight * scale

        val clampedFocusX = focusX.coerceIn(0f, 1f)
        var translateX = viewWidth / 2f - clampedFocusX * scaledImgWidth
        val minTranslateX = minOf(viewWidth - scaledImgWidth, 0f)
        translateX = translateX.coerceIn(minTranslateX, 0f)
        val translateY = (viewHeight - scaledImgHeight) / 2f

        val matrix = android.graphics.Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(translateX, translateY)

        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
    }

    private fun getWallpaperImageView(): ImageView? {
        return findViewById<RelativeLayout>(R.id.main_background)?.findViewWithTag<ImageView>("wallpaper")
    }

    /**
     * Starts (or restarts) the wallpaper slide animation if the setting is enabled.
     * The wallpaper slowly pans from X offset 0 to the max X offset and back, looping.
     * The configured duration covers the whole 0 -> max -> 0 cycle.
     */
    private fun startWallpaperSlideIfEnabled() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SLIDE_WALLPAPER_ENABLED, false)) return

        val wallpaperImageView = getWallpaperImageView() ?: return
        val drawable = wallpaperImageView.drawable ?: return
        val imgWidth = drawable.intrinsicWidth.toFloat()
        val imgHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = wallpaperImageView.width.toFloat()
        val viewHeight = wallpaperImageView.height.toFloat()

        // Cancel any running loop (don't restore the manual offset; we're about to drive it).
        wallpaperSlideRunnable?.let { wallpaperImageView.removeCallbacks(it) }
        wallpaperSlideRunnable = null

        // Wait until the view is laid out and the drawable has real dimensions.
        if (imgWidth <= 0f || imgHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) {
            wallpaperImageView.post { startWallpaperSlideIfEnabled() }
            return
        }

        // The image is scaled to cover the view (center-crop). Work out exactly how many pixels
        // of horizontal slack there are: that's the full distance we can pan.
        val scale = maxOf(viewWidth / imgWidth, viewHeight / imgHeight)
        val scaledImgWidth = imgWidth * scale
        val scaledImgHeight = imgHeight * scale
        val panRangePx = scaledImgWidth - viewWidth // >= 0; total horizontal travel
        val translateY = (viewHeight - scaledImgHeight) / 2f

        Log.d("WPSLIDE", "START img=${imgWidth}x${imgHeight} view=${viewWidth}x${viewHeight} scale=$scale scaledW=$scaledImgWidth panRangePx=$panRangePx")

        // Nothing to slide if the image isn't wider than the view.
        if (panRangePx < 1f) {
            applyWallpaperFocusXToImageView(wallpaperImageView, 0.5f)
            return
        }

        val durationSeconds = prefs.getSafeInt(KEY_SLIDE_WALLPAPER_DURATION, DEFAULT_SLIDE_WALLPAPER_DURATION)
        // durationSeconds is the ONE-WAY time (left edge -> right edge); round trip is 2x.
        val cycleMs = durationSeconds * 2 * 1000L
        // Easing should total ~2s per leg: 1s ramping up + 1s ramping down, with the rest of the
        // leg at constant speed. The ramp covers (1s / leg duration) of each end.
        val rampFraction = (1f / durationSeconds).coerceIn(0.01f, 0.5f)

        // Drive from REAL elapsed time via a per-frame Choreographer callback (postOnAnimation),
        // which ignores the system "Animator duration scale" developer setting, so the configured
        // duration is honored exactly. Translate X goes 0 -> -panRangePx -> 0, eased in/out.
        // Offset the start so we resume from where the slide last stopped (e.g. after backgrounding).
        val startTime = android.os.SystemClock.uptimeMillis() - (wallpaperSlidePositionMs % cycleMs)
        var frameCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (wallpaperSlideRunnable !== this) return // superseded/stopped
                val elapsed = (android.os.SystemClock.uptimeMillis() - startTime) % cycleMs
                wallpaperSlidePositionMs = elapsed // remember where we are so we can resume later
                val phase = elapsed.toFloat() / cycleMs.toFloat()
                // Linear progress of the current leg: 0->1 going out, 1->0 coming back.
                val legProgress = if (phase < 0.5f) phase * 2f else 2f - phase * 2f
                // Ease in/out so it accelerates from and decelerates to rest at each end (~1s each),
                // with a constant-speed glide through the middle.
                val eased = easeWithRamp(legProgress, rampFraction)
                val translateX = -panRangePx * eased

                val matrix = android.graphics.Matrix()
                matrix.setScale(scale, scale)
                matrix.postTranslate(translateX, translateY)
                wallpaperImageView.scaleType = ImageView.ScaleType.MATRIX
                wallpaperImageView.imageMatrix = matrix

                if (frameCount % 30 == 0) {
                    Log.d("WPSLIDE", "elapsedMs=$elapsed legProgress=$legProgress eased=$eased translateX=$translateX")
                }
                frameCount++
                wallpaperImageView.postOnAnimation(this)
            }
        }
        wallpaperSlideRunnable = runnable
        wallpaperImageView.postOnAnimation(runnable)
    }

    /**
     * Eases progress t in [0,1] -> [0,1] with smooth (cosine) acceleration over the first [ramp]
     * fraction, a constant-speed glide through the middle, and symmetric deceleration over the
     * last [ramp] fraction. Velocity starts and ends at zero. ramp=0.5 eases the whole leg;
     * smaller values make the ease shorter (0.25 = half as long, longer constant-speed middle).
     */
    private fun easeWithRamp(t: Float, ramp: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val r = ramp.coerceIn(0.001f, 0.5f)
        val vMax = 1f / (1f - r) // peak speed so total travel is exactly 1
        val pi = Math.PI.toFloat()
        return when {
            x < r -> vMax / 2f * (x - (r / pi) * kotlin.math.sin(pi * x / r))
            x <= 1f - r -> vMax * r / 2f + vMax * (x - r)
            else -> {
                val s = 1f - x
                1f - vMax / 2f * (s - (r / pi) * kotlin.math.sin(pi * s / r))
            }
        }
    }


    private fun applyWallpaperDrawable(drawable: Drawable, uri: Uri? = null) {
        // The phone shell paints its own Start background and never a desktop wallpaper.
        if (true) return

        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)

        // Create or find existing wallpaper ImageView
        var wallpaperImageView = mainBackground.findViewWithTag<ImageView>("wallpaper")

        if (wallpaperImageView == null) {
            // Create new ImageView for wallpaper
            wallpaperImageView = ImageView(this)
            wallpaperImageView.tag = "wallpaper"
            wallpaperImageView.scaleType = ImageView.ScaleType.MATRIX
            wallpaperImageView.adjustViewBounds = false

            // The wallpaper uses a MATRIX scale type whose crop/pan is computed from the view's
            // size. When that size changes (e.g. an orientation change), recompute the matrix so
            // the wallpaper re-fits the new dimensions instead of keeping a stale transform.
            wallpaperImageView.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
                if (sizeChanged) {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_SLIDE_WALLPAPER_ENABLED, false)) {
                        // Restart the slide so it recomputes its pan range for the new size.
                        startWallpaperSlideIfEnabled()
                    } else {
                        val fx = prefs.getSafeFloat(getCurrentThemeWallpaperFocusXKey(), 0.5f)
                        applyWallpaperFocusXToImageView(view as ImageView, fx)
                    }
                }
            }

            // Add as first child (behind everything else)
            val layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            mainBackground.addView(wallpaperImageView, 0, layoutParams)
        }

        // Set the wallpaper image
        wallpaperImageView.setImageDrawable(drawable)

        // Apply the saved horizontal focus offset for the current theme
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val focusX = prefs.getSafeFloat(getCurrentThemeWallpaperFocusXKey(), 0.5f)
        applyWallpaperFocusXToImageView(wallpaperImageView, focusX)

        // Resume sliding the wallpaper if the setting is enabled (new ImageView/drawable).
        startWallpaperSlideIfEnabled()

        // Remove any background from the RelativeLayout
        mainBackground.background = null

        // If URI is provided, save it to SharedPreferences
        if (uri != null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

            // Release any existing persistent URI permission for this theme
            val oldUri = prefs.getString(uriKey, null)
            if (oldUri != null) {
                try {
                    val oldUriParsed = oldUri.toUri()
                    contentResolver.releasePersistableUriPermission(
                        oldUriParsed,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d("MainActivity", "Released old persistent URI permission for: $oldUri")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Could not release old URI permission for: $oldUri", e)
                }
            }

            // Save the URI as current wallpaper for the current theme
            prefs.edit {
                putString(uriKey, uri.toString())
                // Clear path when setting custom URI
                remove(pathKey)
            }
            Log.d("MainActivity", "Saved custom wallpaper URI: $uri")
        }
    }


    /**
     * Reload wallpaper bitmap when app returns to foreground
     */
    private fun reloadWallpaperBitmap() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val (pathKey, uriKey) = getCurrentThemeWallpaperKeys()

            // Check if we have a custom wallpaper URI
            val uriString = prefs.getString(uriKey, null)
            if (uriString != null) {
                val uri = uriString.toUri()

                // Reload with downsampling
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                val displayMetrics = resources.displayMetrics
                val targetWidth = minOf(displayMetrics.widthPixels, 1080)
                val targetHeight = minOf(displayMetrics.heightPixels, 1920)

                options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565

                val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                if (bitmap != null) {
                    val drawable = bitmap.toDrawable(resources)
                    applyWallpaperDrawable(drawable)
                    Log.d("MainActivity", "Reloaded wallpaper bitmap: ${bitmap.width}x${bitmap.height}, ${bitmap.byteCount / 1024}KB")
                }
            } else {
                // Check for built-in wallpaper path
                val path = prefs.getString(pathKey, null)
                if (path != null) {
                    try {
                        val drawable = Drawable.createFromStream(assets.open(path), path)
                        if (drawable != null) {
                            applyWallpaperDrawable(drawable)
                            Log.d("MainActivity", "Reloaded built-in wallpaper: $path")
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Could not reload built-in wallpaper: $path", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Error reloading wallpaper bitmap", e)
        }
    }

    /**
     * Calculate sample size for bitmap downsampling to reduce memory usage
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        Log.d("MainActivity", "Image downsampling: ${width}x${height} -> target ${reqWidth}x${reqHeight}, sample size: $inSampleSize")
        return inSampleSize
    }

    private fun handleSelectedImage(uri: Uri) {
        try {
            // First, decode with inJustDecodeBounds=true to check dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            Log.d("MainActivity", "Original wallpaper size: ${options.outWidth}x${options.outHeight}")

            // Calculate target size based on screen dimensions (limit to 1080p for memory efficiency)
            val displayMetrics = resources.displayMetrics
            val targetWidth = minOf(displayMetrics.widthPixels, 1080)
            val targetHeight = minOf(displayMetrics.heightPixels, 1920)

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)

            // Decode bitmap with inSampleSize set and use RGB_565 for non-transparent images (50% memory savings)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 50% memory vs ARGB_8888

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (bitmap != null) {
                Log.d("MainActivity", "Downsampled wallpaper size: ${bitmap.width}x${bitmap.height}, memory: ${bitmap.byteCount / 1024}KB")

                val drawable = bitmap.toDrawable(resources)

                // Take persistent URI permission to survive app updates
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    Log.d("MainActivity", "Took persistent URI permission for: $uri")
                } catch (e: SecurityException) {
                    Log.w("MainActivity", "Could not take persistent URI permission for: $uri", e)
                    // Continue anyway, the URI might still work temporarily
                }

                // Show wallpaper target selection dialog FIRST, before applying anything
                // The dialog will handle applying the wallpaper based on user selection
                showWallpaperTargetDialog(null, uri, drawable)
                Log.d("MainActivity", "Showing wallpaper target dialog for custom wallpaper: $uri")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load custom wallpaper from device", e)
        }
    }

    private fun applyThemeFontsToDialog(contentView: View) {
        // One family, everywhere. FontManager used to pick between Micross, Tahoma and
        // Segoe for four themes; there is one theme, and it is set in Segoe.
        val fontResId = R.font.segoe_wp_family

        val typeface = try {
            androidx.core.content.res.ResourcesCompat.getFont(this, fontResId)
        } catch (e: Exception) {
            null
        }

        // Find TextViews by traversing the view hierarchy
        applyFontToAllTextViews(contentView, typeface)
    }

    private fun applyFontToAllTextViews(parent: View, typeface: android.graphics.Typeface?) {
        if (parent is TextView) {
            parent.typeface = typeface
        } else if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                applyFontToAllTextViews(parent.getChildAt(i), typeface)
            }
        }
    }






    private fun playStartupSound() {

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // The phone's own jingle. This used to fork four ways, and a Start screen coming
        // up to the XP chime was the one moment the illusion broke.
        playSound(R.raw.startup_8)
    }


    
    


    
    
    


    
    private fun enableEdgeToEdge() {
        try {
            // Extend behind system bars but keep them visible
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Don't hide the system bars, just allow content to draw behind them.
            // Keep the navigation bar transparent (and disable the system's translucent
            // scrim) so the black backdrop we draw behind it shows cleanly. This matters for
            // button/3-button navigation, where setupNavigationBarInsets() pads the content up
            // to the bar and the space behind the buttons is filled by root_container's black.
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error enabling edge-to-edge", e)
        }
    }

    /**
     * Inset the entire launcher so it stops at the system navigation bar instead of drawing
     * behind it. This matters for button/3-button (and 2-button) navigation, whose bar is
     * either a tall strip at the bottom (portrait) or a strip down one side (landscape).
     *
     * The base layout already reserves a 30dp strip at the bottom (the gesture bar) for the
     * navigation area, so we only pad the root by the *extra* nav bar height beyond that: with
     * gesture navigation the bottom inset is <= 30dp, so nothing changes; with a taller button
     * nav bar the content is lifted to clear it. Left/right insets (a side nav bar in landscape)
     * are applied in full. The space freed up behind the bar is filled by root_container's black
     * background, matching the app's black gesture-bar look.
     */
    private fun setupNavigationBarInsets() {
        val root = findViewById<View>(R.id.root_container)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val statusBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())

            // The desktop themes leave the top alone - nothing sits up there but wallpaper -
            // and already reserve 30dp at the bottom via the gesture bar / taskbar margins.
            //
            // The WP8.1 shell has neither: its status bar sits hard against the top edge and
            // its navigation bar against the bottom. Padding root_container (rather than the
            // shell itself) means floating windows, which are siblings of the shell, clear
            // the system bars too.
            val isPhoneShell = true
            val reservedBottomPx =
                if (isPhoneShell) 0
                else (30 * resources.displayMetrics.density).toInt()
            val padLeft = navBars.left
            val padRight = navBars.right
            val padBottom = maxOf(0, navBars.bottom - reservedBottomPx)
            val padTop = if (isPhoneShell) statusBars.top else 0

            if (view.paddingLeft != padLeft || view.paddingRight != padRight ||
                view.paddingBottom != padBottom || view.paddingTop != padTop) {
                view.setPadding(padLeft, padTop, padRight, padBottom)
            }

            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    fun playClickSound() = playSound(R.raw.click)



    fun playEmailSound() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val playEmailSound = prefs.getBoolean(KEY_PLAY_EMAIL_SOUND, true)
        if (playEmailSound) {
            playSound(R.raw.youve_got_mail)
        }
    }


    /**
     * Adds a row to the icon list, which is what the Start screen is built from.
     *
     * It used to build a view for the new icon and place it on the desktop as well. There
     * is no desktop, and a tile is not created here either: the wall holds what its owner
     * pinned, so the caller refreshes it if the new row belongs on it.
     */
    private fun addDesktopIcon(
        appInfo: AppInfo,
        x: Float = 100f,
        y: Float = 100f,
        iconTypeOverride: IconType? = null,
        targetUrl: String? = null
    ) {
        val iconToUse = getAppIcon(appInfo.packageName) ?: appInfo.icon
        val iconType = iconTypeOverride ?: when (appInfo.packageName) {
            "recycle.bin" -> IconType.RECYCLE_BIN
            "my.computer" -> IconType.MY_COMPUTER
            else -> IconType.APP
        }

        desktopIcons.add(
            DesktopIcon(
                name = appInfo.name,
                packageName = appInfo.packageName,
                icon = iconToUse,
                x = x,
                y = y,
                type = iconType,
                targetUrl = targetUrl
            )
        )
        saveDesktopIcons()
    }

    /**
     * Handles an incoming ACTION_SEND intent carrying shared text/URL.
     * Called from onCreate (cold start) and onNewIntent (warm start).
     */
    /**
     * A link tapped somewhere else on the phone, handed to the launcher to open.
     *
     * Straight into the launcher's own browser rather than through [openUrlShortcut]: that
     * one asks whether links should go to Internet Explorer or to the phone's default
     * browser, and neither question applies here - the phone has already decided the
     * launcher *is* the browser, and sending the link back out to be resolved would hand it
     * to whatever answers next, or to this activity a second time.
     *
     * The data is cleared once it has been read so a rotation or a return to the launcher
     * does not open the same page again - the same reason [handleSharedUrlIntent] clears
     * its extra.
     *
     * The app that sent it is written down on the way past, because back out of the page
     * is a way back to that app. See [returnToLinkCaller].
     */
    /**
     * A number handed to the launcher from somewhere else on the phone.
     *
     * Once People holds the phone role this is where every DIAL lands - a number tapped in
     * the browser, in a message, on a web page - and the right answer to all of them is the
     * same: open People's keypad with it already typed, one tap short of the call. Never
     * dialled outright. A DIAL intent is a request to *offer* a number, and an app that
     * rings it the moment it arrives has upgraded somebody's tap into a phone call.
     *
     * The data is cleared once read, so returning to the launcher later does not open the
     * keypad again - the same reason [handleViewUrlIntent] clears its own.
     */
    /**
     * Somebody asking to see the call log.
     *
     * Two ways in, and they mean the same thing. [ACTION_SHOW_CALL_HISTORY] is this app's
     * own missed-call notification being tapped, which is explicit and always lands here.
     * The other is `ACTION_VIEW` on the call log's mime type, which is what everything else
     * on the phone sends - and what Telecom's own missed-call notification used to send,
     * to whichever app happened to claim it.
     *
     * Telecom is told the missed calls have been seen on the way past. It is the one thing
     * only the phone app can do about them, and being shown the history is exactly the
     * moment they have been dealt with.
     */
    private fun handleCallHistoryIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val viewingLog = action == Intent.ACTION_VIEW &&
            intent.type == android.provider.CallLog.Calls.CONTENT_TYPE
        if (action != ACTION_SHOW_CALL_HISTORY && !viewingLog) return
        intent.action = Intent.ACTION_MAIN
        intent.type = null

        rocks.gorjan.gokixp.apps.people.MissedCallReceiver.clear(this)
        try {
            (getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager)
                ?.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Only the default phone app may say this, and it is not always this one.
            Log.d("MainActivity", "Could not clear the missed calls", e)
        }

        showPeopleDialog()
        peopleAppInstance?.showHistory()
    }

    private fun handleDialIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_DIAL &&
            action != Intent.ACTION_CALL_BUTTON &&
            !(action == Intent.ACTION_VIEW && intent.data?.scheme == "tel")
        ) return

        val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.trim()
        intent.data = null
        intent.action = Intent.ACTION_MAIN

        showPeopleDialog()
        // After the window, because the keypad is a page inside it and there has to be
        // something for it to be a page over.
        peopleAppInstance?.showDialer(number)
    }

    /**
     * Somebody asking to send or read a message.
     *
     * Four ways in and one answer. This app's own notifications ask by name; an `sms:` or
     * `smsto:` link followed anywhere on the phone arrives as `SENDTO`, which is the filter
     * that makes People eligible to be the messaging app in the first place; a picture
     * message it cannot show offers the section as somewhere to go instead.
     *
     * A number in the address, words in the body, both optional: `smsto:` with nothing
     * after it is a request to open messages, which is exactly what a share sheet sends
     * when somebody picks the messaging app before picking a person.
     *
     * The intent is emptied once read, so returning to the launcher later does not reopen
     * the same conversation - the same reason [handleDialIntent] clears its own.
     */
    private fun handleMessageIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val data = intent.data
        val addressed = data?.scheme?.lowercase() in MESSAGE_SCHEMES
        val asked = action == ACTION_SHOW_MESSAGES || action == ACTION_SHOW_MESSAGE_THREAD
        val sending = (action == Intent.ACTION_SENDTO || action == Intent.ACTION_VIEW) &&
            addressed
        if (!asked && !sending) return

        // Several recipients are possible in the address and only the first is taken: this
        // app sends text messages, and a text to several people at once is a multimedia
        // message. See RespondViaMessageService, which has the same rule for the same reason.
        val address = intent.getStringExtra(EXTRA_MESSAGE_ADDRESS)
            ?: data?.schemeSpecificPart
                ?.substringBefore('?')
                ?.split(';', ',')
                ?.firstOrNull()
                ?.let { Uri.decode(it).trim() }
        val draft = intent.getStringExtra(EXTRA_MESSAGE_DRAFT)
            ?: intent.getStringExtra("sms_body")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

        intent.action = Intent.ACTION_MAIN
        intent.data = null
        intent.removeExtra(EXTRA_MESSAGE_ADDRESS)
        intent.removeExtra(EXTRA_MESSAGE_DRAFT)
        intent.removeExtra("sms_body")
        intent.removeExtra(Intent.EXTRA_TEXT)

        showPeopleDialog()
        // After the window, because the conversation is a page inside it and there has to
        // be something for it to be a page over.
        peopleAppInstance?.showMessages(address?.takeIf { it.isNotEmpty() }, draft)
    }

    private fun handleViewUrlIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val scheme = data.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return

        val url = data.toString()
        intent.data = null
        linkCallerPackage = callingAppPackage()
        openBrowserBeforeFirstFrame(url)
    }

    /**
     * The app that started this activity, as far as Android will say.
     *
     * The referrer is what a browser is given in place of a caller: an `android-app://`
     * address naming the package that asked. Nothing is our own doing - a link followed
     * inside the launcher comes back round through the same intent filter - so this
     * launcher is not an answer.
     */
    private fun callingAppPackage(): String? {
        val ref = try {
            referrer
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not read the intent's referrer", e)
            null
        } ?: return null
        if (ref.scheme != "android-app") return null
        return ref.host?.takeIf { it.isNotBlank() && it != packageName }
    }

    /**
     * Gives the screen back to the app whose link the browser has been showing.
     *
     * A link tapped in Reddit puts the reader inside Reddit's errand, and finishing with
     * the page ends the errand: back belongs to Reddit, not to the launcher's own Start
     * screen or to whatever else the browser had open. The launcher cannot simply finish
     * the way an ordinary browser activity would - it is the home screen, and there is
     * always more of it underneath - so the whole task steps aside instead and uncovers
     * the app that was there before it.
     *
     * Where the system will not move the task (there may be nothing behind it, on a cold
     * start that the link itself began), the caller is opened by name, which brings its
     * task forward as it stood rather than starting it over.
     */
    private fun returnToLinkCaller() {
        val caller = linkCallerPackage
        linkCallerPackage = null
        ieWindowOpenedByLink = false

        val moved = try {
            moveTaskToBack(true)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not move the launcher aside", e)
            false
        }
        if (moved) return

        if (caller == null) return
        try {
            val back = packageManager.getLaunchIntentForPackage(caller) ?: return
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(back)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not go back to $caller", e)
        }
    }

    /**
     * Opens the browser in the last moment before the shell is put on screen.
     *
     * Somebody who taps a link in another app asked for a web page. What they were shown was
     * half a second of a Start screen first - the tiles arriving, laying themselves out, and
     * only then the browser over the top of them. The launcher was announcing itself in the
     * middle of somebody else's task.
     *
     * This used to be a half-second delay, and the delay was covering something real: on a
     * cold start the browser cannot simply be opened from `onCreate`, because the window it
     * would open into has not been measured or laid out yet and there is nowhere to put it.
     * Waiting for a fixed number of milliseconds is a guess at when that stops being true,
     * and it has to be a generous guess to be safe on a slow phone - which is precisely why
     * it was long enough to watch.
     *
     * A pre-draw listener asks the exact question instead. It runs after measure and layout
     * and before the frame is painted: the shell is fully built, so the browser has somewhere
     * to go, and nothing has been shown yet, so there is nothing for it to be shown *after*.
     * On a warm start the same code path is simply the next frame.
     *
     * The frame is then skipped rather than drawn, because what it holds is the shell without
     * the browser in it - the very thing this exists to avoid. Adding the browser's window
     * schedules the next traversal by itself; the posted invalidate is a belt on that brace,
     * for the paths that reuse an already-open browser and might not dirty anything.
     */
    private fun openBrowserBeforeFirstFrame(url: String) {
        val root = window?.decorView ?: run {
            showInternetExplorerDialog(url, fromAnotherApp = true)
            return
        }
        root.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    showInternetExplorerDialog(url, fromAnotherApp = true)
                    root.post { root.invalidate() }
                    return false
                }
            }
        )
    }

    /**
     * Whether the phone sends its links here.
     *
     * Asked of the system rather than kept as a setting of our own: the user can change
     * this from Android's own screens at any time, and a checkbox in Display Properties
     * remembering an answer the system has since overruled is a setting that lies.
     */
    private fun isDefaultBrowser(): Boolean = try {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        roles?.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER) == true
    } catch (e: Exception) {
        Log.w("MainActivity", "Could not ask about the browser role", e)
        false
    }

    /**
     * Asks to be made the phone's browser.
     *
     * Android puts the choice to the user itself - it is not ours to make - so this raises
     * the system's own request where there is one to raise, and drops the user at the
     * default-apps screen where there is not: either the role is already held, in which
     * case the request would be refused outright and that screen is where it can be given
     * away again, or the phone has no such role and the list is all there is.
     */
    private fun requestDefaultBrowser() {
        try {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles != null &&
                roles.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) &&
                !roles.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
            ) {
                defaultBrowserLauncher.launch(
                    roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER)
                )
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not ask for the browser role", e)
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            Log.e("MainActivity", "No default-apps screen on this phone", e)
            showNotification("Default browser", "This phone has no default apps screen")
        }
    }

    private fun handleSharedUrlIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(sharedText)
        if (url == null) {
            Log.w("MainActivity", "Shared text contained no usable URL: $sharedText")
            return
        }

        // Clear the extra so the same URL isn't handled again on rotation / re-entry
        intent.removeExtra(Intent.EXTRA_TEXT)

        // Delay so the UI (window manager + desktop container) is ready, mirroring
        // handlePendingPackageAction() which does the same for cold-start actions.
        Handler(Looper.getMainLooper()).postDelayed({
            promptCreateUrlShortcut(url)
        }, 500)
    }

    /**
     * Pulls the first web URL out of shared text (browsers sometimes prepend a title)
     * and ensures it has a scheme. Returns null if there's nothing usable.
     */
    private fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val matcher = android.util.Patterns.WEB_URL.matcher(trimmed)
        val candidate = if (matcher.find()) {
            trimmed.substring(matcher.start(), matcher.end())
        } else {
            trimmed
        }

        return if (candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true)) {
            candidate
        } else {
            "https://$candidate"
        }
    }

    /** Suggests a friendly default name (the host) for a URL shortcut. */
    private fun suggestShortcutName(url: String): String {
        return try {
            val host = android.net.Uri.parse(url).host
            if (host.isNullOrEmpty()) url else host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }
    }

    /** Prompts the user for a name (reusing the rename dialog), then creates the shortcut. */
    private fun promptCreateUrlShortcut(url: String) {
        showRenameDialog(
            title = "Add to Desktop",
            initialText = suggestShortcutName(url),
            hint = "Shortcut name"
        ) { name ->
            createUrlShortcutOnDesktop(name, url)
        }
    }

    /**
     * Saves a URL as a shortcut and pins it to Start.
     *
     * It used to be placed in the first free slot on a desktop grid. A tile has no x and y
     * to be given - the wall packs itself from the order of the list - so the row is added
     * and the Start screen is asked to take it.
     */
    private fun createUrlShortcutOnDesktop(name: String, url: String) {
        val urlIcon = AppCompatResources.getDrawable(this, R.drawable.url_shortcut)!!
        // Unique, stable packageName (like folders) so rename/custom-icon mappings key off it
        val packageName = "url_${System.currentTimeMillis()}"
        val appInfo = AppInfo(name = name, packageName = packageName, icon = urlIcon)

        addDesktopIcon(appInfo, iconTypeOverride = IconType.URL_SHORTCUT, targetUrl = url)
        refreshWP81Tiles()

        showNotification("Shortcut Added", "\"$name\" was pinned to Start")
    }

    /**
     * Opens a URL shortcut's target. Honors the "open in Internet Explorer" setting:
     * when enabled, opens in the built-in IE window; otherwise (the default) opens in
     * the system default browser.
     */
    /**
     * Opens a link, wherever the user has said links should open.
     *
     * The one way in for every address this launcher follows - a shortcut tile, a story in
     * the news reader, the update, a link in Welcome - rather than each of them deciding
     * for itself. Internet Explorer is a program here, and opening one to follow a link
     * meant leaving the user inside a toy browser they did not ask for; it is offered
     * behind a setting instead, and used as the fallback when nothing on the phone will
     * take an http intent at all.
     */
    fun openUrlShortcut(url: String?) {
        val target = url?.trim()
        if (target.isNullOrEmpty()) {
            Log.w("MainActivity", "URL shortcut has no target URL")
            return
        }

        // Ours to open, either because the user asked for that or because the phone has made
        // the launcher its browser - in which case handing the link to the system would
        // only bring it straight back here through the front door.
        if (isOpenUrlsInIeEnabled() || isDefaultBrowser()) {
            showInternetExplorerDialog(target)
            return
        }

        // Default: open in the system default browser
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening URL in default browser: $target", e)
            // Fall back to the built-in browser if no external handler is available
            showInternetExplorerDialog(target)
        }
    }


    
    private fun saveDesktopIcons() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()

        // Convert to serializable data
        val serializedIcons = desktopIcons.map { icon ->
            mapOf(
                "name" to icon.name,
                "packageName" to icon.packageName,
                "x" to icon.x,  // Keep for backwards compatibility
                "y" to icon.y,  // Keep for backwards compatibility
                "id" to icon.id,
                "type" to icon.type.name,
                "parentFolderId" to icon.parentFolderId,
                "portraitGridIndex" to icon.portraitGridIndex,
                "landscapeGridIndex" to icon.landscapeGridIndex,
                "targetUrl" to icon.targetUrl,
                "tileSize" to icon.tileSize,
                "tileIndex" to icon.tileIndex,
                "tileSizeLandscape" to icon.tileSizeLandscape,
                "tileIndexLandscape" to icon.tileIndexLandscape
            )
        }

        val json = gson.toJson(serializedIcons)
        prefs.edit { putString(KEY_DESKTOP_ICONS, json) }
        Log.d("MainActivity", "Saved ${desktopIcons.size} desktop icons with grid indices")
    }
    
    /**
     * Reads the saved icon list, which is what the Start screen is built from.
     *
     * A tile is a desktop icon: the wall is laid out from this list, and each row carries
     * its own tile size and position (see DesktopIcon.tileSize / tileIndex). So the whole
     * of the parsing below stays exactly as it was - what has gone is everything that
     * followed it, which built a view per icon and laid them out on a desktop container
     * this launcher no longer has.
     */
    private fun loadDesktopIcons() {
        desktopIcons.clear()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_DESKTOP_ICONS, null) ?: return


        try {
            val gson = Gson()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val serializedIcons: List<Map<String, Any>> = gson.fromJson(json, type)


            val packageManager = packageManager

            serializedIcons.forEach { iconData ->
                val packageName = iconData["packageName"] as String
                // The player is called Music, as the phone called it, and was called Zune
                // for a while. Only the untouched ones: a name the user typed themselves
                // is theirs to keep.
                val name = (iconData["name"] as String)
                    .let { if (packageName == "system.zune" && it == "Zune") "Music" else it }
                val x = (iconData["x"] as Double).toFloat()
                val y = (iconData["y"] as Double).toFloat()
                val id = iconData["id"] as String
                val parentFolderId = iconData["parentFolderId"] as? String
                val typeStr = iconData["type"] as? String
                val targetUrl = iconData["targetUrl"] as? String

                // Read grid indices (may be null for old data)
                val portraitGridIndex = (iconData["portraitGridIndex"] as? Double)?.toInt()
                val landscapeGridIndex = (iconData["landscapeGridIndex"] as? Double)?.toInt()

                // Windows Phone 8.1 tile placement; absent for icons saved before it existed.
                val tileSize = iconData["tileSize"] as? String
                val tileIndex = (iconData["tileIndex"] as? Double)?.toInt()
                val tileSizeLandscape = iconData["tileSizeLandscape"] as? String
                val tileIndexLandscape = (iconData["tileIndexLandscape"] as? Double)?.toInt()


                val iconType = if (typeStr != null) {
                    try {
                        IconType.valueOf(typeStr)
                    } catch (e: Exception) {
                        when (packageName) {
                            "recycle.bin" -> IconType.RECYCLE_BIN
                            "my.computer" -> IconType.MY_COMPUTER
                            else -> IconType.APP
                        }
                    }
                } else {
                    when (packageName) {
                        "recycle.bin" -> IconType.RECYCLE_BIN
                        "my.computer" -> IconType.MY_COMPUTER
                        else -> IconType.APP
                    }
                }

                try {
                    // A picture is not what makes a row real. An icon dropped here is gone
                    // for good - the next save writes the list without it - and when the one
                    // that could not be drawn is a folder, everything filed inside it is
                    // stranded behind a parent that no longer exists. So a missing image
                    // falls back to another image, and what is genuinely no longer installed
                    // is decided by tidyDesktopIcons, which says so in the log.
                    val icon = try {
                        when (iconType) {
                            IconType.RECYCLE_BIN -> {
                                // Special case for recycle bin - use recycle drawable
                                AppCompatResources.getDrawable(this, R.drawable.recycle)!!
                            }
                            IconType.MY_COMPUTER -> {
                                // Special case for My Computer - use theme-appropriate icon
                                AppCompatResources.getDrawable(this, themeManager.getMyComputerIcon())!!
                            }
                            IconType.FOLDER -> {
                                // Use custom icon if available, otherwise use theme-appropriate folder icon
                                getAppIcon(packageName) ?: run {
                                    // Chrome string: Windows Phone 8.1 needs the Vista folder art.
                                    val selectedTheme = themeManager.chromeThemeString()
                                    AppCompatResources.getDrawable(this, if (selectedTheme == "Windows Classic") R.drawable.folder_98 else if (selectedTheme == "Windows Vista") R.drawable.folder_vista else R.drawable.folder_xp)!!
                                }
                            }
                            IconType.URL_SHORTCUT -> {
                                // URL shortcut: use custom icon if set, otherwise the URL icon
                                getAppIcon(packageName) ?: AppCompatResources.getDrawable(this, R.drawable.url_shortcut)!!
                            }
                            IconType.APP -> {
                                // Check if this is a system app first
                                if (isSystemApp(packageName)) {
                                    // Use custom icon if available, otherwise load from system app list
                                    getAppIcon(packageName) ?: run {
                                        getSystemAppsList().find { it.packageName == packageName }?.icon
                                            ?: AppCompatResources.getDrawable(this, themeManager.getIEIcon())!! // Fallback to IE icon
                                    }
                                } else {
                                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                                    // Use custom icon if available, otherwise fallback to default app icon
                                    getAppIcon(packageName) ?: appInfo.loadIcon(packageManager)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "No artwork for $packageName; using a stand-in", e)
                        fallbackIconFor(iconType)
                    }

                    val desktopIcon = DesktopIcon(
                        name, packageName, icon, x, y, id, iconType, parentFolderId,
                        portraitGridIndex, landscapeGridIndex, targetUrl,
                        tileSize, tileIndex, tileSizeLandscape, tileIndexLandscape)
                    desktopIcons.add(desktopIcon)

                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading desktop icon: $packageName", e)
                }
            }

            Log.d("MainActivity", "=== LOAD COMPLETE ===")
            Log.d("MainActivity", "Total icons in desktopIcons list: ${desktopIcons.size}")
            Log.d("MainActivity", "Icons in folders: ${desktopIcons.count { it.parentFolderId != null }}")
            Log.d("MainActivity", "Icons on desktop: ${desktopIcons.count { it.parentFolderId == null }}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading desktop icons", e)
        }

        // No Recycle Bin or My Computer row is created. Both were desktop furniture; a
        // phone has neither. Rows carried in from the desktop launcher are left in the
        // list so a migrated wall still matches what was there - see launchWP81Tile,
        // where My Computer opens Files and the Recycle Bin does nothing.

        // Whatever is wrong with what was saved is wrong the moment it is read, and every
        // screen is built from this list afterwards - so the one place it is loaded is the
        // place to put it right: nothing filed in a folder that is not there, and nothing
        // left over from an app that is no longer installed.
        tidyDesktopIcons()
    }
    
    /**
     * A picture to stand in for one that could not be found.
     *
     * Every kind gets something of its own except an app, which gets the platform's own
     * "some app" icon - the launcher has no generic app art of its own, and a folder icon
     * on a program would say the wrong thing about it.
     */
    private fun fallbackIconFor(type: IconType): Drawable {
        val chrome = themeManager.chromeThemeString()
        val resource = when (type) {
            IconType.RECYCLE_BIN -> R.drawable.recycle
            IconType.MY_COMPUTER -> themeManager.getMyComputerIcon()
            IconType.FOLDER -> when (chrome) {
                "Windows Classic" -> R.drawable.folder_98
                "Windows Vista" -> R.drawable.folder_vista
                else -> R.drawable.folder_xp
            }
            IconType.URL_SHORTCUT -> R.drawable.url_shortcut
            IconType.APP -> android.R.drawable.sym_def_app_icon
        }
        return AppCompatResources.getDrawable(this, resource)
            ?: android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
    }

    










    
    

    private fun getHiddenApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return try {
            val hiddenAppsString = prefs.getString(KEY_HIDDEN_APPS, "") ?: ""
            if (hiddenAppsString.isEmpty()) emptySet()
            else hiddenAppsString.split(",").filter { it.isNotEmpty() }.toSet()
        } catch (e: ClassCastException) {
            Log.w("MainActivity", "Unexpected hidden apps format, ignoring", e)
            emptySet()
        }
    }

    private fun toggleHiddenApp(packageName: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hidden = getHiddenApps().toMutableSet()

        if (!hidden.remove(packageName)) {
            hidden.add(packageName)
            Log.d("MainActivity", "Hid app: $packageName")
        } else {
            Log.d("MainActivity", "Unhid app: $packageName")
        }

        prefs.edit { putString(KEY_HIDDEN_APPS, hidden.joinToString(",")) }
    }

    private fun isAppHidden(packageName: String): Boolean {
        return getHiddenApps().contains(packageName)
    }

    /** The wall is what shows the icon list now, so refreshing it is refreshing the tiles. */
    private fun refreshDesktopIcons() {
        refreshWP81Tiles()
    }

    /**
     * Setup foldable device detection using WindowManager library
     */
    private fun setupFoldableDeviceDetection() {
        lifecycleScope.launch {
            val windowInfoTracker = WindowInfoTracker.getOrCreate(this@MainActivity)
            windowInfoTracker.windowLayoutInfo(this@MainActivity)
                .collectLatest { info ->
                    val foldingFeature = info.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()

                    val previousState = isFoldableUnfolded

                    if (foldingFeature != null) {
                        // Device has a folding feature (hinge)
                        isFoldableUnfolded = when (foldingFeature.state) {
                            FoldingFeature.State.FLAT -> {
                                // Device is fully unfolded → using internal (main) screen
                                Log.d("MainActivity", "Foldable device detected: FLAT (unfolded)")
                                true
                            }
                            FoldingFeature.State.HALF_OPENED -> {
                                // Device is partially folded (like laptop mode)
                                Log.d("MainActivity", "Foldable device detected: HALF_OPENED")
                                true
                            }
                            else -> {
                                Log.d("MainActivity", "Foldable device detected: ${foldingFeature.state}")
                                false
                            }
                        }
                    } else {
                        // No folding feature detected - this is a regular phone or tablet
                        // Don't override orientation for tablets/large phones
                        // Only actual foldables with a hinge should trigger landscape mode in portrait
                        isFoldableUnfolded = false
                        Log.d("MainActivity", "No folding feature detected - using device orientation")
                    }

                    // If state changed, refresh desktop layout
                    if (previousState != isFoldableUnfolded) {
                        Log.d("MainActivity", "Foldable state changed from $previousState to $isFoldableUnfolded - refreshing desktop")
                        runOnUiThread {
                            refreshDesktopIcons()
                        }
                    }
                }
        }
    }

    /**
     * Get current screen orientation
     * Returns LANDSCAPE if:
     * - Device is in landscape orientation, OR
     * - Device is a foldable/tablet with large screen active
     */
    private fun getCurrentOrientation(): ScreenOrientation {
        // Check if in landscape orientation
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return ScreenOrientation.LANDSCAPE
        }

        // Check if foldable is unfolded or if it's a large screen device
        return if (isFoldableUnfolded) {
            ScreenOrientation.LANDSCAPE
        } else {
            ScreenOrientation.PORTRAIT
        }
    }









    
    

    
    
    private fun isSoundMuted(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_SOUND_MUTED, false)
    }
    
    // Grid system is now always enabled - no toggle needed


    
    
    // "statusbar" is not in getSystemService's @ServiceName allow-list because it is not
    // reachable by normal apps; every call below is best-effort and guarded by try/catch.
    @SuppressLint("WrongConstant")
    fun expandNotificationShade() {
        Log.d("MainActivity", "🔥 expandNotificationShade() called")
        
        try {
            // Method 1: Try the standard StatusBarManager approach
            Log.d("MainActivity", "Trying StatusBarManager approach...")
            val statusContext =
                attributionContext("system")
            val statusBarManager = statusContext.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = statusBarManager?.javaClass?.getMethod("expandNotificationsPanel")
            expandMethod?.invoke(statusBarManager)
            Log.d("MainActivity", "✅ StatusBarManager method succeeded")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "StatusBarManager method failed: ${e.message}")
        }
        
        try {
            // Method 2: Try legacy approach with different service name
            Log.d("MainActivity", "Trying legacy statusbar service approach...")
            val statusContext2 =
                attributionContext("system")
            val statusBarService = statusContext2.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = statusBarService?.javaClass?.getMethod("expandNotificationsPanel")
            expandMethod?.invoke(statusBarService)
            Log.d("MainActivity", "✅ Legacy statusbar method succeeded")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "Legacy statusbar method failed: ${e.message}")
        }
        
        try {
            // Method 3: Try expanding settings panel instead
            Log.d("MainActivity", "Trying settings panel approach...")
            val statusContext3 =
                attributionContext("system")
            val statusBarManager = statusContext3.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = statusBarManager?.javaClass?.getMethod("expandSettingsPanel")
            expandMethod?.invoke(statusBarManager)
            Log.d("MainActivity", "✅ Settings panel method succeeded")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "Settings panel method failed: ${e.message}")
        }
        
        try {
            // Method 4: Try to trigger via broadcast
            Log.d("MainActivity", "Trying broadcast approach...")
            val intent = Intent("android.intent.action.EXPAND_NOTIFICATIONS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            sendBroadcast(intent)
            Log.d("MainActivity", "✅ Broadcast sent")
            return
            
        } catch (e: Exception) {
            Log.w("MainActivity", "Broadcast approach failed: ${e.message}")
        }
        
        // Final fallback: Show a message
        Log.e("MainActivity", "❌ All notification shade expansion methods failed")
    }
    
    /**
     * Searches the web for [query], preferring the Google app.
     *
     * Three attempts, narrowing as they go: Google's own app with the query already run,
     * then whatever app claims a web search, then the query as a Google URL for the
     * browser to open. A phone with no Google app and no browser is not one this can help.
     */
    private fun searchTheWebFor(query: String) {
        val term = query.trim()
        if (term.isEmpty()) return

        val google = "com.google.android.googlequicksearchbox"
        try {
            val inGoogle = Intent(Intent.ACTION_WEB_SEARCH).apply {
                setPackage(google)
                putExtra(SearchManager.QUERY, term)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            startActivity(inGoogle)
            Log.d("MainActivity", "Searched Google for '$term'")
            return
        } catch (e: Exception) {
            Log.d("MainActivity", "Google app would not take the search: ${e.message}")
        }

        try {
            val anySearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, term)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            if (anySearch.resolveActivity(packageManager) != null) {
                startActivity(anySearch)
                return
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "No app claims web search: ${e.message}")
        }

        try {
            val url = "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(term, "UTF-8")
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Nothing on this phone can search the web", e)
            showNotification("Search", "No browser to search with")
        }
    }

    private fun launchWebSearch() {
        Log.d("MainActivity", "🔍 launchWebSearch() called")
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, "") // leave empty so the box is focused
                // Launch as separate task that can be dismissed with home gesture
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.d("MainActivity", "✅ Launched web search with focused search box")
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch focused web search: ${e.message}")
        }
        
        // Fallback: try the existing Google Search method
        launchGoogleSearch()
    }



    private fun launchGoogleSearch() {
        Log.d("MainActivity", "🔍 launchGoogleSearch() called")
        try {
            // Method 1: Try to launch Google Search with focused search box
            val searchIntent = Intent(Intent.ACTION_SEARCH)
            searchIntent.setPackage("com.google.android.googlequicksearchbox")
            // Launch as separate task that can be properly dismissed
            searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            startActivity(searchIntent)
            Log.d("MainActivity", "✅ Launched Google Search with focused search box")
            return
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch focused Google Search: ${e.message}")
        }

        try {
            // Method 1b: Try to launch Google Search app directly as fallback
            val googleSearchIntent = packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
            if (googleSearchIntent != null) {
                Log.d("MainActivity", "✅ Launching Google Search app")
                startActivity(googleSearchIntent)
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch Google Search app: ${e.message}")
        }

        try {
            // Method 2: Try to launch search via intent
            val searchIntent = Intent(Intent.ACTION_SEARCH)
            searchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(searchIntent)
            Log.d("MainActivity", "✅ Launched search via ACTION_SEARCH")
            return
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch search via ACTION_SEARCH: ${e.message}")
        }
        
        try {
            // Method 3: Try to launch Google app (fallback)
            val googleAppIntent = packageManager.getLaunchIntentForPackage("com.google.android.gms")
            if (googleAppIntent != null) {
                Log.d("MainActivity", "✅ Launching Google app as fallback")
                startActivity(googleAppIntent)
                return
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch Google app: ${e.message}")
        }
        
        try {
            // Method 4: Launch web search as final fallback
            val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH)
            webSearchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(webSearchIntent)
            Log.d("MainActivity", "✅ Launched web search")
            return
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to launch web search: ${e.message}")
        }
        
        // Final fallback: Show a message
        Log.e("MainActivity", "❌ All Google Search launch methods failed")
        showNotification("Error", "Google Search app not available")
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall", "GestureBackNavigation")
    override fun onBackPressed() {
        // Custom back button behavior for home screen launcher
        when {
            // The switcher first, as above: it is drawn over the windows, so an open
            // window does not own back ahead of it.
            wp81Shell?.closeRecents() == true -> {
                Log.d("MainActivity", "Back pressed (legacy): closing the task switcher")
            }
            // WP8.1 shell, but only when nothing is open on top of it. A window on screen
            // owns back before the shell does.
            floatingWindowManager.getFrontVisibleWindow() == null &&
                wp81Shell?.handleBack() == true -> {
                Log.d("MainActivity", "Back pressed (legacy): handled by WP8.1 shell")
            }
            isStartMenuVisible -> {
                // If start menu is open, close it
                Log.d("MainActivity", "Back pressed: closing start menu")
            }
            floatingWindowManager.getFrontVisibleWindow() != null -> {
                val frontWindow = floatingWindowManager.getFrontVisibleWindow()

                // The phone's browser, as above.
                val metroIE =
                    if (frontWindow?.windowIdentifier == "system.internet_explorer")
                        metroIEAppInstance
                    else null

                if (metroIE != null && metroIE.handleBack()) {
                    Log.d("MainActivity", "Back pressed (legacy): handled by IE (phone)")
                } else {
                    if (frontWindow?.windowIdentifier == "system.files" &&
                        metroFilesAppInstance?.handleBack() == true
                    ) {
                        // A prompt, a hold menu, select mode or a folder above this one.
                        Log.d("MainActivity", "Back pressed (legacy): handled by Files")
                    } else if (frontWindow?.windowIdentifier == "system.notepad" &&
                        metroNotepadAppInstance?.handleBack() == true
                    ) {
                        // A menu, a rename or the note itself was open over the list.
                        Log.d("MainActivity", "Back pressed (legacy): handled by Notepad")
                    } else if (frontWindow?.windowIdentifier == "system.minesweeper" &&
                        metroMinesweeperInstance?.handleBack() == true
                    ) {
                        // The strip's own command list was open over the field.
                        Log.d("MainActivity", "Back pressed (legacy): handled by Minesweeper")
                    } else if (frontWindow?.windowIdentifier == "system.solitare" &&
                        metroSolitaireInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by Solitaire")
                    } else if (frontWindow?.windowIdentifier == "system.weather" &&
                        weatherAppInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by Weather")
                    } else if (frontWindow?.windowIdentifier == "system.alarms" &&
                        alarmsAppInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by Alarms")
                    } else if (frontWindow?.windowIdentifier == "system.people" &&
                        peopleAppInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by People")
                    } else if (frontWindow?.windowIdentifier == "system.zune" &&
                        zuneAppInstance?.handleBack() == true
                    ) {
                        // Something was open over the player - a record, a sheet, the
                        // queue. That is what back closes first.
                        Log.d("MainActivity", "Back pressed: handled by Zune")
                    } else if (frontWindow?.windowIdentifier == "system.zune") {
                        // Put Zune away rather than shutting it down; it keeps playing.
                        Log.d("MainActivity", "Back pressed: minimising Zune")
                        frontWindow.minimize()
                    } else {
                        // Closing the browser on another app's link, as above.
                        val leaving = ieWindowOpenedByLink &&
                            frontWindow?.windowIdentifier == "system.internet_explorer"
                        Log.d("MainActivity", "Back pressed: closing front window")
                        floatingWindowManager.closeFrontWindow()
                        if (leaving) returnToLinkCaller()
                    }
                }
            }
            // As above: the second of two quick presses on Start is the way back to
            // the last app.
            wp81BackAgain() -> {
                Log.d("MainActivity", "Back pressed (legacy): switched to last app")
            }
            else -> {
                // If start menu is closed, do nothing (don't call super.onBackPressed())
                // This prevents the home screen from closing/restarting
                Log.d("MainActivity", "Back pressed: ignored (home screen)")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop periodic app checking when paused
        stopPeriodicAppChecking()

        // Clear non-essential caches to free memory when app goes to background
        clearNonEssentialCaches()
    }

    override fun onResume() {
        super.onResume()
        // Back in front of the user, so the next home gesture is one made from here. See
        // wp81AwayBehindAnotherApp, and onNewIntent, which is guaranteed to run first.
        wp81AwayBehindAnotherApp = false
        refreshWeatherIfNeeded()

        // Back-back sent them somewhere on a guess last time. Now that they are looking at
        // the launcher again, offer the access that would make it a fact.
        if (wp81OfferUsageAccessOnReturn) {
            wp81OfferUsageAccessOnReturn = false
            if (!hasUsageAccess() && !hasAskedUsageAccess()) offerUsageAccess()
        }

        // Check for new apps when resuming and start periodic checking
        checkForNewApps()
        // And for what the check above cannot see: an uninstall and an install between two
        // resumes leave the app count exactly as it was.
        if (tidyDesktopIcons()) {
            refreshWP81Tiles()
            refreshWP81OpenFolder()
        }
        startPeriodicAppChecking()

        // Update permission error visibility when returning from settings
        updateEmailPermissionError?.invoke()
        updateNotificationDotsPermissionError?.invoke()

        // And the same for the phone shell's settings page, which the user can now come
        // back to rather than being put on Start - see wp81ReturningToWhatWasOpen.
        refreshWP81SettingsPermissions()
    }

    /**
     * Re-reads the two settings rows that Android owns, if that page is on screen.
     *
     * The default browser and app access are granted on Android's own screens, and both
     * rows are told the answer when the user comes back from one - but only through the
     * result of the screen that was launched, and a home gesture never produces one:
     * swiping out of Android's settings does not finish it, so nothing is reported and the
     * row goes on claiming what was true before the user went and changed it.
     */
    private fun refreshWP81SettingsPermissions() {
        val shell = wp81Shell ?: return
        if (!shell.isSettingsOpen()) return
        shell.settingsPage.setDefaultBrowser(isDefaultBrowser())
        shell.settingsPage.setLastAppAccess(hasUsageAccess())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        val newOrientation = getCurrentOrientation()
        Log.d("MainActivity", "Configuration changed to: $newOrientation")

        // The phone shell keeps a wall for each way up - see wp81Landscape - so turning it
        // over is not a re-layout of the same wall but a different one, read from the
        // arrangement that was made in this orientation. Rebuilt rather than repacked: the
        // sizes are the other wall's too, and which of them a tile is wearing is part of
        // what the user arranged.
        if (wp81Shell != null) {
            wp81Shell?.startScreen?.columns = themeManager.getWP81Columns()
            refreshWP81Tiles()
        }

        // Any open floating windows re-fit themselves via the OnLayoutChangeListener in
        // WindowsDialog.setupDialogLayout once they are re-laid-out for the new size, so
        // they need no handling here.
    }

    override fun onStop() {
        super.onStop()
        // Something else has the whole screen. Noted so that the home gesture that brings
        // the launcher back is not mistaken for one made while looking at it.
        wp81AwayBehindAnotherApp = true
        // Nothing on screen to put a dot on. The refresh walks every desktop icon and, on
        // the phone shell, every tile - notifications, folder previews and media - twice a
        // second, which is work done behind whatever the user actually opened. It is
        // started again in onStart, and its first run is immediate, so coming back finds
        // the dots current.
        stopNotificationMonitoring()

        // With singleTask launch mode and proper manifest settings,
        // the system should handle home screen behavior correctly

        // A folder on the phone shell is opened into the wall to get at what is inside it,
        // so opening one of those is the end of what the folder was for. Left standing, it
        // is what the user comes home to: a wall still parted around a folder they finished
        // with a moment ago, with the tiles they actually arranged pushed a row down.
        // Closed here rather than at the launch, so that going out to an app and coming
        // straight back is the one gesture that does it, and closed without animation
        // because there is nothing on screen to watch it.
        wp81Shell?.startScreen?.closeFolder(animated = false)

        // The app list is put back to rest on the same terms: whatever was searched for is
        // cleared and the rows are back at the top, ready for the next time it is opened.
        // Here rather than at the tap that launched the app, so none of it happens in front
        // of the user - see the app list's onLaunch.
        wp81Shell?.appList?.let {
            it.endSearch()
            it.scrollToTop()
        }

    }

    override fun onRestart() {
        super.onRestart()
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart called")

        // Reload wallpaper when app comes back to foreground
        reloadWallpaperBitmap()

        // And pick the dots back up - see onStop, which puts them down.
        startNotificationMonitoring()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent called with action: ${intent?.action}, categories: ${intent?.categories}")
        
        // Handle home intent when we're already the active launcher
        if (intent?.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_HOME)) {
            Log.d("MainActivity", "Home intent received - ensuring we stay visible")
            // We're already the home screen, just ensure we're in the right state
            if (isStartMenuVisible) {
            }
            if (!wp81ReturningToWhatWasOpen()) resetWP81ToStart()
        }
        
        // Update intent for activity
        setIntent(intent)

        // Handle a URL shared into the launcher while it's already running (the common path)
        handleSharedUrlIntent(intent)
        handleViewUrlIntent(intent)
        // And a number, which is the usual way one arrives: the launcher is the home
        // screen, so it is nearly always already running when a tel: link is tapped.
        handleDialIntent(intent)
        handleCallHistoryIntent(intent)
        // And a message, which arrives the same way and just as often: this is the home
        // screen, so it is nearly always already running when an sms: link is tapped.
        handleMessageIntent(intent)
        handleAlarmsIntent(intent)
        handleWeatherIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNotificationMonitoring()
        // Windows Phone 8.1 shell: the live-tile flip is a repeating post and would
        // otherwise outlive the activity.
        stopWP81LiveTiles()
        floatingWindowManager.onWindowCountChanged = null
        wp81Shell = null

        // Stop update checker
        stopUpdateChecker()

        // Unregister charging receiver
        chargingReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                Log.d("MainActivity", "Charging receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w("MainActivity", "Charging receiver was not registered")
            }
        }

        // Clean up floating windows
        if (::floatingWindowManager.isInitialized) {
            floatingWindowManager.removeAllWindows()
        }

        // Clean up weather updates
        weatherUpdateRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        // Clean up SoundPool
        if (::soundPool.isInitialized) {
            soundPool.release()
            Log.d("MainActivity", "SoundPool released")
        }

        // Clean up app install receiver listener - ours only, for the same reason as above.
        AppInstallReceiver.clearListener(this)
        unregisterLauncherAppsCallback()
        iconRefreshRunnable?.let { handler.removeCallbacks(it) }
        iconRefreshRunnable = null

        // Stop app checking
        stopPeriodicAppChecking()

        // Only if it is still ours. A theme switch recreates the activity, and the order
        // is not guaranteed to be the tidy one: where the incoming activity has already
        // announced itself, the outgoing one clearing this unconditionally left it null,
        // and NotificationListenerService.notifyMainActivity found nothing to notify - so
        // notification dots and tile counts stopped updating until the next recreate.
        if (instance === this) instance = null

        // The app list goes; the icons stay. This runs on every theme switch, and throwing
        // away a cache the very next activity is about to rebuild is the work it was put
        // there to avoid. Actual memory pressure still empties it - see onLowMemory.
        cachedAppList = null
    }

    /**
     * Called when the system is running low on memory
     */
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        Log.w("MainActivity", "onLowMemory called - clearing caches aggressively")
        clearAllBitmapCaches()
    }

    /**
     * Called when the system wants the application to trim memory
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("MainActivity", "onTrimMemory called with level: $level")

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // App is running but system is critically low on memory
                clearAllBitmapCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // App is running but system is low on memory
                clearNonEssentialCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // App is running but system wants to reclaim memory
                iconBitmapCache.trimToSize(iconBitmapCache.maxSize() / 2)
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // App is in background - can be more aggressive
                clearAllBitmapCaches()
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI is hidden - good time to release memory
                clearNonEssentialCaches()
            }
        }
    }

    /**
     * Clear all bitmap caches aggressively
     */
    private fun clearAllBitmapCaches() {
        val initialSize = iconBitmapCache.size()
        iconBitmapCache.evictAll()
        Log.d("MainActivity", "Cleared icon bitmap cache (was $initialSize items)")

        // Clear cached app list
        cachedAppList = null
        Log.d("MainActivity", "Cleared cached app list")
    }

    /**
     * Clear non-essential caches while keeping visible items
     */
    private fun clearNonEssentialCaches() {
        // Trim icon cache to 25% of max size
        val targetSize = iconBitmapCache.maxSize() / 4
        iconBitmapCache.trimToSize(targetSize)

        // Clear cached app list (will reload when needed)
        cachedAppList = null
    }

    // Weather-related functions
    private var weatherUpdateRunnable: Runnable? = null
    


    private fun handleAqiTap() {
        val aqiAppPackage = "com.gorjan.airquality"
        try {
            val intent = packageManager.getLaunchIntentForPackage(aqiAppPackage)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                startActivity(intent)
                Log.d("MainActivity", "Launched AQI app: $aqiAppPackage")
            } else {
                // App not installed, open Play Store
                openPlayStoreForAqiApp(aqiAppPackage)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error launching AQI app", e)
            openPlayStoreForAqiApp(aqiAppPackage)
        }
    }

    private fun openPlayStoreForAqiApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            // Play Store not available, open in browser
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
    }

    private fun refreshAqiData() {
        Log.d("MainActivity", "Refreshing AQI data with fresh GPS location...")
        val locationContext = attributionContext("aqi")
        val locationManager = locationContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        Log.d("MainActivity", "Got fresh location for AQI: ${location.latitude}, ${location.longitude}")
                        fetchAqiData(location.latitude, location.longitude)
                        locationManager.removeUpdates(this)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                // Request fresh GPS location first, fall back to network
                if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    Log.d("MainActivity", "Requesting fresh GPS location for AQI...")
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER,
                        0, 0f, locationListener)
                } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    Log.d("MainActivity", "GPS not available, using network location for AQI...")
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER,
                        0, 0f, locationListener)
                } else {
                    Log.w("MainActivity", "No location provider available for AQI refresh")
                }

                // Timeout after 15 seconds - fall back to cached location
                handler.postDelayed({
                    locationManager.removeUpdates(locationListener)
                    // Try cached location as fallback
                    var cachedLocation: android.location.Location? = null
                    if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        cachedLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    }
                    if (cachedLocation == null && locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        cachedLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    }
                    if (cachedLocation != null) {
                        Log.d("MainActivity", "GPS timeout, using cached location for AQI")
                        fetchAqiData(cachedLocation.latitude, cachedLocation.longitude)
                    }
                }, 15000)
            }
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Location permission denied for AQI refresh", e)
        }
    }
    
    

    
    

    private fun handleWeatherTempRefresh() {
        Log.d("MainActivity", "🔄 Weather refresh requested via long press")
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            // Request permission
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 
                LOCATION_PERMISSION_REQUEST_CODE)
        } else if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            // Request coarse location as backup
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 
                LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            // Permission already granted, fetch weather
            fetchLocationAndWeather()
        }
    }
    
    private fun updateWeatherTemperature() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        
        // Check for location permissions
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "No location permission - showing '?'")
            weatherTemp?.text = "?"
            return
        }
        
        // Check network availability
        if (!isNetworkAvailable()) {
            Log.d("MainActivity", "No network connection - trying to use cached data")
            // Try to use cached data
            val cachedData = getCachedWeatherJson()
            if (cachedData != null) {
                try {
                    val currentWeather = cachedData.getJSONObject("current")
                    val temperature = currentWeather.getDouble("temperature_2m")
                    val roundedTemp = kotlin.math.round(temperature).toInt()
                    val unitTemp = getWeatherUnit()
                    weatherTemp?.text = "$roundedTemp°$unitTemp"
                    Log.d("MainActivity", "Using cached weather data: $roundedTemp°")
                    return
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error parsing cached weather data", e)
                }
            }
            weatherTemp?.text = "?"
            return
        }
        
        // Permission granted and network available, fetch weather
        fetchLocationAndWeather()
    }
    
    private fun fetchLocationAndWeather() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        weatherTemp?.text = "..."

        // A place chosen by hand in the Weather app is the phone's weather from then on:
        // the taskbar reading, the Start screen's tile and the app all show one forecast,
        // and going to the radio here would fetch a second one for a place nobody asked
        // about. It also means a pinned place works with location switched off entirely.
        rocks.gorjan.gokixp.wp81.WeatherStore.selected(this)?.let { place ->
            if (!place.isHere && !place.latitude.isNaN()) {
                fetchWeatherData(place.latitude, place.longitude)
                return
            }
        }

        val locationContext =
            attributionContext("weather")
        val locationManager = locationContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED) {
                
                // First try to get last known location (fast, no GPS ping)
                var lastKnownLocation: android.location.Location? = null
                
                // Check GPS provider first
                if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                }
                
                // Fallback to network provider
                if (lastKnownLocation == null && locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                }
                
                // If we have a cached location, use it immediately
                if (lastKnownLocation != null) {
                    // Written down as well as used: the Weather app draws its "my
                    // location" against the last fix the phone had, and indoors there may
                    // never be another one.
                    rocks.gorjan.gokixp.wp81.WeatherStore.rememberHere(
                        this, lastKnownLocation.latitude, lastKnownLocation.longitude)
                    // And put a town to it, off this thread: the Weather app is headed
                    // with the name rather than with "my location".
                    rocks.gorjan.gokixp.wp81.WeatherStore.nameHereLater(
                        this, lastKnownLocation.latitude, lastKnownLocation.longitude)
                    fetchWeatherData(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    return
                }
                
                // Only if no cached location is available, request fresh location
                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        rocks.gorjan.gokixp.wp81.WeatherStore.rememberHere(
                            this@MainActivity, location.latitude, location.longitude)
                        rocks.gorjan.gokixp.wp81.WeatherStore.nameHereLater(
                            this@MainActivity, location.latitude, location.longitude)
                        fetchWeatherData(location.latitude, location.longitude)
                        locationManager.removeUpdates(this)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                
                // Request fresh location as fallback
                if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    // Prefer network provider for speed
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.NETWORK_PROVIDER, 
                        0, 0f, locationListener)
                } else if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        android.location.LocationManager.GPS_PROVIDER, 
                        0, 0f, locationListener)
                }
                
                // Shorter timeout since we're only using this as fallback
                handler.postDelayed({
                    locationManager.removeUpdates(locationListener)
                    weatherTemp?.text = "?"
                }, 10000) // Reduced to 10 seconds
            }
        } catch (e: SecurityException) {
            weatherTemp?.text = "?"
        }
    }
    
    private fun fetchWeatherData(latitude: Double, longitude: Double) {
        Thread {
            val maxRetries = 3
            var lastError: Exception? = null
            
            for (attempt in 0 until maxRetries) {
                try {
                    Log.d("MainActivity", "Weather fetch attempt ${attempt + 1}/$maxRetries")
                    
                    // One request, and everything on the phone reads what it brings
                    // back: this readout, the Start screen's weather tile, the Quick
                    // Glance widget and the Weather app. Asking for a week of daily
                    // figures and the hours inside it costs exactly the same single call
                    // as asking for the current temperature alone, and it is what stops
                    // the app having to fetch a second forecast that disagrees with this
                    // one. What is on the list, and why each of it is, is WeatherStore's.
                    val url =
                        rocks.gorjan.gokixp.wp81.WeatherStore.forecastUrl(latitude, longitude)
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000 + (attempt * 2000) // Increase timeout with retries
                    connection.readTimeout = 10000 + (attempt * 2000)
                    
                    val responseCode = connection.responseCode
                    Log.d("MainActivity", "Weather API response code: $responseCode")
                    
                    if (responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        
                        // Parse JSON response properly
                        try {
                            val jsonObject = org.json.JSONObject(response)
                            val currentWeather = jsonObject.getJSONObject("current")
                            val temperature = currentWeather.getDouble("temperature_2m")

                            // Save weather data to SharedPreferences for other components
                            saveWeatherData(response)

                            runOnUiThread {
                                val weatherTemp = findViewById<TextView>(R.id.weather_temp)
                                val formattedTemp = formatTemperature(temperature)
                                weatherTemp?.text = formattedTemp
                                Log.d("MainActivity", "Weather updated successfully: $formattedTemp")

                                // And the Start screen's weather tile, which would
                                // otherwise sit on the old reading until the next tick.
                                refreshWP81Weather()
                                // An open Weather app is looking at the forecast that has
                                // just been replaced underneath it.
                                weatherAppInstance?.bind()
                            }
                            // Also fetch AQI data
                            fetchAqiData(latitude, longitude)
                            return@Thread // Success - exit retry loop
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error parsing weather JSON on attempt ${attempt + 1}", e)
                            lastError = e
                        }
                    } else {
                        val errorMessage = "HTTP error $responseCode on attempt ${attempt + 1}"
                        Log.e("MainActivity", errorMessage)
                        lastError = Exception(errorMessage)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Network error on attempt ${attempt + 1}: ${e.message}", e)
                    lastError = e
                }
                
                // Wait before retrying (exponential backoff)
                if (attempt < maxRetries - 1) {
                    val delayMs = (1000 * (attempt + 1) * (attempt + 1)).toLong() // 1s, 4s, 9s
                    Log.d("MainActivity", "Waiting ${delayMs}ms before retry...")
                    try {
                        Thread.sleep(delayMs)
                    } catch (e: InterruptedException) {
                        Log.d("MainActivity", "Retry sleep interrupted")
                        break
                    }
                }
            }
            
            // All retries failed - try to use cached data or show error
            Log.e("MainActivity", "All weather fetch attempts failed. Last error: ${lastError?.message}")
            runOnUiThread {
                handleWeatherFetchFailure()
            }
        }.start()
    }
    
    private fun handleWeatherFetchFailure() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        
        // Try to use cached data as fallback
        val cachedData = getCachedWeatherJson()
        if (cachedData != null) {
            try {
                val currentWeather = cachedData.getJSONObject("current")
                val temperature = currentWeather.getDouble("temperature_2m")
                val formattedTemp = formatTemperature(temperature)
                weatherTemp?.text = formattedTemp
                Log.d("MainActivity", "Using cached weather as fallback: $formattedTemp")
                return
            } catch (e: Exception) {
                Log.e("MainActivity", "Error parsing cached weather fallback", e)
            }
        }
        
        // No cached data available - show error
        weatherTemp?.text = "?"
        Log.d("MainActivity", "No cached weather available - showing '?'")
    }
    
    // Weather data caching methods
    private fun saveWeatherData(weatherResponse: String) {
        try {
            // Through WeatherStore rather than straight into preferences: it writes the
            // same two keys this always did, and also notes the reading against the place
            // it was taken for, which is what the Weather app's places list is a column of.
            rocks.gorjan.gokixp.wp81.WeatherStore.save(this, weatherResponse)
            Log.d("MainActivity", "Weather data saved to SharedPreferences")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving weather data", e)
        }
    }
    
    private fun getCachedWeatherData(): String? {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.getString(KEY_WEATHER_DATA, null)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error retrieving cached weather data", e)
            null
        }
    }
    
    

    // Temperature unit preference methods
    private fun getWeatherUnit(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_WEATHER_UNIT, "C") ?: "C"
    }



    private fun convertTemperature(tempCelsius: Double): Int {
        return when (getWeatherUnit()) {
            "F" -> kotlin.math.round((tempCelsius * 9.0 / 5.0) + 32.0).toInt()
            else -> kotlin.math.round(tempCelsius).toInt()
        }
    }

    private fun formatTemperature(tempCelsius: Double): String {
        val temp = convertTemperature(tempCelsius)
        var unit = getWeatherUnit()
        return "$temp°$unit"
    }


    private fun refreshWeatherIfNeeded() {
        val weatherTemp = findViewById<TextView>(R.id.weather_temp)
        val currentText = weatherTemp?.text?.toString() ?: "?"
        
        // Always try to refresh if showing "?" or cached data is old
        if (currentText == "?" || currentText == "..." || isCachedWeatherDataOld()) {
            Log.d("MainActivity", "Weather refresh needed - current: $currentText")
            updateWeatherTemperature()
        } else {
            Log.d("MainActivity", "Weather refresh not needed - current: $currentText")
        }
    }
    
    private fun isCachedWeatherDataOld(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timestamp = prefs.getSafeLong(KEY_WEATHER_TIMESTAMP, 0L)
        val currentTime = System.currentTimeMillis()
        val thirtyMinutesAgo = currentTime - (30 * 60 * 1000) // 30 minutes
        return timestamp < thirtyMinutesAgo
    }
    
    // Helper method to get parsed weather data
    fun getCachedWeatherJson(): org.json.JSONObject? {
        return try {
            val weatherData = getCachedWeatherData()
            if (weatherData != null) {
                org.json.JSONObject(weatherData)
            } else null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error parsing cached weather JSON", e)
            null
        }
    }

    // AQI (Air Quality Index) methods
    private fun fetchAqiData(latitude: Double, longitude: Double) {
        // Don't hit the AirCare API at all when the indicator is disabled.
        if (!isShowAqiEnabled()) return
        Thread {
            try {
                val url = "https://getaircare.com/api/v4/api.php?requestType=point&lat=$latitude&lng=$longitude"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(response)
                    val measurements = jsonObject.getJSONArray("measurements")

                    // Find pid: 7 (EU AQI)
                    var aqiValue: Int? = null
                    for (i in 0 until measurements.length()) {
                        val measurement = measurements.getJSONObject(i)
                        if (measurement.getInt("pid") == 7) {
                            aqiValue = measurement.getInt("val")
                            break
                        }
                    }

                    if (aqiValue != null) {
                        saveAqiData(aqiValue)
                        runOnUiThread {
                            updateAqiDisplay(aqiValue)
                            // Notify QuickGlanceWidget to refresh
                        }
                        Log.d("MainActivity", "AQI updated successfully: $aqiValue")
                    }
                } else {
                    Log.e("MainActivity", "AQI API error: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error fetching AQI data", e)
            }
        }.start()
    }

    private fun saveAqiData(aqi: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                putInt(KEY_AQI_DATA, aqi)
                putLong(KEY_AQI_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            Log.d("MainActivity", "AQI data saved: $aqi")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving AQI data", e)
        }
    }



    private fun updateAqiDisplay(aqi: Int) {
        val aqiContainer = findViewById<LinearLayout>(R.id.aqi_container)
        val aqiText = findViewById<TextView>(R.id.aqi_text)

        // Respect the opt-in setting: never surface AQI when it's disabled.
        if (!isShowAqiEnabled()) {
            aqiContainer?.visibility = View.GONE
            return
        }

        aqiContainer?.visibility = View.VISIBLE
        aqiText?.text = aqi.toString()

        // Get underline color based on AQI value
        val underlineColor = when {
            aqi <= 26 -> "#4CAF50".toColorInt() // Green
            aqi <= 33 -> "#FFEB3B".toColorInt() // Yellow
            aqi <= 66 -> "#FF9800".toColorInt() // Orange
            aqi <= 100 -> "#F44336".toColorInt() // Red
            else -> "#9C27B0".toColorInt() // Purple
        }

        // Clear container background
        aqiContainer?.setBackgroundColor(Color.TRANSPARENT)

        val textColor = Color.WHITE
        aqiText?.setTextColor(textColor)

        // Create 2px colored underline as background drawable, offset 2px down
        aqiText?.let { textView ->
            val density = resources.displayMetrics.density
            val underlineHeight = (2 * density).toInt()
            val underlineOffset = (2 * density).toInt()
            val underlineDrawable = GradientDrawable().apply {
                setColor(underlineColor)
            }
            val layerDrawable = LayerDrawable(arrayOf(underlineDrawable)).apply {
                setLayerGravity(0, Gravity.BOTTOM)
                setLayerHeight(0, underlineHeight)
                setLayerInsetBottom(0, -underlineOffset)
            }
            textView.background = layerDrawable
        }
        aqiContainer?.clipToPadding = false
        aqiContainer?.clipChildren = false
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager

            // Check if we have the required permission
            // For API 23+, use the modern approach
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: SecurityException) {
            // Handle case where ACCESS_NETWORK_STATE permission is missing
            Log.w("MainActivity", "ACCESS_NETWORK_STATE permission not granted, assuming network is available", e)
            true // Default to assuming network is available
        } catch (e: Exception) {
            // Handle any other unexpected exceptions
            Log.e("MainActivity", "Unexpected error checking network availability, assuming network is available", e)
            true // Default to assuming network is available
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, fetch weather
                fetchLocationAndWeather()
            } else {
                // Permission denied, show settings or keep question mark
                val weatherTemp = findViewById<TextView>(R.id.weather_temp)
                weatherTemp?.text = "?"

                // Handle location permission rationale
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    // User denied but didn't check "don't ask again"
                } else {
                    // User denied and checked "don't ask again", open settings
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Couldn't open settings
                    }
                }
            }
            
            CALENDAR_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Calendar permission granted")

                // Read the calendar again now it is allowed, so the tile fills in.
                handler.postDelayed({ refreshWP81TodayEvent() }, 500)
            } else {
                Log.d("MainActivity", "Calendar permission denied")
                showNotification("Permission Needed", "Calendar permission required for event display")
            }

            AUDIO_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("Winamp", "Audio permission granted")

                zuneAppInstance?.refreshLibrary()
            } else {
                Log.d("Winamp", "Audio permission denied")
                showNotification("Permission Needed", "Storage permission required to access music files")
            }

            NOTIFICATION_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Notification permission granted")
            } else {
                Log.d("MainActivity", "Notification permission denied")
                // Don't show a toast for notification denial as it's optional
            }

            PHOTOS_PERMISSION_REQUEST_CODE -> {
                // Any of them: on Android 14 the user may have granted a selection rather
                // than the library, which arrives as the second permission and not the first.
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    // The tile fills with pictures where the invitation was. Opening the
                    // gallery on top of that would take the user away from the thing they
                    // have just this second switched on.
                    refreshWP81Photos(force = true)
                } else {
                    showNotification("Photos", "Photo access is needed to show your pictures")
                }
            }

            CONTACTS_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    // The tile fills with faces where the invitation was. Opening the
                    // dialler on top of that would take the user away from the thing they
                    // have just this second switched on.
                    refreshWP81People(force = true)
                } else {
                    showNotification("People", "Contact access is needed to show your people")
                }
            }

            PEOPLE_PERMISSION_REQUEST_CODE -> {
                // Whatever was granted, the app re-reads everything: a section that was
                // showing "tap to allow" fills in where it stands rather than asking the
                // user to leave the app and come back to it.
                peopleAppInstance?.refresh()
                if (grantResults.none { it == PackageManager.PERMISSION_GRANTED }) {
                    showNotification("People", "That needs permission to work")
                } else {
                    // The tile is the same book. A contact permission granted in the app
                    // is the moment the wall on Start can fill itself in too.
                    refreshWP81People(force = true)
                }
            }
        }
    }
    
    /**
     * Notifications arrived or cleared.
     *
     * The desktop drew a dot on an icon; the phone puts the count on the tile, which is
     * what the shell already does from the same data - so this only asks for a redraw.
     */
    fun updateNotificationDots() {
        refreshWP81Tiles()
    }
    
    /**
     * The dots refresh, held so it can be stopped.
     *
     * It used to be a local: a runnable that reposts itself every two seconds, with no
     * reference kept anywhere, so nothing could ever take it off the queue. Each recreate -
     * every theme switch - left another one ticking, and because a pending Message holds
     * its callback, and the callback holds this activity, the whole activity stayed alive
     * with its view tree, its wallpaper and its shell, being refreshed twice a second for a
     * screen nobody was looking at. That was the retention path in the heap dump:
     * Message.next ... Message.callback -> startNotificationMonitoring$updateRunnable$1.
     */
    private var notificationMonitorRunnable: Runnable? = null

    private fun startNotificationMonitoring() {
        stopNotificationMonitoring()

        // Start periodic refresh of notification dots every 2 seconds
        val updateRunnable = object : Runnable {
            override fun run() {
                updateNotificationDots()
                handler.postDelayed(this, 2000) // 2 seconds
            }
        }
        notificationMonitorRunnable = updateRunnable
        handler.post(updateRunnable)
        
        // Check if notification listener service is enabled
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        if (intent.resolveActivity(packageManager) != null && 
            !isNotificationListenerEnabled()) {
            
            // Optionally show a dialog to enable notification listener
            Log.d("MainActivity", "Notification listener not enabled, notifications dots may not work")
        }
    }
    
    private fun stopNotificationMonitoring() {
        notificationMonitorRunnable?.let { handler.removeCallbacks(it) }
        notificationMonitorRunnable = null
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            val names = flat.split(":")
            for (name in names) {
                val componentName = android.content.ComponentName.unflattenFromString(name)
                if (componentName != null && packageName == componentName.packageName) {
                    return true
                }
            }
        }
        return false
    }
    
    /**
     * Takes these icons out of the list, and so off the Start screen.
     *
     * The one place icons stop existing. Deliberately not a save: a removal is always part
     * of a larger change - an uninstall, a folder deleted, a tidy-up of several things at
     * once - and the caller writes once when it is finished.
     */
    private fun removeIcons(ids: Set<String>) {
        if (ids.isEmpty()) return
        desktopIcons.removeAll { it.id in ids }
        // A tile's colour is remembered against the icon's id, and no icon will ever carry
        // that id again - ids are minted from a package name and the clock, so even a
        // reinstall makes a new one. Left behind, it is a preference that grows by an entry
        // per deletion and is never read again.
        ids.forEach { themeManager.setWP81TileColor(it, null) }
    }

    /**
     * An icon's id, and every icon filed inside it if it is a folder, to any depth.
     *
     * Deleting a folder without this leaves its contents pointing at a folder that is not
     * there. Nothing draws such an icon - Start shows the top level and a folder page shows
     * its own children - so they become rows the user can neither see nor reach, and they
     * outlive everything: they are saved, loaded and saved again for as long as the
     * launcher is installed. See [pruneStrayIcons], which clears up any that got away.
     */
    private fun iconIdsWithContents(icon: DesktopIcon): Set<String> {
        val ids = mutableSetOf(icon.id)
        if (icon.type != IconType.FOLDER) return ids
        // A folder inside a folder is one level more than the shell offers today, but the
        // walk costs nothing and does not have to be revisited if that changes. Ids already
        // collected are never followed twice, so a folder somehow filed inside itself is a
        // deletion rather than a hang.
        var growing = true
        while (growing) {
            val next = desktopIcons.filter { it.parentFolderId in ids && it.id !in ids }
            growing = next.isNotEmpty()
            next.forEach { ids.add(it.id) }
        }
        return ids
    }

    /**
     * Everything the launcher was holding for an app that is no longer installed.
     *
     * The icon list is the one model behind every theme - the desktop's icons, the phone
     * shell's tiles and whatever is filed inside a folder are all rows in it - so a
     * removal has to happen there rather than on whichever views happen to exist. Sweeping
     * the views was the old way, and it only ever found what the desktop had built a view
     * for: an icon inside a folder never gets one, and under WP8.1 the desktop is not on
     * screen at all, so an uninstalled app kept both its tile and its place in a folder.
     *
     * Returns whether anything went, so callers can skip the rebuild that follows.
     */
    private fun removeDesktopIconsForPackage(packageName: String): Boolean {
        // The shell's own programs live under a package prefix no real app can own and are
        // never installed or uninstalled, so a package broadcast is never about them.
        if (isSystemApp(packageName)) return false

        // Only apps: a folder, a web shortcut or the Recycle Bin may happen to carry this
        // package name without being the app that has just gone.
        val doomed = desktopIcons
            .filter { it.type == IconType.APP && it.packageName == packageName }
            .map { it.id }
            .toSet()
        if (doomed.isEmpty()) return false

        return try {
            removeIcons(doomed)
            saveDesktopIcons()
            Log.d("MainActivity", "Removed ${doomed.size} icons for uninstalled package: $packageName")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error removing icons for: $packageName", e)
            false
        }
    }

    /**
     * Throws away icons filed in a folder that is not there.
     *
     * The safety net under every path that deletes a folder. Those all take the folder's
     * contents with them now, but a stray costs the user something worse than the row it
     * occupies: it is invisible, so it can never be removed by hand, and it comes back
     * with every save. One sweep of the list on load settles the question for good,
     * including for arrangements that were already carrying strays before any of this.
     *
     * A stray folder is taken with its contents, which are not strays themselves - their
     * parent exists - but are about to be.
     *
     * Returns how many rows went.
     */
    private fun pruneStrayIcons(): Int {
        val folders = desktopIcons.filter { it.type == IconType.FOLDER }.map { it.id }.toSet()
        val strays = desktopIcons.filter {
            it.parentFolderId != null && it.parentFolderId !in folders
        }
        if (strays.isEmpty()) return 0

        val doomed = strays.flatMap { iconIdsWithContents(it) }.toSet()
        Log.w(
            "MainActivity",
            "Dropping ${doomed.size} icons filed in folders that no longer exist: " +
                strays.joinToString { "${it.name} (${it.parentFolderId})" }
        )
        removeIcons(doomed)
        return doomed.size
    }

    /**
     * Drops icons for apps that went while nobody was listening.
     *
     * The two live routes - the manifest receiver and the LauncherApps callback - both
     * need this activity to be up, and the periodic check only looks when the number of
     * launchable apps has changed, which an uninstall and an install between two resumes
     * leave exactly as it was. So a pinned app can outlive its package with nothing left
     * to notice, and the tile it leaves behind is blank and backed by nothing.
     *
     * Cheap enough for every resume: one package-manager lookup per distinct app on the
     * wall, on a list that is dozens of entries at most.
     */
    private fun pruneUninstalledIcons(): Int {
        val packages = desktopIcons
            .filter { it.type == IconType.APP && !isSystemApp(it.packageName) }
            .map { it.packageName }
            .toSet()
        if (packages.isEmpty()) return 0

        // QUERY_ALL_PACKAGES is held, so "not found" here means genuinely not installed
        // rather than merely not visible to this app.
        val gone = packages.filterNot { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                // Anything else is the package manager being unhelpful, not an answer.
                Log.w("MainActivity", "Could not check whether $pkg is installed", e)
                true
            }
        }
        if (gone.isEmpty()) return 0

        val doomed = desktopIcons
            .filter { it.type == IconType.APP && it.packageName in gone }
            .map { it.id }
            .toSet()
        Log.d("MainActivity", "Dropping ${doomed.size} icons for uninstalled packages: $gone")
        gone.forEach { pkg ->
            invalidateIconCache(pkg)
            wp81IconProvider.invalidate(pkg)
        }
        removeIcons(doomed)
        return doomed.size
    }

    /**
     * Puts the icon list back into a state the shells can draw from.
     *
     * Two things can be wrong with it, and both are invisible until something goes looking:
     * a row for an app that is no longer installed, which shows as a tile with no artwork
     * and nothing behind it, and a row filed in a folder that is not there, which shows as
     * nothing at all. Run wherever the list has just been read or has just changed
     * underneath the user.
     *
     * Saves once for both, and returns whether the caller has anything to redraw.
     */
    private fun tidyDesktopIcons(): Boolean {
        val removed = try {
            pruneStrayIcons() + pruneUninstalledIcons()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error tidying the icon list", e)
            0
        }
        if (removed == 0) return false
        saveDesktopIcons()
        return true
    }

    /**
     * Rebuilds the folder that is open, if one is, after its contents changed underneath it.
     *
     * A folder opened into the wall closes itself when the wall is rebuilt, so that one
     * needs nothing; the folder *page* - which is where a folder inside another folder is
     * opened - is a surface of its own and would go on showing the tile of an app that no
     * longer exists.
     */
    private fun refreshWP81OpenFolder() {
        val shell = wp81Shell ?: return
        if (!shell.isFolderOpen()) return
        val folderId = wp81OpenFolderId ?: return
        // Nothing left to look into, and a page showing an empty folder is a page with
        // nothing on it: back to the wall instead.
        if (desktopIcons.none { it.parentFolderId == folderId }) {
            shell.closeFolder()
            return
        }
        reopenWP81Folder(folderId)
    }

    // AppChangeListener implementation
    override fun onAppInstalled(packageName: String) {
        Log.d("MainActivity", "App installed notification: $packageName")
        runOnUiThread {
            // Drop anything cached under this package name (a reinstall reuses it) and refresh
            invalidateIconCache(packageName)
            loadInstalledApps()
            refreshWP81ForPackageChange(packageName)

            // Nothing is pinned to Start on the user's behalf. A desktop dropped an icon
            // for every install because a desktop is where icons live; on Windows Phone a
            // new app appears in the app list, and the wall holds what its owner put there.
        }
    }
    
    override fun onAppRemoved(packageName: String) {
        Log.d("MainActivity", "App removed notification: $packageName")
        runOnUiThread {
            // Drop the removed app's cached icons, then refresh the app list
            invalidateIconCache(packageName)
            loadInstalledApps()

            // Before the shells are told rather than after. Everything they draw is read
            // from the icon list, so refreshing first rebuilt the wall around an app that
            // was still in it: the tile stayed, lost its artwork - nothing can resolve a
            // glyph for a package that is gone - and sat there blank until something else
            // happened to rebuild Start.
            val removed = removeDesktopIconsForPackage(packageName)

            refreshWP81ForPackageChange(packageName)
            // The wall being rebuilt does not reach inside an opened folder, which is
            // built once from what it held at the time.
            if (removed) refreshWP81OpenFolder()
        }
    }
    
    override fun onAppReplaced(packageName: String) {
        Log.d("MainActivity", "App replaced notification: $packageName")
        runOnUiThread {
            // An update can ship a different icon under the same package name, so the cached
            // bitmaps have to go before anything reads the icon again
            refreshIconsForPackage(packageName)
            refreshWP81ForPackageChange(packageName)
        }
    }

    /**
     * Brings the Windows Phone 8.1 shell up to date after a package changed.
     *
     * The desktop themes were already told - this is the same routine, which rebuilt the
     * app list and the desktop icons and simply had no branch for this shell, so under
     * WP8.1 a newly installed app stayed invisible until the theme was applied again.
     *
     * Both surfaces are refreshed because both are downstream of the change: the app list
     * reads the package manager, and Start is built from the desktop icons this routine
     * has just added one to (or taken one away from).
     *
     * All three calls no-op when the shell is not up, so this is safe on every theme.
     */
    private fun refreshWP81ForPackageChange(packageName: String) {
        if (wp81Shell == null) return
        // The glyph is derived from the app's icon and cached by package; an update can
        // ship a new one under the same name.
        wp81IconProvider.invalidate(packageName)
        refreshWP81AppList()
        refreshWP81Tiles()
    }
    
    private fun handlePendingPackageAction() {
        val intent = intent
        val packageAction = intent.getStringExtra("package_action")
        val packageName = intent.getStringExtra("package_name")
        
        if (packageAction != null && packageName != null) {
            Log.d("MainActivity", "Handling pending package action: $packageAction for $packageName")
            
            // Handle the action after a short delay to ensure UI is ready
            Handler(Looper.getMainLooper()).postDelayed({
                when (packageAction) {
                    "install" -> onAppInstalled(packageName)
                    "remove" -> onAppRemoved(packageName)
                    "replace" -> onAppReplaced(packageName)
                }
                
                // Clear the intent extras so they don't get handled again
                intent.removeExtra("package_action")
                intent.removeExtra("package_name")
            }, 1000) // 1 second delay
        }
    }
    

    // Alternative app detection system (since broadcasts may not work on modern Android)
    private fun initializeAppDetection() {
        // Get initial app count
        lastKnownAppCount = getCurrentLaunchableAppCount()
        Log.d("MainActivity", "Initial app count: $lastKnownAppCount")

        // Initialize known apps list if it doesn't exist
        initializeKnownAppsList()
    }

    private fun initializeKnownAppsList() {
        val knownApps = getKnownApps()
        if (knownApps.isEmpty()) {
            // First time - populate with current apps
            val currentApps = getCurrentInstalledApps()
            saveKnownApps(currentApps)
            Log.d("MainActivity", "Initialized known apps list with ${currentApps.size} apps")
        } else {
            Log.d("MainActivity", "Loaded ${knownApps.size} known apps from storage")
        }
    }

    private fun getCurrentInstalledApps(): Set<String> {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(mainIntent, 0).map {
                it.activityInfo.packageName
            }.toSet()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting current installed apps", e)
            emptySet()
        }
    }

    private fun getKnownApps(): Set<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val knownAppsJson = prefs.getString(KEY_KNOWN_APPS, "[]")
        return try {
            val gson = Gson()
            val type = object : TypeToken<List<String>>() {}.type
            val knownAppsList: List<String> = gson.fromJson(knownAppsJson, type) ?: emptyList()
            knownAppsList.toSet()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading known apps", e)
            emptySet()
        }
    }

    private fun saveKnownApps(apps: Set<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val knownAppsJson = gson.toJson(apps.toList())
        prefs.edit {
            putString(KEY_KNOWN_APPS, knownAppsJson)
        }
        Log.d("MainActivity", "Saved ${apps.size} known apps to storage")
    }

    private fun getCurrentLaunchableAppCount(): Int {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(mainIntent, 0).size
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting app count", e)
            0
        }
    }

    private fun checkForNewApps() {
        try {
            val currentAppCount = getCurrentLaunchableAppCount()

            if (currentAppCount != lastKnownAppCount) {
                detectAppChanges()
                lastKnownAppCount = currentAppCount
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking for new apps", e)
        }
    }

    private fun detectAppChanges() {
        try {
            // Get current installed apps
            val currentApps = getCurrentInstalledApps()

            // Get previously known apps from storage
            val knownApps = getKnownApps()

            // Find new apps (in current but not in known)
            val newApps = currentApps - knownApps

            // Find removed apps (in known but not in current)
            val removedApps = knownApps - currentApps

            Log.d("MainActivity", "New apps: $newApps")
            Log.d("MainActivity", "Removed apps: $removedApps")

            // Handle removed apps. Through the same routine the broadcast uses, so an
            // uninstall noticed here is undone exactly as thoroughly as one that arrived
            // live - icons inside folders included.
            var swept = false
            removedApps.forEach { packageName ->
                invalidateIconCache(packageName)
                wp81IconProvider.invalidate(packageName)
                if (removeDesktopIconsForPackage(packageName)) swept = true
            }
            if (swept) {
                refreshWP81Tiles()
                refreshWP81OpenFolder()
            }

            // Update the known apps list with current apps
            if (newApps.isNotEmpty() || removedApps.isNotEmpty()) {
                saveKnownApps(currentApps)
            }

            // Refresh the app list
            loadInstalledApps()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error detecting app changes", e)
        }
    }

    private fun startPeriodicAppChecking() {
        stopPeriodicAppChecking() // Stop any existing checker

        appCheckRunnable = Runnable {
            checkForNewApps()

            // Schedule next check
            appCheckRunnable?.let { runnable ->
                handler.postDelayed(runnable, APP_CHECK_INTERVAL)
            }
        }

        // Start first check after a short delay
        appCheckRunnable?.let { runnable ->
            handler.postDelayed(runnable, 5000) // 5 seconds initial delay
        }

    }

    private fun stopPeriodicAppChecking() {
        appCheckRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }
        appCheckRunnable = null
    }








    private var wp81Shell: rocks.gorjan.gokixp.wp81.WP81Shell? = null

    /**
     * When back was last pressed on Start with nothing to answer it, for [wp81BackAgain].
     * Zero once a press has been spent, so a third press starts a fresh pair.
     */
    private var wp81LastIdleBackAt = 0L

    /**
     * Set when back-back sent the user away without the phone's app history to go on, so
     * the one offer of that access is made when they come back rather than to their heels.
     */
    private var wp81OfferUsageAccessOnReturn = false

    /**
     * Whether the launcher has been away behind another app since it was last on screen.
     *
     * Set when the activity stops - which only happens once something else is covering the
     * whole screen - and cleared when the user is looking at it again. [onNewIntent] reads
     * it to tell the two homes apart: the home gesture made from inside another app, which
     * is a way back here, and the one made while already here, which is a way to Start.
     * The flag is cleared in onResume rather than onStart because the order of onStart
     * against onNewIntent is not guaranteed, and onResume is documented to follow both.
     */
    private var wp81AwayBehindAnotherApp = false
    private val wp81IconProvider by lazy {
        rocks.gorjan.gokixp.wp81.MonochromeIconProvider(this)
    }

    /**
     * Where the user has been, from the phone's history and from this launcher's own.
     *
     * Not only the phone shell's: it is also what back-back on Start reads to find the last
     * app, and [launchSystemApp] writes to it under every theme, because when a program was
     * last opened is a fact about the launcher rather than about whichever shell is drawing
     * at the time. See [rocks.gorjan.gokixp.wp81.RecentAppsStore].
     */
    private val wp81Recents by lazy {
        rocks.gorjan.gokixp.wp81.RecentAppsStore(this) { hasUsageAccess() }
    }
    private var wp81LiveTileRunnable: Runnable? = null
    private val wp81Handler = Handler(Looper.getMainLooper())

    /** Latest next-event summary for the Calendar live widget, or null when there is none. */
    private var wp81NextCalendarEvent: Pair<String, Long>? = null

    private var wp81CalendarProvider:
        rocks.gorjan.gokixp.quickglance.CalendarDataProvider? = null
    /**
     * What is on the Start screen, and how each tile is painted.
     *
     * Shared with the car screen, which builds its own wall from this same object so that
     * a tile pinned or recoloured here turns up there without being added twice.
     */
    private val wp81TileHost by lazy {
        rocks.gorjan.gokixp.wp81.WP81TileHost(
            context = this,
            icons = { desktopIcons },
            saveIcons = { saveDesktopIcons() },
            persistTiles = { tiles -> persistWP81Tiles(tiles) },
            displayName = { pkg, original -> getCustomOrOriginalName(pkg, original) },
            landscape = { wp81Landscape() }
        )
    }

    private var wp81MediaSessions: rocks.gorjan.gokixp.wp81.MediaSessions? = null

    /**
     * Tile glyphs for the built-in programs. Third-party apps go through
     * [rocks.gorjan.gokixp.wp81.MonochromeIconProvider] instead; these are fixed because
     * the built-ins have no Android icon to derive anything from.
     */
    private val wp81SystemGlyphs: Map<String, Int> = mapOf(
        "system.internet_explorer" to R.drawable.wp81_glyph_ie,
        "system.registry_editor" to R.drawable.wp81_glyph_regedit,
        "system.dialer" to R.drawable.wp81_glyph_dialer,
        "system.notepad" to R.drawable.wp81_glyph_notepad,
        "system.winamp" to R.drawable.wp81_glyph_winamp,
        "system.zune" to R.drawable.wp81_glyph_headphones,
        "system.news" to R.drawable.wp81_glyph_news,
        "system.welcome" to R.drawable.wp81_glyph_welcome,
        "system.wmp" to R.drawable.wp81_glyph_wmp,
        "system.minesweeper" to R.drawable.wp81_glyph_minesweeper,
        "system.solitare" to R.drawable.wp81_glyph_solitaire,
        "system.pinball" to R.drawable.wp81_glyph_pinball,
        "system.clock" to R.drawable.wp81_glyph_clock,
        "system.calculator" to R.drawable.wp81_glyph_calculator,
        "system.people" to R.drawable.wp81_glyph_people,
        "system.alarms" to R.drawable.wp81_glyph_clock,
        "system.weather" to R.drawable.wp81_glyph_weather,
        "system.files" to R.drawable.wp81_glyph_files
    )

    /**
     * The shell's own Metro programs, as against the desktop-era ones it also carries.
     *
     * These are the apps written for this shell - full screen, no title bar, laid out the
     * way the phone laid things out - and in the app list they are drawn the way Windows
     * Phone drew the programs that came with it: the glyph in white on a square of the
     * accent. Notepad, the browser and the calculator have desktop versions too, and open
     * as those under the desktop themes; what is listed here is what they are *under this
     * one*, which is where the app list is.
     *
     * A new Metro app belongs in this set and in [wp81SystemGlyphs], and nowhere else.
     */
    private val wp81MetroApps: Set<String> = setOf(
        "system.internet_explorer",
        "system.notepad",
        "system.welcome",
        "system.zune",
        "system.news",
        "system.calculator",
        "system.people",
        "system.minesweeper",
        "system.solitare",
        "system.alarms",
        "system.weather",
        "system.files"
    )

    /**
     * Puts the desktop back after the Windows Phone shell has been over it.
     *
     * The mirror of what [applyWindowsPhone81Theme] takes away. Written as a restore rather
     * than as an undo inside each desktop theme, because there are four of those and they
     * all want the same thing: a screen that looks like nothing else was ever on it.
     */
    /**
     * Puts the two things that have to be seen over a program above the windows rather
     * than inside the shell.
     *
     * WP81Shell builds both as its own children, which is right for everything the shell
     * itself draws and wrong the moment a program is open: the shell draws at elevation 0
     * and floating_windows_container at 50dp, so a toast raised from inside a window - a
     * download that has landed, a picture that has been saved - played its sound behind the
     * program and was never seen. Elevation only orders a view against its own siblings, so
     * being topmost in the shell cannot lift it over the container beside the shell; it has
     * to be moved out to sit next to that container instead.
     *
     * The task switcher has exactly the same problem and it matters more there: the whole
     * point of it is to get out of the program you are in, and one that opened behind that
     * program would be a key that appeared to do nothing.
     *
     * Both are kept at the navigation keys' height, which is where WP81Shell had them: the
     * toast stays within reach of a thumb, and the switcher leaves the keys uncovered -
     * back is the way out of it.
     */
    private fun liftWP81Overlays(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        val density = resources.displayMetrics.density
        val root = findViewById<RelativeLayout>(R.id.main_background) ?: return
        // The switcher first, so the toast still lands on top of it - a program announcing
        // something while the switcher is up is still worth reading.
        for ((view, elevationDp) in listOf(
            shell.recents to RECENTS_ELEVATION_DP,
            shell.toast to TOAST_ELEVATION_DP
        )) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.elevation = elevationDp * density
            root.addView(
                view,
                RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    bottomMargin =
                        (rocks.gorjan.gokixp.wp81.WP81NavBar.HEIGHT_DP * density).toInt()
                }
            )
        }
    }


    private fun applyWindowsPhone81Theme() {
        Log.d("MainActivity", "Applying Windows Phone 8.1 theme")

        // Tear down the desktop metaphor. None of this has a counterpart on a phone.
        findViewById<View>(R.id.desktop_icons_container)?.visibility = View.GONE
        findViewById<View>(R.id.taskbar_container)?.visibility = View.GONE
        findViewById<View>(R.id.start_menu_container)?.visibility = View.GONE
        findViewById<View>(R.id.gesture_bar_background)?.visibility = View.GONE
        val palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)

        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)
        mainBackground?.background = null
        mainBackground?.setBackgroundColor(palette.background)

        // root_container is declared black in the layout, and it is what shows through the
        // padding held back for the system status and navigation bars. Left alone, a Light
        // theme would sit in a black frame top and bottom.
        findViewById<View>(R.id.root_container)?.setBackgroundColor(palette.background)

        // The fake mouse cursor is a desktop conceit; a phone is touch-only.
        findViewById<View>(R.id.cursor_effect)?.visibility = View.GONE

        // Drop any wallpaper a previous theme left behind. It is added at index 0 of
        // main_background, so it would otherwise sit on top of anything added there.
        mainBackground?.findViewWithTag<ImageView>("wallpaper")?.let { wallpaper ->
            mainBackground.removeView(wallpaper)
        }

        val shell = wp81Shell ?: rocks.gorjan.gokixp.wp81.WP81Shell(
            this, palette, wp81IconProvider
        ).also { created ->
            wp81Shell = created
            // Added last, at elevation 0: above every other elevation-0 sibling, but still
            // below floating_windows_container (elevation 50dp), so windowed programs keep
            // drawing over the shell.
            mainBackground?.addView(
                created,
                RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                )
            )
            liftWP81Overlays(created)
            wireWP81Shell(created)
        }

        shell.applyPalette(palette)
        applyWP81SystemBarAppearance(palette)
        applyWP81StartBackground()
        rebaseFloatingWindowsForWP81()

        // The phone keeps its own hand-picked icons, so they have to be read for this
        // theme the way each desktop theme reads its own. Without this the shell arrived
        // still holding the previous theme's mappings - showing its icons on the tiles,
        // and writing that borrowed set back out under the phone's key the moment one
        // tile icon was changed.
        loadCustomIconMappings()
        wp81IconProvider.invalidateAll()

        refreshWP81Tiles()
        refreshWP81AppList()
        startWP81LiveTiles()

    }

    /**
     * Points Android's own status bar icons the right way.
     *
     * WP8.1's shell is either black or white end to end, and the system bar sits directly
     * on it, so the icons have to invert with the Light/Dark setting or they vanish.
     */
    private fun applyWP81SystemBarAppearance(palette: rocks.gorjan.gokixp.wp81.WP81Palette) {
        val controller = androidx.core.view.WindowCompat
            .getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.isDark
        controller.isAppearanceLightNavigationBars = !palette.isDark
    }

    private fun wireWP81Shell(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        wireWP81Settings(shell)
        shell.startScreen.onLaunch = { tile -> launchWP81Tile(tile) }
        shell.startScreen.onSwipeDownAtTop = { expandNotificationShade() }
        // Pushing up at the bottom of Start reaches the app list, which still arrives from
        // the side: the gesture is a shortcut to the page, not a different way of showing it.
        shell.startScreen.onSwipeUpAtBottom = {
            // The same knock the shade gives when it is pulled out of the other end. Both
            // gestures push the wall off an edge and hand the screen to something else, so
            // both should land the same way.
            rocks.gorjan.gokixp.wp81.Haptics.tap(shell.startScreen)
            shell.openAppSearch()
        }
        shell.startScreen.onTilesChanged = { tiles ->
            persistWP81Tiles(tiles)
            // A resize decides whether the weather tile reads its forecast across itself
            // or turns it over face by face, and this is where a resize lands. Left to the
            // twelve-second tick, a tile dragged wider sat on a single reading for most of
            // the time the user spent looking at what they had just resized.
            refreshWP81Weather()
        }
        shell.startScreen.onTileUnpin = { tile -> unpinOrHideWP81Tile(tile) }
        // The foot mark is Alarms' - it wears that app's icon and is about that app's
        // alarms - so it opens Alarms rather than the tile it happens to be standing on.
        shell.startScreen.onAlarmMarkTap = { showAlarmsDialog() }
        // The arrow under the wall goes where the leftward swipe goes.
        // The arrow pages to the list and stops there. It used to drop into search as
        // well, which put a keyboard over the very list the arrow had just been pressed to
        // see - the arrow is how somebody who wants to *look* through their apps gets
        // there, and typing is what the search key and the jump list's globe are for.
        shell.startScreen.onOpenAppList = { shell.goToAppList() }
        // A folder opens into the wall rather than onto a page of its own.
        shell.startScreen.onFolderOpened = { folder -> openWP81FolderInline(folder) }
        // Resizing a tile inside an opened folder writes back to the folder's own icons.
        shell.startScreen.onFolderTilesChanged = { folderId, tiles ->
            persistWP81FolderTiles(folderId, tiles)
        }
        shell.startScreen.onTileFiled = { tile, folderId, alreadyMoved ->
            fileWP81Tile(tile, folderId, alreadyMoved)
        }
        shell.startScreen.onFolderRename = { folder -> renameWP81Tile(folder) }
        shell.startScreen.folderPreviewOf = { pair -> wp81PreviewOf(pair) }
        shell.startScreen.folderPreviewWith = { folder, incoming ->
            wp81PreviewWith(folder, incoming)
        }
        shell.startScreen.onTilesFoldered = { dragged, onto -> foldWP81Tiles(dragged, onto) }

        shell.appList.onLaunch = { app ->
            if (isSystemApp(app.packageName)) {
                // A program of this shell's own opens in a window over the shell, so the
                // shell has to be somewhere worth opening over: left in search, the list
                // and its keyboard stayed in front of the window that had just opened, and
                // pressing search on a sole result looked like it had done nothing.
                shell.appList.endSearch()
                shell.goToStart(animated = false)
                launchSystemApp(app.packageName)
            } else {
                // Straight out to the app, with nothing done to the list first. Leaving
                // search here - re-filtering the rows, dropping the keyboard - was work
                // done in front of somebody who had already asked for something else, and
                // it read as the tap hesitating before the app opened. The list is put
                // back to rest once the launcher is actually behind the app: see onStop.
                packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                }
            }
        }
        // The shell's own programs wear their glyph on an accent square; everything else
        // keeps the icon it was installed with. See AppListView.metroGlyph.
        shell.appList.metroGlyph = { app ->
            if (app.packageName in wp81MetroApps) wp81SystemGlyphs[app.packageName] else null
        }
        shell.appList.onLongPress = { app, anchorY ->
            // No buzz of its own: the row gives the shell's tick as it claims the press.
            // See AppListView's long-click listener, and wp81.Haptics.
            // A menu over a keyboard leaves the commands squeezed into what is left of the
            // screen; the search text is kept so the list is unchanged on the way back.
            shell.appList.hideKeyboard()
            shell.contextMenu.show(app.name, wp81AppMenu(app), anchorY)
        }

        shell.onSearch = { launchWebSearch() }
        shell.appList.onSearchWeb = { query ->
            // Leaving search behind: coming back to a list still filtered to nothing, with
            // the keyboard up, is not where anyone wants to land after being sent away.
            shell.appList.endSearch()
            searchTheWebFor(query)
        }
        shell.navBar.onBack = { onBackPressedDispatcher.onBackPressed() }
        // Holding it: the task switcher. See WP81NavBar.applyHold for why the hold is timed
        // by hand rather than left to the framework's long press.
        shell.onRecents = { openWP81Recents() }
        wireWP81Recents(shell)
        // Folders are made by holding one tile over another, as on the phone, so there is
        // no "new folder" command any more. "Remove from folder" is on the tile's own
        // command list, where the rest of the once-a-tile things live.
        shell.secondaryBar.onAddApp = { addAppToOpenWP81Folder() }
        shell.secondaryBar.onTileColor = {
            shell.selectedTile()?.let { tile ->
                shell.colorPicker.show(tile.label, themeManager.getWP81TileColors()[tile.id])
            }
        }
        shell.colorPicker.onPicked = { color ->
            shell.selectedTile()?.let { tile ->
                themeManager.setWP81TileColor(tile.id, color)
                wp81TileHost.refreshColors()
                if (themeManager.getWP81HideTileColors()) {
                    // Stored, but nothing on screen will change until the switch in
                    // settings goes back off - and a command that silently does nothing is
                    // a command the user will assume is broken.
                    showNotification("Tile color", "Saved \u2013 tile colors are hidden")
                } else {
                    // Repainted in place: rebuilding the wall to change one colour would
                    // drop the selection and replay every tile's entrance.
                    shell.startScreen.setTileColor(tile.id, color)
                    shell.folderPage.contents.setTileColor(tile.id, color)
                }
            }
        }
        shell.secondaryBar.onTileMenu = {
            // Anchored to the strip the command came from, so the list opens next to it.
            shell.selectedTile()?.let { tile ->
                shell.contextMenu.show(
                    tile.label,
                    // Asked of the tile, not of the screen: a folder opened into the wall
                    // leaves the folder page shut, so its tiles were offered "unpin from
                    // start" - a command that could not apply to them and did nothing.
                    wp81TileMenu(tile, inFolder = wp81TileIsInFolder(tile)),
                    shell.height * 0.45f
                )
            }
        }

        // Windowed programs sit on black: WP8.1 has no desktop for them to float over.
        // The same signal tells the shell where the user is, so the key strip answers for
        // the program on screen rather than for the Start screen hidden behind it.
        floatingWindowManager.onWindowCountChanged = { visible ->
            val wasCovered = wp81ProgramOnScreen
            wp81ProgramOnScreen = visible > 0
            shell.setWindowBackdropVisible(visible > 0)
            shell.programOnScreen = visible > 0
            // The last program has gone and Start is back, so it arrives rather than
            // simply being there again - and it has to. Launching from a tile turns the
            // whole wall away and leaves it transparent, and the only thing that ever put
            // it back was the Start screen becoming visible again. That never happens to a
            // program in a window: the shell is covered by a backdrop drawn over it, not
            // hidden, so closing one left the wall turned away on a black screen.
            if (wasCovered && visible == 0) shell.startScreen.playEntrance()
        }
        // And once now: the callback only fires on a change, so a program left open across
        // a theme switch would otherwise have the shell believing it was on Start.
        floatingWindowManager.notifyWindowVisibilityChanged()

        // The Start key means Start. With a program on screen the shell's own answer -
        // "you are already on Start, so open the searchable app list" - was true of the
        // shell and wrong for the user, who was looking at Zune and got a keyboard.
        //
        // Programs are put away rather than shut down: nothing here has been asked to
        // stop, Zune in particular is still playing, and every one of them comes back by
        // being launched again.
        // Holding the Start key: the shell's own commands, as against a tile's. Settings
        // is here rather than on the key strip, which is now the three hardware keys on
        // every page - and the shell's commands are what a hold on Start should offer
        // anyway. The app list is built once and cached, so an app installed since then is
        // invisible until something asks for it again - and until now nothing did short of
        // switching theme.
        shell.navBar.onStartLongPress = {
            // The command list belongs to the shell, which sits under the window
            // container - so with a program on screen it would open where nobody could
            // see it. There the hold does what the tap does: put the program away.
            if (!minimiseWP81Windows()) shell.contextMenu.show(
                "start",
                listOfNotNull(
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("settings") {
                        openWP81Settings()
                    },
                    // What is hidden is a fact about the wall, not about whichever tile
                    // happened to be selected when the user went looking for it - which is
                    // where this used to be, on the command list of a tile that had nothing
                    // to do with it. Offered only once something is hidden: a list of
                    // nothing is a command that answers a question nobody asked.
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("hidden tiles") {
                        showWP81HiddenTiles()
                    }.takeIf { themeManager.getWP81HiddenTiles().isNotEmpty() },
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("refresh app list") {
                        refreshWP81AppList()
                        showNotification("App list", "Looking for new apps")
                    },
                    // Welcome is where the release notes are, and it is the nearest thing
                    // this shell has to an about box. Straight to it, rather than to the
                    // update the tile diverts to: this is a command that was asked for by
                    // name, not a tile showing something.
                    rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("about") {
                        showWelcomeDialogWP81()
                    }
                ),
                // Low on the screen, next to the key the command came from.
                shell.height * 0.62f
            )
        }
        shell.navBar.onStart = {
            when {
                // The switcher stands over whatever the user was in, program windows
                // included, so it is the innermost thing on screen and goes first.
                shell.closeRecents() -> Unit
                minimiseWP81Windows() -> Unit
                // To the list, not into a search of it - the same thing the arrow under
                // the wall does. A key pressed to see what is installed should not answer
                // with a keyboard over it; the swipe across is the gesture that means
                // "and I am about to type".
                shell.isOnStartPage() -> shell.goToAppList()
                else -> shell.goToStart()
            }
        }
    }

    /**
     * The floating window container is laid out for the desktop themes - 60dp of headroom
     * and 70dp for the taskbar. Under WP8.1 it has to clear the status and navigation bars
     * instead.
     */
    private fun rebaseFloatingWindowsForWP81() {
        val container = findViewById<View>(R.id.floating_windows_container) ?: return
        val density = resources.displayMetrics.density
        val params = container.layoutParams as RelativeLayout.LayoutParams
        params.topMargin = 0
        params.bottomMargin = (rocks.gorjan.gokixp.wp81.WP81NavBar.HEIGHT_DP * density).toInt()
        container.layoutParams = params
    }

    // ---------------------------------------------------------------- tiles

    /**
     * Builds the Start screen from the launcher's existing desktop icons.
     *
     * Icons with no tile placement yet - which is all of them the first time this theme is
     * used - are migrated in their existing desktop grid order and given a medium tile,
     * so the user's arrangement carries over rather than being thrown away.
     */
    private fun buildWP81Tiles(): List<rocks.gorjan.gokixp.wp81.Tile> =
        wp81TileHost.buildTiles()


    /**
     * Pushes current notification text onto the tiles.
     *
     * Called from [updateNotificationDots], which the listener already invokes on every
     * change, so tiles track the shade without a poll of their own.
     */
    private fun refreshWP81Notifications() {
        val shell = wp81Shell ?: return
        // A missed call arriving or being dismissed decides whether the People tile is a
        // wall of faces or an icon with a number on it. Only when the answer has actually
        // changed: this pass runs every two seconds, and rebuilding the mosaic on each of
        // them would reshuffle the faces under the user twice a minute.
        val missed = NotificationListenerService.missedCalls().isNotEmpty()
        val texts = NotificationListenerService.messages().isNotEmpty()
        if (missed != wp81MissedCalls || texts != wp81Messages) {
            wp81MissedCalls = missed
            wp81Messages = texts
            refreshWP81People()
        }
        shell.startScreen.setNotifications { tile -> wp81NotificationsFor(tile) }
        // The folder page shows tiles too, so it gets the same treatment while it is open.
        shell.folderPage.setNotifications { tile -> wp81NotificationsFor(tile) }
        // A folder's preview carries the same information as a dot, one app at a time, so
        // it is refreshed on the same pass.
        shell.startScreen.setFolderPreviews { tile -> wp81FolderPreviewFor(tile) }
        shell.folderPage.setFolderPreviews { tile -> wp81FolderPreviewFor(tile) }
        refreshWP81Media()
    }

    /**
     * Which package's media session a tile should read.
     *
     * Its own, for a real app. Zune is a program inside this launcher rather than an
     * installed app, so its session is held by *this* process - and a tile looking for one
     * under "system.zune" would never find anything, leaving the one player on the phone
     * that belongs to this shell as the only one whose tile stayed dead.
     */
    private fun wp81MediaPackageFor(tile: rocks.gorjan.gokixp.wp81.Tile): String =
        if (tile.packageName == "system.zune") packageName else tile.packageName

    /**
     * Pushes what each app is playing onto its tile.
     *
     * Applied after notifications so that media wins where both exist: a tile can only say
     * one thing, and what is playing now beats what arrived earlier.
     */
    private fun refreshWP81Media() {
        val shell = wp81Shell ?: return
        val sessions = wp81MediaSessions?.active().orEmpty()
        val lookup: (rocks.gorjan.gokixp.wp81.Tile) -> rocks.gorjan.gokixp.wp81.MediaSessions.Info? =
            { tile -> sessions[wp81MediaPackageFor(tile)] }
        shell.startScreen.setMedia(lookup)
        shell.folderPage.contents.setMedia(lookup)

        for (surface in listOf(shell.startScreen, shell.folderPage.contents)) {
            surface.setMediaHandlers(
                onPlayPause = { tile -> wp81MediaSessions?.togglePlayPause(wp81MediaPackageFor(tile)) },
                onNext = { tile -> wp81MediaSessions?.next(wp81MediaPackageFor(tile)) },
                onPrevious = { tile -> wp81MediaSessions?.previous(wp81MediaPackageFor(tile)) }
            )
        }
    }

    /**
     * Notification lines for a tile.
     *
     * A folder has no notifications of its own, so it stands in for everything filed
     * inside it - otherwise something arriving in a folder would be invisible from Start,
     * which is the one screen the user is looking at.
     */
    /** The stories behind the News tile. Fed by whichever feeds are switched on. */
    private val wp81NewsFeed by lazy {
        rocks.gorjan.gokixp.wp81.NewsFeed { refreshWP81News() }
    }

    /**
     * Hands the News tile the run of stories it turns through.
     *
     * Headline over source: a tile has room for what happened and who says so, and the
     * summary underneath it would leave neither legible.
     */
    private fun refreshWP81News() {
        // The reader, if it is open, shows the same stories as the tile.
        newsAppInstance?.bind()
        val shell = wp81Shell ?: return
        val faces = wp81NewsFeed.stories().map { story ->
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = story.title,
                detail = story.source.takeIf { it.isNotBlank() },
                image = story.image
            )
        }
        val waiting = listOf(
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = if (themeManager.getWP81NewsFeeds().isEmpty()) "No feeds turned on"
                        else "Fetching the news…",
                detail = "News"
            )
        )
        // The tiles are rebuilt often; the loader is theirs for as long as they live. One
        // loader serves the whole wall, so it answers for both kinds of picture: a story's,
        // fetched over the network, and one of the user's own, read out of MediaStore.
        shell.startScreen.setBackdropLoader { source, onReady ->
            if (source.startsWith("content:")) {
                rocks.gorjan.gokixp.wp81.PhotoFeed.load(this, source, onReady)
            } else {
                rocks.gorjan.gokixp.wp81.NewsImages.load(source, onReady)
            }
        }
        shell.startScreen.setLiveWidgetRotation(
            WP81_WIDGET_NEWS,
            faces.ifEmpty { waiting },
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.STORY
        )
    }

    /**
     * The story the News tile has on its face this moment, if it has one.
     *
     * The tile turns through [NewsFeed.stories] in order and knows how far along it is, so
     * the face is a lookup rather than anything the tile has to be asked to remember. Null
     * while the tile is still showing the line it puts up in place of a story.
     */
    private fun wp81NewsStoryOnTile(): rocks.gorjan.gokixp.wp81.NewsStory? {
        val shell = wp81Shell ?: return null
        return wp81NewsFeed.stories()
            .getOrNull(shell.startScreen.rotationIndexOf(WP81_WIDGET_NEWS))
    }

    /** Reads the feeds, if the tile that shows them is on Start. */
    private fun refreshWP81NewsFeeds(force: Boolean = false) {
        if (!wp81HasNewsTile()) return
        wp81NewsFeed.refreshIfStale(themeManager.getWP81NewsFeeds().toList().sorted(), force)
    }

    private fun wp81HasNewsTile(): Boolean =
        wp81Shell?.startScreen?.tiles()?.any { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS } == true

    // ---------------------------------------------------------------- photos

    /** The camera roll the Photos tile is turning through, newest first. */
    private var wp81Photos: List<rocks.gorjan.gokixp.wp81.PhotoFeed.Shot> = emptyList()

    /** When that was last read, so new pictures arrive without re-reading on every tick. */
    private var wp81PhotosReadAt = 0L
    private var wp81PhotosLoading = false

    private fun wp81HasPhotosTile(): Boolean =
        wp81Shell?.startScreen?.tiles()
            ?.any { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS } == true

    /**
     * Hands the Photos tile the run of pictures it turns through.
     *
     * Three states, and the tile says which one it is in: no permission yet, in which case
     * it is an invitation rather than a slideshow; permission but nothing read back; and
     * the pictures themselves, which carry no words at all - a photograph on a tile is not
     * captioned, it is looked at.
     */
    private fun refreshWP81Photos(force: Boolean = false) {
        val shell = wp81Shell ?: return
        if (!wp81HasPhotosTile()) return

        if (!rocks.gorjan.gokixp.wp81.PhotoFeed.hasAccess(this)) {
            wp81Photos = emptyList()
            wp81PhotosReadAt = 0L
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PHOTOS,
                listOf(
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "Photos",
                        detail = "tap to allow",
                        glyph = R.drawable.wp81_glyph_photos
                    )
                ),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            return
        }

        val age = System.currentTimeMillis() - wp81PhotosReadAt
        if (!wp81PhotosLoading && (force || wp81PhotosReadAt == 0L || age > WP81_PHOTOS_MAX_AGE_MS)) {
            wp81PhotosLoading = true
            // A forced read is one where what was there is no longer to be trusted - the
            // permission just changed - so what was decoded under it goes too.
            if (force) rocks.gorjan.gokixp.wp81.PhotoFeed.clear()
            rocks.gorjan.gokixp.wp81.PhotoFeed.recent(this) { shots ->
                wp81PhotosLoading = false
                wp81PhotosReadAt = System.currentTimeMillis()
                wp81Photos = shots
                refreshWP81Photos()
            }
        }

        val faces = wp81Photos.map { shot ->
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = "",
                detail = null,
                image = shot.uri,
                // The picture is the tile. See TileView.LiveFace.washed.
                washed = false,
                // A clip plays on the tile rather than sitting there as its first frame.
                motion = shot.isVideo
            )
        }
        val waiting = listOf(
            rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                title = "Photos",
                detail = if (wp81PhotosLoading) "looking\u2026" else "no pictures yet",
                glyph = R.drawable.wp81_glyph_photos
            )
        )
        shell.startScreen.setLiveWidgetRotation(
            WP81_WIDGET_PHOTOS,
            faces.ifEmpty { waiting },
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
        )
    }

    /**
     * Tapping the Photos tile: the permission first, the app after.
     *
     * The first tap is the opt-in, because the shell has nowhere else to ask - and asking
     * on first run, for a tile the user may never have wanted, is how a launcher earns a
     * reputation. Once it has been granted the tile does what it says: it opens the
     * pictures.
     */
    private fun openWP81Photos() {
        if (!rocks.gorjan.gokixp.wp81.PhotoFeed.hasAccess(this)) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                rocks.gorjan.gokixp.wp81.PhotoFeed.permissions(),
                PHOTOS_PERMISSION_REQUEST_CODE
            )
            return
        }
        // Reading the roll again on the way out. A tap on this tile is someone going to
        // look at their pictures, which is exactly the moment the tile behind them should
        // stop showing the ones from before the last time they did - and it is the only
        // signal the shell gets that anything has been taken since.
        refreshWP81Photos(force = true)
        packageManager.getLaunchIntentForPackage(WP81_PHOTOS_PACKAGE)?.let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(it)
            return
        }
        // No Google Photos on this phone; hand the pictures to whatever does show them.
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.w("MainActivity", "WP8.1: nothing on this phone opens pictures", e)
            showNotification("Photos", "No gallery app found")
        }
    }

    // ---------------------------------------------------------------- people

    /** The address book the People tile fills itself from: favourites, then the rest. */
    private var wp81People = rocks.gorjan.gokixp.wp81.ContactFeed.Book(emptyList(), emptyList())

    /** When that was last read, so a new contact arrives without re-reading on every tick. */
    private var wp81PeopleReadAt = 0L
    private var wp81PeopleLoading = false

    /** Whether a missed call was showing last time the shade was read. See above. */
    private var wp81MissedCalls = false

    /** And whether a text message was. The tile steps aside for either. */
    private var wp81Messages = false

    private fun wp81HasPeopleTile(): Boolean =
        wp81Shell?.startScreen?.tiles()
            ?.any { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE } == true

    /**
     * Hands the People tile the faces it fills itself with.
     *
     * Three states, as the Photos tile has: no permission, in which case the tile is an
     * invitation rather than a wall; permission but an address book with nobody in it,
     * which the tile says in as many words so it is clear it is working; and the people
     * themselves, who arrive as a mosaic rather than as a run of faces the tile turns over
     * - the tile *is* the grid, and it does its own shuffling from there.
     *
     * Handed over in the two parts ContactFeed reads them in, favourites and everybody
     * else, because how many of the second the wall needs depends on how many squares it
     * has - which is the tile's business, not this one's. See TileView.applyPeopleGrid.
     */
    private fun refreshWP81People(force: Boolean = false) {
        val shell = wp81Shell ?: return
        if (!wp81HasPeopleTile()) return

        // A missed call takes the tile, before any of the faces below get a turn at it.
        // Ahead of the reading branches on purpose: those say "People / looking…" while the
        // address book is being read and "People / no contacts yet" when it comes back
        // empty, and either of them standing over a missed call is the tile answering a
        // question nobody asked. With nothing of its own on it the tile falls to its icon
        // and the count beside it, which is what every other app on the wall does with
        // something waiting - and a missed call, unlike a wall of faces, is a thing to be
        // answered. See TileView.standingIn.
        val missed = NotificationListenerService.missedCalls().isNotEmpty()
        val texts = NotificationListenerService.messages().isNotEmpty()
        if (missed || texts) {
            // Which of the two it stepped aside for, said by the icon it steps aside to.
            // A call outranks a message when both are waiting: one of them is somebody who
            // tried to reach you and could not, and the other is somebody who did.
            shell.startScreen.setGlyph(WP81_WIDGET_PEOPLE, wp81PeopleStandInGlyph(missed))
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PEOPLE,
                emptyList(),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            shell.startScreen.setPeopleMosaic(WP81_WIDGET_PEOPLE, emptyList(), emptyList())
            return
        }

        if (!rocks.gorjan.gokixp.wp81.ContactFeed.hasAccess(this)) {
            wp81People = rocks.gorjan.gokixp.wp81.ContactFeed.Book(emptyList(), emptyList())
            wp81PeopleReadAt = 0L
            shell.startScreen.setPeopleMosaic(WP81_WIDGET_PEOPLE, emptyList(), emptyList())
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PEOPLE,
                listOf(
                    // No mark in the corner: the tile says what it is in words, and a
                    // silhouette over them is a second way of saying the same thing on a
                    // tile whose whole subject is faces.
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "People",
                        detail = "tap to allow"
                    )
                ),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            return
        }

        val age = System.currentTimeMillis() - wp81PeopleReadAt
        if (!wp81PeopleLoading && (force || wp81PeopleReadAt == 0L || age > WP81_PEOPLE_MAX_AGE_MS)) {
            wp81PeopleLoading = true
            // A forced read re-reads *who* is starred, and nothing more. The pictures are
            // not re-decoded with it: they belong to people whose faces have not changed
            // because the tile was tapped, and throwing them away here emptied the wall of
            // every face it had - the mosaic only asks for a picture when a square turns
            // over to somebody new, so the tile came back as a grid of initials and filled
            // itself in one square at a time over the next minute.
            rocks.gorjan.gokixp.wp81.ContactFeed.people(this) { book ->
                wp81PeopleLoading = false
                wp81PeopleReadAt = System.currentTimeMillis()
                wp81People = book
                refreshWP81People()
            }
        }

        if (wp81People.isEmpty) {
            shell.startScreen.setLiveWidgetRotation(
                WP81_WIDGET_PEOPLE,
                listOf(
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "People",
                        detail = if (wp81PeopleLoading) "looking\u2026" else "no contacts yet"
                    )
                ),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            return
        }

        // The words go before the faces do: the tile is one or the other, and clearing the
        // run it was turning through is what stops it flipping over a wall that has just
        // taken the front.
        shell.startScreen.setLiveWidgetRotation(
            WP81_WIDGET_PEOPLE,
            emptyList(),
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
        )
        // The faces come back the moment the missed call is dismissed, which is the same
        // moment the user has dealt with it - see the top of this function.
        shell.startScreen.setPeopleMosaic(
            WP81_WIDGET_PEOPLE, wp81People.favourites, wp81People.others)
    }

    /**
     * Tapping the People tile: the permission first, the phone after.
     *
     * The first tap is the opt-in, exactly as it is on the Photos tile - the shell has
     * nowhere else to ask, and an address book is not something to demand on first run for
     * a tile the user may never have wanted.
     *
     * Then People, which is the hub Windows Phone opened here - the same address book
     * this wall is made of, with the call log and the keypad in it. It used to open
     * whichever dialler the phone had, for want of anything better; a tile that is a wall
     * of faces from *this* book should land inside the app that book belongs to.
     */
    private fun openWP81People() {
        if (!rocks.gorjan.gokixp.wp81.ContactFeed.hasAccess(this)) {
            // The address book, and only the starred part of it - see ContactFeed.
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                rocks.gorjan.gokixp.wp81.ContactFeed.permissions(),
                CONTACTS_PERMISSION_REQUEST_CODE
            )
            return
        }
        // Reading the book again on the way in: a tap on this tile is somebody going to
        // find a person, which is the moment the tile behind them should stop showing the
        // faces from before the last time they added one.
        refreshWP81People(force = true)
        showPeopleDialog()
    }


    private fun wp81NotificationsFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.TileView.Line> =
        // An app that is playing something shows that instead; its notifications would be
        // competing for the same tile and are the less useful of the two.
        if (wp81MediaSessions?.active()?.containsKey(wp81MediaPackageFor(tile)) == true) {
            emptyList()
        } else if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE) {
            // The People tile's notifications are the calls that went unanswered. It has
            // no package of its own to read them from - it is this shell's own widget -
            // and they may have been posted by any of three apps depending on which one is
            // the phone, so they are gathered by what they are rather than by who sent
            // them. See NotificationListenerService.missedCalls.
            // Calls first, and messages only when there are none: both belong to this app
            // and the tile has one number to show, so the more pressing of the two takes
            // it whole rather than the pair being added into a total that is about nothing
            // in particular. See NotificationListenerService.messages.
            NotificationListenerService.missedCalls()
                .ifEmpty { NotificationListenerService.messages() }
                .map { rocks.gorjan.gokixp.wp81.TileView.Line(it.title, it.text) }
        } else if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER) {
            collectFolderNotifications(tile.id, mutableSetOf())
        } else {
            NotificationListenerService.getNotificationLines(tile.packageName)
                .map { rocks.gorjan.gokixp.wp81.TileView.Line(it.title, it.text) }
        } + wp81UpdateLine(tile)

    /**
     * The Welcome tile's live content: the update, when there is one.
     *
     * Reusing the notification path rather than inventing a second one - the tile turns
     * over to it, wears the unread dot and comes back to its icon exactly as an app's
     * tile does with a message. There is no notification behind it, only a version number
     * this launcher happens to know about itself.
     */
    private fun wp81UpdateLine(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.TileView.Line> {
        if (tile.packageName != "system.welcome") return emptyList()
        val version = updateAvailableVersion ?: return emptyList()
        return listOf(
            rocks.gorjan.gokixp.wp81.TileView.Line(
                "Update available", "Version $version \u00b7 tap to download")
        )
    }

    /**
     * Every notification from the apps inside [folderId], recursively.
     *
     * Each line is titled with the app it came from, since a folder tile showing bare
     * message text gives no clue which of its contents produced it. [visited] guards
     * against a folder cycle, which the data model does not forbid.
     */
    private fun collectFolderNotifications(
        folderId: String,
        visited: MutableSet<String>
    ): List<rocks.gorjan.gokixp.wp81.TileView.Line> {
        if (!visited.add(folderId)) return emptyList()
        val lines = mutableListOf<rocks.gorjan.gokixp.wp81.TileView.Line>()
        for (child in desktopIcons.filter { it.parentFolderId == folderId }) {
            if (child.type == IconType.FOLDER) {
                lines += collectFolderNotifications(child.id, visited)
                continue
            }
            val appName = getCustomOrOriginalName(child.packageName, child.name)
            for (line in NotificationListenerService.getNotificationLines(child.packageName)) {
                val detail = listOf(line.title, line.text)
                    .filter { it.isNotBlank() }
                    .joinToString(" \u00b7 ")
                lines += rocks.gorjan.gokixp.wp81.TileView.Line(appName, detail)
            }
        }
        return lines
    }

    /**
     * What each folder's tile previews, keyed by folder id.
     *
     * Built with the tiles rather than looked up per refresh: resolving a glyph reads the
     * package manager and rasterises artwork to measure it, and the notification pass runs
     * every couple of seconds. The contents of a folder change when the user changes them,
     * which is one of the things that rebuilds the tiles anyway.
     */
    private var wp81FolderPreviews: Map<String, List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry>> =
        emptyMap()

    /** Resolves every folder's contents into the mini tiles its own tile shows. */
    private fun buildWP81FolderPreviews() {
        wp81FolderPreviews = desktopIcons
            .filter { it.parentFolderId != null }
            .groupBy { it.parentFolderId!! }
            .mapValues { (_, children) ->
                children
                    // The same order the folder page lists them in, so the preview and the
                    // page inside it agree about what comes first.
                    .sortedWith(compareBy({ it.wp81TileIndex ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                    .map { icon -> wp81FolderEntryFor(icon) }
            }
    }

    /** What a folder holding these tiles would show, for the fold-together preview. */
    private fun wp81PreviewOf(
        tiles: List<rocks.gorjan.gokixp.wp81.Tile>
    ): List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry> =
        tiles.mapNotNull { tile ->
            desktopIcons.firstOrNull { it.id == tile.id }?.let { wp81FolderEntryFor(it) }
        }

    /** What an existing folder would show with one more tile in it, for the hover offer. */
    private fun wp81PreviewWith(
        folder: rocks.gorjan.gokixp.wp81.Tile,
        incoming: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry> =
        // The arrival last, which is where filing it will actually put it: a folder keeps
        // what it already had in order and adds to the end.
        wp81FolderPreviewFor(folder) + wp81PreviewOf(listOf(incoming))

    /**
     * Makes a folder of two tiles held together, where the lower one was standing, and
     * opens it.
     *
     * Opened straight away because a folder with two things in it is not finished: it wants
     * a name, and the heading of the open folder is where that is done. Arriving inside it
     * also shows what was actually made, which a new tile appearing on the wall does not.
     */
    private fun foldWP81Tiles(
        dragged: rocks.gorjan.gokixp.wp81.Tile,
        onto: rocks.gorjan.gokixp.wp81.Tile
    ) {
        val first = desktopIcons.firstOrNull { it.id == onto.id } ?: return
        val second = desktopIcons.firstOrNull { it.id == dragged.id } ?: return
        if (first.type == IconType.FOLDER || second.type == IconType.FOLDER) return

        val folderId = "folder_${System.currentTimeMillis()}"
        val folderIcon = AppCompatResources.getDrawable(this, R.drawable.folder_vista) ?: return
        desktopIcons.add(
            DesktopIcon(
                name = "New Folder",
                packageName = folderId,
                icon = folderIcon,
                x = 0f,
                y = 0f,
                id = folderId,
                type = IconType.FOLDER,
                // In the slot the tile it was dropped on was in, at that tile's size: the
                // folder appears where the user was looking, not at the end of the wall.
                tileSize = onto.size.name,
                tileIndex = first.tileIndex,
                tileSizeLandscape = onto.size.name,
                tileIndexLandscape = first.tileIndexLandscape
            )
        )

        // The one that was underneath first, so the folder opens in the order they were
        // put together in.
        first.parentFolderId = folderId
        first.wp81TileIndex = 0
        second.parentFolderId = folderId
        second.wp81TileIndex = 1

        saveDesktopIcons()
        refreshWP81Tiles()

        // After the rebuild, and after it has been laid out: the gap is placed against the
        // folder's own row, and the packer has not worked out where that is until then.
        wp81Shell?.startScreen?.post {
            wp81Shell?.startScreen?.tiles()?.firstOrNull { it.id == folderId }
                ?.let { openWP81FolderInline(it) }
        }
    }

    /** One app inside a folder, as its mini tile draws it. */
    private fun wp81FolderEntryFor(
        icon: DesktopIcon
    ): rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry {
        // Routed through the tile glyph resolver rather than straight to the launcher
        // icon, so a mini tile shows exactly what the app's own tile would: a custom icon
        // if the user set one, the themed monochrome layer if the app ships one.
        val glyph = wp81GlyphFor(
            rocks.gorjan.gokixp.wp81.Tile(
                id = icon.id,
                label = icon.name,
                packageName = icon.packageName,
                size = rocks.gorjan.gokixp.wp81.TileSize.SMALL,
                index = 0,
                kind = wp81KindFor(icon)
            )
        )
        return rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry(
            id = icon.id,
            icon = when (glyph) {
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome -> glyph.drawable
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor -> glyph.drawable
                null -> null
            },
            tint = glyph is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome,
            contentRatio = when (glyph) {
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome -> glyph.contentRatio
                is rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor -> glyph.contentRatio
                null -> 1f
            }
        )
    }

    /**
     * A folder's contents, as its tile previews them.
     *
     * Straight from the cache: a preview is icons, and what is unread inside the folder is
     * marked on the folder's name rather than on the squares.
     */
    private fun wp81FolderPreviewFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): List<rocks.gorjan.gokixp.wp81.FolderPreviewView.Entry> {
        if (tile.kind != rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER) return emptyList()
        return wp81FolderPreviews[tile.id].orEmpty()
    }

    /** Which kind of tile a desktop icon becomes. Shared by Start, folders and previews. */
    private fun wp81KindFor(icon: DesktopIcon): rocks.gorjan.gokixp.wp81.Tile.Kind =
        wp81TileHost.kindFor(icon)

    /**
     * Whether the phone is on its side.
     *
     * The Start screen keeps a wall for each way up: turned sideways the screen is twice
     * as wide and half as tall and the wall is packed into twice the columns, so the
     * arrangement that suited it upright is not an arrangement at all - tiles the user put
     * side by side end up on different rows and the order they chose reads as nothing.
     * Two arrangements, remembered separately, and each one only written when the phone is
     * being held that way.
     */
    private fun wp81Landscape(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * Where this icon sits on the wall the phone is currently holding, and how big.
     *
     * Reading sideways falls back to the upright placement while there is none of its own,
     * so the first turn of the phone lands on the wall the user already knows rather than
     * on an alphabetical one. Writing never falls back: it puts the arrangement where the
     * orientation it was made in will find it.
     */
    private var DesktopIcon.wp81TileIndex: Int?
        get() = if (wp81Landscape()) tileIndexLandscape ?: tileIndex else tileIndex
        set(value) {
            if (wp81Landscape()) tileIndexLandscape = value else tileIndex = value
        }

    private var DesktopIcon.wp81TileSize: String?
        get() = if (wp81Landscape()) tileSizeLandscape ?: tileSize else tileSize
        set(value) {
            if (wp81Landscape()) tileSizeLandscape = value else tileSize = value
        }

    /**
     * What a tile should actually be painted, which is nothing while colours are hidden.
     *
     * Null rather than the accent: a tile with no colour of its own is a window onto the
     * Start background, where one painted the accent would be a solid block of it - and
     * seeing the wallpaper is the entire point of the switch. See TileView.onDraw.
     */
    private fun wp81ColorFor(tile: rocks.gorjan.gokixp.wp81.Tile): Int? =
        wp81TileHost.colorFor(tile)

    /**
     * Repaints the wall for the current colour setting, without rebuilding it.
     *
     * In place, like the colour picker: rebuilding to change what is only a paint job
     * would drop the selection and replay every tile's entrance.
     */
    private fun applyWP81TileColors() {
        val shell = wp81Shell ?: return
        wp81TileHost.refreshColors()
        // The walls are told as well as the tiles: a tile with words on it is drawn over a
        // little black while the colours are held back, and null is not enough to say so -
        // an unpainted tile is handed null either way. See TileView.tileColorsHidden.
        val hidden = themeManager.getWP81HideTileColors()
        shell.startScreen.tileColorsHidden = hidden
        shell.folderPage.contents.tileColorsHidden = hidden
        for (tile in shell.startScreen.tiles()) {
            shell.startScreen.setTileColor(tile.id, wp81ColorFor(tile))
        }
        // The folder page is a wall of tiles too, and may be open in front of this one.
        for (tile in shell.folderPage.contents.tiles()) {
            shell.folderPage.contents.setTileColor(tile.id, wp81ColorFor(tile))
        }
    }

    private fun refreshWP81Tiles() {
        val shell = wp81Shell ?: return
        wp81TileHost.refreshColors()
        // Set before the tiles are built, so each one is born knowing which mark to wear.
        shell.startScreen.countsEnabled = themeManager.getWP81TileCounts()
        shell.startScreen.tileColorsHidden = themeManager.getWP81HideTileColors()
        shell.startScreen.columns = themeManager.getWP81Columns()
        buildWP81FolderPreviews()
        shell.startScreen.setTiles(
            buildWP81Tiles(),
            liveWidget = { tile -> wp81LiveWidgetContent(tile) },
            widgetGlyphs = { tile -> wp81WidgetGlyphFor(tile) },
            widgetBacks = { tile -> wp81LiveWidgetBack(tile) },
            alarmMarks = { tile -> wp81AlarmMarkFor(tile) },
            tileColors = { tile -> wp81ColorFor(tile) }
        ) { tile -> wp81GlyphFor(tile) }
        // The entrance is for arriving at Start, not for keeping it up to date. Every
        // rebuild replayed it - pinning an app, renaming a tile, a package changing
        // underneath - and every one of those blanked the whole wall and faded it back in.
        //
        // Coming home from another app is the worst of them: the system shows the launcher
        // as it left it, the activity is rebuilt behind that, and the tiles the user is
        // already looking at drop to nothing and stagger back. Played once per process, so
        // a genuine cold start still gets it.
        if (!wp81EntrancePlayed) {
            wp81EntrancePlayed = true
            shell.startScreen.playEntrance()
        }
        refreshWP81News()
        refreshWP81Photos()
        refreshWP81People()
        refreshWP81Weather()
        refreshWP81Notifications()
        refreshWP81Media()
    }

    /**
     * Headline and detail for a built-in live widget, or null if the tile is not one.
     *
     * Fed from the caches the Quick Glance widget already maintains, so the tiles say
     * something real rather than animating for its own sake, and cost nothing extra to
     * keep current.
     */
    private fun wp81LiveWidgetContent(tile: rocks.gorjan.gokixp.wp81.Tile): rocks.gorjan.gokixp.wp81.TileView.Reading? =
        wp81TileHost.liveContent(tile) { size -> wp81CalendarSummary(size) }

    /**
     * The date, over the next calendar entry - or a friendly empty state when there is
     * nothing on (or no permission to look).
     *
     * The day of the month is the reading; the weekday and the month stand beside it. The
     * narrowest strip abbreviates the weekday, which is otherwise wider than the room
     * beside a number.
     */
    private fun wp81CalendarSummary(size: rocks.gorjan.gokixp.wp81.TileSize): rocks.gorjan.gokixp.wp81.TileView.Reading =
        wp81TileHost.calendarSummary(
            size,
            wp81NextCalendarEvent?.let { "${it.first}\n${wp81EventWhen(it.second)}" }
        )

    /**
     * How many appointments tomorrow holds, and the first of them.
     *
     * Its own query rather than the Quick Glance provider's: that one exists to answer
     * "what is next", which is a different question and never looks past the event it
     * finds. Instances rather than Events, so a weekly meeting counts once for each time
     * it actually occurs rather than once for the series.
     *
     * Runs off the main thread - a calendar query walks every provider on the phone.
     */
    /**
     * When an appointment starts, in the terms it is worth saying it in.
     *
     * Close to, a clock time is arithmetic the reader has to do: "14:20" is only useful
     * once you have worked out what it is now, and the answer they wanted was how long
     * they have. Past a couple of hours that stops being true - "in 5 hours 40 minutes" is
     * a number nobody holds on to - and the time of day is the better answer again.
     */
    private fun wp81EventWhen(starts: Long): String {
        val locale = java.util.Locale.getDefault()
        val minutes = Math.ceil((starts - System.currentTimeMillis()) / 60000.0).toLong()
        // Already running: it was found because it has not finished, not because it has
        // not begun.
        if (minutes <= 0L) return "now"
        if (minutes < 60L) return if (minutes == 1L) "in 1 minute" else "in $minutes minutes"
        if (minutes <= RELATIVE_EVENT_MINUTES) {
            val hours = minutes / 60
            val rest = minutes % 60
            val said = if (hours == 1L) "in 1 hour" else "in $hours hours"
            return when (rest) {
                0L -> said
                1L -> "$said 1 minute"
                else -> "$said $rest minutes"
            }
        }
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(starts))
    }

    /**
     * The next appointment left today, and when it starts.
     *
     * Its own query rather than the Quick Glance provider's, which answers "what is next"
     * in words - "in 20 minutes" - and looks no further than six hours ahead. A tile says
     * the time itself and says it about the whole day.
     */
    private fun refreshWP81TodayEvent() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            wp81NextCalendarEvent = null
            return
        }
        Thread {
            val found = try {
                queryWP81NextEvent()
            } catch (e: Exception) {
                Log.w("MainActivity", "WP8.1: could not read today's calendar", e)
                null
            }
            runOnUiThread {
                wp81NextCalendarEvent = found
                wp81Shell?.startScreen?.let { start ->
                    start.tiles()
                        .firstOrNull { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR }
                        ?.let { tile ->
                            wp81LiveWidgetContent(tile)?.let { reading ->
                                start.setLiveWidgetContent(tile.id, reading)
                            }
                        }
                }
            }
        }.start()
    }

    /** The first appointment still to come today: its name, and when it begins. */
    private fun queryWP81NextEvent(): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        val end = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.timeInMillis.toString())
            .build()

        contentResolver.query(
            uri,
            arrayOf(
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.ALL_DAY
            ),
            null,
            null,
            "${android.provider.CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                // All-day entries are not appointments: birthdays, holidays and the
                // fortnight somebody blocked out as leave would have the tile reporting a
                // full day when there is nothing to actually be anywhere for. They also
                // begin at UTC midnight rather than local, so a day's worth of them lands
                // in whichever day that happens to be.
                //
                // Read from the cursor rather than filtered in the query: not every
                // provider honours a selection on Instances, and this cannot be argued
                // with.
                if (cursor.getInt(2) != 0) continue
                val title = cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                return title to cursor.getLong(1)
            }
        }
        return null
    }

    /**
     * Opens the phone's own clock app.
     *
     * Deliberately not [openClockApp], which is the launcher's fake Date and Time
     * Properties window: on a phone shell, tapping the clock should land in the real
     * clock. Falls back through the alarm intent, then the launch intent of whichever
     * clock package is installed.
     */
    private fun openPhoneClockApp() {
        val candidates = listOf(
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CALENDAR")
        )
        for (intent in candidates) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    Log.w("MainActivity", "Clock intent failed: $intent", e)
                }
            }
        }
        val fallbackPackages = listOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage"
        )
        for (pkg in fallbackPackages) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(it)
                return
            }
        }
        Log.w("MainActivity", "No clock app found on this device")
        showNotification("Clock", "No clock app is installed")
    }


    /**
     * The reverse of a live widget: the same reading told another way, or more of it.
     *
     * Returning null leaves a widget one-sided, and turning it over spins it back to
     * itself rather than doing nothing.
     */
    private fun wp81LiveWidgetBack(tile: rocks.gorjan.gokixp.wp81.Tile): rocks.gorjan.gokixp.wp81.TileView.Reading? {
        val locale = java.util.Locale.getDefault()
        val now = java.util.Date()
        return when (tile.kind) {
            // Front says the time; the back says which day it is. The full date is a
            // caption a 1x1 cannot hold, so there the weekday stands alone.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK -> rocks.gorjan.gokixp.wp81.TileView.Reading(
                number = java.text.SimpleDateFormat("EEEE", locale).format(now).lowercase(locale),
                caption = java.text.SimpleDateFormat("d MMMM yyyy", locale).format(now)
                    .lowercase(locale)
                    .takeUnless { tile.size == rocks.gorjan.gokixp.wp81.TileSize.SMALL }
            )

            // The weather turns through three faces of its own; it has no reverse.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null

            // One-sided: the index is on the front and the mark in the corner says which
            // index it is. Nothing is held back for a reverse.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> null

            // One-sided, like the index. What a calendar tile is for is the day you
            // are standing in - the date, and the next thing on it - and a face that
            // turns over to another day makes the reader wait to find out which day the
            // number they are looking at belongs to.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> null

            else -> null
        }
    }


    /**
     * What the weather tile turns through: what it is doing now, and the highs still worth
     * naming - today's until its peak has passed, tomorrow's always.
     *
     * Readings of the same kind, each labelled - a temperature with no label is a number,
     * and several of them in turn without labels are numbers that appear to disagree. The
     * 1x1 shortens the labels rather than dropping them: "max tomorrow" does not fit
     * across it, and "tomorrow" says the necessary half.
     *
     * Falls back to the current reading alone when the forecast has not arrived, which
     * leaves the tile still rather than turning between a number and two blanks.
     */
    private fun wp81WeatherFaces(
        size: rocks.gorjan.gokixp.wp81.TileSize
    ): List<rocks.gorjan.gokixp.wp81.TileView.LiveFace> = wp81TileHost.weatherFaces(size)

    /**
     * Hands the weather tile what it shows: a row of readings, or a run of faces.
     *
     * Which one depends on the footprint, and only on that. A tile two cells across and
     * two deep has the room to put now, today and tomorrow side by side, and a tile that
     * can show all three at once should not be making the user wait nine seconds for the
     * one they wanted. Anything smaller turns them over as it always did.
     *
     * Both are set on every refresh, one of them to nothing: a tile resized from wide to
     * small is the moment the panel has to come down and the faces have to start turning
     * again, and the size is read here rather than remembered.
     */
    private fun refreshWP81Weather() {
        // First, and outside everything below it. What follows gives up as soon as it
        // finds no weather tile on the wall, and somebody who has taken the tile off has
        // said nothing about wanting no warning when it is about to rain.
        considerRainNotification()
        val shell = wp81Shell ?: return
        val tile = shell.startScreen.tiles()
            .firstOrNull { it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER }
            ?: return
        val panel =
            if (tile.size.canShowForecast) wp81TileHost.weatherPanel() else emptyList()
        if (panel.isNotEmpty()) {
            // The faces go before the panel does, so the tile is not left turning over a
            // reading behind a row that has just taken the front. The same order the
            // People tile clears its own words in.
            shell.startScreen.setLiveWidgetRotation(
                tile.id, emptyList(), rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING)
            shell.startScreen.setForecast(tile.id, panel)
            return
        }
        shell.startScreen.setForecast(tile.id, emptyList())
        val faces = wp81WeatherFaces(tile.size)
        if (faces.isEmpty()) return
        shell.startScreen.setLiveWidgetRotation(
            tile.id, faces, rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING)
    }

    /**
     * Asks whether rain is close enough to be worth saying something about.
     *
     * Hung off the weather tile's refresh rather than given a job of its own: the forecast
     * is already being read there, and a second thing waking up to read the same cache
     * would be a second thing to go wrong. Cheap by design - see RainNotifier, which
     * mostly decides not to speak.
     */
    private fun considerRainNotification() {
        try {
            rocks.gorjan.gokixp.apps.weather.RainNotifier.consider(this)
        } catch (e: Exception) {
            Log.w("MainActivity", "could not check the forecast for rain", e)
        }
    }


    /**
     * The corner mark a live widget carries, if any.
     *
     * Only the News tile, now. Every other widget is a reading with a caption that names
     * it in words - "sunny", "good aqi", an appointment - and a mark repeating that is one
     * more thing on a tile whose whole job is to be read at a glance.
     */
    private fun wp81WidgetGlyphFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): Pair<Int?, Int?> = when (tile.kind) {
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null to null
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> null to null
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> null to null
        // The mark stays up on both faces: every one of them is a story, and a tile of
        // nothing but text needs something to say what it is.
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS ->
            R.drawable.wp81_glyph_news to R.drawable.wp81_glyph_news
        else -> null to null
    }

    /**
     * The mark at the foot of the clock and the calendar: an alarm is coming.
     *
     * Alarms is the app that knows, so it is Alarms' own icon, exactly as the mark it puts
     * in the status bar is - a foot mark and a line in the shade that came from the same
     * program should look like they did.
     *
     * The two tiles between them are what the phone says about the day it is in - the hour
     * and the date, the next thing on it - and an alarm inside the day is part of that
     * answer. Only this shell's own alarms: the mark wears Alarms' icon, and wearing it
     * for something set in another app would be pointing at the wrong program.
     *
     * Read from the store each time rather than cached. It is a short JSON array in the
     * preferences this shell has open anyway, the answer changes the moment an alarm is
     * set or goes off, and a cache would only be somewhere for a stale mark to sit.
     */
    private fun wp81AlarmMarkFor(tile: rocks.gorjan.gokixp.wp81.Tile): Int? = when (tile.kind) {
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK,
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR ->
            R.drawable.wp81_glyph_clock.takeIf { wp81AlarmDueWithinDay() }

        else -> null
    }

    /** Whether any of the shell's alarms next goes off inside the coming day. */
    private fun wp81AlarmDueWithinDay(): Boolean {
        val now = System.currentTimeMillis()
        val horizon = now + WP81_ALARM_HORIZON_MS
        return rocks.gorjan.gokixp.apps.alarms.AlarmStore.all(this).any { alarm ->
            // Its next occurrence, which is what an alarm turned off, snoozed or sitting
            // one morning out has none of - see AlarmScheduler.nextTrigger.
            val at = rocks.gorjan.gokixp.apps.alarms.AlarmScheduler.nextTrigger(alarm, now)
            at != null && at <= horizon
        }
    }




    /**
     * The mark the People tile wears while it has stepped aside for the shade.
     *
     * Only ever on screen while something is waiting - see refreshWP81People - so it says
     * what is waiting rather than naming the app. A handset with an arrow for a call that
     * went unanswered, a speech bubble for a message that arrived. The People icon here
     * would be the tile introducing itself at the one moment it has something else to say.
     */
    private fun wp81PeopleStandInRes(missedCall: Boolean): Int =
        if (missedCall) R.drawable.wp81_glyph_missed_call else R.drawable.wp81_glyph_message

    private fun wp81PeopleStandInGlyph(
        missedCall: Boolean
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? =
        wp81GlyphOf(wp81PeopleStandInRes(missedCall))

    /** One of this shell's own drawables, as a tile glyph. */
    private fun wp81GlyphOf(
        res: Int
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? {
        val drawable = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(this, res) ?: return null
        return rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome(
            drawable,
            wp81IconProvider.ratioFor("res:$res", drawable)
        )
    }

    /** Resolves the art for one tile: fixed glyph for built-ins, provider for real apps. */
    private fun wp81GlyphFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? {
        // An icon the user chose outranks everything derived - the app's themed monochrome
        // layer, its notification silhouette, and the built-in glyphs for system tiles.
        // Without this check the provider preferred an app's Android 13 themed icon, and a
        // custom icon simply never appeared for any app that ships one.
        if (hasCustomIcon(tile.packageName)) {
            getAppIcon(tile.packageName)?.let { drawable ->
                return rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor(
                    drawable,
                    wp81IconProvider.ratioFor("custom:${tile.packageName}", drawable)
                )
            }
        }

        val fixed = when (tile.kind) {
            rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER -> R.drawable.wp81_glyph_folder
            rocks.gorjan.gokixp.wp81.Tile.Kind.MY_COMPUTER -> R.drawable.wp81_glyph_computer
            rocks.gorjan.gokixp.wp81.Tile.Kind.RECYCLE_BIN -> R.drawable.wp81_glyph_recycle
            rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT -> R.drawable.wp81_glyph_ie
            rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP ->
                wp81SystemGlyphs[tile.packageName] ?: R.drawable.wp81_glyph_computer
            rocks.gorjan.gokixp.wp81.Tile.Kind.APP -> null
            rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS -> R.drawable.wp81_glyph_settings
            rocks.gorjan.gokixp.wp81.Tile.Kind.WELCOME -> R.drawable.wp81_glyph_welcome
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null
            // Live widgets render their content directly; there is no glyph to resolve.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> null
            // The one live widget that has an icon of its own, because it is the one that
            // steps aside for a notification - and the mark it steps aside *to* is the
            // notification, not the app: this glyph is only ever on screen while a call has
            // gone unanswered, so it says so. A handset with the People icon on it would be
            // the tile naming itself at the one moment it has something else to say.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE ->
                wp81PeopleStandInRes(
                    NotificationListenerService.missedCalls().isNotEmpty())
        }
        if (fixed != null) return wp81GlyphOf(fixed)
        return wp81IconProvider.glyphFor(tile.packageName, getAppIcon(tile.packageName))
    }

    // ---------------------------------------------------------------- task switcher

    /**
     * Hooks up the switcher's three answers, once, when the shell is built.
     *
     * Separate from [openWP81Recents], which is called every time the key is held: the
     * callbacks belong to the view and outlive any one showing of it.
     */
    private fun wireWP81Recents(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        shell.recents.onOpen = { card ->
            // Down before the app comes up. The switcher is over everything, program
            // windows included, so left standing it would be covering whatever the tap
            // had just opened.
            shell.closeRecents()
            if (isSystemApp(card.id)) {
                // The same door every other way into these programs uses, so a card
                // resumes a window that is already open rather than building a second one.
                launchSystemApp(card.id)
            } else {
                packageManager.getLaunchIntentForPackage(card.id)?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                } ?: run {
                    // Uninstalled since the history was read. Taken off the list rather
                    // than left there to fail again next time.
                    Log.d("MainActivity", "WP8.1 switcher: ${card.id} is gone; dropping it")
                    wp81Recents.dismiss(card.id)
                    showNotification("Recent apps", "${card.label} is no longer installed")
                }
            }
        }
        shell.recents.onDismiss = { card -> wp81Recents.dismiss(card.id) }
        shell.recents.onGrantAccess = {
            // The switcher goes with the user to Android's own settings screen: they are
            // leaving to answer a question this screen asked, and coming back to a stale
            // list held over from before the answer would be the wrong thing to return to.
            shell.closeRecents()
            requestUsageAccess()
        }
    }

    /**
     * Opens the switcher on whatever the two histories say between them.
     *
     * The cards are painted here rather than in the view for a reason the app list already
     * had: which glyph an app wears, what a tile of it is painted and what the user has
     * renamed it to are questions this class answers for the Start screen, and a second
     * answer worked out in the view is how a program comes to look like one thing on a
     * tile and another on a card. Each entry is turned back into a [rocks.gorjan.gokixp.wp81.Tile]
     * so [wp81GlyphFor] and [wp81ColorFor] - which is what paints the wall - answer for it
     * too, including a tile that inherits its colour from the folder it is filed in.
     */
    private fun openWP81Recents() {
        val shell = wp81Shell ?: return
        val startedAt = android.os.SystemClock.uptimeMillis()
        // The list this shell offers, which is where the phone-only and desktop-only rules
        // have already been applied - so a Phone Dialer opened yesterday on the desktop is
        // not offered here. See getSystemAppsList.
        val systemApps = getSystemAppsList().associateBy { it.packageName }
        val listedAt = android.os.SystemClock.uptimeMillis()
        val hidden = getHiddenApps()
        val visits = wp81Recents.recents(systemApps.keys)
        val scannedAt = android.os.SystemClock.uptimeMillis()
        val cards = visits.mapNotNull { visit ->
            val system = systemApps[visit.id]
            // Hidden from the app list means hidden here too: the switcher is another way
            // of naming what is on the phone, and one that quietly ignored the setting
            // would be a hole in it.
            if (system == null && visit.id in hidden) return@mapNotNull null
            val label = when {
                system != null -> getCustomOrOriginalName(visit.id, system.name)
                else -> androidAppLabel(visit.id) ?: return@mapNotNull null
            }
            val tile = rocks.gorjan.gokixp.wp81.Tile(
                // A pinned app's tile is filed under its package, but an app migrated from
                // the desktop is filed under the icon it came from - and that identifier is
                // what a tile colour is stored against. Falling back to the package covers
                // everything that is not on Start at all, which simply has no colour.
                id = desktopIcons.firstOrNull { it.packageName == visit.id }?.id ?: visit.id,
                label = label,
                packageName = visit.id,
                size = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM,
                index = 0,
                kind = if (system != null) rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP
                else rocks.gorjan.gokixp.wp81.Tile.Kind.APP
            )
            rocks.gorjan.gokixp.wp81.WP81RecentsView.Card(
                id = visit.id,
                label = label,
                glyph = wp81GlyphFor(tile),
                color = wp81ColorFor(tile)
            )
        }
        shell.recents.show(cards, hasUsageAccess())
        // The switcher is the one surface reached by holding a key, so every millisecond
        // between the buzz and the cards is a millisecond the user spends wondering whether
        // the hold registered. Logged rather than guessed at: the work here is a usage-stats
        // scan and a rebuild of the system app list, and which of them is the expensive one
        // is not something to have an opinion about.
        Log.d("MainActivity", "Recents: apps=${listedAt - startedAt}ms " +
            "scan=${scannedAt - listedAt}ms cards=${android.os.SystemClock.uptimeMillis() - scannedAt}ms " +
            "(${cards.size} cards)")
    }

    /**
     * What an installed app calls itself, or null if it is not installed any more.
     *
     * Asked of the package manager one at a time rather than taken from the cached app
     * list, because the switcher shows at most ten apps and the cached list is loaded off
     * the main thread when the shell arrives - which is far too late for a key that has
     * just been held.
     */
    private fun androidAppLabel(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        getCustomOrOriginalName(packageName, info.loadLabel(packageManager).toString())
    } catch (e: Exception) {
        Log.d("MainActivity", "WP8.1 switcher: no such package $packageName")
        null
    }

    /**
     * Back pressed on Start, where a single press has nowhere to go.
     *
     * The second of two quick presses switches to whatever app the user was in last -
     * the phone's held-back task switcher boiled down to the one entry anybody reaches
     * for. The first press still does nothing at all, so a lone press is unchanged.
     *
     * Returns true when the press was spent on the switch.
     */
    private fun wp81BackAgain(): Boolean {
        if (wp81Shell == null) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val isSecond = now - wp81LastIdleBackAt <= WP81_BACK_AGAIN_MS
        // Spent either way: a second press that found no app to open must not also count
        // as the first of the next pair, or the key becomes doubly-armed after any miss.
        wp81LastIdleBackAt = if (isSecond) 0L else now
        if (!isSecond) return false

        val switched = openLastLaunchedApp()
        if (!hasUsageAccess() && !hasAskedUsageAccess()) {
            // Offered once, and offered where it can be read: if the switch worked the
            // user is already looking at the other app, so the offer waits until they
            // come back rather than being shown to an empty screen.
            if (switched) wp81OfferUsageAccessOnReturn = true else offerUsageAccess()
        }
        return switched
    }

    /**
     * Whether the phone will tell us which app was last in front.
     *
     * A special access rather than a runtime permission: it cannot be prompted for, only
     * granted by hand on the phone's own screen, so everything here treats it as
     * something that may never arrive.
     */
    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        ops != null && ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        Log.w("MainActivity", "Could not read usage access state: ${e.message}")
        false
    }

    private fun hasAskedUsageAccess(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_ASKED_USAGE_ACCESS, false)

    /**
     * The last app the phone itself had in front, whoever opened it.
     *
     * This is what back-back is really after: an app reached from a notification, or from
     * a link inside another app, is somewhere the user was just as much as a tile they
     * tapped. Null when the access has not been granted, or when nothing in the window
     * qualifies - the caller falls back on what the launcher opened itself.
     *
     * The scan itself lives in [rocks.gorjan.gokixp.wp81.RecentAppsStore], which reads the
     * same history into an ordered list for the task switcher. One app or ten is the same
     * walk over the same events, and two copies of it would drift.
     */
    private fun lastForegroundApp(): String? = wp81Recents.lastForegroundApp()

    /**
     * Offers the access, once, from wherever the user just felt the lack of it.
     *
     * Marked as offered before the notification is even tapped: the point is not to ask
     * twice, whatever the answer was.
     */
    private fun offerUsageAccess() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_ASKED_USAGE_ACCESS, true).apply()
        showNotification(
            "Back twice for the last app",
            "Tap to let Start see which app you used last"
        ) { requestUsageAccess() }
    }

    /** Opens the phone's Usage access screen, at this app's own row where it can. */
    private fun requestUsageAccess() {
        val screen = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        try {
            // The per-app deep link, so the user lands on the one switch they came for
            // rather than on a list of every app that has ever asked.
            usageAccessLauncher.launch(
                Intent(screen).setData(Uri.fromParts("package", packageName, null))
            )
        } catch (e: Exception) {
            try {
                usageAccessLauncher.launch(screen)
            } catch (e2: Exception) {
                Log.e("MainActivity", "No usage access screen on this phone", e2)
                showNotification("App history", "This phone has no usage access screen")
            }
        }
    }

    /**
     * Brings the last app the launcher opened back to the front, as tapping its tile
     * would: the task is resumed where it was left rather than restarted.
     *
     * Returns false when there is nothing to go back to - nothing launched yet this
     * install, or the app has since been uninstalled or disabled.
     */
    private fun openLastLaunchedApp(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // The phone's own answer first - it knows about every app, not only the ones this
        // launcher opened - and what we noted ourselves when it has not been granted.
        val fromHistory = lastForegroundApp()
        val target = fromHistory ?: prefs.getString(KEY_LAST_LAUNCHED_APP, null) ?: return false
        val intent = packageManager.getLaunchIntentForPackage(target)
        if (intent == null) {
            // Gone since it was noted. Forget it rather than keep failing on it.
            Log.d("MainActivity", "Last app $target is no longer launchable; forgetting")
            if (fromHistory == null) prefs.edit().remove(KEY_LAST_LAUNCHED_APP).apply()
            return false
        }
        return try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            Log.d("MainActivity", "Back-back on Start: returning to $target")
            true
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to return to $target: ${e.message}")
            false
        }
    }

    /**
     * Notes an app the launcher is about to open, for [openLastLaunchedApp].
     *
     * Hooked on [startActivity] rather than at each of the two dozen places that launch
     * something, so a tile, an app-list row, a folder, the swipe-right app and the search
     * key all count without any of them having to remember to say so. Only an app's own
     * front door counts: a share sheet, a web link or a settings screen is not somewhere
     * the user thinks of themselves as having been.
     */
    private fun rememberLaunchedApp(intent: Intent) {
        if (intent.action != Intent.ACTION_MAIN) return
        // CATEGORY_INFO is what getLaunchIntentForPackage prefers when an app declares
        // one; HOME is us handing the screen to another launcher, which is not a visit.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) return
        if (!intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            !intent.hasCategory(Intent.CATEGORY_INFO)
        ) return
        val target = intent.component?.packageName ?: intent.`package` ?: return
        if (target == packageName) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_LAST_LAUNCHED_APP, target).apply()
    }

    // Both overloads: the one-argument form delegates to the other on current Android,
    // but noting the same package twice costs nothing and this does not depend on it.
    override fun startActivity(intent: Intent) {
        rememberLaunchedApp(intent)
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        rememberLaunchedApp(intent)
        super.startActivity(intent, options)
    }

    private fun launchWP81Tile(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val icon = desktopIcons.firstOrNull { it.id == tile.id }
        when (tile.kind) {
            // Folders are opened in place by the wall itself - see onFolderOpened - so a
            // launch only reaches here for one tapped somewhere that has no wall to part:
            // inside another folder's band.
            rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER -> openWP81Folder(tile)
            // My Computer was the desktop's way into storage, and it went with the
            // desktop. A tile carried over from there opens Files, which is the phone's
            // own answer to the same question - better than a tile that does nothing.
            rocks.gorjan.gokixp.wp81.Tile.Kind.MY_COMPUTER -> showFilesDialog()
            rocks.gorjan.gokixp.wp81.Tile.Kind.RECYCLE_BIN -> {
                // No-op, matching the desktop: the Recycle Bin has no window of its own
                // there either - it is a drop target with a context menu. The tile is kept
                // so the migrated Start screen still mirrors what was on the desktop.
                Log.d("MainActivity", "WP8.1: Recycle Bin tile tapped; no window to open")
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT -> openUrlShortcut(icon?.targetUrl)
            rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP -> launchSystemApp(tile.packageName)
            rocks.gorjan.gokixp.wp81.Tile.Kind.APP -> {
                packageManager.getLaunchIntentForPackage(tile.packageName)?.let {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                }
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK -> openPhoneClockApp()
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> openCalendarApp()
            // The forecast, not another refresh. A tile shows one reading at a time and
            // tapping it asks for the rest of it - the same relation the News tile has to
            // the reader it opens. What the user set as their weather app on the desktop
            // is deliberately not consulted: that setting is about the taskbar readout,
            // which is a number in a corner with nowhere of its own to go, and this tile
            // is the Weather app's own tile.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> showWeatherDialog()
            // The reader, not the story itself: a tile shows one headline at a time and
            // tapping it is a request for the rest of them, with the story on the face one
            // tap further in. But the reader opens *on* that story, marked - the tile was
            // pointing at something, and a page that landed at the top would lose it.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS -> showNewsDialog(wp81NewsStoryOnTile())

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS -> openWP81Photos()

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE -> openWP81People()

            rocks.gorjan.gokixp.wp81.Tile.Kind.WELCOME -> launchSystemApp("system.welcome")

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_AQI -> {
                // Tapping doubles as the opt-in, since the desktop checkbox that normally
                // enables air quality lives inside Settings. Enabling it also asks for a
                // reading, so the tile has something to show on the way back.
                if (!isShowAqiEnabled()) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit { putBoolean(KEY_SHOW_AQI, true) }
                    refreshAqiData()
                }
                // Then AirCare itself, which is where the taskbar indicator goes on every
                // other theme - the tile is that indicator, and should not behave differently
                // for being square.
                handleAqiTap()
            }
            rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS -> openWP81Settings()
        }
    }

    /**
     * Commands for a Start screen tile.
     *
     * Deliberately shorter than the desktop's icon menu: "Send to Desktop", "Set as Swipe
     * Right App" and "Change Icon" are desktop notions with nowhere to land here.
     *
     * Resizing and reordering are absent too - they are direct manipulations now, on the
     * tile's own bottom-right and top-right handles, which beats picking a verb from a
     * list and then doing the gesture anyway.
     */
    private fun wp81TileMenu(
        tile: rocks.gorjan.gokixp.wp81.Tile,
        inFolder: Boolean
    ): List<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item> {
        val shell = wp81Shell
        val items = mutableListOf<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item>()

        // Taking a tile out of a folder deletes it; on Start the same act is "unpin".
        if (inFolder && !tile.kind.isBuiltIn) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("remove from folder") {
                removeWP81TileFromFolder(tile)
            })
        }

        // Both of these used to have a key on the strip, which is now the colour key.
        // Settings is excluded on purpose: it is the only route back to this screen.
        if (tile.kind.isBuiltIn && tile.kind != rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("hide tile") { hideWP81Tile(tile.id) })
        }

        if (!tile.kind.isBuiltIn && !inFolder) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("unpin from start") {
                shell?.startScreen?.unpinTile(tile)
            })
        }

        val icon = desktopIcons.firstOrNull { it.id == tile.id }

        if (!tile.kind.isBuiltIn) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("rename") { renameWP81Tile(tile) })
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("change icon") { changeWP81TileIcon(tile) })
        }

        // A web shortcut exists only on the Start screen, so removing it *is* deleting it -
        // "unpin" would understate what happens.
        if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT && icon != null) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("delete") {
                desktopIcons.removeAll { it.id == icon.id }
                saveDesktopIcons()
                refreshWP81Tiles()
            })
        }

        // Only real installed apps can be uninstalled or inspected.
        if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.APP) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("app info") { openAppInfo(tile.packageName) })
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("uninstall") {
                uninstallApp(AppInfo(tile.label, tile.packageName, icon = wp81BlankIcon()))
            })
        }

        return items
    }

    /** Commands for a row in the app list. */
    private fun wp81AppMenu(app: AppInfo): List<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item> {
        val shell = wp81Shell
        val items = mutableListOf<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item>()

        val pinnedIcon = desktopIcons.firstOrNull {
            it.packageName == app.packageName && it.parentFolderId == null
        }
        if (pinnedIcon != null) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("unpin from start") {
                desktopIcons.removeAll { it.id == pinnedIcon.id }
                saveDesktopIcons()
                refreshWP81Tiles()
                showNotification("Unpinned", app.name)
            })
        } else {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("pin to start") { pinWP81Tile(app) })
        }

        // Reuses the launcher's existing hidden-apps set, so a app hidden here is hidden
        // in the desktop themes' start menu too.
        val hidden = isAppHidden(app.packageName)
        items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item(if (hidden) "unhide" else "hide from list") {
            toggleHiddenApp(app.packageName)
            refreshWP81AppList()
        })

        if (!isSystemApp(app.packageName)) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("app info") { openAppInfo(app.packageName) })
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("uninstall") { uninstallApp(app) })
        }

        return items
    }

    /**
     * Renames a tile, through the Metro prompt rather than the Vista rename window.
     *
     * Writes to the same customNameMappings the desktop themes use, so a rename here shows
     * up on the desktop too rather than being a WP8.1-only alias.
     */
    private fun renameWP81Tile(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        val current = getCustomOrOriginalName(icon.packageName, icon.name)
        shell.inputDialog.show("rename", current) { newName ->
            if (newName.isEmpty()) customNameMappings.remove(icon.packageName)
            else customNameMappings[icon.packageName] = newName
            saveCustomNameMappings()
            refreshWP81Tiles()
        }
    }

    /**
     * Opens the Metro icon picker for a tile.
     *
     * Icons are decoded in batches on a background thread and handed over as they arrive:
     * the bundled sets run to several hundred files, and decoding them before showing the
     * page would stall it for seconds.
     */
    private fun changeWP81TileIcon(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        val picker = shell.iconPicker

        picker.show(tile.label)
        picker.onPicked = { path ->
            applyWP81CustomIcon(icon.packageName, path)
            picker.dismiss()
        }
        picker.onResetToDefault = {
            applyWP81CustomIcon(icon.packageName, "default")
            picker.dismiss()
        }
        picker.onBrowse = {
            setPendingImagePick(PICK_TARGET_WP81_ICON_PREFIX + icon.packageName)
            imagePickerLauncher.launch("image/*")
        }

        loadWP81IconChoices(picker)
    }

    // ---- pending image pick -----------------------------------------------------------

    private fun setPendingImagePick(target: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_PENDING_IMAGE_PICK, target)
        }
    }

    /** Reads and clears the pending target, so a stale one cannot claim a later pick. */
    private fun consumePendingImagePick(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val target = prefs.getString(KEY_PENDING_IMAGE_PICK, null)
        if (target != null) prefs.edit { remove(KEY_PENDING_IMAGE_PICK) }
        return target
    }

    /**
     * Applies an image picked for the Start background.
     *
     * Copied into app storage rather than stored as a content URI: the picker is
     * GetContent, which grants read access for this session only, so a remembered URI
     * would stop resolving after a reboot.
     */
    private fun applyPickedWP81Background(uri: Uri) {
        val stored = copyWP81BackgroundLocally(uri)
        if (stored == null) {
            showNotification("Start background", "That image could not be used")
            return
        }
        themeManager.setWP81StartBackground(stored)
        themeManager.setWP81StartBackgroundFocusX(0.5f)
        applyWP81StartBackground()
        refreshWP81BackgroundControls()
        // Straight onto the same command list a bundled wallpaper answers a hold with.
        // A photo the user went looking for is the one most likely to be wanted on the
        // phone as well, and there is nothing to hold here - it was chosen in a picker
        // that has already closed. Posted so the list is placed against a laid-out shell.
        wp81Shell?.let { shell ->
            shell.post { showWP81WallpaperMenu(stored, shell.height * 0.4f) }
        }
    }

    /** Applies an image picked as a tile icon for [packageName]. */
    private fun applyPickedWP81Icon(packageName: String, uri: Uri) {
        val stored = importCustomIconFromUri(uri)
        if (stored == null) {
            showNotification("Change icon", "That image could not be used")
            return
        }
        applyWP81CustomIcon(packageName, stored)
        wp81Shell?.iconPicker?.dismiss()
    }

    /** Streams the current theme's icon set into the picker, a batch at a time. */
    private fun loadWP81IconChoices(picker: rocks.gorjan.gokixp.wp81.WP81IconPicker) {
        // The phone's own set, which is not the set its window chrome would suggest: the
        // desktop icons Vista's key holds are drawn for a desktop, in colour, with
        // shadows, and none of them is what a Windows Phone tile should be offered.
        val folders = listOf(WP81_ICON_FOLDER, "custom_icons_programs")
        Thread {
            for (folder in folders) {
                val names = try {
                    assets.list(folder)?.sorted().orEmpty()
                } catch (e: Exception) {
                    Log.w("MainActivity", "WP8.1: cannot list $folder", e)
                    emptyList()
                }
                val batch = mutableListOf<rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice>()
                for (name in names) {
                    if (!name.matches(".*\\.(svg|png|jpg|jpeg|webp)$".toRegex(RegexOption.IGNORE_CASE))) continue
                    val path = "$folder/$name"
                    val drawable = try {
                        loadIconFromPath(path)
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    batch.add(rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice(path, drawable))
                    if (batch.size >= WP81_ICON_BATCH) {
                        val chunk = batch.toList()
                        batch.clear()
                        runOnUiThread { if (picker.isShowing()) picker.addChoices(chunk) }
                    }
                }
                if (batch.isNotEmpty()) {
                    val chunk = batch.toList()
                    runOnUiThread { if (picker.isShowing()) picker.addChoices(chunk) }
                }
            }
        }.start()
    }

    /** Commits a chosen icon through the same mappings the desktop themes read. */
    private fun applyWP81CustomIcon(packageName: String, path: String) {
        if (path == "default") customIconMappings.remove(packageName)
        else customIconMappings[packageName] = path
        saveCustomIconMappings()
        pruneUnusedImportedIcons()
        invalidateIconCache(packageName)
        // The artwork changed, so its measured proportions have to go too, or the new icon
        // is drawn scaled for the old one.
        wp81IconProvider.invalidate(packageName)
        refreshWP81Tiles()
    }

    /** Hides a built-in tile from Start. Its position is kept for when it comes back. */
    private fun hideWP81Tile(tileId: String) {
        themeManager.setWP81HiddenTiles(themeManager.getWP81HiddenTiles() + tileId)
        wp81Shell?.exitEditModeEverywhere()
        refreshWP81Tiles()
        showNotification("Tile hidden", "Bring it back from the hidden list while editing")
    }

    private fun restoreWP81Tile(tileId: String) {
        themeManager.setWP81HiddenTiles(themeManager.getWP81HiddenTiles() - tileId)
        refreshWP81Tiles()
    }

    /**
     * The top-right edit handle's job, which depends on whose tile it is.
     *
     * A tile the user pinned comes off Start; one the shell provides is hidden instead,
     * because unpinning it would only last until the next refresh rebuilt it; and one
     * inside an opened folder is taken out of the folder, which deletes it - the same
     * thing the handle does on the folder page.
     */
    private fun unpinOrHideWP81Tile(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        when {
            tile.kind.isBuiltIn -> hideWP81Tile(tile.id)
            // A folder opens into a band inside the wall, and the tiles in that band are
            // not on the wall: they are in a grid of the folder's own. Unpinning searched
            // the wall's list, did not find them and returned, so the handle on a tile
            // inside a folder did nothing whatsoever.
            wp81TileIsInFolder(tile) -> removeWP81TileFromFolder(tile)
            else -> shell.startScreen.unpinTile(tile)
        }
    }

    /** Whether a tile is filed inside a folder rather than pinned to Start. */
    private fun wp81TileIsInFolder(tile: rocks.gorjan.gokixp.wp81.Tile): Boolean =
        desktopIcons.firstOrNull { it.id == tile.id }?.parentFolderId != null

    /** Human-readable names for the hideable built-ins, for the restore list. */
    private fun wp81BuiltInLabel(id: String): String = when (id) {
        WP81_WIDGET_CLOCK -> "Clock"
        WP81_WIDGET_WEATHER -> "Weather"
        WP81_WIDGET_AQI -> "Air quality"
        WP81_WIDGET_CALENDAR -> "Calendar"
        WP81_WIDGET_NEWS -> "News"
        WP81_WIDGET_PHOTOS -> "Photos"
        WP81_WIDGET_PEOPLE -> "People"
        "system.welcome" -> "Welcome"
        WP81_WIDGET_SETTINGS -> "Settings"
        else -> id
    }

    /** Lists what has been hidden, so any of it can be put back. */
    private fun showWP81HiddenTiles() {
        val shell = wp81Shell ?: return
        val hidden = themeManager.getWP81HiddenTiles()
        if (hidden.isEmpty()) return
        val items = hidden.sorted().map { id ->
            rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item(wp81BuiltInLabel(id)) { restoreWP81Tile(id) }
        }
        shell.contextMenu.show("hidden tiles", items, shell.height * 0.4f)
    }

    /** Placeholder icon for the AppInfo that [uninstallApp] only reads the package from. */
    private fun wp81BlankIcon(): Drawable =
        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

    /**
     * Opens the WP8.1 settings page and populates its wallpaper strip.
     *
     * Wallpaper drawables are decoded off the main thread - there are a few dozen bundled
     * and decoding them inline visibly stutters the page-in.
     */
    private fun openWP81Settings() {
        val shell = wp81Shell ?: return
        shell.openSettings()
        shell.settingsPage.setDefaultBrowser(isDefaultBrowser())
        shell.settingsPage.setLastAppAccess(hasUsageAccess())
        refreshDefaultBrowser = { shell.settingsPage.setDefaultBrowser(isDefaultBrowser()) }
        Thread {
            val items = try {
                // The strip's squares are 72x120dp - see WP81SettingsView.wallpaperTile.
                val stripPx = (120 * resources.displayMetrics.density).toInt()
                loadWallpapers(previewPx = stripPx).mapNotNull { item ->
                    val drawable = item.drawable ?: return@mapNotNull null
                    item.filePath?.let { path -> path to drawable }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: failed to load wallpapers", e)
                emptyList()
            }
            runOnUiThread {
                shell.settingsPage.setWallpapers(items, themeManager.getWP81StartBackground())
                refreshWP81BackgroundControls()
            }
        }.start()
    }

    private fun wireWP81Settings(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        shell.settingsPage.onBack = { shell.closeSettings() }
        shell.settingsPage.onAccentPicked = { color ->
            commitWP81Appearance(color, themeManager.isWP81Dark())
        }
        shell.settingsPage.onDarkPicked = { dark ->
            commitWP81Appearance(themeManager.getWP81Accent(), dark)
        }
        shell.settingsPage.onBackgroundPicked = { path ->
            themeManager.setWP81StartBackground(path)
            // A fresh pick starts centred; the preview strip is how it gets reframed.
            themeManager.setWP81StartBackgroundFocusX(0.5f)
            applyWP81StartBackground()
            refreshWP81BackgroundControls()
        }
        shell.settingsPage.onBlurChanged = { amount ->
            themeManager.setWP81StartBackgroundBlur(amount)
            refreshWP81Blur()
        }
        shell.settingsPage.onDriftChanged = { enabled ->
            themeManager.setWP81StartBackgroundDrift(enabled)
            shell.setBackgroundDrift(enabled)
        }
        shell.settingsPage.onHideTileColorsChanged = { hidden ->
            themeManager.setWP81HideTileColors(hidden)
            applyWP81TileColors()
        }
        shell.settingsPage.onTileCountsChanged = { enabled ->
            themeManager.setWP81TileCounts(enabled)
            applyWP81TileCounts()
        }
        shell.settingsPage.onColumnsPicked = { columns ->
            themeManager.setWP81Columns(columns)
            shell.startScreen.columns = columns
            shell.folderPage.contents.columns = columns
        }
        // Written to the same key Display Properties uses, so the answer is the launcher's
        // rather than each shell's. See openUrlShortcut.
        shell.settingsPage.setOpenLinksInIe(isOpenUrlsInIeEnabled())
        shell.settingsPage.onOpenLinksInIeChanged = { enabled ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(KEY_OPEN_URLS_IN_IE, enabled)
            }
        }
        // Where the rest of the phone's links go. Read from the system rather than kept
        // here - see isDefaultBrowser - and asked again when the user comes back from
        // Android's own prompt.
        shell.settingsPage.setDefaultBrowser(isDefaultBrowser())
        shell.settingsPage.onDefaultBrowser = { requestDefaultBrowser() }
        // Read from the phone in the same way, and for the same reason: it is granted and
        // revoked on Android's own screen, so the row can only report what it finds.
        shell.settingsPage.setLastAppAccess(hasUsageAccess())
        shell.settingsPage.onLastAppAccess = { requestUsageAccess() }
        // No launcher-theme row: this app is the Windows Phone shell and nothing else.
        // The desktop themes it used to offer live in the other launcher now, and a row
        // that switched to one of them would be switching to a shell that is not here.
        shell.settingsPage.onBrowse = {
            setPendingImagePick(PICK_TARGET_WP81_BACKGROUND)
            imagePickerLauncher.launch("image/*")
        }
        shell.settingsPage.onWallpaperLongPress = { source, anchorY ->
            showWP81WallpaperMenu(source, anchorY)
        }
    }

    /**
     * Where a wallpaper can go besides Start.
     *
     * A tap on one of these dresses the Start screen, which is the shell's own business
     * and all it used to be able to do. The phone underneath has two more walls of its
     * own - the launcher it falls back to and the lock screen - and the picture the user
     * is looking at is as good for those as for this one. The desktop themes have offered
     * this since they had a wallpaper picker; this is the same offer in the shape WP8.1
     * asks a question, which is a command list under the thing being asked about.
     *
     * Start itself is not on the list. Tapping the wallpaper already does that, and a
     * command list that repeats the tap is a list with a wasted line on it.
     */
    private fun showWP81WallpaperMenu(source: String, anchorY: Float) {
        val shell = wp81Shell ?: return
        shell.contextMenu.show(
            "wallpaper",
            listOf(
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("apply to lock screen") {
                    applyWP81WallpaperToDevice(source, system = false, lock = true)
                },
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("apply to system wallpaper") {
                    applyWP81WallpaperToDevice(source, system = true, lock = false)
                },
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("apply to both") {
                    applyWP81WallpaperToDevice(source, system = true, lock = true)
                },
                // Nothing to undo - the list has done nothing yet - so this is the row that
                // closes it. WP8.1 put one on every command list that could be opened by
                // accident, and a hold on a wallpaper is exactly that kind of press.
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("cancel") { }
            ),
            anchorY
        )
    }

    /**
     * Puts a wallpaper on the phone itself: the system wall, the lock screen, or both.
     *
     * Off the main thread, and at the size the wallpaper is actually drawn at rather than
     * the size Start needs. The Start background is downsampled to the screen and kept in
     * 565 - it sits behind tiles under a blur, where neither costs anything - but a lock
     * screen is the picture itself, and a gradient in 565 is a gradient in visible bands.
     */
    private fun applyWP81WallpaperToDevice(source: String, system: Boolean, lock: Boolean) {
        if (!system && !lock) return
        Thread {
            val bitmap = decodeWallpaperFullSize(source)
            if (bitmap == null) {
                runOnUiThread {
                    showNotification("Wallpaper", "That image could not be used")
                }
                return@Thread
            }
            val flags = (if (system) android.app.WallpaperManager.FLAG_SYSTEM else 0) or
                (if (lock) android.app.WallpaperManager.FLAG_LOCK else 0)
            val done = try {
                android.app.WallpaperManager.getInstance(this)
                    .setBitmap(bitmap, null, true, flags)
                true
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: failed to set device wallpaper", e)
                false
            }
            runOnUiThread {
                val where = when {
                    system && lock -> "Applied to lock screen and system wallpaper"
                    system -> "Applied to system wallpaper"
                    else -> "Applied to lock screen"
                }
                showNotification(
                    "Wallpaper",
                    if (done) where else "The wallpaper could not be changed"
                )
            }
        }.start()
    }

    /**
     * Decodes a wallpaper at its own size, from a bundled asset or a picked image.
     *
     * Sampled down only when the image is larger than the wall it is going on - the
     * wallpaper service's own desired size, doubled, which is the room a phone gives a
     * picture to be panned across. A bundled wallpaper is under that already and is
     * decoded whole.
     */
    private fun decodeWallpaperFullSize(source: String): Bitmap? = try {
        fun open(): java.io.InputStream? =
            if (source.startsWith("content://") || source.startsWith("file://")) {
                contentResolver.openInputStream(source.toUri())
            } else {
                assets.open(source)
            }

        val manager = android.app.WallpaperManager.getInstance(this)
        val metrics = resources.displayMetrics
        val targetW = maxOf(manager.desiredMinimumWidth, metrics.widthPixels * 2)
        val targetH = maxOf(manager.desiredMinimumHeight, metrics.heightPixels * 2)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, targetW, targetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: failed to decode wallpaper $source", e)
        null
    }

    /**
     * Copies a picked image into app storage and returns a file:// path for it, or null.
     * One slot, overwritten each time - only the current background is ever needed.
     */
    private fun copyWP81BackgroundLocally(uri: Uri): String? = try {
        val target = java.io.File(filesDir, "wp81_start_background.img")
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() > 0) Uri.fromFile(target).toString() else null
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: could not copy picked background", e)
        null
    }

    /** Cached Start background at full sharpness, so a drag never re-decodes. */
    private var wp81BackgroundBitmap: Bitmap? = null

    /** The blurred derivative actually handed to the tiles, keyed by the blur it was made at. */
    private var wp81BlurredBackground: Bitmap? = null
    private var wp81BlurredAmount = -1f

    /**
     * Where the built-in tiles sit, by tile id.
     *
     * The widgets and Settings have no DesktopIcon to hang a position on, so unlike the
     * user's tiles their placement lives here. Seeded once, the first time the theme is
     * used, and thereafter owned by the user exactly like any other tile - rebuilding them
     * at fixed positions on every refresh is what made them jump back to the top whenever
     * an icon changed or the user came home.
     */
    private fun loadWP81BuiltInPlacements(): MutableMap<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>> =
        wp81TileHost.loadBuiltInPlacements()

    private fun saveWP81BuiltInPlacements(placements: Map<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>>) =
        wp81TileHost.saveBuiltInPlacements(placements)

    /** Guards against a slow blur landing after a newer one has already been requested. */
    private var wp81BlurGeneration = 0


    /**
     * Recomputes the blurred background off the main thread and hands it to the shell.
     *
     * Blurring a wallpaper is tens of milliseconds even at a reduced working size, which
     * is far too slow to run per frame while the slider is moving - so the slider stays
     * responsive and the result catches up. Requests are generation-stamped so a slow one
     * cannot overwrite a newer result.
     */
    private fun refreshWP81Blur() {
        val shell = wp81Shell ?: return
        val source = wp81BackgroundBitmap
        val amount = themeManager.getWP81StartBackgroundBlur()
        val focusX = themeManager.getWP81StartBackgroundFocusX()

        if (source == null || amount <= 0.01f) {
            // Supersede anything still blurring. The slider fires all the way down, so
            // sliding to zero leaves a request for the last non-zero amount in flight -
            // and without claiming the generation here it landed a moment later and put
            // the blur straight back, which is why zero was not sharp.
            wp81BlurGeneration++
            wp81BlurredBackground?.recycle()
            wp81BlurredBackground = null
            wp81BlurredAmount = -1f
            shell.setStartBackground(source, focusX)
            return
        }
        if (kotlin.math.abs(amount - wp81BlurredAmount) < BLUR_QUANTISATION &&
            wp81BlurredBackground != null) {
            shell.setStartBackground(wp81BlurredBackground, focusX)
            return
        }

        val generation = ++wp81BlurGeneration
        Thread {
            val blurred = try {
                rocks.gorjan.gokixp.wp81.Blur.apply(source, amount)
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: blur failed", e)
                null
            }
            runOnUiThread {
                if (generation != wp81BlurGeneration || wp81Shell == null) {
                    // Superseded while we were working; drop it rather than flicker back.
                    if (blurred !== source) blurred?.recycle()
                    return@runOnUiThread
                }
                val previous = wp81BlurredBackground
                wp81BlurredBackground = blurred
                wp81BlurredAmount = amount
                shell.setStartBackground(
                    blurred ?: source,
                    themeManager.getWP81StartBackgroundFocusX()
                )
                // Recycled only after the tiles have been handed the replacement.
                if (previous !== source) previous?.recycle()
            }
        }.start()
    }

    /** Offers the settings page's blur slider, for a background that is actually set. */
    /** Pushes the numbers-or-dots setting onto the wall, and onto an open folder page. */
    private fun applyWP81TileCounts() {
        val shell = wp81Shell ?: return
        val enabled = themeManager.getWP81TileCounts()
        shell.startScreen.countsEnabled = enabled
        shell.folderPage.contents.countsEnabled = enabled
    }

    private fun refreshWP81BackgroundControls() {
        val shell = wp81Shell ?: return
        shell.settingsPage.setTileControls(
            themeManager.getWP81TileCounts(), themeManager.getWP81Columns())
        shell.settingsPage.setBackgroundControls(
            wp81BackgroundBitmap != null,
            themeManager.getWP81StartBackgroundBlur(),
            themeManager.getWP81StartBackgroundDrift(),
            themeManager.getWP81HideTileColors()
        )
    }

    /**
     * Loads and applies the Start background photo, or clears it.
     *
     * Accepts either a bundled asset path or a content:// URI from the picker, and
     * downsamples to roughly screen size - a full-resolution camera photo is many times
     * more pixels than the tiles will ever show.
     */
    private fun applyWP81StartBackground() {
        val shell = wp81Shell ?: return
        val source = themeManager.getWP81StartBackground()
        val focusX = themeManager.getWP81StartBackgroundFocusX()
        // Set before the photo: the drift is cut out of the crop, so the crop has to know
        // whether there will be any before it is taken.
        shell.setBackgroundDrift(themeManager.getWP81StartBackgroundDrift())
        if (source == null) {
            wp81BackgroundBitmap = null
            wp81BlurredBackground = null
            wp81BlurredAmount = -1f
            shell.setStartBackground(null, focusX)
            refreshWP81BackgroundControls()
            return
        }
        Thread {
            val bitmap = decodeWP81Background(source)
            runOnUiThread {
                // Likewise: a blur of the photo being replaced must not land on top of
                // the one that replaced it.
                wp81BlurGeneration++
                wp81BackgroundBitmap = bitmap
                wp81BlurredBackground?.recycle()
                wp81BlurredBackground = null
                wp81BlurredAmount = -1f
                shell.setStartBackground(bitmap, focusX)
                refreshWP81Blur()
                refreshWP81BackgroundControls()
            }
        }.start()
    }

    /** Decodes a Start background from an asset path or a content URI, downsampled to fit. */
    private fun decodeWP81Background(source: String): Bitmap? = try {
        val metrics = resources.displayMetrics
        val targetW = metrics.widthPixels
        val targetH = metrics.heightPixels

        fun open(): java.io.InputStream? =
            if (source.startsWith("content://") || source.startsWith("file://")) {
                contentResolver.openInputStream(source.toUri())
            } else {
                assets.open(source)
            }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, targetW, targetH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: failed to load start background $source", e)
        null
    }

    /** The folder page currently open, if any. */
    private var wp81OpenFolderId: String? = null

    /**
     * Sends the user to the app list to choose something to put in the open folder.
     *
     * The list doubles as a picker rather than getting a near-identical screen of its own -
     * see AppListView.onPick.
     */
    private fun addAppToOpenWP81Folder() {
        val shell = wp81Shell ?: return
        val folderId = wp81OpenFolderId ?: return
        shell.openAppPicker(onCancel = { reopenWP81Folder(folderId) }) { app ->
            val existing = desktopIcons.firstOrNull { it.packageName == app.packageName }
            if (existing != null) {
                // Already on Start or in another folder - move it rather than duplicating.
                existing.parentFolderId = folderId
                existing.wp81TileIndex = null
            } else {
                desktopIcons.add(
                    DesktopIcon(
                        name = app.name,
                        packageName = app.packageName,
                        icon = app.icon,
                        x = 0f,
                        y = 0f,
                        type = IconType.APP,
                        parentFolderId = folderId,
                        tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
                    )
                )
            }
            saveDesktopIcons()
            refreshWP81Tiles()
            reopenWP81Folder(folderId)
            showNotification("Added to folder", app.name)
        }
    }

    /**
     * Gives the tiles inside a folder the same behaviour they have on Start.
     *
     * Rewired per folder because the callbacks close over which folder is open - what
     * "unpin" and "reorder" mean depends on it.
     */
    private fun wireWP81FolderContents(shell: rocks.gorjan.gokixp.wp81.WP81Shell, folderId: String) {
        val contents = shell.folderPage.contents
        contents.onLaunch = { child -> launchWP81Tile(child) }
        contents.onEditModeChanged = { shell.refreshNavMode() }
        contents.onTilesChanged = { tiles -> persistWP81FolderTiles(folderId, tiles) }
        // Inside a folder the same handle means "take it out of here", which for a folder
        // is a deletion rather than an unpinning - see removeSelectedFromWP81Folder.
        contents.onTileUnpin = { removeSelectedFromWP81Folder() }
        // Wired before the page is filled, so its tiles are built knowing it too.
        contents.countsEnabled = themeManager.getWP81TileCounts()
        contents.tileColorsHidden = themeManager.getWP81HideTileColors()
        contents.columns = themeManager.getWP81Columns()
        shell.folderPage.onBack = { shell.closeFolder() }
    }

    /**
     * Writes size and order back onto the icons filed inside a folder.
     *
     * Kept separate from [persistWP81Tiles] because the two sweep different sets: that one
     * treats an icon missing from the list as unpinned from Start, which for folder
     * contents would delete every icon that merely lives somewhere else.
     */
    private fun persistWP81FolderTiles(
        folderId: String,
        tiles: List<rocks.gorjan.gokixp.wp81.Tile>
    ) {
        val byId = desktopIcons.associateBy { it.id }
        tiles.sortedBy { it.index }.forEachIndexed { position, tile ->
            byId[tile.id]?.let { icon ->
                icon.wp81TileSize = tile.size.name
                icon.wp81TileIndex = position
            }
        }
        // Removing a tile from inside a folder deletes that icon, matching Start. Scoped to
        // this folder's own children so nothing outside it is touched.
        val keep = tiles.map { it.id }.toSet()
        desktopIcons.removeAll { it.parentFolderId == folderId && it.id !in keep }
        saveDesktopIcons()
    }

    /**
     * Deletes the selected tile from the folder it is in.
     *
     * The icon is removed outright rather than relocated to Start - a folder is where the
     * user put it, and quietly moving it somewhere else means having to go and find it.
     */
    private fun removeSelectedFromWP81Folder() {
        val tile = wp81Shell?.selectedTile() ?: return
        removeWP81TileFromFolder(tile)
    }

    /**
     * Takes one tile out of the folder it is filed in, wherever that folder is open.
     *
     * The folder it belongs to is read off the icon rather than from whichever surface is
     * showing, because the two disagree: a folder opened into the wall never set the page's
     * idea of what is open, so this used to return without doing anything - the reason a
     * tile inside a folder could not be removed at all except from the folder page.
     */
    private fun removeWP81TileFromFolder(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        val folderId = icon.parentFolderId ?: return
        val name = getCustomOrOriginalName(icon.packageName, icon.name)

        // Which surface the folder is being looked into, read before the rebuild: the wall
        // closes its own gap whenever it is rebuilt, so afterwards there is nothing to ask.
        val onPage = shell.isFolderOpen() && wp81OpenFolderId == folderId
        val inWall = shell.startScreen.openFolderId == folderId

        removeIcons(iconIdsWithContents(icon))
        saveDesktopIcons()

        shell.exitEditModeEverywhere()
        refreshWP81Tiles()

        // Back where the user was, minus one tile. A folder with nothing left in it is
        // closed instead: there is nothing to look into any more.
        val empty = desktopIcons.none { it.parentFolderId == folderId }
        when {
            onPage && empty -> shell.closeFolder()
            onPage -> reopenWP81Folder(folderId)
            inWall && !empty -> shell.startScreen.tiles()
                .firstOrNull { it.id == folderId }
                ?.let { openWP81FolderInline(it) }
        }
        showNotification("Removed", name)
    }

    /** Re-shows a folder page after its contents changed. */
    private fun reopenWP81Folder(folderId: String) {
        val icon = desktopIcons.firstOrNull { it.id == folderId } ?: return
        val tile = rocks.gorjan.gokixp.wp81.Tile(
            id = icon.id,
            label = getCustomOrOriginalName(icon.packageName, icon.name),
            packageName = icon.packageName,
            size = rocks.gorjan.gokixp.wp81.TileSize.fromName(icon.wp81TileSize),
            index = 0,
            kind = rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER
        )
        openWP81Folder(tile)
    }

    /**
     * Opens a folder as a Metro page rather than a Vista window.
     *
     * A folder is part of the shell, not a program: Solitaire and Internet Explorer are
     * genuine windowed applications and keep their Vista chrome, but a folder browsing its
     * own contents has no reason to leave the phone UI.
     */
    /**
     * Opens a folder into the Start screen, in the gap under its own tile.
     *
     * The contents are built exactly as the folder page built them; what changed is where
     * they are put. Nothing is pushed, nothing is navigated to, and the tile that was
     * tapped stays where it is with the gap hanging off it.
     */
    private fun openWP81FolderInline(folder: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val contents = wp81FolderContents(folder.id)
        if (contents.isEmpty()) {
            showNotification(folder.label, "This folder is empty")
            return
        }
        shell.startScreen.openFolder(
            folder, contents, { child -> wp81ColorFor(child) }
        ) { child -> wp81GlyphFor(child) }
        refreshWP81Notifications()
    }

    /**
     * Moves a tile into a folder, or back out onto Start.
     *
     * One field decides which of the two lists an icon is in, so this is a one-line change
     * followed by a rebuild of everything that reads it. Rebuilding rather than patching:
     * the wall repacks around the hole, the folder repacks around the arrival, and both of
     * those are the packer's job rather than something to reproduce here.
     */
    private fun fileWP81Tile(
        tile: rocks.gorjan.gokixp.wp81.Tile,
        folderId: String?,
        landed: Boolean
    ) {
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        // A folder cannot be put inside itself, and a tile already where it is asked to go
        // is a drop that changed nothing.
        if (folderId == icon.id || icon.parentFolderId == folderId) return

        val leaving = icon.parentFolderId
        icon.parentFolderId = folderId
        // [landed] means the tile is sitting in its new list's grid, in the place the user
        // put it, and that grid writes down the order. A tile posted into a folder that
        // was not open has no place in it yet, so it goes on the end.
        // Put on the end of the list it is joining, in whichever way up the phone is being
        // held: the other arrangement has its own idea of where things go and is not being
        // looked at.
        if (!landed) icon.wp81TileIndex = null
        saveDesktopIcons()

        // Nothing is rebuilt either way. The wall has already closed around the tile that
        // left, and replacing every view on it would undo the movement the user has just
        // watched. Only what reads the icons afresh has to be told.
        refreshWP81Notifications()
        buildWP81FolderPreviews()

        if (leaving != null) discardWP81FolderIfEmpty(leaving)
    }

    /**
     * Throws away a folder whose last tile has just been taken out of it.
     *
     * An empty folder is a tile that opens onto nothing. It was made by putting two things
     * together, so taking both out again undoes the making - leaving the shell to be tidied
     * up by hand would be asking the user to clear up after a gesture.
     */
    private fun discardWP81FolderIfEmpty(folderId: String) {
        if (desktopIcons.any { it.parentFolderId == folderId }) return
        val folder = desktopIcons.firstOrNull { it.id == folderId } ?: return
        if (folder.type != IconType.FOLDER) return

        // Whatever is on screen of it goes first: the gap belongs to a tile that is about
        // to stop existing.
        wp81Shell?.startScreen?.closeFolder(animated = false)
        desktopIcons.remove(folder)
        saveDesktopIcons()
        refreshWP81Tiles()
    }

    /** The tiles filed inside a folder, in the order the folder keeps them. */
    private fun wp81FolderContents(folderId: String): List<rocks.gorjan.gokixp.wp81.Tile> =
        desktopIcons
            .filter { it.parentFolderId == folderId }
            .sortedWith(compareBy({ it.wp81TileIndex ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            .mapIndexed { i, icon ->
                rocks.gorjan.gokixp.wp81.Tile(
                    id = icon.id,
                    label = icon.name.replace("\\n", " ").replace("\n", " "),
                    packageName = icon.packageName,
                    size = rocks.gorjan.gokixp.wp81.TileSize.fromName(icon.wp81TileSize),
                    index = i,
                    kind = wp81KindFor(icon)
                )
            }

    private fun openWP81Folder(folder: rocks.gorjan.gokixp.wp81.Tile) {
        val shell = wp81Shell ?: return
        val contents = desktopIcons
            .filter { it.parentFolderId == folder.id }
            .sortedWith(compareBy({ it.wp81TileIndex ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            .mapIndexed { i, icon ->
                rocks.gorjan.gokixp.wp81.Tile(
                    id = icon.id,
                    label = icon.name.replace("\\n", " ").replace("\n", " "),
                    packageName = icon.packageName,
                    size = rocks.gorjan.gokixp.wp81.TileSize.fromName(icon.wp81TileSize),
                    index = i,
                    kind = wp81KindFor(icon)
                )
            }
        wp81OpenFolderId = folder.id
        wireWP81FolderContents(shell, folder.id)
        // Read fresh rather than from the per-rebuild cache: a tile is repainted in place
        // without rebuilding the wall, so the cache can be a colour behind. Empty while
        // colours are being held back for the wallpaper's sake - see wp81ColorFor.
        val colors =
            if (themeManager.getWP81HideTileColors()) emptyMap()
            else themeManager.getWP81TileColors()
        // A folder that was painted a colour hands it to everything inside: the page *is*
        // the folder opened up, and a wall of accent tiles inside a green folder reads as
        // having arrived somewhere else. A tile the user painted individually keeps its
        // own - that was a deliberate choice about that app, and it outranks the folder's.
        val folderColor = colors[folder.id]
        shell.openFolder(
            folder.label,
            contents,
            notifications = { child -> wp81NotificationsFor(child) },
            tileColors = { child -> colors[child.id] ?: folderColor }
        ) { child -> wp81GlyphFor(child) }
        // A folder inside a folder previews its own contents too, and the page is on
        // screen before the next notification pass comes round.
        shell.folderPage.setFolderPreviews { child -> wp81FolderPreviewFor(child) }
    }

    /** Writes tile size and order back onto the desktop icons that back them. */
    /**
     * Writes tile size and order back onto the desktop icons that back them.
     *
     * Built-ins and user tiles share one index space, because the user can interleave
     * them freely. User positions ride on their DesktopIcon; the built-ins have no icon to
     * hang anything on, so theirs go to SharedPreferences.
     */
    private fun persistWP81Tiles(tiles: List<rocks.gorjan.gokixp.wp81.Tile>) {
        val byId = desktopIcons.associateBy { it.id }
        val ordered = tiles.sortedBy { it.index }

        // One shared index space: built-ins and user tiles are interleaved however the
        // user arranged them, so positions must be numbered across both, not per family.
        val builtInPlacements = mutableMapOf<String, Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>>()
        ordered.forEachIndexed { position, tile ->
            if (tile.kind.isBuiltIn) {
                builtInPlacements[tile.id] = tile.size to position
            } else {
                byId[tile.id]?.let { icon ->
                    icon.wp81TileSize = tile.size.name
                    icon.wp81TileIndex = position
                }
            }
        }
        // Merged over what is already stored: a hidden tile is absent from this list, and
        // replacing outright would forget where it used to sit.
        saveWP81BuiltInPlacements(loadWP81BuiltInPlacements() + builtInPlacements)

        val userTiles = ordered.filterNot { it.kind.isBuiltIn }

        // Unpinning a tile removes the underlying desktop icon: in this theme the Start
        // screen *is* the icon list. Three kinds are exempt, because their absence from
        // Start means something other than "the user removed it":
        //   - icons filed inside a folder live on that folder's page;
        //   - the Recycle Bin and My Computer are deliberately not shown here at all, and
        //     deleting them would lose them from the desktop themes too.
        val keep = userTiles.map { it.id }.toSet()
        // Through iconIdsWithContents rather than in bulk, so unpinning a folder takes
        // what was filed in it as well: those icons are reachable only through the folder,
        // so a folder removed without them leaves rows nothing shows and nothing can delete.
        desktopIcons.filter {
            it.id !in keep &&
                it.parentFolderId == null &&
                it.type != IconType.RECYCLE_BIN &&
                it.type != IconType.MY_COMPUTER
        }.forEach { removeIcons(iconIdsWithContents(it)) }
        saveDesktopIcons()
    }

    /** Pins an app from the app list as a new medium tile. */
    private fun pinWP81Tile(app: AppInfo) {
        // Already on the wall: nothing to do. Tested the same way the menu tests it - a
        // tile *on Start*, not an icon anywhere in the list. Refusing on the whole list
        // meant an app filed inside a folder could never be pinned: the menu offered
        // "pin to start", because there was no tile on Start, and this returned without
        // doing anything, because there was an icon somewhere.
        if (desktopIcons.any { it.packageName == app.packageName && it.parentFolderId == null }) return

        // On the end of both walls: a new tile has no place on either, and the one the
        // phone is not being held in would otherwise put it wherever its name falls.
        val nextIndex = (desktopIcons.mapNotNull { it.tileIndex }.maxOrNull() ?: -1) + 1
        val nextLandscape =
            (desktopIcons.mapNotNull { it.tileIndexLandscape }.maxOrNull() ?: -1) + 1

        // An app that is already in a folder moves out onto Start rather than appearing in
        // both places. A phone gives an app one tile, and two tiles for one app - one of
        // them buried in a folder - is not something the user could have asked for.
        val filed = desktopIcons.firstOrNull {
            it.packageName == app.packageName && it.parentFolderId != null
        }
        if (filed != null) {
            filed.parentFolderId = null
            filed.tileIndex = nextIndex
            filed.tileIndexLandscape = nextLandscape
            if (filed.tileSize == null) filed.tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
            if (filed.tileSizeLandscape == null) {
                filed.tileSizeLandscape = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
            }
            saveDesktopIcons()
            refreshWP81Tiles()
            wp81Shell?.let { shell ->
                shell.goToStart()
                shell.startScreen.scrollToEnd()
            }
            return
        }

        val icon = DesktopIcon(
            name = app.name,
            packageName = app.packageName,
            icon = app.icon,
            x = 0f,
            y = 0f,
            type = IconType.APP,
            tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name,
            tileIndex = nextIndex,
            tileSizeLandscape = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name,
            tileIndexLandscape = nextLandscape
        )
        desktopIcons.add(icon)
        saveDesktopIcons()
        refreshWP81Tiles()
        // Shown rather than announced: the tile goes on the end of the wall, which on a
        // full Start screen is off the bottom of it, so a message saying it had been
        // pinned was the only evidence the user got. Going there is better evidence, and
        // it also leaves them where they can move it.
        wp81Shell?.let { shell ->
            shell.goToStart()
            shell.startScreen.scrollToEnd()
        }
    }

    // ---------------------------------------------------------------- app list

    /**
     * Loads the app list for the shell.
     *
     * Deliberately independent of [cachedAppList]: that cache is nulled every time the
     * desktop Start menu closes, whereas the WP8.1 app list is a permanent page.
     */
    private fun refreshWP81AppList() {
        val shell = wp81Shell ?: return
        Thread {
            val apps = try {
                loadAppsInBackground()
            } catch (e: Exception) {
                Log.e("MainActivity", "WP8.1: failed to load apps", e)
                emptyList()
            }
            val hidden = getHiddenApps()
            val visible = apps.filterNot { it.packageName in hidden }
            runOnUiThread { shell.setApps(visible) }
        }.start()
    }

    // ---------------------------------------------------------------- live tiles

    private fun startWP81LiveTiles() {
        stopWP81LiveTiles()

        // Only worth the request if the tile that shows it is on Start.
        refreshWP81NewsFeeds()
        refreshWP81News()
        refreshWP81TodayEvent()

        // Media sessions are read through the notification listener this launcher already
        // runs, so no extra permission - but nothing shows until notification access is on.
        wp81MediaSessions = rocks.gorjan.gokixp.wp81.MediaSessions(this).also {
            it.startUpdates { runOnUiThread { refreshWP81Media() } }
        }

        // Reuse the Quick Glance calendar provider rather than querying the calendar again:
        // it already handles permissions, the all-day/next-event logic and its own refresh
        // cadence, and pushes results back on the main thread.
        wp81CalendarProvider = rocks.gorjan.gokixp.quickglance.CalendarDataProvider(this).also {
            it.startUpdates { data ->
                // The provider is the signal, not the content: it notices the calendar
                // moving, and today is then re-read for what a tile actually shows - a
                // name and a time, rather than "in twenty minutes".
                refreshWP81TodayEvent()
                wp81Shell?.startScreen?.let { start ->
                    wp81LiveWidgetContent(
                        start.tiles().firstOrNull { t ->
                            t.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR
                        } ?: return@let
                    )?.let { reading ->
                        start.setLiveWidgetContent(WP81_WIDGET_CALENDAR, reading)
                    }
                }
            }
        }
        val runnable = object : Runnable {
            override fun run() {
                wp81Shell?.startScreen?.let { start ->
                    // Built-in widgets show their content permanently, so they are simply
                    // refreshed in place.
                    // Cheap: it returns without doing anything until the stories are half
                    // an hour old, or the feeds turned on have changed.
                    refreshWP81NewsFeeds()

                    refreshWP81Weather()
                    // Media sessions announce themselves when they change, but a session
                    // quietly going away is a change nobody reports. Re-read on the tick
                    // so a tile cannot be left holding a track that finished.
                    refreshWP81Media()
                    for (tile in start.tiles()) {
                        wp81LiveWidgetContent(tile)?.let { reading ->
                            start.setLiveWidgetContent(tile.id, reading)
                        }
                        // Conditions change with the reading, so the mark refreshes with it.
                        val (frontGlyph, backGlyph) = wp81WidgetGlyphFor(tile)
                        start.setWidgetGlyph(tile.id, frontGlyph, backGlyph)
                        start.setWidgetBack(tile.id, wp81LiveWidgetBack(tile))
                        // An alarm set, gone off or turned off while Start is up is a
                        // change nothing announces, so the mark is re-asked with the rest.
                        start.setAlarmMark(tile.id, wp81AlarmMarkFor(tile))
                    }
                }
                wp81Handler.postDelayed(this, WP81_LIVE_TILE_INTERVAL_MS)
            }
        }
        wp81LiveTileRunnable = runnable
        wp81Handler.postDelayed(runnable, WP81_LIVE_TILE_INTERVAL_MS)
    }

    private fun stopWP81LiveTiles() {
        wp81LiveTileRunnable?.let { wp81Handler.removeCallbacks(it) }
        wp81LiveTileRunnable = null
        wp81CalendarProvider?.stopUpdates()
        wp81CalendarProvider = null
        wp81MediaSessions?.stopUpdates()
        wp81MediaSessions = null
    }

    /**
     * Stores a new accent / background and repaints immediately.
     *
     * Deliberately does not recreate the activity: unlike a theme switch, changing accent
     * is meant to be instant, and every WP8.1 surface repaints from the palette.
     */
    private fun commitWP81Appearance(accent: Int, dark: Boolean) {
        val changed = accent != themeManager.getWP81Accent() || dark != themeManager.isWP81Dark()
        if (!changed) return
        themeManager.setWP81Accent(accent)
        themeManager.setWP81Dark(dark)
        refreshWP81Palette()
    }

    /**
     * Returns the WP8.1 shell to the top of Start.
     *
     * Home means home: whatever was open - the app list mid-search, a folder page,
     * settings, a selected tile, or Start scrolled halfway down - swiping home puts the
     * user back at the top of the Start screen, the way pressing Start on a real phone did.
     */
    /**
     * Everything "home" means under Windows Phone 8.1.
     *
     * Reached from the system's home gesture as well as from the Start key, and both mean
     * the same thing: whatever the user has got themselves into, put it away. Programs
     * included - resetting the shell while a maximised program covered it left the user
     * looking at exactly what they had just asked to leave, with a Start screen tidying
     * itself up behind it.
     */
    /**
     * Whether this home gesture is the user coming back to something, not leaving it.
     *
     * The home key does two jobs from one intent, and which it is depends on where the
     * user was standing when they made the gesture. Made from inside another app, home is
     * the way back to the launcher - and the launcher they left had People, or Zune, or
     * the settings page open on it, so that is what they are coming back to. Reset, and
     * the gesture that was meant to bring them home instead threw away what they had been
     * in the middle of, for a Start screen they never asked for.
     *
     * Made while already looking at the launcher, the same gesture means Start, which is
     * what [resetWP81ToStart] does - and it is one press away, because coming back here
     * puts the launcher in front of the user, so the next home gesture is that one.
     * Windows key included: see the nav bar's onStart, which puts programs away first.
     *
     * Settings counts for a reason of its own: half of what is on that page sends the user
     * out to Android's own settings to answer it - the default browser, app access, the
     * notification listener - and the way back from each of those is this gesture. Landing
     * on Start after granting something means walking back into the page to see whether it
     * took. See [refreshWP81SettingsPermissions], which makes sure it says so.
     *
     * A folder page and the app list are not on the list: both are already put back to rest
     * when the launcher stops - see onStop - so there is nothing of those to come back to.
     */
    private fun wp81ReturningToWhatWasOpen(): Boolean {
        if (!wp81AwayBehindAnotherApp) return false
        return when (wp81Shell?.where()) {
            rocks.gorjan.gokixp.wp81.WP81Shell.Place.PROGRAM,
            rocks.gorjan.gokixp.wp81.WP81Shell.Place.SETTINGS -> true
            else -> false
        }
    }

    private fun resetWP81ToStart() {
        val shell = wp81Shell ?: return
        minimiseWP81Windows()
        wp81OpenFolderId = null
        shell.contextMenu.dismiss()
        shell.startScreen.exitEditMode()
        shell.goToStart(animated = false)
        shell.startScreen.scrollToTop()
    }

    /**
     * Takes every program that is on screen off it, and says whether there were any.
     *
     * Minimised rather than closed: none of them has been asked to stop, Zune in
     * particular is still playing, and each comes back by being launched again - which
     * restores it rather than starting it over.
     */
    private fun minimiseWP81Windows(): Boolean {
        val onScreen = floatingWindowManager.getAllActiveWindows().filterNot { it.isMinimized() }
        onScreen.forEach { it.minimize() }
        return onScreen.isNotEmpty()
    }

    /** Repaints the shell after an accent or Light/Dark change, without a recreate. */
    fun refreshWP81Palette() {
        val shell = wp81Shell ?: return
        val palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        shell.applyPalette(palette)
        applyWP81SystemBarAppearance(palette)
        findViewById<RelativeLayout>(R.id.main_background)?.setBackgroundColor(palette.background)
        findViewById<View>(R.id.root_container)?.setBackgroundColor(palette.background)
    }








    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Windows Classic asked for a 5% larger base font; nothing here does.
        @Suppress("ConstantConditionIf")
        if (false) {
            val config = Configuration(newBase.resources.configuration)
            config.fontScale = 1.05f
            val ctx = newBase.createConfigurationContext(config)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }


    /**
     * Puts the phone shell up.
     *
     * What used to be a fork over four themes is a single call: this launcher renders
     * Windows Phone 8.1 and nothing else. The guard against applying it twice stays, since
     * the activity is still recreated for configuration changes.
     */
    private fun initializeTheme() {
        if (shellApplied) return
        applyWindowsPhone81Theme()
        shellApplied = true
    }

    /** True if the user has set a custom icon for this package (folders check this before re-theming). */
    fun hasCustomIcon(packageName: String): Boolean = customIconMappings.containsKey(packageName)






    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { event ->
            val edgeThresholdPx = BACK_GESTURE_EDGE_THRESHOLD_DP * resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val isEdgeTouch = event.rawX <= edgeThresholdPx || event.rawX >= (screenWidth - edgeThresholdPx)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // If touch starts at edge, mark as potential back gesture
                    if (isEdgeTouch) {
                        potentialBackGestureStartTime = System.currentTimeMillis()
                        Log.d("MainActivity", "Potential back gesture detected at x=${event.rawX}")
                    } else {
                        potentialBackGestureStartTime = 0L
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Reset potential gesture tracking
                    potentialBackGestureStartTime = 0L
                }
            }

            // Block edge touches that could be back gestures
            if (isEdgeTouch && potentialBackGestureStartTime > 0L) {
                val timeSinceEdgeTouch = System.currentTimeMillis() - potentialBackGestureStartTime

                // Block if:
                // 1. Back gesture is confirmed, OR
                // 2. Touch is at edge and we're within the timeout window
                if (isBackGestureInProgress || timeSinceEdgeTouch < BACK_GESTURE_TIMEOUT_MS) {
                    Log.d("MainActivity", "Blocking edge touch (gesture=${isBackGestureInProgress}, time=${timeSinceEdgeTouch}ms)")
                    return true // Consume the event without processing
                }
            }

            // Also block if back gesture is confirmed (even if not at edge anymore)
            if (isBackGestureInProgress) {
                return true // Consume the event without processing
            }

        }
        return super.dispatchTouchEvent(ev)
    }








    /**
     * Creates a WindowsDialog with the correct theme from the start to avoid re-inflation
     */
    private fun createThemedWindowsDialog(): WindowsDialog = WindowsDialog(this)

    /**
     * Shows a notification bubble with title and description
     * @param title The notification title (application name)
     * @param description The notification message
     */
    private fun showNotification(title: String, description: String, onTap: (() -> Unit)? = null) {
        // Windows Phone 8.1 announces things with a band across the top instead of the
        // Vista speech bubble, which is anchored to a system tray this shell does not have.
        wp81Shell?.let { shell ->
            // Clear of whatever strip the program on screen has along the bottom, asked
            // for as the band goes up rather than when that program opened: a link
            // arriving from another app opens the browser before the shell is necessarily
            // there to be told about it, and a lift set then is set on nothing.
            shell.toast.lift = metroIEAppInstance?.barHeight() ?: 0
            shell.toast.show(title, description, NOTIFICATION_DURATION_MS, onTap)
            // The phone's own alert, not the desktop's: a band across the top of a Start
            // screen announcing itself with Vista's bubble is two operating systems at once.
            playSound(R.raw.bubble_8)
            return
        }

        // The shell is not up yet - the only moment this happens is a link handed to the
        // launcher before it has drawn. Android's own toast rather than nothing, since the
        // Vista speech bubble this used to fall back to went with the desktop.
        android.widget.Toast.makeText(this, "$title: $description", android.widget.Toast.LENGTH_LONG).show()
    }


    /**
     * Checks for app updates from remote config
     */
    private fun checkForUpdates(showCheckingNotification: Boolean = false) {
        Thread {
            try {
                val apiUrl = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                val connection = apiUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    val gson = Gson()
                    val release = gson.fromJson(response, com.google.gson.JsonObject::class.java)

                    val latestTag = release.get("tag_name")?.asString ?: ""
                    val isPrerelease = release.get("prerelease")?.asBoolean ?: false

                    // Skip prereleases if you only want stable versions
                    if (isPrerelease) {
                        Log.d("MainActivity", "Skipping prerelease: $latestTag")
                        return@Thread
                    }

                    val downloadUrl = release.get("html_url")?.asString ?: ""

                    // Current app versionName (like "1.6" or "v1.6")
                    val currentVersionName = try {
                        val pInfo = packageManager.getPackageInfo(packageName, 0)
                        pInfo.versionName ?: ""
                    } catch (e: Exception) {
                        ""
                    }

                    Log.d("MainActivity", "Current version: $currentVersionName | Latest: $latestTag")

                    val latestNumeric = latestTag.trim().removePrefix("v").removePrefix("V")
                    val currentNumeric = currentVersionName.trim().removePrefix("v").removePrefix("V")

                    val updateAvailable = try {
                        compareVersions(latestNumeric, currentNumeric) > 0
                    } catch (e: Exception) {
                        latestNumeric != currentNumeric // fallback simple check
                    }

                    if (updateAvailable) {
                        runOnUiThread {
                            updateDownloadLink = downloadUrl
                            updateAvailableVersion = latestTag
                            // The Welcome tile says so on Start, the way the taskbar icon
                            // says so on the desktop.
                            refreshWP81Notifications()

                            showNotification(
                                "Windows Update",
                                "A new version ($latestTag) is available. Tap to download."
                            ) {
                                if (downloadUrl.isNotEmpty()) {
                                    try {
//                                        val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())
//                                        startActivity(intent)
                                        openUrlShortcut(downloadUrl)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error opening link", e)
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d("MainActivity", "No update available")
                        if (showCheckingNotification) {
                            runOnUiThread {
                                showNotification("Up to date", "No new updates available")
                            }
                        }
                    }
                } else {
                    connection.disconnect()
                    Log.w("MainActivity", "GitHub API failed: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking for updates", e)
            }
        }.start()
    }

    /**
     * Simple semantic version comparator (e.g., 1.7.0 > 1.6)
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".", "-")
        val parts2 = v2.split(".", "-")
        val len = maxOf(parts1.size, parts2.size)
        for (i in 0 until len) {
            val a = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val b = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a.compareTo(b)
        }
        return 0
    }


    /**
     * Starts the periodic update checker
     */
    private fun startUpdateChecker() {
        // Check immediately on launch
        checkForUpdates()

        // Set up recurring check every hour
        updateCheckRunnable = object : Runnable {
            override fun run() {
                checkForUpdates()
                updateCheckHandler.postDelayed(this, UPDATE_CHECK_INTERVAL)
            }
        }
        updateCheckHandler.postDelayed(updateCheckRunnable!!, UPDATE_CHECK_INTERVAL)
    }

    /**
     * Stops the periodic update checker
     */
    private fun stopUpdateChecker() {
        updateCheckRunnable?.let { updateCheckHandler.removeCallbacks(it) }
        updateCheckRunnable = null
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun applyWallpaperToDevice(wallpaperItem: WallpaperItem, setHomeScreen: Boolean, setLockScreen: Boolean) {
        try {
            val wallpaperManager = android.app.WallpaperManager.getInstance(this)

            // Load the wallpaper drawable
            val drawable = if (wallpaperItem.filePath != null) {
                // Load from assets
                val inputStream = assets.open(wallpaperItem.filePath)
                val loadedDrawable = Drawable.createFromStream(inputStream, wallpaperItem.filePath)
                inputStream.close()
                loadedDrawable
            } else {
                wallpaperItem.drawable
            }

            if (drawable != null) {
                // Convert drawable to bitmap
                val bitmap = when (drawable) {
                    is android.graphics.drawable.BitmapDrawable -> {
                        drawable.bitmap
                    }
                    else -> {
                        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
                        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1920
                        val bitmap = createBitmap(width, height)
                        val canvas = Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap
                    }
                }

                // Set wallpaper based on selected options
                // Android 7.0+ supports separate home and lock screen wallpapers
                if (setHomeScreen && setLockScreen) {
                    wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK)
                } else if (setHomeScreen) {
                    wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                } else if (setLockScreen) {
                    wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_LOCK)
                }

                Log.d("MainActivity", "Successfully set device wallpaper: home=$setHomeScreen, lock=$setLockScreen")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set device wallpaper", e)
        }
    }

    private fun applyWallpaperToDeviceFromDrawable(drawable: Drawable, setHomeScreen: Boolean, setLockScreen: Boolean) {
        try {
            val wallpaperManager = android.app.WallpaperManager.getInstance(this)

            // Convert drawable to bitmap
            val bitmap = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> {
                    drawable.bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1920
                    val bitmap = createBitmap(width, height)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            // Set wallpaper based on selected options
            // Android 7.0+ supports separate home and lock screen wallpapers
            if (setHomeScreen && setLockScreen) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK)
            } else if (setHomeScreen) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
            } else if (setLockScreen) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_LOCK)
            }

            Log.d("MainActivity", "Successfully set device wallpaper from drawable: home=$setHomeScreen, lock=$setLockScreen")

        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set device wallpaper from drawable", e)
        }
    }

}