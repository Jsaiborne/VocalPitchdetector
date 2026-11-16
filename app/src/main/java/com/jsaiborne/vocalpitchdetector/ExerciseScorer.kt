package com.jsaiborne.vocalpitchdetector

import kotlin.math.abs
import kotlin.math.roundToInt

// --- Exercise scoring (no Android or Compose types, so it can be unit tested) ---------------------

/** The start of each note isn't scored: it takes a moment to land on a pitch (and for the mic to hear it). */
internal const val ONSET_GRACE_MS = 150L

internal const val DEFAULT_TOLERANCE_CENTS = 50f
internal const val STRICT_TOLERANCE_CENTS = 25f

/** How often the sung pitch is sampled while scoring. */
internal const val SCORE_FRAME_MS = 20L

/** Misses bigger than this are a different note (or an octave slip), not a sharp/flat tendency. */
private const val TENDENCY_MAX_CENTS = 200f

/** Below this average error the singer is neither flat nor sharp. */
private const val TENDENCY_NEUTRAL_CENTS = 10f

/** A pitch older than this is out of date (the engine only reports when something changes). */
internal const val STALE_PITCH_MS = 400L

/** The sung pitch at one moment; [frequencyHz] is 0 or less when nothing was being sung. */
data class PitchFrame(val tMs: Long, val frequencyHz: Float)

/**
 * Rebuilds the sung pitch every [stepMs] from [fromMs] up to [toMs] out of the engine's [readings],
 * each of which is a [PitchFrame] placed at the time of the audio it describes. A reading stands
 * until the next one (the engine reports silence once, when a note ends), but no longer than
 * [staleMs]. Placing pitches when they were sung, rather than when the app heard about them, is
 * what keeps the detector's delay out of the score.
 */
internal fun framesFromReadings(
    readings: List<PitchFrame>,
    fromMs: Long,
    toMs: Long,
    stepMs: Long = SCORE_FRAME_MS,
    staleMs: Long = STALE_PITCH_MS
): List<PitchFrame> {
    val sorted = readings.sortedBy { it.tMs }
    val frames = ArrayList<PitchFrame>()
    var next = 0
    var latest: PitchFrame? = null
    var t = fromMs
    while (t < toMs) {
        while (next < sorted.size && sorted[next].tMs <= t) latest = sorted[next++]
        val heard = latest?.takeIf { t - it.tMs <= staleMs }
        frames += PitchFrame(t, heard?.frequencyHz ?: -1f)
        t += stepMs
    }
    return frames
}

data class RepScore(
    val repIndex: Int,
    val rootMidi: Int,
    val frames: Int,
    val inTuneFrames: Int,
    /** Average signed error of the sung frames, negative when flat; null when nothing usable was sung. */
    val meanCentsError: Float?
) {
    val accuracy: Float get() = if (frames == 0) 0f else inTuneFrames.toFloat() / frames
}

data class ExerciseResult(
    val frames: Int,
    val inTuneFrames: Int,
    val reps: List<RepScore>,
    val meanCentsError: Float?
) {
    val accuracy: Float get() = if (frames == 0) 0f else inTuneFrames.toFloat() / frames
    val percent: Int get() = (accuracy * 100f).roundToInt()
}

/**
 * Scores sampled [frames] against the scored notes of [schedule]. Every frame inside a note
 * (after its onset grace) counts, including silent ones, so not singing can't score well. A frame
 * is in tune when it is within [toleranceCents] of the target.
 */
internal fun scoreFrames(
    frames: List<PitchFrame>,
    schedule: ExerciseSchedule,
    toleranceCents: Float,
    graceMs: Long = ONSET_GRACE_MS
): ExerciseResult {
    val events = schedule.scoredEvents.sortedBy { it.startMs }
    val tallies = List(schedule.reps.size) { RepTally() }

    var eventIndex = 0
    for (frame in frames.sortedBy { it.tMs }) {
        while (eventIndex < events.size && events[eventIndex].endMs <= frame.tMs) eventIndex++
        val event = events.getOrNull(eventIndex) ?: break
        // Short notes keep at least half their length for scoring
        val grace = minOf(graceMs, (event.endMs - event.startMs) / 2)
        val tally = tallies.getOrNull(event.repIndex)
        if (tally != null && frame.tMs >= event.startMs + grace) {
            tally.add(frame.frequencyHz, event.midi, toleranceCents)
        }
    }

    val reps = schedule.reps.map { span ->
        val tally = tallies[span.index]
        RepScore(
            repIndex = span.index,
            rootMidi = span.rootMidi,
            frames = tally.frames,
            inTuneFrames = tally.inTune,
            meanCentsError = tally.meanCents()
        )
    }
    val total = RepTally()
    for (tally in tallies) total.merge(tally)
    return ExerciseResult(
        frames = total.frames,
        inTuneFrames = total.inTune,
        reps = reps,
        meanCentsError = total.meanCents()
    )
}

private class RepTally {
    var frames = 0
    var inTune = 0
    private var centsSum = 0.0
    private var centsCount = 0

    fun add(frequencyHz: Float, targetMidi: Int, toleranceCents: Float) {
        frames++
        if (frequencyHz <= 0f) return
        val cents = centsDifference(frequencyHz.toDouble(), targetMidi)
        if (abs(cents) <= toleranceCents) inTune++
        if (abs(cents) <= TENDENCY_MAX_CENTS) {
            centsSum += cents
            centsCount++
        }
    }

    fun merge(other: RepTally) {
        frames += other.frames
        inTune += other.inTune
        centsSum += other.centsSum
        centsCount += other.centsCount
    }

    fun meanCents(): Float? = if (centsCount == 0) null else (centsSum / centsCount).toFloat()
}

/** A short note on whether the singer tends to be flat or sharp, or null when there's nothing to say. */
internal fun tendencyText(meanCentsError: Float?): String? {
    val cents = meanCentsError ?: return null
    val rounded = abs(cents).roundToInt()
    return when {
        cents <= -TENDENCY_NEUTRAL_CENTS -> "You tend to sing a little flat (about $rounded cents under)"
        cents >= TENDENCY_NEUTRAL_CENTS -> "You tend to sing a little sharp (about $rounded cents over)"
        else -> "Nicely centred: no flat or sharp tendency"
    }
}
