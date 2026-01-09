package com.example.vocalpitchdetector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * PitchEngine backed by AudioRecordPitchDetector (YIN + smoothing + stability detection).
 * Exposes a StateFlow<PitchState> for live updates and a SharedFlow<StableNote> for stable-note events.
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

    // normalized RMS threshold (0f..1f). Default ~0.02 is a reasonable starting point.
    @Volatile
    private var volumeThreshold: Float = 0.02f

    fun start() {
        if (isRunning) return

        // ensure any previous detector is stopped/cleaned
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

        // apply stored threshold immediately
        detector?.volumeThreshold = volumeThreshold

        // wire detector callbacks -> flows
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
        detector?.stop()
        detector = null
        isRunning = false
    }

    /**
     * Set the volume (RMS) threshold used by the detector.
     * Threshold is normalized (0..1). This updates the running detector immediately if present.
     */
    fun setVolumeThreshold(threshold: Float) {
        volumeThreshold = threshold.coerceIn(0f, 1f)
        detector?.volumeThreshold = volumeThreshold
    }

    /** Optional convenience: expose running state. */
    fun isRunning(): Boolean = isRunning
}
