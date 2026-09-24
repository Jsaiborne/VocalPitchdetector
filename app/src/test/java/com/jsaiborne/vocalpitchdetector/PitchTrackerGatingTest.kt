package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins down what [PitchTracker.processFrame] does with its (pitchHz, cmndfMin, rms) arguments:
 * a frame is only voiced when it is both clear (low cmndfMin) and loud enough (rms).
 * Note: this exercises the tracker itself; the call site in AudioRecordPitchDetector needs a
 * device/instrumented test because it depends on AudioRecord.
 */
class PitchTrackerGatingTest {

    private fun newTracker() = PitchTracker(
        sampleRate = 44100,
        hopSize = 512,
        energyThreshold = 1e-4,
        confidenceThreshold = 0.12
    )

    @Test
    fun clearLoudFrame_isAccepted() {
        val hz = newTracker().processFrame(pitchHz = 440.0, cmndfMin = 0.05, rms = 0.3)

        assertNotNull(hz)
        assertEquals(440.0, hz!!, 0.5)
    }

    @Test
    fun lowClarityFrame_isRejected() {
        // cmndfMin 0.95 => clarity 0.05, below the 0.12 confidence threshold
        val hz = newTracker().processFrame(pitchHz = 440.0, cmndfMin = 0.95, rms = 0.3)

        assertNull(hz)
    }

    @Test
    fun silentFrame_isRejected() {
        // Clear pitch but no energy: must not be treated as voiced
        val hz = newTracker().processFrame(pitchHz = 440.0, cmndfMin = 0.05, rms = 0.0)

        assertNull(hz)
    }
}
