/*
 * Copyright (C) 2026 Gorjan Jovanovski
 *
 * This file is part of Windows Phone Launcher, and is released under the GNU General
 * Public License v3. See LICENSE.
 */

package rocks.gorjan.gokixp.apps.cortana.shazam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The two halves of song recognition that fail silently.
 *
 * Neither of these throws when it is wrong. A transform with a sign error still returns
 * 1025 plausible-looking numbers, and a packet with the length field off by four is still a
 * packet - they just mean that Shazam never recognises anything, which looks exactly like
 * a quiet pub. Both were worth writing down.
 *
 * The peak picking itself is not tested here. What it produces is only meaningful to
 * somebody else's database, so the only real test of it is that a phone held up to a song
 * gets the song, and that is not a thing a JVM test can do.
 */
class FingerprintingTest {

    // ---------------------------------------------------------------- the transform

    /**
     * The fast transform against the slow one.
     *
     * A direct sum over every input for every bin, which is the definition rather than an
     * implementation, so agreeing with it is the whole of being correct. Done at 256 points
     * because the naive version is quadratic and 2048 would take a while; the code under
     * test does not care about the size and the packing it uses is the same at both.
     */
    @Test
    fun `transform matches a direct discrete transform`() {
        val size = 256
        val random = Random(20260906)
        val input = FloatArray(size) { (random.nextGaussian() * 3000).toFloat() }

        val actual = FloatArray(size / 2 + 1)
        RealFft(size).transform(input, actual)

        for (bin in 0..size / 2) {
            var re = 0.0
            var im = 0.0
            for (n in 0 until size) {
                val angle = -2.0 * Math.PI * bin * n / size
                re += input[n] * cos(angle)
                im += input[n] * sin(angle)
            }
            val expected = (re * re + im * im).toFloat()
            val tolerance = abs(expected) * 1e-3f + 1f
            assertEquals("bin $bin", expected, actual[bin], tolerance)
        }
    }

    /** A pure tone belongs in one bin, and the transform should put it there. */
    @Test
    fun `a tone lands in its own bin`() {
        val size = 2048
        val magnitudes = FloatArray(size / 2 + 1)
        val fft = RealFft(size)

        for (bin in intArrayOf(1, 7, 100, 511, 1023)) {
            val input = FloatArray(size) {
                (8000.0 * sin(2.0 * Math.PI * bin * it / size)).toFloat()
            }
            fft.transform(input, magnitudes)

            var loudest = 0
            for (i in magnitudes.indices) if (magnitudes[i] > magnitudes[loudest]) loudest = i
            assertEquals("a tone at bin $bin", bin, loudest)
        }
    }

    /**
     * The direct-current and Nyquist bins, which the packing handles separately.
     *
     * These are the two the real-input trick is most likely to get wrong, because neither
     * has a partner bin to be split against - so they are checked against values that can
     * be worked out by hand rather than against the transform's own idea of them.
     */
    @Test
    fun `the edge bins are right`() {
        val size = 64
        val magnitudes = FloatArray(size / 2 + 1)
        val fft = RealFft(size)

        // A constant signal is entirely direct current: bin 0 holds the sum, and there is
        // nothing at any other frequency.
        fft.transform(FloatArray(size) { 100f }, magnitudes)
        assertEquals((100.0 * size) * (100.0 * size), magnitudes[0].toDouble(), 1.0)
        for (bin in 1..size / 2) {
            assertTrue("bin $bin of a constant", magnitudes[bin] < 1.0)
        }

        // A signal that alternates every sample is exactly the Nyquist frequency, and
        // belongs entirely in the last bin.
        fft.transform(FloatArray(size) { if (it % 2 == 0) 100f else -100f }, magnitudes)
        assertEquals(
            (100.0 * size) * (100.0 * size), magnitudes[size / 2].toDouble(), 1.0
        )
        for (bin in 0 until size / 2) {
            assertTrue("bin $bin of an alternating signal", magnitudes[bin] < 1.0)
        }
    }

    // ---------------------------------------------------------------- the packet

