package com.jsaiborne.vocalpitchdetector

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
@Suppress("LongParameterList")
class AudioRecordPitchDetector(
    private val sampleRate: Int = 44100,
    private val bufferSize: Int = 2048,
    private val hopSize: Int = 512,
    private val minFreq: Float = 60f,
    private val maxFreq: Float = 1200f,
    private val smoothingAlpha: Float = 0.18f, // Default updated to match Tracker default
    private val stabilityCentsThreshold: Float = 30f,
    private val stabilityConfidenceThreshold: Float = 0.55f,
    private val stabilityRequiredFrames: Int = 3,
    private val pitchConfidenceThreshold: Float = 0.45f,
    private val minContiguousFrames: Int = 4
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    private val yin = YinPitchDetector(
        sampleRate = sampleRate,
        bufferSize = bufferSize, // Note: bufferSize in YIN should match window size
        minFreq = minFreq,
        maxFreq = maxFreq
    )
    private val shortBuffer = ShortArray(hopSize)

    // State
    private var smoothedFreq = -1f
    private var stableCount = 0
    private var framesWithPitch = 0
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
                Log.e("PitchDetector", "AudioRecord not initialized")
                return
            }
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e("PitchDetector", "AudioRecord init failed", e)
            return
        }

        running.set(true)

        // Fix: Use the class-level smoothingAlpha
        val pitchTracker = PitchTracker(
            sampleRate = sampleRate,
            hopSize = hopSize,
            hangoverMs = 200,
            medianWindow = 5,
            smoothingAlpha = smoothingAlpha.toDouble(),
            energyThreshold = volumeThreshold.toDouble(),
            confidenceThreshold = pitchConfidenceThreshold.toDouble()
        )

        workerThread = Thread {
            val window = FloatArray(bufferSize)

            // Priority boost for audio thread
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

            while (running.get()) {
                val read = try {
                    audioRecord?.read(shortBuffer, 0, hopSize) ?: -1
                } catch (e: Exception) { -1 }

                if (read <= 0) continue

                // Shift window and append new data
                System.arraycopy(window, read, window, 0, bufferSize - read)
                for (i in 0 until read) {
                    window[bufferSize - read + i] = shortBuffer[i] / 32768f
                }

                // 1. Calculate RMS once
                var sumSq = 0f
                for (i in 0 until bufferSize) {
                    val v = window[i]
                    sumSq += v * v
                }
                val rms = sqrt(sumSq / bufferSize)

                // Gate: Early exit on silence
                if (rms < volumeThreshold) {
                    handleSilence(onPitchDetected)
                    continue
                }

                // 2. Pitch Detection (Pass the calculated RMS!)
                val yinRes = yin.getPitch(window, bufferSize, rms.toDouble())

                // 3. Track & Smooth
                val outHz = pitchTracker.processFrame(yinRes.pitchHz, yinRes.cmndfMin, yinRes.rms)

                if (outHz == null) {
                    handleSilence(onPitchDetected)
                    continue
                }

                // 4. Debounce
                framesWithPitch++
                if (framesWithPitch < minContiguousFrames) {
                    onPitchDetected(-1f, 0f)
                    continue
                }

                smoothedFreq = outHz.toFloat()
                val confidence = (1.0 - yinRes.cmndfMin).coerceIn(0.0, 1.0).toFloat()

                onPitchDetected(smoothedFreq, confidence)
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

    // Helper to keep utils internal
    private companion object {
        private const val A4_MIDI = 69.0
        private const val A4_FREQ_HZ = 440.0
        private const val SEMITONES_PER_OCTAVE = 12.0
        private const val OCTAVE_RATIO = 2.0
    }
    private fun freqToMidi(freq: Double): Double =
        A4_MIDI +
            SEMITONES_PER_OCTAVE *
            kotlin.math.ln(freq / A4_FREQ_HZ) /
            kotlin.math.ln(OCTAVE_RATIO)

    fun stop() {
        running.set(false)
        try { workerThread?.join(300) } catch (_: Exception) {}
        workerThread = null
    }
}
