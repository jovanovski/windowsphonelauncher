/*
 * Copyright (C) 2026 Gorjan Jovanovski
 *
 * This file is part of Windows Phone Launcher.
 *
 * Ported from SongRec (https://github.com/marin-m/SongRec) by marin-m, GPL-3.0 -
 * specifically src/core/fingerprinting/signature_format.rs. The binary layout, the magic
 * numbers and the delta encoding below are that project's reverse engineering of Shazam's
 * format; only the Kotlin is new. See NOTICE.
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

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * One peak in the spectrogram: a moment, a frequency, and how loud it was there.
 *
 * This is the whole of what a fingerprint is made of. A song is not recognised by what it
 * sounds like but by the pattern its loudest moments make when they are plotted against
 * time and frequency - which is why a recording made across a noisy room still matches, and
 * why the phone can send this instead of the audio.
 *
 * [fftPassNumber] is which 128-sample step of the analysis this peak was found in, so time
 * is measured in eighths of a second rather than in seconds. [correctedPeakFrequencyBin] is
 * the frequency bin multiplied by 64 and then nudged by an interpolation between the bins
 * either side of it, which recovers a good deal more precision than 1024 bins would
 * otherwise give.
 */
internal class FrequencyPeak(
    val fftPassNumber: Int,
    val peakMagnitude: Int,
    val correctedPeakFrequencyBin: Int
)

/**
 * The four bands peaks are filed under, and their ordering in the packet.
 *
 * Shazam splits the spectrum rather than treating it as one range, so that a bass line and
 * a cymbal are not competing to be the same peak. Anything below 250 Hz or above 5.5 kHz is
 * discarded: the bottom is mostly room and handling noise, and the top carries almost
 * nothing that survives a phone microphone.
 */
internal enum class FrequencyBand(val lowHz: Int, val highHz: Int) {
    BAND_250_520(250, 519),
    BAND_520_1450(520, 1449),
    BAND_1450_3500(1450, 3499),
    BAND_3500_5500(3500, 5500);

    companion object {
        /** Which band a frequency belongs to, or null if it is outside all of them. */
        fun of(frequencyHz: Int): FrequencyBand? =
            entries.firstOrNull { frequencyHz >= it.lowHz && frequencyHz <= it.highHz }
    }
}

/**
 * A finished fingerprint, and the one thing in this package that goes over the network.
 *
 * [encodeToUri] packs it into the binary form Shazam's servers expect and wraps that in the
 * data URI their client sends. The layout is not documented anywhere by Shazam; it is
 * SongRec's reading of their own packets, and the magic numbers below are theirs.
 */
internal class DecodedSignature(
    val sampleRateHz: Int,
    val numberSamples: Int,
    val peaksByBand: Map<FrequencyBand, List<FrequencyPeak>>
) {

    /** How long the recording behind this fingerprint was, which the request also states. */
    val durationMs: Int get() = (numberSamples.toLong() * 1000 / sampleRateHz).toInt()

    /**
     * The fingerprint as Shazam's own binary packet.
     *
     * A 48-byte header, then a type-length-value list with one entry per band. The two
     * lengths and the checksum can only be filled in once everything after them is known,
     * so they are written as zeroes and patched at the end - which is why this builds into
     * a byte array and edits it, rather than streaming.
     */
    fun encodeToBinary(): ByteArray {
        val body = ByteArrayOutputStream(4096)

        fun le(value: Int) {
            body.write(value and 0xFF)
            body.write(value ushr 8 and 0xFF)
            body.write(value ushr 16 and 0xFF)
            body.write(value ushr 24 and 0xFF)
        }

        le(MAGIC_1)
        le(0)                       // crc32, patched below
        le(0)                       // size minus header, patched below
        le(MAGIC_2)
        le(0); le(0); le(0)         // void
        le(sampleRateId(sampleRateHz) shl 27)
        le(0); le(0)                // void
        // The sample count with a fixed fraction of the sample rate added. The rate is
        // known from the field above, so the reader subtracts it back off.
        le(numberSamples + (sampleRateHz * 0.24f).toInt())
        le((15 shl 19) + 0x40000)   // fixed

        le(0x40000000)
        le(0)                       // the size again, patched below

        for (band in FrequencyBand.entries) {
            val peaks = peaksByBand[band].orEmpty()
            if (peaks.isEmpty()) continue

            val packed = ByteArrayOutputStream(peaks.size * 5)

            // Time is stored as a gap from the previous peak rather than absolutely, which
            // fits almost every gap in a single byte. When one will not fit, an escape
            // carries the position in full and the gap that follows it is zero.
            var previousPass = 0
            for (peak in peaks) {
                if (peak.fftPassNumber - previousPass >= 0xFF) {
                    packed.write(0xFF)
                    packed.write(peak.fftPassNumber and 0xFF)
                    packed.write(peak.fftPassNumber ushr 8 and 0xFF)
                    packed.write(peak.fftPassNumber ushr 16 and 0xFF)
                    packed.write(peak.fftPassNumber ushr 24 and 0xFF)
                    previousPass = peak.fftPassNumber
                }
                packed.write(peak.fftPassNumber - previousPass and 0xFF)
                packed.write(peak.peakMagnitude and 0xFF)
                packed.write(peak.peakMagnitude ushr 8 and 0xFF)
                packed.write(peak.correctedPeakFrequencyBin and 0xFF)
                packed.write(peak.correctedPeakFrequencyBin ushr 8 and 0xFF)
                previousPass = peak.fftPassNumber
            }

            val bytes = packed.toByteArray()
            le(BAND_TAG_BASE + band.ordinal)
            le(bytes.size)
            body.write(bytes)
            // Each band's block is padded out to a multiple of four.
            repeat((4 - bytes.size % 4) % 4) { body.write(0) }
        }

        val packet = body.toByteArray()
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8, packet.size - HEADER_SIZE)
        buffer.putInt(HEADER_SIZE + 4, packet.size - HEADER_SIZE)

        // Over everything past the checksum field itself, which is why it is written last.
        val crc = CRC32()
        crc.update(packet, 8, packet.size - 8)
        buffer.putInt(4, crc.value.toInt())

        return packet
    }

    /** The packet as the data URI the request carries it in. */
    fun encodeToUri(): String =
        DATA_URI_PREFIX + Base64.encodeToString(encodeToBinary(), Base64.NO_WRAP)

    private fun sampleRateId(rate: Int): Int = when (rate) {
        8000 -> 1
        11025 -> 2
        16000 -> 3
        32000 -> 4
        44100 -> 5
        48000 -> 6
        else -> throw IllegalArgumentException("Shazam has no id for a $rate Hz signature")
    }

    private companion object {
        const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        const val MAGIC_1 = 0xcafe2580.toInt()
        const val MAGIC_2 = 0x94119c00.toInt()
        const val BAND_TAG_BASE = 0x60030040
        const val HEADER_SIZE = 48
    }
}
