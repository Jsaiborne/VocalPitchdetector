package com.jsaiborne.vocalpitchdetector

import kotlin.math.min
import kotlin.math.roundToLong

// --- Exercise timeline (no Android or Compose types, so it can be unit tested) --------------------

/** Beats of count-in before the first repetition: the first root sounds, then a beat of silence. */
internal const val COUNT_IN_BEATS = 2f

/** In call & response, the pause between the app's demonstration and the singer's turn. */
internal const val RESPONSE_GAP_BEATS = 1f

enum class GuideMode {
    /** The app plays the pattern, then the singer sings it back alone. Scores cleanly through the speaker. */
    CALL_AND_RESPONSE,

    /** The pattern plays while the singer sings along. Best with headphones, or the mic hears the piano. */
    SING_ALONG
}

enum class Direction { UP, DOWN, UP_THEN_DOWN }

enum class TargetKind {
    /** Played for the singer to hear; not scored. */
    GUIDE,

    /** Sung alone and scored. */
    SING,

    /** Played and sung at the same time; scored. */
    GUIDE_AND_SING,

    /** A single note that gives the singer the next key. */
    CUE;

    val audible: Boolean get() = this != SING
    val scored: Boolean get() = this == SING || this == GUIDE_AND_SING
}

/** One note on the exercise timeline, in absolute wall-clock milliseconds. */
data class TargetEvent(
    val startMs: Long,
    val endMs: Long,
    val midi: Int,
    val repIndex: Int,
    val kind: TargetKind
)

/** Where one repetition sits on the timeline. [listenStartMs] is null in sing-along mode. */
data class RepSpan(
    val index: Int,
    val rootMidi: Int,
    val listenStartMs: Long?,
    val singStartMs: Long,
    val singEndMs: Long
)

enum class ExercisePhase { COUNT_IN, LISTEN, SING, BREATH, DONE }

data class ExerciseSchedule(
    val events: List<TargetEvent>,
    val reps: List<RepSpan>,
    val startMs: Long,
    val endMs: Long
) {
    val scoredEvents: List<TargetEvent> get() = events.filter { it.kind.scored }

    fun phaseAt(tMs: Long): ExercisePhase {
        val first = reps.firstOrNull() ?: return ExercisePhase.DONE
        if (tMs >= endMs) return ExercisePhase.DONE
        if (tMs < (first.listenStartMs ?: first.singStartMs)) return ExercisePhase.COUNT_IN
        for (rep in reps) {
            val listenStart = rep.listenStartMs
            if (listenStart != null && tMs >= listenStart && tMs < rep.singStartMs) return ExercisePhase.LISTEN
            if (tMs >= rep.singStartMs && tMs < rep.singEndMs) return ExercisePhase.SING
        }
        return ExercisePhase.BREATH
    }

    /** The repetition in progress at [tMs], or the next one during a pause; null once all are done. */
    fun repAt(tMs: Long): RepSpan? = reps.firstOrNull { it.singEndMs > tMs }

    /**
     * Moves repetition [repIndex] and everything after it so that it starts again at [startMs], cues
     * and count-in included. Earlier repetitions stay where they were. Used to resume after a pause.
     */
    fun restartFrom(repIndex: Int, startMs: Long): ExerciseSchedule {
        val rep = reps.getOrNull(repIndex) ?: return this
        val shift = startMs - (rep.listenStartMs ?: rep.singStartMs)
        return copy(
            events = events.map {
                if (it.repIndex < repIndex) it else it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift)
            },
            reps = reps.map {
                if (it.index < repIndex) {
                    it
                } else {
                    it.copy(
                        listenStartMs = it.listenStartMs?.plus(shift),
                        singStartMs = it.singStartMs + shift,
                        singEndMs = it.singEndMs + shift
                    )
                }
            },
            endMs = endMs + shift
        )
    }

    /** Only what had happened by [tMs], for scoring an exercise stopped early. */
    fun truncatedAt(tMs: Long): ExerciseSchedule = copy(
        events = events.filter { it.startMs < tMs }.map { it.copy(endMs = minOf(it.endMs, tMs)) },
        reps = reps.filter { it.singStartMs < tMs }.map { it.copy(singEndMs = minOf(it.singEndMs, tMs)) },
        endMs = minOf(endMs, tMs)
    )
}

/**
 * The exercise's time: wall-clock time minus the time spent paused, so the schedule simply waits
 * while paused. Matches the pitch graph's own clock, which freezes the same way.
 */
