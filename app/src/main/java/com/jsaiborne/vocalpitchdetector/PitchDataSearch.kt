package com.jsaiborne.vocalpitchdetector

import kotlin.math.abs

/**
 * Un-rounded MIDI pitch, so playback curves glide between semitone lines like the live graph.
 * [RecordedPitchPoint.midiNote] is rounded at record time; this is recovered from [RecordedPitchPoint.frequencyHz].
 *
 * Kept here, away from the playback screen's Android set-up, so plain JVM unit tests can use it.
 */
internal val RecordedPitchPoint.midiFloat: Float
    get() = if (frequencyHz > 0f) freqToMidi(frequencyHz.toDouble()).toFloat() else midiNote.toFloat()

/**
 * Index of the first point whose timestamp is >= [timeMs] (or size if there is none).
 * Recorded pitch data is written in time order, so this is a binary search.
 */
internal fun List<RecordedPitchPoint>.firstIndexAtOrAfter(timeMs: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].timestampMs < timeMs) lo = mid + 1 else hi = mid
    }
    return lo
}

/** The point closest in time to [timeMs], or null if the list is empty. */
internal fun List<RecordedPitchPoint>.nearestTo(timeMs: Long): RecordedPitchPoint? {
    if (isEmpty()) return null
    val next = firstIndexAtOrAfter(timeMs)
    if (next == 0) return first()
    if (next == size) return last()
    val before = this[next - 1]
    val after = this[next]
    return if (abs(after.timestampMs - timeMs) < abs(timeMs - before.timestampMs)) after else before
}
