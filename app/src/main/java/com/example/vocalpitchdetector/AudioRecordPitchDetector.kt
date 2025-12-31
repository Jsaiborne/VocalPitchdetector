package com.example.vocalpitchdetector

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * AudioRecord-based detector using YinPitchDetector + exponential smoothing + stability detection.
 *
 * start(onPitchDetected, onStableNote) emits rapid pitch updates via onPitchDetected (threaded callback)
 * and calls onStableNote when a note is held stable for N consecutive frames.
 *
 * Integration notes:
 * - If you want streaming-to-disk while detecting, set `externalRecordingManager = yourRecordingManager`
 *   (PitchEngine.attachRecordingManager(...) already wires this for you).
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
    private val shortBuffer = ShortArray(hopSize) // read by hopSize
    private val window = FloatArray(bufferSize)   // sliding window used by YIN

    // smoothing & stability state
    private var smoothedFreq = -1f
    private var stableCount = 0
    private var lastStableMidi = Int.MIN_VALUE

    /**
     * External RecordingManager bridge (optional). If set, the audio read loop will call
     * `externalRecordingManager?.writePcmFromShorts(shortBuffer, read)` for each successful read.
     */
    var externalRecordingManager: RecordingManager? = null

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
                Log.e(TAG, "AudioRecord not initialized (state=${audioRecord?.state})")
                onPitchDetected(-1f, 0f)
                return
            }

            audioRecord?.startRecording()
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", se)
            onPitchDetected(-1f, 0f)
            return
        } catch (ie: IllegalStateException) {
            Log.e(TAG, "AudioRecord.startRecording failed", ie)
            onPitchDetected(-1f, 0f)
            return
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            onPitchDetected(-1f, 0f)
            return
        }

        running.set(true)

        workerThread = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Throwable) {
                // ignore if we can't set priority
            }
            Thread.currentThread().name = "AudioRecordPitchDetector"

            // keep a simple sliding window: initially zeroed
            while (running.get()) {
                val read = try {
                    audioRecord?.read(shortBuffer, 0, hopSize) ?: -1
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord.read failed", e)
                    -1
                }

                if (read <= 0) {
                    // short-circuit if nothing read or error
                    continue
                }

                // Forward PCM to RecordingManager (safe-guarded)
                try {
                    externalRecordingManager?.writePcmFromShorts(shortBuffer, read)
                } catch (e: Exception) {
                    // protect audio thread from errors in recording manager
                    Log.w(TAG, "RecordingManager write failed", e)
                }

                // Shift window left by `read` samples then append new samples at end
                if (read < bufferSize) {
                    // shift left
                    System.arraycopy(window, read, window, 0, bufferSize - read)
                    // append normalized new samples in last `read` positions
                    val base = bufferSize - read
                    for (i in 0 until read) window[base + i] = shortBuffer[i] / 32768f
                } else {
                    // full overwrite (rare unless hopSize >= bufferSize)
                    for (i in 0 until bufferSize) window[i] = shortBuffer[i] / 32768f
                }

                // Compute pitch via YIN. Wrap in try/catch to avoid unexpected exceptions killing thread.
                val (freq, confidence) = try {
                    yin.getPitch(window, bufferSize)
                } catch (e: Exception) {
                    Log.w(TAG, "Yin.getPitch failed", e)
                    Pair(-1f, 0f)
                }

                if (freq <= 0f || confidence <= 0f) {
                    smoothedFreq = -1f
                    stableCount = 0
                    onPitchDetected(-1f, 0f)
                    continue
                }

                // exponential smoothing
                smoothedFreq = if (smoothedFreq <= 0f) freq else (smoothingAlpha * freq + (1f - smoothingAlpha) * smoothedFreq)

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
        }.also { it.start() }
    }

    fun stop() {
        running.set(false)
        // wait briefly for thread to exit
        try { workerThread?.join(300) } catch (_: InterruptedException) { }
        workerThread = null

        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
    }

    companion object {
        private const val TAG = "AudioRecordPitchDetector"
    }
}
