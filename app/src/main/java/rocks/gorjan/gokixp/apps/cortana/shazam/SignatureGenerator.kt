/*
 * Copyright (C) 2026 Gorjan Jovanovski
 *
 * This file is part of Windows Phone Launcher.
 *
 * Ported from SongRec (https://github.com/marin-m/SongRec) by marin-m, GPL-3.0 -
 * specifically src/core/fingerprinting/algorithm.rs. The algorithm, its window sizes and
 * every constant below are that project's work; only the Kotlin is new. See NOTICE.
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

import kotlin.math.cos
import kotlin.math.ln

/**
 * Turns microphone audio into a Shazam fingerprint, as it arrives.
 *
 * Fed 16 kHz mono samples in whatever sizes the recorder hands them over, this keeps a
 * running spectrogram and picks out the peaks in it. What comes out of [snapshot] is a
 * list of (time, frequency, magnitude) triples - no audio, and nothing from which audio
 * could be reconstructed.
 *
 * The shape of it:
 *
 *  - Every 128 samples - a hundred and twenty-five times a second - the last 2048 samples
 *    are windowed and transformed. Overlapping the windows by sixteen to one is what makes
 *    the result stable under a moving phone and a noisy room.
 *  - Each spectrum is **spread**: every bin is raised to the loudest of itself and its two
 *    neighbours, and then back into the three spectra that came shortly before it. This
 *    smears a peak slightly in both frequency and time, so that a peak in the recording and
 *    the same peak in the reference need not land in exactly the same bin to be compared.
 *  - A bin becomes a **peak** when it stands above every one of its neighbours in both
 *    directions, tested against the spread copies rather than the raw ones, and against a
 *    scatter of earlier and later spectra as well as the adjacent ones.
 *
 * Written streaming rather than as one pass over a finished recording, which is the whole
 * reason listening feels instant: by the time the user has held the phone up for four
 * seconds, four seconds of transforms have already been done, and the fingerprint can go
 * out the moment they stop. SongRec's own microphone mode works the same way.
 *
 * Not thread-safe. [SongRecogniser] keeps one of these on its own recording thread and
 * takes a [snapshot] under a lock.
 */
internal class SignatureGenerator {

    private val fft = RealFft(FFT_SIZE)

    /** The last 2048 samples, oldest first once [ringIndex] is taken into account. */
    private val ring = FloatArray(FFT_SIZE)
    private var ringIndex = 0

    /** The ring, straightened out and windowed, ready for the transform. */
    private val windowed = FloatArray(FFT_SIZE)

    /** The last 256 spectra, and the last 256 spread copies of them. */
    private val spectra = Array(HISTORY) { FloatArray(BINS) }
    private var spectraIndex = 0
    private val spread = Array(HISTORY) { FloatArray(BINS) }
    private var spreadIndex = 0

    private var spreadsDone = 0

    /** Samples handed over but not yet part of a whole 128-sample step. */
    private val pending = FloatArray(HOP)
    private var pendingCount = 0

    private var samplesSeen = 0

    private val peaks: Map<FrequencyBand, MutableList<FrequencyPeak>> =
        FrequencyBand.entries.associateWith { mutableListOf() }

    /**
     * The window, computed rather than tabulated.
     *
     * A Hann window of 2050 points with the zero at each end dropped, which is what Shazam
     * uses and is very slightly not the Hann window any library hands you by default. It is
     * 2048 cosines once at startup, so there is no reason to ship it as a table the way
     * SongRec does.
     */
    private val window = FloatArray(FFT_SIZE) { n ->
        (0.5 * (1.0 - cos(2.0 * Math.PI * (n + 1) / (FFT_SIZE + 1)))).toFloat()
    }