internal class ExerciseClock(private val wallClock: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var pausedAtMs: Long? = null

    @Volatile
    private var pausedTotalMs = 0L

    val isPaused: Boolean get() = pausedAtMs != null

    fun now(): Long = (pausedAtMs ?: wallClock()) - pausedTotalMs

    fun pause() {
        if (pausedAtMs == null) pausedAtMs = wallClock()
    }

    fun resume() {
        val pausedAt = pausedAtMs ?: return
        pausedTotalMs += wallClock() - pausedAt
        pausedAtMs = null
    }
}

/**
 * The roots the pattern is sung from, one semitone apart, so that every note stays inside
 * [lowMidi]..[highMidi]. When the range is narrower than the pattern, it is sung once from the root
 * that centres it in the range.
 */
internal fun exerciseRoots(exercise: Exercise, lowMidi: Int, highMidi: Int, direction: Direction): List<Int> {
    val lowest = lowMidi - exercise.minOffset
    val highest = highMidi - exercise.maxOffset
    val up = if (highest >= lowest) {
        (lowest..highest).toList()
    } else {
        listOf(Math.floorDiv(lowMidi + highMidi - exercise.minOffset - exercise.maxOffset, 2))
    }
    return when (direction) {
        Direction.UP -> up
        Direction.DOWN -> up.reversed()
        Direction.UP_THEN_DOWN -> up + up.reversed().drop(1)
    }
}

/**
 * Lays the exercise out on the clock, starting at [startMs]: a count-in, then every repetition
 * with a breath between them.
 */
internal fun buildSchedule(
    exercise: Exercise,
    lowMidi: Int,
    highMidi: Int,
    direction: Direction,
    mode: GuideMode,
    startMs: Long
): ExerciseSchedule {
    val roots = exerciseRoots(exercise, lowMidi, highMidi, direction)
    val beatMs = exercise.beatMs.toDouble()
    val events = mutableListOf<TargetEvent>()
    val reps = mutableListOf<RepSpan>()

    // Time is kept as a Double and rounded per event, so rounding never builds up into drift
    var cursor = startMs.toDouble()
    events += TargetEvent(cursor.roundToLong(), (cursor + beatMs).roundToLong(), roots.first(), 0, TargetKind.CUE)
    cursor += COUNT_IN_BEATS * beatMs

    for ((index, root) in roots.withIndex()) {
        var listenStart: Long? = null
        if (mode == GuideMode.CALL_AND_RESPONSE) {
            listenStart = cursor.roundToLong()
            cursor = addPattern(events, exercise, root, index, TargetKind.GUIDE, cursor, beatMs)
            cursor += RESPONSE_GAP_BEATS * beatMs
        }
        val singStart = cursor.roundToLong()
        val singKind = if (mode == GuideMode.SING_ALONG) TargetKind.GUIDE_AND_SING else TargetKind.SING
        cursor = addPattern(events, exercise, root, index, singKind, cursor, beatMs)
        reps += RepSpan(index, root, listenStart, singStart, cursor.roundToLong())

        if (index < roots.lastIndex) {
            val breathMs = exercise.breathBeats * beatMs
            // In call & response the next demonstration gives the key; singing along needs a cue
            if (mode == GuideMode.SING_ALONG && breathMs > 0.0) {
                val cueMs = min(beatMs, breathMs)
                events += TargetEvent(
                    startMs = (cursor + breathMs - cueMs).roundToLong(),
                    endMs = (cursor + breathMs).roundToLong(),
                    midi = roots[index + 1],
                    repIndex = index + 1,
                    kind = TargetKind.CUE
                )
            }
            cursor += breathMs
        }
    }
    return ExerciseSchedule(events, reps, startMs, cursor.roundToLong())
}

/** Adds one pass of the pattern from [root] starting at [fromMs]; returns the time it ends. */
@Suppress("LongParameterList")
private fun addPattern(
    events: MutableList<TargetEvent>,
    exercise: Exercise,
    root: Int,
    repIndex: Int,
    kind: TargetKind,
    fromMs: Double,
    beatMs: Double
): Double {
    var cursor = fromMs
    for (note in exercise.notes) {
        val lengthMs = note.beats * beatMs
        note.offset?.let { offset ->
            val endMs = (cursor + lengthMs).roundToLong()
            events += TargetEvent(cursor.roundToLong(), endMs, root + offset, repIndex, kind)
        }
        cursor += lengthMs
    }
    return cursor
}
