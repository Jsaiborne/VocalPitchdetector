package com.jsaiborne.vocalpitchdetector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

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

    private var detector: AudioRecordPitchDetector? = null
    private var running = false

    private var volumeThreshold: Float = DEFAULT_VOLUME_THRESHOLD
    private var pitchConfidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    private var minContiguousFrames: Int = DEFAULT_MIN_CONTIGUOUS_FRAMES

    // --- Recording States ---
    private var isRecordingSession = false
    private var isRecordingPaused = false

    // Time tracking variables to keep JSON aligned with the paused audio
    private var recordingStartTimeMs = 0L
    private var pauseStartTimeMs = 0L
    private var accumulatedPauseTimeMs = 0L

    private val recordedSession = mutableListOf<RecordedPitchPoint>()
    private val recordedStableNotes = mutableListOf<RecordedPitchPoint>()

    private var currentPitchFile: File? = null
    // NEW: Track the audio file so we can delete it if discarded
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
                    scope.launch {
                        _state.emit(
                            PitchState(
                                frequency = freqHz,
                                confidence = confidence,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    }

                    if (isRecordingSession && !isRecordingPaused && freqHz > 0 && confidence >= pitchConfidenceThreshold) {
                        val relativeTimeMs = System.currentTimeMillis() - recordingStartTimeMs - accumulatedPauseTimeMs
                        val midiNote = freqToMidi(freqHz.toDouble()).roundToInt()

                        recordedSession.add(
                            RecordedPitchPoint(
                                timestampMs = relativeTimeMs,
                                frequencyHz = freqHz,
                                midiNote = midiNote
                            )
                        )
                    }
                },
                onVolumeDetected = { rms ->
                    _volumeRms.value = rms
                },
                onStableNote = { midiNote, frequencyHz ->
                    val now = System.currentTimeMillis()
                    scope.launch {
                        _stableNotes.emit(
                            StableNote(
                                midi = midiNote,
                                frequency = frequencyHz,
                                timestampMs = now
                            )
                        )
                    }

                    if (isRecordingSession && !isRecordingPaused) {
                        val relativeTimeMs = now - recordingStartTimeMs - accumulatedPauseTimeMs
                        recordedStableNotes.add(
                            RecordedPitchPoint(
                                timestampMs = relativeTimeMs,
                                frequencyHz = frequencyHz,
                                midiNote = midiNote
                            )
                        )
                    }
                }
            )
        }

        running = true
    }

    fun startRecording(audioFile: File, pitchFile: File) {
        isRecordingSession = true
        isRecordingPaused = false
        recordedSession.clear()
        recordedStableNotes.clear()

        currentPitchFile = pitchFile
        currentAudioFile = audioFile // NEW: Save reference for discard feature

        recordingStartTimeMs = System.currentTimeMillis()
        accumulatedPauseTimeMs = 0L
        pauseStartTimeMs = 0L

        detector?.startDiskRecording(audioFile)
    }

    fun stopRecording() {
        isRecordingSession = false
        isRecordingPaused = false
        detector?.stopDiskRecording()

        val pitchFile = currentPitchFile ?: return

        scope.launch(Dispatchers.IO) {
            try {
                val rootObj = JSONObject()

                // 1. Save Pitch Trace
                val pitchArray = JSONArray()
                for (point in recordedSession) {
                    val obj = JSONObject()
                    obj.put("timestampMs", point.timestampMs)
                    obj.put("frequencyHz", point.frequencyHz.toDouble())
                    obj.put("midiNote", point.midiNote)
                    pitchArray.put(obj)
                }
                rootObj.put("pitchData", pitchArray)

                // 2. Save Stable Markers
                val stableArray = JSONArray()
                for (point in recordedStableNotes) {
                    val obj = JSONObject()
                    obj.put("timestampMs", point.timestampMs)
                    obj.put("frequencyHz", point.frequencyHz.toDouble())
                    obj.put("midiNote", point.midiNote)
                    stableArray.put(obj)
                }
                rootObj.put("stableNotes", stableArray)

                pitchFile.writeText(rootObj.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                currentPitchFile = null
                currentAudioFile = null // NEW: Clear the audio file reference
                recordedSession.clear()
                recordedStableNotes.clear()
            }
        }
    }

    // --- NEW: Cancel & Delete Method ---
    fun cancelRecording() {
        isRecordingSession = false
        isRecordingPaused = false

        // Stop the underlying writer to release the file lock
        detector?.stopDiskRecording()

        // Delete the files if they were created
        try {
            currentAudioFile?.let { if (it.exists()) it.delete() }
            currentPitchFile?.let { if (it.exists()) it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Reset state
            currentAudioFile = null
            currentPitchFile = null
            recordedSession.clear()
            recordedStableNotes.clear()
        }
    }
    // -----------------------------------

    fun stop() {
        detector?.stop()
        detector = null
        running = false
    }

    fun pauseRecording() {
        detector?.pauseDiskRecording()
        if (!isRecordingPaused) {
            isRecordingPaused = true
            pauseStartTimeMs = System.currentTimeMillis()
        }
    }

    fun resumeRecording() {
        detector?.resumeDiskRecording()
        if (isRecordingPaused) {
            isRecordingPaused = false
            accumulatedPauseTimeMs += (System.currentTimeMillis() - pauseStartTimeMs)
        }
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