    /**
     * Takes [count] samples from [samples] and folds them into the spectrogram.
     *
     * Samples are 16-bit PCM as the recorder gives them, kept at that scale rather than
     * normalised to ±1: the peak threshold further down is an absolute number and assumes
     * this.
     */
    fun feed(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val take = minOf(HOP - pendingCount, count - offset)
            for (i in 0 until take) pending[pendingCount + i] = samples[offset + i].toFloat()
            pendingCount += take
            offset += take
            samplesSeen += take

            if (pendingCount == HOP) {
                doFft(pending)
                doPeakSpreading()
                spreadsDone++
                if (spreadsDone >= PEAK_LAG) doPeakRecognition()
                pendingCount = 0
            }
        }
    }

    /**
     * The fingerprint of everything fed in so far.
     *
     * Copied, so that the recorder can go on adding to the spectrogram while the copy is
     * being encoded and sent. Cortana asks for one of these several times during a single
     * listen - a clear recording often matches after four seconds and there is no reason to
     * make the user wait out the other eight.
     */
    fun snapshot(): DecodedSignature = DecodedSignature(
        sampleRateHz = SAMPLE_RATE,
        numberSamples = samplesSeen,
        peaksByBand = peaks.mapValues { (_, list) -> list.toList() }
    )

    /** Whether anything worth sending has been found yet. */
    fun hasPeaks(): Boolean = peaks.values.any { it.isNotEmpty() }

    // ---------------------------------------------------------------- the passes

    private fun doFft(hop: FloatArray) {
        // The newest 128 samples overwrite the oldest, so the ring always holds the last
        // 2048 with the write position marking the boundary between old and new.
        System.arraycopy(hop, 0, ring, ringIndex, HOP)
        ringIndex = (ringIndex + HOP) and (FFT_SIZE - 1)

        // Straighten it out - oldest sample first - and apply the window in the same pass.
        for (i in 0 until FFT_SIZE) {
            windowed[i] = ring[(i + ringIndex) and (FFT_SIZE - 1)] * window[i]
        }

        val output = spectra[spectraIndex]
        fft.transform(windowed, output)
        for (i in 0 until BINS) {
            // The divisor is what puts the transform of a full-scale 16-bit signal into the
            // range the peak threshold below is written in terms of. The floor keeps the
            // logarithm further down finite for a bin that is digital silence.
            output[i] = (output[i] / MAGNITUDE_SCALE).coerceAtLeast(MAGNITUDE_FLOOR)
        }
        spectraIndex = (spectraIndex + 1) and (HISTORY - 1)
    }

    private fun doPeakSpreading() {
        val latest = spectra[(spectraIndex - 1) and (HISTORY - 1)]
        val target = spread[spreadIndex]
        System.arraycopy(latest, 0, target, 0, BINS)

        // Frequency-domain spreading: each bin takes the loudest of itself and the two
        // above it. Done in place and upwards, so a tall bin propagates down the array as
        // far as its neighbours reach rather than only one step.
        for (i in 0..BINS - 3) {
            var value = target[i]
            if (target[i + 1] > value) value = target[i + 1]
            if (target[i + 2] > value) value = target[i + 2]
            target[i] = value
        }

        // Time-domain spreading: this spectrum is also folded back into three that came
        // before it, so a peak is visible slightly early as well as slightly late.
        for (back in TIME_SPREAD) {
            val earlier = spread[(spreadIndex - back) and (HISTORY - 1)]
            for (i in 0 until BINS) {
                if (target[i] > earlier[i]) earlier[i] = target[i]
            }
        }

        spreadIndex = (spreadIndex + 1) and (HISTORY - 1)
    }

    private fun doPeakRecognition() {
        // Peaks are looked for well behind the newest spectrum, because the test compares a
        // candidate against spectra on both sides of it - including some a good way after -
        // and those have to have been computed already.
        val candidate = spectra[(spectraIndex - PEAK_LAG) and (HISTORY - 1)]
        val reference = spread[(spreadIndex - SPREAD_LAG) and (HISTORY - 1)]

        for (bin in 10..1014) {
            val value = candidate[bin]

            // Loud enough to be worth considering at all, and louder than the spread
            // spectrum just below it in frequency.
            if (value < PEAK_THRESHOLD || value < reference[bin - 1]) continue

            // A local maximum in frequency, against a scatter of neighbours rather than
            // just the two adjacent bins.
            var loudestNeighbour = 0f
            for (offset in FREQUENCY_NEIGHBOURS) {
                val neighbour = reference[bin + offset]
                if (neighbour > loudestNeighbour) loudestNeighbour = neighbour
            }
            if (value <= loudestNeighbour) continue

            // And a local maximum in time, against spectra scattered before and after.
            var loudestInTime = loudestNeighbour
            for (offset in TIME_NEIGHBOURS) {
                val other = spread[(spreadIndex + offset) and (HISTORY - 1)]
                val neighbour = other[bin - 1]
                if (neighbour > loudestInTime) loudestInTime = neighbour
            }
            if (value <= loudestInTime) continue

            // A peak. Its exact frequency is interpolated from the bins either side, which
            // recovers precision the 1024-bin transform threw away - hence the factor of 64
            // the bin number is scaled by before the correction is added.
            val magnitude = logMagnitude(value)
            val before = logMagnitude(candidate[bin - 1])
            val after = logMagnitude(candidate[bin + 1])

            val curvature = magnitude * 2f - before - after
            // Zero curvature means the three bins are collinear and there is no summit to
            // interpolate. SongRec asserts this cannot happen; a launcher cannot afford to
            // find out it can, so the peak is dropped instead.
            if (curvature <= 0f) continue

            val correction = ((after - before) * 32f / curvature).toInt()
            val correctedBin = (bin * 64 + correction) and 0xFFFF

            val frequencyHz = (correctedBin * HZ_PER_CORRECTED_BIN).toInt()
            val band = FrequencyBand.of(frequencyHz) ?: continue

            peaks.getValue(band).add(
                FrequencyPeak(
                    fftPassNumber = spreadsDone - PEAK_LAG,
                    peakMagnitude = magnitude.toInt().coerceIn(0, 0xFFFF),
                    correctedPeakFrequencyBin = correctedBin
                )
            )
        }
    }

    /**
     * A bin's loudness on the scale the fingerprint stores it in.
     *
     * Logarithmic, because loudness is: the difference between a quiet peak and a loud one
     * matters far less than the fact that both are peaks, and a linear scale would let one
     * loud moment dominate a whole fingerprint.
     */
    private fun logMagnitude(value: Float): Float =
        ln(value).coerceAtLeast(PEAK_THRESHOLD) * 1477.3f + 6144f

    private companion object {
        const val SAMPLE_RATE = 16000
        const val FFT_SIZE = 2048

        /** How far the analysis window moves each step: 8 ms, or 125 a second. */
        const val HOP = 128

        /** Bins a real 2048-point transform has. */
        const val BINS = 1025

        /** How many spectra are kept. A power of two, so the ring index can be masked. */
        const val HISTORY = 256

        /**
         * How far behind the newest spectrum peaks are picked out.
         *
         * The time-domain test below reaches up to 249 spectra away in the ring, which is
         * to say a little way into the future of the candidate; this lag is what makes that
         * future already computed.
         */
        const val PEAK_LAG = 46
        const val SPREAD_LAG = 49

        /** Which earlier spectra a new one is folded back into. */
        val TIME_SPREAD = intArrayOf(1, 3, 6)

        /** Offsets in bins that a candidate has to beat to be a peak. */
        val FREQUENCY_NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)

        /** Offsets in spectra that it also has to beat. */
        val TIME_NEIGHBOURS = intArrayOf(
            -53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249
        )

        const val PEAK_THRESHOLD = 1f / 64f
        const val MAGNITUDE_SCALE = (1 shl 17).toFloat()
        const val MAGNITUDE_FLOOR = 1e-10f

        /** 16 kHz over two, over 1024 bins, over the factor of 64 the bin is scaled by. */
        const val HZ_PER_CORRECTED_BIN = 16000f / 2f / 1024f / 64f
    }
}
