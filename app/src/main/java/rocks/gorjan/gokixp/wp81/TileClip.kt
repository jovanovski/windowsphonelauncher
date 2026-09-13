package rocks.gorjan.gokixp.wp81

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A clip from the camera roll playing on a tile: its picture, and nothing else.
 *
 * Only the video track is ever read. A MediaPlayer turned down to nothing is not silent -
 * it still decodes the clip's sound and hands it to the mixer at full level, and the
 * volume is applied after that. Recording the screen with the phone's audio copies each
 * app's sound before the volume is applied (AudioFlinger's Track::interceptBuffer), so a
 * muted tile put the clip's sound into every screen recording of Start. A clip whose
 * sound is never decoded has none to copy - and asks for no audio focus, so whatever the
 * user is actually listening to goes on playing, and runs no audio decoder for a tile
 * nobody could hear anyway.
 *
 * One thread per clip, decoding straight onto the tile's surface and holding each frame
 * until its time comes. Paused, the thread waits on the frame it is holding; released, it
 * lets go of the decoder itself, so the main thread never waits on a codec.
 */
internal class TileClip(
    context: Context,
    private val uri: Uri,
    private val surface: Surface,
    /**
     * The clip this one follows onto the same surface, if any. A surface takes one decoder
     * at a time and the last clip lets go of its own on its own thread, so this one waits
     * for that before asking for the surface.
     */
    private var previous: TileClip?,
    /** The picture's size is known; read it from [videoWidth] and [videoHeight]. */
    private val onSize: (TileClip) -> Unit,
    /** The clip will not play. The still behind it is all the tile has. */
    private val onError: (TileClip) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()

    @Volatile private var playing = false
    @Volatile private var released = false

    // The decoding thread's own. A frame is due at clockBaseNs plus its presentation time.
    private var clockBaseNs = 0L
    private var clockStale = true

    /**
     * The picture's size as it is shown - turned already, for a clip filmed on its side.
     * Main thread only; zero until [onSize].
     */
    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    private val thread = Thread(::run, "TileClip").apply { start() }

    /** Starts or holds the picture. A clip starts held. */
    fun setPlaying(play: Boolean) = lock.withLock {
        playing = play
        wake.signalAll()
    }

    /** Stops for good. The decoder is let go of on the clip's own thread. */
    fun release() = lock.withLock {
        released = true
        wake.signalAll()
    }

    private fun run() {
        previous?.thread?.join()
        previous = null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalStateException("No picture in $uri")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            // The decoder turns the picture on the surface itself (the rotation is in the
            // format it is configured with), so only the size handed back needs turning.
            val turned = format.containsKey(MediaFormat.KEY_ROTATION) &&
                format.getInteger(MediaFormat.KEY_ROTATION) % 180 != 0
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            main.post {
                if (released) return@post
                videoWidth = if (turned) h else w
                videoHeight = if (turned) w else h
                onSize(this)
            }

            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            codec = if (name != null) MediaCodec.createByCodecName(name)
            else MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            if (released) return
            codec.configure(format, surface, null, 0)
            codec.start()
            decode(extractor, codec)
        } catch (e: Exception) {
            // A clip taken down mid-frame loses its surface under the decoder, which is
            // expected rather than worth reporting.
            if (!released) {
                Log.w(TAG, "Could not play a clip on a tile", e)
                main.post { if (!released) onError(this) }
            }
        } finally {
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                // Never started, or already in error; release is all there is to do.
            }
            codec?.release()
            extractor?.release()
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var shownThisPass = false
        while (!released) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Anything negative is "try again" or news about the output this loop has no
            // use for: the surface takes care of formats and buffers itself.
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) continue

            val show = info.size > 0
            if (show && !awaitFrame(info.presentationTimeUs * 1_000)) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            codec.releaseOutputBuffer(index, show)
            shownThisPass = shownThisPass || show

            // Short clips loop rather than freezing on their last frame; long ones are cut
            // off by the flip, which is the right length for a tile either way.
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                if (!shownThisPass) throw IllegalStateException("No frames in $uri")
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                inputDone = false
                shownThisPass = false
                clockStale = true
            }
        }
    }

    /**
     * Holds the thread until a frame is due, or for as long as the clip is held.
     *
     * The clock is set again by the first frame after a hold or a loop, so a clip left
     * held for a minute does not come back racing to catch up with that minute.
     *
     * @return false if the clip was released while waiting.
     */
    private fun awaitFrame(presentationNs: Long): Boolean {
        lock.withLock {
            while (!released) {
                if (!playing) {
                    wake.await()
                    clockStale = true
                    continue
                }
                val now = System.nanoTime()
                if (clockStale) {
                    clockBaseNs = now - presentationNs
                    clockStale = false
                }
                val waitNs = clockBaseNs + presentationNs - now
                if (waitNs <= 0) return true
                wake.awaitNanos(waitNs)
            }
            return false
        }
    }

    private companion object {
        const val TAG = "TileClip"

        /** Short, so a released clip is never more than a moment from noticing. */
        const val TIMEOUT_US = 10_000L
    }
}
