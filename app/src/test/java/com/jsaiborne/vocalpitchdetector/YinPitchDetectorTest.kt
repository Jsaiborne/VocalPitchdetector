package com.jsaiborne.vocalpitchdetector

import kotlin.math.PI
import kotlin.math.abs
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
}
