package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PianoRollModelTest {

    @Test
    fun gapsOnTheGridBecomeRests() {
        val roll = listOf(RollNote(0, 4, 0), RollNote(6, 2, 4), RollNote(8, 8, 7))
        assertEquals(
            listOf(
                ExerciseNote(0, 1f),
                ExerciseNote(null, 0.5f),
                ExerciseNote(4, 0.5f),
                ExerciseNote(7, 2f)
            ),
            exerciseNotesOf(roll)
        )
    }

    @Test
    fun aRestBeforeTheFirstNoteIsKept() {
        assertEquals(
            listOf(ExerciseNote(null, 1f), ExerciseNote(0, 1f)),
            exerciseNotesOf(listOf(RollNote(4, 4, 0)))
        )
    }

    @Test
    fun everyBuiltInSurvivesTheGrid() {
        for (exercise in BUILT_IN_EXERCISES) {
            val notes = exerciseNotesOf(rollNotesOf(exercise.notes))
            // Rests after the last note mean nothing on the grid, so they're the only thing dropped
            assertEquals(exercise.name, exercise.notes.dropLastWhile { it.isRest }, notes)
        }
    }

    @Test
    fun aNewNoteStopsWhereTheNextOneStarts() {
        val roll = listOf(RollNote(0, 4, 0), RollNote(8, 4, 2))
        assertEquals(RollNote(4, 4, 5), roll.placing(4, 5, 16))
        assertEquals(RollNote(12, 2, 5), roll.placing(12, 5, 2))
        // Nor past the end of the grid
        assertEquals(MAX_PATTERN_STEPS - 1, roll.placing(MAX_PATTERN_STEPS - 1, 0, 16).end - 1)
    }

    @Test
    fun tapsSnapToTheGridButNotOverEarlierNotes() {
        val roll = listOf(RollNote(0, 5, 0))
        assertEquals(8, roll.startForTap(10, 4))
        assertEquals(10, roll.startForTap(10, 1))
        // The beat starts at 4, but the note before only finishes at 5
        assertEquals(5, roll.startForTap(6, 4))
        assertEquals(0, emptyList<RollNote>().startForTap(3, 4))
    }

    @Test
    fun aNoteDrawnOverAnotherCutsItShort() {
        val roll = listOf(RollNote(0, 8, 0), RollNote(8, 4, 2))
        val placed = roll.placing(4, 7, 4)
        assertEquals(listOf(RollNote(0, 4, 0), RollNote(4, 4, 7), RollNote(8, 4, 2)), roll.withNote(placed))
    }

    @Test
    fun movingOntoANoteReplacesIt() {
        val roll = listOf(RollNote(0, 4, 0), RollNote(4, 4, 2), RollNote(8, 4, 4))
        val (moved, landed) = roll.moving(RollNote(8, 4, 4), 4, 5)
        assertEquals(RollNote(4, 4, 5), landed)
        assertEquals(listOf(RollNote(0, 4, 0), RollNote(4, 4, 5)), moved)
    }

    @Test
    fun movesStayOnTheGrid() {
        val roll = listOf(RollNote(0, 4, 0))
        assertEquals(RollNote(0, 4, EDITOR_MAX_OFFSET), roll.moving(roll[0], -3, 99).second)
        assertEquals(MAX_PATTERN_STEPS, roll.moving(roll[0], 1000, 0).second.end)
    }

    @Test
    fun resizingStopsAtTheNextNoteAndTheLongestLength() {
        val roll = listOf(RollNote(0, 2, 0), RollNote(6, 2, 4))
        assertEquals(6, roll.resizing(roll[0], 12).second.length)
        assertEquals(1, roll.resizing(roll[0], 0).second.length)
        assertEquals(MAX_NOTE_STEPS, roll.resizing(roll[1], 40).second.length)
        assertEquals(listOf(RollNote(0, 6, 0), RollNote(6, 2, 4)), roll.resizing(roll[0], 12).first)
    }

    @Test
    fun cellsFindTheirNote() {
        val roll = listOf(RollNote(4, 4, 2))
        assertEquals(roll[0], roll.noteAt(7, 2))
        assertNull(roll.noteAt(8, 2))
        assertNull(roll.noteAt(5, 3))
        assertEquals(8, roll.endStep)
    }
}
