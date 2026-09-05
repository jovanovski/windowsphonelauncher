package rocks.gorjan.gokixp.apps.zune

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The launcher's playlists.
 *
 * Winamp wrote this format - a list of names, each holding file paths - and the two players
 * shared it while they shipped together. Winamp is in the desktop launcher now and Zune is
 * the only reader left, but the format and the `winamp_prefs` file name are kept exactly as
 * they were: they are what is already on disk on every phone this has run on.
 *
 * Paths rather than MediaStore ids, because that is what was already written to disk on
 * every phone this has ever run on, and a format change would silently empty everybody's
 * playlists.
 *
 * Last writer wins, as it always did: two players open at once each hold their own copy,
 * and whichever saves last is the one on disk. In practice the other picks the change up
 * the next time it loads.
 */
/**
 * A playlist: a name and the file paths in it.
 *
 * Defined alongside the store now. It lived on Winamp, which wrote the format; the JSON
 * field names are unchanged so lists already saved still load.
 */
data class Playlist(
    val name: String,
    val tracks: MutableList<String> = mutableListOf()
)

object PlaylistStore {

    const val PREFS_NAME = "winamp_prefs"
    const val KEY_PLAYLISTS = "playlists"

    /**
     * The everything-you-own list.
     *
     * Rebuilt from the library on the fly and never written to disk, so it is skipped on
     * save and should not be offered as somewhere to add a song.
     */
    const val ALL_LOCAL_FILES = "Local Tracks"

    /** Every stored playlist, in the order they were saved. Never includes [ALL_LOCAL_FILES]. */
    fun load(context: Context): MutableList<Playlist> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYLISTS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<Playlist>>() {}.type
            val loaded: List<Playlist> = Gson().fromJson(json, type) ?: emptyList()
            loaded.filter { it.name != ALL_LOCAL_FILES }.toMutableList()
        } catch (e: Exception) {
            Log.e("PlaylistStore", "Could not read the saved playlists", e)
            mutableListOf()
        }
    }

    /** Writes [playlists] back, dropping the generated one. */
    fun save(context: Context, playlists: List<Playlist>) {
        val keep = playlists.filter { it.name != ALL_LOCAL_FILES }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PLAYLISTS, Gson().toJson(keep))
            .apply()
        Log.d("PlaylistStore", "Saved ${keep.size} playlists")
    }

    /**
     * Puts one file into a named playlist, if it is not in it already.
     *
     * Reads and writes rather than editing in place, so a player that has been sitting
     * open with a stale copy cannot undo a change made somewhere else by saving over it.
     */
    fun addTrack(context: Context, playlistName: String, path: String): Boolean {
        if (playlistName == ALL_LOCAL_FILES) return false
        val playlists = load(context)
        val target = playlists.firstOrNull { it.name == playlistName } ?: return false
        if (target.tracks.contains(path)) return false
        target.tracks.add(path)
        save(context, playlists)
        return true
    }
}
