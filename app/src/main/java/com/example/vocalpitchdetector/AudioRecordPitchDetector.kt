package com.example.vocalpitchdetector

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import android.annotation.SuppressLint
import android.util.Log
import kotlin.collections.get
import kotlin.compareTo
import kotlin.div
import kotlin.inc
import kotlin.text.compareTo
import kotlin.text.get
import kotlin.text.set
import kotlin.text.toDouble
import kotlin.times

/**
 * AudioRecord-based detector using YinPitchDetector + exponential smoothing + stability detection.
 *
 * start(onPitchDetected, onStableNote) emits rapid pitch updates via onPitchDetected (threaded callback)
 * and calls onStableNote when a note is held stable for N consecutive frames.
 */
class AudioRecordPitchDetector(
    private val sampleRate: Int = 44100,
    private val bufferSize: Int = 2048,
    private val hopSize: Int = 512,
    private val minFreq: Float = 60f,
    private val maxFreq: Float = 1200f,
    private val smoothingAlpha: Float = 0.25f,
    private val stabilityCentsThreshold: Float = 30f,
    private val stabilityConfidenceThreshold: Float = 0.55f,
    private val stabilityRequiredFrames: Int = 3
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // detector and buffers
    private val yin = YinPitchDetector(sampleRate = sampleRate, bufferSize = bufferSize, minFreq = minFreq, maxFreq = maxFreq)
    private val floatBuffer = FloatArray(bufferSize)
    private val shortBuffer = ShortArray(bufferSize)

    // smoothing & stability state
    private var smoothedFreq = -1f
    private var stableCount = 0
    private var lastStableMidi = Int.MIN_VALUE


    @SuppressLint("MissingPermission")
    fun start(
        onPitchDetected: (frequencyHz: Float, confidence: Float) -> Unit,
        onStableNote: ((midiNote: Int, frequencyHz: Float) -> Unit)? = null
    ) {
        if (running.get()) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val readBufferBytes = maxOf(minBufferBytes, bufferSize * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                readBufferBytes
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecordPitchDetector", "AudioRecord not initialized")
                onPitchDetected(-1f, 0f)
                return
            }

            // startRecording can throw SecurityException if permission is missing
            audioRecord?.startRecording()
        } catch (se: SecurityException) {
            Log.e("AudioRecordPitchDetector", "Missing RECORD_AUDIO permission", se)
            onPitchDetected(-1f, 0f)
            return
        } catch (ie: IllegalStateException) {
            Log.e("AudioRecordPitchDetector", "AudioRecord.startRecording failed", ie)
            onPitchDetected(-1f, 0f)
            return
        } catch (e: Exception) {
            Log.e("AudioRecordPitchDetector", "AudioRecord init failed", e)
            onPitchDetected(-1f, 0f)
            return
        }

        running.set(true)

        workerThread = Thread {
            val window = FloatArray(bufferSize)

            while (running.get()) {
                val read = try {
                    audioRecord?.read(shortBuffer, 0, hopSize) ?: -1
                } catch (e: Exception) {
                    Log.e("AudioRecordPitchDetector", "AudioRecord.read failed", e)
                    -1
                }

                if (read <= 0) continue

                // shift window left and append new samples
                val shift = read
                if (shift < bufferSize) {
                    System.arraycopy(window, shift, window, 0, bufferSize - shift)
                    for (i in 0 until read) window[bufferSize - read + i] = shortBuffer[i] / 32768f
                } else {
                    for (i in 0 until bufferSize) window[i] = shortBuffer[i] / 32768f
                }

                val (freq, confidence) = yin.getPitch(window, bufferSize)

                if (freq <= 0f || confidence <= 0f) {
                    smoothedFreq = -1f
                    stableCount = 0
                    onPitchDetected(-1f, 0f)
                    continue
                }

                // smoothing
                if (smoothedFreq <= 0f) smoothedFreq = freq
                else smoothedFreq = smoothingAlpha * freq + (1f - smoothingAlpha) * smoothedFreq

                onPitchDetected(smoothedFreq, confidence)

                // stability detection in cents
                val midiFloat = freqToMidi(smoothedFreq.toDouble())
                val nearestMidi = midiFloat.roundToInt()
                val cents = (midiFloat - nearestMidi) * 100f

                if (abs(cents) <= stabilityCentsThreshold && confidence >= stabilityConfidenceThreshold) {
                    stableCount++
                } else {
                    stableCount = 0
                }

                if (stableCount >= stabilityRequiredFrames) {
                    if (lastStableMidi != nearestMidi) {
                        lastStableMidi = nearestMidi
                        onStableNote?.invoke(nearestMidi, smoothedFreq)
                    }
                }
            }

            try { audioRecord?.stop() } catch (_: Exception) { }
            audioRecord?.release()
            audioRecord = null
        }.also { it.start() }
    }
    fun stop() {
        running.set(false)
        try { workerThread?.join(300) } catch (_: InterruptedException) { }
        workerThread = null
    }
}
