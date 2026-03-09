package com.jsaiborne.vocalpitchdetector

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
 * Uses exponential-style envelopes for fade-in/out to reduce clicks.
 */
object ToneGenerator {
    // Static short-tone AudioTrack
    private var staticTrack: AudioTrack? = null

    // Streaming continuous tone
    @Volatile
    private var streaming = false
    private var streamThread: Thread? = null
    private var streamTrack: AudioTrack? = null

    private val defaultSampleRate = 44100

    // fade settings (ms) - tweak to taste
    private const val STATIC_FADE_IN_MS = 8
    private const val STATIC_FADE_OUT_MS = 20
    private const val STREAM_FADE_IN_MS = 8
    private const val STREAM_FADE_OUT_MS = 40

    // Exponential shape parameter:
    // - 0.0 => linear
    // - >0.0 => exponential curve; larger values make the curve more pronounced
    private const val EXP_SHAPE = 6.0

    private const val SILENCE_BUFFER_SIZE = 64
    private const val THREAD_JOIN_TIMEOUT_MS = 300L

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
     * Play a short static tone. A small exponential fade-in and fade-out is applied to avoid clicks.
     */
    fun playTone(freqHz: Double, durationMs: Int = 1000, sampleRate: Int = defaultSampleRate) {
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
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        staticTrack = null
    }

    /** Start a continuous streaming tone (returns immediately). Call stop() to end (will fade out). */
    fun playToneContinuous(freqHz: Double, sampleRate: Int = defaultSampleRate) {
        stop() // stop any existing streaming tone
        streaming = true

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // create streaming audio track
        streamTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufSize,
            AudioTrack.MODE_STREAM
        )
        streamTrack?.play()

        streamThread = thread(start = true) {
            val bufferSize = 1024
            val buffer = ShortArray(bufferSize)
            var iLong = 0L

            // fade parameters for stream
            val totalFadeInSamples = max(0, (sampleRate * (STREAM_FADE_IN_MS / 1000.0)).roundToInt())
            val totalFadeOutSamples = max(0, (sampleRate * (STREAM_FADE_OUT_MS / 1000.0)).roundToInt())

            var fadeInRemaining = totalFadeInSamples
            var fadeOutRemaining = -1 // -1 means not started fading out yet

            val baseAmp = (Short.MAX_VALUE * 0.25).toInt() // keep headroom to avoid clipping

            // Loop until we've finished steady-play and finished fading out
            loop@ while (streamTrack != null) {
                // If stop() was called, start fade-out (once)
                if (!streaming && fadeOutRemaining == -1) {
                    fadeOutRemaining = totalFadeOutSamples
                }

                // Fill buffer
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
                            expEnv(progress)
                        }
                        fadeOutRemaining == 0 -> {
                            // fade finished, we're done — exit outer loop after writing what we have
                            break@loop
                        }
                        else -> 1.0
                    }

                    val sample = (baseAmp * env * sin(2.0 * PI * iLong * freqHz / sampleRate)).toInt()
                    buffer[i] = sample.toShort()
                    iLong++
                }

                // Write buffer
                try {
                    streamTrack?.write(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    // swallow write exceptions
                }
            }

            // Flush a tiny bit of silence to ensure the track consumes final samples
            try {
                val silence = ShortArray(SILENCE_BUFFER_SIZE)
                streamTrack?.write(silence, 0, silence.size)
            } catch (_: Exception) {
            }

            try {
                streamTrack?.stop()
            } catch (_: Exception) {
            }
            try {
                streamTrack?.release()
            } catch (_: Exception) {
            }
            streamTrack = null
        }
    }

    /** Stop any playing tone (static or streaming). Will cause streaming tone to fade out smoothly. */
    fun stop() {
        stopStatic()
        // signal streaming thread to begin fade-out
        streaming = false
        try {
            streamThread?.join(THREAD_JOIN_TIMEOUT_MS) // wait briefly for fade-out to finish
        } catch (_: Exception) {
        }
        streamThread = null
        // In case thread didn't finish for some reason, try to stop/release the track
        streamTrack?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        streamTrack = null
    }
}
