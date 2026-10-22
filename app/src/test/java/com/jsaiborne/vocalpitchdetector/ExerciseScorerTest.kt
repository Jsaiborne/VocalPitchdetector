package com.jsaiborne.vocalpitchdetector

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseScorerTest {

    /** Two one-second notes, sung twice (roots 60 and 61), alone after the piano plays them. */
    private val schedule = buildSchedule(
        exercise = Exercise(
            id = "test",
            name = "Test",
            notes = listOf(ExerciseNote(0, 1f), ExerciseNote(4, 1f)),
            bpm = 60f
        ),
        lowMidi = 60,
        highMidi = 65,
        direction = Direction.UP,
        mode = GuideMode.CALL_AND_RESPONSE,
        startMs = 0L
    )

    private fun shifted(midi: Int, cents: Double): Float = (midiToFreq(midi) * 2.0.pow(cents / 1200.0)).toFloat()

    /** A frame every 20 ms over the whole run; [sing] gives the pitch for a moment inside a sung note. */
    private fun frames(sing: (TargetEvent, Long) -> Float): List<PitchFrame> =
        (schedule.startMs until schedule.endMs step SCORE_FRAME_MS).map { t ->
            val event = schedule.scoredEvents.firstOrNull { t >= it.startMs && t < it.endMs }
            PitchFrame(t, if (event == null) -1f else sing(event, t))
        }

    @Test
    fun singingEveryTargetScoresFull() {
        val result = scoreFrames(frames { e, _ -> midiToFreq(e.midi).toFloat() }, schedule, DEFAULT_TOLERANCE_CENTS)
        assertTrue(result.frames > 0)
        assertEquals(100, result.percent)
        assertEquals(0f, result.meanCentsError!!, 0.01f)
    }

    @Test
    fun silenceScoresZero() {
        val result = scoreFrames(frames { _, _ -> -1f }, schedule, DEFAULT_TOLERANCE_CENTS)
        assertTrue(result.frames > 0)
        assertEquals(0, result.percent)
        assertNull(result.meanCentsError)
    }

    @Test
    fun noFramesMeansNothingScored() {
        val result = scoreFrames(emptyList(), schedule, DEFAULT_TOLERANCE_CENTS)
        assertEquals(0, result.frames)
        assertEquals(0, result.percent)
    }

    @Test
    fun aSemitoneFlatMissesAndShowsAFlatTendency() {
        val result = scoreFrames(frames { e, _ -> midiToFreq(e.midi - 1).toFloat() }, schedule, DEFAULT_TOLERANCE_CENTS)
        assertEquals(0, result.percent)
        assertEquals(-100f, result.meanCentsError!!, 0.5f)
        assertTrue(tendencyText(result.meanCentsError)!!.contains("flat"))
    }

    @Test
    fun strictToleranceIsTighter() {
        val thirtySharp = frames { e, _ -> shifted(e.midi, 30.0) }
        assertEquals(100, scoreFrames(thirtySharp, schedule, DEFAULT_TOLERANCE_CENTS).percent)
        assertEquals(0, scoreFrames(thirtySharp, schedule, STRICT_TOLERANCE_CENTS).percent)
        assertTrue(tendencyText(30f)!!.contains("sharp"))
    }

    @Test
    fun theStartOfEachNoteIsNotScored() {
        // Way off for the first 100 ms of every note while finding the pitch, then spot on
        val scooping = frames { e, t ->
            if (t - e.startMs < 100) shifted(e.midi, -300.0) else midiToFreq(e.midi).toFloat()
        }
        assertEquals(100, scoreFrames(scooping, schedule, DEFAULT_TOLERANCE_CENTS).percent)
    }

    @Test
    fun eachRepetitionIsScoredSeparately() {
        // In tune for the first repetition, silent for the second
        val result = scoreFrames(
            frames { e, _ -> if (e.repIndex == 0) midiToFreq(e.midi).toFloat() else -1f },
            schedule,
            DEFAULT_TOLERANCE_CENTS
        )
        assertEquals(listOf(60, 61), result.reps.map { it.rootMidi })
        assertEquals(1f, result.reps[0].accuracy, 0.001f)
        assertEquals(0f, result.reps[1].accuracy, 0.001f)
        assertNull(result.reps[1].meanCentsError)
        assertEquals(50, result.percent)
    }

    @Test
    fun theDemonstrationIsNeverScored() {
        // The mic hearing the piano while it plays mustn't count; only the singer's turn does
        val hearsPianoOnly = (schedule.startMs until schedule.endMs step SCORE_FRAME_MS).map { t ->
            val guide = schedule.events.firstOrNull { it.kind == TargetKind.GUIDE && t >= it.startMs && t < it.endMs }
            PitchFrame(t, guide?.let { midiToFreq(it.midi).toFloat() } ?: -1f)
        }
        assertEquals(0, scoreFrames(hearsPianoOnly, schedule, DEFAULT_TOLERANCE_CENTS).percent)
    }

    @Test
    fun centredSingingHasNoTendencyToReport() {
        assertNotNull(tendencyText(3f))
        assertTrue(tendencyText(3f)!!.contains("centred"))
        assertNull(tendencyText(null))
    }
}
