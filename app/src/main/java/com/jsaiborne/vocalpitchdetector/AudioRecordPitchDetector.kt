@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Readings in the median that removes one-reading glitches; 3 delays the pitch by just one reading. */
private const val MEDIAN_READINGS = 3

/** How long a sudden octave jump inside a note is held back before it's taken as a real leap. */
private const val OCTAVE_HOLD_MS = 90f

@Suppress("LongParameterList")
class AudioRecordPitchDetector(
    private val sampleRate: Int = 44100,
    /** Samples analysed for each reading (the YIN window). */
    private val bufferSize: Int = 2048,
    /** Samples between readings; less than [bufferSize] makes each window overlap the one before. */
    private val hopSize: Int = bufferSize / 2,
    private val minFreq: Float = 60f,
    private val maxFreq: Float = 1200f,
    /** Time constant of the pitch smoothing; 0 turns it off. */
    smoothingMs: Float = 25f,
    private val stabilityCentsThreshold: Float = 30f,
    private val stabilityConfidenceThreshold: Float = 0.55f,
    /** How long the pitch must stay near one note for it to count as a stable note. */
    stabilityMs: Float = 140f,
    pitchConfidenceThreshold: Float = 0.45f,
    minVoicedMs: Float = 70f,
    /** How long a note may lose its pitch (a weak or quiet moment) and still carry on. */
    graceMs: Float = 70f
) {
    private var audioRecord: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null

    // Tunable while running (written from the UI thread, read on the audio thread)
    @Volatile
    var pitchConfidenceThreshold: Float = pitchConfidenceThreshold

    /** How long a sound must keep a pitch before it counts as a note. */
    @Volatile
    var minVoicedMs: Float = minVoicedMs

    // --- Disk Recording State (all access to the stream/counters is guarded by diskLock) ---
    private val diskLock = Any()
    private var recordingStream: DataOutputStream? = null
    private val isRecordingToDisk = AtomicBoolean(false)
    private val isDiskRecordingPaused = AtomicBoolean(false)
    private var recordedBytes = 0
    private var currentOutputFile: File? = null

    // Reuse a byte buffer for writing PCM to disk
    private val byteBuffer = ByteBuffer.allocate(bufferSize * 2).order(ByteOrder.LITTLE_ENDIAN)

    private val yin = YinPitchDetector(
        sampleRate = sampleRate,
        bufferSize = bufferSize,
        minFreq = minFreq,
        maxFreq = maxFreq,
        threshold = 0.25f
    )

    /** Time between readings, and half a window: a reading describes the middle of its window. */
    private val hopMs = hopSize * 1000f / sampleRate
    private val halfWindowMs = bufferSize * 500L / sampleRate

    // Readings arrive hopSize samples apart, so the tracker's timings and the smoothing use that spacing
    private val tracker = PitchTracker(
        sampleRate = sampleRate,
        hopSize = hopSize,
        medianWindow = MEDIAN_READINGS,
        smoothingAlpha = if (smoothingMs > 0f) 1.0 - exp(-hopMs / smoothingMs.toDouble()) else 1.0,
        octaveHoldFrames = framesFor(OCTAVE_HOLD_MS, hopMs)
    )

    private val gate = VoicingGate(graceFrames = framesFor(graceMs, hopMs))
    private val stabilityRequiredFrames = framesFor(stabilityMs, hopMs)

    var volumeThreshold: Float = 0.02f

    private var stableCount: Int = 0
    private var lastStableMidi: Int = -1

    // Set by start(), before the audio thread that calls them begins
    private var onPitch: (Float, Float, Long) -> Unit = { _, _, _ -> }
    private var onStable: ((Int, Float, Long) -> Unit)? = null

    /**
     * Opens the microphone and starts analysing. Returns false if the microphone could not be
     * opened (e.g. it is in use by a call or another app), so the caller can tell the user.
     *
     * [onPitchDetected] gets (frequency in Hz or -1 for silence, confidence, time in ms) and
     * [onStableNote] gets (MIDI note, frequency, time); times are wall-clock times of the audio they
     * describe, a little before the call. Silence is reported once, when a note ends.
     */
    @SuppressLint("MissingPermission")
    fun start(
        onPitchDetected: (Float, Float, Long) -> Unit,
        onVolumeDetected: (Float) -> Unit,
        onStableNote: ((Int, Float, Long) -> Unit)? = null
    ): Boolean {
        if (running.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val actualBufferSize = maxOf(bufferSize * 2, minBufferSize)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                actualBufferSize
            )
        } catch (e: IllegalArgumentException) {
            Log.e("AudioRecordPitch", "AudioRecord could not be created", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecordPitch", "AudioRecord initialization failed")
            record.release()
            return false
        }

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e("AudioRecordPitch", "AudioRecord could not start", e)
            record.release()
            return false
        }
        audioRecord = record
        onPitch = onPitchDetected
        onStable = onStableNote
        running.set(true)
        // Clears any pitch still showing from before
        onPitchDetected(-1f, 0f, System.currentTimeMillis())

        workerThread = Thread {
            val hop = ShortArray(hopSize)
            // The last bufferSize samples; each read slides it on by hopSize, so windows overlap
            val window = FloatArray(bufferSize)
            var filled = 0
            val clock = SampleClock(sampleRate)

            while (running.get()) {
                val read = audioRecord?.read(hop, 0, hopSize) ?: 0
                if (read > 0) {
                    val endMs = clock.advance(read, System.currentTimeMillis())

                    // Write raw PCM to disk if recording is active (only the new samples)
                    if (isRecordingToDisk.get() && !isDiskRecordingPaused.get()) {
                        writePcm(hop, read)
                    }

                    System.arraycopy(window, read, window, 0, bufferSize - read)
                    for (i in 0 until read) window[bufferSize - read + i] = hop[i] / 32768f
                    filled = minOf(bufferSize, filled + read)

                    val rms = rmsOf(window)
                    onVolumeDetected(rms)
                    if (filled == bufferSize) analyse(window, rms, timeMs = endMs - halfWindowMs)
                }
            }
        }
        workerThread?.priority = Thread.MAX_PRIORITY
        workerThread?.start()
        return true
    }

    /** Finds the pitch of one full [window] (centred at [timeMs]) and publishes what it leads to. */
    private fun analyse(window: FloatArray, rms: Float, timeMs: Long) {
        var pitch: TimedPitch? = null
        if (rms >= volumeThreshold) {
            val yinResult = yin.getPitch(window, bufferSize, rms.toDouble())
            val confidence = (1.0 - yinResult.cmndfMin).coerceIn(0.0, 1.0).toFloat()
            if (confidence >= pitchConfidenceThreshold) {
                val hz = tracker.processFrame(yinResult.pitchHz, yinResult.cmndfMin, rms.toDouble())
                if (hz != null) pitch = TimedPitch(timeMs, hz.toFloat(), confidence)
            }
        }
        val onsetFrames = framesFor(minVoicedMs, hopMs)
        publish(if (pitch != null) gate.voiced(pitch, onsetFrames) else gate.unvoiced(timeMs))
    }

    private fun publish(step: GateStep) {
        for (p in step.pitches) {
            onPitch(p.frequencyHz, p.confidence, p.timeMs)
            checkStability(p)
        }
        step.silenceAtMs?.let { onPitch(-1f, 0f, it) }
        if (step.resetTracker) {
            tracker.reset()
            stableCount = 0
            // Silence ends the note, so singing the same note again gets its own stable marker
            lastStableMidi = -1
        }
    }

    private fun writePcm(samples: ShortArray, count: Int) {
        synchronized(diskLock) {
            val stream = recordingStream ?: return
            try {
                byteBuffer.clear()
                for (i in 0 until count) {
                    byteBuffer.putShort(samples[i])
                }
                stream.write(byteBuffer.array(), 0, count * 2)
                recordedBytes += count * 2 // 2 bytes per Short
            } catch (e: java.io.IOException) {
                Log.e("AudioRecordPitch", "Failed to write audio stream", e)
            }
        }
    }

    // --- Recording Control Methods ---
    fun startDiskRecording(outputFile: File) {
        synchronized(diskLock) {
            try {
                currentOutputFile = outputFile
                recordedBytes = 0
                isDiskRecordingPaused.set(false)
                val stream = DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))
                // Write 44 bytes of empty space to hold the WAV header later
                stream.write(ByteArray(WAV_HEADER_BYTES))
                recordingStream = stream
                isRecordingToDisk.set(true)
            } catch (e: java.io.IOException) {
                Log.e("AudioRecordPitch", "Failed to start disk recording", e)
                currentOutputFile = null
            }
        }
    }

    fun stopDiskRecording() {
        synchronized(diskLock) {
            isRecordingToDisk.set(false)
            val stream = recordingStream
            val file = currentOutputFile
            recordingStream = null
            currentOutputFile = null
            if (stream == null || file == null) return

            try {
                stream.close()

                // Rewrite the file header with the exact byte lengths
                RandomAccessFile(file, "rw").use { raf ->
                    writeWavHeader(raf, recordedBytes, sampleRate, 1, 16)
                }
            } catch (e: java.io.IOException) {
                Log.e("AudioRecordPitch", "Error stopping disk recording", e)
            }
        }
    }

    // --- PAUSE methods ---
    fun pauseDiskRecording() {
        isDiskRecordingPaused.set(true)
    }

    fun resumeDiskRecording() {
        isDiskRecordingPaused.set(false)
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        running.set(false)
        Thread {
            try {
                workerThread?.join()
            } catch (e: InterruptedException) {
                Log.e("AudioRecordPitch", "Error joining worker thread", e)
            } finally {
                workerThread = null
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                onStopped?.invoke()
            }
        }.start()
    }

    private fun checkStability(pitch: TimedPitch) {
        val midiFloat = freqToMidi(pitch.frequencyHz.toDouble())
        val nearestMidi = midiFloat.roundToInt()
        val cents = (midiFloat - nearestMidi) * 100f

        if (abs(cents) <= stabilityCentsThreshold && pitch.confidence >= stabilityConfidenceThreshold) {
            stableCount++
        } else {
            stableCount = 0
        }

        if (stableCount >= stabilityRequiredFrames) {
            if (lastStableMidi != nearestMidi) {
                lastStableMidi = nearestMidi
                onStable?.invoke(nearestMidi, pitch.frequencyHz, pitch.timeMs)
            }
        }
    }

    private companion object {
        private const val WAV_HEADER_BYTES = 44
    }
}

