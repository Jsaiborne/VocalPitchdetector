package com.example.vocalpitchdetector

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AudioRecord-based detector using YinPitchDetector + exponential smoothing + stability detection.
 *
 * Updated with "Attack/Release" gating to prevent low-frequency artifacts at the start/end of notes.
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
    private val stabilityRequiredFrames: Int = 3,
    // NEW: Minimum confidence to consider a pitch valid (prevents end-of-note tails)
    private val pitchConfidenceThreshold: Float = 0.45f,
    // NEW: How many valid frames required before we start outputting pitch (eats start-of-note noise)
    private val minContiguousFrames: Int = 4
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // detector and buffers
    private val yin = YinPitchDetector(
        sampleRate = sampleRate,
        bufferSize = bufferSize,
        minFreq = minFreq,
        maxFreq = maxFreq
    )
    private val shortBuffer = ShortArray(hopSize) // Read only hopSize

    // smoothing & stability state
    private var smoothedFreq = -1f
    private var stableCount = 0
    private var framesWithPitch = 0 // Counter for debounce logic
    private var lastStableMidi = Int.MIN_VALUE

    @Volatile
    var volumeThreshold: Float = 0.02f

    @SuppressLint("MissingPermission")
    fun start(
        onPitchDetected: (frequencyHz: Float, confidence: Float) -> Unit,
        onStableNote: ((midiNote: Int, frequencyHz: Float) -> Unit)? = null
    ) {
        if (running.get()) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // Ensure buffer is large enough for smooth reading
        val readBufferBytes = maxOf(minBufferBytes, bufferSize * 4)

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

            audioRecord?.startRecording()
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
                } catch (e: Exception) { -1 }

                if (read <= 0) continue

                // Shift window left by hopSize
                System.arraycopy(window, read, window, 0, bufferSize - read)
                // Append new samples (normalized)
                for (i in 0 until read) {
                    window[bufferSize - read + i] = shortBuffer[i] / 32768f
                }

                // 1. Volume Check
                var sumSq = 0f
                for (i in 0 until bufferSize) {
                    val v = window[i]
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / bufferSize)

                if (rms < volumeThreshold) {
                    handleSilence(onPitchDetected)
                    continue
                }

                // 2. Pitch Detection
                val (freq, confidence) = yin.getPitch(window, bufferSize)

                // 3. Confidence Gating (Fix for "End Tails")
                // If confidence is low, the pitch is likely dropping into noise. Cut it immediately.
                if (freq <= 0f || confidence < pitchConfidenceThreshold) {
                    handleSilence(onPitchDetected)
                    continue
                }

                // 4. Onset Delay (Fix for "Start Artifacts")
                // We found a pitch, but is it a real note or just a breath?
                framesWithPitch++

                if (framesWithPitch < minContiguousFrames) {
                    // We see pitch, but we are waiting to confirm it.
                    // Send -1 to keep graph gap open.
                    onPitchDetected(-1f, 0f)
                    continue
                }

                // 5. Smoothing
                // If we just came out of silence (framesWithPitch == minContiguousFrames),
                // snap directly to the new freq so we don't smooth from 0.
                if (smoothedFreq <= 0f || framesWithPitch == minContiguousFrames) {
                    smoothedFreq = freq
                } else {
                    smoothedFreq = smoothingAlpha * freq + (1f - smoothingAlpha) * smoothedFreq
                }

                onPitchDetected(smoothedFreq, confidence)

                // 6. Stability Logic (Unchanged)
                checkStability(smoothedFreq, confidence, onStableNote)
            }

            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }.also { it.start() }
    }

    private fun handleSilence(onPitchDetected: (Float, Float) -> Unit) {
        smoothedFreq = -1f
        framesWithPitch = 0
        stableCount = 0
        onPitchDetected(-1f, 0f)
    }

    private fun checkStability(
        freq: Float,
        confidence: Float,
        onStableNote: ((Int, Float) -> Unit)?
    ) {
        val midiFloat = freqToMidi(freq.toDouble())
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
                onStableNote?.invoke(nearestMidi, freq)
            }
        }
    }

    fun stop() {
        running.set(false)
        try { workerThread?.join(300) } catch (_: Exception) {}
        workerThread = null
    }
}
