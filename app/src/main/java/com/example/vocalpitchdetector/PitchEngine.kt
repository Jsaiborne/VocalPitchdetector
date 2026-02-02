package com.example.vocalpitchdetector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PitchEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
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

    @Volatile private var volumeThreshold: Float = 0.02f

    @Volatile private var pitchConfidenceThreshold: Float = 0.45f

    @Volatile private var minContiguousFrames: Int = 4

    fun start() {
        if (isRunning) return
        stop()

        detector = AudioRecordPitchDetector(
            sampleRate = 44100,
            bufferSize = 2048,
            hopSize = 512,
            minFreq = 60f,
            maxFreq = 1200f,
            smoothingAlpha = 0.18f, // Adjusted slightly lower for tighter tracking
            pitchConfidenceThreshold = pitchConfidenceThreshold,
            minContiguousFrames = minContiguousFrames
        ).also { det ->
            det.volumeThreshold = volumeThreshold
            det.start(
                onPitchDetected = { freqHz, confidence ->
                    scope.launch {
                        _state.emit(PitchState(freqHz, confidence, System.currentTimeMillis()))
                    }
                },
                onStableNote = { midiNote, frequencyHz ->
                    scope.launch {
                        _stableNotes.emit(StableNote(midiNote, frequencyHz, System.currentTimeMillis()))
                    }
                }
            )
        }
        isRunning = true
    }

    fun stop() {
        detector?.stop()
        detector = null
        isRunning = false
    }

    fun setVolumeThreshold(threshold: Float) {
        volumeThreshold = threshold.coerceIn(0f, 1f)
        detector?.volumeThreshold = volumeThreshold
    }

    fun setPitchConfidenceThreshold(threshold: Float) {
        pitchConfidenceThreshold = threshold.coerceIn(0f, 1f)
    }

    fun setMinContiguousFrames(frames: Int) {
        minContiguousFrames = frames.coerceAtLeast(1)
    }

    fun isRunning() = isRunning
}
