package com.jsaiborne.vocalpitchdetector

import org.json.JSONArray
import org.json.JSONObject

/** Version of the exercise JSON format, bumped whenever a stored field changes meaning. */
internal const val EXERCISE_FORMAT_VERSION = 1

/**
 * One step of an exercise pattern. [offset] is in semitones from the root (0 = the root, 4 = a
 * major third up), or null for a rest. [beats] is how long it lasts at the exercise tempo.
 */
data class ExerciseNote(val offset: Int?, val beats: Float) {
    val isRest: Boolean get() = offset == null
}

/**
 * A melodic pattern sung from a moving root. Notes are stored relative to the root so the same
 * exercise can be transposed to any key, which is what "step it up/down" does.
 */
data class Exercise(
    val id: String,
    val name: String,
    val notes: List<ExerciseNote>,
    val description: String = "",
    val bpm: Float = 100f,
    /** Silence between repetitions, so the singer can breathe. */
    val breathBeats: Float = 2f,
    val builtIn: Boolean = false
) {
    val minOffset: Int get() = notes.mapNotNull { it.offset }.minOrNull() ?: 0
    val maxOffset: Int get() = notes.mapNotNull { it.offset }.maxOrNull() ?: 0

    /** Distance in semitones between the pattern's lowest and highest notes. */
    val span: Int get() = maxOffset - minOffset

    val totalBeats: Float get() = notes.sumOf { it.beats.toDouble() }.toFloat()

    val beatMs: Float get() = 60_000f / bpm
}

internal fun Exercise.toJson(): JSONObject {
    val notesJson = JSONArray()
    for (note in notes) {
        notesJson.put(
            JSONObject().apply {
                // A rest has no offset field at all
                note.offset?.let { put("offset", it) }
                put("beats", note.beats.toDouble())
            }
        )
    }
    return JSONObject().apply {
        put("version", EXERCISE_FORMAT_VERSION)
        put("id", id)
        put("name", name)
        put("description", description)
        put("bpm", bpm.toDouble())
        put("breathBeats", breathBeats.toDouble())
        put("notes", notesJson)
    }
}

/**
 * Reads an exercise written by [toJson]. Throws [org.json.JSONException] when required fields are
 * missing. Exercises read from JSON are never built in.
 */
internal fun exerciseFromJson(json: JSONObject): Exercise {
    val notesJson = json.getJSONArray("notes")
    val notes = (0 until notesJson.length()).map { i ->
        val n = notesJson.getJSONObject(i)
        ExerciseNote(
            offset = if (n.has("offset")) n.getInt("offset") else null,
            beats = n.getDouble("beats").toFloat()
        )
    }
    return Exercise(
        id = json.getString("id"),
        name = json.getString("name"),
        notes = notes,
        description = json.optString("description", ""),
        bpm = json.optDouble("bpm", 100.0).toFloat(),
        breathBeats = json.optDouble("breathBeats", 2.0).toFloat()
    )
}
