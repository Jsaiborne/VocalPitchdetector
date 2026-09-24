@file:Suppress("TooGenericExceptionCaught", "PrintStackTrace")

package com.jsaiborne.vocalpitchdetector

import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Serializes a recorded session to the JSON format read back by [PlaybackViewModel]. */
internal fun serializePitchSession(
    pitchPoints: List<RecordedPitchPoint>,
    stableNotes: List<RecordedPitchPoint>
): String {
    fun toJson(points: List<RecordedPitchPoint>): JSONArray {
        val array = JSONArray()
        for (point in points) {
            array.put(
                JSONObject().apply {
                    put("timestampMs", point.timestampMs)
                    put("frequencyHz", point.frequencyHz.toDouble())
                    put("midiNote", point.midiNote)
                }
            )
        }
        return array
    }

    return JSONObject().apply {
        put("pitchData", toJson(pitchPoints))
        put("stableNotes", toJson(stableNotes))
    }.toString()
}

@Suppress("TooManyFunctions")
class PitchEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val DEFAULT_BUFFER_SIZE = 2048
        private const val DEFAULT_HOP_SIZE = 512
        private const val DEFAULT_MIN_FREQ = 60f
        private const val DEFAULT_MAX_FREQ = 1200f
        private const val DEFAULT_SMOOTHING_ALPHA = 0.18f
        private const val DEFAULT_VOLUME_THRESHOLD = 0.02f
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.45f
        private const val DEFAULT_MIN_CONTIGUOUS_FRAMES = 4
    }

    data class PitchState(
        val frequency: Float = -1f,
        val confidence: Float = 0f,
        val timestampMs: Long = 0L
    )

    data class StableNote(
        val midi: Int,
        val frequency: Float,
        val timestampMs: Long
    )

    private val _state = MutableStateFlow(PitchState())
    val state: StateFlow<PitchState> = _state

    private val _volumeRms = MutableStateFlow(0f)
    val volumeRms: StateFlow<Float> = _volumeRms

    private val _stableNotes = MutableSharedFlow<StableNote>(extraBufferCapacity = 10)
    val stableNotes: SharedFlow<StableNote> = _stableNotes

    // --- Recording state (source of truth for the UI) ---
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private var detector: AudioRecordPitchDetector? = null

    @Volatile
    private var running = false

    private var volumeThreshold: Float = DEFAULT_VOLUME_THRESHOLD
    private var pitchConfidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    private var minContiguousFrames: Int = DEFAULT_MIN_CONTIGUOUS_FRAMES

    // Everything below is guarded by recordLock: it is written from the audio thread
    // (recorded points) and the UI thread (start/pause/resume/stop).
    private val recordLock = Any()

    // Time tracking variables to keep JSON aligned with the paused audio
    private var recordingStartTimeMs = 0L
    private var pauseStartTimeMs = 0L
    private var accumulatedPauseTimeMs = 0L

    private val recordedSession = mutableListOf<RecordedPitchPoint>()
    private val recordedStableNotes = mutableListOf<RecordedPitchPoint>()

    private var currentPitchFile: File? = null

    // Track the audio file so we can delete it if discarded
    private var currentAudioFile: File? = null

    fun start() {
        if (running) return

        detector = AudioRecordPitchDetector(
            sampleRate = DEFAULT_SAMPLE_RATE,
            bufferSize = DEFAULT_BUFFER_SIZE,
            hopSize = DEFAULT_HOP_SIZE,
            minFreq = DEFAULT_MIN_FREQ,
            maxFreq = DEFAULT_MAX_FREQ,
            smoothingAlpha = DEFAULT_SMOOTHING_ALPHA,
            pitchConfidenceThreshold = pitchConfidenceThreshold,
            minContiguousFrames = minContiguousFrames
        ).apply {
            this.volumeThreshold = this@PitchEngine.volumeThreshold

            start(
                onPitchDetected = { freqHz, confidence ->
                    _state.value = PitchState(
                        frequency = freqHz,
                        confidence = confidence,
                        timestampMs = System.currentTimeMillis()
                    )

                    if (freqHz > 0 && confidence >= pitchConfidenceThreshold && _isRecording.value) {
                        recordPoint(
                            target = recordedSession,
                            freqHz = freqHz,
                            midiNote = freqToMidi(freqHz.toDouble()).roundToInt()
                        )
                    }
                },
                onVolumeDetected = { rms ->
                    _volumeRms.value = rms
                },
                onStableNote = { midiNote, frequencyHz ->
                    _stableNotes.tryEmit(
                        StableNote(
                            midi = midiNote,
                            frequency = frequencyHz,
                            timestampMs = System.currentTimeMillis()
                        )
                    )

                    if (_isRecording.value) {
                        recordPoint(recordedStableNotes, frequencyHz, midiNote)
                    }
                }
            )
        }

        running = true
    }

    /** Adds a point to [target] unless recording has stopped or is paused. */
    private fun recordPoint(target: MutableList<RecordedPitchPoint>, freqHz: Float, midiNote: Int) {
        val now = System.currentTimeMillis()
        synchronized(recordLock) {
            if (!_isRecording.value || _isPaused.value) return
            val relativeTimeMs = now - recordingStartTimeMs - accumulatedPauseTimeMs
            target.add(RecordedPitchPoint(relativeTimeMs, freqHz, midiNote))
        }
    }

    fun startRecording(audioFile: File, pitchFile: File) {
        val activeDetector = detector ?: return
        if (_isRecording.value) return

        synchronized(recordLock) {
            recordedSession.clear()
            recordedStableNotes.clear()
            currentPitchFile = pitchFile
            currentAudioFile = audioFile
            recordingStartTimeMs = System.currentTimeMillis()
            accumulatedPauseTimeMs = 0L
            pauseStartTimeMs = 0L
            _isPaused.value = false
        }

        activeDetector.startDiskRecording(audioFile)
        _isRecording.value = true
    }

    /** Finishes the WAV file and saves the pitch trace next to it. Safe to call when not recording. */
    fun stopRecording() {
        if (!_isRecording.value) return

        val pitchFile: File?
        val pitchSnapshot: List<RecordedPitchPoint>
        val stableSnapshot: List<RecordedPitchPoint>
        synchronized(recordLock) {
            _isRecording.value = false
            _isPaused.value = false
            pitchFile = currentPitchFile
            pitchSnapshot = recordedSession.toList()
            stableSnapshot = recordedStableNotes.toList()
            recordedSession.clear()
            recordedStableNotes.clear()
            currentPitchFile = null
            currentAudioFile = null
        }

        detector?.stopDiskRecording()

        if (pitchFile == null) return

        // NonCancellable: the caller's scope may already be cancelled (e.g. the screen was left
        // mid-recording), but the file must still be written.
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                pitchFile.writeText(serializePitchSession(pitchSnapshot, stableSnapshot))
            } catch (e: java.io.IOException) {
                e.printStackTrace()
            }
        }
    }

    /** Stops recording and deletes the partial audio/pitch files. */
    fun cancelRecording() {
        val audioFile: File?
        val pitchFile: File?
        synchronized(recordLock) {
            _isRecording.value = false
            _isPaused.value = false
            audioFile = currentAudioFile
            pitchFile = currentPitchFile
            recordedSession.clear()
            recordedStableNotes.clear()
            currentAudioFile = null
            currentPitchFile = null
        }

        // Stop the underlying writer to release the file lock before deleting
        detector?.stopDiskRecording()

        try {
            audioFile?.let { if (it.exists()) it.delete() }
            pitchFile?.let { if (it.exists()) it.delete() }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun stop() {
        // Never drop an in-progress recording just because the screen went away.
        if (_isRecording.value) stopRecording()
        detector?.stop()
        detector = null
        running = false
    }

    fun pauseRecording() {
        synchronized(recordLock) {
            if (!_isRecording.value || _isPaused.value) return
            _isPaused.value = true
            pauseStartTimeMs = System.currentTimeMillis()
        }
        detector?.pauseDiskRecording()
    }

    fun resumeRecording() {
        synchronized(recordLock) {
            if (!_isRecording.value || !_isPaused.value) return
            accumulatedPauseTimeMs += System.currentTimeMillis() - pauseStartTimeMs
            _isPaused.value = false
        }
        detector?.resumeDiskRecording()
    }

    fun setVolumeThreshold(threshold: Float) {
        volumeThreshold = threshold.coerceIn(0f, 1f)
        detector?.volumeThreshold = volumeThreshold
    }

    fun setPitchConfidenceThreshold(threshold: Float) {
        pitchConfidenceThreshold = threshold.coerceIn(0f, 1f)
        detector?.pitchConfidenceThreshold = pitchConfidenceThreshold
    }

    fun setMinContiguousFrames(frames: Int) {
        minContiguousFrames = frames.coerceAtLeast(1)
        detector?.minContiguousFrames = minContiguousFrames
    }

    fun isRunning(): Boolean = running
}
