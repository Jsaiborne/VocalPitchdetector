package com.example.vocalpitchdetector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * PitchEngine backed by AudioRecordPitchDetector (YIN + smoothing + stability detection).
 * Exposes a StateFlow<PitchState> for live updates and a SharedFlow<StableNote> for stable-note events.
 *
 * This engine can optionally be wired to a RecordingManager to stream incoming PCM to disk while
 * the detector runs. Use attachRecordingManager(...) to attach a RecordingManager instance.
 */
class PitchEngine(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {

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

    private val _stableNotes = MutableSharedFlow<StableNote>(replay = 0)
    val stableNotes: SharedFlow<StableNote> = _stableNotes

    private var detector: AudioRecordPitchDetector? = null
    private var isRunning = false

    // Optional recording manager (set via attachRecordingManager)
    private var recordingManager: RecordingManager? = null

    /**
     * Attach a RecordingManager to the engine so the detector's audio loop forwards PCM to it.
     * If the detector is already created this will immediately wire it.
     */
    fun attachRecordingManager(manager: RecordingManager) {
        recordingManager = manager
        detector?.externalRecordingManager = manager
    }

    /**
     * Detach any previously attached RecordingManager.
     */
    fun detachRecordingManager() {
        detector?.externalRecordingManager = null
        recordingManager = null
    }

    /**
     * Convenience: start streaming-recording (calls RecordingManager.startRecording()).
     * No-op if no RecordingManager attached.
     */
    fun startRecording() {
        recordingManager?.startRecording()
    }

    /** Pause the recording stream (if recording). */
    fun pauseRecording() {
        recordingManager?.pauseRecording()
    }

    /** Resume the recording stream (if paused). */
    fun resumeRecording() {
        recordingManager?.resumeRecording()
    }

    /** Discard current recording (stop & delete temp). */
    fun discardRecording() {
        recordingManager?.discardRecording()
    }

    /**
     * Finalize & save the recording (WAV + pitch JSON). This is a suspend function and should be
     * called from a coroutine. Returns Pair(wavFile, jsonFile) or null on failure.
     */
    suspend fun stopAndSaveRecording(prefix: String = "rec"): Pair<File, File>? {
        return recordingManager?.stopAndSave(prefix)
    }

    fun start() {
        if (isRunning) return
        stop()

        detector = AudioRecordPitchDetector(
            sampleRate = 44100,
            bufferSize = 2048,
            hopSize = 512,
            minFreq = 60f,
            maxFreq = 1200f,
            smoothingAlpha = 0.25f,
            stabilityCentsThreshold = 30f,
            stabilityConfidenceThreshold = 0.55f,
            stabilityRequiredFrames = 3
        )

        // If a recordingManager was attached earlier, wire it into the new detector instance
        detector?.externalRecordingManager = recordingManager

        detector?.start({ freqHz, confidence ->
            // called on detector thread — forward to coroutine scope
            scope.launch {
                _state.emit(PitchState(freqHz, confidence, System.currentTimeMillis()))
            }
        }, { midiNote, frequencyHz ->
            scope.launch {
                _stableNotes.emit(StableNote(midiNote, frequencyHz, System.currentTimeMillis()))
            }
        })

        isRunning = true
    }

    fun stop() {
        detector?.externalRecordingManager = null
        detector?.stop()
        detector = null
        isRunning = false
    }
}
