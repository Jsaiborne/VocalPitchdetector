package com.jsaiborne.vocalpitchdetector

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ToneGenerator with two modes:
 * - playTone(freq, duration): short static tone (uses MODE_STATIC)
 * - playToneContinuous(freq): starts streaming a tone until stop() is called (uses MODE_STREAM)
 *
 * Uses exponential-style envelopes for the fade-ins, and a long smooth release when a held note is
 * let go, so notes die away instead of being cut off.
 */
object ToneGenerator {
    // Static short-tone AudioTrack
    private var staticTrack: AudioTrack? = null

    /**
     * One streaming tone. Each call to [playToneContinuous] gets its own handle so that [stop]
     * can only ever tear down the tone it was asked to stop, never a newer one.
     */
    private class StreamHandle(val track: AudioTrack) {
        @Volatile
        var stopRequested = false
        var thread: Thread? = null
    }

    private val streamLock = Any()
    private var currentStream: StreamHandle? = null

    private const val DEFAULT_SAMPLE_RATE = 44100

    // fade settings (ms) - tweak to taste
    private const val STATIC_FADE_IN_MS = 8
    private const val STATIC_FADE_OUT_MS = 20
    private const val STREAM_FADE_IN_MS = 8

    // A held note dies away over this long when released, like a piano/organ release, instead of
    // being cut off. Long enough to sound smooth, short enough that it doesn't feel laggy.
    private const val STREAM_FADE_OUT_MS = 350

    // Exponential shape parameter (used for the fade-ins and the short static tone):
    // - 0.0 => linear
    // - >0.0 => exponential curve; larger values make the curve more pronounced
    private const val EXP_SHAPE = 6.0

    private const val SILENCE_BUFFER_SIZE = 64

    // The watchdog only steps in if the streaming thread is stuck; it must outlast the whole
    // fade-out plus draining the audio queue, or it would cut the release short
    private const val THREAD_JOIN_TIMEOUT_MS = 3000L
    private const val DRAIN_TIMEOUT_MS = 1500L
    private const val DRAIN_POLL_MS = 5L

    /**
     * Exponential-style envelope mapping.
     * progress in [0..1] -> returns value in [0..1].
     * Formula used: (exp(k * progress) - 1) / (exp(k) - 1)
     * If k == 0, falls back to linear.
     */
    private fun expEnv(progress: Double, k: Double = EXP_SHAPE): Double {
        val p = progress.coerceIn(0.0, 1.0)
        if (k == 0.0) return p
        val denom = exp(k) - 1.0
        // If denom is tiny (shouldn't be for >0), avoid division by zero
        return if (denom == 0.0) p else (exp(k * p) - 1.0) / denom
    }

    /**
     * Smooth release curve (smoothstep) for a note that has been let go.
     * [remaining] runs from 1 (release just started) down to 0 (silent); the gain starts and ends
     * with zero slope, so there is neither a kink when the release starts nor a click at the end.
     */
    private fun releaseEnv(remaining: Double): Double {
        val r = remaining.coerceIn(0.0, 1.0)
        return r * r * (3.0 - 2.0 * r)
    }

