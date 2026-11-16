package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicingGateTest {

    // Readings ~23 ms apart, as the detector produces them
    private val step = 23L
    private val onset = 3

    private fun pitch(timeMs: Long) = TimedPitch(timeMs, frequencyHz = 440f, confidence = 0.9f)

    private fun GateStep.times() = pitches.map { it.timeMs }

    private fun assertNothing(result: GateStep) {
        assertTrue(result.pitches.isEmpty())
        assertNull(result.silenceAtMs)
        assertFalse(result.resetTracker)
    }

    /** A gate with a confirmed note whose readings ran up to [lastMs]. */
    private fun soundingGate(lastMs: Long = 2 * step): VoicingGate {
        val gate = VoicingGate(graceFrames = 3)
        var t = lastMs - (onset - 1) * step
        repeat(onset) {
            gate.voiced(pitch(t), onset)
            t += step
        }
        return gate
    }

    @Test
    fun onset_publishesTheHeldBackReadingsInOrder() {
        val gate = VoicingGate(graceFrames = 3)

        assertNothing(gate.voiced(pitch(0), onset))
        assertNothing(gate.voiced(pitch(step), onset))
        assertEquals(listOf(0L, step, 2 * step), gate.voiced(pitch(2 * step), onset).times())
        // Once the note is going, each reading is published straight away
        assertEquals(listOf(3 * step), gate.voiced(pitch(3 * step), onset).times())
    }

    @Test
    fun blip_isDroppedAndResetsTheTracker() {
        val gate = VoicingGate(graceFrames = 3)
        gate.voiced(pitch(0), onset)
        gate.voiced(pitch(step), onset)

        val result = gate.unvoiced(2 * step)

        assertTrue(result.pitches.isEmpty())
        assertNull(result.silenceAtMs)
        assertTrue(result.resetTracker)
        // The next note has to be confirmed from scratch
        assertNothing(gate.voiced(pitch(3 * step), onset))
        assertNothing(gate.voiced(pitch(4 * step), onset))
        assertEquals(listOf(3 * step, 4 * step, 5 * step), gate.voiced(pitch(5 * step), onset).times())
    }

    @Test
    fun silenceWithNothingGoing_doesNothing() {
        assertNothing(VoicingGate(graceFrames = 3).unvoiced(0))
    }

    @Test
    fun shortDropout_isBridged() {
        val gate = soundingGate()

        assertNothing(gate.unvoiced(3 * step))
        assertNothing(gate.unvoiced(4 * step))
        // The note carries on without being confirmed again
        assertEquals(listOf(5 * step), gate.voiced(pitch(5 * step), onset).times())
    }

    @Test
    fun longGap_publishesOneSilenceAtTheFirstWeakReading() {
        val gate = soundingGate()

        assertNothing(gate.unvoiced(3 * step))
        assertNothing(gate.unvoiced(4 * step))
        assertNothing(gate.unvoiced(5 * step))
        val end = gate.unvoiced(6 * step)

        assertEquals(3 * step, end.silenceAtMs)
        assertTrue(end.resetTracker)
        assertTrue(end.pitches.isEmpty())
        assertNothing(gate.unvoiced(7 * step))
    }

    @Test
    fun graceStartsAgainAfterEachVoicedReading() {
        val gate = soundingGate()
        var t = 3 * step

        repeat(3) {
            repeat(3) {
                assertNothing(gate.unvoiced(t))
                t += step
            }
            assertEquals(listOf(t), gate.voiced(pitch(t), onset).times())
            t += step
        }
    }

    @Test
    fun shorterOnset_confirmsSooner() {
        val gate = VoicingGate(graceFrames = 3)

        assertNothing(gate.voiced(pitch(0), 2))
        assertEquals(listOf(0L, step), gate.voiced(pitch(step), 2).times())
    }

    @Test
    fun onsetOfOneReading_publishesStraightAway() {
        assertEquals(listOf(0L), VoicingGate(graceFrames = 3).voiced(pitch(0), 1).times())
    }
}
