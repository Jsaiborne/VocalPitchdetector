package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.pow

/**
 * Plays piano notes from a handful of samples. Notes can be started from any thread (the exercise
 * player uses a background one), so everything it keeps is guarded by [lock].
 */
object SamplePlayer {
    private val lock = Any()
    private var soundPool: SoundPool? = null

    // Map to store: MIDI Note Number -> SoundPool ID
    private val sampleMap = mutableMapOf<Int, Int>()

    @Volatile
    private var loadedSamplesCount = 0

    /** How many notes can sound at once (polyphony). */
    private const val MAX_STREAMS = 8

    /** How far from a sample's own note it can be played. */
    private const val MAX_SHIFT_SEMITONES = 12

    /** A held note dies away in these steps, [FADE_STEP_MS] apart, instead of being cut off with a click. */
    private val FADE_VOLUMES = floatArrayOf(0.6f, 0.35f, 0.18f, 0.08f)
    private const val FADE_STEP_MS = 15L

    // Define your available samples and their MIDI values
    private val AVAILABLE_SAMPLES = mapOf(
        36 to R.raw.piano_c2,
        48 to R.raw.piano_c3,
        60 to R.raw.piano_c4,
        72 to R.raw.piano_c5
    )

    /** A note that is let go (faded out) when its time is up, rather than left to ring. */
    private class Release(val streamId: Int) {
        var dueUptimeMs = 0L
        var remainingMs = 0L
        var task: Runnable? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The streams started most recently, so they can be stopped; SoundPool keeps no list of its own. */
    private val recentStreams = ArrayDeque<Int>()
    private val releases = mutableListOf<Release>()
    private var paused = false

    fun init(context: Context) {
        synchronized(lock) {
            if (soundPool != null) return

            val pool = SoundPool.Builder().setMaxStreams(MAX_STREAMS).build()
            soundPool = pool

            // Attach the listener before loading: a sample that finishes loading before the listener
            // exists is never counted, and the piano would then fall back to the oscillator forever
            pool.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) loadedSamplesCount++
            }

            for ((midi, resId) in AVAILABLE_SAMPLES) {
                sampleMap[midi] = pool.load(context, resId, 1)
            }
        }
    }

    /**
     * Plays [midi]; returns false when it couldn't (samples still loading, or the note is out of
     * reach), so the caller can fall back to a synthesised tone. With [holdMs] the note is let go
     * after that long, like a key being released; without it the note rings out by itself.
     */
    @Suppress("ReturnCount")
    fun play(midi: Int, holdMs: Int? = null): Boolean {
        synchronized(lock) {
            val pool = soundPool
            if (pool == null || loadedSamplesCount < AVAILABLE_SAMPLES.size) return false

            val bestBaseMidi = sampleMap.keys.minByOrNull { abs(it - midi) } ?: return false
            val sampleId = sampleMap[bestBaseMidi] ?: return false

            // SoundPool can only shift a sample by an octave either way (0.5x to 2x). A note further
            // off would sound at the wrong pitch, so leave it to the caller's synthesised tone
            if (abs(midi - bestBaseMidi) > MAX_SHIFT_SEMITONES) return false

            val semitoneDelta = (midi - bestBaseMidi).toDouble()
            val rate = 2.0.pow(semitoneDelta / 12.0).toFloat().coerceIn(0.5f, 2.0f)

            // SoundPool gives 0 when it couldn't start the note
            val streamId = pool.play(sampleId, 1f, 1f, 1, 0, rate)
            if (streamId == 0) return false

            recentStreams.addLast(streamId)
            if (recentStreams.size > MAX_STREAMS) recentStreams.removeFirst()
            if (holdMs != null) scheduleRelease(streamId, holdMs.toLong())
            return true
        }
    }

    /** Lines up the fade-out of [streamId] for [afterMs] from now. Call with [lock] held. */
    private fun scheduleRelease(streamId: Int, afterMs: Long) {
        val release = Release(streamId)
        val task = Runnable { fadeOut(release) }
        release.task = task
        releases += release
        if (paused) {
            // Nothing is counted down while paused; resumeAll starts the wait
            release.remainingMs = afterMs
        } else {
            release.dueUptimeMs = SystemClock.uptimeMillis() + afterMs
            handler.postAtTime(task, release.dueUptimeMs)
        }
    }

    /** Turns the note down in a few quick steps and then stops it. Runs on the main thread. */
    private fun fadeOut(release: Release) {
        synchronized(lock) { releases -= release }
        val streamId = release.streamId
        for ((step, volume) in FADE_VOLUMES.withIndex()) {
            val turnDown = Runnable { synchronized(lock) { soundPool?.setVolume(streamId, volume, volume) } }
            handler.postDelayed(turnDown, step * FADE_STEP_MS)
        }
        val stop = Runnable { synchronized(lock) { soundPool?.stop(streamId) } }
        handler.postDelayed(stop, FADE_VOLUMES.size * FADE_STEP_MS)
    }

    /** Pauses every note still sounding, mid-note, until [resumeAll]. Held notes stop counting down too. */
    fun pauseAll() {
        synchronized(lock) {
            if (!paused) {
                paused = true
                val now = SystemClock.uptimeMillis()
                for (release in releases) {
                    release.task?.let { handler.removeCallbacks(it) }
                    release.remainingMs = (release.dueUptimeMs - now).coerceAtLeast(0L)
                }
            }
            soundPool?.autoPause()
        }
    }

    /** Carries on the notes stopped by [pauseAll] from where they were. */
    fun resumeAll() {
        synchronized(lock) {
            soundPool?.autoResume()
            if (paused) {
                paused = false
                val now = SystemClock.uptimeMillis()
                for (release in releases) {
                    release.dueUptimeMs = now + release.remainingMs
                    release.task?.let { handler.postAtTime(it, release.dueUptimeMs) }
                }
            }
        }
    }

    /**
     * Stops the notes started recently, paused ones included. Without this, notes paused by
     * [pauseAll] and never resumed would wait in the pool and sound at the next [resumeAll].
     */
    fun stopAll() {
        synchronized(lock) {
            for (release in releases) release.task?.let { handler.removeCallbacks(it) }
            releases.clear()
            paused = false
            val pool = soundPool
            if (pool != null) for (streamId in recentStreams) pool.stop(streamId)
            recentStreams.clear()
        }
    }

    fun release() {
        synchronized(lock) {
            handler.removeCallbacksAndMessages(null)
            releases.clear()
            recentStreams.clear()
            paused = false
            soundPool?.release()
            soundPool = null
            sampleMap.clear()
            loadedSamplesCount = 0
        }
    }
}
