package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocalRangeLogicTest {

    private val a4 = 440f // MIDI 69 exactly
    private val confident = 0.9f

    /** Feeds [frequencyHz] every [stepMs] from [fromMs] to [toMs]; returns every note reported as held. */
    private fun feed(
        tracker: RangeHoldTracker,
        frequencyHz: Float,
        fromMs: Long,
        toMs: Long,
        confidence: Float = confident,
        stepMs: Long = 20L
    ): List<Int> {
        val held = mutableListOf<Int>()
        var t = fromMs
        while (t <= toMs) {
            tracker.update(frequencyHz, confidence, t).heldMidi?.let { held.add(it) }
            t += stepMs
        }
        return held
    }

    @Test
    fun noteIsReportedOnceAfterOneSecond() {
        val tracker = RangeHoldTracker()
        assertTrue(feed(tracker, a4, 0L, 900L).isEmpty())
        assertEquals(listOf(69), feed(tracker, a4, 920L, 3000L))
    }

    @Test
    fun changingNoteRestartsTheHold() {
        val tracker = RangeHoldTracker()
        feed(tracker, a4, 0L, 800L)
        // Jump a whole tone up: the earlier progress must not count towards the new note
        val heldAfterJump = feed(tracker, 493.88f, 820L, 1500L)
        assertTrue(heldAfterJump.isEmpty())
        assertEquals(listOf(71), feed(tracker, 493.88f, 1520L, 2000L))
    }

    @Test
    fun lowConfidenceNeverCounts() {
        val tracker = RangeHoldTracker()
        assertTrue(feed(tracker, a4, 0L, 3000L, confidence = 0.3f).isEmpty())
    }

    @Test
    fun outOfTuneNoteIsNotAccepted() {
        val tracker = RangeHoldTracker()
        // ~47 cents sharp of A4 is outside the 35 cent tolerance
        assertTrue(feed(tracker, 452f, 0L, 3000L).isEmpty())
        val snapshot = tracker.update(452f, confident, 3020L)
        assertFalse(snapshot.steady)
        assertNotNull(snapshot.noteMidi)
    }

    @Test
    fun shortDropoutDoesNotResetTheHold() {
        val tracker = RangeHoldTracker()
        val held = mutableListOf<Int>()
        held += feed(tracker, a4, 0L, 500L)
        held += feed(tracker, -1f, 520L, 600L) // 100 ms of silence, inside the 150 ms grace
        held += feed(tracker, a4, 620L, 1200L)
        assertEquals(listOf(69), held)
    }

    @Test
    fun longSilenceResetsTheHold() {
        val tracker = RangeHoldTracker()
        feed(tracker, a4, 0L, 800L)
        feed(tracker, -1f, 820L, 1200L) // well over the grace period
        val snapshot = tracker.update(a4, confident, 1220L)
        assertEquals(0f, snapshot.progress, 0.05f)
        assertNull(snapshot.heldMidi)
    }

    @Test
    fun voiceTypeIsEstimatedFromTheCentreOfTheRange() {
        assertEquals("Tenor", estimateVoiceType(48, 72).label) // C3 to C5
        assertEquals("Bass", estimateVoiceType(40, 64).label) // E2 to E4
        assertEquals("Soprano", estimateVoiceType(60, 84).label) // C4 to C6
    }

    @Test
    fun spanTextDescribesOctavesAndSemitones() {
        assertEquals("2 octaves and 3 semitones (27 semitones)", rangeSpanText(48, 75))
        assertEquals("1 octave (12 semitones)", rangeSpanText(60, 72))
        assertEquals("5 semitones (5 semitones)", rangeSpanText(60, 65))
    }
}