    /**
     * The header Shazam reads before it looks at anything else.
     *
     * Their server drops a packet whose checksum or lengths are wrong without saying why,
     * so these four fields are the difference between "no match" and "never asked".
     */
    @Test
    fun `the packet header describes the packet`() {
        val packet = signatureOf(peaks = 40).encodeToBinary()
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("magic 1", 0xcafe2580.toInt(), buffer.getInt(0))
        assertEquals("magic 2", 0x94119c00.toInt(), buffer.getInt(12))
        assertEquals("size in the header", packet.size - 48, buffer.getInt(8))
        assertEquals("size repeated after it", packet.size - 48, buffer.getInt(52))
        // Three void words sit between the second magic number and this one.
        assertEquals("the 16 kHz sample rate id", 3 shl 27, buffer.getInt(28))

        val crc = CRC32().apply { update(packet, 8, packet.size - 8) }
        assertEquals("checksum", crc.value.toInt(), buffer.getInt(4))

        // Every band's block is padded out, so the whole thing stays aligned.
        assertEquals("total length is a multiple of four", 0, packet.size % 4)
    }

    /**
     * The sample count, which is how the far end knows how long a recording it is judging.
     *
     * Stored with a fixed fraction of the sample rate added to it, so this is checked
     * against the same arithmetic in reverse rather than against a number typed out here.
     */
    @Test
    fun `the packet states how much audio it came from`() {
        val signature = signatureOf(peaks = 8)
        val buffer = ByteBuffer.wrap(signature.encodeToBinary()).order(ByteOrder.LITTLE_ENDIAN)
        val stored = buffer.getInt(40)
        assertEquals(signature.numberSamples, stored - (16000 * 0.24f).toInt())
        assertEquals("four seconds", 4000, signature.durationMs)
    }

    /**
     * A gap of 255 passes or more between peaks, which the delta encoding cannot hold.
     *
     * The escape that handles it is the one branch in the encoder that a short recording
     * never reaches, so it would otherwise ship untested and break only for somebody who
     * held their phone up to a very quiet song.
     */
    @Test
    fun `a long gap between peaks is escaped`() {
        val far = DecodedSignature(
            sampleRateHz = 16000,
            numberSamples = 16000,
            peaksByBand = mapOf(
                FrequencyBand.BAND_250_520 to listOf(
                    FrequencyPeak(0, 1000, 4000),
                    FrequencyPeak(900, 1000, 4000)
                )
            )
        )
        val packet = far.encodeToBinary()
        // Header, then the band's tag and length, then: one peak at zero (5 bytes), the
        // escape and its four-byte position, and the second peak.
        val bandLength = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(60)
        assertEquals("five bytes a peak, plus a five-byte escape", 15, bandLength)
        assertEquals("the escape marker", 0xFF, packet[64 + 5].toInt() and 0xFF)
    }

    /** Bands with nothing in them are left out rather than written empty. */
    @Test
    fun `empty bands are omitted`() {
        val onlyOne = DecodedSignature(
            sampleRateHz = 16000,
            numberSamples = 16000,
            peaksByBand = mapOf(
                FrequencyBand.BAND_250_520 to listOf(FrequencyPeak(0, 1000, 4000)),
                FrequencyBand.BAND_520_1450 to emptyList()
            )
        )
        // 48 of header, 8 for the size chunk, 8 for the one band's tag and length, and
        // five bytes of peak padded up to eight.
        assertEquals(48 + 8 + 8 + 8, onlyOne.encodeToBinary().size)
    }

    /** A fingerprint with some peaks in the lowest band, for the tests above to chew on. */
    private fun signatureOf(peaks: Int) = DecodedSignature(
        sampleRateHz = 16000,
        numberSamples = 4 * 16000,
        peaksByBand = mapOf(
            FrequencyBand.BAND_250_520 to (0 until peaks).map {
                FrequencyPeak(
                    fftPassNumber = it * 3,
                    peakMagnitude = 20000 + it,
                    correctedPeakFrequencyBin = 3000 + it
                )
            }
        )
    )
}
