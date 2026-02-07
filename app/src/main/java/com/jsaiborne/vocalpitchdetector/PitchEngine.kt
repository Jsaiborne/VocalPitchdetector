package com.jsaiborne.vocalpitchdetector

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

    private val _stableNotes = MutableSharedFlow<StableNote>(replay = 0)
    val stableNotes: SharedFlow<StableNote> = _stableNotes

    private var detector: AudioRecordPitchDetector? = null
    private var running = false

    @Volatile
    private var volumeThreshold: Float = DEFAULT_VOLUME_THRESHOLD

    @Volatile
    private var pitchConfidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD

    @Volatile
    private var minContiguousFrames: Int = DEFAULT_MIN_CONTIGUOUS_FRAMES

    fun start() {
        if (running) return
        stop()

        detector = AudioRecordPitchDetector(
            sampleRate = DEFAULT_SAMPLE_RATE,
            bufferSize = DEFAULT_BUFFER_SIZE,
            hopSize = DEFAULT_HOP_SIZE,
            minFreq = DEFAULT_MIN_FREQ,
            maxFreq = DEFAULT_MAX_FREQ,
            smoothingAlpha = DEFAULT_SMOOTHING_ALPHA,
            pitchConfidenceThreshold = pitchConfidenceThreshold,
            minContiguousFrames = minContiguousFrames
        ).also { det ->
            det.volumeThreshold = volumeThreshold
            det.start(
                onPitchDetected = { freqHz, confidence ->
                    scope.launch {
                        _state.emit(
                            PitchState(
                                frequency = freqHz,
                                confidence = confidence,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    }
                },
                onStableNote = { midiNote, frequencyHz ->
                    scope.launch {
                        _stableNotes.emit(
                            StableNote(
                                midi = midiNote,
                                frequency = frequencyHz,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
            )
        }

        running = true
    }

    fun stop() {
        detector?.stop()
        detector = null
        running = false
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

    fun isRunning(): Boolean = running
}
