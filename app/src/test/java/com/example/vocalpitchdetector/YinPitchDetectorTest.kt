package com.example.vocalpitchdetector

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class YinPitchDetectorTest {

    @Test
    fun detects_440hz_sine() {
        val sampleRate = 44100
        val bufferSize = 2048
        val detector = YinPitchDetector(sampleRate = sampleRate, bufferSize = bufferSize)

        // generate sine wave at 440 Hz
        val freq = 440.0
        val buffer = FloatArray(bufferSize)
        for (i in 0 until bufferSize) {
            buffer[i] = (0.7 * sin(2.0 * PI * i * freq / sampleRate)).toFloat()
        }

        val (detectedFreq, confidence) = detector.getPitch(buffer, bufferSize)
        // small tolerance: algorithmic detectors might be a few Hz off depending on params
        assertTrue("confidence should be > 0.2 but was $confidence", confidence > 0.2f)
        assertTrue("freq should be close to 440Hz but was $detectedFreq", kotlin.math.abs(detectedFreq - 440f) < 5f)
    }
}
