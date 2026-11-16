package com.jsaiborne.vocalpitchdetector

/** A detected pitch, stamped with the wall-clock moment of audio it describes. */
internal data class TimedPitch(val timeMs: Long, val frequencyHz: Float, val confidence: Float)

/** What one analysis frame leads to (see [VoicingGate]). */
internal class GateStep(
    /** Readings to publish now, oldest first: any held back while a note was being confirmed, then this one. */
    val pitches: List<TimedPitch> = emptyList(),
    /** When set, the note has ended: publish silence stamped at this time. */
    val silenceAtMs: Long? = null,
    /** The pitch smoothing belongs to a note that has ended or never started, so start it afresh. */
    val resetTracker: Boolean = false
) {
    companion object {
        val NOTHING = GateStep()
        val RESET = GateStep(resetTracker = true)
    }
}

/**
 * Decides when the detector's frames make a note. A note starts once enough frames in a row have a pitch,
 * and the frames held back while waiting are then published too, so the note starts where it really
 * began. Inside a note, up to [graceFrames] frames in a row without a pitch (a weak or quiet moment) are
 * bridged without a gap; after that the note ends, with silence stamped at the first of them.
 */
internal class VoicingGate(private val graceFrames: Int) {
    private val pending = ArrayList<TimedPitch>()
    private var sounding = false
    private var missed = 0
    private var firstMissMs = 0L

    /** A frame with a pitch. A note is confirmed after [onsetFrames] of them in a row. */
    fun voiced(pitch: TimedPitch, onsetFrames: Int): GateStep {
        if (sounding) {
            missed = 0
            return GateStep(pitches = listOf(pitch))
        }
        pending.add(pitch)
        if (pending.size < onsetFrames) return GateStep.NOTHING
        sounding = true
        val confirmed = pending.toList()
        pending.clear()
        return GateStep(pitches = confirmed)
    }

    /** A frame without a usable pitch (too quiet, unclear or rejected), centred at [timeMs]. */
    fun unvoiced(timeMs: Long): GateStep {
        if (!sounding) {
            if (pending.isEmpty()) return GateStep.NOTHING
            // A blip too short to be a note
            pending.clear()
            return GateStep.RESET
        }
        if (missed == 0) firstMissMs = timeMs
        missed++
        if (missed <= graceFrames) return GateStep.NOTHING
        sounding = false
        missed = 0
        return GateStep(silenceAtMs = firstMissMs, resetTracker = true)
    }
}

/** Longest the wall clock may run ahead of the audio read before [SampleClock] decides audio was lost. */
private const val SAMPLE_CLOCK_MAX_LAG_MS = 100L

/**
 * Wall-clock times for audio read from the microphone, worked out from how many samples have been read
 * rather than from when each read returned. Reads come back with uneven delays, sometimes several at
 * once, so their return times would bunch the readings up; the audio itself is evenly spaced.
 *
 * The clock is anchored to the earliest start time the reads allow (a read can come back late, never
 * early). If the wall clock runs more than [maxLagMs] ahead of the audio, samples were lost (the app
 * fell behind), and it re-anchors.
 */
internal class SampleClock(private val sampleRate: Int, private val maxLagMs: Long = SAMPLE_CLOCK_MAX_LAG_MS) {
    private var startMs = 0L
    private var samplesRead = 0L
    private var anchored = false

    /** Counts a read of [count] samples that returned at [wallMs]; returns the time just after the last of them. */
    fun advance(count: Int, wallMs: Long): Long {
        samplesRead += count
        val readMs = samplesRead * MILLIS_PER_SECOND / sampleRate
        val start = wallMs - readMs
        if (!anchored || start < startMs || start - startMs > maxLagMs) {
            startMs = start
            anchored = true
        }
        return startMs + readMs
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
