package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A pitch older than this is out of date (the engine only reports when something changes). */
private const val STALE_PITCH_MS = 400L

/**
 * Plays the audible notes of [schedule] (guide notes and cues) that are still to come, at their
 * scheduled times on [clock]. Each wait is measured against the clock rather than summed, so
 * playback never drifts from the targets on the graph. Cancel the calling coroutine to stop.
 *
 * Playback restarts whenever the schedule changes or a pause ends; notes already started are left
 * alone (a paused piano note carries on by itself), so none is played twice.
 */
internal suspend fun playSchedule(schedule: ExerciseSchedule, clock: ExerciseClock) = withContext(Dispatchers.Default) {
    val from = clock.now()
    val audible = schedule.events.filter { it.kind.audible && it.startMs > from }.sortedBy { it.startMs }
    for (event in audible) {
        val waitMs = event.startMs - clock.now()
        if (waitMs > 0) delay(waitMs)
        playGuideNote(event.midi, (event.endMs - event.startMs).toInt())
    }
}

/** A piano sample when one is loaded, otherwise a synthesised tone of the note's length. */
@Suppress("TooGenericExceptionCaught")
internal fun playGuideNote(midi: Int, durationMs: Int) {
    try {
        if (!SamplePlayer.play(midi)) ToneGenerator.playTone(midiToFreq(midi), durationMs)
    } catch (e: RuntimeException) {
        Log.w("ExerciseAudio", "Guide note $midi failed: ${e.message}")
    }
}

/**
 * Samples the sung pitch into [frames] every [SCORE_FRAME_MS] until the schedule ends on [clock].
 * Sampling on a clock (instead of collecting the engine's updates) is what lets silence count
 * against the score: the engine reports silence only once. Frames are added to the caller's list
 * so they survive a pause, which cancels this and starts it again.
 */
internal suspend fun recordFrames(
    engine: PitchEngine,
    schedule: ExerciseSchedule,
    clock: ExerciseClock,
    frames: MutableList<PitchFrame>
) {
    var now = clock.now()
    while (now < schedule.endMs) {
        if (now >= schedule.startMs) {
            val state = engine.state.value
            // The engine stamps its updates with wall-clock time
            val fresh = System.currentTimeMillis() - state.timestampMs <= STALE_PITCH_MS
            frames += PitchFrame(now, if (fresh) state.frequency else -1f)
        }
        delay(SCORE_FRAME_MS)
        now = clock.now()
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
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) headphoneTypes += AudioDeviceInfo.TYPE_USB_HEADSET
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) headphoneTypes += AudioDeviceInfo.TYPE_BLE_HEADSET
    return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }
}
