package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseScheduleTest {

    private val fiveTone = BUILT_IN_EXERCISES.first { it.id == "builtin_five_tone" }

    /** Two one-beat notes a major third apart, at one beat per second, so times are easy to read. */
    private val twoNotes = Exercise(
        id = "test",
        name = "Test",
        notes = listOf(ExerciseNote(0, 1f), ExerciseNote(4, 1f)),
        bpm = 60f,
        breathBeats = 2f
    )

    @Test
    fun rootsStepUpASemitoneAndKeepEveryNoteInRange() {
        // The 5-tone scale spans a fifth (7 semitones), so C3..C4 fits roots C3..F3
        assertEquals((48..53).toList(), exerciseRoots(fiveTone, 48, 60, Direction.UP))
    }

    @Test
    fun rootsCanGoDownOrUpThenDown() {
        assertEquals((53 downTo 48).toList(), exerciseRoots(fiveTone, 48, 60, Direction.DOWN))
        // The top root is sung once, not twice
        assertEquals(
            (48..53).toList() + (52 downTo 48).toList(),
            exerciseRoots(fiveTone, 48, 60, Direction.UP_THEN_DOWN)
        )
    }

    @Test
    fun patternBelowTheRootStillFitsTheRange() {
        val dipsBelow = twoNotes.copy(notes = listOf(ExerciseNote(-2, 1f), ExerciseNote(5, 1f)))
        assertEquals((50..55).toList(), exerciseRoots(dipsBelow, 48, 60, Direction.UP))
    }

    @Test
    fun rangeNarrowerThanThePatternIsSungOnceInTheMiddle() {
        val roots = exerciseRoots(fiveTone, 50, 54, Direction.UP_THEN_DOWN)
        assertEquals(1, roots.size)
        // Pattern 48..55 is centred on 51.5, as close as it gets to the range centre of 52
        assertEquals(48, roots.first())
    }

    @Test
    fun callAndResponsePlaysThenLeavesTheSingerToSingBack() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
        val expected = listOf(
            TargetEvent(0, 1000, 60, 0, TargetKind.CUE),
            TargetEvent(2000, 3000, 60, 0, TargetKind.GUIDE),
            TargetEvent(3000, 4000, 64, 0, TargetKind.GUIDE),
            TargetEvent(5000, 6000, 60, 0, TargetKind.SING),
            TargetEvent(6000, 7000, 64, 0, TargetKind.SING),
            TargetEvent(9000, 10000, 61, 1, TargetKind.GUIDE),
            TargetEvent(10000, 11000, 65, 1, TargetKind.GUIDE),
            TargetEvent(12000, 13000, 61, 1, TargetKind.SING),
            TargetEvent(13000, 14000, 65, 1, TargetKind.SING)
        )
        assertEquals(expected, s.events)
        assertEquals(listOf(RepSpan(0, 60, 2000, 5000, 7000), RepSpan(1, 61, 9000, 12000, 14000)), s.reps)
        assertEquals(14000L, s.endMs)
    }

    @Test
    fun singAlongScoresWhilePlayingAndCuesTheNextKey() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.SING_ALONG, startMs = 0L)
        val expected = listOf(
            TargetEvent(0, 1000, 60, 0, TargetKind.CUE),
            TargetEvent(2000, 3000, 60, 0, TargetKind.GUIDE_AND_SING),
            TargetEvent(3000, 4000, 64, 0, TargetKind.GUIDE_AND_SING),
            // The next root sounds in the last beat of the breath
            TargetEvent(5000, 6000, 61, 1, TargetKind.CUE),
            TargetEvent(6000, 7000, 61, 1, TargetKind.GUIDE_AND_SING),
            TargetEvent(7000, 8000, 65, 1, TargetKind.GUIDE_AND_SING)
        )
        assertEquals(expected, s.events)
        assertNull(s.reps.first().listenStartMs)
        assertEquals(8000L, s.endMs)
    }

    @Test
    fun restsTakeTimeButAddNoTarget() {
        val staccato = twoNotes.copy(
            notes = listOf(ExerciseNote(0, 0.5f), ExerciseNote(null, 0.5f), ExerciseNote(4, 0.5f))
        )
        val s = buildSchedule(staccato, 60, 64, Direction.UP, GuideMode.SING_ALONG, startMs = 0L)
        val sung = s.events.filter { it.kind.scored }
        assertEquals(listOf(2000L to 2500L, 3000L to 3500L), sung.map { it.startMs to it.endMs })
    }

    @Test
    fun startTimeShiftsEverything() {
        val atZero = buildSchedule(fiveTone, 48, 60, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
        val later = buildSchedule(fiveTone, 48, 60, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 5_000L)
        assertEquals(atZero.events.map { it.startMs + 5_000L }, later.events.map { it.startMs })
        assertEquals(atZero.endMs + 5_000L, later.endMs)
    }

    @Test
    fun phasesFollowTheTimeline() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
        assertEquals(ExercisePhase.COUNT_IN, s.phaseAt(500))
        assertEquals(ExercisePhase.LISTEN, s.phaseAt(2500))
        // The pause before the singer's turn still belongs to listening
        assertEquals(ExercisePhase.LISTEN, s.phaseAt(4500))
        assertEquals(ExercisePhase.SING, s.phaseAt(5500))
        assertEquals(ExercisePhase.BREATH, s.phaseAt(8000))
        assertEquals(ExercisePhase.DONE, s.phaseAt(14000))
    }

    @Test
    fun repAtGivesTheCurrentOrNextRepetition() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
        assertEquals(0, s.repAt(6000)?.index)
        // During the breath, the next repetition is the one to show
        assertEquals(1, s.repAt(8000)?.index)
        assertNull(s.repAt(14000))
    }

    @Test
    fun restartingARepetitionMovesItAndEverythingAfterIt() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
        // Paused during the second repetition's demonstration, resumed so it starts again at 20 s
        val restarted = s.restartFrom(1, 20_000L)
        assertEquals(s.events.filter { it.repIndex == 0 }, restarted.events.filter { it.repIndex == 0 })
        assertEquals(
            listOf(20_000L, 21_000L, 23_000L, 24_000L),
            restarted.events.filter { it.repIndex == 1 }.map { it.startMs }
        )
        assertEquals(RepSpan(1, 61, 20_000, 23_000, 25_000), restarted.reps[1])
        assertEquals(s.reps[0], restarted.reps[0])
        assertEquals(25_000L, restarted.endMs)
    }

    @Test
    fun restartingTheFirstRepetitionRepeatsTheCountIn() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.SING_ALONG, startMs = 0L)
        val restarted = s.restartFrom(0, 10_000L)
        // The count-in comes two beats before the repetition, as it did at the start
        assertEquals(TargetEvent(8_000, 9_000, 60, 0, TargetKind.CUE), restarted.events.first())
        assertEquals(10_000L, restarted.reps[0].singStartMs)
    }

    @Test
    fun truncatingKeepsOnlyWhatHadHappened() {
        val s = buildSchedule(twoNotes, 60, 65, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
        // Stopped halfway through the second note of the first sung repetition
        val cut = s.truncatedAt(6_500L)
        assertEquals(listOf(RepSpan(0, 60, 2000, 5000, 6500)), cut.reps)
        assertEquals(TargetEvent(6000, 6500, 64, 0, TargetKind.SING), cut.events.last())
        assertEquals(6_500L, cut.endMs)
        // Stopped during the demonstration: nothing sung yet, so no repetition to score
        assertTrue(s.truncatedAt(3_000L).reps.isEmpty())
    }

    @Test
    fun clockStandsStillWhilePaused() {
        var wall = 1_000L
        val clock = ExerciseClock { wall }
        wall = 2_000L
        assertEquals(2_000L, clock.now())
        clock.pause()
        wall = 5_000L
        assertEquals(2_000L, clock.now())
        assertTrue(clock.isPaused)
        clock.resume()
        wall = 6_000L
        // Carries on from where it stopped, three seconds of pause later
        assertEquals(3_000L, clock.now())
    }

    @Test
    fun builtInExercisesAreUsable() {
        assertEquals(BUILT_IN_EXERCISES.size, BUILT_IN_EXERCISES.map { it.id }.toSet().size)
        for (exercise in BUILT_IN_EXERCISES) {
            assertTrue(exercise.name, exercise.notes.any { !it.isRest })
            assertTrue(exercise.name, exercise.builtIn)
            val s = buildSchedule(exercise, 48, 67, Direction.UP, GuideMode.CALL_AND_RESPONSE, startMs = 0L)
            assertTrue(exercise.name, s.scoredEvents.all { it.midi in 48..67 })
        }
    }
}
