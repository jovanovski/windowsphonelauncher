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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import rocks.gorjan.gokixp.wp81.WP81Settings
import rocks.gorjan.gokixp.wp81.CUSTOM_ICONS_KEY
import rocks.gorjan.gokixp.wp81.DESKTOP_CUSTOM_ICON_KEYS
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.view.isEmpty
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity(), AppChangeListener {

    val themeManager by lazy { WP81Settings(this) }

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

    /**
     * How long the band across the top stays quiet about an update it has already announced.
     *
     * The looking goes on hourly - the Welcome tile is only right about a new version if
     * something keeps asking - but announcing is an interruption, and one an hour about a
     * download the user has already decided not to take yet is nagging. Two days; a version
     * that has not been announced before says so straight away whatever the clock reads.
     * See [shouldAnnounceUpdate].
     */
    private val UPDATE_NOTICE_INTERVAL = 2L * 24 * 60 * 60 * 1000 // 2 days in milliseconds
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
    private lateinit var floatingWindowManager: FloatingWindowManager
    /**
     * The icons a package is shown with. Holds the picked-icon map and the bitmap cache.
     *
     * System programs' artwork is passed in rather than looked up there: which drawable
     * Zune or Internet Explorer wears is the shell's business, not the store's.
     */
    val iconStore by lazy {
        IconStore(this, { pkg -> loadAppIcon(pkg) }, ::isIconPackable)
    }

    /**
     * Whether an icon pack gets a say about this package.
     *
     * Only real installed apps. Everything else the launcher draws an icon for is its own -
     * the shell's programs under [SYSTEM_APP_PREFIX], the folders on the wall, the recycle
     * bin carried over from the desktop - and a pack has no entry for any of them, so what
     * it would do instead is mount the shell's own artwork on its backplate. See
     * [loadAppIcon], which answers for exactly the same three cases.
     */
    private fun isIconPackable(packageName: String): Boolean =
        !isSystemApp(packageName) &&
            !packageName.startsWith("folder_") &&
            packageName != "recycle.bin"
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

    // Image picker launcher for wallpaper selection
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            // What the pick was *for* is recorded in SharedPreferences rather than held in
            // a lambda. The system picker is another activity, and this one can be
            // recreated behind it; an in-memory handler is then gone by the time the result
            // arrives, and the pick silently falls through to the wallpaper flow - which is
            // how choosing a tile icon ended up asking where to apply a wallpaper.
            when (val target = consumePendingImagePick()) {
                PICK_TARGET_WP81_BACKGROUND -> applyPickedWP81Background(selectedUri)
                null -> Log.w("MainActivity", "Image picked with nothing waiting for it")
                else ->
                    if (target.startsWith(PICK_TARGET_WP81_ICON_PREFIX)) {
                        applyPickedWP81Icon(
                            target.removePrefix(PICK_TARGET_WP81_ICON_PREFIX), selectedUri)
                    } else {
                        Log.w("MainActivity", "Image picked for an unknown target: $target")
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


    /** Set by whichever settings surface is open, so it can be told the answer. */
    private var refreshDefaultBrowser: (() -> Unit)? = null

    private fun refreshDefaultBrowserUi() {
        refreshDefaultBrowser?.invoke()
    }

    // ---- backup and restore -----------------------------------------------------------

    /**
     * The user's Google Drive, as one of the two places a backup can go.
     *
     * Lazy, and it asks the phone about the account rather than holding one: the sign-in
     * belongs to the device and outlives every instance of this activity, so a helper that
     * only knew about accounts it had seen sign in would be signed out on every launch.
     */
    private val googleDrive by lazy { GoogleDriveHelper(this) }

    /**
     * Where the user chose to put a backup file.
     *
     * The snapshot is taken here rather than carried to here. The system's file picker is
     * another activity and this one can be recreated behind it, which takes any settings
     * held in a field with it - and a backup that silently wrote nothing is worse than one
     * that failed loudly. Reading the preferences at the moment of writing costs a few
     * milliseconds and cannot go stale.
     */
    private val backupExportLauncher =
        registerForActivityResult(CreateDocument(BACKUP_MIME)) { uri: Uri? ->
            val target = uri ?: return@registerForActivityResult
            Thread {
                val failure = try {
                    val json = SettingsBackup.snapshot(this)
                    contentResolver.openOutputStream(target)?.use { it.write(json.toByteArray()) }
                        ?: throw java.io.IOException("Nothing would open that file for writing")
                    null
                } catch (e: Exception) {
                    Log.e("MainActivity", "Could not write the backup file", e)
                    e.message ?: "the file could not be written"
                }
                runOnUiThread {
                    if (failure == null) {
                        themeManager.setWP81LastFileBackup(System.currentTimeMillis())
                        refreshWP81BackupRows()
                        showNotification("Backup", "Your settings were saved to that file")
                    } else {
                        showNotification("Backup", "Could not save: $failure")
                    }
                }
            }.start()
        }

    /** A backup file the user picked, read and then offered back to them to confirm. */
    private val backupImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val source = uri ?: return@registerForActivityResult
            Thread {
                val json = try {
                    contentResolver.openInputStream(source)?.use {
                        it.bufferedReader().readText()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Could not read the backup file", e)
                    null
                }
                runOnUiThread {
                    if (json == null) showNotification("Restore", "That file could not be read")
                    else confirmRestore(json, "that file")
                }
            }.start()
        }

    /**
     * The Google sign-in screen, and what it was opened for.
     *
     * What the user asked for is recorded in preferences rather than held in a lambda, for
     * the reason the image picker's target is - see [consumePendingImagePick]. Sign-in is a
     * whole screen from another app, and an activity that is recreated behind it comes back
     * with any in-memory handler gone, leaving a user who has just signed in with nothing
     * to show for it.
     */
    private val driveSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val wanted = consumePendingDriveAction()
        if (result.resultCode != RESULT_OK) {
            // RESULT_CANCELED is both "the user backed out" and "this build has no OAuth
            // client for its package and signing key", and the two are indistinguishable
            // from here. Said plainly rather than guessed at.
            Log.w("MainActivity", "Google sign-in did not complete: ${result.resultCode}")
            showNotification("Google Drive", "Not signed in")
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            googleDrive.connect(account)
            when (wanted) {
                DRIVE_ACTION_BACKUP -> backUpToDrive()
                DRIVE_ACTION_RESTORE -> restoreFromDrive()
                else -> showNotification("Google Drive", "Signed in as ${account.email}")
            }
        } catch (e: ApiException) {
            Log.e("MainActivity", "Google sign-in failed: ${e.statusCode}", e)
            showNotification("Google Drive", "Sign-in failed (${e.statusCode})")
        } catch (e: Exception) {
            Log.e("MainActivity", "Google sign-in failed", e)
            showNotification("Google Drive", "Sign-in failed")
        }
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
        phoneAppInstance?.refresh()
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
     * Only ever from a tap in Phone - never on a launch, never on a timer. Taking this
     * role means every call on the device comes through this app's screen, including ones
     * placed from somewhere else entirely, and that is not a thing to ask for on the way
     * past. The system puts its own dialog in front of the request, which is the consent
     * that matters; this only decides when the question gets asked.
     */
    private fun requestDialerRole() {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles == null || !roles.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
            showNotification("Phone", "This device has no phone app to be")
            return
        }
        if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)) {
            ensureCallPermissions()
            phoneAppInstance?.refresh()
            return
        }
        try {
            dialerRoleLauncher.launch(
                roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not ask to be the phone", e)
            showNotification("Phone", "This phone would not offer the choice")
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
        messagingAppInstance?.refresh()
    }

    /**
     * Asks to become the phone's messaging app.
     *
     * Only ever from a tap in Messaging. Taking this role is a larger thing than taking the
     * phone one, because it moves work rather than only moving a screen: from that moment
     * every text message on the device is delivered to this app alone, and one it fails to
     * write down or announce is a message nobody ever sees. It also ends multimedia
     * messages, which this app does not do - see MmsDeliverReceiver. The system's own
     * dialog is the consent that matters; this only decides when it gets asked.
     */
    private fun requestSmsRole() {
        val roles = getSystemService(android.app.role.RoleManager::class.java)
        if (roles == null || !roles.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
            showNotification("Messaging", "This device has no messaging app to be")
            return
        }
        if (roles.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)) {
            ensureMessagePermissions()
            messagingAppInstance?.refresh()
            return
        }
        try {
            smsRoleLauncher.launch(
                roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS))
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not ask to be the messaging app", e)
            showNotification("Messaging", "This phone would not offer the choice")
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

        // The one tile the shell still provides itself. The rest of the widget ids are
        // WP81TileHost's, which is where what became of them is written down.
        private const val WP81_WIDGET_CALENDAR = "wp81.widget.calendar"
        private const val KEY_WP81_BUILTIN_TILES = "wp81_builtin_tiles"

        /** Set once the phone's custom icons have been moved off the desktop themes' keys. */
        private const val KEY_WP81_ICONS_SPLIT = "wp81_custom_icons_split"

        /** The same, for the phone on its side. See wp81Landscape. */
        private const val KEY_WP81_BUILTIN_TILES_LANDSCAPE = "wp81_builtin_tiles_landscape"

        /** What the in-flight system image pick is for; see imagePickerLauncher. */
        private const val KEY_PENDING_IMAGE_PICK = "pending_image_pick"
        private const val PICK_TARGET_WP81_BACKGROUND = "wp81_background"
        private const val PICK_TARGET_WP81_ICON_PREFIX = "wp81_icon:"

        /** What the in-flight Google sign-in is for; see driveSignInLauncher. */
        private const val KEY_PENDING_DRIVE_ACTION = "pending_drive_action"
        private const val DRIVE_ACTION_BACKUP = "backup"
        private const val DRIVE_ACTION_RESTORE = "restore"

        /**
         * That a restore has just happened, for the launcher it restarts to announce.
         *
         * A restore replaces every setting the shell was drawn from, so the shell is built
         * again from scratch - which takes the band announcing it with it. So the news is
         * left in the preferences the restart is about to read, and said on the way back
         * up. Cleared as it is read; see [announceRestoreIfJustDone].
         */
        private const val KEY_RESTORE_ANNOUNCE = "wp81_restore_announce"

        /** What a settings backup is, to the picker that saves one and the one that opens it. */
        private const val BACKUP_MIME = "application/json"

        /**
         * How large the flat swatch behind this launcher is. See [matchDeviceWallToTheme].
         *
         * Small, but not one pixel: a wallpaper is read back and sampled by the system for
         * the colours it hands to other things, and a single pixel is the size at which
         * some of that quietly declines to work.
         */
        private const val WALL_SWATCH_PX = 32

        /**
         * Where a colour stops being something to draw white on. See [paintWP81NavBar].
         *
         * Relative luminance, which is what the eye does with a colour rather than what
         * the numbers in it are: Yellow and Cobalt are the same distance from black by
         * one measure and nowhere near it by the other.
         */
        private const val LIGHT_GROUND = 0.5

        /** How long an in-app notification stays on screen. */
        private const val NOTIFICATION_DURATION_MS = 7000L

        /** A band that holds until it is tapped or flicked away. See [showNotification]. */
        private const val STICKY_NOTIFICATION = 0L

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

        /**
         * The most icons offered out of an icon pack, and how large each is held.
         *
         * A cap rather than the whole pack: the grid holds every choice it is handed, and
         * packs run to a couple of thousand drawables. See loadWP81IconPackChoices.
         */
        private const val WP81_ICON_PACK_MAX = 400
        private const val WP81_ICON_PACK_THUMB_PX = 144

        /**
         * The most icon packs listed at once.
         *
         * The command list is a plain column with no scroller - it is meant for four or
         * five verbs - so a phone with a dozen packs would put its last few off the bottom
         * of the screen where they cannot be reached. Well past what anyone has installed.
         */
        private const val ICON_PACK_MENU_MAX = 8

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
        private val RETIRED_SYSTEM_APPS = setOf(
            "system.msn",
            // The desktop's own programs. Each was still listed and each did nothing when
            // tapped - no action was ever registered for them under this shell - because
            // the windows they opened were the desktop launcher's. Music replaced both
            // players, Alarms replaced the Clock, and a phone has no registry to edit.
            "system.registry_editor", "system.winamp", "system.wmp", "system.pinball",
            "system.clock"
        )

        /**
         * The repository this launcher updates itself from, and shows release notes for.
         *
         * Its own, not the desktop launcher's. Left pointing at windowslauncher, every
         * update check here would offer the desktop launcher's APK - which is a different
         * app with a different application id, so it would install alongside rather than
         * over, and the person would end up with two launchers and no update.
         */
        const val GITHUB_REPO = "jovanovski/windowsphonelauncher"

        /** Which of [RETIRED_SYSTEM_APPS] have already been swept out of the user's arrangement. */
        private const val KEY_RETIRED_APPS_PURGED = "retired_system_apps_purged"
        private const val KEY_SOUND_MUTED = "sound_muted"
        private const val KEY_SHOW_NOTIFICATION_DOTS = "show_notification_dots"
        private const val KEY_CLOCK_24_HOUR = "clock_24_hour"
        private const val KEY_KNOWN_APPS = "known_apps"
        private const val KEY_WALLPAPER_VISTA_PATH = "wallpaper_vista_path"
        private const val KEY_WALLPAPER_VISTA_URI = "wallpaper_vista_uri"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_NAMES = "custom_names"
        private const val KEY_WEATHER_DATA = "weather_data"
        private const val KEY_WEATHER_TIMESTAMP = "weather_timestamp"
        private const val KEY_WEATHER_UNIT = "weather_unit"
        private const val KEY_SHOW_CALENDAR_EVENTS = "show_calendar_events"
        private const val KEY_IE_HOMEPAGE = "ie_homepage"
        private const val KEY_SWIPE_RIGHT_APP = "swipe_right_app"
        private const val KEY_WEATHER_APP = "weather_app"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_SHOWN_WELCOME_FOR_VERSION = "shown_welcome_for_version"

        /** The version the update band last announced, and when. See [shouldAnnounceUpdate]. */
        private const val KEY_UPDATE_ANNOUNCED_VERSION = "update_announced_version"
        private const val KEY_UPDATE_ANNOUNCED_AT = "update_announced_at"
        private const val KEY_LAST_GOOGLE_DRIVE_SYNC = "last_google_drive_sync"
        private const val KEY_OPEN_URLS_IN_IE = "open_urls_in_ie"

        /**
         * The last app the launcher sent the user to, for back-back on Start.
         *
         * Kept in preferences rather than in a field because a launcher is killed
         * between visits more often than any other app on the phone, and the app you
         * were in five minutes ago is exactly the one you want when you come back.
         */

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

        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002

        /** Anything asked for from the welcome app's permissions page. */
        private const val WELCOME_PERMISSION_REQUEST_CODE = 1010

        /** That the out-of-box Start screen has been laid out. See seedDefaultWallIfFirstRun. */
        private const val KEY_DEFAULT_WALL_SEEDED = "wp81_default_wall_seeded"

        /** That the shell's own programs have been filed. See seedWindowsAppsFolder. */
        private const val KEY_WINDOWS_APPS_FOLDER_SEEDED = "wp81_windows_apps_folder_seeded"

        /**
         * Which of the shell's programs have been offered to that folder, by package.
         *
         * The boolean above only says that the filing happened, which was enough while the
         * set of programs was fixed and stopped being enough the moment one was added: an
         * install from before Cortana existed has the flag set, so she would never be filed
         * on that phone however many times it started up.
         *
         * A record of what has been offered answers that without ever fighting the user's
         * own arrangement. A program is filed once; if they then take it out of the folder
         * it stays out, because it is in here and will not be offered again.
         */
        private const val KEY_WINDOWS_APPS_FOLDER_FILED = "wp81_windows_apps_folder_filed"

        /**
         * Programs added to the shell after the folder above started being seeded.
         *
         * Needed only for the one upgrade where a phone has the old boolean and no record
         * of what was filed under it. Everything not named here is taken to have been
         * offered already - which is true, because the folder was filed from the whole list
         * as it stood - and everything named here is offered now.
         *
         * **A new built-in program belongs on this list.** Leave it off and it will be
         * filed for people installing fresh and never appear for anybody who already has
         * the launcher, which is the bug this whole mechanism exists to stop.
         */
        private val WINDOWS_APPS_FOLDER_LATE_ADDITIONS =
            setOf("system.cortana", "system.settings", "system.phone", "system.messaging")

        /**
         * That the five live widgets have been handed to their programs. See
         * [connectWP81ProgramTiles].
         */
        private const val KEY_WP81_PROGRAM_TILES = "wp81_program_tiles_connected"

        /**
         * The folder the launcher's own programs are filed in, and what it is called.
         *
         * A fixed id rather than the timestamp a hand-made folder gets: this one is put
         * there by the shell, and a shell that cannot name what it made cannot tell
         * whether it has already made it.
         */
        private const val WINDOWS_APPS_FOLDER_ID = "folder_windows_apps"
        private const val WINDOWS_APPS_FOLDER_NAME = "Windows Apps"

        /** Asked for by the Photos tile, the first time it is tapped. */
        private const val PHOTOS_PERMISSION_REQUEST_CODE = 1005

        /** And by the People tile, on the same terms. */
        private const val CONTACTS_PERMISSION_REQUEST_CODE = 1006

        /**
         * Anything People, Phone or Messaging asks for from inside itself.
         *
         * One code for all three - writing contacts, the call log, placing a call, reading
         * and sending texts - because the answer is always the same: tell every one of
         * them that is open to read again, and let the page the user is standing on show
         * what it can now do. Which of them was granted is a question each app asks the
         * system directly, at the moment it matters.
         */
        private const val PEOPLE_PERMISSION_REQUEST_CODE = 1007

        /**
         * Cortana asking to use the microphone, so she can name what is playing.
         *
         * Its own code rather than the keyboard's: the keyboard asks for the same
         * permission in order to take dictation, and the two want different things done
         * when the answer comes back.
         */
        private const val CORTANA_MICROPHONE_REQUEST_CODE = 1008

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
         * phone where Messaging holds the role has nowhere else to go, and opening the
         * conversation with that person is a better answer to it than nothing happening.
         */
        private val MESSAGE_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

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
        const val IMPORTED_ICONS_DIR = "imported_icons"

        // Standard size icons are rendered at
        const val ICON_SIZE_PX = 288

        /**
         * Longest edge a bundled wallpaper is decoded to when it is only being previewed.
         *
         * The largest thing that shows one is the XP picker's 138x102dp monitor; the phone's
         * strip is smaller still. See [loadWallpaperPreview].
         */
        private const val WALLPAPER_PREVIEW_PX = 512

        /**
         * Where a picked Start background is kept, under the app's own storage.
         *
         * One slot, overwritten by the next pick: the picker grants read access for the
         * session only, so the picture has to be copied somewhere that outlives a reboot -
         * and a phone that keeps every photograph the user ever tried is a phone quietly
         * filling up. See [copyWP81BackgroundLocally] and [wp81CustomBackgroundPath].
         */
        private const val WP81_BACKGROUND_FILE = "wp81_start_background.img"

        /** The height of a square in the settings page's wallpaper strip, in dp. */
        private const val WP81_STRIP_TILE_DP = 120

        private var instance: MainActivity? = null

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

        /**
         * What the phone should call its owner, as typed in Cortana's settings.
         *
         * That is the only place it can be typed, because the greeting under her ring is
         * the only place in the shell that says it - a page of its own for one field that
         * feeds one line would be a settings entry nobody would ever find.
         *
         * A blank name is forgotten rather than stored blank, so that "has not said" and
         * "said nothing" stay the same state and [getUserName] has one fallback rather
         * than two.
         */
        fun setUserName(context: Context, name: String) {
            val trimmed = name.trim()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                if (trimmed.isEmpty()) remove(KEY_USER_NAME)
                else putString(KEY_USER_NAME, trimmed)
            }
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


        // Orientation enum
        enum class ScreenOrientation {
            PORTRAIT,
            LANDSCAPE
        }


    }


    // There is no theme-change notification, because there is no theme change. The
    // desktop launcher rebuilt itself in place when the user picked another shell and had
    // to tell every view about it; this launcher has one shell for its whole life.


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
        rocks.gorjan.gokixp.apps.phone.GokiInCallService.clearIfIdle(this)

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
        iconStore.load()

        // And the icon pack with them, for the same reason: everything built below resolves
        // an icon per app, and a pack applied afterwards would mean building it all twice.
        applyIconPack()

        // Load saved desktop icons (now with custom mappings available)
        loadDesktopIcons()

        // And if there were none, because this is a fresh install, lay out the wall the
        // launcher ships with. After the load, so it can tell a first run from a wall the
        // user has emptied.
        seedDefaultWallIfFirstRun()

        // On a wall arranged before the live widgets became their programs' tiles, each
        // one moves onto the program it was about. After the seeding, which lays a fresh
        // install out with those tiles already pinned and so leaves this nothing to find.
        connectWP81ProgramTiles()

        // The shell's own programs, in a folder on that wall. After the seeding above, so
        // a fresh install has its tiles down and this one takes what is left.
        seedWindowsAppsFolder()

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
            announceRestoreIfJustDone()
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

        // The five the shell actually plays. Every other sound in res/raw was loaded here
        // and never asked for again: the four desktop shells' startup and shutdown
        // jingles, XP's error/warning/information stings, Vista's click, ding, bubble and
        // charging chimes, the Recycle Bin emptying, the Phone Dialer's keypad tones and
        // three easter eggs with nothing left to trigger them. Decoding them all into a
        // five-stream SoundPool at every launch, to play none of them.
        soundIds[R.raw.startup_8] = soundPool.load(audioContext, R.raw.startup_8, 1)
        soundIds[R.raw.click] = soundPool.load(audioContext, R.raw.click, 1)
        // The phone's alert, for a toast while the shell is up.
        soundIds[R.raw.bubble_8] = soundPool.load(audioContext, R.raw.bubble_8, 1)
        soundIds[R.raw.charge_on_8] = soundPool.load(audioContext, R.raw.charge_on_8, 1)
        soundIds[R.raw.charge_off_8] = soundPool.load(audioContext, R.raw.charge_off_8, 1)

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
                    Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            // The charge itself, which is what the battery tile draws and what the app's
            // chart is drawn from. Registered alongside the two connection actions rather
            // than in a receiver of its own: it is the same subject, and a launcher with
            // two battery receivers is a launcher where one of them gets forgotten.
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(chargingReceiver, filter)
        Log.d("MainActivity", "Charging detection setup complete")
    }

    /**
     * A new reading of the battery: written down, and put on the tile.
     *
     * The record is kept whether or not the tile is pinned and whether or not the shell is
     * up, which is the one place this departs from how the other live tiles are fed. It has
     * to be: a chart of the last day cannot be assembled after the fact, so the moment
     * somebody pins the tile or opens the app is far too late to start watching. What that
     * costs is a comparison and, when the level has actually moved, one string written to
     * preferences - see BatteryStore.record, which mostly decides against.
     *
     * The broadcast arrives for temperature and voltage as well as for the level, so this
     * runs more often than the level changes and is written to be cheap on the times it
     * has nothing to say.
     */
    private fun onBatteryChanged(intent: Intent) {
        val reading = try {
            rocks.gorjan.gokixp.wp81.BatteryStore.from(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "could not read the battery broadcast", e)
            return
        }
        if (!reading.known) return
        rocks.gorjan.gokixp.wp81.BatteryStore.record(this, reading)

        // Only when it says something new. The broadcast carries the temperature and the
        // voltage as well as the level, so on some phones it arrives every half minute
        // saying the same thing about the charge - and what hangs off this is a wall of
        // tiles and, when it is open, a page that re-reads a day of usage events to
        // rebuild itself. Neither is worth doing to redraw the same number.
        val state = reading.percent to reading.charging
        if (state == wp81LastBattery) return
        wp81LastBattery = state
        refreshWP81Battery()
        // And the app, if it happens to be open over the wall: the page it is showing is
        // the same reading, and one that went stale while somebody watched it would be the
        // one screen on the phone that disagreed with the status bar above it.
        batteryAppInstance?.bind()
    }

    /** The last charge the tiles were told about. See [onBatteryChanged]. */
    private var wp81LastBattery: Pair<Int, Boolean>? = null

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

        // Phone and Messaging, the two programs People used to hold pages for. Registered
        // on the same terms, and reached from three other places besides the app list: a
        // tile, one of their own notifications, and an intent from somewhere else on the
        // phone asking to dial or to send a text. See handleDialIntent and
        // handleMessageIntent.
        systemAppActions["system.phone"] = { _ ->
            showPhoneDialog()
        }

        systemAppActions["system.messaging"] = { _ ->
            showMessagingDialog()
        }

        // Welcome. A waiting update does not divert the tap any more: the program opens
        // either way and carries the update across the top of itself, so the tile still
        // leads to the page it names and the download is one tap further on.
        systemAppActions["system.welcome"] = { _ ->
            showWelcomeDialogWP81()
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

        // Files, on the same terms again.
        systemAppActions["system.files"] = { _ ->
            showFilesDialog()
        }

        // Battery, likewise. It is also what the Start screen's battery tile opens, the
        // way the Weather tile opens Weather - the tile is this program's own.
        systemAppActions["system.battery"] = { _ ->
            showBatteryDialog()
        }

        // Cortana, which is also where the search key goes - see the shell's onCortana.
        // Registered here as well so she can be pinned and opened from the app list like
        // anything else, the way the phone let Cortana be pinned to Start.
        systemAppActions["system.cortana"] = { _ ->
            showCortanaDialog()
        }

        // Settings, which opens the very page a hold on Start opens - see openWP81Settings.
        //
        // The one program here that is not a window: settings are a Metro page inside the
        // shell, and this is a second door onto it rather than a second copy of it. The
        // hold was the only way in, and a gesture nobody is told about is a settings screen
        // nobody can find - which was the whole of the problem this answers. An icon in the
        // app list, filed with the rest of the shell's programs and pinnable to Start, is
        // where somebody looks for settings on a phone.
        systemAppActions["system.settings"] = { _ ->
            openWP81Settings()
        }

        Log.d("MainActivity", "System apps initialized: ${systemAppActions.size} apps")
    }

    private fun getSystemAppsList(): List<AppInfo> {
        val systemApps = mutableListOf<AppInfo>()

        // Internet Explorer - scale icon to match app icon size
        val ieDrawable = AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_ie)
        if (ieDrawable != null) {
            systemApps.add(AppInfo(
                name = "Internet Explorer",
                exeName = "iexplore.exe",
                packageName = "system.internet_explorer",
                icon = iconStore.square(ieDrawable),
                minWindowWidthDp = 360
            ))
        }

        // Notepad - scale icon to match app icon size
        val notepadDrawable = AppCompatResources.getDrawable(this,R.drawable.wp81_glyph_notepad)
        if (notepadDrawable != null) {
            systemApps.add(AppInfo(
                name = "Notepad",
                exeName = "notepad.exe",
                packageName = "system.notepad",
                icon = iconStore.square(notepadDrawable)
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
                icon = iconStore.square(glyph)
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
                icon = iconStore.square(tinted)
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
                icon = iconStore.square(tinted)
            ))
        }

        // People: the address book. It used to be this shell's phone and its messaging app
        // as well, on four pages of one panorama - Windows Phone kept those in programs of
        // their own and so does this now. See PeopleApp, and the two below.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_people)?.let { glyph ->
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "People",
                exeName = "people.exe",
                packageName = "system.people",
                icon = iconStore.square(tinted)
            ))
        }

        // Phone: the speed dial, the call log and the keypad. Listed straight after People
        // because that is what it was carved out of, and because the two of them are read
        // as a pair in the app list the way the phone's own were.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_phone)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Phone",
                exeName = "phone.exe",
                packageName = "system.phone",
                icon = iconStore.square(tinted)
            ))
        }

        // Messaging: the conversations, and the box to say something in.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_message_smiley)?.let { glyph ->
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Messaging",
                exeName = "messaging.exe",
                packageName = "system.messaging",
                icon = iconStore.square(tinted)
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
                icon = iconStore.square(tinted)
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
                name = "Alarms & Tasks",
                exeName = "alarms.exe",
                packageName = "system.alarms",
                icon = iconStore.square(tinted)
            ))
        }

        // Weather, the forecast behind the weather tile.
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
                icon = iconStore.square(tinted)
            ))
        }

        // Battery, the charge behind the battery tile: the day it has had, and what spent
        // it. Windows Phone kept its Battery Saver in Settings; this shell gives it a
        // program of its own because the tile it feeds is a program's tile, not a widget
        // standing beside one - see WP81TileHost.PROGRAM_WIDGETS.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_battery)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Battery",
                exeName = "battery.exe",
                packageName = "system.battery",
                icon = iconStore.square(tinted)
            ))
        }

        // Files & Photos: the app Windows Phone 8.1 finally got in 2014 - a plain list of
        // what is on the phone - with the one section the phone kept in a program of its
        // own, a wall of every folder that has pictures in it.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_files)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Files & Photos",
                exeName = "files.exe",
                // Not renamed with the app: this is the address the shell files pinned
                // tiles, saved icons and window state under, and changing it would orphan
                // every one of them on somebody's phone.
                packageName = "system.files",
                icon = iconStore.square(tinted)
            ))
        }

        // Cortana, which is what the search key opens. She is in the list as well because
        // the phone had her there and let her be pinned: the key is the way you reach her
        // without thinking about it, and the tile is the way you reach her on purpose.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_cortana)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Cortana",
                exeName = "cortana.exe",
                packageName = "system.cortana",
                icon = iconStore.square(tinted)
            ))
        }

        // Settings, the way in that is not a gesture. The page it opens is the shell's own
        // - the same one a hold on Start reaches - and this is a program only in the sense
        // that matters here: something with a name, a mark and a row in the app list, that
        // can be pinned to the wall like anything else.
        AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_settings_app)?.let { glyph ->
            // The glyph is drawn white for tiles; the app list is not always dark, so it
            // takes the accent here rather than vanishing on a Light theme.
            val tinted = glyph.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(
                tinted, themeManager.getWP81Accent())
            systemApps.add(AppInfo(
                name = "Settings",
                exeName = "settings.exe",
                packageName = "system.settings",
                icon = iconStore.square(tinted)
            ))
        }

        // Minesweeper - scale icon to match app icon size
        val minesweeperDrawable = AppCompatResources.getDrawable(this,R.drawable.wp81_glyph_minesweeper)
        if (minesweeperDrawable != null) {
            systemApps.add(AppInfo(
                name = "Minesweeper",
                exeName = "minesweeper.exe",
                packageName = "system.minesweeper",
                icon = iconStore.square(minesweeperDrawable)
            ))
        }

        // Solitare - scale icon to match app icon size
        val solitareDrawable = AppCompatResources.getDrawable(this,R.drawable.wp81_glyph_solitaire)
        if (solitareDrawable != null) {
            systemApps.add(AppInfo(
                name = "Solitaire",
                exeName = "solitare.exe",
                packageName = "system.solitare",
                icon = iconStore.square(solitareDrawable)
            ))
        }

        return systemApps
    }

    fun launchSystemApp(packageName: String) {
        // Find the AppInfo for this system app
        val systemApps = getSystemAppsList()
        val appInfo = systemApps.find { it.packageName == packageName }

        // Noted before anything is opened, and noted whether the window is new or was
        // already standing behind something else - going back into a program is being in
        // it, which is the whole of what the switcher is recording.
        //
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

        // Handle system apps. One mark each, the one their tile wears - this was a list
        // of its own answering with the desktop artwork for the programs that had a
        // desktop version (IE's globe, Notepad's pad, the two card games' boxes) and with
        // the phone's glyph for the rest. Nothing draws the desktop artwork: a system
        // tile, a mini tile inside a folder and an app-list row all resolve through
        // [wp81SystemGlyphs], so the second set was only ever loaded and dropped.
        if (isSystemApp(packageName)) {
            val glyph = wp81SystemGlyphs[packageName] ?: return null
            return AppCompatResources.getDrawable(this, glyph)
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


    /**
     * Where the Start background is remembered, as (path key, uri key).
     *
     * One pair, because there is one shell. The desktop launcher kept a wallpaper per
     * theme and asked this which pair to use; the "vista" in the key names is only what
     * the phone shell inherited when it was the fourth theme rather than the only one,
     * and it stays spelled that way so an existing install keeps its picture.
     */
    private fun getCurrentThemeWallpaperKeys(): Pair<String, String> =
        Pair(KEY_WALLPAPER_VISTA_PATH, KEY_WALLPAPER_VISTA_URI)

    
    
    


    


    
    

    
    


    
    


    
    
    
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
                                // A profile, the editor, the jump grid or a command list
                                // was open over the book. Backing out of one of those is a
                                // step inside the app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by People")
                            } else if (frontWindow?.windowIdentifier == "system.phone" &&
                                phoneAppInstance?.handleBack() == true
                            ) {
                                // The keypad, one number's calls, or a command list.
                                Log.d("MainActivity", "Back pressed (modern): handled by Phone")
                            } else if (frontWindow?.windowIdentifier == "system.messaging" &&
                                messagingAppInstance?.handleBack() == true
                            ) {
                                // A conversation, the new-message page, or a command list.
                                Log.d("MainActivity", "Back pressed (modern): handled by Messaging")
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
                            } else if (frontWindow?.windowIdentifier == "system.cortana" &&
                                cortanaAppInstance?.handleBack() == true
                            ) {
                                // Her settings were open over the greeting. Backing out of
                                // those is a step inside the app rather than a way out of it.
                                Log.d("MainActivity", "Back pressed (modern): handled by Cortana")
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

        val phoneKey = rocks.gorjan.gokixp.wp81.CUSTOM_ICONS_KEY
        val phoneIcons = parse(phoneKey)
        val moved = mutableMapOf<String, String>()

        // Vista's is where the writes went, XP's is what the car screen was reading and
        // what the shell carried into memory when it was entered from XP. Both are swept,
        // and so is Classic's, for a setup carried over from the desktop launcher.
        val donors = rocks.gorjan.gokixp.wp81.DESKTOP_CUSTOM_ICON_KEYS

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
                for (key in listOf(rocks.gorjan.gokixp.wp81.CUSTOM_ICONS_KEY) + KEY_CUSTOM_NAMES) {
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
     * The icon a package is shown with, from the one store that knows.
     *
     * Kept as a method rather than every caller reaching for iconStore: it is named in ten
     * places here and in the tile host, and the shell's own artwork has to be handed in
     * (see [IconStore]), so this is where the two halves meet.
     */
    fun getAppIcon(packageName: String, skipCustom: Boolean = false): Drawable? =
        iconStore.iconFor(packageName, skipCustom)

    /**
     * Drops a package's cached artwork, and the app list along with it.
     *
     * The store cannot do the second half - the cached list is the activity's - but the
     * two must happen together: the list holds the old drawables, so invalidating one
     * without the other leaves the stale icon on screen wherever the list is read.
     */
    private fun invalidateIconCache(packageName: String) {
        iconStore.invalidate(packageName)
        cachedAppList = null
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
        // The library has a search of its own, so the key is lent to it while this is the
        // program in front - and given back when a record or a shelf is opened over it.
        // See refreshWP81SearchOffer.
        zuneApp.onSearchOfferChanged = { refreshWP81SearchOffer() }

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

    /**
     * The welcome app while its window is up, so its permission switches can be put back
     * where the system is when the user returns from one of Android's own prompts.
     */
    private var welcomeAppInstance: rocks.gorjan.gokixp.apps.welcome.WelcomeApp? = null

    private var alarmsAppInstance: rocks.gorjan.gokixp.apps.alarms.AlarmsApp? = null

    private var weatherAppInstance: rocks.gorjan.gokixp.apps.weather.WeatherApp? = null

    /**
     * Battery while its window is up, so a level that moves under it is rebound rather
     * than left as it was when the page opened.
     */
    private var batteryAppInstance: rocks.gorjan.gokixp.apps.battery.BatteryApp? = null

    /**
     * Opens Battery.
     *
     * Full-screen and chromeless like the rest of the Metro programs. Bound on the way in
     * rather than left to the next broadcast: the level and the day behind it are both
     * readable on the spot, and a page that opened blank until something happened to the
     * battery would be a page that opened blank.
     */
    private fun showBatteryDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.battery")) {
            // It may have been behind something for a while, and both the level and the
            // record it draws will have moved on.
            batteryAppInstance?.bind()
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.battery"

        val battery = rocks.gorjan.gokixp.apps.battery.BatteryApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onNotify = { title, message -> showNotification(title, message) }
        )
        batteryAppInstance = battery

        val view = battery.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_battery)
        windowsDialog.setTitle("Battery")
        windowsDialog.setOnCloseListener {
            battery.cleanup()
            batteryAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

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
        calculatorAppInstance = calculator

        val view = calculator.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_calculator)
        windowsDialog.setTitle("Calculator")
        // Kept only so a change of theme can reach it - see repaintOpenWP81Programs.
        // Nothing else asks the calculator anything once it is up.
        windowsDialog.setOnCloseListener { calculatorAppInstance = null }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    private var calculatorAppInstance: rocks.gorjan.gokixp.apps.calculator.CalculatorApp? = null

    /**
     * Opens Cortana - the screen behind the phone's search key.
     *
     * Full-screen and chromeless like the rest of the shell's own programs, and one window
     * only. Pressing search while she is already open is not a request for a second
     * Cortana; it is the same request as the first time, so the window comes forward with
     * a new greeting on it and an empty box - see [rocks.gorjan.gokixp.apps.cortana
     * .CortanaApp.greetAfresh]. That is the one thing about this app that is not like the
     * others: for everything else, coming back to a window means finding it where you left
     * it, and the whole point of this one is the moment of arriving at it.
     */
    private fun showCortanaDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.cortana")) {
            cortanaAppInstance?.greetAfresh()
            cortanaAppInstance?.focusInput()
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.cortana"

        val cortana = rocks.gorjan.gokixp.apps.cortana.CortanaApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onOpenUrl = { url, inIe ->
                // Her own setting, not the shell's. A question asked here is not a link
                // arriving from somewhere else, and the two get separate answers - see
                // CortanaSettings.getSearchOpensInIe.
                if (inIe) showInternetExplorerDialog(url) else openInExternalBrowser(url)
            },
            hasMicrophone = {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            onAskForMicrophone = {
                requestPermissions(
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    CORTANA_MICROPHONE_REQUEST_CODE
                )
            }
        )
        cortanaAppInstance = cortana

        val view = cortana.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_cortana)
        windowsDialog.setTitle("Cortana")
        windowsDialog.setOnCloseListener {
            cortana.cleanup()
            cortanaAppInstance = null
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
        // After the window is up, so there is something on screen for the field to take
        // focus in - see CortanaApp.focusInput.
        cortana.focusInput()
    }

    private var cortanaAppInstance: rocks.gorjan.gokixp.apps.cortana.CortanaApp? = null

    /**
     * Hands a URL to whatever Android would have opened it with.
     *
     * The counterpart to [showInternetExplorerDialog] for the one caller that has already
     * decided which of the two it wants. [openUrlShortcut] makes that decision from the
     * shell's own setting; Cortana makes it from hers, and so needs the other half of that
     * function without the deciding.
     *
     * Falls back to the phone's own browser rather than failing silently: a search that
     * produced nothing at all looks like a button that does not work.
     */
    private fun openInExternalBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Nothing on this phone would open $url", e)
            showInternetExplorerDialog(url)
        }
    }

    private var peopleAppInstance: rocks.gorjan.gokixp.apps.people.PeopleApp? = null
    private var phoneAppInstance: rocks.gorjan.gokixp.apps.phone.PhoneApp? = null
    private var messagingAppInstance:
        rocks.gorjan.gokixp.apps.messaging.MessagingApp? = null

    /**
     * Opens People - the phone's address book.
     *
     * Full-screen and chromeless like Zune and News, and one window only: there is one
     * address book, and a second copy of this open somewhere else would be a second view
     * of it that disagrees with the first the moment either one saves anything.
     *
     * The permission is not demanded here. The app opens either way and says what it is
     * missing and offers to ask for it, which is the only honest order to do this in.
     *
     * [contactId] opens straight onto somebody's card, which is how Phone and Messaging
     * reach a person: neither of them has a card of its own any more, and both have rows
     * that are about somebody rather than about a call or a conversation.
     */
    private fun showPeopleDialog(contactId: Long? = null, newContactNumber: String? = null) {
        if (floatingWindowManager.findAndFocusWindow("system.people")) {
            contactId?.let { peopleAppInstance?.showProfile(it) }
            newContactNumber?.let { peopleAppInstance?.showNewContact(it) }
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.people"

        val people = rocks.gorjan.gokixp.apps.people.PeopleApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onRequestPermissions = { permissions ->
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, permissions, PEOPLE_PERMISSION_REQUEST_CODE)
            },
            onNotify = { title, message -> showNotification(title, message) },
            photoPicker = peoplePhotoPickerLauncher,
            onShowThread = { number -> showMessagingDialog(address = number) }
        )
        peopleAppInstance = people
        // The address book is the search this key was made for. See refreshWP81SearchOffer.
        people.onSearchOfferChanged = { refreshWP81SearchOffer() }

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
        // After the window, because the card is a page inside it and there has to be
        // something for it to be a page over.
        contactId?.let { people.showProfile(it) }
        newContactNumber?.let { people.showNewContact(it) }
    }

    /**
     * Opens Phone - the speed dial, the call log and the keypad.
     *
     * One window, on the same terms as People and for the same reason. The three ways in
     * besides the app list all land here: the tile, this app's own missed-call
     * notification, and an `ACTION_DIAL` from anywhere on the phone.
     *
     * A person reached from inside it opens People, and a conversation opens Messaging.
     * They are three programs now, and a program that drew somebody else's page inside its
     * own window would be the split undone.
     */
    private fun showPhoneDialog() {
        if (floatingWindowManager.findAndFocusWindow("system.phone")) return

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.phone"

        val phone = rocks.gorjan.gokixp.apps.phone.PhoneApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onRequestPermissions = { permissions ->
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, permissions, PEOPLE_PERMISSION_REQUEST_CODE)
            },
            onNotify = { title, message -> showNotification(title, message) },
            onBecomeDialer = { requestDialerRole() },
            onShowContact = { id -> showPeopleDialog(contactId = id) },
            onNewContact = { number -> showPeopleDialog(newContactNumber = number) },
            onShowThread = { number -> showMessagingDialog(address = number) }
        )
        phoneAppInstance = phone

        val view = phone.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_phone)
        windowsDialog.setTitle("Phone")
        windowsDialog.setOnCloseListener {
            phoneAppInstance = null
            // Somebody starred or unstarred on the favourites wall is the same book the
            // People tile is a mosaic of.
            refreshWP81People(force = true)
        }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
    }

    /**
     * Opens Messaging - the conversations, and one of them if the caller named a number.
     *
     * [address] and [draft] are how everything else on the phone asks for this: a
     * notification tapped, an `sms:` link followed, a share sheet that picked the messaging
     * app before it picked a person. Handed to the app after its window is up, because a
     * conversation is a page inside it.
     */
    private fun showMessagingDialog(address: String? = null, draft: String? = null) {
        if (floatingWindowManager.findAndFocusWindow("system.messaging")) {
            messagingAppInstance?.showMessages(address, draft)
            return
        }

        val windowsDialog = createThemedWindowsDialog()
        windowsDialog.windowIdentifier = "system.messaging"

        val messaging = rocks.gorjan.gokixp.apps.messaging.MessagingApp(
            context = this,
            palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager),
            onRequestPermissions = { permissions ->
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, permissions, PEOPLE_PERMISSION_REQUEST_CODE)
            },
            onNotify = { title, message -> showNotification(title, message) },
            onBecomeMessenger = { requestSmsRole() },
            onShowContact = { id -> showPeopleDialog(contactId = id) },
            onNewContact = { number -> showPeopleDialog(newContactNumber = number) }
        )
        messagingAppInstance = messaging

        val view = messaging.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_message_smiley)
        windowsDialog.setTitle("Messaging")
        windowsDialog.setOnCloseListener { messagingAppInstance = null }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
        messaging.showMessages(address, draft)
    }

    /**
     * Opens Welcome, the phone's version of the window the desktop themes show after an
     * update.
     *
     * [startOnReleaseNotes] is the update itself opening the program rather than the user:
     * it lands on what changed instead of on the introduction. See showWelcomeScreenIfNeeded.
     */
    private fun showWelcomeDialogWP81(startOnReleaseNotes: Boolean = false) {
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
            loadReleaseNotes = { onReady -> fetchWP81ReleaseNotes(onReady) },
            permissions = launcherPermissions(),
            // The strip across the top, when a version is already known to be waiting. The
            // tile is showing the same thing, and this is where tapping it leads now.
            onDownloadUpdate = updateDownloadLink
                ?.takeIf { it.isNotEmpty() }
                ?.let { link -> { openUrlShortcut(link) } },
            // Tapping the version number asks now rather than waiting for the hourly look.
            onCheckForUpdates = { onDone -> checkForUpdates(manualCheck = true, onDone = onDone) },
            startOnReleaseNotes = startOnReleaseNotes
        )
        welcomeAppInstance = welcomeApp

        val view = welcomeApp.createView()
        windowsDialog.setContentView(view)
        windowsDialog.setBorderless()
        windowsDialog.setSaveState(false)
        windowsDialog.setMaximizable(true)
        windowsDialog.setTaskbarIcon(R.drawable.wp81_glyph_welcome)
        windowsDialog.setTitle("Welcome")
        windowsDialog.setOnCloseListener { welcomeAppInstance = null }
        windowsDialog.setContextMenuView(contextMenu)
        floatingWindowManager.showWindow(windowsDialog)
        turnWP81PageIn(view)
        // The startup jingle, once, as the page turns in - the desktop welcome played a
        // theme and this is the phone's.
        playStartupSound()
    }

    /**
     * The release notes, from the same GitHub releases the desktop welcome reads.
     *
     * One list of what changed, fetched rather than bundled, so it is never a build behind
     * what is actually out.
     */
    /**
     * The wall a fresh install opens on.
     *
     * Windows Phone was never a blank screen: it shipped arranged, and an empty wall is a
     * launcher that looks broken before it has been used. So the first run lays out the
     * tiles below and pins the three programs among them.
     *
     * Only ever on a genuinely fresh install. An arrangement already in preferences is the
     * user's - or the desktop launcher's, carried over by [DesktopImport] - and replacing
     * either with a default would be the one thing this must not do. The marker is written
     * whatever happens, so a wall the user has since emptied is not filled in again behind
     * them.
     */
    private fun seedDefaultWallIfFirstRun() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULT_WALL_SEEDED, false)) return
        prefs.edit { putBoolean(KEY_DEFAULT_WALL_SEEDED, true) }

        val hasIcons = !prefs.getString(KEY_DESKTOP_ICONS, null).isNullOrBlank()
        val hasWall = !prefs.getString(
            rocks.gorjan.gokixp.wp81.WP81TileHost.KEY_BUILTIN_TILES, null).isNullOrBlank()
        if (hasIcons || hasWall) return

        val host = rocks.gorjan.gokixp.wp81.WP81TileHost
        // The one tile on this wall the shell provides itself. Placed among the pinned
        // ones below: the wall packs in index order, and these sizes only tile a
        // four-column screen in this sequence.
        wp81TileHost.saveBuiltInPlacements(
            mapOf(host.WIDGET_CALENDAR to (rocks.gorjan.gokixp.wp81.TileSize.SMALL_WIDE to 1))
        )

        // And the programs, at the indices that tile the four columns. The first four are
        // the ones whose tile is a live one - the clock beside the calendar, the forecast
        // and the camera roll under them, the headlines across the next row - which is
        // where those readings stood when they were the shell's own widgets rather than
        // Alarms', Weather's, Files' and News'. See WP81TileHost.PROGRAM_WIDGETS.
        //
        // Every one of them is also filed in the Windows Apps folder that
        // [seedWindowsAppsFolder] puts on the end of this wall - a tile on Start and a
        // place in the folder are two different answers to "where is Music", and the
        // arrangement can now hold both.
        val system = getSystemAppsList().associateBy { it.packageName }
        listOf(
            Triple("system.alarms", rocks.gorjan.gokixp.wp81.TileSize.SMALL_WIDE, 0),
            Triple("system.weather", rocks.gorjan.gokixp.wp81.TileSize.MEDIUM, 2),
            Triple("system.files", rocks.gorjan.gokixp.wp81.TileSize.MEDIUM, 3),
            Triple("system.news", rocks.gorjan.gokixp.wp81.TileSize.MEDIUM, 4),
            Triple("system.notepad", rocks.gorjan.gokixp.wp81.TileSize.SMALL, 5),
            Triple("system.zune", rocks.gorjan.gokixp.wp81.TileSize.SMALL_WIDE_3, 6),
            // The three that were one program. All at the same size and next to each
            // other, because that is what they are on this wall - the address book, the
            // dialler and the texts, which is the row Windows Phone shipped its own Start
            // screen with. Medium rather than small: two of them have something to say -
            // a call that went unanswered, a text waiting - and a 1x1 tile has nowhere to
            // write it. See TileSize.canShowText.
            Triple("system.people", rocks.gorjan.gokixp.wp81.TileSize.MEDIUM, 7),
            Triple("system.phone", rocks.gorjan.gokixp.wp81.TileSize.MEDIUM, 8),
            Triple("system.messaging", rocks.gorjan.gokixp.wp81.TileSize.MEDIUM, 9)
        ).forEach { (packageName, size, index) ->
            val app = system[packageName] ?: return@forEach
            pinWP81SystemTile(app, size to index, size to index)
        }
        saveDesktopIcons()
        Log.d("MainActivity", "Laid out the default Start screen")
    }

    /**
     * Pins one of the shell's own programs at a place on the wall the shell has chosen.
     *
     * For the two moments the shell puts a tile down itself rather than the user asking
     * for one: laying out the default wall, and handing the live widgets back to the
     * programs they were about. [pinWP81Tile] is the other way in, and puts a tile on the
     * end, which is where one somebody has just asked for belongs.
     *
     * A placement of null leaves that arrangement to stand in from the other, exactly as
     * an unplaced tile does anywhere else. The caller saves: both of them pin several.
     */
    private fun pinWP81SystemTile(
        app: AppInfo,
        upright: Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>?,
        sideways: Pair<rocks.gorjan.gokixp.wp81.TileSize, Int>?
    ) {
        desktopIcons.add(
            DesktopIcon(
                name = app.name,
                packageName = app.packageName,
                icon = app.icon,
                x = 0f,
                y = 0f,
                id = newIconId(app.packageName),
                type = IconType.APP,
                tileSize = upright?.first?.name,
                tileIndex = upright?.second,
                tileSizeLandscape = sideways?.first?.name,
                tileIndexLandscape = sideways?.second
            )
        )
    }

    /**
     * Hands the five live widgets back to the programs they were always about, once.
     *
     * The shell used to keep a Weather tile and a Weather app, a News tile and the reader
     * it opened, a wall of faces and the People hub behind it: the same thing said twice,
     * and the half on the wall was the half nobody could unpin, because it was not theirs
     * to unpin. Each is its program's own tile now - Alarms' clock, Files' camera roll -
     * and Welcome, which was never live at all, is the app-list program it always looked
     * like. See WP81TileHost.PROGRAM_WIDGETS.
     *
     * Which leaves the walls that were arranged before that. Every widget placement
     * becomes a pinned tile for its program, at exactly the size and position the widget
     * held, in both arrangements - so nothing moves on a screen somebody already knows.
     * What changes is that the tile now belongs to something, and can be taken off. One
     * they had hidden stays off: hiding it was them saying they did not want it, and the
     * program is in the app list to be pinned by hand.
     *
     * Once, and marked done whether or not there was anything to move, so a tile unpinned
     * afterwards is not put back behind them.
     */
    private fun connectWP81ProgramTiles() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_WP81_PROGRAM_TILES, false)) return
        prefs.edit { putBoolean(KEY_WP81_PROGRAM_TILES, true) }

        val host = rocks.gorjan.gokixp.wp81.WP81TileHost
        // Each arrangement as it was actually written, with neither standing in for the
        // other: a sideways wall that has never been laid out has no positions to carry
        // over, and inventing them from the upright one would settle a wall the user has
        // not settled themselves.
        val upright = wp81TileHost.storedBuiltInPlacements(sideways = false)
        val sideways = wp81TileHost.storedBuiltInPlacements(sideways = true)
        if (upright == null && sideways == null) return

        val hidden = themeManager.getWP81HiddenTiles()
        val system = getSystemAppsList().associateBy { it.packageName }
        val moved = mutableListOf<String>()
        listOf(
            host.WIDGET_CLOCK to "system.alarms",
            host.WIDGET_WEATHER to "system.weather",
            host.WIDGET_NEWS to "system.news",
            host.WIDGET_PHOTOS to "system.files",
            host.WIDGET_PEOPLE to "system.people",
            host.WIDGET_WELCOME to "system.welcome"
        ).forEach { (widgetId, packageName) ->
            if (widgetId in hidden) return@forEach
            val here = upright?.get(widgetId)
            val there = sideways?.get(widgetId)
            // A wall with placements written but none for this one is a wall this tile was
            // never on, and there is nothing to carry over.
            if (here == null && there == null) return@forEach
            val app = system[packageName] ?: return@forEach

            // The program may already be out on the wall - the arrangement this shipped
            // with pinned Alarms a few squares along from the clock widget that was
            // showing its alarms. Then it moves to where the reading was rather than a
            // second one being pinned there, because two of them would now be the same
            // clock twice.
            val standing = desktopIcons.firstOrNull {
                it.parentFolderId == null && it.type == IconType.APP &&
                    it.packageName == packageName
            }
            if (standing == null) {
                pinWP81SystemTile(app, here, there)
            } else {
                here?.let { (size, index) ->
                    standing.tileSize = size.name
                    standing.tileIndex = index
                }
                there?.let { (size, index) ->
                    standing.tileSizeLandscape = size.name
                    standing.tileIndexLandscape = index
                }
            }
            moved.add(app.name)
        }

        // The placements and the hidden entries go whether or not anything was pinned:
        // they name tiles the shell does not provide any more, and one nothing claims is
        // a hole kept open in an arrangement.
        val retired = host.RETIRED_WIDGETS.toSet()
        upright?.let { wp81TileHost.saveBuiltInPlacements(it - retired, sideways = false) }
        sideways?.let { wp81TileHost.saveBuiltInPlacements(it - retired, sideways = true) }
        themeManager.setWP81HiddenTiles(hidden - retired)

        if (moved.isEmpty()) return
        saveDesktopIcons()
        Log.d("MainActivity", "Gave ${moved.joinToString()} tiles of their own")
    }

    /**
     * Files the launcher's own programs in a folder on the wall, once.
     *
     * The shell ships with a dozen programs of its own and, until now, showed most of them
     * nowhere but the app list: the wall pinned three of them and the rest had to be found
     * by scrolling an alphabetical list of everything installed. A folder called
     * [WINDOWS_APPS_FOLDER_NAME] puts them together where the phone is actually looked at.
     *
     * Every one of them, as copies. An app can be in more than one place - see
     * [pinWP81Tile] - so a program already pinned to the wall or filed in a folder of the
     * user's own is filed here as well rather than taken from where it was: the folder is
     * somewhere to find every one of these, not somewhere they are kept instead. Nothing
     * already on the wall moves.
     *
     * Once, and on every install rather than only new ones - a launcher that already has a
     * wall has the same programs on it and the same reason to gather them. The marker is
     * written whatever happens, so a folder the user has since emptied or deleted is not
     * put back behind them.
     */
    private fun seedWindowsAppsFolder() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val everything = getSystemAppsList()
        val firstRun = !prefs.getBoolean(KEY_WINDOWS_APPS_FOLDER_SEEDED, false)

        // What has already been offered. On a phone that was filed by an older build there
        // is no such record, so it is reconstructed: that build filed the whole list as it
        // stood, which is everything except the programs added since.
        val offered: Set<String> = when {
            prefs.contains(KEY_WINDOWS_APPS_FOLDER_FILED) ->
                prefs.getString(KEY_WINDOWS_APPS_FOLDER_FILED, "")
                    .orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            firstRun -> emptySet()
            else -> everything.map { it.packageName }.toSet() -
                WINDOWS_APPS_FOLDER_LATE_ADDITIONS
        }

        // A program the user has hidden from the app list is one they have said they do
        // not want to see, and a tile in a folder is more visible than the list they hid
        // it from.
        val hidden = getHiddenApps()
        val filing = everything.filterNot { it.packageName in offered || it.packageName in hidden }

        // Whatever happens below, every program the shell currently has counts as offered
        // afterwards - including the hidden ones, which were considered and deliberately
        // passed over. Written before the early returns, so a phone that cannot be filed
        // for some other reason does not try again on every single startup.
        prefs.edit {
            putBoolean(KEY_WINDOWS_APPS_FOLDER_SEEDED, true)
            putString(
                KEY_WINDOWS_APPS_FOLDER_FILED,
                everything.joinToString(",") { it.packageName }
            )
        }

        // Nothing to file is no folder at all: an empty one is worse than none.
        if (filing.isEmpty()) return

        val folder = desktopIcons.firstOrNull { it.id == WINDOWS_APPS_FOLDER_ID }

        // A folder that is not there on an upgrade is one the user threw away, and a
        // program added since is not a reason to put it back. On a first run there is
        // nothing to have thrown away, so one is made.
        if (folder == null && !firstRun) return

        // A folder already wearing this id is the shell's own, from a run of this that was
        // interrupted or from an arrangement restored over the top of one. Filling it is
        // what was wanted either way, and a second folder under the same id would be one
        // the shell can no longer tell from the first.
        val folderIcon = AppCompatResources.getDrawable(this, R.drawable.folder_vista) ?: return
        if (folder == null) desktopIcons.add(
            DesktopIcon(
                name = WINDOWS_APPS_FOLDER_NAME,
                packageName = WINDOWS_APPS_FOLDER_ID,
                icon = folderIcon,
                x = 0f,
                y = 0f,
                id = WINDOWS_APPS_FOLDER_ID,
                type = IconType.FOLDER,
                // On the end of the wall, in both orientations: the wall above it is
                // either the one the launcher ships with or the one the user made, and
                // neither wants a folder pushed into the middle of it.
                tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name,
                tileIndex = lastWP81TileIndex(landscape = false) + 1,
                tileSizeLandscape = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name,
                tileIndexLandscape = lastWP81TileIndex(landscape = true) + 1
            )
        )

        // In the order the shell lists its own programs in, which is the order they were
        // built rather than the alphabet - Internet Explorer and Notepad first, the games
        // last, as the app list has always had them.
        //
        // Every one of them at the same size, which is the size a folder's page is a run
        // of - what an app is pinned at on the wall is about the wall.
        val medium = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
        // Anything already in this folder is from a run of this that was interrupted, and
        // filing a second copy of it beside the first would be the one duplicate nobody
        // asked for.
        val contents = desktopIcons.filter { it.parentFolderId == WINDOWS_APPS_FOLDER_ID }
        val already = contents.map { it.packageName }.toSet()
        // After whatever is in there, rather than from zero: a program arriving on an
        // upgrade goes on the end of a folder the user has already arranged, not on top of
        // whatever is currently first in it.
        // An icon with no index has never been placed, so it has no say in where the end
        // of the folder is - hence mapNotNull rather than a maximum over nullables.
        var next = (contents.mapNotNull { it.tileIndex }.maxOrNull() ?: -1) + 1

        var filed = 0
        for (app in filing) {
            if (app.packageName in already) continue
            desktopIcons.add(
                DesktopIcon(
                    name = app.name,
                    packageName = app.packageName,
                    icon = app.icon,
                    x = 0f,
                    y = 0f,
                    id = newIconId(app.packageName),
                    type = IconType.APP,
                    parentFolderId = WINDOWS_APPS_FOLDER_ID,
                    tileSize = medium,
                    tileIndex = next,
                    tileSizeLandscape = medium,
                    tileIndexLandscape = next
                )
            )
            next++
            filed++
        }
        saveDesktopIcons()
        Log.d(
            "MainActivity",
            "Filed $filed of the shell's own programs in $WINDOWS_APPS_FOLDER_NAME"
        )
    }

    /**
     * The last position anything holds on the wall, so something new can go after it.
     *
     * Both halves of the wall are asked: the built-in widgets keep their placements in
     * preferences of their own, the user's tiles keep theirs on the icons, and the two are
     * sorted into one sequence when the wall is built. Reading only one of them would put
     * a new tile on top of whatever the other has at that position.
     *
     * -1 when the wall is empty, so the first thing on it lands at 0.
     */
    private fun lastWP81TileIndex(landscape: Boolean): Int {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val host = rocks.gorjan.gokixp.wp81.WP81TileHost
        // Sideways falls back to the upright arrangement while there is none of its own,
        // exactly as the wall itself does. See WP81TileHost.loadBuiltInPlacements.
        val raw = (if (landscape) prefs.getString(host.KEY_BUILTIN_TILES_LANDSCAPE, null) else null)
            ?: prefs.getString(host.KEY_BUILTIN_TILES, null)
        val widgets = raw.orEmpty().split(";").mapNotNull {
            it.split(":").getOrNull(2)?.toIntOrNull()
        }
        val tiles = desktopIcons.filter { it.parentFolderId == null }.mapNotNull {
            if (landscape) it.tileIndexLandscape ?: it.tileIndex else it.tileIndex
        }
        return (widgets + tiles).maxOrNull() ?: -1
    }

    /**
     * Everything the launcher needs permission to do, for the welcome app's own page.
     *
     * Gathered here rather than in that app because every one of these is the activity's
     * business - which Android permission, which special-access screen, which role - and
     * the page only draws the answer.
     *
     * Ordered by how visibly the launcher suffers without it: the keyboard first, because
     * a keyboard that is installed and not enabled looks like a keyboard that is missing.
     */
    private fun launcherPermissions(): List<rocks.gorjan.gokixp.apps.welcome.WelcomeApp.Permission> {
        fun runtime(name: String, why: String, vararg perms: String) =
            rocks.gorjan.gokixp.apps.welcome.WelcomeApp.Permission(
                name = name,
                why = why,
                isOn = {
                    perms.all {
                        androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                },
                onTap = {
                    // Already granted: Android will not ask again, so the only way to
                    // change it is its own page for this app.
                    if (perms.all {
                            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                                PackageManager.PERMISSION_GRANTED
                        }
                    ) openAppSettings()
                    else androidx.core.app.ActivityCompat.requestPermissions(
                        this, arrayOf(*perms), WELCOME_PERMISSION_REQUEST_CODE)
                }
            )

        fun screen(name: String, why: String, isOn: () -> Boolean, intent: () -> Intent) =
            rocks.gorjan.gokixp.apps.welcome.WelcomeApp.Permission(
                name = name, why = why, isOn = isOn,
                onTap = {
                    try {
                        startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w("MainActivity", "No settings screen for $name", e)
                        showNotification("Windows Phone", "This phone has no screen for $name")
                    }
                }
            )

        val list = mutableListOf<rocks.gorjan.gokixp.apps.welcome.WelcomeApp.Permission>()

        list += screen(
            "Windows Phone keyboard",
            "Type in the phone's own keyboard. It has to be switched on in Android's " +
                "keyboard list before it can be picked.",
            { isOwnKeyboardEnabled() },
            { Intent(Settings.ACTION_INPUT_METHOD_SETTINGS) }
        )
        list += runtime("Contacts", "Faces on the People tile, and the address book in People.",
            android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)
        list += runtime("Phone", "Making and taking calls, and the call history in Phone.",
            android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG)
        list += runtime("Messages", "Reading and sending texts in Messaging.",
            android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECEIVE_SMS)
        list += runtime("Calendar", "What the Calendar tile shows.",
            android.Manifest.permission.READ_CALENDAR)
        list += runtime("Location", "The weather tile and the Weather app.",
            android.Manifest.permission.ACCESS_COARSE_LOCATION)
        list += runtime("Photos and music", "The Photos tile, Zune's library and the file browser.",
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO)
        list += runtime("Microphone", "Speaking instead of typing, on the keyboard.",
            android.Manifest.permission.RECORD_AUDIO)
        list += runtime("Notifications", "Alarms, timers and messages announcing themselves.",
            android.Manifest.permission.POST_NOTIFICATIONS)

        list += screen(
            "Notification access",
            "The counts on tiles - unread messages, missed calls - and the text a live " +
                "tile shows.",
            { isNotificationListenerEnabled() },
            { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        )
        // Only where the phone has the setting to point at. Before Android 12 an exact
        // alarm needed no permission at all, and asking `canScheduleExactAlarms` there is
        // not merely pointless but fatal: the method does not exist, and the call throws
        // NoSuchMethodError as this list is built - which is a page that opens by itself
        // on first run, so the launcher crashed a second after starting and went on doing
        // it, because the marker saying the page had been shown is written afterwards.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += screen(
                "Alarms and reminders",
                "Alarms and timers going off at the time they were set for.",
                {
                    getSystemService(android.app.AlarmManager::class.java)
                        ?.canScheduleExactAlarms() != false
                },
                {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.fromParts("package", packageName, null))
                }
            )
        }
        // Likewise: all-files access arrived in Android 11, and before it the storage
        // permission granted at install is the whole of the answer. Same crash otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list += screen(
                "All files",
                "Browsing the whole of storage in the file browser, rather than only media.",
                { android.os.Environment.isExternalStorageManager() },
                {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.fromParts("package", packageName, null))
                }
            )
        }
        // The roles ask through Android's own prompt rather than the settings list, which
        // is both fewer taps and the only way that says which app is being chosen.
        list += rocks.gorjan.gokixp.apps.welcome.WelcomeApp.Permission(
            name = "Default phone app",
            why = "Calls opening in the phone's own dialler rather than another app.",
            isOn = { holdsRole(android.app.role.RoleManager.ROLE_DIALER) },
            onTap = { requestDialerRole() }
        )
        list += rocks.gorjan.gokixp.apps.welcome.WelcomeApp.Permission(
            name = "Default messaging app",
            why = "Texts arriving in Messages rather than another app.",
            isOn = { holdsRole(android.app.role.RoleManager.ROLE_SMS) },
            onTap = { requestSmsRole() }
        )
        return list
    }

    /** Whether this launcher currently holds one of Android's app roles. */
    private fun holdsRole(role: String): Boolean = try {
        getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(role) == true
    } catch (e: Exception) {
        false
    }

    /**
     * Whether this app's keyboard is switched on in Android's list of input methods.
     *
     * Asked of InputMethodManager rather than read out of `Settings.Secure`. The setting
     * holds the same list, but a normal app is not guaranteed to be allowed to read it -
     * which is silent, because the read simply returns null and the keyboard then reports
     * itself as off while working perfectly well.
     */
    private fun isOwnKeyboardEnabled(): Boolean = try {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { it.packageName == packageName } == true
    } catch (e: Exception) {
        Log.w("MainActivity", "Could not read the enabled keyboards", e)
        false
    }

    /**
     * The keyboard's own settings page, from the shell's.
     *
     * In this task rather than a new one, unlike everything else launched from here: it is
     * this app's own page, and the back key should come back to the settings the user
     * opened it from rather than dropping them out of the launcher entirely. The keyboard
     * starts the same activity with `NEW_TASK` because an input method has no task to
     * start one in - see WP81KeyboardService.showSettings.
     */
    private fun openKeyboardSettings() {
        try {
            startActivity(
                Intent(this, rocks.gorjan.gokixp.wp81.keyboard.KeyboardSettingsActivity::class.java)
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not open the keyboard's settings", e)
        }
    }

    /**
     * Android's own settings, from the row at the foot of this launcher's.
     *
     * The top of them rather than any particular screen: what somebody wants from here is
     * unknown - the network, the volume, the clock, a permission - and the phone's own
     * front page is the one place all of it is reachable from.
     *
     * NEW_TASK because this leaves the launcher: the settings app belongs in its own entry
     * in the task switcher, not stacked on top of the home screen, or backing out of it
     * would land on Start with settings still notionally underneath.
     *
     * A phone with no settings activity to open is not a phone, but the launcher is a home
     * screen and cannot afford to take that on faith - see the same guard on every other
     * screen this hands over to.
     */
    private fun openPhoneSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not open the phone's settings", e)
            showNotification("Settings", "This phone has no settings screen to open")
        }
    }

    /** This app's own page in Android settings, for a permission already granted. */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not open this app's settings page", e)
        }
    }

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
                showNotification(title, message, onTap = onTap)
            },
            onUpdateWindowTitle = { title -> windowsDialog.setTitle(title) },
            onReturnToLinkCaller = { returnToLinkCaller() },
            // The last tab closed. A browser with nothing open in it is nothing to look
            // at, so the window goes the same way it goes when back runs out.
            onRequestClose = { windowsDialog.closeWindow() }
        )
        metroIEAppInstance = ieApp
        // The address bar takes a search as readily as an address, and the key is how it
        // is reached without going for the strip. See refreshWP81SearchOffer.
        ieApp.onSearchOfferChanged = { refreshWP81SearchOffer() }

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
                // Asked of the feed rather than through refreshWP81NewsFeeds, which reads
                // nothing unless there is a News tile on Start. The request came from the
                // reader, which is open and waiting on it whether the wall has a tile or not.
                wp81NewsFeed.refreshIfStale(ids.toList().sorted(), force = true)
            },
            customFeeds = { themeManager.getWP81CustomNewsFeeds() },
            onCustomFeedsChanged = { feeds ->
                themeManager.setWP81CustomNewsFeeds(feeds)
                // An id in the enabled set that no longer names anything is a feed that
                // has been deleted. Cleared here rather than at the point of deletion, so
                // that a set left holding one - by a list that failed to save, or by a
                // build that spelled an id differently - is tidied up at the next change
                // rather than leaving the tile waiting on a feed nobody can read.
                val live = themeManager.getWP81NewsFeeds()
                    .filter { themeManager.getWP81NewsSource(it) != null }.toSet()
                if (live != themeManager.getWP81NewsFeeds()) {
                    themeManager.setWP81NewsFeeds(live)
                }
                // No fetch from here: the reader follows every change to this list with the
                // set of feeds it wants read, and that is what asks for one.
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
        windowsDialog.setTitle("Alarms & Tasks")
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
        // The box that finds a town is this app's search, and the key is how it is reached
        // from the two sections that do not carry the plus. See refreshWP81SearchOffer.
        weather.onSearchOfferChanged = { refreshWP81SearchOffer() }

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
     * Opens Files & Photos.
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
        windowsDialog.setTitle("Files & Photos")
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
            // Marked as shown *before* the page is opened, not after.
            //
            // This is the launcher's home screen opening a window on itself a second after
            // starting, unasked. If that throws, the marker written afterwards never gets
            // written - so the next launch tries again, throws again, and the phone is left
            // with a home screen that dies a second after every single start with no way
            // back in. A welcome page missed because it could not be drawn is a far smaller
            // thing than that, so the cost of failure is one page rather than the launcher.
            prefs.edit { putString(KEY_SHOWN_WELCOME_FOR_VERSION, currentVersion) }

            // The phone's own welcome. A Vista dialog with a picture and two buttons over
            // a Start screen would be a window from another operating system, which is
            // why this branched before; there is only the one shell to greet now.
            //
            // An update opens it on the release notes - the reason it is opening at all is
            // that something changed, and the person reading has met the introduction. A
            // first install, which is the null, opens on the introduction itself.
            try {
                showWelcomeDialogWP81(startOnReleaseNotes = shownForVersion != null)
            } catch (e: Throwable) {
                // Throwable rather than Exception: the failure this is here for was a
                // NoSuchMethodError, which is an Error and would sail straight past a
                // catch on Exception into the main looper.
                Log.e("MainActivity", "Could not open the welcome page", e)
            }
        }

        offerDesktopImportIfAvailable()
    }

    /**
     * Offers to bring the Start screen across from the desktop launcher.
     *
     * Windows Phone was a theme inside that launcher until it became this app, and it sets
     * a copy of the arrangement aside when the theme is removed. See [DesktopImport].
     *
     * Asked rather than done, and asked once. Importing replaces what is here - the
     * preferences are restored wholesale, because a half-merged Start screen is worse than
     * either of the two it came from - so it is only ever the right answer on a launcher
     * the user has not arranged yet. The offer is marked as made whichever way they
     * answer, so declining is not re-asked on the next launch.
     */
    private fun offerDesktopImportIfAvailable() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(DesktopImport.KEY_IMPORT_OFFERED, false)) return

        val paths = DesktopImport.available(this) ?: return
        val shell = wp81Shell ?: return

        shell.inputDialog.confirm(
            "windows phone",
            "Your Start screen from Windows Launcher can be brought over - tiles, colours, " +
                "icons and background. This replaces what is here now.",
            "import"
        ) {
            prefs.edit { putBoolean(DesktopImport.KEY_IMPORT_OFFERED, true) }
            if (DesktopImport.importFrom(this, paths)) {
                // Everything on screen was built from the preferences that have just been
                // replaced, so it is all read again rather than patched.
                iconStore.load()
                applyIconPack()
                loadDesktopIcons()
                applyWP81StartBackground()
                refreshWP81Tiles()
                refreshWP81AppList()
                showNotification("Windows Phone", "Your Start screen was brought over")
            } else {
                showNotification("Windows Phone", "Could not bring your Start screen over")
            }
        }

        // Marked as offered even if they never answer: the dialog has been put in front of
        // them, and a launcher that asks again on every start is worse than one that does
        // not ask twice.
        prefs.edit { putBoolean(DesktopImport.KEY_IMPORT_OFFERED, true) }
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
        // Either a bundled asset or the picture the user browsed for, which lives in app
        // storage under a file:// path - see copyWP81BackgroundLocally. The phone's
        // settings page shows both in the same strip, so both are read the same way.
        fun open(): java.io.InputStream? =
            if (path.startsWith("content://") || path.startsWith("file://")) {
                contentResolver.openInputStream(path.toUri())
            } else {
                assets.open(path)
            }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, maxPx, maxPx)
        }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) }
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
     * Does nothing. The phone shell paints its own Start background - see
     * applyWP81StartBackground - and never a desktop wallpaper behind it.
     *
     * Kept as an empty seam rather than unpicked from its four callers, which are the
     * wallpaper picker and the slideshow; both still have a picture to hand and no longer
     * have anywhere to put it.
     */
    private fun applyWallpaperDrawable(drawable: Drawable, uri: Uri? = null) = Unit


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

            // The bottom is a margin on the shell's ground rather than padding on this
            // view, so that the band it frees up is inside this layout and can be given a
            // colour of its own. Padding would leave that band showing root_container's
            // background, which is the page's colour and belongs behind the status bar at
            // the top - not under the navigation strip at the bottom. See gestureBarStrip.
            if (view.paddingLeft != padLeft || view.paddingRight != padRight ||
                view.paddingBottom != 0 || view.paddingTop != padTop) {
                view.setPadding(padLeft, padTop, padRight, 0)
            }
            findViewById<View>(R.id.main_background)?.let { ground ->
                val params = ground.layoutParams as? RelativeLayout.LayoutParams
                if (params != null && params.bottomMargin != padBottom) {
                    params.bottomMargin = padBottom
                    ground.layoutParams = params
                }
            }
            findViewById<View>(R.id.gesture_bar_strip)?.let { strip ->
                if (strip.layoutParams.height != padBottom) {
                    strip.layoutParams = strip.layoutParams.apply { height = padBottom }
                }
            }
            paintWP81NavBar()
            applyWP81CornerInsets(insets, statusBars.top, navBars.bottom, padLeft, padRight)

            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    /**
     * Stands the shell's two edge bars clear of the display's rounded corners.
     *
     * A phone with round corners cuts a bite out of each of them, and nothing tells an app
     * about it: the corner is not an inset, because it is not a bar - it is the shape of the
     * screen. While Android's own status bar is on show it hides the problem, since our
     * strip is then drawn below the whole arc; hide it - which is what "full screen" does -
     * and the strip lands in the steepest part of the curve, where it lost the first signal
     * bar and the last digit of the clock.
     *
     * The bars are the only things this has to be done for. Everything between them is a
     * page with margins of its own and comes nowhere near an arc.
     */
    private fun applyWP81CornerInsets(
        insets: androidx.core.view.WindowInsetsCompat,
        statusBarPx: Int,
        navBarPx: Int,
        padLeft: Int,
        padRight: Int
    ) {
        val shell = wp81Shell ?: return
        val (topRadius, bottomRadius) = roundedCornerRadii(insets)
        val density = resources.displayMetrics.density

        // The camera first, because it moves what the strip writes and so changes how far
        // the corner reaches into it. Only what Android's own status bar was not already
        // covering: with that bar on show the lens is behind it and there is nothing to do,
        // and full screen is where the shell gets handed the band the camera is punched
        // through. See WP81StatusBar.setTopInset.
        val cutout = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        val cameraPx = maxOf(0, cutout.top - statusBarPx)
        shell.setStatusBarTopInset(cameraPx)

        // How far the content of each bar sits from the physical edge of the screen: the
        // system bar's own height, plus whatever the camera pushed it down by, plus the
        // bar's own padding.
        val stripContent = statusBarPx + shell.statusBarContentTopPx
        val keyContent = navBarPx +
            (rocks.gorjan.gokixp.wp81.WP81NavBar.GLYPH_INSET_DP * density).toInt()
        // A side bar in landscape has already moved the shell in by that much, so only what
        // the arc asks for beyond it is left to do here.
        val alreadyIn = maxOf(padLeft, padRight)
        shell.setCornerInsets(
            maxOf(0, cornerSideInsetPx(topRadius, stripContent) - alreadyIn),
            maxOf(0, cornerSideInsetPx(bottomRadius, keyContent) - alreadyIn)
        )
        // The container windowed programs are drawn in is a sibling of the shell rather
        // than a child, so it is laid out against the strip by hand and has to be told
        // when the strip's height moves under it.
        rebaseFloatingWindowsForWP81()
    }

    /**
     * The radius of the sharpest corner along the top of the display, and along the bottom.
     *
     * The sharpest of each pair rather than each corner's own, because a bar is padded the
     * same at both ends: two different insets on one strip would be a clock further from its
     * edge than the signal is from the other, which reads as a mistake rather than as a
     * screen with one corner rounder than the other.
     *
     * Nothing before Android 12 will say, so nothing is done there - those phones are square
     * enough that the strip was never bitten into.
     */
    private fun roundedCornerRadii(
        insets: androidx.core.view.WindowInsetsCompat
    ): Pair<Int, Int> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return 0 to 0
        val platform = insets.toWindowInsets() ?: return 0 to 0
        fun radius(position: Int) = platform.getRoundedCorner(position)?.radius ?: 0
        return maxOf(
            radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            radius(android.view.RoundedCorner.POSITION_TOP_RIGHT)
        ) to maxOf(
            radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
            radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
        )
    }

    /**
     * How far in from the side something must start to clear a corner of [radiusPx], when it
     * sits [contentEdgePx] from that edge of the screen.
     *
     * The corner is a quarter circle centred [radiusPx] in from both edges, so at a distance
     * `y` from the top the screen begins at `r - sqrt(r^2 - (r - y)^2)`. Content further down
     * than the radius is past the arc entirely and needs nothing - which is the answer
     * whenever the system's own bars are on show, and is why this quietly does nothing until
     * the shell is put full screen.
     */
    private fun cornerSideInsetPx(radiusPx: Int, contentEdgePx: Int): Int {
        if (radiusPx <= 0 || contentEdgePx >= radiusPx) return 0
        val fromCentre = (radiusPx - contentEdgePx).toDouble()
        val reach = radiusPx - kotlin.math.sqrt(
            radiusPx.toDouble() * radiusPx - fromCentre * fromCentre)
        return reach.toInt().coerceAtLeast(0)
    }

    fun playClickSound() = playSound(R.raw.click)


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
     * Once this launcher holds the phone role this is where every DIAL lands - a number
     * tapped in the browser, in a message, on a web page - and the right answer to all of
     * them is the same: open Phone's keypad with it already typed, one tap short of the
     * call. Never
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

        rocks.gorjan.gokixp.apps.phone.MissedCallReceiver.clear(this)
        try {
            (getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager)
                ?.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Only the default phone app may say this, and it is not always this one.
            Log.d("MainActivity", "Could not clear the missed calls", e)
        }

        showPhoneDialog()
        // After the window, because the history is a page inside it.
        phoneAppInstance?.showHistory()
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

        showPhoneDialog()
        // After the window, because the keypad is a page inside it and there has to be
        // something for it to be a page over.
        phoneAppInstance?.showDialer(number)
    }

    /**
     * Somebody asking to send or read a message.
     *
     * Four ways in and one answer. This app's own notifications ask by name; an `sms:` or
     * `smsto:` link followed anywhere on the phone arrives as `SENDTO`, which is the filter
     * that makes this launcher eligible to be the messaging app in the first place; a
     * picture message it cannot show offers the list as somewhere to go instead.
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

        showMessagingDialog(address?.takeIf { it.isNotEmpty() }, draft)
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
                // A tile keeps the name it was pinned under, so a program that has since
                // been renamed needs saying so here or its tile carries the old label for
                // ever. Only the untouched ones: a name the user typed themselves is
                // theirs to keep.
                val name = (iconData["name"] as String).let {
                    when {
                        packageName == "system.zune" && it == "Zune" -> "Music"
                        packageName == "system.alarms" && it == "Alarms" -> "Alarms & Tasks"
                        else -> it
                    }
                }
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
                                // Use custom icon if available, otherwise the folder art.
                                getAppIcon(packageName)
                                    ?: AppCompatResources.getDrawable(
                                        this, themeManager.getFolderIconRes())!!
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
                                            ?: AppCompatResources.getDrawable(this, R.drawable.wp81_glyph_ie)!! // Fallback to IE icon
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
        val resource = when (type) {
            IconType.RECYCLE_BIN -> R.drawable.recycle
            IconType.MY_COMPUTER -> themeManager.getMyComputerIcon()
            IconType.FOLDER -> themeManager.getFolderIconRes()
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
                    } else if (frontWindow?.windowIdentifier == "system.cortana" &&
                        cortanaAppInstance?.handleBack() == true
                    ) {
                        // Her settings were open over the greeting.
                        Log.d("MainActivity", "Back pressed (legacy): handled by Cortana")
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
                    } else if (frontWindow?.windowIdentifier == "system.phone" &&
                        phoneAppInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by Phone")
                    } else if (frontWindow?.windowIdentifier == "system.messaging" &&
                        messagingAppInstance?.handleBack() == true
                    ) {
                        Log.d("MainActivity", "Back pressed (legacy): handled by Messaging")
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
        // Said again rather than only when it changes, and cheaply: the keyboard hears this
        // through a receiver its service registers, and the service is not always there to
        // hear - the system takes it down when another keyboard is picked and builds it again
        // later, in a process that may have outlived it holding an older answer. Repeating it
        // here means any missed change is put right before the launcher is on screen, which
        // is necessarily before anybody types into one of its text boxes. See
        // KeyboardAppearance.
        rocks.gorjan.gokixp.wp81.keyboard.KeyboardAppearance.publish(
            this, themeManager.getWP81Accent(), themeManager.isWP81Dark()
        )
        refreshWeatherIfNeeded()

        // Most likely straight back from one of Android's permission prompts. The switches
        // on the welcome app's permissions page show what was actually granted, which is
        // not always what was asked for.
        welcomeAppInstance?.refresh()

        // Check for new apps when resuming and start periodic checking
        checkForNewApps()
        // And for what the check above cannot see: an uninstall and an install between two
        // resumes leave the app count exactly as it was.
        if (tidyDesktopIcons()) {
            refreshWP81Tiles()
            refreshWP81OpenFolder()
        }
        startPeriodicAppChecking()

        // And the same for the phone shell's settings page, which the user can now come
        // back to rather than being put on Start - see wp81ReturningToWhatWasOpen.
        refreshWP81SettingsPermissions()

        // A bar the user swiped back into view while they were here, or one the app they
        // came home from left standing, is put away again. See applyWP81Fullscreen.
        if (wp81Shell != null) applyWP81Fullscreen()

        // The strip stops being refreshed while the launcher is not on screen, so the
        // first thing somebody coming home would see is the clock as it was when they
        // left. Taken now rather than on the next tick, which is up to two seconds away
        // and is two seconds spent looking at the wrong time.
        wp81Shell?.statusBar?.refresh(force = true)
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
        // And the keyboard, which is switched on in Android's own list of input methods -
        // reached from the keyboard's settings page, and so left behind exactly the same
        // way: by a home gesture that reports nothing back.
        shell.settingsPage.setKeyboardEnabled(isOwnKeyboardEnabled())
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
                iconStore.trim(fraction = 2)
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
        iconStore.evictAll()
        // Clear cached app list
        cachedAppList = null
        Log.d("MainActivity", "Cleared cached app list")
    }

    /**
     * Clear non-essential caches while keeping visible items
     */
    private fun clearNonEssentialCaches() {
        iconStore.trim()
        // The app list holds drawables too, and rebuilds itself when next asked for.
        cachedAppList = null
    }

    // Weather-related functions
    private var weatherUpdateRunnable: Runnable? = null
    


    
    

    
    

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
    
    /**
     * Fetches the weather, where there is anything to fetch it with.
     *
     * Both of the ways out used to be a reading of their own: without permission or
     * without a network the taskbar's chip was set to "?", and on the cached path it was
     * set to the stored temperature. Nothing on screen belongs to this any more - what is
     * stored is what the Start tile and the Weather app read - so the answer here is only
     * whether to go and ask.
     */
    private fun updateWeatherTemperature() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "No location permission - leaving the weather as it stands")
            return
        }

        if (!isNetworkAvailable()) {
            Log.d("MainActivity", "No network connection - keeping the cached weather")
            return
        }

        fetchLocationAndWeather()
    }
    
    private fun fetchLocationAndWeather() {

        // A place chosen by hand in the Weather app is the phone's weather from then on:
        // the Start screen's tile and the app show one forecast, and going to the radio
        // here would fetch a second one for a place nobody asked about. It also means a
        // pinned place works with location switched off entirely.
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
                }, 10000) // Reduced to 10 seconds
            }
        } catch (e: SecurityException) {
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
                                Log.d("MainActivity", "Weather updated successfully: " +
                                    formatTemperature(temperature))

                                // And the Start screen's weather tile, which would
                                // otherwise sit on the old reading until the next tick.
                                refreshWP81Weather()
                                // An open Weather app is looking at the forecast that has
                                // just been replaced underneath it.
                                weatherAppInstance?.bind()
                            }
                            // And the air, from the other server, for the same place.
                            rocks.gorjan.gokixp.wp81.WeatherStore.refreshAirQuality(
                                this@MainActivity, latitude, longitude
                            ) { weatherAppInstance?.bind() }
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
    
    /**
     * What is left when a fetch does not come back.
     *
     * Nothing, now. This fell back to the stored reading and put it in the taskbar's
     * chip; with the chip gone, the stored reading is already what everything shows, and
     * the fallback is simply to leave it alone.
     */
    private fun handleWeatherFetchFailure() {
        Log.d("MainActivity", "Weather fetch failed - keeping what is stored")
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


    /**
     * Fetches the weather again if what is stored has gone stale.
     *
     * Asked of the store rather than of the screen. This used to read the temperature out
     * of the taskbar's own chip and refresh when it said "?" or "..." - which was the
     * desktop's way of noticing it had never got an answer. There is no chip now; the
     * cache is the only thing that knows.
     */
    private fun refreshWeatherIfNeeded() {
        if (getCachedWeatherJson() == null || isCachedWeatherDataOld()) {
            Log.d("MainActivity", "Weather refresh needed")
            updateWeatherTemperature()
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
            // Cortana asked so that she could listen for a song. Either way the answer goes
            // straight back to her: she is the screen that put the question up, and she is
            // the one that has to say what happens next.
            CORTANA_MICROPHONE_REQUEST_CODE -> cortanaAppInstance?.onMicrophoneAnswered(
                grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            )

            LOCATION_PERMISSION_REQUEST_CODE -> if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, fetch weather
                fetchLocationAndWeather()
            } else {
                // Permission denied, show settings or keep question mark

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
                // Whatever was granted, every one of the three re-reads: the address book,
                // the call log and the message store are asked for from three programs
                // that share this one request code, and a page that was showing "tap to
                // allow" fills in where it stands rather than asking the user to leave the
                // app and come back to it. Any of them that is not open is null.
                peopleAppInstance?.refresh()
                phoneAppInstance?.refresh()
                messagingAppInstance?.refresh()
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
    /**
     * Notifications arrived or cleared, so the tiles carrying counts are brought up to date.
     *
     * Deliberately [refreshWP81Notifications] and not [refreshWP81Tiles]. This runs every
     * two seconds, and refreshWP81Tiles goes through StartScreenView.setTiles, which
     * rebuilds the wall from scratch and drops the tile being edited - so a rebuild on this
     * pass took the user out of edit mode within two seconds of entering it, every time.
     * setNotifications updates the counts on the tiles that are already there.
     */
    fun updateNotificationDots() {
        refreshWP81Notifications()
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
    /**
     * An id no icon in the arrangement is already using.
     *
     * The default is the package name and the clock, which was unique enough while an app
     * could only be in one place. Now that the same app can be pinned twice - on the wall
     * and in a folder - two copies made in the same millisecond would carry the same id,
     * and everything that finds an icon finds it by id: which tile was dragged, which was
     * resized, which was unpinned.
     */
    private fun newIconId(packageName: String): String {
        val base = "${packageName}_${System.currentTimeMillis()}"
        if (desktopIcons.none { it.id == base }) return base
        var copy = 2
        while (desktopIcons.any { it.id == "${base}_$copy" }) copy++
        return "${base}_$copy"
    }

    private fun removeIcons(ids: Set<String>) {
        if (ids.isEmpty()) return
        desktopIcons.removeAll { it.id in ids }
        // A tile's colour is remembered against the icon's id, and no icon will ever carry
        // that id again - ids are minted from a package name and the clock, so even a
        // reinstall makes a new one. Left behind, it is a preference that grows by an entry
        // per deletion and is never read again.
        ids.forEach {
            themeManager.setWP81TileColor(it, null)
            // And whether it was holding its picture back, which is remembered the same
            // way and would otherwise be left behind for the same reason.
            themeManager.setWP81TilePicture(it, true)
        }
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
            reapplyIfIconPack(packageName)
        }
    }
    
    override fun onAppReplaced(packageName: String) {
        Log.d("MainActivity", "App replaced notification: $packageName")
        runOnUiThread {
            // An update can ship a different icon under the same package name, so the cached
            // bitmaps have to go before anything reads the icon again
            refreshIconsForPackage(packageName)
            refreshWP81ForPackageChange(packageName)
            reapplyIfIconPack(packageName)
        }
    }

    /**
     * Re-resolves every app icon when the package that changed is the icon pack itself.
     *
     * The routines above are all keyed on the one package that changed, which is right for
     * an app but wrong for a pack: a pack leaving takes its artwork off every app it was
     * dressing, and a pack updating can ship a whole new set under the same name. So this
     * is the wall, the app list and the cache rather than one entry in any of them.
     *
     * Cheap to ask and almost always false - it is a string comparison against a
     * preference - so it is asked on every package change rather than guessed at.
     */
    private fun reapplyIfIconPack(packageName: String) {
        if (packageName != themeManager.getWP81IconPack()) return
        Log.d("MainActivity", "Icon pack $packageName changed; re-resolving every app icon")
        // Re-opened rather than kept: an update ships new artwork under the same package,
        // and the instance in hand is holding the previous version's resources.
        commitIconPack(reopen = true)
        refreshWP81IconPackRow()
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
        // The Action Center keeps its own copy of every app's name and mark, because it
        // rebuilds its list from a two-second tick and cannot ask the package manager
        // each time. That copy is now out of date for this app.
        wp81Shell?.actionCenter?.invalidateApps()
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

    private var wp81LiveTileRunnable: Runnable? = null
    private val wp81Handler = Handler(Looper.getMainLooper())

    /** Latest next-event summary for the Calendar live widget, or null when there is none. */
    private var wp81NextCalendarEvent: Pair<String, Long>? = null

    private var wp81CalendarProvider: rocks.gorjan.gokixp.wp81.CalendarWatcher? = null
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
        "system.notepad" to R.drawable.wp81_glyph_notepad,
        "system.zune" to R.drawable.wp81_glyph_headphones,
        "system.news" to R.drawable.wp81_glyph_news,
        "system.welcome" to R.drawable.wp81_glyph_welcome,
        "system.minesweeper" to R.drawable.wp81_glyph_minesweeper,
        "system.solitare" to R.drawable.wp81_glyph_solitaire,
        "system.calculator" to R.drawable.wp81_glyph_calculator,
        "system.people" to R.drawable.wp81_glyph_people,
        "system.phone" to R.drawable.wp81_glyph_phone,
        "system.messaging" to R.drawable.wp81_glyph_message_smiley,
        "system.alarms" to R.drawable.wp81_glyph_clock,
        "system.weather" to R.drawable.wp81_glyph_weather,
        "system.battery" to R.drawable.wp81_glyph_battery,
        "system.files" to R.drawable.wp81_glyph_files,
        "system.cortana" to R.drawable.wp81_glyph_cortana,
        "system.settings" to R.drawable.wp81_glyph_settings_app
    )


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
        for ((view, elevationDp) in listOf(
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
                    // The keys' height, or none if they are hidden - see wp81NavBarInsetPx.
                    bottomMargin = wp81NavBarInsetPx()
                }
            )
        }
    }


    private fun applyWindowsPhone81Theme() {
        Log.d("MainActivity", "Applying Windows Phone 8.1 theme")

        val palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)

        val mainBackground = findViewById<RelativeLayout>(R.id.main_background)
        mainBackground?.setBackgroundColor(palette.background)

        // root_container is declared black in the layout, and it is what shows through the
        // padding held back for the system status and navigation bars. Left alone, a Light
        // theme would sit in a black frame top and bottom.
        findViewById<View>(R.id.root_container)?.setBackgroundColor(palette.background)

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
        applyWP81NavBarVisibility()
        applyWP81ActionCenter()
        applyWP81SystemBarAppearance(palette)
        applyWP81Fullscreen()
        applyWP81StartBackground()

        // The phone keeps its own hand-picked icons, so they have to be read for this
        // theme the way each desktop theme reads its own. Without this the shell arrived
        // still holding the previous theme's mappings - showing its icons on the tiles,
        // and writing that borrowed set back out under the phone's key the moment one
        // tile icon was changed.
        iconStore.load()
        // Read alongside them, and for the same reason: the pack is the other half of the
        // answer to "what is this app wearing", and the wall is about to be built from it.
        applyIconPack()
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
        // The navigation bar is not asked about here: what is behind it is the strip's
        // ground rather than the page's, and on an accent those two disagree. See
        // paintWP81NavBar.
    }

    private fun wireWP81Shell(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        wireWP81Settings(shell)
        wireWP81ActionCenter(shell)
        shell.startScreen.onLaunch = { tile -> launchWP81Tile(tile) }
        // The wall's pull-down: the shell's own Action Center where the user has asked
        // for it, and Android's own shade where they have not. Read at the moment the
        // gesture fires rather than wired once, so the switch in settings takes effect on
        // the next pull rather than on the next launch.
        shell.startScreen.onSwipeDownAtTop = {
            if (themeManager.getWP81ActionCenter()) openWP81ActionCenter(shell)
            else expandNotificationShade()
        }
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
            // And the battery, which is where a tile just pinned from the app list gets
            // its first charge to draw: the broadcast that would otherwise hand it one may
            // not come round for a minute.
            refreshWP81Battery()
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
        //
        // Every one of them, now. This used to ask a wp81MetroApps set first, because the
        // launcher also carried the desktop's programs - the Registry Editor, Winamp, the
        // media player - and those wore their own desktop artwork in the list rather than
        // a glyph on the accent. They are gone, so the set had come to name every system
        // program there is and the question always came back yes.
        shell.appList.metroGlyph = { app -> wp81SystemGlyphs[app.packageName] }
        // A picked icon is the tile's and the row's alike. See wp81CustomGlyphFor.
        shell.appList.customGlyph = { app -> wp81CustomGlyphFor(app.packageName) }
        shell.appList.onLongPress = { app, anchorY ->
            // No buzz of its own: the row gives the shell's tick as it claims the press.
            // See AppListView's long-click listener, and wp81.Haptics.
            // A menu over a keyboard leaves the commands squeezed into what is left of the
            // screen; the search text is kept so the list is unchanged on the way back.
            shell.appList.hideKeyboard()
            shell.contextMenu.show(app.name, wp81AppMenu(app), anchorY)
        }

        // The search key opens Cortana, which is what it did on Windows Phone 8.1 - the
        // third capacitive key stopped being "search" and became "ask her" in that release,
        // and it is one of the most recognisable things about the phone. It used to hand
        // off to whatever Android app claims web search, which put Google's box on top of
        // the launcher and was the one key on the phone that left it.
        //
        // Where the screen in front has a search of its own the tap goes there instead and
        // the key wears the accent to say so; holding it is Cortana wherever the user is.
        // See WP81Searchable, and the shell's searchHere.
        shell.onCortana = { showCortanaDialog() }
        shell.appList.onSearchWeb = { query ->
            // Leaving search behind: coming back to a list still filtered to nothing, with
            // the keyboard up, is not where anyone wants to land after being sent away.
            shell.appList.endSearch()
            searchTheWebFor(query)
        }
        shell.navBar.onBack = { onBackPressedDispatcher.onBackPressed() }
        // Holding it: the task switcher. See WP81NavBar.applyHold for why the hold is timed
        // by hand rather than left to the framework's long press.
        // "Remove from folder" is on the tile's own command list, where the rest of the
        // once-a-tile things live.
        shell.secondaryBar.onAddApp = { addAppToOpenWP81Folder() }
        shell.secondaryBar.onNewFolder = {
            shell.selectedTile()?.let { tile -> newWP81FolderFrom(tile) }
        }
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
        shell.secondaryBar.onTilePicture = {
            shell.selectedTile()?.let { tile ->
                val shown = tile.id !in themeManager.getWP81TilesWithoutPicture()
                themeManager.setWP81TilePicture(tile.id, !shown)
                applyWP81TilePictures()
                // The ring wears the accent while the picture is on, so the strip has to
                // be asked again - the commands on it have not changed, only how one of
                // them is drawn. See WP81SecondaryBar.setMode.
                shell.refreshNavMode()
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
        // The search key is lent to the program in front, so it has to be asked again
        // whenever a different one is - which is not the same moment as the count changing:
        // a program brought forward over another changes who is being asked without
        // changing how many there are.
        floatingWindowManager.onFrontWindowChanged = { refreshWP81SearchOffer() }
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
                    }.takeIf { wp81RestorableTiles().isNotEmpty() },
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
        val params = container.layoutParams as RelativeLayout.LayoutParams
        params.topMargin = wp81StatusBarInsetPx()
        params.bottomMargin = wp81NavBarInsetPx()
        container.layoutParams = params
    }

    /**
     * How much room the navigation keys are taking at the foot of the screen.
     *
     * The shell's own answer once it exists - see [rocks.gorjan.gokixp.wp81.WP81Shell.navBarInsetPx] -
     * and the same sum read off the setting before it does, because the window container
     * is rebased and the toast is lifted while the shell is still being built.
     */
    private fun wp81NavBarInsetPx(): Int =
        if (themeManager.getWP81HideNavBar()) 0
        else (rocks.gorjan.gokixp.wp81.WP81NavBar.HEIGHT_DP *
            resources.displayMetrics.density).toInt()

    /**
     * How much room the shell's status strip is taking at the top, which is none when the
     * user has turned it off.
     *
     * The mirror of [wp81NavBarInsetPx], and used for the same one thing: the container
     * windowed programs are drawn in is a sibling of the shell rather than a child, so it
     * is laid out against these two bands by hand. A status bar that vanished the moment
     * anything opened over it would not be a status bar - the phone's was above every
     * program on it. See WP81StatusBar.
     */
    private fun wp81StatusBarInsetPx(): Int =
        if (!themeManager.getWP81ActionCenter()) 0
        // Asked of the shell, which is the only thing that knows how far the camera has
        // pushed it down; the constant alone was right until the strip could grow.
        else wp81Shell?.statusBarInsetPx
            ?: (rocks.gorjan.gokixp.wp81.WP81StatusBar.HEIGHT_DP *
                resources.displayMetrics.density).toInt()

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
        // A missed call arriving or being dismissed decides whether the Phone tile wears
        // its handset or the mark for a call that went unanswered, and a text does the
        // same to Messaging. The words underneath arrive with setNotifications below; the
        // *mark* is settled when the wall is built and has to be repainted by hand.
        //
        // Only when the answer has actually changed: this pass runs every two seconds, and
        // handing every tile a freshly rasterised glyph on each of them is work for a
        // picture that is the same picture.
        val missed = NotificationListenerService.missedCalls().isNotEmpty()
        val texts = NotificationListenerService.messages().isNotEmpty()
        if (missed != wp81MissedCalls || texts != wp81Messages) {
            wp81MissedCalls = missed
            wp81Messages = texts
            for (start in wp81TileSurfaces()) {
                start.setGlyph("system.phone", wp81GlyphOf(
                    if (missed) R.drawable.wp81_glyph_missed_call
                    else R.drawable.wp81_glyph_phone))
                start.setGlyph("system.messaging", wp81GlyphOf(
                    if (texts) R.drawable.wp81_glyph_message
                    else R.drawable.wp81_glyph_message_smiley))
            }
        }
        shell.startScreen.setNotifications { tile -> wp81NotificationsFor(tile) }
        // The folder page shows tiles too, so it gets the same treatment while it is open.
        shell.folderPage.setNotifications { tile -> wp81NotificationsFor(tile) }
        // A folder's preview carries the same information as a dot, one app at a time, so
        // it is refreshed on the same pass.
        shell.startScreen.setFolderPreviews { tile -> wp81FolderPreviewFor(tile) }
        shell.folderPage.setFolderPreviews { tile -> wp81FolderPreviewFor(tile) }
        // The Action Center is reading the same notifications the tiles are, so it is fed
        // from the same pass. Both calls are cheap while it is away: the list is kept and
        // not drawn, and the signal, the battery and the four quick actions are not read
        // at all. See WP81ActionCenter.setNotifications.
        shell.actionCenter.setNotifications(NotificationListenerService.shade())
        shell.actionCenter.refreshChrome()
        // The strip is on screen whether or not the panel is, so it is kept current on
        // every pass. It throttles its own expensive readings - see WP81StatusBar.refresh.
        shell.statusBar.refresh()
        // Only while it is down: this is a read out of Settings.Secure, and the answer can
        // only have changed by the user leaving to change it - which they can do from the
        // panel's own empty page and come straight back to.
        if (shell.actionCenter.isOpen()) {
            shell.actionCenter.setNotificationAccess(isNotificationListenerEnabled())
        }
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
    /**
     * Every surface with tiles on it: the wall, and the page a nested folder opens.
     *
     * A program's live tile is live wherever it is pinned, so live content goes to all of
     * them. A folder opened into the wall is not a surface of its own - its band is a
     * child of the wall's grid, and everything handed out by kind reaches it already.
     */
    private fun wp81TileSurfaces(): List<rocks.gorjan.gokixp.wp81.StartScreenView> =
        wp81Shell?.let { listOf(it.startScreen, it.folderPage.contents) }.orEmpty()

    /**
     * Everything a live tile turns through, asked for again.
     *
     * The four runs that arrive by kind rather than being read off the tile: what the sky
     * is doing, the headlines, the camera roll and the address book. Run whenever a set of
     * tiles appears that was not there when they were last handed out - a folder opening
     * into the wall, a folder page turning in - since those tiles are as live as the ones
     * they were filed away from.
     */
    private fun refreshWP81LiveRuns() {
        refreshWP81News()
        refreshWP81Photos()
        refreshWP81People()
        refreshWP81Weather()
        refreshWP81Battery()
    }

    private fun refreshWP81Media() {
        val shell = wp81Shell ?: return
        val sessions = wp81MediaSessions?.active().orEmpty()
        val lookup: (rocks.gorjan.gokixp.wp81.Tile) -> rocks.gorjan.gokixp.wp81.MediaSessions.Info? =
            { tile -> sessions[wp81MediaPackageFor(tile)] }
        shell.startScreen.setMedia(lookup)
        shell.folderPage.contents.setMedia(lookup)
        // The Action Center draws whichever of these is playing as a player of its own,
        // and hides that app's notification while it does. Every session is handed over
        // rather than one: which to draw is the panel's own decision, and it keeps the one
        // it is already showing where two are alive at once.
        shell.actionCenter.setMedia(sessions.values)

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
        rocks.gorjan.gokixp.wp81.NewsFeed(
            onUpdated = { refreshWP81News() },
            // An enabled id may name one of the built-in feeds or one the user added; the
            // settings are the only place that knows about both.
            sourceById = { id -> themeManager.getWP81NewsSource(id) }
        )
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
        for (start in wp81TileSurfaces()) {
            // The tiles are rebuilt often; the loader is theirs for as long as they live.
            // One loader serves a whole surface, so it answers for both kinds of picture:
            // a story's, fetched over the network, and one of the user's own, read out of
            // MediaStore.
            start.setBackdropLoader { source, onReady ->
                if (source.startsWith("content:")) {
                    rocks.gorjan.gokixp.wp81.PhotoFeed.load(this, source, onReady)
                } else {
                    rocks.gorjan.gokixp.wp81.NewsImages.load(source, onReady)
                }
            }
            start.setLiveWidgetRotation(
                rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS,
                faces.ifEmpty { waiting },
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.STORY
            )
        }
    }

    /**
     * The story a News tile has on its face this moment, if it has one.
     *
     * The tile turns through [NewsFeed.stories] in order and knows how far along it is, so
     * the face is a lookup rather than anything the tile has to be asked to remember. Null
     * where it is not pointing at a story at all: while it is still showing the line it
     * puts up in place of one, and while it is resting on its icon between two of them -
     * see Tile.Kind.restsOnIcon. The reader then opens at the top of the feed, which is
     * what a tap on a tile showing nothing in particular is asking for.
     *
     * Which tile is asked matters now that the News tile is the News program's, and a
     * program's tile can be pinned twice: two of them turn independently, and the one that
     * was tapped is the one whose story the reader should open on.
     */
    private fun wp81NewsStoryOnTile(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): rocks.gorjan.gokixp.wp81.NewsStory? {
        // Whichever surface holds it answers with where it has got to; the others do not
        // have it and do not answer at all, so a tile nobody can find falls back to the
        // first story, which is what such a tile should open on anyway.
        val face = wp81TileSurfaces().mapNotNull { it.rotationIndexOf(tile.id) }.maxOrNull() ?: 0
        return wp81NewsFeed.stories().getOrNull(face)
    }

    /** Reads the feeds, if the tile that shows them is on Start. */
    private fun refreshWP81NewsFeeds(force: Boolean = false) {
        if (!wp81HasNewsTile()) return
        wp81NewsFeed.refreshIfStale(themeManager.getWP81NewsFeeds().toList().sorted(), force)
    }

    private fun wp81HasNewsTile(): Boolean = wp81HasProgramTile(rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS)

    /**
     * Whether a program's live tile is anywhere in the arrangement.
     *
     * The icon list rather than the wall's own tiles, because a tile filed in a folder is
     * as live as one out on the wall and is not in that list - and a feed nobody reads
     * because the tile showing it happens to be in a folder is a tile that opens blank.
     */
    private fun wp81HasProgramTile(kind: rocks.gorjan.gokixp.wp81.Tile.Kind): Boolean =
        desktopIcons.any { wp81KindFor(it) == kind }

    // ---------------------------------------------------------------- photos

    /** The camera roll the Photos tile is turning through, newest first. */
    private var wp81Photos: List<rocks.gorjan.gokixp.wp81.PhotoFeed.Shot> = emptyList()

    /** When that was last read, so new pictures arrive without re-reading on every tick. */
    private var wp81PhotosReadAt = 0L
    private var wp81PhotosLoading = false

    private fun wp81HasPhotosTile(): Boolean = wp81HasProgramTile(rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS)

    /**
     * Hands the Photos tile the run of pictures it turns through.
     *
     * Three states, and the tile says which one it is in: no permission yet, in which case
     * it is an invitation rather than a slideshow; permission but nothing read back; and
     * the pictures themselves, which carry no words at all - a photograph on a tile is not
     * captioned, it is looked at.
     *
     * None of the three names the tile. It already carries its program's name along its
     * foot, as every program's live tile does - so a "Photos" set large on the floor of it
     * was the same word twice, one above the other, and the picture mark in the corner was
     * a third. What is left is the one line that says what is actually going on.
     */
    private fun refreshWP81Photos(force: Boolean = false) {
        val shell = wp81Shell ?: return
        if (!wp81HasPhotosTile()) return

        if (!rocks.gorjan.gokixp.wp81.PhotoFeed.hasAccess(this)) {
            wp81Photos = emptyList()
            wp81PhotosReadAt = 0L
            for (start in wp81TileSurfaces()) start.setLiveWidgetRotation(
                rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS,
                listOf(
                    rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                        title = "",
                        detail = "tap to allow"
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
            rocks.gorjan.gokixp.wp81.PhotoFeed.recent(
                this,
                includeVideo = themeManager.getWP81PhotoTileVideos()
            ) { shots ->
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
                title = "",
                detail = if (wp81PhotosLoading) "looking\u2026" else "no pictures yet"
            )
        )
        for (start in wp81TileSurfaces()) start.setLiveWidgetRotation(
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS,
            faces.ifEmpty { waiting },
            rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
        )
    }

    /**
     * Tapping the pictures tile: the permission first, Files after.
     *
     * The first tap is the opt-in, because the shell has nowhere else to ask - and asking
     * on first run, for a tile the user may never have wanted, is how a launcher earns a
     * reputation.
     *
     * Then Files, because this is Files' tile: the roll it turns through is the newest
     * thing on the phone, which is what the program opens on. It used to hand the pictures
     * to Google Photos, or to whatever else would take them, from a tile that belonged to
     * the shell and had no program of its own to be about.
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
        showFilesDialog()
    }

    // ---------------------------------------------------------------- people

    /** The address book the People tile fills itself from: favourites, then the rest. */
    private var wp81People = rocks.gorjan.gokixp.wp81.ContactFeed.Book(emptyList(), emptyList())

    /** When that was last read, so a new contact arrives without re-reading on every tick. */
    private var wp81PeopleReadAt = 0L
    private var wp81PeopleLoading = false

    /**
     * Whether a missed call was showing last time the shade was read.
     *
     * The Phone tile's mark turns over when this changes - see refreshWP81Notifications.
     * It used to be the People tile's, back when People was the phone as well.
     */
    private var wp81MissedCalls = false

    /** And whether a text message was, which is the Messaging tile's mark on the same terms. */
    private var wp81Messages = false

    private fun wp81HasPeopleTile(): Boolean = wp81HasProgramTile(rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE)

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

        // The tile used to step aside here for a missed call or a waiting text, because
        // People was the phone and the messaging app as well and those were the only two
        // things it ever had to announce. They are Phone's and Messaging's now, and each
        // says so on its own tile - see wp81NotificationsFor. What is left on this one is
        // what it was always about: the faces.

        if (!rocks.gorjan.gokixp.wp81.ContactFeed.hasAccess(this)) {
            wp81People = rocks.gorjan.gokixp.wp81.ContactFeed.Book(emptyList(), emptyList())
            wp81PeopleReadAt = 0L
            for (start in wp81TileSurfaces()) {
                start.setPeopleMosaic(emptyList(), emptyList())
                start.setLiveWidgetRotation(
                    rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE,
                    listOf(
                        // No mark in the corner: the tile says what it is in words, and a
                        // silhouette over them is a second way of saying the same thing on
                        // a tile whose whole subject is faces.
                        rocks.gorjan.gokixp.wp81.TileView.LiveFace(
                            title = "People",
                            detail = "tap to allow"
                        )
                    ),
                    rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
                )
            }
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
            for (start in wp81TileSurfaces()) start.setLiveWidgetRotation(
                rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE,
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
        for (start in wp81TileSurfaces()) {
            start.setLiveWidgetRotation(
                rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE,
                emptyList(),
                rocks.gorjan.gokixp.wp81.TileView.LiveStyle.READING
            )
            start.setPeopleMosaic(wp81People.favourites, wp81People.others)
        }
    }

    /**
     * Tapping the People tile: the permission first, the address book after.
     *
     * The first tap is the opt-in, exactly as it is on the Photos tile - the shell has
     * nowhere else to ask, and an address book is not something to demand on first run for
     * a tile the user may never have wanted.
     *
     * Then People, which is the same address book this wall is made of. It used to open
     * whichever dialler the phone had, for want of anything better; a tile that is a wall
     * of faces from *this* book should land inside the app that book belongs to - and the
     * dialler has a tile of its own now.
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
        } else if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER) {
            collectFolderNotifications(tile.id, mutableSetOf())
        } else {
            // By package, so Phone and Messaging get the calls and texts that were left
            // by somebody else. See NotificationListenerService.linesFor.
            NotificationListenerService.linesFor(tile.packageName)
                .map { line ->
                    rocks.gorjan.gokixp.wp81.TileView.Line(line.title, line.text).apply {
                        open = wp81NotificationOpening(tile, line)
                    }
                }
        } + wp81UpdateLine(tile)

    /**
     * What a tap on a tile showing this notification should do, or null for the usual
     * launch.
     *
     * A tile turned over to "Mum: are you coming?" is a tile about that message, and the
     * app's own content intent is what goes to it - the same thing the shade sends when
     * the notification is tapped there. Without this every one of them landed on the app's
     * front page, leaving the user to find the conversation the tile had just shown them.
     *
     * The shell's own tiles follow only the shell's own notifications. Phone and Messaging
     * are programs in here, and a missed call left by whichever dialler the phone is
     * actually using points into *that* app - so a tap on the Phone tile would leave the
     * launcher for somebody else's call log rather than opening the one in here.
     */
    private fun wp81NotificationOpening(
        tile: rocks.gorjan.gokixp.wp81.Tile,
        line: NotificationListenerService.NotificationLine
    ): (() -> Unit)? {
        val opening = line.opening ?: return null
        if (tile.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP && !opening.ours) {
            return null
        }
        return { openNotification(opening) { launchWP81Tile(tile) } }
    }

    /**
     * Brings the Action Center down over the wall, with what is posted right now on it.
     *
     * Handed the list here rather than left to the two-second tick: a panel opened by a
     * gesture should be right by the time it has finished arriving, and the tick may have
     * been anywhere in its cycle when the finger moved.
     *
     * The same knock the push-up at the other end of the wall gives. Both gestures take
     * the wall off an edge and hand the screen to something else, so both should land the
     * same way - and unlike the system shade, which rings its own bell, this one is drawn
     * by the launcher and has to.
     */
    private fun openWP81ActionCenter(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        rocks.gorjan.gokixp.wp81.Haptics.tap(shell.startScreen)
        primeWP81ActionCenter(shell)
        shell.actionCenter.open()
    }

    /**
     * Puts on the panel what is true right now, ahead of it being seen.
     *
     * Both ways in come through here - the pull-down on the wall, which opens it outright,
     * and the drag on the status strip, which carries it out by hand - because in both the
     * panel is about to be looked at and neither can wait for the two-second tick to have
     * come round. Whether the listener is switched on is a Settings.Secure read and is
     * asked here rather than on that tick for the same reason it is only asked while the
     * panel is down: the answer can only change by the user going away to change it.
     */
    private fun primeWP81ActionCenter(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        shell.statusBar.refresh(force = true)
        shell.actionCenter.setNotificationAccess(isNotificationListenerEnabled())
        shell.actionCenter.setNotifications(NotificationListenerService.shade())
        shell.actionCenter.setMedia(wp81MediaSessions?.active()?.values.orEmpty())
    }

    /**
     * What the Action Center's rows and commands actually do.
     *
     * All of it belongs to the host rather than to the panel: the panel draws a list and
     * reports which row was touched, and every one of these answers is something only the
     * activity can give - a PendingIntent sent with the launch grant, the listener's
     * connection, the shell's own settings page.
     */
    private fun wireWP81ActionCenter(shell: rocks.gorjan.gokixp.wp81.WP81Shell) {
        val panel = shell.actionCenter
        // Where the shade would send it, and the app's front page where the notification
        // carries nowhere to go - which is the same fallback a tile's notification gets.
        panel.onOpenNotification = { entry ->
            val opening = entry.opening
            if (opening != null) openNotification(opening) { launchInstalledApp(entry.packageName) }
            else launchInstalledApp(entry.packageName)
        }
        panel.onDismissNotification = { entry ->
            NotificationListenerService.dismiss(entry.key)
        }
        panel.onOpenApp = { packageName -> launchInstalledApp(packageName) }
        // A picked icon is worn here too, and on the status strip the panel feeds. See
        // wp81CustomGlyphFor.
        panel.customGlyph = { packageName -> wp81CustomGlyphFor(packageName) }
        // The mini player at the top of the panel. It reports which app is making the
        // sound and nothing else: the sessions - and the notification access they are read
        // through - are the activity's. See WP81ActionCenter.MiniPlayer.
        panel.onMediaPlayPause = { app -> wp81MediaSessions?.togglePlayPause(app) }
        panel.onMediaNext = { app -> wp81MediaSessions?.next(app) }
        panel.onMediaPrevious = { app -> wp81MediaSessions?.previous(app) }
        panel.onMediaSeek = { app, position -> wp81MediaSessions?.seekTo(app, position) }
        panel.onClearAll = { NotificationListenerService.clearAll() }
        // The shell's settings, not Android's. The phone's own command went to the phone's
        // own settings, and on this phone that page is this shell's.
        panel.onAllSettings = { shell.openSettings() }

        // The strip is the handle the panel is pulled out of, which is how the phone did
        // it: what comes down follows the finger the whole way rather than playing an
        // animation once a threshold has been passed. The strip reports the drag and knows
        // nothing about what is behind it - see WP81StatusBar.onPullStart - so which
        // direction means what is settled here.
        val strip = shell.statusBar
        strip.onPullStart = { travelled ->
            if (travelled > 0f) {
                rocks.gorjan.gokixp.wp81.Haptics.tap(strip)
                primeWP81ActionCenter(shell)
                panel.beginPull()
            } else {
                // Upward, with the panel already down: the strip sits directly above it
                // and is the obvious thing to push it back into. Closed, this is a swipe
                // up at the top of the screen and means nothing.
                panel.close()
            }
        }
        strip.onPullMove = { travelled -> panel.pullTo(travelled) }
        strip.onPullEnd = { travelled -> panel.endPull(travelled) }
        // The panel is worth nothing without the listener, and an empty list is the only
        // symptom of its being off - so the empty page offers the way to switch it on.
        panel.onNotificationAccess = {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Log.w("MainActivity", "No notification access screen on this phone", e)
            }
        }
    }

    /**
     * Opens an installed app by its package name, or does nothing if it has none to open.
     *
     * An app can be uninstalled while a notification it posted is still standing, and a
     * few - widgets, plugins - have no launcher entry at all. Both come back as no intent,
     * which is a row that cannot be followed rather than an error worth showing.
     */
    private fun launchInstalledApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not open $packageName", e)
        }
    }

    /**
     * Sends a notification where it was going, and retires it the way the shade would.
     *
     * The intent belongs to the app that posted it and is sent with that app's identity;
     * all this end has to do is grant the launch, which since Android 14 is the sender's
     * to grant and nobody else's - see [pendingIntentOptions].
     *
     * A notification can be withdrawn between the tile reading it and the user tapping it,
     * which is what [fallback] is for: the app opens on its front page, which is where it
     * would have opened before any of this.
     */
    private fun openNotification(
        opening: NotificationListenerService.Opening,
        fallback: () -> Unit
    ) {
        try {
            opening.intent.send(this, 0, null, null, null, null, pendingIntentOptions())
        } catch (e: android.app.PendingIntent.CanceledException) {
            Log.w("MainActivity", "Notification intent has been withdrawn; opening the app", e)
            fallback()
            return
        }
        // Only where the app asked for it. A notification that stays put in the shade
        // after it is tapped means to stay put - an ongoing download, a running trip -
        // and clearing it off the tile would be clearing it out from under its own app.
        if (opening.autoCancel) NotificationListenerService.dismiss(opening.key)
    }

    /**
     * The permission to start what is on the other end of a notification.
     *
     * From Android 14 a PendingIntent's activity start is the sender's privilege to hand
     * over rather than something the creator carries, and the app that posted the
     * notification is in the background by definition. Without this the tap is swallowed
     * and nothing opens at all.
     */
    private fun pendingIntentOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }


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
            for (line in NotificationListenerService.linesFor(child.packageName)) {
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
        // The one that was underneath goes in first, so the folder opens in the order the
        // two were put together in - and it is the one whose place the folder takes.
        makeWP81Folder(first, onto.size, listOf(first, second))
    }

    /**
     * Makes a folder out of the tile that is selected, where it stands.
     *
     * The app bar's answer to holding one tile over another, which needs no second tile.
     * A folder of one is the start of a folder rather than the whole of it: it opens into
     * the wall as soon as it is made, and the rest goes in either by holding a tile over
     * it or by dragging tiles down into the open band.
     */
    private fun newWP81FolderFrom(tile: rocks.gorjan.gokixp.wp81.Tile) {
        val icon = desktopIcons.firstOrNull { it.id == tile.id } ?: return
        // Nothing already filed: a folder does not go inside a folder, and the command is
        // only offered on the wall. Answered again here because the strip decides what to
        // *show* from the tile on screen, and this decides what to do from the icon behind
        // it - see StartScreenView.editingCanFolder for the other half.
        if (icon.parentFolderId != null) return
        makeWP81Folder(icon, tile.size, listOf(icon))
    }

    /**
     * Puts a new folder in [seat]'s place, files [members] into it in order, and opens it.
     *
     * Shared by the two ways of asking for one, because everything except how many tiles go
     * in is the same. The folder takes the seat's slot and the size it is asked for, so it
     * appears where the user was looking rather than at the end of the wall - and it opens
     * straight away, because a folder nobody can see the inside of is indistinguishable
     * from a tile that has vanished.
     */
    private fun makeWP81Folder(
        seat: DesktopIcon,
        size: rocks.gorjan.gokixp.wp81.TileSize,
        members: List<DesktopIcon>
    ) {
        if (members.isEmpty() || members.any { it.type == IconType.FOLDER }) return

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
                tileSize = size.name,
                tileIndex = seat.tileIndex,
                tileSizeLandscape = size.name,
                tileIndexLandscape = seat.tileIndexLandscape
            )
        )

        members.forEachIndexed { i, icon ->
            icon.parentFolderId = folderId
            icon.wp81TileIndex = i
        }

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

    /**
     * Pushes the tiles that are holding their picture back onto the wall, and onto an open
     * folder page.
     *
     * The walls rather than the tiles, for the reason [applyWP81DimAllTiles] does it that
     * way: a tile rebuilt afterwards has to be born knowing. See
     * StartScreenView.picturesHidden.
     */
    private fun applyWP81TilePictures() {
        val shell = wp81Shell ?: return
        val hidden = themeManager.getWP81TilesWithoutPicture()
        shell.startScreen.picturesHidden = hidden
        shell.folderPage.contents.picturesHidden = hidden
    }

    /**
     * Pushes the dim-every-tile setting onto the wall, and onto an open folder page.
     *
     * The walls rather than the tiles, which hand it down: a tile built after the switch
     * was thrown has to be born knowing it. See TileView.dimAllTiles.
     */
    private fun applyWP81DimAllTiles() {
        val shell = wp81Shell ?: return
        val dim = themeManager.getWP81DimAllTiles()
        val amount = themeManager.getWP81DimAmount()
        shell.startScreen.dimAllTiles = dim
        shell.startScreen.dimAmount = amount
        shell.folderPage.contents.dimAllTiles = dim
        shell.folderPage.contents.dimAmount = amount
    }

    private fun refreshWP81Tiles() {
        val shell = wp81Shell ?: return
        wp81TileHost.refreshColors()
        // Set before the tiles are built, so each one is born knowing which mark to wear.
        shell.startScreen.countsEnabled = themeManager.getWP81TileCounts()
        shell.startScreen.tileColorsHidden = themeManager.getWP81HideTileColors()
        shell.startScreen.dimAllTiles = themeManager.getWP81DimAllTiles()
        shell.startScreen.dimAmount = themeManager.getWP81DimAmount()
        shell.startScreen.picturesHidden = themeManager.getWP81TilesWithoutPicture()
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
        refreshWP81Battery()
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
                for (start in wp81TileSurfaces()) start.refreshLiveWidgets()
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
     * The reverse of a live widget: the same reading told another way, or more of it.
     *
     * Returning null leaves a widget one-sided, and turning it over spins it back to
     * itself rather than doing nothing.
     */
    private fun wp81LiveWidgetBack(tile: rocks.gorjan.gokixp.wp81.Tile): rocks.gorjan.gokixp.wp81.TileView.Reading? {
        val locale = java.util.Locale.getDefault()
        val now = java.util.Date()
        return when (tile.kind) {
            // One-sided. The date sits over the time on the front, so there is nothing
            // held back to turn over to - and a clock that has to be waited on to say the
            // time again is a clock that is harder to read than a watch.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK -> null

            // The weather turns through three faces of its own; it has no reverse.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> null

            // One-sided, like the index. What a calendar tile is for is the day you
            // are standing in - the date, and the next thing on it - and a face that
            // turns over to another day makes the reader wait to find out which day the
            // number they are looking at belongs to.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> null

            else -> null
        }
    }


    /**
     * Hands every weather tile what it shows.
     *
     * One reading, to all of them at once, whatever size they are: where it is, what the
     * sky is doing, the temperature and the day's range. What a given tile has the room
     * for is the face's own decision - see WeatherFaceView - so a wide one on the wall and
     * a small one further down show the same reading cut to fit rather than two different
     * things.
     */
    private fun refreshWP81Weather() {
        // First, and outside everything below it. What follows gives up as soon as it
        // finds the Weather tile pinned nowhere at all, and somebody who has taken the
        // tile off has said nothing about wanting no warning when it is about to rain.
        considerRainNotification()
        if (!wp81HasProgramTile(rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER)) return
        val face = wp81TileHost.weatherFace()
        for (start in wp81TileSurfaces()) start.setWeatherFace(face)
    }

    /**
     * Hands every battery tile the charge it draws.
     *
     * Cheap enough to run on the tick and on every battery broadcast alike: it is one read
     * of a sticky intent the platform keeps up to date anyway, plus a line written to the
     * record when the level has actually moved. See BatteryStore.
     *
     * Guarded by the tile being pinned, like the rest of them - with one deliberate
     * exception below, in [onBatteryChanged], where the record is kept whether or not
     * anybody is showing it.
     */
    private fun refreshWP81Battery() {
        if (!wp81HasProgramTile(rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_BATTERY)) return
        val face = wp81TileHost.batteryFace()
        for (start in wp81TileSurfaces()) start.setBatteryFace(face)
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
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK ->
            R.drawable.wp81_glyph_clock.takeIf { wp81AlarmDueWithinDay() }

        // The calendar carries the mark only when there is no clock to carry it. Both
        // tiles are about the day ahead, so both are fair places to say an alarm is set -
        // but saying it twice on one wall reads as two alarms rather than one.
        rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR ->
            R.drawable.wp81_glyph_clock.takeIf { !wp81HasClockTile() && wp81AlarmDueWithinDay() }

        else -> null
    }

    /** Whether a clock tile is on the wall, and so is the one to wear the alarm mark. */
    private fun wp81HasClockTile(): Boolean =
        wp81Shell?.startScreen?.tiles()?.any {
            it.kind == rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK
        } == true

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
     * The mark a tile wears while something of its own is waiting in the shade.
     *
     * Only ever on screen while there is something behind it, so it says what is waiting
     * rather than naming the app: a handset with an arrow on Phone for a call that went
     * unanswered, a speech bubble on Messaging for a text that arrived. Their own icons
     * here would be the tile introducing itself at the one moment it has something else
     * to say.
     *
     * Both of these used to be one tile's business, because People was all three programs.
     * See wp81NotificationsFor, which split the words the same way.
     */
    private fun wp81WaitingMarkFor(packageName: String): Int? = when (packageName) {
        "system.phone" ->
            R.drawable.wp81_glyph_missed_call
                .takeIf { NotificationListenerService.missedCalls().isNotEmpty() }

        "system.messaging" ->
            R.drawable.wp81_glyph_message
                .takeIf { NotificationListenerService.messages().isNotEmpty() }

        else -> null
    }

    /** One of this shell's own drawables, as a tile glyph. */
    private fun wp81GlyphOf(
        res: Int
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? {
        val drawable = androidx.appcompat.content.res.AppCompatResources
            .getDrawable(this, res) ?: return null
        return rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome(
            drawable,
            wp81IconProvider.ratioFor("res:$res", drawable),
            wp81IconProvider.inkFor("res:$res", drawable)
        )
    }

    /**
     * The icon the user picked for [packageName], or null where they have not picked one.
     *
     * One answer for every place an app shows its icon - its tile, its row in the app list,
     * and its marks in the Action Center and the status strip - so a pick made on any of
     * them is worn on all of them. Asked from the list's worker thread as well as the main
     * one, which the store and the provider's caches are both built for.
     *
     * The mapping is checked again after the load because a file that no longer reads is
     * dropped by the store on the way, and what comes back then is the app's own icon -
     * not something to put on a square as though it had been chosen.
     *
     * A glyph from the phone's own set is handed out as flat artwork, because that is what
     * it is: white, and meant to take the ink of whatever it is drawn on. On an accent
     * square that ink is white anyway, so a tile looks exactly as it did; in the status
     * strip it is the strip's own, which is what keeps it from vanishing on the Light theme.
     * A picture is a picture, and is never tinted.
     */
    private fun wp81CustomGlyphFor(
        packageName: String
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? {
        if (!iconStore.has(packageName)) return null
        val drawable = getAppIcon(packageName)?.takeIf { iconStore.has(packageName) }
            ?: return null
        val ratio = wp81IconProvider.ratioFor("custom:$packageName", drawable)
        val ink = wp81IconProvider.inkFor("custom:$packageName", drawable)
        return if (iconStore.isGlyph(packageName)) {
            rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.Monochrome(drawable, ratio, ink)
        } else {
            rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph.FullColor(
                drawable, ratio, chosen = true, ink = ink)
        }
    }

    /** Resolves the art for one tile: fixed glyph for built-ins, provider for real apps. */
    private fun wp81GlyphFor(
        tile: rocks.gorjan.gokixp.wp81.Tile
    ): rocks.gorjan.gokixp.wp81.MonochromeIconProvider.Glyph? {
        // An icon the user chose outranks everything derived - the app's themed monochrome
        // layer, its notification silhouette, and the built-in glyphs for system tiles.
        // Without this check the provider preferred an app's Android 13 themed icon, and a
        // custom icon simply never appeared for any app that ships one.
        wp81CustomGlyphFor(tile.packageName)?.let { return it }

        val fixed = when (tile.kind) {
            rocks.gorjan.gokixp.wp81.Tile.Kind.FOLDER -> R.drawable.wp81_glyph_folder
            rocks.gorjan.gokixp.wp81.Tile.Kind.MY_COMPUTER -> R.drawable.wp81_glyph_computer
            rocks.gorjan.gokixp.wp81.Tile.Kind.RECYCLE_BIN -> R.drawable.wp81_glyph_recycle
            rocks.gorjan.gokixp.wp81.Tile.Kind.URL_SHORTCUT -> R.drawable.wp81_glyph_ie
            rocks.gorjan.gokixp.wp81.Tile.Kind.SYSTEM_APP ->
                // Phone and Messaging step aside for what is waiting; every other program
                // of the shell's own wears its mark whatever is in the shade.
                wp81WaitingMarkFor(tile.packageName)
                    ?: wp81SystemGlyphs[tile.packageName]
                    ?: R.drawable.wp81_glyph_computer
            rocks.gorjan.gokixp.wp81.Tile.Kind.APP -> null
            rocks.gorjan.gokixp.wp81.Tile.Kind.SETTINGS -> R.drawable.wp81_glyph_settings
            // A live tile draws its content instead of a mark and hides the mark while it
            // has any, so this is for the moments it has none: a forecast not fetched yet,
            // and the mini tiles a folder shows of what is filed inside it. The mark is
            // then the program's own, exactly as its row in the app list wears it.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS,
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS,
            // The battery's face is up at every footprint, the 1x1 included, so this is
            // for the one moment it has none: a phone that will not say what the charge
            // is. Which is also the tile a folder draws of it while it is filed inside.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_BATTERY -> wp81SystemGlyphs[tile.packageName]
            // The shell's own has no program behind it to take a mark from, so it is
            // given one of this shell's own. It shows wherever any other widget's icon
            // does: before the first reading is cached, and on the 1x1 - see
            // TileView.showsLive, where every widget stands down to its mark.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> R.drawable.wp81_glyph_calendar
            // People's mark, for the moments the mosaic has no faces to draw - the book
            // unread, or the permission never given.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE ->
                wp81SystemGlyphs[tile.packageName]
        }
        if (fixed != null) return wp81GlyphOf(fixed)
        return wp81IconProvider.glyphFor(
            tile.packageName, getAppIcon(tile.packageName), isTile = true)
    }

    // ---------------------------------------------------------------- task switcher


    // Both overloads: the one-argument form delegates to the other on current Android,
    // but noting the same package twice costs nothing and this does not depend on it.
    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
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
            // Alarms, which is whose tile this is. It went to the phone's own clock app
            // while the tile was the shell's and had no program behind it to open.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CLOCK -> launchSystemApp(tile.packageName)
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_CALENDAR -> openCalendarApp()
            // The forecast, not another refresh. A tile shows one reading at a time and
            // tapping it asks for the rest of it - the same relation the News tile has to
            // the reader it opens.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_WEATHER -> showWeatherDialog()
            // The reader, not the story itself: a tile shows one headline at a time and
            // tapping it is a request for the rest of them, with the story on the face one
            // tap further in. But the reader opens *on* that story, marked - the tile was
            // pointing at something, and a page that landed at the top would lose it.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_NEWS ->
                showNewsDialog(wp81NewsStoryOnTile(tile))

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PHOTOS -> openWP81Photos()

            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_PEOPLE -> openWP81People()

            // The charge opened out: the tile says how full and the program says what
            // happened to get there and what spent it. The same relation the weather tile
            // has to the forecast behind it.
            rocks.gorjan.gokixp.wp81.Tile.Kind.LIVE_BATTERY -> showBatteryDialog()

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

        // Pinning is offered whether or not there is a tile already: a second one is a
        // thing the user can now ask for, and the menu is where they ask. Unpinning is
        // offered as well as it, not instead of it, and takes every tile this app has on
        // Start - the ones inside folders are the folder's to remove. A single tile out of
        // several would be an app that is still pinned after being unpinned.
        val pinned = desktopIcons.filter {
            it.packageName == app.packageName && it.parentFolderId == null
        }
        items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("pin to start") { pinWP81Tile(app) })
        if (pinned.isNotEmpty()) {
            items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("unpin from start") {
                removeIcons(pinned.map { it.id }.toSet())
                saveDesktopIcons()
                refreshWP81Tiles()
                showNotification("Unpinned", app.name)
            })
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
        // A pack the user already has on is a set of icons they chose, so it is worth
        // offering as a source here even for an app the pack has no artwork for - which is
        // the case somebody reaches this page over in the first place.
        picker.setIconPackAvailable(wp81IconPack != null)
        picker.onIconPack = { showPack ->
            picker.clearChoices()
            if (showPack) loadWP81IconPackChoices(picker) else loadWP81IconChoices(picker)
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
        // The square that was tapped to get here now wears what came back, so the strip
        // shows what Start is wearing rather than losing the answer among the bundled set.
        // The page is told the choice as well: it is open behind the picker and was never
        // tapped, so without this the picture only showed on the next visit to settings.
        wp81Shell?.settingsPage?.setSelectedBackground(stored)
        refreshWP81CustomBackground()
        // Nothing else is asked. Browsing for a picture is a request to dress Start, and
        // the command list used to open over the answer offering to dress the phone's own
        // walls as well - a second question nobody had asked, standing in the way of
        // looking at the thing they had just chosen. It is still one hold away, on the
        // square the picture now occupies, which is where every other wallpaper keeps it.
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
        // The phone's own set, and only it: the desktop program icons are drawn for a
        // desktop, in colour, with shadows, and none of them is what a Windows Phone tile
        // should be offered.
        Thread {
            val names = try {
                assets.list(WP81_ICON_FOLDER)?.sorted().orEmpty()
            } catch (e: Exception) {
                Log.w("MainActivity", "WP8.1: cannot list $WP81_ICON_FOLDER", e)
                emptyList()
            }
            val batch = mutableListOf<rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice>()
            for (name in names) {
                if (!name.matches(".*\\.(svg|png|jpg|jpeg|webp)$".toRegex(RegexOption.IGNORE_CASE))) continue
                val path = "$WP81_ICON_FOLDER/$name"
                val drawable = try {
                    iconStore.fromPath(path)
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
        }.start()
    }

    // ---- icon packs -------------------------------------------------------------------

    /**
     * The icon pack in force, opened once and held for as long as it is the answer.
     *
     * Held rather than opened per lookup because opening one means asking the package
     * manager for another app's [android.content.res.Resources] and parsing its
     * `appfilter.xml` - a few hundred to a few thousand entries. Once per pack, not once
     * per icon. See [IconPack].
     */
    private var wp81IconPack: IconPack? = null

    /**
     * Puts the saved icon pack in force, or takes one out of force.
     *
     * Sets the two places a pack is consulted and nothing else - see [commitIconPack] for
     * the repaint - because this also runs on the way in, before there is anything on
     * screen to repaint.
     *
     * A pack named in preferences that the phone no longer has is forgotten here rather
     * than carried: an uninstalled pack cannot be opened, so every lookup would fall
     * through to the app's own icon anyway, and the settings row would go on naming a pack
     * that is not there.
     */
    private fun applyIconPack(reopen: Boolean = false) {
        val chosen = themeManager.getWP81IconPack()
        // The one already open is kept unless it is the wrong one or the caller says it is
        // stale, because opening a pack means parsing a few thousand mapping entries - and
        // because handing the store a fresh instance is what empties its bitmap cache. A
        // change of accent must not cost every app icon the launcher has already rendered.
        val pack = wp81IconPack?.takeIf { !reopen && it.packageName == chosen }
            ?: chosen?.let { IconPack.open(this, it) }
        if (chosen != null && pack == null) {
            Log.d("MainActivity", "Icon pack $chosen is gone; back to the apps' own icons")
            themeManager.setWP81IconPack(null)
        }

        wp81IconPack = pack
        // Empties the rendered-bitmap cache by itself when the answer actually changed.
        iconStore.pack = pack

        // The app list is always dressed by the pack; the wall only when the user has said
        // so. Both are the same resolution - a list row and a tile ask the provider the same
        // question - so which surface is asking is what the flag below settles, rather than
        // whether the pack is consulted at all. See MonochromeIconProvider.packOnTiles.
        wp81IconProvider.packOnTiles = themeManager.getWP81IconPackOnTiles()
        wp81IconProvider.packGlyph =
            if (pack == null) null
            else { packageName ->
                // Nothing where the pack has no word about this app, so the themed
                // monochrome layer and the notification silhouette still get their turn -
                // and nothing where the user picked an icon for it by hand, which is a
                // narrower answer than a pack and outranks it everywhere else too.
                if (!isIconPackable(packageName) ||
                    iconStore.has(packageName) ||
                    !pack.covers(packageName)
                ) null
                // Asked of the store rather than the pack so the composite is cached: this
                // is called for every tile on every rebuild, and dressing an icon means
                // rendering three layers into a 288px bitmap.
                else iconStore.iconFor(packageName, skipCustom = true)
            }
    }

    /** [applyIconPack], and everything wearing an app icon repainted around it. */
    private fun commitIconPack(reopen: Boolean = false) {
        applyIconPack(reopen)
        cachedAppList = null
        wp81IconProvider.invalidateAll()
        loadInstalledApps()
        refreshWP81Tiles()
        refreshWP81AppList()
    }

    /**
     * The list of packs to choose from, hung off the settings row that was tapped.
     *
     * "none" is first and always there, because getting back to the phone's own icons is
     * the one command on this list that has to be reachable whatever else is installed.
     * The way to the store is last, and is offered even when packs are installed - a
     * search for more of them is a reasonable thing to want from the row that lists them.
     */
    private fun showIconPackChooser(anchorY: Float) {
        if (wp81Shell == null) return
        val current = themeManager.getWP81IconPack()
        // Off the main thread: finding the packs means asking the package manager, once per
        // theme action, for every app on the phone that answers it.
        Thread {
            val installed = IconPack.installed(this)
            runOnUiThread {
                val shell = wp81Shell ?: return@runOnUiThread
                val items = mutableListOf<rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item>()
                if (current != null) {
                    items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("none") {
                        chooseIconPack(null)
                    })
                }
                installed.asSequence()
                    .filter { it.packageName != current }
                    .take(ICON_PACK_MENU_MAX)
                    .forEach { pack ->
                        items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item(
                            pack.label.lowercase()) { chooseIconPack(pack.packageName) })
                    }
                items.add(rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("get icon packs") {
                    openIconPackStore()
                })
                shell.contextMenu.show("icon pack", items, anchorY)
            }
        }.start()
    }

    /** Records the chosen pack and repaints everything that was wearing the old answer. */
    private fun chooseIconPack(packageName: String?) {
        themeManager.setWP81IconPack(packageName)
        commitIconPack()
        refreshWP81IconPackRow()
        showNotification(
            "Icon pack",
            if (packageName == null) "Apps are back to their own icons"
            else "Applied to the app list" +
                if (themeManager.getWP81IconPackOnTiles()) " and Start" else ""
        )
    }

    /**
     * Sends the user to the store's own list of icon packs.
     *
     * Left as an ACTION_VIEW so the Play Store app claims it, rather than opened in
     * Internet Explorer: the store is an app the phone has, and a store page rendered in
     * this launcher's browser is a page that cannot install anything.
     */
    private fun openIconPackStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, IconPack.STORE_SEARCH.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.w("MainActivity", "No store to send the user to", e)
            showNotification("Icon pack", "No app store to open")
        }
    }

    /** Tells the settings page which pack is on, and how many there are to choose from. */
    private fun refreshWP81IconPackRow() {
        val shell = wp81Shell ?: return
        val current = themeManager.getWP81IconPack()
        val name = wp81IconPack?.label?.takeIf { current != null }
        // Off the main thread: this queries every installed package for a theme activity.
        Thread {
            val installed = IconPack.installed(this).size
            runOnUiThread {
                shell.settingsPage.setIconPack(
                    name, themeManager.getWP81IconPackOnTiles(), installed)
            }
        }.start()
    }

    /**
     * Refills the icon picker's grid from the pack the user has on.
     *
     * The same batching as the bundled set, and for a stronger reason: a pack routinely
     * ships two thousand drawables, and decoding them before showing any would leave the
     * page blank for as long as it took.
     *
     * Two things the bundled set does not need. The grid holds every choice it is given, so
     * the artwork is scaled down to the size the cells actually draw it at - the bundled
     * icons are SVG paths and cost nothing to hold, while a pack's are full-size PNGs and
     * two thousand of them at their own resolution is hundreds of megabytes. And the run is
     * capped at [WP81_ICON_PACK_MAX], because a grid of two thousand icons with nothing to
     * search it by is not a way of finding one anyway.
     */
    private fun loadWP81IconPackChoices(picker: rocks.gorjan.gokixp.wp81.WP81IconPicker) {
        val pack = wp81IconPack ?: return
        picker.clearChoices()
        Thread {
            val names = try {
                pack.contents()
            } catch (e: Exception) {
                Log.w("MainActivity", "Cannot list ${pack.packageName}'s icons", e)
                emptyList()
            }
            val batch = mutableListOf<rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice>()
            var taken = 0
            fun flush() {
                val chunk = batch.toList()
                batch.clear()
                runOnUiThread { if (picker.isShowing()) picker.addChoices(chunk) }
            }
            for (name in names) {
                val drawable = try {
                    pack.drawableNamed(name)
                } catch (e: Exception) {
                    null
                } ?: continue
                batch.add(rocks.gorjan.gokixp.wp81.WP81IconPicker.Choice(
                    IconPack.pathFor(pack.packageName, name), thumbnailOf(drawable)))
                if (batch.size >= WP81_ICON_BATCH) flush()
                if (++taken >= WP81_ICON_PACK_MAX) break
            }
            if (batch.isNotEmpty()) flush()
        }.start()
    }

    /**
     * [drawable] rendered small enough to keep a grid of them in memory.
     *
     * Only for the picker, and only for a pack's own artwork. What gets *chosen* out of
     * that grid is stored as a path and loaded again at full size when it is drawn, so
     * nothing downstream ever sees this copy. See [IconPack.pathFor].
     */
    private fun thumbnailOf(drawable: Drawable): Drawable {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        val longest = maxOf(w, h)
        if (longest in 1..WP81_ICON_PACK_THUMB_PX) return drawable
        val scale = if (longest > 0) WP81_ICON_PACK_THUMB_PX.toFloat() / longest else 1f
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        return try {
            val bitmap = createBitmap(tw, th)
            drawable.setBounds(0, 0, tw, th)
            drawable.draw(Canvas(bitmap))
            bitmap.toDrawable(resources)
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not scale a pack icon down", e)
            drawable
        }
    }

    /** Commits a chosen icon through the same mappings the desktop themes read. */
    private fun applyWP81CustomIcon(packageName: String, path: String) {
        iconStore.set(packageName, path)
        iconStore.pruneImported()
        invalidateIconCache(packageName)
        // The artwork changed, so its measured proportions have to go too, or the new icon
        // is drawn scaled for the old one.
        wp81IconProvider.invalidate(packageName)
        refreshWP81Tiles()
        // The app list keeps each row's resolved artwork until it is handed a fresh list,
        // so without this the row went on wearing the old icon after the tile changed.
        refreshWP81AppList()
        // And the Action Center keeps its own copy of every app's mark, as does the strip.
        wp81Shell?.actionCenter?.invalidateApps()
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

    /**
     * Human-readable names for the hideable built-ins, for the restore list.
     *
     * One of them. Every other tile that used to be hideable is a program's now, and a
     * program's tile is unpinned rather than hidden - see [unpinOrHideWP81Tile].
     */
    private fun wp81BuiltInLabel(id: String): String = when (id) {
        WP81_WIDGET_CALENDAR -> "Calendar"
        else -> id
    }

    /** Lists what has been hidden, so any of it can be put back. */
    private fun showWP81HiddenTiles() {
        val shell = wp81Shell ?: return
        val hidden = wp81RestorableTiles()
        if (hidden.isEmpty()) return
        val items = hidden.map { id ->
            rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item(wp81BuiltInLabel(id)) { restoreWP81Tile(id) }
        }
        shell.contextMenu.show("hidden tiles", items, shell.height * 0.4f)
    }

    /**
     * What is hidden and can still be put back.
     *
     * A tile the shell no longer provides cannot be restored - there is nothing left to
     * restore it to - so a wall still carrying one is not offered the chance. Air quality
     * is the one that leaves this way, and it leaves from almost every wall there is: the
     * default arrangement hid it, so its id is in most users' hidden sets. Filtered rather
     * than deleted, because the set is the desktop themes' as well as this shell's.
     */
    private fun wp81RestorableTiles(): List<String> =
        themeManager.getWP81HiddenTiles()
            .filterNot { it in rocks.gorjan.gokixp.wp81.WP81TileHost.RETIRED_WIDGETS }
            .sorted()

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
        shell.settingsPage.setKeyboardEnabled(isOwnKeyboardEnabled())
        refreshWP81BackupRows()
        refreshDefaultBrowser = { shell.settingsPage.setDefaultBrowser(isDefaultBrowser()) }
        refreshWP81IconPackRow()
        refreshWP81CustomBackground()
        Thread {
            val items = try {
                val stripPx = wp81StripPreviewPx()
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
        shell.settingsPage.onKeyboard = { openKeyboardSettings() }
        shell.settingsPage.onPhoneSettings = { openPhoneSettings() }
        // Welcome, opened over settings rather than in place of it - it is a program, and
        // it comes up in a window like every other one. Closing it leaves the user where
        // they were, which is the list they opened it from.
        shell.settingsPage.onAbout = { showWelcomeDialogWP81() }
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
        shell.settingsPage.onDimAllTilesChanged = { dim ->
            themeManager.setWP81DimAllTiles(dim)
            applyWP81DimAllTiles()
        }
        shell.settingsPage.onDimAmountChanged = { amount ->
            themeManager.setWP81DimAmount(amount)
            applyWP81DimAllTiles()
        }
        shell.settingsPage.onTileCountsChanged = { enabled ->
            themeManager.setWP81TileCounts(enabled)
            applyWP81TileCounts()
        }
        // The keys' own colour. Applied to the bar rather than through the palette: the
        // accent strip is the navigation bar's alone, and everything else on screen keeps
        // the page's ground. See WP81NavBar.setAccented.
        shell.settingsPage.setAccentNavBar(themeManager.getWP81AccentNavBar())
        shell.settingsPage.onAccentNavBarChanged = { enabled ->
            themeManager.setWP81AccentNavBar(enabled)
            paintWP81NavBar()
        }
        // How much of the screen the shell is given: its own keys, and the phone's bars.
        // Neither is a repaint - both change what is laid out where - so each goes to the
        // applier that owns that, rather than through the palette.
        shell.settingsPage.setScreenControls(
            hideNavBar = themeManager.getWP81HideNavBar(),
            fullscreen = themeManager.getWP81Fullscreen()
        )
        shell.settingsPage.onHideNavBarChanged = { hidden ->
            themeManager.setWP81HideNavBar(hidden)
            applyWP81NavBarVisibility()
        }
        shell.settingsPage.onFullscreenChanged = { enabled ->
            themeManager.setWP81Fullscreen(enabled)
            applyWP81Fullscreen()
        }
        // Nothing to lay out either way - the gesture reads the setting when it fires -
        // but a panel that happens to be down while it is switched off should not stay
        // down, which is the one thing this cannot leave to the next pull.
        shell.settingsPage.setActionCenter(themeManager.getWP81ActionCenter())
        shell.settingsPage.onActionCenterChanged = { enabled ->
            themeManager.setWP81ActionCenter(enabled)
            // The strip goes with it, and takes the panel down with itself if it happens
            // to be open - see WP81Shell.setStatusBarShown.
            applyWP81ActionCenter()
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
        // No launcher-theme row: this app is the Windows Phone shell and nothing else.
        // The desktop themes it used to offer live in the other launcher now, and a row
        // that switched to one of them would be switching to a shell that is not here.
        // What the apps are wearing. The row opens the list of installed packs; the switch
        // under it decides whether the answer reaches Start as well as the app list.
        shell.settingsPage.onIconPack = { anchorY -> showIconPackChooser(anchorY) }
        shell.settingsPage.onIconPackOnTilesChanged = { onTiles ->
            themeManager.setWP81IconPackOnTiles(onTiles)
            commitIconPack()
        }
        // How the app list opens. Held on the shell as well as in preferences because the
        // swipe reads it as the finger moves - see WP81Shell.searchOnAppListOpen - and a
        // gesture is no place to be opening a preference file.
        shell.searchOnAppListOpen = themeManager.getWP81AppListSearchFocus()
        shell.settingsPage.setAppListSearchFocus(shell.searchOnAppListOpen)
        shell.settingsPage.onAppListSearchFocusChanged = { focus ->
            themeManager.setWP81AppListSearchFocus(focus)
            shell.searchOnAppListOpen = focus
        }
        // What the Photos tile turns through. Forced, because the roll is read with the
        // answer in hand rather than filtered afterwards - see PhotoFeed.recent - so the
        // run of pictures the tile is holding was gathered under the old one.
        shell.settingsPage.setPhotoTileVideos(themeManager.getWP81PhotoTileVideos())
        shell.settingsPage.onPhotoTileVideosChanged = { show ->
            themeManager.setWP81PhotoTileVideos(show)
            refreshWP81Photos(force = true)
        }
        shell.settingsPage.onBrowse = {
            setPendingImagePick(PICK_TARGET_WP81_BACKGROUND)
            imagePickerLauncher.launch("image/*")
        }
        shell.settingsPage.onWallpaperLongPress = { source, anchorY ->
            showWP81WallpaperMenu(source, anchorY)
        }
        // Backup. The two dates are read from preferences here and refreshed after every
        // successful transfer; see refreshWP81BackupRows.
        refreshWP81BackupRows()
        shell.settingsPage.onBackUpToDrive = { backUpToDrive() }
        shell.settingsPage.onBackUpToFile = { backUpToFile() }
        shell.settingsPage.onRestoreFromDrive = { restoreFromDrive() }
        shell.settingsPage.onRestoreFromFile = { restoreFromFile() }
    }

    // ---- backup and restore -----------------------------------------------------------

    /** Tells the backup rows when each destination was last written to. */
    private fun refreshWP81BackupRows() {
        wp81Shell?.settingsPage?.setBackupTimes(
            themeManager.getWP81LastDriveBackup(),
            themeManager.getWP81LastFileBackup()
        )
    }

    /**
     * Writes a backup to the user's Google Drive, signing them in first if need be.
     *
     * Signing in is a screen from another app, so it cannot be waited on - the tap that
     * wanted a backup is recorded, and the sign-in launcher picks this up again on the way
     * back. See [driveSignInLauncher].
     */
    private fun backUpToDrive() {
        if (!googleDrive.isSignedIn()) {
            setPendingDriveAction(DRIVE_ACTION_BACKUP)
            signInToDrive()
            return
        }
        showNotification("Google Drive", "Backing up your settings...")
        Thread {
            val failure = try {
                googleDrive.upload(SettingsBackup.snapshot(this))
                null
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not back up to Drive", e)
                e.message ?: "Drive would not take the file"
            }
            runOnUiThread {
                if (failure == null) {
                    themeManager.setWP81LastDriveBackup(System.currentTimeMillis())
                    refreshWP81BackupRows()
                    // Named, because a phone can have more than one Google account on it
                    // and "backed up" is worth nothing to somebody who cannot tell which
                    // of them is holding the copy.
                    val account = googleDrive.accountEmail()
                    showNotification(
                        "Google Drive",
                        if (account != null) "Your settings were backed up to $account"
                        else "Your settings were backed up"
                    )
                } else {
                    showNotification("Google Drive", "Could not back up: $failure")
                }
            }
        }.start()
    }

    /**
     * Asks where to put a backup file, and writes it there.
     *
     * The system's own document picker rather than a path of this app's choosing, so the
     * file lands wherever the user keeps things - their own Drive, a memory card, whatever
     * cloud they have - and the launcher needs no storage permission to put it there.
     */
    private fun backUpToFile() {
        try {
            backupExportLauncher.launch(SettingsBackup.fileName())
        } catch (e: Exception) {
            Log.e("MainActivity", "No document picker to save a backup with", e)
            showNotification("Backup", "This phone has nowhere to save the file")
        }
    }

    /** Fetches the backup on the user's Drive and asks before putting it back. */
    private fun restoreFromDrive() {
        if (!googleDrive.isSignedIn()) {
            setPendingDriveAction(DRIVE_ACTION_RESTORE)
            signInToDrive()
            return
        }
        showNotification("Google Drive", "Looking for your backup...")
        Thread {
            val json = try {
                googleDrive.download()
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not fetch the Drive backup", e)
                runOnUiThread {
                    showNotification(
                        "Google Drive", e.message ?: "The backup could not be fetched")
                }
                return@Thread
            }
            runOnUiThread { confirmRestore(json, "google drive") }
        }.start()
    }

    /** Asks for a backup file, then asks before putting it back. */
    private fun restoreFromFile() {
        try {
            // Not only application/json: a backup that has been through a cloud drive or a
            // messaging app routinely comes back as text/plain or with no type at all, and
            // a picker that hides it is a picker with nothing in it.
            backupImportLauncher.launch(arrayOf(BACKUP_MIME, "text/plain", "*/*"))
        } catch (e: Exception) {
            Log.e("MainActivity", "No document picker to open a backup with", e)
            showNotification("Restore", "This phone has nowhere to open the file from")
        }
    }

    private fun signInToDrive() {
        try {
            driveSignInLauncher.launch(googleDrive.signInIntent())
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not open the Google sign-in screen", e)
            consumePendingDriveAction()
            showNotification("Google Drive", "This phone cannot sign in to Google")
        }
    }

    private fun setPendingDriveAction(action: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_PENDING_DRIVE_ACTION, action)
        }
    }

    /** Reads and clears it, so a stale one cannot claim a later sign-in. */
    private fun consumePendingDriveAction(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val action = prefs.getString(KEY_PENDING_DRIVE_ACTION, null)
        if (action != null) prefs.edit { remove(KEY_PENDING_DRIVE_ACTION) }
        return action
    }

    /**
     * Puts the backup in front of the user, with its date, before anything is replaced.
     *
     * A restore is the one thing on the settings page that cannot be undone by tapping it
     * again: it replaces the Start screen, the colours, the hand-picked icons, the keyboard
     * and every app's settings at once. The date is what makes the question answerable -
     * "restore" is not a decision anybody can make, and "restore the copy from March" is.
     */
    private fun confirmRestore(json: String, source: String) {
        val taken = SettingsBackup.createdAt(json)
        val stamp = if (taken > 0L) {
            android.text.format.DateUtils.formatDateTime(
                this,
                taken,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or
                    android.text.format.DateUtils.FORMAT_SHOW_TIME or
                    android.text.format.DateUtils.FORMAT_ABBREV_ALL
            )
        } else {
            null
        }
        val question =
            (if (stamp != null) "This backup was taken on $stamp. " else "") +
                "Restoring replaces everything this launcher remembers - your Start screen, " +
                "colours, icons, keyboard and app settings - with what is in it."

        val shell = wp81Shell
        if (shell == null) {
            // Nothing to ask with. The user tapped restore and there is no shell to put the
            // question in, which only happens if the launcher is still coming up.
            showNotification("Restore", "Try again once the launcher has finished starting")
            return
        }
        shell.inputDialog.confirm("restore", question, "restore") {
            applyRestoredBackup(json, source)
        }
    }

    /**
     * Puts the settings back, and starts the launcher again on top of them.
     *
     * Everything on screen was built out of the preferences that have just been replaced -
     * the palette, the tile wall, the app list, the icons, the columns, the navigation bar -
     * so the shell is built again rather than patched. [DesktopImport] refreshes in place
     * instead, and can: it brings across the Start screen alone, where this brings across
     * every setting there is.
     *
     * The keyboard's own file is among them, and the keyboard runs in its own process - see
     * WP81Settings.keyboardPrefs. A keyboard that happens to be loaded at this moment is
     * holding the old settings and will write them back over these when it next saves one.
     * Restarting the phone, or simply the next time the keyboard is loaded fresh, settles
     * it; that is the same bargain the desktop import already makes.
     */
    private fun applyRestoredBackup(json: String, source: String) {
        val files = try {
            SettingsBackup.restore(this, json)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not restore the backup", e)
            showNotification("Restore", e.message ?: "That backup could not be read")
            return
        }
        Log.i("MainActivity", "Restored $files preference files from $source")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_RESTORE_ANNOUNCE, source)
        }
        recreate()
    }

    /** Says a restore went through, on the launcher the restore restarted. */
    private fun announceRestoreIfJustDone() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val source = prefs.getString(KEY_RESTORE_ANNOUNCE, null) ?: return
        prefs.edit { remove(KEY_RESTORE_ANNOUNCE) }
        showNotification("Restore", "Your settings came back from $source")
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
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("only launcher") {
                    applyWP81WallpaperToDevice(source, system = true, lock = false)
                },
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("only lock screen") {
                    applyWP81WallpaperToDevice(source, system = false, lock = true)
                },
                rocks.gorjan.gokixp.wp81.WP81ContextMenu.Item("both") {
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
            // The launcher wall now holds a picture somebody chose, so the Light/Dark
            // setting stops painting over it. See matchDeviceWallToTheme.
            if (done && system) themeManager.setKeepsDeviceWallInStep(false)
            runOnUiThread {
                val where = when {
                    system && lock -> "Applied to the launcher and the lock screen"
                    system -> "Applied to the launcher"
                    else -> "Applied to the lock screen"
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
        val target = java.io.File(filesDir, WP81_BACKGROUND_FILE)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() > 0) Uri.fromFile(target).toString() else null
    } catch (e: Exception) {
        Log.e("MainActivity", "WP8.1: could not copy picked background", e)
        null
    }

    /** The picked background's path, or null where the user has never browsed for one. */
    private fun wp81CustomBackgroundPath(): String? =
        java.io.File(filesDir, WP81_BACKGROUND_FILE)
            .takeIf { it.length() > 0 }
            ?.let { Uri.fromFile(it).toString() }

    /** The longest edge a square of the settings strip draws. See WP81SettingsView. */
    private fun wp81StripPreviewPx(): Int =
        (WP81_STRIP_TILE_DP * resources.displayMetrics.density).toInt()

    /**
     * Puts the picture the user browsed for on the settings page's browse square.
     *
     * There is no square of its own for it: one file, replaced by the next pick, is not a
     * library. So it is worn by the square that went and got it - see
     * WP81SettingsView.setCustomBackground - which is also the only place the user can see
     * the wallpaper they chose themselves at all.
     */
    private fun refreshWP81CustomBackground() {
        val shell = wp81Shell ?: return
        val path = wp81CustomBackgroundPath()
        if (path == null) {
            shell.settingsPage.setCustomBackground(null, null)
            return
        }
        val previewPx = wp81StripPreviewPx()
        Thread {
            val preview = loadWallpaperPreview(path, previewPx)
            runOnUiThread { shell.settingsPage.setCustomBackground(path, preview) }
        }.start()
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
            themeManager.getWP81HideTileColors(),
            themeManager.getWP81DimAllTiles(),
            themeManager.getWP81DimAmount()
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
            // A copy, wherever else that app already is. Putting something in a folder
            // used to take it out of everywhere else - the tile on Start went dark the
            // moment it was filed - which made a folder somewhere to hide things rather
            // than somewhere to also keep them. See [pinWP81Tile].
            desktopIcons.add(
                DesktopIcon(
                    name = app.name,
                    packageName = app.packageName,
                    icon = app.icon,
                    x = 0f,
                    y = 0f,
                    id = newIconId(app.packageName),
                    type = IconType.APP,
                    parentFolderId = folderId,
                    tileSize = rocks.gorjan.gokixp.wp81.TileSize.MEDIUM.name
                )
            )
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
        contents.dimAllTiles = themeManager.getWP81DimAllTiles()
        contents.dimAmount = themeManager.getWP81DimAmount()
        contents.picturesHidden = themeManager.getWP81TilesWithoutPicture()
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
        // No band announcing it: the tile leaving the folder is on screen as it happens,
        // and a notice for something the user just watched themselves do is noise.
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
        // The band is a set of tiles that did not exist when the runs were last handed
        // out, and a live tile filed in a folder is live: the forecast and the faces are
        // asked for again so the folder opens onto them rather than onto blank squares.
        refreshWP81LiveRuns()
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
        val start = wp81Shell?.startScreen
        start?.closeFolder(animated = false)
        desktopIcons.remove(folder)
        saveDesktopIcons()
        // Off the wall one tile at a time where it is on the wall, and the rest close
        // around it. A folder is emptied in the middle of a drop, and rebuilding every
        // tile there replaces the one the user has just watched land. The full pass is
        // kept for a folder with no tile of its own on Start - one filed inside another
        // folder - where there is nothing here to take off.
        if (start?.unpinTile(folder.id) != true) refreshWP81Tiles()
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
            tileColors = { child -> colors[child.id] ?: folderColor },
            // The same four the wall is built from: a program's tile is live on this page
            // as well, and a page is where a folder inside a folder is opened.
            liveWidget = { child -> wp81LiveWidgetContent(child) },
            widgetGlyphs = { child -> wp81WidgetGlyphFor(child) },
            widgetBacks = { child -> wp81LiveWidgetBack(child) },
            alarmMarks = { child -> wp81AlarmMarkFor(child) }
        ) { child -> wp81GlyphFor(child) }
        // A folder inside a folder previews its own contents too, and the page is on
        // screen before the next notification pass comes round.
        shell.folderPage.setFolderPreviews { child -> wp81FolderPreviewFor(child) }
        // And the runs that arrive by kind rather than off the tile, for a page of tiles
        // that did not exist when they were last handed out.
        refreshWP81LiveRuns()
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

    /**
     * Pins an app from the app list as a new medium tile.
     *
     * A new one every time it is asked for, whatever else that app already has. This used
     * to refuse when there was a tile on Start and to drag the icon out of its folder when
     * there was one in there - both on the reasoning that an app has a tile, singular. It
     * does not: a tile is a place the user put an app, and a program kept in a folder with
     * its own kind is a fair thing to also want on the wall where it is reached in one tap.
     * See [addAppToOpenWP81Folder], which files a copy on the same terms.
     */
    private fun pinWP81Tile(app: AppInfo) {
        // On the end of both walls: a new tile has no place on either, and the one the
        // phone is not being held in would otherwise put it wherever its name falls.
        val nextIndex = (desktopIcons.mapNotNull { it.tileIndex }.maxOrNull() ?: -1) + 1
        val nextLandscape =
            (desktopIcons.mapNotNull { it.tileIndexLandscape }.maxOrNull() ?: -1) + 1

        val icon = DesktopIcon(
            name = app.name,
            packageName = app.packageName,
            icon = app.icon,
            x = 0f,
            y = 0f,
            id = newIconId(app.packageName),
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
            // Handed the lot, plus which of them are hidden: the page has an eye in its
            // rail that shows the hidden ones on request, so which are left out is the
            // list's to decide from one moment to the next rather than this loader's.
            val hidden = getHiddenApps()
            runOnUiThread { shell.setApps(apps, hidden) }
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

        // A plain ticker; see CalendarWatcher for what this used to borrow.
        wp81CalendarProvider = rocks.gorjan.gokixp.wp81.CalendarWatcher().also {
            it.start {
                // The watcher is the signal, not the content: it marks the time passing,
                // and today is then re-read for what a tile actually shows - a name and a
                // time, rather than "in twenty minutes".
                refreshWP81TodayEvent()
                for (start in wp81TileSurfaces()) start.refreshLiveWidgets()
            }
        }
        val runnable = object : Runnable {
            override fun run() {
                if (wp81Shell != null) {
                    // A live tile shows its content permanently, so it is simply
                    // refreshed in place.
                    // Cheap: it returns without doing anything until the stories are half
                    // an hour old, or the feeds turned on have changed.
                    refreshWP81NewsFeeds()

                    refreshWP81Weather()
                    // The charge arrives by broadcast, which is how the tile keeps up
                    // between ticks - this is for the case the broadcast cannot cover: a
                    // wall rebuilt while the level happened not to move, which leaves a
                    // freshly-built tile with no face until something changes.
                    refreshWP81Battery()
                    // Media sessions announce themselves when they change, but a session
                    // quietly going away is a change nobody reports. Re-read on the tick
                    // so a tile cannot be left holding a track that finished.
                    refreshWP81Media()
                    // Every tile on every surface, the band a folder opened into as well:
                    // the reading, the mark that changes with it, the reverse, and whether
                    // an alarm set or turned off while Start is up is still coming. See
                    // StartScreenView.refreshLiveWidgets.
                    for (start in wp81TileSurfaces()) start.refreshLiveWidgets()
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
        wp81CalendarProvider?.stop()
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
        // Read before the setting is written over, because only a change of *background*
        // is worth dressing the phone's wall for: an accent is twenty taps in a row on
        // that page, and writing a wallpaper on each of them is twenty writes for a
        // surface whose colour has not moved.
        val backgroundChanged = dark != themeManager.isWP81Dark()
        themeManager.setWP81Accent(accent)
        themeManager.setWP81Dark(dark)
        refreshWP81Palette()
        // The keyboard is a process of its own and does not share these preferences, so it
        // is told rather than left to notice. See KeyboardAppearance.
        rocks.gorjan.gokixp.wp81.keyboard.KeyboardAppearance.publish(this, accent, dark)
        if (backgroundChanged) matchDeviceWallToTheme()
    }

    /**
     * Paints the phone's own launcher wall the colour this shell's pages are.
     *
     * There is a wall under this launcher, and normally nobody sees it: Start is drawn
     * over the whole of it. It shows for the moment between a program being asked for and
     * that program having something on screen - which on the Dark theme is black over
     * black and invisible, and on the Light theme is a black flash in front of a white
     * page every single time a program is opened.
     *
     * A colour rather than a picture, and generated rather than shipped: the wall is
     * stretched to fill the screen whatever size it arrives at, so one flat swatch a few
     * dozen pixels across says everything a full-screen PNG of the same colour would and
     * costs nothing to keep.
     *
     * Only while the user has not put a picture there themselves - see
     * [WP81Settings.keepsDeviceWallInStep]. Off the main thread, because writing a
     * wallpaper goes to the system's own store and back.
     */
    private fun matchDeviceWallToTheme() {
        if (!themeManager.keepsDeviceWallInStep()) return
        val colour = if (themeManager.isWP81Dark()) Color.BLACK else Color.WHITE
        Thread {
            val swatch = Bitmap.createBitmap(WALL_SWATCH_PX, WALL_SWATCH_PX, Bitmap.Config.ARGB_8888)
            swatch.eraseColor(colour)
            try {
                android.app.WallpaperManager.getInstance(this).setBitmap(
                    swatch,
                    null,
                    // Nothing to keep: it is one colour, and the setting that decides which
                    // is backed up already.
                    false,
                    android.app.WallpaperManager.FLAG_SYSTEM
                )
            } catch (e: Exception) {
                Log.w("MainActivity", "WP8.1: could not match the device wall to the theme", e)
            }
            swatch.recycle()
        }.start()
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
     * user was standing when they made the gesture. Made while already looking at the
     * launcher, it means Start, which is what [resetWP81ToStart] does - Windows key
     * included: see the nav bar's onStart, which puts programs away first.
     *
     * Made from inside another app it is the way back here, and that lands on Start as
     * well. A program the user left open - People, Zune, the browser - is where they put
     * the shell down before going somewhere else entirely; coming home to it hands them
     * back a page they finished with, and the wall they were asking for is another press
     * away. Home from an app means the tiles.
     *
     * The settings page is the one thing kept, for a reason of its own: half of what is on
     * that page sends the user out to Android's own settings to answer it - the default
     * browser, app access, the notification listener - and the way back from each of those
     * is this gesture. Landing on Start after granting something means walking back into
     * the page to see whether it took. See [refreshWP81SettingsPermissions], which makes
     * sure it says so.
     *
     * A folder page and the app list are not kept either, and need no undoing here: both
     * are already put back to rest when the launcher stops - see onStop.
     */
    private fun wp81ReturningToWhatWasOpen(): Boolean {
        if (!wp81AwayBehindAnotherApp) return false
        return wp81Shell?.where() == rocks.gorjan.gokixp.wp81.WP81Shell.Place.SETTINGS
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

    /**
     * Paints the navigation strip, and the band under it, the same colour.
     *
     * Two views because the shell stops above the system's gesture bar and the band that
     * leaves is outside it - see [setupNavigationBarInsets]. One colour, because the phone
     * has one bar along the bottom: the keys stop where the gesture bar begins, but a
     * strip in the accent with the page's own colour beneath it reads as two.
     *
     * The strip is the one that decides - see [WP81NavBar.groundColour] - so the switch
     * that turns the accent on has one place to say so.
     */
    private fun paintWP81NavBar() {
        val shell = wp81Shell ?: return
        // Hidden keys are not accented keys: with the strip gone the accent would be left
        // as a band of colour along the bottom of the wall with nothing on it, and the
        // band below is the system's gesture bar rather than anything this shell drew.
        // The stored answer is untouched, so the keys come back wearing what they wore.
        shell.navBar.setAccented(
            !themeManager.getWP81HideNavBar() && themeManager.getWP81AccentNavBar()
        )
        val ground = shell.navBar.groundColour()
        findViewById<View>(R.id.gesture_bar_strip)?.setBackgroundColor(ground)
        // The system draws its gesture pill over that band and picks the colour of it from
        // what it has been told the bar is standing on. That used to be the page, which
        // was true while the band was the page's colour; on an accent it is not, and a
        // white pill on Yellow or a black one on Cobalt is the same mistake either way.
        androidx.core.view.WindowCompat
            .getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars =
            androidx.core.graphics.ColorUtils.calculateLuminance(ground) > LIGHT_GROUND
    }

    /**
     * Puts the navigation keys on the screen, or takes them off it.
     *
     * Three things move together, which is why they are in one place: the strip itself
     * and everything the shell laid out above it, the container windowed programs are
     * drawn in - which is the host's and so out of the shell's reach - and the colour of
     * the band at the foot of the screen, since keys that are not there cannot be wearing
     * the accent. See [rocks.gorjan.gokixp.wp81.WP81Shell.setNavBarShown].
     */
    /**
     * Draws the status strip, or takes it away, along with what the pull-down opens.
     *
     * One switch for both because they are one thing: the strip is the Action Center's
     * header, and the panel comes down out of it. Turned off, the shell is what it was
     * before either existed - Android's own status bar above, the wall directly beneath it,
     * and a pull-down that asks the system for its own shade.
     */
    private fun applyWP81ActionCenter() {
        val shell = wp81Shell ?: return
        shell.setStatusBarShown(themeManager.getWP81ActionCenter())
        // The windows programs are drawn in are laid out against the strip by hand, so
        // they have to be told when its height changes. See wp81StatusBarInsetPx.
        rebaseFloatingWindowsForWP81()
        // How far a rounded corner reaches into the strip depends on how far down the
        // strip its writing begins - and with no strip on screen that is nothing at all,
        // so the answer worked out without one is the full radius. Left alone, the strip
        // would come back wearing it and stand well in from both edges. Asking for the
        // insets again works them out against the strip that is now there, which is the
        // same thing full screen does when it is the switch that moved. See
        // [applyWP81CornerInsets].
        findViewById<View>(R.id.root_container)?.let {
            androidx.core.view.ViewCompat.requestApplyInsets(it)
        }
    }

    private fun applyWP81NavBarVisibility() {
        val shell = wp81Shell ?: return
        shell.setNavBarShown(!themeManager.getWP81HideNavBar())
        rebaseFloatingWindowsForWP81()
        paintWP81NavBar()
    }

    /**
     * Hides Android's own bars, or gives them back.
     *
     * Nothing is laid out from here. The shell already stands clear of the status bar and
     * the gesture bar by whatever those bars report as insets - see
     * [setupNavigationBarInsets] - and a hidden bar reports none, so asking for the insets
     * again is the whole of the layout change. The bars themselves come back on a swipe
     * from the edge and leave again by themselves, which is Android's own behaviour for a
     * full screen app and the nearest thing to how Windows Phone hid its status bar.
     *
     * Applied again on every resume: a bar swiped back into view stays until the system
     * decides otherwise, and coming home from another app is not something that puts it
     * away again.
     */
    private fun applyWP81Fullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (themeManager.getWP81Fullscreen()) controller.hide(bars) else controller.show(bars)
        findViewById<View>(R.id.root_container)?.let {
            androidx.core.view.ViewCompat.requestApplyInsets(it)
        }
    }

    /** Repaints the shell after an accent or Light/Dark change, without a recreate. */
    fun refreshWP81Palette() {
        val shell = wp81Shell ?: return
        val palette = rocks.gorjan.gokixp.wp81.WP81Palette.from(themeManager)
        shell.applyPalette(palette)
        applyWP81SystemBarAppearance(palette)
        findViewById<RelativeLayout>(R.id.main_background)?.setBackgroundColor(palette.background)
        findViewById<View>(R.id.root_container)?.setBackgroundColor(palette.background)
        paintWP81NavBar()
        repaintOpenWP81Programs(palette)
        // A rebuild costs a program the pages that were stacked over it - see WP81Program -
        // so the screen the key was being lent to may not be there any more.
        refreshWP81SearchOffer()
    }

    /**
     * Hands the new theme to the programs that are still open behind the shell.
     *
     * Leaving a program minimises its window rather than closing it - see
     * [minimiseWP81Windows] - so one the user has walked away from is still standing
     * there, built out of the palette that was current when it opened, and comes back in
     * that palette however many times the theme has changed since. Which is what a light
     * music app on a dark phone is.
     *
     * Each one rebuilds itself and hands back a view - see [WP81Program] - and the window
     * it lives in is handed the new one. Windows that are not open are not in the list to
     * begin with: a program is only kept hold of while its window is.
     */
    private fun repaintOpenWP81Programs(palette: rocks.gorjan.gokixp.wp81.WP81Palette) {
        for ((identifier, program) in openWP81Programs()) {
            val window = floatingWindowManager.findWindowByIdentifier(identifier) ?: continue
            try {
                window.setContentView(program.applyPalette(palette))
            } catch (e: Exception) {
                // One program that cannot rebuild is one program in the wrong colours; it
                // is not a reason for the other eleven to stay in them, nor to bring the
                // shell down in the middle of a theme change.
                Log.e("MainActivity", "WP8.1: $identifier could not be rebuilt", e)
            }
        }
    }

    /**
     * Hands the shell whatever the program in front means by the search key.
     *
     * The shell cannot work this out for itself: programs are drawn in windows above it,
     * by a container it knows nothing about, so it can neither see which one is on top nor
     * ask it anything. This is the join - the front window's identifier gives the program,
     * and a program that has a search says what its current screen would do with the key.
     * Everything else - a program with no search, a program showing a page that has none,
     * no program at all - comes back null, and the key stays Cortana's.
     *
     * Asked again rather than remembered, because the answer changes underneath: a page
     * opened over the address book withdraws it, and closing that page offers it back. See
     * [rocks.gorjan.gokixp.wp81.WP81Searchable].
     */
    private fun refreshWP81SearchOffer() {
        val shell = wp81Shell ?: return
        val front = floatingWindowManager.getFrontVisibleWindow()?.windowIdentifier
        val program = openWP81Programs().firstOrNull { it.first == front }?.second
        shell.programSearch =
            (program as? rocks.gorjan.gokixp.wp81.WP81Searchable)?.searchAction()
    }

    /**
     * Every program that is open, against the window it is open in.
     *
     * Written out rather than discovered: each program is held in a field of its own,
     * cleared when its window closes, and there is no register to walk. Anything added
     * here has to be added to this list or it is the one that comes back in last week's
     * theme.
     */
    private fun openWP81Programs(): List<Pair<String, rocks.gorjan.gokixp.wp81.WP81Program>> =
        listOfNotNull(
            zuneAppInstance?.let { "system.zune" to it },
            metroIEAppInstance?.let { "system.internet_explorer" to it },
            metroFilesAppInstance?.let { "system.files" to it },
            peopleAppInstance?.let { "system.people" to it },
            phoneAppInstance?.let { "system.phone" to it },
            messagingAppInstance?.let { "system.messaging" to it },
            newsAppInstance?.let { "system.news" to it },
            alarmsAppInstance?.let { "system.alarms" to it },
            weatherAppInstance?.let { "system.weather" to it },
            batteryAppInstance?.let { "system.battery" to it },
            metroNotepadAppInstance?.let { "system.notepad" to it },
            calculatorAppInstance?.let { "system.calculator" to it },
            cortanaAppInstance?.let { "system.cortana" to it },
            metroMinesweeperInstance?.let { "system.minesweeper" to it },
            metroSolitaireInstance?.let { "system.solitare" to it },
            welcomeAppInstance?.let { "system.welcome" to it }
        )


    override fun attachBaseContext(newBase: Context) {
        // Nothing rescales the base font. Windows Classic asked for 5% more and had to do
        // it here, before any resource was resolved; no shell here asks.
        super.attachBaseContext(newBase)
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
     * @param durationMs How long the band holds before retracting on its own; zero or less
     *   leaves it up until it is tapped or flicked away, for something the user is meant to
     *   act on rather than merely notice.
     */
    private fun showNotification(
        title: String,
        description: String,
        durationMs: Long = NOTIFICATION_DURATION_MS,
        onTap: (() -> Unit)? = null
    ) {
        // Windows Phone 8.1 announces things with a band across the top instead of the
        // Vista speech bubble, which is anchored to a system tray this shell does not have.
        wp81Shell?.let { shell ->
            // Clear of whatever strip the program on screen has along the bottom, asked
            // for as the band goes up rather than when that program opened: a link
            // arriving from another app opens the browser before the shell is necessarily
            // there to be told about it, and a lift set then is set on nothing.
            shell.toast.lift = metroIEAppInstance?.barHeight() ?: 0
            shell.toast.show(title, description, durationMs, onTap)
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
     *
     * [manualCheck] is somebody having tapped the version number in Welcome rather than the
     * hourly clock coming round. A check that was asked for says what it found whatever that
     * is - a new version, none, or GitHub not answering - and says it now, skipping the two
     * days of quiet [shouldAnnounceUpdate] keeps for the checks nobody asked for. A tap that
     * answers with nothing reads as a broken tap.
     *
     * [onDone] runs on the main thread once the answer is in, however it went, so the page
     * that asked can stop saying it is asking.
     */
    private fun checkForUpdates(manualCheck: Boolean = false, onDone: (() -> Unit)? = null) {
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
                        // Nothing stable on offer, which to somebody who just asked is the
                        // same answer as no update at all.
                        if (manualCheck) runOnUiThread { showNoUpdateNotice() }
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
                            // And the Welcome program itself, if it happens to be open: it
                            // was built before this answer came back, and would otherwise be
                            // the one page on the phone with nothing to say about the update.
                            welcomeAppInstance?.setUpdateAvailable { openUrlShortcut(downloadUrl) }

                            // The band, though, only every couple of days - see
                            // UPDATE_NOTICE_INTERVAL. The tile and the page above carry it
                            // the rest of the time, and neither of them interrupts anything.
                            // Not short-circuited: the stamp wants writing either way, or
                            // the next hourly check announces what the tap just showed.
                            val due = shouldAnnounceUpdate(latestTag)
                            if (manualCheck || due) {
                                showNotification(
                                    "Windows Update",
                                    "A new version ($latestTag) is available. Tap to download.",
                                    // Stays up until it is dealt with. This one is asking
                                    // for a download rather than reporting something that
                                    // already happened, and a band that retracts on its own
                                    // after seven seconds is the update nobody ever saw -
                                    // the next reminder is two days out. Flick it away to
                                    // decline it.
                                    durationMs = STICKY_NOTIFICATION
                                ) {
                                    if (downloadUrl.isNotEmpty()) {
                                        try {
                                            openUrlShortcut(downloadUrl)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Error opening link", e)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d("MainActivity", "No update available")
                        if (manualCheck) runOnUiThread { showNoUpdateNotice() }
                    }
                } else {
                    connection.disconnect()
                    Log.w("MainActivity", "GitHub API failed: ${connection.responseCode}")
                    if (manualCheck) runOnUiThread { showUpdateCheckFailedNotice() }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking for updates", e)
                if (manualCheck) runOnUiThread { showUpdateCheckFailedNotice() }
            } finally {
                onDone?.let { runOnUiThread(it) }
            }
        }.start()
    }

    /** The band that answers a check nobody needed to make. See [checkForUpdates]. */
    private fun showNoUpdateNotice() =
        showNotification("Windows Update", "You are up to date, no new version available")

    /** The band for a check that never got an answer out of GitHub. See [checkForUpdates]. */
    private fun showUpdateCheckFailedNotice() =
        showNotification("Windows Update", "Could not check for updates right now")

    /**
     * Whether the band should announce [version] now, and notes it down when it says yes.
     *
     * The check runs hourly and an update stays available until it is installed, so the
     * plain answer would be the same notification every hour until the user gave in. Held
     * in preferences rather than a field because a launcher is killed and restarted more
     * often than anything else on the phone, and a field would forget on each of them -
     * which is the hourly nag again, by another route.
     *
     * A version this launcher has not announced before goes out whatever the clock says:
     * the two days of quiet are about a download the user has already been told of, not
     * about the next one.
     */
    private fun shouldAnnounceUpdate(version: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val announcedVersion = prefs.getString(KEY_UPDATE_ANNOUNCED_VERSION, null)
        val announcedAt = prefs.getLong(KEY_UPDATE_ANNOUNCED_AT, 0L)
        val now = System.currentTimeMillis()
        // The last clause is a clock that has been wound back: a stamp sitting in the
        // future would otherwise silence the band until the phone caught up with it.
        val due = announcedVersion != version ||
            now - announcedAt >= UPDATE_NOTICE_INTERVAL ||
            now < announcedAt
        if (!due) return false
        prefs.edit {
            putString(KEY_UPDATE_ANNOUNCED_VERSION, version)
            putLong(KEY_UPDATE_ANNOUNCED_AT, now)
        }
        return true
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


}