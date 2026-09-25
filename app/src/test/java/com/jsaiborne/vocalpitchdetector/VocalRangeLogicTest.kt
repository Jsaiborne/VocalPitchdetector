package com.jsaiborne.vocalpitchdetector

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun noteIsReportedOnceAfterTheHoldTime() {
        val tracker = RangeHoldTracker()
        assertTrue(feed(tracker, a4, 0L, 560L).isEmpty())
        assertEquals(listOf(69), feed(tracker, a4, 580L, 3000L))
    }

    @Test
    fun changingNoteRestartsTheHold() {
        val tracker = RangeHoldTracker()
        feed(tracker, a4, 0L, 400L)
        // A whole tone up: earlier progress must not count towards the new note
        assertTrue(feed(tracker, 493.88f, 420L, 1000L).isEmpty())
        assertEquals(listOf(71), feed(tracker, 493.88f, 1020L, 2000L))
    }

    @Test
    fun veryLowConfidenceNeverCounts() {
        val tracker = RangeHoldTracker()
        assertTrue(feed(tracker, a4, 0L, 3000L, confidence = 0.1f).isEmpty())
    }

    @Test
    fun weakButUsableConfidenceStillCounts() {
        val tracker = RangeHoldTracker()
        assertEquals(listOf(69), feed(tracker, a4, 0L, 2000L, confidence = 0.35f))
    }

    @Test
    fun wobbleWithinTheWindowStillCounts() {
        val tracker = RangeHoldTracker()
        val held = mutableListOf<Int>()
        var t = 0L
        // Alternates between A4 and ~47 cents sharp, like an unsteady voice
        while (t <= 2000L) {
            val freq = if ((t / 20L) % 2L == 0L) a4 else 452f
            tracker.update(freq, confident, t).heldMidi?.let { held.add(it) }
            t += 20L
        }
        assertEquals(listOf(69), held)
    }

    @Test
    fun fastGlideNeverCountsAsAHeldNote() {
        val tracker = RangeHoldTracker()
        val held = mutableListOf<Int>()
        var t = 0L
        var semitones = 0.0
        // 5 semitones per second: leaves the window every ~160 ms, well short of the hold time
        while (t <= 3000L) {
            val freq = (a4 * 2.0.pow(semitones / 12.0)).toFloat()
            tracker.update(freq, confident, t).heldMidi?.let { held.add(it) }
            semitones += 0.1
            t += 20L
        }
        assertTrue(held.isEmpty())
    }

    @Test
    fun shortDropoutDoesNotResetTheHold() {
        val tracker = RangeHoldTracker()
        val held = mutableListOf<Int>()
        held += feed(tracker, a4, 0L, 300L)
        held += feed(tracker, -1f, 320L, 500L) // 200 ms of silence, inside the 300 ms grace
        held += feed(tracker, a4, 520L, 1200L)
        assertEquals(listOf(69), held)
    }

    @Test
    fun longSilenceResetsTheHold() {
        val tracker = RangeHoldTracker()
        feed(tracker, a4, 0L, 400L)
        feed(tracker, -1f, 420L, 1000L) // well over the grace period
        val snapshot = tracker.update(a4, confident, 1020L)
        assertEquals(0f, snapshot.progress, 0.05f)
        assertNull(snapshot.heldMidi)
        assertFalse(snapshot.steady)
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
