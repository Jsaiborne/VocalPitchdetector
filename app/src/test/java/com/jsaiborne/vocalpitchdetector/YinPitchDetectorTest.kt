package com.jsaiborne.vocalpitchdetector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YinPitchDetectorTest {

    @Test
    fun detects_440hz_sine() {
        val sampleRate = 44100
        val bufferSize = 2048
        val detector = YinPitchDetector(
            sampleRate = sampleRate,
            bufferSize = bufferSize
        )

        // generate sine wave at 440 Hz
        val freq = 440.0
        val buffer = FloatArray(bufferSize)
        for (i in 0 until bufferSize) {
            buffer[i] = (0.7 * sin(2.0 * PI * i * freq / sampleRate)).toFloat()
        }

        val result = detector.getPitch(buffer, bufferSize)

        // pitch must exist for a clean sine wave
        assertNotNull("pitchHz should not be null for a clean sine wave", result.pitchHz)

        val detectedFreq = result.pitchHz!!
        val confidence = (1.0 - result.cmndfMin).toFloat()

        // small tolerance: YIN may be a few Hz off depending on params
        assertTrue(
            "confidence should be > 0.2 but was $confidence",
            confidence > 0.2f
        )

        assertTrue(
            "freq should be close to 440Hz but was $detectedFreq",
            abs(detectedFreq - 440.0) < 5.0
        )
    }

    // --- Octave errors -----------------------------------------------------------------------

    private val rate = 44100
    private val window = 2048

    /** The detector as the app sets it up (threshold 0.25). */
    private fun appDetector() = YinPitchDetector(sampleRate = rate, bufferSize = window, threshold = 0.25f)

    /** A window of a tone at [freq] Hz with harmonic amplitudes [amps] (fundamental first). */
    private fun harmonicTone(freq: Double, amps: DoubleArray): FloatArray {
        val total = amps.sum()
        return FloatArray(window) { i ->
            var s = 0.0
            for (k in amps.indices) s += amps[k] * sin(2.0 * PI * (k + 1) * freq * i / rate)
            (0.5 * s / total).toFloat()
        }
    }

    private fun assertReadAt(freq: Double, amps: DoubleArray) {
        val hz = appDetector().getPitch(harmonicTone(freq, amps), window).pitchHz
        assertNotNull("no pitch for $freq Hz", hz)
        val semitonesOff = 12.0 * ln(hz!! / freq) / ln(2.0)
        assertTrue("$freq Hz was read as $hz Hz", abs(semitonesOff) < 0.5)
    }

    @Test
    fun weakOddHarmonics_highMaleNotes_areNotReadAnOctaveUp() {
        // An open vowel high in a man's range: a strong 2nd harmonic, weak fundamental and 3rd. The
        // sound repeats almost every half period, which used to read F4, G4 and A4 an octave high
        val amps = doubleArrayOf(0.1, 1.0, 0.1, 0.5, 0.05, 0.3)
        for (freq in listOf(349.23, 392.0, 440.0, 493.88)) assertReadAt(freq, amps)
    }

    @Test
    fun ordinaryVoices_stayOnTheirNote() {
        // Guards against the octave check pushing ordinary notes an octave down
        val amps = doubleArrayOf(1.0, 0.6, 0.4, 0.25, 0.15)
        for (freq in listOf(98.0, 130.81, 196.0, 261.63, 329.63, 440.0, 523.25, 659.25)) assertReadAt(freq, amps)
    }

    @Test
    fun highNotesWithVibrato_areNotReadAnOctaveDown() {
        // A soprano's top notes, with a singer's vibrato so the windows aren't perfectly periodic. A5 and
        // above used to be read an octave low: the dip at twice the period looked deeper than the true one
        val amps = doubleArrayOf(1.0, 0.6, 0.4, 0.25, 0.15)
        val vibratoCents = 25.0
        val vibratoHz = 5.5
        val detector = appDetector()
        for (freq in listOf(880.0, 987.77, 1046.5, 1174.66)) {
            var phase = 0.0
            repeat(8) { index ->
                var freqSum = 0.0
                val samples = FloatArray(window) { i ->
                    val seconds = (index * window + i).toDouble() / rate
                    val f = freq * 2.0.pow(vibratoCents / 1200.0 * sin(2.0 * PI * vibratoHz * seconds))
                    freqSum += f
                    phase += 2.0 * PI * f / rate
                    var s = 0.0
                    for (k in amps.indices) s += amps[k] * sin((k + 1) * phase)
                    (0.5 * s / amps.sum()).toFloat()
                }
                val sungHz = freqSum / window
                val hz = detector.getPitch(samples, window).pitchHz
                assertNotNull("no pitch for $freq Hz (window $index)", hz)
                val semitonesOff = 12.0 * ln(hz!! / sungHz) / ln(2.0)
                assertTrue("$freq Hz (window $index) was read as $hz Hz", abs(semitonesOff) < 0.5)
            }
        }
    }
}
