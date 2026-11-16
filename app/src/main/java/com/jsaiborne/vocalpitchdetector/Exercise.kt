package com.jsaiborne.vocalpitchdetector

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Version of the exercise JSON format, bumped whenever a stored field changes meaning. */
internal const val EXERCISE_FORMAT_VERSION = 1

/**
 * The key the editor shows a pattern in (its root note). It only changes how the pattern looks
 * and sounds while editing; the player still transposes it to the singer's range.
 */
internal const val DEFAULT_EDITOR_ROOT_MIDI = 60
internal val EDITOR_ROOT_RANGE = 48..60

/** Exercises stay inside the notes the pitch detector can hear (about B1 to D6). */
internal const val EXERCISE_MIN_MIDI = 36
internal const val EXERCISE_MAX_MIDI = 84

internal const val MIN_EXERCISE_BPM = 40f
internal const val MAX_EXERCISE_BPM = 200f
internal const val DEFAULT_EXERCISE_BPM = 100f

internal const val MAX_BREATH_BEATS = 8f
internal const val DEFAULT_BREATH_BEATS = 2f

internal const val MAX_NAME_LENGTH = 40
internal const val MAX_DESCRIPTION_LENGTH = 120

/** The shortest note and the longest pattern, in beats: what the editor's grid can hold. */
private const val MIN_NOTE_BEATS = 1f / STEPS_PER_BEAT
private const val MAX_PATTERN_BEATS = 1f * MAX_PATTERN_STEPS / STEPS_PER_BEAT

private const val UNTITLED = "Untitled"

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
    val bpm: Float = DEFAULT_EXERCISE_BPM,
    /** Silence between repetitions, so the singer can breathe. */
    val breathBeats: Float = DEFAULT_BREATH_BEATS,
    val builtIn: Boolean = false,
    val editorRootMidi: Int = DEFAULT_EDITOR_ROOT_MIDI
) {
    val minOffset: Int get() = notes.mapNotNull { it.offset }.minOrNull() ?: 0
    val maxOffset: Int get() = notes.mapNotNull { it.offset }.maxOrNull() ?: 0

    /** Distance in semitones between the pattern's lowest and highest notes. */
    val span: Int get() = maxOffset - minOffset

    /** The first note that is sung, in semitones from the root: the note a cue gives the singer. */
    val firstOffset: Int get() = notes.firstNotNullOfOrNull { it.offset } ?: 0

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
        put("editorRoot", editorRootMidi)
        put("notes", notesJson)
    }
}

/**
 * Reads an exercise written by [toJson]. Exercises read from JSON are never built in.
 *
 * A file can come back from a backup damaged or edited, so nothing in it is trusted: the tempo,
 * breath, offsets and lengths are brought inside what the app can play, and notes that can't be
 * played are dropped. Throws [JSONException] when required fields are missing, when the file was
 * written by a newer version of the format, or (unless [requireNotes] is false, for a pattern
 * still being drawn) when no note is left to sing.
 */
internal fun exerciseFromJson(json: JSONObject, requireNotes: Boolean = true): Exercise {
    val version = json.optInt("version", EXERCISE_FORMAT_VERSION)
    if (version > EXERCISE_FORMAT_VERSION) throw JSONException("Exercise format $version is newer than this app")

    val notes = readNotes(json.getJSONArray("notes"))
    if (requireNotes && notes.all { it.isRest }) throw JSONException("Exercise has no notes to sing")

    return Exercise(
        id = json.getString("id"),
        name = json.getString("name").trim().take(MAX_NAME_LENGTH).ifEmpty { UNTITLED },
        notes = notes,
        description = json.optString("description", "").trim().take(MAX_DESCRIPTION_LENGTH),
        bpm = finiteOr(json.optDouble("bpm"), DEFAULT_EXERCISE_BPM).coerceIn(MIN_EXERCISE_BPM, MAX_EXERCISE_BPM),
        breathBeats = finiteOr(json.optDouble("breathBeats"), DEFAULT_BREATH_BEATS).coerceIn(0f, MAX_BREATH_BEATS),
        editorRootMidi = json.optInt("editorRoot", DEFAULT_EDITOR_ROOT_MIDI).coerceIn(EDITOR_ROOT_RANGE)
    )
}

/** The notes that can be played, kept on the editor's rows and cut off at the longest pattern. */
private fun readNotes(notesJson: JSONArray): List<ExerciseNote> {
    val notes = mutableListOf<ExerciseNote>()
    var totalBeats = 0f
    for (i in 0 until notesJson.length()) {
        val n = notesJson.getJSONObject(i)
        val room = MAX_PATTERN_BEATS - totalBeats
        val beats = finiteOr(n.optDouble("beats"), 0f)
        // A note with no length (or a broken one) takes no time, so there is nothing to keep; nor is
        // anything kept once the pattern is as long as it can be
        if (beats > 0f && room >= MIN_NOTE_BEATS) {
            val length = beats.coerceIn(MIN_NOTE_BEATS, room)
            val offset = if (n.has("offset")) n.getInt("offset") else null
            notes += ExerciseNote(offset?.coerceIn(EDITOR_MIN_OFFSET, EDITOR_MAX_OFFSET), length)
            totalBeats += length
        }
    }
    return notes
}

/** [value] as a Float, or [fallback] when it is missing (NaN) or infinite. */
private fun finiteOr(value: Double, fallback: Float): Float {
    val number = value.toFloat()
    return if (number.isNaN() || number.isInfinite()) fallback else number
}
