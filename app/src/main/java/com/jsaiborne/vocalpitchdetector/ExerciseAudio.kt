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
 * Plays the audible notes of [schedule] (guide notes and cues) at their scheduled times. Each wait
 * is measured against the wall clock rather than summed, so playback never drifts from the
 * targets on the graph. Cancel the calling coroutine to stop.
 */
internal suspend fun playSchedule(schedule: ExerciseSchedule) = withContext(Dispatchers.Default) {
    val audible = schedule.events.filter { it.kind.audible }.sortedBy { it.startMs }
    for (event in audible) {
        val waitMs = event.startMs - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
        playGuideNote(event.midi, (event.endMs - event.startMs).toInt())
    }
}

/** A piano sample when one is loaded, otherwise a synthesised tone of the note's length. */
@Suppress("TooGenericExceptionCaught")
private fun playGuideNote(midi: Int, durationMs: Int) {
    try {
        if (!SamplePlayer.play(midi)) ToneGenerator.playTone(midiToFreq(midi), durationMs)
    } catch (e: RuntimeException) {
        Log.w("ExerciseAudio", "Guide note $midi failed: ${e.message}")
    }
}

/**
 * Samples the sung pitch every [SCORE_FRAME_MS] until the schedule ends. Sampling on a clock
 * (instead of collecting the engine's updates) is what lets silence count against the score: the
 * engine reports silence only once.
 */
internal suspend fun recordFrames(engine: PitchEngine, schedule: ExerciseSchedule): List<PitchFrame> {
    val frames = ArrayList<PitchFrame>()
    var now = System.currentTimeMillis()
    while (now < schedule.endMs) {
        if (now >= schedule.startMs) {
            val state = engine.state.value
            val fresh = now - state.timestampMs <= STALE_PITCH_MS
            frames += PitchFrame(now, if (fresh) state.frequency else -1f)
        }
        delay(SCORE_FRAME_MS)
        now = System.currentTimeMillis()
    }
    return frames
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
