package com.example.vocalpitchdetector

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.junit.Test

class PitchTrackerTest {

    @Suppress("LongMethod")
    @Test
    fun phraseEndingDoesNotSpike() {
        val sampleRate = 44100
        val bufferSize = 2048
        val hopSize = 512

        val toneFreq = 440.0
        val toneAmp = 0.6
        val holdSec = 1.5
        val decaySec = 0.5
        val tailSilenceSec = 1.0
        val totalSec = holdSec + decaySec + tailSilenceSec
        val totalSamples = (totalSec * sampleRate).toInt()

        val yin = YinPitchDetector(
            sampleRate = sampleRate,
            bufferSize = bufferSize
        )

        val tracker = PitchTracker(
            sampleRate = sampleRate,
            hopSize = hopSize,
            hangoverMs = 200,
            medianWindow = 5,
            smoothingAlpha = 0.18,
            energyThreshold = 1e-4,
            confidenceThreshold = 0.12
        )

        val samples = DoubleArray(totalSamples) { n ->
            val t = n.toDouble() / sampleRate
            val amp = when {
                t < holdSec -> toneAmp
                t < holdSec + decaySec ->
                    toneAmp * (1.0 - (t - holdSec) / decaySec)
                else -> 0.0
            }
            amp * sin(2.0 * Math.PI * toneFreq * t)
        }

        val window = FloatArray(bufferSize)
        var readPos = 0
        var prevHz: Double? = null
        var maxSemitoneJump = 0.0

        while (readPos < totalSamples) {
            val toCopy = min(hopSize, totalSamples - readPos)
            System.arraycopy(window, toCopy, window, 0, bufferSize - toCopy)
            for (i in 0 until toCopy) {
                window[bufferSize - toCopy + i] = samples[readPos + i].toFloat()
            }

            val yinRes = yin.getPitch(window, bufferSize)
            val outHz = tracker.processFrame(
                yinRes.pitchHz,
                yinRes.cmndfMin,
                yinRes.rms
            )

            if (outHz != null && prevHz != null) {
                val ratio = outHz / prevHz!!
                if (ratio > 0) {
                    val semis = abs(12.0 * ln(ratio) / ln(2.0))
                    maxSemitoneJump = max(maxSemitoneJump, semis)
                }
            }

            if (outHz != null) prevHz = outHz
            readPos += hopSize
        }

        // Assert: no wild jumps at phrase end
        assert(maxSemitoneJump < 6.0) {
            "Detected pitch spike: $maxSemitoneJump semitones"
        }
    }
}
