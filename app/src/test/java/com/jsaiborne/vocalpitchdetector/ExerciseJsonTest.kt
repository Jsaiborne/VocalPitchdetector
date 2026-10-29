package com.jsaiborne.vocalpitchdetector

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun comfortableRangeTrimsTheTestedExtremes() {
        assertEquals(47..70, comfortableRange(45, 72))
        // Too narrow to trim: used as it is
        assertEquals(50..55, comfortableRange(50, 55))
        // Outside what the detector hears: clamped first
        assertEquals((EXERCISE_MIN_MIDI + 2)..(EXERCISE_MAX_MIDI - 2), comfortableRange(20, 100))
    }
}
