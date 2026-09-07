/*
 * Copyright (C) 2026 Gorjan Jovanovski
 *
 * This file is part of Windows Phone Launcher.
 *
 * Ported from SongRec (https://github.com/marin-m/SongRec) by marin-m, GPL-3.0 -
 * specifically src/core/fingerprinting/communication.rs. The endpoint, its query string and
 * the shape of the request body are that project's work; only the Kotlin is new. See
 * NOTICE.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package rocks.gorjan.gokixp.apps.cortana.shazam

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * A song Shazam recognised.
 *
 * The response carries a great deal more than this - cover art, a release date, links to
 * half a dozen streaming services - and none of it is kept, because a named song goes
 * straight to a search rather than to a page about itself. See `CortanaApp.searchForSong`.
 */
internal data class RecognisedSong(val title: String, val artist: String) {
    /** The words to look the song up by. */
    val searchTerm: String get() = if (artist.isBlank()) title else "$artist $title"
}

/** How an attempt at recognising something ended. */
internal sealed interface ShazamResult {
    data class Match(val song: RecognisedSong) : ShazamResult

    /** The request worked and Shazam knew of nothing matching. */
    data object NoMatch : ShazamResult

    /** It did not get that far. [message] is fit to show the user. */
    data class Failed(val message: String) : ShazamResult
}

/**
 * Asks Shazam what a fingerprint is.
 *
 * This is not a published API. It is the one their own mobile client uses, which SongRec
 * worked out by watching it, and it comes with the usual consequences of that: it is
 * rate-limited per address, it can start refusing at any time, and nothing here is a
 * contract anybody has to honour. All three failure modes come back as [ShazamResult.Failed]
 * with something a person can read, because from the user's side "Cortana couldn't reach
 * out" is the whole of what happened.
 *
 * Only the fingerprint is sent. There is no audio in the request and none is ever kept -
 * see [SignatureGenerator].
 */
internal object ShazamClient {

    private const val TAG = "Cortana/Shazam"

    /**
     * Sends [signature] and waits for an answer. Blocking; call it off the main thread.
     */
    fun recognise(signature: DecodedSignature): ShazamResult {
        val timestamp = System.currentTimeMillis()

        // The geolocation is fixed and deliberately vague. Their client sends one and the
        // request is rejected without it, but a launcher has no business attaching the
        // user's actual position to a question about a song - so it sends the same
        // meaningless point every time, as SongRec does.
        val body = JsonObject().apply {
            add("geolocation", JsonObject().apply {
                addProperty("altitude", 300)
                addProperty("latitude", 45)
                addProperty("longitude", 2)
            })
            add("signature", JsonObject().apply {
                addProperty("samplems", signature.durationMs)
                addProperty("timestamp", timestamp)
                addProperty("uri", signature.encodeToUri())
            })
            addProperty("timestamp", timestamp)
            addProperty("timezone", TimeZone.getDefault().id)
        }.toString()

        val url = URL(
            "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
                UUID.randomUUID().toString().uppercase(Locale.US) + "/" +
                UUID.randomUUID().toString() +
                "?sync=true&webv3=true&sampling=true&connected=" +
                "&shazamapiversion=v3&sharehub=true&video=v3"
        )

        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Language", "en_US")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            when (val code = connection.responseCode) {
                200 -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                429 -> {
                    Log.w(TAG, "rate limited")
                    ShazamResult.Failed("Too many songs too quickly. Try again in a minute.")
                }
                else -> {
                    Log.w(TAG, "Shazam answered $code")
                    ShazamResult.Failed("Couldn't reach the music service.")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "could not reach Shazam", e)
            ShazamResult.Failed("Couldn't reach the music service.")
        } catch (e: Exception) {
            Log.e(TAG, "recognition failed", e)
            ShazamResult.Failed("Something went wrong listening.")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Pulls the song out of the response, or decides there wasn't one.
     *
     * Defensive throughout: this is somebody else's undocumented JSON, every field is
     * optional as far as this code is concerned, and a missing one means "no match" rather
     * than a crash in a launcher.
     */
    private fun parse(json: String): ShazamResult {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return ShazamResult.NoMatch

        val track = root.getAsJsonObject("track") ?: return ShazamResult.NoMatch
        val title = track.get("title")?.asStringOrNull().orEmpty()
        if (title.isBlank()) return ShazamResult.NoMatch

        val artist = track.get("subtitle")?.asStringOrNull().orEmpty()
        return ShazamResult.Match(RecognisedSong(title, artist))
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        runCatching { if (isJsonPrimitive) asString else null }.getOrNull()

    /**
     * What the request says it is.
     *
     * A plain Android user agent. SongRec rotates through a large list of real ones to
     * avoid being singled out; that is a reasonable thing for a desktop client making many
     * requests and overkill for a launcher whose user taps this a few times a day.
     */
    private const val USER_AGENT =
        "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)"
}
