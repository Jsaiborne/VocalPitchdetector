package com.jsaiborne.vocalpitchdetector

import kotlin.math.roundToInt

/** The editor's grid is in quarter beats: the shortest note it writes. */
internal const val STEPS_PER_BEAT = 4

/** Note lengths the editor offers, in steps: ¼, ½, 1, 2, 3 and 4 beats. */
internal val NOTE_LENGTH_STEPS = listOf(1, 2, 4, 8, 12, 16)
internal const val MAX_NOTE_STEPS = 16

/** Longest pattern the editor builds (32 beats). */
internal const val MAX_PATTERN_STEPS = 32 * STEPS_PER_BEAT

/** Notes can sit an octave below the root up to two octaves above it. */
internal const val EDITOR_MIN_OFFSET = -12
internal const val EDITOR_MAX_OFFSET = 24

/**
 * A note on the editor grid. It starts at [start] and lasts [length] steps (quarter beats), and
 * sits [offset] semitones from the root. A pattern is a single voice, so notes never overlap.
 */
internal data class RollNote(val start: Int, val length: Int, val offset: Int) {
    val end: Int get() = start + length

    fun overlaps(from: Int, to: Int): Boolean = start < to && from < end
}

/** Lays [notes] out on the grid: rests become gaps. Lengths round to the nearest quarter beat. */
internal fun rollNotesOf(notes: List<ExerciseNote>): List<RollNote> {
    val roll = mutableListOf<RollNote>()
    var step = 0
    for (note in notes) {
        val length = (note.beats * STEPS_PER_BEAT).roundToInt().coerceAtLeast(1)
        note.offset?.let { roll += RollNote(step, length, it) }
        step += length
    }
    return roll
}

/** The pattern drawn on the grid. Gaps between notes become rests; nothing is kept after the last note. */
internal fun exerciseNotesOf(roll: List<RollNote>): List<ExerciseNote> {
    val notes = mutableListOf<ExerciseNote>()
    var step = 0
    for (note in roll.sortedBy { it.start }) {
        if (note.start > step) notes += ExerciseNote(null, (note.start - step).toFloat() / STEPS_PER_BEAT)
        notes += ExerciseNote(note.offset, note.length.toFloat() / STEPS_PER_BEAT)
        step = note.end
    }
    return notes
}

/** The note drawn in the cell at [step] on the [offset] row, if any. */
internal fun List<RollNote>.noteAt(step: Int, offset: Int): RollNote? =
    firstOrNull { it.offset == offset && step >= it.start && step < it.end }

/** Where the pattern ends, in steps. */
internal val List<RollNote>.endStep: Int get() = maxOfOrNull { it.end } ?: 0

/**
 * Where a note added by tapping [step] starts: snapped back to the [snap] grid (so a one-beat note
 * lands on the beat), but never back over a note that has already finished.
 */
internal fun List<RollNote>.startForTap(step: Int, snap: Int): Int {
    val previousEnd = filter { it.end <= step }.maxOfOrNull { it.end } ?: 0
    return maxOf(step - step % snap, previousEnd)
}

/**
 * A new note of up to [length] steps at [start], cut short where the next note begins so that
 * adding a note never removes one that comes later.
 */
internal fun List<RollNote>.placing(start: Int, offset: Int, length: Int): RollNote {
    val nextStart = filter { it.start > start }.minOfOrNull { it.start } ?: MAX_PATTERN_STEPS
    return RollNote(start, minOf(length, nextStart - start, MAX_PATTERN_STEPS - start), offset)
}

/**
 * Puts [note] on the grid. The pattern is one voice, so a note it overlaps is cut short when that
 * note starts earlier and taken out otherwise.
 */
internal fun List<RollNote>.withNote(note: RollNote): List<RollNote> {
    val kept = mapNotNull { other ->
        when {
            !other.overlaps(note.start, note.end) -> other
            other.start < note.start -> other.copy(length = note.start - other.start)
            else -> null
        }
    }
    return (kept + note).sortedBy { it.start }
}

/** [note] moved to [start] on the [offset] row, kept on the grid. Returns the notes and where it landed. */
internal fun List<RollNote>.moving(note: RollNote, start: Int, offset: Int): Pair<List<RollNote>, RollNote> {
    val moved = note.copy(
        start = start.coerceIn(0, MAX_PATTERN_STEPS - note.length),
        offset = offset.coerceIn(EDITOR_MIN_OFFSET, EDITOR_MAX_OFFSET)
    )
    return (this - note).withNote(moved) to moved
}

/**
 * [note] made [length] steps long. It can't grow into the next note or past the longest length
 * offered. Returns the notes and the resized note.
 */
internal fun List<RollNote>.resizing(note: RollNote, length: Int): Pair<List<RollNote>, RollNote> {
    val nextStart = filter { it.start > note.start }.minOfOrNull { it.start } ?: MAX_PATTERN_STEPS
    val resized = note.copy(length = length.coerceIn(1, minOf(MAX_NOTE_STEPS, nextStart - note.start)))
    return map { if (it == note) resized else it } to resized
}
