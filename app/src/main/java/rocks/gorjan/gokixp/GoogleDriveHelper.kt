package rocks.gorjan.gokixp

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The user's own Google Drive, as somewhere to keep one backup.
 *
 * One file in one folder, replaced each time rather than added to: this is "the backup of
 * this phone", not a history of them. Somebody who wants to keep an old one has the file
 * export for that, and Drive keeps its own versions of anything overwritten.
 *
 * `DRIVE_FILE` is the scope, which is the narrow one: it grants this app the files this
 * app creates and nothing else in the user's Drive. The wide `DRIVE` scope would let a
 * launcher read every document its owner has, to write a settings file.
 *
 * Every call here blocks, which is deliberate - the shell talks to the network on plain
 * threads throughout and this is one more of them. Nothing on this object may be touched
 * from the main thread except [isSignedIn], [accountEmail], [signInIntent] and [connect].
 */
class GoogleDriveHelper(private val context: Context) {

    private var drive: Drive? = null

    /**
     * Whether there is an account with permission to reach Drive.
     *
     * The account and the scope are two questions, and the second is the one that matters:
     * a phone can be signed into Google and have refused this app its Drive, which looks
     * exactly like being signed in until the first upload fails.
     */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))
    }

    /** The address of whoever is signed in, for the settings row to name. */
    fun accountEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email?.takeIf { isSignedIn() }

    fun signInIntent(): Intent = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
    ).signInIntent

    /**
     * Builds the Drive client for a signed-in account.
     *
     * Called both after the sign-in screen and on the way into a transfer: the account
     * outlives this object - it is the phone's, not the launcher's - so a client that was
     * only ever built at sign-in would be null on every launch after the first.
     */
    fun connect(account: GoogleSignInAccount?): Boolean {
        val chosen = account ?: GoogleSignIn.getLastSignedInAccount(context) ?: return false
        val credential = GoogleAccountCredential
            .usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
            .apply { selectedAccount = chosen.account }
        drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()
        Log.d(TAG, "Drive is ready for ${chosen.email}")
        return true
    }

    /** Writes the backup, replacing the one that is there. */
    fun upload(json: String) {
        val service = service()
        val folder = findFolder(service) ?: createFolder(service)
        val existing = findBackup(service, folder)

        val metadata = File().apply {
            name = BACKUP_FILE_NAME
            mimeType = MIME_JSON
            // Only on the way in: Drive rejects an update that tries to re-parent a file.
            if (existing == null) parents = listOf(folder)
        }
        val content = InputStreamContent(MIME_JSON, ByteArrayInputStream(json.toByteArray()))

        if (existing != null) {
            service.files().update(existing, metadata, content).execute()
        } else {
            service.files().create(metadata, content).setFields("id").execute()
        }
        Log.i(TAG, "Backup written to Drive (${json.length} bytes)")
    }

    /** Reads the backup back, or throws saying there isn't one. */
    fun download(): String {
        val service = service()
        val folder = findFolder(service)
            ?: throw IOException("There is no backup on this Google account")
        val fileId = findBackup(service, folder)
            ?: throw IOException("There is no backup on this Google account")

        val bytes = ByteArrayOutputStream()
        service.files().get(fileId).executeMediaAndDownloadTo(bytes)
        return bytes.toString(Charsets.UTF_8.name())
    }

    fun signOut() {
        GoogleSignIn.getClient(
            context, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        ).signOut()
        drive = null
    }

    /** The client, built on demand from the account already on the phone. */
    private fun service(): Drive {
        drive?.let { return it }
        if (!isSignedIn() || !connect(null)) {
            throw IllegalStateException("Not signed in to Google Drive")
        }
        return drive ?: throw IllegalStateException("Not signed in to Google Drive")
    }

    private fun findFolder(service: Drive): String? = service.files().list()
        .setQ(
            "mimeType='application/vnd.google-apps.folder' " +
                "and name='$FOLDER_NAME' and trashed=false"
        )
        .setSpaces("drive")
        .setFields("files(id)")
        .execute()
        .files.firstOrNull()?.id

    private fun createFolder(service: Drive): String = service.files()
        .create(File().apply {
            name = FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        })
        .setFields("id")
        .execute()
        .id

    private fun findBackup(service: Drive, folderId: String): String? = service.files().list()
        .setQ("'$folderId' in parents and name='$BACKUP_FILE_NAME' and trashed=false")
        .setSpaces("drive")
        .setFields("files(id)")
        .execute()
        .files.firstOrNull()?.id

    companion object {
        private const val TAG = "GoogleDriveHelper"

        /** What Drive shows as the app that wrote the file. */
        private const val APP_NAME = "Windows Phone Launcher"

        /**
         * Where the backup sits in the user's Drive.
         *
         * Its own folder, and named for the launcher rather than tucked into Drive's
         * hidden application data: a settings backup the user cannot see, move or hand to
         * their next phone is not much of a backup.
         */
        private const val FOLDER_NAME = "Windows Phone Launcher"
        private const val BACKUP_FILE_NAME = "settings-backup.json"
        private const val MIME_JSON = "application/json"
    }
}
