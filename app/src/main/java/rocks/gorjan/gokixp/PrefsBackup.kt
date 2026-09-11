package rocks.gorjan.gokixp

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlin.math.floor

/**
 * Serialises one SharedPreferences file to JSON and back.
 *
 * Shared by the local-file and Google Drive paths so they can't drift apart - they used to
 * have two different (and differently wrong) ideas about how to turn a JSON number back into
 * a preference.
 *
 * One file, not the app: a backup carries every preference file the app has written, and
 * assembling them is [SettingsBackup]'s. Which is why the working shape here is a map
 * rather than a string - a whole-app backup nests one of these per file, and a string
 * would have to be parsed straight back out of the document it was just put into.
 */
object PrefsBackup {

    private const val TAG = "PrefsBackup"

    /**
     * Reserved key holding the SharedPreferences type of every other entry.
     *
     * JSON has a single number type, so Gson hands every number back as a Double and the
     * restore has no way to tell an Int from a Long from a Float. Guessing is what stored
     * `christmas_lights_margin` as a Float and made getInt throw. Backups written from here
     * carry the real types; ones written before this existed have no [TYPES_KEY] and fall
     * back to [putGuessedNumber].
     */
    private const val TYPES_KEY = "__types__"

    fun toJson(prefs: SharedPreferences): String =
        GsonBuilder().setPrettyPrinting().create().toJson(toMap(prefs))

    /**
     * The file as a JSON-shaped map: every entry, plus the [TYPES_KEY] that says what each one is.
     *
     * [skip] names keys that belong to the phone rather than to its settings and so do
     * not travel - see SettingsBackup.DEVICE_KEYS, the only caller that has any.
     */
    fun toMap(prefs: SharedPreferences, skip: Set<String> = emptySet()): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()
        val types = mutableMapOf<String, String>()

        prefs.all.forEach { (key, value) ->
            if (key == TYPES_KEY || key in skip) return@forEach
            val type = when (value) {
                is Boolean -> "Boolean"
                is Int -> "Int"
                is Long -> "Long"
                is Float -> "Float"
                is String -> "String"
                is Set<*> -> "StringSet"
                else -> {
                    Log.w(TAG, "Skipping $key: unsupported type ${value?.javaClass?.simpleName}")
                    return@forEach
                }
            }
            values[key] = value
            types[key] = type
        }

        values[TYPES_KEY] = types
        return values
    }

    /**
     * Replaces everything in [prefs] with the contents of [json]. Throws if [json] isn't a
     * JSON object, so callers can report a bad backup instead of wiping settings.
     */
    fun restore(prefs: SharedPreferences, json: String) {
        val parsed = Gson().fromJson(json, Map::class.java) as? Map<*, *>
            ?: throw IllegalArgumentException("Backup is not a JSON object")
        restore(prefs, parsed)
    }

    /**
     * The same, from an already-parsed map - which is what a whole-app backup holds one of
     * per preference file. [keep] names entries of the *current* file that survive the
     * clear: the same keys the snapshot skipped, so a restore leaves them as it found them.
     */
    fun restore(prefs: SharedPreferences, parsed: Map<*, *>, keep: Set<String> = emptySet()) {
        val kept = prefs.all.filterKeys { it in keep }

        val types = parsed[TYPES_KEY] as? Map<*, *>
        if (types == null) {
            Log.w(TAG, "Backup has no type map - falling back to inferring number types")
        }

        prefs.edit {
            clear()
            parsed.forEach { (rawKey, value) ->
                val key = rawKey as? String ?: return@forEach
                if (key == TYPES_KEY || key in kept) return@forEach

                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Number -> putNumber(key, value, types?.get(key) as? String)
                    // Gson turns a JSON array back into a List
                    is List<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> Unit
                    else -> Log.w(TAG, "Skipping $key: unsupported type ${value.javaClass.simpleName}")
                }
            }
            // Put back what the clear took out. Written after the backup's own entries so
            // a backup that happens to carry one of these keys does not win over the phone.
            for ((key, value) in kept) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }

    private fun SharedPreferences.Editor.putNumber(key: String, value: Number, declaredType: String?) {
        when (declaredType) {
            "Int" -> putInt(key, value.toInt())
            "Long" -> putLong(key, value.toLong())
            "Float" -> putFloat(key, value.toFloat())
            else -> putGuessedNumber(key, value)
        }
    }

    /**
     * Best effort for backups written before the type map existed. A whole number is far more
     * likely to be an Int or a Long than a Float, so that's the guess. A Float that happens to
     * hold a whole number (an agent parked at x=100.0, say) lands here as an Int - `getSafeFloat`
     * repairs that on the first read rather than throwing.
     */
    private fun SharedPreferences.Editor.putGuessedNumber(key: String, value: Number) {
        val asDouble = value.toDouble()
        when {
            asDouble.isNaN() || asDouble.isInfinite() || asDouble != floor(asDouble) ->
                putFloat(key, value.toFloat())
            asDouble >= Int.MIN_VALUE.toDouble() && asDouble <= Int.MAX_VALUE.toDouble() ->
                putInt(key, value.toInt())
            else ->
                putLong(key, value.toLong())
        }
    }
}
