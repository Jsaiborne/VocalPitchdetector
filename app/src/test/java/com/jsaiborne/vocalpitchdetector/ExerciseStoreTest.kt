package com.jsaiborne.vocalpitchdetector

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExerciseStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun exercise(name: String) = Exercise(
        id = newExerciseId(),
        name = name,
        notes = listOf(ExerciseNote(0, 1f), ExerciseNote(null, 0.5f), ExerciseNote(7, 2f)),
        bpm = 90f
    )

    @Test
    fun savedExercisesComeBack() {
        val store = ExerciseStore(File(folder.root, EXERCISES_DIR))
        val first = exercise("First")
        val second = exercise("Second")
        store.save(first)
        store.save(second)
        assertEquals(setOf(first, second), store.loadAll().map { it.exercise }.toSet())
    }

    @Test
    fun savingAgainReplacesTheOldVersion() {
        val store = ExerciseStore(folder.root)
        val original = exercise("Draft")
        store.save(original)
        store.save(original.copy(name = "Final", notes = listOf(ExerciseNote(0, 4f))))
        val all = store.loadAll()
        assertEquals(1, all.size)
        assertEquals("Final", all[0].exercise.name)
        // No temporary file left behind
        assertEquals(listOf("${original.id}.json"), folder.root.list()!!.toList())
    }

    @Test
    fun deletedExercisesAreGone() {
        val store = ExerciseStore(folder.root)
        val doomed = exercise("Doomed")
        store.save(doomed)
        assertTrue(store.delete(doomed.id))
        assertFalse(store.delete(doomed.id))
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun unreadableFilesAreSkipped() {
        val store = ExerciseStore(folder.root)
        val good = exercise("Good")
        store.save(good)
        File(folder.root, "broken.json").writeText("{ not json")
        // Its id doesn't match its file name, so it couldn't be found again to save or delete
        File(folder.root, "misnamed.json").writeText(exercise("Misnamed").toJson().toString())
        assertEquals(listOf(good), store.loadAll().map { it.exercise })
    }

    @Test
    fun aMissingFolderIsAnEmptyLibrary() {
        assertTrue(ExerciseStore(File(folder.root, "nowhere")).loadAll().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun idsThatArentFileSafeAreRefused() {
        ExerciseStore(folder.root).save(exercise("Sneaky").copy(id = "../outside"))
    }
}
