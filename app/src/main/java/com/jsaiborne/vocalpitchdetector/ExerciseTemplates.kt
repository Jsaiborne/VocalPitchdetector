package com.jsaiborne.vocalpitchdetector

/** Notes of equal length, with the last one held for [lastBeats]. Offsets are semitones from the root. */
private fun pattern(vararg offsets: Int, beats: Float = 1f, lastBeats: Float = 2f): List<ExerciseNote> =
    offsets.mapIndexed { i, offset ->
        ExerciseNote(offset, if (i == offsets.lastIndex) lastBeats else beats)
    }

/** Short detached notes: each one is sung for half its beat, then a rest. */
private fun staccato(vararg offsets: Int): List<ExerciseNote> =
    offsets.flatMap { listOf(ExerciseNote(it, 0.5f), ExerciseNote(null, 0.5f)) }

/** Classic warm-ups shipped with the app. Users can sing them as they are. */
internal val BUILT_IN_EXERCISES: List<Exercise> = listOf(
    Exercise(
        id = "builtin_five_tone",
        name = "5-Tone Scale",
        description = "1-2-3-4-5-4-3-2-1 · the everyday warm-up",
        notes = pattern(0, 2, 4, 5, 7, 5, 4, 2, 0),
        bpm = 120f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_major_triad",
        name = "Major Triad",
        description = "1-3-5-3-1 · easy leaps to find the chord",
        notes = pattern(0, 4, 7, 4, 0),
        bpm = 100f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_octave_arpeggio",
        name = "Octave Arpeggio",
        description = "1-3-5-8-5-3-1 · stretches across the octave",
        notes = pattern(0, 4, 7, 12, 7, 4, 0),
        bpm = 100f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_rossini",
        name = "Rossini Scale",
        description = "9-note scale up and down · agility and breath control",
        notes = pattern(0, 2, 4, 5, 7, 9, 11, 12, 14, 12, 11, 9, 7, 5, 4, 2, 0, beats = 0.5f, lastBeats = 1.5f),
        bpm = 90f,
        breathBeats = 2.5f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_octave_jump",
        name = "Octave Jump",
        description = "1-8-1 · land the top note cleanly and hold it",
        notes = listOf(ExerciseNote(0, 1f), ExerciseNote(12, 2f), ExerciseNote(0, 2f)),
        bpm = 80f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_staccato_triad",
        name = "Staccato Triad",
        description = "1-3-5-3-1, short and bouncy · light, precise onsets",
        notes = staccato(0, 4, 7, 4, 0),
        bpm = 100f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_minor_five_tone",
        name = "Minor 5-Tone",
        description = "1-2-♭3-4-5-4-♭3-2-1 · hear the minor third",
        notes = pattern(0, 2, 3, 5, 7, 5, 3, 2, 0),
        bpm = 120f,
        builtIn = true
    ),
    Exercise(
        id = "builtin_fifth_leap",
        name = "Fifth Leap",
        description = "1-5-1 · a clean jump up a fifth and back",
        notes = listOf(ExerciseNote(0, 1f), ExerciseNote(7, 2f), ExerciseNote(0, 2f)),
        bpm = 90f,
        builtIn = true
    )
)
