package com.jsaiborne.vocalpitchdetector

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SEMITONES_PER_OCTAVE = 12
private const val WHITE_KEYS_PER_OCTAVE = 7f

/** True for the naturals (white keys), false for the sharps (black keys). */
internal fun isWhiteKey(midi: Int): Boolean = !midiToNoteName(midi).contains("#")

/**
 * The pitch axis shared by the piano and the live graph, for a keyboard whose first key is [startMidi]
 * and whose white keys are [keySize] long (any unit; positions come out in the same unit).
 *
 * Every semitone is equally long and an octave is as long as its 7 white keys, so each C lane starts
 * exactly where its C key does. The black keys and the lanes sit where the key backs are on a real
 * piano: C# a little left of the C/D join, F# left of F/G, A# right of A/B.
 */
internal class KeyboardPitchAxis(startMidi: Int, keySize: Float) {
    /** Length of one semitone lane. */
    val semitoneSize: Float = keySize * WHITE_KEYS_PER_OCTAVE / SEMITONES_PER_OCTAVE

    // The C at or below the first key, and how far that C's edge lies before the start of the keyboard
    private val octaveStartMidi = startMidi - Math.floorMod(startMidi, SEMITONES_PER_OCTAVE)
    private val octaveStartOffset = (octaveStartMidi until startMidi).count { isWhiteKey(it) } * keySize

    /** Distance from the low end of the keyboard to the centre of [midi], which may be fractional. */
    fun centreOf(midi: Float): Float = (midi - octaveStartMidi + 0.5f) * semitoneSize - octaveStartOffset
}

// --- Held notes ------------------------------------------------------------------------------

/** A stretch of steady singing on one note, drawn as a labelled block on the pitch graphs. */
internal data class NoteSegment(
    val midi: Int,
    val startMs: Long,
    val endMs: Long,
    /** Average distance from the note's centre, in cents (positive = sharp). */
    val cents: Float
)

/** The pitch must move this far (in semitones) from the current note before a new note starts. */
private const val NOTE_SWITCH_SEMITONES = 0.6f

/** A pause longer than this ends the note. */
internal const val NOTE_SILENCE_GAP_MS = 150L

/** Shorter notes (quick glides and scoops) are not shown. */
internal const val NOTE_MIN_DURATION_MS = 150L

/** The start of a note (the scoop into it) is left out of its tuning, unless that's all there is. */
private const val NOTE_ATTACK_MS = 80L

/**
 * Splits pitch points into held notes. [midiOf] gives a point's pitch as a fractional MIDI note, or NaN
 * for silence. A note ends at silence, at a pause longer than [NOTE_SILENCE_GAP_MS], or when the pitch
 * moves more than [NOTE_SWITCH_SEMITONES] from it (so vibrato and small wobbles stay one note); notes
 * shorter than [NOTE_MIN_DURATION_MS] are dropped. The last note is included even if it's still going.
 */
internal fun <T> segmentNotes(points: List<T>, timeOf: (T) -> Long, midiOf: (T) -> Float): List<NoteSegment> {
    val notes = mutableListOf<NoteSegment>()
    val note = NoteBuilder(notes)
    var lastTimeMs = Long.MIN_VALUE
    for (point in points) {
        val timeMs = timeOf(point)
        val pitch = midiOf(point)
        val afterPause = lastTimeMs != Long.MIN_VALUE && timeMs - lastTimeMs > NOTE_SILENCE_GAP_MS
        val movedAway = note.isOpen && abs(pitch - note.midi) > NOTE_SWITCH_SEMITONES
        if (pitch.isNaN() || afterPause || movedAway) note.finish()
        if (pitch.isNaN()) continue
        if (!note.isOpen) note.start(pitch.roundToInt(), timeMs)
        note.add(pitch, timeMs)
        lastTimeMs = timeMs
    }
    note.finish()
    return notes
}

/** Collects the points of the note being read and hands it to [out] once it ends. */
private class NoteBuilder(private val out: MutableList<NoteSegment>) {
    var isOpen = false
        private set
    var midi = 0
        private set
    private var startMs = 0L
    private var endMs = 0L

    // Offsets (in cents) of the points that round to this note, split into the attack and the rest
    private var attackSum = 0f
    private var attackCount = 0
    private var bodySum = 0f
    private var bodyCount = 0

    fun start(note: Int, timeMs: Long) {
        isOpen = true
        midi = note
        startMs = timeMs
        endMs = timeMs
        attackSum = 0f
        attackCount = 0
        bodySum = 0f
        bodyCount = 0
    }

    fun add(pitch: Float, timeMs: Long) {
        endMs = timeMs
        val offset = pitch - midi
        if (abs(offset) > 0.5f) return
        if (timeMs - startMs < NOTE_ATTACK_MS) {
            attackSum += offset * 100f
            attackCount++
        } else {
            bodySum += offset * 100f
            bodyCount++
        }
    }

    fun finish() {
        if (isOpen && endMs - startMs >= NOTE_MIN_DURATION_MS) {
            val cents = when {
                bodyCount > 0 -> bodySum / bodyCount
                attackCount > 0 -> attackSum / attackCount
                else -> 0f
            }
            out.add(NoteSegment(midi, startMs, endMs, cents))
        }
        isOpen = false
    }
}

// --- Tuning ----------------------------------------------------------------------------------

/** Within this many cents of a note counts as in tune. */
internal const val IN_TUNE_CENTS = 10f

/** Within this many cents counts as close. */
internal const val NEAR_TUNE_CENTS = 25f

/** How close a pitch is to its note, with the colour the graphs and the cents meter use for it. */
internal enum class TuningBand(val color: Color) {
    IN_TUNE(Color(0xFF66BB6A)),
    CLOSE(Color(0xFFFFB74D)),
    OFF(Color(0xFFEF5350))
}

internal fun tuningBand(cents: Float): TuningBand = when {
    abs(cents) <= IN_TUNE_CENTS -> TuningBand.IN_TUNE
    abs(cents) <= NEAR_TUNE_CENTS -> TuningBand.CLOSE
    else -> TuningBand.OFF
}
