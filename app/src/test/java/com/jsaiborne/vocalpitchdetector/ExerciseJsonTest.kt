package com.jsaiborne.vocalpitchdetector

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExerciseJsonTest {

    @Test
    fun exerciseSurvivesARoundTrip() {
        val exercise = Exercise(
            id = "custom_1",
            name = "My warm-up",
            notes = listOf(ExerciseNote(0, 0.5f), ExerciseNote(null, 0.5f), ExerciseNote(-3, 2f)),
            description = "With a rest",
            bpm = 84f,
            breathBeats = 3f,
            editorRootMidi = 55
        )
        assertEquals(exercise, exerciseFromJson(JSONObject(exercise.toJson().toString())))
    }

    @Test
    fun builtInExercisesRoundTripAsUserCopies() {
        for (exercise in BUILT_IN_EXERCISES) {
            val copy = exerciseFromJson(exercise.toJson())
            assertFalse(copy.builtIn)
            assertEquals(exercise.copy(builtIn = false), copy)
        }
    }

    @Test
    fun formatVersionIsWritten() {
        assertEquals(EXERCISE_FORMAT_VERSION, BUILT_IN_EXERCISES.first().toJson().getInt("version"))
    }

    @Test
    fun optionalFieldsFallBackToDefaults() {
        val json = JSONObject(
            """{"id":"x","name":"Bare","notes":[{"offset":0,"beats":1},{"beats":1}]}"""
        )
        val exercise = exerciseFromJson(json)
        assertEquals(listOf(ExerciseNote(0, 1f), ExerciseNote(null, 1f)), exercise.notes)
        assertEquals(100f, exercise.bpm)
        assertEquals(2f, exercise.breathBeats)
        assertEquals("", exercise.description)
        assertEquals(DEFAULT_EDITOR_ROOT_MIDI, exercise.editorRootMidi)
    }

    @Test(expected = JSONException::class)
    fun aFileFromANewerFormatIsRefused() {
        exerciseFromJson(JSONObject("""{"version":99,"id":"x","name":"Later","notes":[{"offset":0,"beats":1}]}"""))
    }

    @Test
    fun valuesTheAppCantPlayAreBroughtIntoRange() {
        val json = JSONObject(
            """{"id":"x","name":"   ","bpm":0,"breathBeats":99,"editorRoot":5,"notes":[
                {"offset":0,"beats":0},{"offset":40,"beats":1},{"offset":-40,"beats":-2},{"offset":2,"beats":0.01}
            ]}"""
        )
        val exercise = exerciseFromJson(json)
        assertEquals("Untitled", exercise.name)
        // A tempo of 0 would make every beat last for ever
        assertEquals(MIN_EXERCISE_BPM, exercise.bpm)
        assertEquals(MAX_BREATH_BEATS, exercise.breathBeats)
        assertEquals(EDITOR_ROOT_RANGE.first, exercise.editorRootMidi)
        // Notes with no length are dropped; the rest are kept on the grid's rows and steps
        assertEquals(listOf(ExerciseNote(EDITOR_MAX_OFFSET, 1f), ExerciseNote(2, 0.25f)), exercise.notes)
    }

    @Test
    fun aPatternLongerThanTheGridIsCutOff() {
        val tooLong = Exercise(id = "x", name = "Long", notes = List(40) { ExerciseNote(0, 1f) })
        val read = exerciseFromJson(tooLong.toJson())
        assertEquals(MAX_PATTERN_STEPS / STEPS_PER_BEAT, read.notes.size)
    }

    @Test
    fun anExerciseWithNothingToSingIsRefusedUnlessItsADraft() {
        val empty = Exercise(id = "x", name = "Empty", notes = listOf(ExerciseNote(null, 1f)))
        assertTrue(exerciseFromJson(empty.toJson(), requireNotes = false).notes.all { it.isRest })
        try {
            exerciseFromJson(empty.toJson())
            fail("An exercise with only rests was accepted")
        } catch (expected: JSONException) {
            // Refused, as it should be
        }
    }

    @Test
    fun comfortableRangeTrimsTheTestedExtremes() {
        assertEquals(47..70, comfortableRange(45, 72))
        // Too narrow to trim: used as it is
        assertEquals(50..55, comfortableRange(50, 55))
        // Outside what the detector hears: clamped first
        assertEquals((EXERCISE_MIN_MIDI + 2)..(EXERCISE_MAX_MIDI - 2), comfortableRange(20, 100))
    }
}
