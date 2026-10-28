package com.jsaiborne.vocalpitchdetector

import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/** Folder under filesDir for the user's own exercises. It's included in Auto Backup. */
internal const val EXERCISES_DIR = "exercises"

private const val USER_ID_PREFIX = "user_"
private val VALID_ID = Regex("[A-Za-z0-9_-]+")

/** An exercise read from disk, with when it was last saved. */
internal data class SavedExercise(val exercise: Exercise, val savedAtMs: Long)

/** A fresh id for an exercise the user makes. It's also the file name, so it stays file-safe. */
internal fun newExerciseId(): String = USER_ID_PREFIX + UUID.randomUUID().toString()

/**
 * The user's exercises, one JSON file each in [dir]. Every save goes to a temporary file first and
 * is then renamed over the old one, so a crash mid-write never leaves a half-written exercise.
 * Call from a background thread.
 */
internal class ExerciseStore(private val dir: File) {

    /** Every readable exercise, most recently saved first. Files that can't be read are skipped. */
    fun loadAll(): List<SavedExercise> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull(::read).sortedByDescending { it.savedAtMs }
    }

    /** Writes [exercise], replacing any earlier version. Throws [IOException] if it can't. */
    fun save(exercise: Exercise): SavedExercise {
        val target = fileFor(exercise.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create $dir")
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(exercise.toJson().toString(2))
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Can't replace ${target.name}")
        }
        return SavedExercise(exercise.copy(builtIn = false), target.lastModified())
    }

    /** Removes the exercise with [id]; returns false if there was nothing to remove. */
    fun delete(id: String): Boolean = fileFor(id).delete()

    private fun fileFor(id: String): File {
        require(VALID_ID.matches(id)) { "Not a file-safe exercise id: $id" }
        return File(dir, "$id.json")
    }

    private fun read(file: File): SavedExercise? = try {
        val exercise = exerciseFromJson(JSONObject(file.readText()))
        // The file name is how the exercise is found again, so it has to match the id inside
        if (file.name == "${exercise.id}.json") SavedExercise(exercise, file.lastModified()) else null
    } catch (ignored: JSONException) {
        null
    } catch (ignored: IOException) {
        null
    }
}
