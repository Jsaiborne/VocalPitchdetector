package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often a paused run looks to see whether it has been resumed. */
private const val PAUSE_POLL_MS = 30L

/** A guide note found this late when playback restarts is still played, not dropped. */
private const val LATE_NOTE_GRACE_MS = 120L

/** A guide note sounds for at least this long, however short its bar. */
private const val MIN_GUIDE_NOTE_MS = 80

/** How long a run waits after its last note for the final pitch readings to come in before scoring. */
internal const val SCORE_TAIL_MS = 150L

/**
 * How far a run's guide playback has got. Playback is restarted whenever the schedule changes or
 * a pause ends, so this is kept outside it: a restart then neither repeats a note nor drops one.
 */
internal class GuidePlayback {
    @Volatile
    var lastStartedMs = Long.MIN_VALUE
}

/**
 * Waits until the exercise clock reaches [untilMs]. The clock is checked rather than trusted to
 * keep moving: while it's paused this just waits, so nothing that follows can happen during a
 * pause, even when the pause came from leaving the app (when the screen can't cancel anything).
 */
internal suspend fun ExerciseClock.awaitTime(untilMs: Long) {
    var waitMs = untilMs - now()
    while (isPaused || waitMs > 0) {
        delay(if (isPaused) PAUSE_POLL_MS else waitMs)
        waitMs = untilMs - now()
    }
}

/**
 * Plays the audible notes of [schedule] (guide notes and cues) that are still to come, at their
 * scheduled times on [clock]. Each wait is measured against the clock rather than summed, so
 * playback never drifts from the targets on the graph. Cancel the calling coroutine to stop.
 *
 * Playback restarts whenever the schedule changes or a pause ends; [playback] remembers the notes
 * already started (a paused piano note carries on by itself), so none is played twice.
 */
internal suspend fun playSchedule(schedule: ExerciseSchedule, clock: ExerciseClock, playback: GuidePlayback) =
    withContext(Dispatchers.Default) {
        val earliestMs = clock.now() - LATE_NOTE_GRACE_MS
        val audible = schedule.events
            .filter { it.kind.audible && it.startMs > playback.lastStartedMs && it.startMs >= earliestMs }
            .sortedBy { it.startMs }
        for (event in audible) {
            clock.awaitTime(event.startMs)
            playback.lastStartedMs = event.startMs
            playGuideNote(event.midi, (event.endMs - event.startMs).toInt())
        }
    }

/**
 * A piano sample when one is loaded, otherwise a synthesised tone of the note's length. The piano
 * note is let go after [durationMs] too, so short notes and rests sound as they are drawn; with
 * [ring] it is left to ring out instead (a tap on the editor's grid).
 */
@Suppress("TooGenericExceptionCaught")
internal fun playGuideNote(midi: Int, durationMs: Int, ring: Boolean = false) {
    try {
        val holdMs = if (ring) null else durationMs.coerceAtLeast(MIN_GUIDE_NOTE_MS)
        if (!SamplePlayer.play(midi, holdMs)) ToneGenerator.playTone(midiToFreq(midi), durationMs)
    } catch (e: RuntimeException) {
        Log.w("ExerciseAudio", "Guide note $midi failed: ${e.message}")
    }
}

/**
 * Keeps every pitch the engine reports in [readings], placed on [clock] at the time of the audio
 * it describes, until cancelled. Readings from a pause are left out. The score is worked out from
 * these afterwards (see [framesFromReadings]), which is what lets silence count against it and
 * keeps the detector's delay out of it.
 */
internal suspend fun collectReadings(engine: PitchEngine, clock: ExerciseClock, readings: MutableList<PitchFrame>) {
    engine.readings.collect { reading ->
        clock.exerciseTimeOf(reading.timestampMs)?.let { readings += PitchFrame(it, reading.frequency) }
    }
}

/**
 * True when audio goes to headphones or earbuds. Singing along through the speaker lets the mic
 * hear the guide notes, which would be scored as if the singer had sung them.
 */
internal fun hasHeadphones(context: Context): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    val headphoneTypes = mutableSetOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        // USB-C headphones and adapters often report themselves as a plain USB device
        AudioDeviceInfo.TYPE_USB_DEVICE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) headphoneTypes += AudioDeviceInfo.TYPE_USB_HEADSET
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) headphoneTypes += AudioDeviceInfo.TYPE_HEARING_AID
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) headphoneTypes += AudioDeviceInfo.TYPE_BLE_HEADSET
    return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }
}
