package com.example.vocalpitchdetector

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.concurrent.thread

/**
 * ToneGenerator with two modes:
 * - playTone(freq, duration): short static tone (uses MODE_STATIC)
 * - playToneContinuous(freq): starts streaming a tone until stop() is called (uses MODE_STREAM)
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

    fun playTone(freqHz: Double, durationMs: Int = 1000, sampleRate: Int = defaultSampleRate) {
        stopStatic()
        val count = (sampleRate * (durationMs / 1000.0)).toInt()
        val buffer = ShortArray(count)
        val amp = 12000 // volume
        for (i in 0 until count) {
            val sample = (amp * sin(2.0 * PI * i * freqHz / sampleRate)).toInt()
            buffer[i] = sample.toShort()
        }
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        staticTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBufSize, buffer.size * 2), AudioTrack.MODE_STATIC)
        staticTrack?.write(buffer, 0, buffer.size)
        staticTrack?.play()
    }

    private fun stopStatic() {
        staticTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        staticTrack = null
    }

    /** Start a continuous streaming tone (returns immediately). Call stop() to end. */
    fun playToneContinuous(freqHz: Double, sampleRate: Int = defaultSampleRate) {
        stop() // stop any existing streaming tone
        streaming = true

        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
// create streaming audio track
        streamTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize, AudioTrack.MODE_STREAM)
        streamTrack?.play()

        streamThread = thread(start = true) {
            val bufferSize = 1024
            val buffer = ShortArray(bufferSize)
            var iLong = 0L
            while (streaming && streamTrack != null) {
                for (i in 0 until bufferSize) {
                    val sample = (Short.MAX_VALUE * 0.25 * sin(2.0 * PI * iLong * freqHz / sampleRate)).toInt()
                    buffer[i] = sample.toShort()
                    iLong++
                }
                try {
                    streamTrack?.write(buffer, 0, bufferSize)
                } catch (_: Exception) {
// swallow write exceptions
                }
            }
            try { streamTrack?.stop() } catch (_: Exception) {}
            try { streamTrack?.release() } catch (_: Exception) {}
            streamTrack = null
        }
    }

    /** Stop any playing tone (static or streaming). */
    fun stop() {
        stopStatic()
        streaming = false
        try {
            streamThread?.join(200)
        } catch (_: Exception) {}
        streamThread = null
        streamTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        streamTrack = null
    }
}