/** The number of readings, [hopMs] apart, that span [ms]; at least one. */
private fun framesFor(ms: Float, hopMs: Float): Int = (ms / hopMs).roundToInt().coerceAtLeast(1)

/** Root-mean-square level of [samples]. */
private fun rmsOf(samples: FloatArray): Float {
    var sumSquares = 0f
    for (s in samples) sumSquares += s * s
    return sqrt(sumSquares / samples.size)
}

/** Writes a 44-byte PCM WAV header at the start of [raf] for [audioLen] bytes of sample data. */
internal fun writeWavHeader(
    raf: RandomAccessFile,
    audioLen: Int,
    sampleRate: Int,
    channels: Int,
    bitDepth: Int
) {
    val byteRate = sampleRate * channels * bitDepth / 8
    val totalDataLen = audioLen + 36

    // RandomAccessFile writes in Big Endian, so we use reverseBytes for Little Endian WAV spec
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
    raf.writeShort(
        java.lang.Short.reverseBytes((channels * bitDepth / 8).toShort()).toInt()
    ) // BlockAlign
    raf.writeShort(java.lang.Short.reverseBytes(bitDepth.toShort()).toInt()) // BitsPerSample
    raf.write("data".toByteArray(Charsets.US_ASCII))
    raf.writeInt(Integer.reverseBytes(audioLen))
}
