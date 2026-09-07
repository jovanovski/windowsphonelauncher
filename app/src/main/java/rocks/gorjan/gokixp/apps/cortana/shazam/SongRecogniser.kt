/*
 * Copyright (C) 2026 Gorjan Jovanovski
 *
 * This file is part of Windows Phone Launcher.
 *
 * The listening strategy follows SongRec's microphone mode
 * (https://github.com/marin-m/SongRec, GPL-3.0). See NOTICE.
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

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the microphone open, fingerprints what it hears, and asks Shazam about it.
 *
 * One listen runs for at most twelve seconds and asks three times along the way - at four,
 * eight and twelve. That is the whole of the strategy, and it exists because those three
 * cases are genuinely different: a chorus recorded a foot from a speaker is usually named
 * in four seconds, a verse across a pub might need all twelve, and something that is not in
 * the database will never be named however long it is listened to. Asking early costs one
 * request and saves eight seconds of somebody standing there holding their phone up.
 *
 * The fingerprint is cumulative - the second question includes everything the first one
 * asked about - so a late attempt is strictly better informed than an early one, and there
 * is no sense in which the twelve-second answer is a different recording from the four.
 *
 * Recording and network both happen on one background thread. Callbacks come back on the
 * main thread, because they all end in something being drawn.
 */
class SongRecogniser {

    /** What the caller is told, on the main thread. */
    interface Listener {
        /** The microphone is open and the ring should say so. */
        fun onListening()

        /**
         * A song, named - as the words to look it up by.
         *
         * The title and the artist are not handed over separately, because nothing shows
         * them separately: a named song goes straight to a search - see
         * `CortanaApp.searchForSong` - and what that needs is one line of text.
         */
        fun onRecognised(searchTerm: String)

        /** Twelve seconds of listening and Shazam knew none of it. */
        fun onNotRecognised()

        /** Something went wrong; [message] is fit to put on screen. */
        fun onFailed(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val isListening: Boolean get() = running.get()

    /**
     * Starts listening, unless a listen is already under way.
     *
     * The caller is responsible for having the microphone permission before calling this -
     * see `CortanaApp.startListening`. Without it [AudioRecord] does not fail, it quietly
     * returns silence, which would look like a song nobody could name.
     */
    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (!running.compareAndSet(false, true)) return

        thread = Thread({ listen(listener) }, "cortana-listen").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    /** Stops a listen in progress. Silent - the caller already knows it asked for this. */
    fun stop() {
        running.set(false)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun listen(listener: Listener) {
        var recorder: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimum <= 0) {
                finish(listener) { it.onFailed("This phone won't let me listen.") }
                return
            }

            recorder = AudioRecord(
                // The unprocessed source. The default one is tuned for speech - noise
                // suppression, automatic gain, sometimes a high-pass filter - and every one
                // of those is a machine confidently removing the music.
                MediaRecorder.AudioSource.UNPROCESSED,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, SAMPLE_RATE)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(
                    // Not every phone has an unprocessed source. A processed one still
                    // recognises most things; it is a worse microphone, not a broken one.
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, SAMPLE_RATE)
                )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                finish(listener) { it.onFailed("Something else is using the microphone.") }
                return
            }

            recorder.startRecording()
            main.post { listener.onListening() }

            val generator = SignatureGenerator()
            val buffer = ShortArray(READ_SAMPLES)
            var samplesRead = 0
            var nextAttempt = 0

            while (running.get() && samplesRead < MAX_SAMPLES) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR) {
                        finish(listener) { it.onFailed("The microphone stopped.") }
                        return
                    }
                    continue
                }
                generator.feed(buffer, read)
                samplesRead += read

                // Ask as soon as this much has been heard, then again later if it was not
                // enough. The check is a threshold rather than an equality because a read
                // can hand over any number of samples and will rarely land on the mark.
                if (nextAttempt < ATTEMPT_AFTER_SAMPLES.size &&
                    samplesRead >= ATTEMPT_AFTER_SAMPLES[nextAttempt]
                ) {
                    nextAttempt++
                    if (!generator.hasPeaks()) continue

                    when (val result = ShazamClient.recognise(generator.snapshot())) {
                        is ShazamResult.Match -> {
                            val term = result.song.searchTerm
                            finish(listener) { it.onRecognised(term) }
                            return
                        }
                        // Worth carrying on: the next attempt has more to go on. Only the
                        // last one means the answer is actually no.
                        is ShazamResult.NoMatch -> Unit
                        is ShazamResult.Failed -> {
                            finish(listener) { it.onFailed(result.message) }
                            return
                        }
                    }
                    if (!running.get()) return
                }
            }

            if (!running.get()) {
                // Cancelled by the user. They are looking at the screen they asked to go
                // back to, and have no use for a verdict on a listen they abandoned.
                running.set(false)
                return
            }
            finish(listener) { it.onNotRecognised() }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to record", e)
            finish(listener) { it.onFailed("I need permission to use the microphone.") }
        } catch (e: Exception) {
            Log.e(TAG, "listening failed", e)
            finish(listener) { it.onFailed("Something went wrong listening.") }
        } finally {
            try {
                recorder?.stop()
            } catch (_: Exception) {
                // Already stopped, or never started. Nothing to do about it either way.
            }
            recorder?.release()
            running.set(false)
        }
    }

    /** Ends the listen and reports it, unless the user got there first. */
    private fun finish(listener: Listener, report: (Listener) -> Unit) {
        if (!running.getAndSet(false)) return
        main.post { report(listener) }
    }

    private companion object {
        const val TAG = "Cortana/Listen"
        const val SAMPLE_RATE = 16000

        /** How much is read at a time: a fifth of a second, and a multiple of the 128 hop. */
        const val READ_SAMPLES = 3200

        /** Twelve seconds, which is as long as Shazam's own client listens for. */
        const val MAX_SAMPLES = 12 * SAMPLE_RATE

        /** When to ask: after four seconds, eight, and the full twelve. */
        val ATTEMPT_AFTER_SAMPLES = intArrayOf(4 * SAMPLE_RATE, 8 * SAMPLE_RATE, 12 * SAMPLE_RATE)
    }
}