    /**
     * Play a short static tone. A small exponential fade-in and fade-out is applied to avoid clicks.
     */
    fun playTone(freqHz: Double, durationMs: Int = 1000, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        stopStatic()

        val count = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(1)

        // Compute fade sample counts (clamp so fades don't exceed duration)
        val fadeInSamples = min((sampleRate * (STATIC_FADE_IN_MS / 1000.0)).roundToInt(), count / 2)
        val fadeOutSamples = min((sampleRate * (STATIC_FADE_OUT_MS / 1000.0)).roundToInt(), count / 2)

        val buffer = ShortArray(count)
        val baseAmp = 12000 // static amplitude (kept well under Short.MAX_VALUE)

        for (i in 0 until count) {
            // envelope multiplier (0..1) using exponential mapping
            val env = when {
                i < fadeInSamples -> {
                    val idx = i
                    val progress = if (fadeInSamples > 0) idx.toDouble() / fadeInSamples else 1.0
                    expEnv(progress)
                }
                i >= count - fadeOutSamples -> {
                    val idx = (count - i - 1).coerceAtLeast(0)
                    val progress = if (fadeOutSamples > 0) idx.toDouble() / fadeOutSamples else 0.0
                    expEnv(progress)
                }
                else -> 1.0
            }.coerceIn(0.0, 1.0)

            val sample = (baseAmp * env * sin(2.0 * PI * i * freqHz / sampleRate)).toInt()
            buffer[i] = sample.toShort()
        }

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        staticTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufSize, buffer.size * 2),
            AudioTrack.MODE_STATIC
        )
        staticTrack?.write(buffer, 0, buffer.size)
        staticTrack?.play()
    }

    private fun stopStatic() {
        staticTrack?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                android.util.Log.w("ToneGenerator", "Static track stop failed", e)
            }
            try {
                it.release()
            } catch (e: IllegalStateException) {
                android.util.Log.w("ToneGenerator", "Static track release failed", e)
            }
        }
        staticTrack = null
    }

    /** Start a continuous streaming tone (returns immediately). Call stop() to end (will fade out). */
    fun playToneContinuous(freqHz: Double, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        stop() // stop any existing streaming tone

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // create streaming audio track
        val handle = StreamHandle(
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize,
                AudioTrack.MODE_STREAM
            )
        )
        val track = handle.track
        track.play()

        // Publish the handle before the thread can run so a concurrent stop() always sees it
        synchronized(streamLock) { currentStream = handle }

        handle.thread = thread(start = true) {
            val bufferSize = 1024
            val buffer = ShortArray(bufferSize)
            var iLong = 0L

            // fade parameters for stream
            val totalFadeInSamples = max(0, (sampleRate * (STREAM_FADE_IN_MS / 1000.0)).roundToInt())
            val totalFadeOutSamples = max(0, (sampleRate * (STREAM_FADE_OUT_MS / 1000.0)).roundToInt())

            var fadeInRemaining = totalFadeInSamples
            var fadeOutRemaining = -1 // -1 means not started fading out yet
            var framesWritten = 0

            val baseAmp = (Short.MAX_VALUE * 0.25).toInt() // keep headroom to avoid clipping

            // Loop until we've finished steady-play and finished fading out
            while (true) {
                // If stop() was called, start fade-out (once)
                if (handle.stopRequested && fadeOutRemaining == -1) {
                    fadeOutRemaining = totalFadeOutSamples
                }

                // Fill buffer
                var finished = false
                for (i in 0 until bufferSize) {
                    // envelope multiplier
                    val env = when {
                        fadeInRemaining > 0 -> {
                            // calculate progress for fade-in (0..1)
                            val idx = totalFadeInSamples - fadeInRemaining
                            val progress = if (totalFadeInSamples > 0) idx.toDouble() / totalFadeInSamples else 1.0
                            fadeInRemaining--
                            expEnv(progress)
                        }
                        fadeOutRemaining > 0 -> {
                            // fadeOutRemaining counts down from totalFadeOutSamples -> 0
                            // map to progress in [0..1] where 1 => full amplitude, 0 => silence
                            val progress = if (totalFadeOutSamples > 0) {
                                (fadeOutRemaining.toDouble() / totalFadeOutSamples)
                            } else {
                                0.0
                            }
                            fadeOutRemaining--
                            releaseEnv(progress)
                        }
                        fadeOutRemaining == 0 -> {
                            // Fade finished: pad the rest of this buffer with silence and write it, so
                            // the very end of the release is played rather than dropped
                            finished = true
                            0.0
                        }
                        else -> 1.0
                    }

                    val sample = (baseAmp * env * sin(2.0 * PI * iLong * freqHz / sampleRate)).toInt()
                    buffer[i] = sample.toShort()
                    iLong++
                }

                // Write buffer
                try {
                    track.write(buffer, 0, buffer.size)
                    framesWritten += buffer.size
                } catch (e: IllegalStateException) {
                    android.util.Log.e("ToneGenerator", "Track write failed", e)
                }

                if (finished) break
            }

            // Flush a tiny bit of silence to ensure the track consumes final samples
            try {
                val silence = ShortArray(SILENCE_BUFFER_SIZE)
                track.write(silence, 0, silence.size)
                framesWritten += silence.size
            } catch (e: IllegalStateException) {
                android.util.Log.w("ToneGenerator", "Silence flush failed", e)
            }

            drainAndRelease(track, framesWritten)
        }
    }

    /** Stop any playing tone (static or streaming). Will cause streaming tone to fade out smoothly. */
    fun stop() {
        stopStatic()

        val handle = synchronized(streamLock) {
            currentStream.also { currentStream = null }
        } ?: return

        // Signal the streaming thread to begin its fade-out; it releases its own track when done.
        handle.stopRequested = true

        // Watchdog: if the thread doesn't finish in time, force-release this handle's track only.
        thread(start = true) {
            try {
                handle.thread?.join(THREAD_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                android.util.Log.e("ToneGenerator", "Thread join interrupted", e)
            }
            if (handle.thread?.isAlive == true) releaseTrack(handle.track)
        }
    }

    /**
     * Waits until the track has actually played everything written to it, then releases it.
     * Releasing straight after the last write throws away whatever is still queued, which is the
     * end of the fade-out, and is heard as a click.
     */
    private fun drainAndRelease(track: AudioTrack, framesWritten: Int) {
        val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
        try {
            while (SystemClock.elapsedRealtime() < deadline && track.playbackHeadPosition < framesWritten) {
                Thread.sleep(DRAIN_POLL_MS)
            }
        } catch (e: InterruptedException) {
            android.util.Log.w("ToneGenerator", "Drain wait interrupted", e)
        } catch (e: IllegalStateException) {
            android.util.Log.w("ToneGenerator", "Track no longer readable while draining", e)
        }
        releaseTrack(track)
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.stop()
        } catch (e: IllegalStateException) {
            android.util.Log.w("ToneGenerator", "Stream track stop failed", e)
        }
        try {
            track.release()
        } catch (e: IllegalStateException) {
            android.util.Log.w("ToneGenerator", "Stream track release failed", e)
        }
    }
}
