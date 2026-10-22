package com.jsaiborne.vocalpitchdetector

import android.content.SharedPreferences

private const val PREF_EXERCISE_LOW = "ExerciseLowMidi"
private const val PREF_EXERCISE_HIGH = "ExerciseHighMidi"
private const val PREF_EXERCISE_RANGE_TIME = "ExerciseRangeTimeMs"
private const val PREF_EXERCISE_DIRECTION = "ExerciseDirection"
private const val PREF_EXERCISE_MODE = "ExerciseMode"
private const val PREF_EXERCISE_STRICT = "ExerciseStrict"
private const val PREF_EXERCISE_BEST_PREFIX = "ExerciseBest_"

/** Exercises stay inside the notes the pitch detector can hear (about B1 to D6). */
internal const val EXERCISE_MIN_MIDI = 36
internal const val EXERCISE_MAX_MIDI = 84

/** With no range test yet, start from C3 to C4, which most voices can manage. */
private const val DEFAULT_LOW_MIDI = 48
private const val DEFAULT_HIGH_MIDI = 60

/** A range test finds the extremes; exercises sit a little inside them. */
private const val RANGE_TEST_TRIM_SEMITONES = 2
private const val MIN_TRIMMED_SPAN = 8

/** How the user last set up an exercise. The tempo belongs to each exercise, so it isn't kept here. */
internal data class ExerciseSettings(
    val lowMidi: Int,
    val highMidi: Int,
    val direction: Direction = Direction.UP,
    val mode: GuideMode = GuideMode.CALL_AND_RESPONSE,
    val strict: Boolean = false
) {
    val toleranceCents: Float get() = if (strict) STRICT_TOLERANCE_CENTS else DEFAULT_TOLERANCE_CENTS
}

/**
 * The exercise range: the one the user last chose, unless the vocal range test was taken since,
 * in which case the new result (trimmed to comfortable notes) wins.
 */
internal fun initialExerciseRange(prefs: SharedPreferences, savedRange: SavedRange?): IntRange {
    val chosenAtMs = prefs.getLong(PREF_EXERCISE_RANGE_TIME, -1L)
    val hasChosen = prefs.contains(PREF_EXERCISE_LOW) && prefs.contains(PREF_EXERCISE_HIGH)
    return when {
        hasChosen && (savedRange == null || savedRange.timeMs <= chosenAtMs) ->
            prefs.getInt(PREF_EXERCISE_LOW, DEFAULT_LOW_MIDI)..prefs.getInt(PREF_EXERCISE_HIGH, DEFAULT_HIGH_MIDI)
        savedRange != null -> comfortableRange(savedRange.low, savedRange.high)
        else -> DEFAULT_LOW_MIDI..DEFAULT_HIGH_MIDI
    }
}

/** A tested range with a couple of semitones taken off each end, unless that would leave too little. */
internal fun comfortableRange(lowMidi: Int, highMidi: Int): IntRange {
    val low = lowMidi.coerceIn(EXERCISE_MIN_MIDI, EXERCISE_MAX_MIDI)
    val high = highMidi.coerceIn(low, EXERCISE_MAX_MIDI)
    if (high - low < MIN_TRIMMED_SPAN) return low..high
    return (low + RANGE_TEST_TRIM_SEMITONES)..(high - RANGE_TEST_TRIM_SEMITONES)
}

internal fun readExerciseSettings(prefs: SharedPreferences, savedRange: SavedRange?): ExerciseSettings {
    val range = initialExerciseRange(prefs, savedRange)
    return ExerciseSettings(
        lowMidi = range.first,
        highMidi = range.last,
        direction = enumPref(prefs.getString(PREF_EXERCISE_DIRECTION, null), Direction.UP),
        mode = enumPref(prefs.getString(PREF_EXERCISE_MODE, null), GuideMode.CALL_AND_RESPONSE),
        strict = prefs.getBoolean(PREF_EXERCISE_STRICT, false)
    )
}

internal fun saveExerciseSettings(prefs: SharedPreferences, settings: ExerciseSettings) {
    prefs.edit()
        .putInt(PREF_EXERCISE_LOW, settings.lowMidi)
        .putInt(PREF_EXERCISE_HIGH, settings.highMidi)
        .putLong(PREF_EXERCISE_RANGE_TIME, System.currentTimeMillis())
        .putString(PREF_EXERCISE_DIRECTION, settings.direction.name)
        .putString(PREF_EXERCISE_MODE, settings.mode.name)
        .putBoolean(PREF_EXERCISE_STRICT, settings.strict)
        .apply()
}

internal fun readBestScore(prefs: SharedPreferences, exerciseId: String): Int? {
    val key = PREF_EXERCISE_BEST_PREFIX + exerciseId
    return if (prefs.contains(key)) prefs.getInt(key, 0) else null
}

/** Keeps [percent] if it beats the stored best; returns true when it did. */
internal fun saveBestScore(prefs: SharedPreferences, exerciseId: String, percent: Int): Boolean {
    val best = readBestScore(prefs, exerciseId)
    if (best != null && best >= percent) return false
    prefs.edit().putInt(PREF_EXERCISE_BEST_PREFIX + exerciseId, percent).apply()
    return true
}

private inline fun <reified T : Enum<T>> enumPref(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
