package com.jsaiborne.vocalpitchdetector

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                val ratio = outHz / prevHz
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

    @Test
    fun reset_startsTheNextNoteAfresh() {
        val tracker = PitchTracker(
            sampleRate = 44100,
            hopSize = 1024,
            smoothingAlpha = 0.18,
            energyThreshold = 1e-4,
            confidenceThreshold = 0.12
        )
        repeat(5) { tracker.processFrame(pitchHz = 440.0, cmndfMin = 0.05, rms = 0.3) }

        tracker.reset()
        // Half a semitone up: without the reset, the median would still give 440 Hz
        val hz = tracker.processFrame(pitchHz = 452.9, cmndfMin = 0.05, rms = 0.3)

        assertNotNull(hz)
        assertEquals(452.9, hz!!, 0.01)
    }

    // --- Octave flips --------------------------------------------------------------------------

    /** Set up as the detector uses it: a reading every ~23 ms, light smoothing, a 4-reading octave hold. */
    private fun liveTracker() = PitchTracker(
        sampleRate = 44100,
        hopSize = 1024,
        medianWindow = 3,
        smoothingAlpha = 0.6,
        octaveHoldFrames = 4,
        energyThreshold = 1e-4,
        confidenceThreshold = 0.12
    )

    private fun PitchTracker.feed(hz: Double): Double = processFrame(pitchHz = hz, cmndfMin = 0.05, rms = 0.3)!!

    private fun semitonesBetween(hz: Double, referenceHz: Double) = 12.0 * ln(hz / referenceHz) / ln(2.0)

    @Test
    fun briefOctaveFlip_staysOnTheNote() {
        val tracker = liveTracker()
        repeat(10) { tracker.feed(440.0) }

        val during = List(3) { tracker.feed(880.0) }
        val after = tracker.feed(440.0)

        for (hz in during + after) assertEquals(0.0, semitonesBetween(hz, 440.0), 0.5)
    }

    @Test
    fun realOctaveLeap_isTakenAfterTheHoldAndSticks() {
        val tracker = liveTracker()
        repeat(10) { tracker.feed(440.0) }

        val outputs = List(12) { tracker.feed(880.0) }

        // Held back at first, then taken as a leap, and it stays up
        assertEquals(0.0, semitonesBetween(outputs.first(), 440.0), 0.5)
        for (hz in outputs.takeLast(6)) assertEquals(0.0, semitonesBetween(hz, 880.0), 0.5)
    }

    @Test
    fun octaveDropout_isFoldedUpToo() {
        val tracker = liveTracker()
        repeat(10) { tracker.feed(440.0) }

        for (hz in List(2) { tracker.feed(220.0) }) assertEquals(0.0, semitonesBetween(hz, 440.0), 0.5)
    }

    @Test
    fun noFoldingAfterReset() {
        val tracker = liveTracker()
        repeat(10) { tracker.feed(440.0) }

        tracker.reset()

        assertEquals(0.0, semitonesBetween(tracker.feed(880.0), 880.0), 0.5)
    }
}
