/*
 * Copyright (C) 2026 Gorjan Jovanovski
 *
 * This file is part of Windows Phone Launcher.
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
import kotlin.math.sin

/**
 * A fixed-size real-input Fourier transform, for the one place this phone needs one.
 *
 * [SignatureGenerator] runs this on every 128 samples of audio - a hundred and twenty-five
 * times a second, for as long as Cortana is listening - so it is written as a decimation-
 * in-time radix-2 transform with the bit reversal and the twiddle factors worked out once
 * at construction and reused. Everything is in flat float arrays and nothing is allocated
 * per call, because a transform that allocates on the audio path is a transform that hands
 * the garbage collector a job a hundred and twenty-five times a second.
 *
 * Real input, which is what halves the work: audio has no imaginary part, so rather than a
 * 2048-point complex transform of a signal that is half zeroes, the samples are packed in
 * pairs as the real and imaginary halves of a 1024-point complex one, and the result is
 * separated out afterwards. It is the standard trick and it is exactly twice as fast; the
 * only thing to be careful about is the last bin, which the packing leaves at the far end -
 * see the end of [transform].
 *
 * Only the magnitudes come out, because magnitudes are all the caller wants: the phase of a
 * peak has nothing to do with which song it belongs to. They are returned squared, since
 * the caller compares them against each other and against a threshold and would only be
 * taking a square root in order to throw the information away.
 *
 * SongRec, which the rest of this package is ported from, hands this off to a Rust crate.
 * There is no equivalent in the platform, so this part is written rather than translated.
 */
internal class RealFft(private val size: Int) {

    /** The complex transform actually run: half the length of the real one. */
    private val half = size / 2

    /** Interleaved re, im - one array rather than two, so a butterfly touches one object. */
    private val work = FloatArray(size)

    /** Where each input index ends up once the inputs are in bit-reversed order. */
    private val reversed = IntArray(half)

    /** cos and sin of -2 pi k / half, for k up to half / 2. Used by the butterflies. */
    private val cosTable = FloatArray(half / 2)
    private val sinTable = FloatArray(half / 2)

    /** The same for the full-length transform, used only when unpacking. */
    private val unpackCos = FloatArray(half / 2 + 1)
    private val unpackSin = FloatArray(half / 2 + 1)

    init {
        require(size > 0 && size and (size - 1) == 0) { "size must be a power of two" }

        var bits = 0
        while (1 shl bits < half) bits++
        for (i in 0 until half) {
            var r = 0
            for (b in 0 until bits) if (i shr b and 1 == 1) r = r or (1 shl (bits - 1 - b))
            reversed[i] = r
        }

        for (k in 0 until half / 2) {
            val angle = -2.0 * Math.PI * k / half
            cosTable[k] = cos(angle).toFloat()
            sinTable[k] = sin(angle).toFloat()
        }
        for (k in 0..half / 2) {
            val angle = -2.0 * Math.PI * k / size
            unpackCos[k] = cos(angle).toFloat()
            unpackSin[k] = sin(angle).toFloat()
        }
    }

    /**
     * Transforms [input], and writes the squared magnitude of each bin into [magnitudes].
     *
     * [input] is [size] real samples; [magnitudes] receives `size / 2 + 1` values, which is
     * every bin a real signal has - the rest of the spectrum is the mirror image of these
     * and carries nothing.
     *
     * Unnormalised, like every other library's forward transform: no factor of 1/N is
     * applied, so the values scale with both the input's loudness and the window length.
     * The caller divides by a constant of its own - see [SignatureGenerator.doFft] - and
     * that constant assumes exactly this.
     */
    fun transform(input: FloatArray, magnitudes: FloatArray) {
        // Pack the real samples in pairs: even-numbered samples become the real parts of a
        // half-length complex signal and odd-numbered ones the imaginary parts. Loaded in
        // bit-reversed order at the same time, which is what lets the butterflies below run
        // in place.
        for (i in 0 until half) {
            val target = reversed[i] shl 1
            work[target] = input[i * 2]
            work[target + 1] = input[i * 2 + 1]
        }

        // The butterflies, smallest span first. This is an ordinary iterative Cooley-Tukey:
        // at each stage the signal is treated as pairs of half-length transforms and
        // recombined, doubling the span until it is the whole thing.
        var span = 2
        while (span <= half) {
            val step = half / span
            var start = 0
            while (start < half) {
                var k = 0
                for (pair in start until start + span / 2) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val a = pair shl 1
                    val b = (pair + span / 2) shl 1

                    val br = work[b]
                    val bi = work[b + 1]
                    val tr = br * wr - bi * wi
                    val ti = br * wi + bi * wr

                    work[b] = work[a] - tr
                    work[b + 1] = work[a + 1] - ti
                    work[a] += tr
                    work[a + 1] += ti

                    k += step
                }
                start += span
            }
            span = span shl 1
        }

        // Separate the two interleaved transforms back out.
        //
        // The packed result holds the transforms of the even and odd samples added
        // together, one in the part of each bin that is symmetric about zero and the other
        // in the antisymmetric part. Splitting bin k against bin half-k recovers both, and
        // a twiddle factor puts the odd half back into step with the even one.
        for (k in 0..half / 2) {
            val opposite = (half - k) and (half - 1)

            val ar = work[k shl 1]
            val ai = work[(k shl 1) + 1]
            val br = work[opposite shl 1]
            val bi = work[(opposite shl 1) + 1]

            // The even samples' transform: the half of the pair that is symmetric.
            val evenR = (ar + br) * 0.5f
            val evenI = (ai - bi) * 0.5f
            // The odd samples': the antisymmetric half, turned back a quarter turn.
            val oddR = (ai + bi) * 0.5f
            val oddI = -(ar - br) * 0.5f

            val wr = unpackCos[k]
            val wi = unpackSin[k]
            val rotatedR = oddR * wr - oddI * wi
            val rotatedI = oddR * wi + oddI * wr

            val re = evenR + rotatedR
            val im = evenI + rotatedI
            magnitudes[k] = re * re + im * im

            // The mirrored bin comes out of the same pair for free, and every one of them
            // is wanted: the caller reads bins 0 to size/2 inclusive. Skipped for k = 0,
            // whose mirror is the last bin and is handled below - that one is the odd case
            // the packing creates, since bin size/2 has no partner to be split against.
            if (k > 0 && k < half / 2) {
                val mirror = half - k
                val mr = evenR - rotatedR
                val mi = -(evenI - rotatedI)
                magnitudes[mirror] = mr * mr + mi * mi
            }
        }

        // Bin size/2, the Nyquist frequency. Both halves of the packed transform contribute
        // their zeroth bin to it, with the odd half turned all the way round to negative -
        // so it is one subtraction rather than anything needing a twiddle factor.
        val zeroR = work[0]
        val zeroI = work[1]
        val nyquist = zeroR - zeroI
        magnitudes[half] = nyquist * nyquist
    }
}
