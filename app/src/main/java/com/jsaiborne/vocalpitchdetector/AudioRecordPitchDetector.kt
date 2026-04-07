@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("LongParameterList")
class AudioRecordPitchDetector(
    private val sampleRate: Int = 44100,
    private val bufferSize: Int = 2048,
    private val hopSize: Int = 512,
    private val minFreq: Float = 60f,
    private val maxFreq: Float = 1200f,
    private val smoothingAlpha: Float = 0.45f,
    private val stabilityCentsThreshold: Float = 30f,
    private val stabilityConfidenceThreshold: Float = 0.55f,
    private val stabilityRequiredFrames: Int = 3,
    private val pitchConfidenceThreshold: Float = 0.45f,
    private val minContiguousFrames: Int = 4
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // --- NEW: Disk Recording State ---
    private var recordingStream: DataOutputStream? = null
    private val isRecordingToDisk = AtomicBoolean(false)
    private val isDiskRecordingPaused = AtomicBoolean(false)
    private var recordedBytes = 0
    private var currentOutputFile: File? = null

    private val yin = YinPitchDetector(
        sampleRate = sampleRate,
        bufferSize = bufferSize,
        minFreq = minFreq,
        maxFreq = maxFreq
    )

    private val tracker = PitchTracker(
        sampleRate = sampleRate,
        hopSize = hopSize,
        smoothingAlpha = smoothingAlpha.toDouble()
    )

    var volumeThreshold: Float = 0.02f

    private var smoothedFreq: Float = -1f
    private var framesWithPitch: Int = 0
    private var stableCount: Int = 0
    private var lastStableMidi: Int = -1

    @SuppressLint("MissingPermission")
    fun start(
        onPitchDetected: (Float, Float) -> Unit,
        onVolumeDetected: (Float) -> Unit,
        onStableNote: ((Int, Float) -> Unit)? = null
    ) {
        if (running.get()) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val actualBufferSize = maxOf(bufferSize * 2, minBufferSize)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            actualBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecordPitch", "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        running.set(true)
        resetPitchState(onPitchDetected)

        workerThread = Thread {
            val audioBuffer = ShortArray(bufferSize)
            val floatBuffer = FloatArray(bufferSize)

            while (running.get()) {
                val read = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    // --- NEW: Write raw PCM to disk if recording is active ---
                    if (isRecordingToDisk.get() && !isDiskRecordingPaused.get()) {
                        try {
                            for (i in 0 until read) {
                                // WAV expects Little Endian, AudioBuffer gives Big Endian natively
                                recordingStream?.writeShort(java.lang.Short.reverseBytes(audioBuffer[i]).toInt())
                            }
                            recordedBytes += read * 2 // 2 bytes per Short
                        } catch (e: Exception) {
                            Log.e("AudioRecordPitch", "Failed to write audio stream", e)
                        }
                    }

                    // 1. Calculate RMS volume
                    var sumSquares = 0f
                    for (i in 0 until read) {
                        val norm = audioBuffer[i] / 32768f
                        floatBuffer[i] = norm
                        sumSquares += norm * norm
                    }
                    val rms = kotlin.math.sqrt(sumSquares / read)
                    onVolumeDetected(rms)

                    // 2. Pitch Detection
                    if (rms >= volumeThreshold) {
                        val yinResult = yin.getPitch(floatBuffer, read, rms.toDouble())
                        val confidence = (1.0 - yinResult.cmndfMin).coerceIn(0.0, 1.0).toFloat()

                        if (confidence >= pitchConfidenceThreshold) {
                            val finalPitch = tracker.processFrame(
                                yinResult.pitchHz,
                                rms.toDouble(),
                                confidence.toDouble()
                            )
                            if (finalPitch != null) {
                                framesWithPitch++
                                if (framesWithPitch >= minContiguousFrames) {
                                    smoothedFreq = finalPitch.toFloat()
                                    onPitchDetected(smoothedFreq, confidence)
                                    checkStability(smoothedFreq, confidence, onStableNote)
                                }
                            } else {
                                resetPitchState(onPitchDetected)
                            }
                        } else {
                            resetPitchState(onPitchDetected)
                        }
                    } else {
                        resetPitchState(onPitchDetected)
                    }
                }
            }
        }
        workerThread?.priority = Thread.MAX_PRIORITY
        workerThread?.start()
    }

    // --- NEW: Recording Control Methods ---
    fun startDiskRecording(outputFile: File) {
        try {
            currentOutputFile = outputFile
            recordedBytes = 0
            recordingStream = DataOutputStream(FileOutputStream(outputFile))
            // Write 44 bytes of empty space to hold the WAV header later
            recordingStream?.write(ByteArray(44))
            isRecordingToDisk.set(true)
        } catch (e: Exception) {
            Log.e("AudioRecordPitch", "Failed to start disk recording", e)
        }
    }

    fun stopDiskRecording() {
        isRecordingToDisk.set(false)
        try {
            recordingStream?.close()
            recordingStream = null

            // Rewrite the file header with the exact byte lengths
            currentOutputFile?.let { file ->
                RandomAccessFile(file, "rw").use { raf ->
                    writeWavHeader(raf, recordedBytes, sampleRate, 1, 16)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecordPitch", "Error stopping disk recording", e)
        } finally {
            currentOutputFile = null
        }
    }

    // --- PAUSE methods ---
    fun pauseDiskRecording() {
        isDiskRecordingPaused.set(true)
    }

    fun resumeDiskRecording() {
        isDiskRecordingPaused.set(false)
    }

    // -----------------------------
    private fun writeWavHeader(raf: RandomAccessFile, audioLen: Int, sampleRate: Int, channels: Int, bitDepth: Int) {
        val byteRate = sampleRate * channels * bitDepth / 8
        val totalDataLen = audioLen + 36

        // RandomAccessFile writes in Big Endian, so we use Integer.reverseBytes for Little Endian WAV spec
        raf.seek(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.writeInt(Integer.reverseBytes(totalDataLen))
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.writeInt(Integer.reverseBytes(16)) // Subchunk1Size (16 for PCM)
        raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat (1 for PCM)
        raf.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
        raf.writeInt(Integer.reverseBytes(sampleRate))
        raf.writeInt(Integer.reverseBytes(byteRate))
        raf.writeShort(java.lang.Short.reverseBytes((channels * bitDepth / 8).toShort()).toInt()) // BlockAlign
        raf.writeShort(java.lang.Short.reverseBytes(bitDepth.toShort()).toInt()) // BitsPerSample
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.writeInt(Integer.reverseBytes(audioLen))
    }
    // --------------------------------------

    fun stop() {
        running.set(false)
        workerThread?.join()
        workerThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun resetPitchState(onPitchDetected: (Float, Float) -> Unit) {
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
}
