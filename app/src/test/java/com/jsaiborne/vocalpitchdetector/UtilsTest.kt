package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {

    @Test
    fun freqToMidi_and_midiToNoteName_are_consistent() {
        val midiA4 = freqToMidi(440.0)
        // A4 should map to MIDI 69
        assertEquals(69.0, midiA4, 1e-6)

        val name = midiToNoteName(69)
        assertEquals("A4", name)
    }

    @Test
    fun centsDifference_zero_for_exact_reference() {
        val cents = centsDifference(440.0, 69)
        assertEquals(0.0, cents, 1e-6)
    }

    @Test
    fun dbToRms_and_rmsToDb_roundtrip() {
        val db = -6f
        val rms = dbToRms(db)
        val dbBack = rmsToDb(rms)
        // Allow small rounding differences
        assertEquals(db, dbBack, 0.5f)
    }

    @Test
    fun midiToNoteName_handlesOctaveBoundariesAndNegatives() {
        assertEquals("C4", midiToNoteName(60))
        assertEquals("B3", midiToNoteName(59))
        assertEquals("C-1", midiToNoteName(0))
        assertEquals("B-2", midiToNoteName(-1)) // used to throw ArrayIndexOutOfBounds
    }

    @Test
    fun midiToFreq_roundTripsThroughFreqToMidi() {
        assertEquals(440.0, midiToFreq(69), 1e-9)
        for (midi in 24..84) {
            assertEquals(midi.toDouble(), freqToMidi(midiToFreq(midi)), 1e-9)
        }
    }

    @Test
    fun rmsToDb_floor_for_zero() {
        val db = rmsToDb(0f)
        assertTrue(db <= -80f) // our function floors to minDb
    }
}
