package com.jsaiborne.vocalpitchdetector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphNotesTest {

    // --- Pitch axis ---

    // A white key 12 long makes a semitone exactly 7 long
    private val key = 12f
    private val axis = KeyboardPitchAxis(startMidi = 24, keySize = key)

    @Test
    fun pitchAxis_spacesEverySemitoneEqually() {
        assertEquals(7f, axis.semitoneSize, 1e-4f)
        for (m in 24 until 84) {
            assertEquals(7f, axis.centreOf((m + 1).toFloat()) - axis.centreOf(m.toFloat()), 1e-3f)
        }
        // Fractional notes sit in between
        assertEquals(axis.centreOf(60f) + 3.5f, axis.centreOf(60.5f), 1e-3f)
    }

    @Test
    fun pitchAxis_octavesLineUpWithTheWhiteKeys() {
        // C1's lane starts at the edge of the keyboard, and each C lane starts 7 white keys after the last
        assertEquals(0f, axis.centreOf(24f) - axis.semitoneSize / 2f, 1e-3f)
        assertEquals(7 * key, axis.centreOf(36f) - axis.centreOf(24f), 1e-3f)
        assertEquals(35 * key, axis.centreOf(84f) - axis.semitoneSize / 2f, 1e-3f)
    }

    @Test
    fun pitchAxis_putsEachNoteOverItsOwnKey() {
        var whitesSoFar = 0
        for (m in 24..84) {
            val centre = axis.centreOf(m.toFloat())
            val name = midiToNoteName(m)
            if (isWhiteKey(m)) {
                val inside = centre > whitesSoFar * key && centre < (whitesSoFar + 1) * key
                assertTrue("$name lies inside its white key", inside)
                whitesSoFar++
            } else {
                // Near the join of the two white keys either side, as on a real piano
                assertTrue("$name sits at the join", abs(centre - whitesSoFar * key) < axis.semitoneSize / 2f)
            }
        }
    }

    @Test
    fun pitchAxis_worksForAKeyboardThatDoesNotStartOnC() {
        // Starting on D1 just moves everything one white key down
        val fromD = KeyboardPitchAxis(startMidi = 26, keySize = key)
        for (m in 26..84) {
            assertEquals(axis.centreOf(m.toFloat()) - key, fromD.centreOf(m.toFloat()), 1e-3f)
        }
        val dCentre = fromD.centreOf(26f)
        assertTrue(dCentre > 0f && dCentre < key)
    }

    // --- Held notes ---

    private data class P(val t: Long, val m: Float)

    private fun notes(points: List<P>) = segmentNotes(points, { it.t }, { it.m })

    /** Points every 46 ms (one detector frame) from [fromMs] until [toMs], with the pitch at each time. */
    private fun frames(fromMs: Long, toMs: Long, pitch: (Long) -> Float): List<P> =
        (fromMs..toMs step 46L).map { P(it, pitch(it)) }

    @Test
    fun segmentNotes_aHeldNoteIsOneBlock_evenWhileStillGoing() {
        val points = frames(0, 920) { 60.05f }

        val result = notes(points)

        assertEquals(1, result.size)
        assertEquals(60, result[0].midi)
        assertEquals(0L, result[0].startMs)
        assertEquals(points.last().t, result[0].endMs)
        assertEquals(5f, result[0].cents, 0.01f)
    }

    @Test
    fun segmentNotes_vibratoStaysOneNote() {
        // ±40 cents at 5.5 Hz
        val points = frames(0, 1500) { t -> 60f + 0.4f * sin(2.0 * PI * 5.5 * t / 1000.0).toFloat() }

        val result = notes(points)

        assertEquals(1, result.size)
        assertEquals(60, result[0].midi)
        assertTrue("average stays near the centre: ${result[0].cents}", abs(result[0].cents) < 8f)
    }

    @Test
    fun segmentNotes_aQuickNoteIsNotShown() {
        val points = frames(0, 92) { 64f } + P(138, Float.NaN)

        assertTrue(notes(points).isEmpty())
    }

    @Test
    fun segmentNotes_aGlideOnlyShowsTheNoteItLandsOn() {
        val points = listOf(P(0, 60f), P(46, 61f)) + frames(92, 600) { 62f }

        val result = notes(points)

        assertEquals(listOf(62), result.map { it.midi })
        assertEquals(92L, result[0].startMs)
    }

    @Test
    fun segmentNotes_silenceSplitsANote() {
        val withSilence = frames(0, 460) { 60f } + P(506, Float.NaN) + frames(552, 1012) { 60f }
        // Recordings have gaps instead of silent points
        val withGap = frames(0, 460) { 60f } + frames(800, 1260) { 60f }

        assertEquals(2, notes(withSilence).size)
        assertEquals(2, notes(withGap).size)
    }

    @Test
    fun segmentNotes_twoNotesGetTheirOwnTuning() {
        val points = frames(0, 460) { 60.1f } + frames(506, 966) { 62.2f }

        val result = notes(points)

        assertEquals(listOf(60, 62), result.map { it.midi })
        assertEquals(10f, result[0].cents, 0.01f)
        assertEquals(20f, result[1].cents, 0.01f)
    }

    @Test
    fun segmentNotes_leavesTheScoopIntoANoteOutOfItsTuning() {
        val points = listOf(P(0, 59.7f), P(46, 59.85f)) + frames(92, 600) { 60f }

        val result = notes(points)

        assertEquals(1, result.size)
        assertEquals(0L, result[0].startMs)
        assertEquals(0f, result[0].cents, 0.01f)
    }

    // --- Tuning ---

    @Test
    fun tuningBand_usesTheCentsMeterThresholds() {
        assertEquals(TuningBand.IN_TUNE, tuningBand(0f))
        assertEquals(TuningBand.IN_TUNE, tuningBand(10f))
        assertEquals(TuningBand.IN_TUNE, tuningBand(-10f))
        assertEquals(TuningBand.CLOSE, tuningBand(10.5f))
        assertEquals(TuningBand.CLOSE, tuningBand(-25f))
        assertEquals(TuningBand.OFF, tuningBand(25.1f))
        assertEquals(TuningBand.OFF, tuningBand(-48f))
    }
}
