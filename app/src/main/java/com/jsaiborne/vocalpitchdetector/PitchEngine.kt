@file:Suppress("TooGenericExceptionCaught", "PrintStackTrace")

package com.jsaiborne.vocalpitchdetector

import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
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

        /** Samples analysed for each reading (~46 ms, enough for the lowest notes). */
        private const val DEFAULT_BUFFER_SIZE = 2048

        /** Samples between readings (~23 ms), so each analysis overlaps the one before by half. */
        private const val DEFAULT_HOP_SIZE = 1024
        private const val DEFAULT_MIN_FREQ = 60f
        private const val DEFAULT_MAX_FREQ = 1200f

        /** Time constant of the pitch smoothing: light, so vibrato and scoops still show. */
        private const val DEFAULT_SMOOTHING_MS = 25f
        private const val DEFAULT_VOLUME_THRESHOLD = 0.02f
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.45f

        /** How long a sound must keep a pitch before it counts as a note. */
        private const val DEFAULT_MIN_VOICED_MS = 70f

        /** How long a note may lose its pitch (a weak or quiet moment) and still carry on. */
        private const val DEFAULT_GRACE_MS = 70f
        private const val SENSITIVE_CONFIDENCE_THRESHOLD = 0.30f
        private const val SENSITIVE_MIN_VOICED_MS = 45f

        /** Readings [readings] keeps for a collector that falls behind (~1.5 s). */
        private const val READINGS_BUFFER = 64
    }

    /**
     * The latest reading: [frequency] in Hz (-1 for silence), and [timestampMs], the wall-clock time of
     * the audio it describes (slightly before it was published).
     */
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

    private val _readings = MutableSharedFlow<PitchState>(
        extraBufferCapacity = READINGS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Every reading in order, as published to [state], including the ones [state] skips over while its
     * collectors are busy. Nothing is replayed to a new collector. For the live graph.
     */
    val readings: SharedFlow<PitchState> = _readings

    private val _volumeRms = MutableStateFlow(0f)
    val volumeRms: StateFlow<Float> = _volumeRms

    private val _stableNotes = MutableSharedFlow<StableNote>(extraBufferCapacity = 10)
    val stableNotes: SharedFlow<StableNote> = _stableNotes

    // --- Recording state (source of truth for the UI) ---
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    /** True when the microphone couldn't be opened (in use elsewhere); the UI offers a retry. */
    private val _micUnavailable = MutableStateFlow(false)
    val micUnavailable: StateFlow<Boolean> = _micUnavailable

    private var detector: AudioRecordPitchDetector? = null

    @Volatile
    private var running = false

    private var volumeThreshold: Float = DEFAULT_VOLUME_THRESHOLD
    private var pitchConfidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    private var minVoicedMs: Float = DEFAULT_MIN_VOICED_MS

    // Everything below is guarded by recordLock: it is written from the audio thread
    // (recorded points) and the UI thread (start/pause/resume/stop).
    private val recordLock = Any()

    // Time tracking variables to keep JSON aligned with the paused audio
    private var recordingStartTimeMs = 0L
    private var pauseStartTimeMs = 0L
    private var accumulatedPauseTimeMs = 0L

    // When recording last started or resumed; readings of audio from before then aren't recorded
    private var recordingSegmentStartMs = 0L

    private val recordedSession = mutableListOf<RecordedPitchPoint>()
    private val recordedStableNotes = mutableListOf<RecordedPitchPoint>()

    private var currentPitchFile: File? = null

    // Track the audio file so we can delete it if discarded
    private var currentAudioFile: File? = null

    /**
     * Opens the microphone and starts detecting pitch. Returns false (and sets [micUnavailable])
     * when the microphone can't be opened, e.g. during a phone call; calling it again retries.
     */
    fun start(): Boolean {
        if (running) return true

        val newDetector = AudioRecordPitchDetector(
            sampleRate = DEFAULT_SAMPLE_RATE,
            bufferSize = DEFAULT_BUFFER_SIZE,
            hopSize = DEFAULT_HOP_SIZE,
            minFreq = DEFAULT_MIN_FREQ,
            maxFreq = DEFAULT_MAX_FREQ,
            smoothingMs = DEFAULT_SMOOTHING_MS,
            pitchConfidenceThreshold = pitchConfidenceThreshold,
            minVoicedMs = minVoicedMs,
            graceMs = DEFAULT_GRACE_MS
        )
        newDetector.volumeThreshold = volumeThreshold

        val started = newDetector.run {
            start(
                onPitchDetected = { freqHz, confidence, timeMs -> publishReading(freqHz, confidence, timeMs) },
                onVolumeDetected = { rms ->
                    _volumeRms.value = rms
                },
                onStableNote = { midiNote, frequencyHz, timeMs ->
                    _stableNotes.tryEmit(
                        StableNote(
                            midi = midiNote,
                            frequency = frequencyHz,
                            timestampMs = timeMs
                        )
                    )

                    if (_isRecording.value) {
                        recordPoint(recordedStableNotes, frequencyHz, midiNote, timeMs)
                    }
                }
            )
        }

        _micUnavailable.value = !started
        if (!started) return false
        detector = newDetector
        running = true
        return true
    }

    /** Recording length so far, excluding paused time (0 when not recording). */
    fun recordingElapsedMs(): Long = synchronized(recordLock) {
        if (!_isRecording.value) return@synchronized 0L
        val end = if (_isPaused.value) pauseStartTimeMs else System.currentTimeMillis()
        (end - recordingStartTimeMs - accumulatedPauseTimeMs).coerceAtLeast(0L)
    }

    /** Publishes a reading from the detector (on the audio thread) and records it if recording. */
    private fun publishReading(freqHz: Float, confidence: Float, timeMs: Long) {
        // Silence is reported once, when a note ends; publishing it again would only make the UI
        // recompose while nobody is singing
        if (freqHz <= 0f && _state.value.frequency <= 0f) return
        val reading = PitchState(frequency = freqHz, confidence = confidence, timestampMs = timeMs)
        _state.value = reading
        _readings.tryEmit(reading)

        if (freqHz > 0 && confidence >= pitchConfidenceThreshold && _isRecording.value) {
            recordPoint(
                target = recordedSession,
                freqHz = freqHz,
                midiNote = freqToMidi(freqHz.toDouble()).roundToInt(),
                timeMs = timeMs
            )
        }
    }

    /**
     * Adds a point for audio at wall-clock [timeMs] to [target], unless recording has stopped or is
     * paused, or the audio is from before recording started or resumed.
     */
    private fun recordPoint(target: MutableList<RecordedPitchPoint>, freqHz: Float, midiNote: Int, timeMs: Long) {
        synchronized(recordLock) {
            if (!_isRecording.value || _isPaused.value || timeMs < recordingSegmentStartMs) return
            val relativeTimeMs = timeMs - recordingStartTimeMs - accumulatedPauseTimeMs
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
            recordingSegmentStartMs = recordingStartTimeMs
            accumulatedPauseTimeMs = 0L
            pauseStartTimeMs = 0L
            _isPaused.value = false
        }

        activeDetector.startDiskRecording(audioFile)
        _isRecording.value = true
    }

    /**
     * Finishes the WAV file and saves the pitch trace next to it, off the main thread. Returns the
     * save [Job] so callers can wait before telling the user it's saved or listing recordings; null
     * when nothing was recording.
     */
    fun stopRecording(): Job? {
        if (!_isRecording.value) return null

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

        // Captured now: stop() may clear the detector field while this save is still running
        val activeDetector = detector

        // NonCancellable: the caller's scope may already be cancelled (e.g. the screen was left
        // mid-recording), but the files must still be written.
        return scope.launch(Dispatchers.IO + NonCancellable) {
            activeDetector?.stopDiskRecording()
            if (pitchFile == null) return@launch
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
            val now = System.currentTimeMillis()
            accumulatedPauseTimeMs += now - pauseStartTimeMs
            recordingSegmentStartMs = now
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

    /** How long a sound must keep a pitch before it's shown as a note. */
    fun setMinVoicedMs(ms: Float) {
        minVoicedMs = ms.coerceAtLeast(0f)
        detector?.minVoicedMs = minVoicedMs
    }

    /**
     * Relaxes the engine's own pitch gates so quieter or breathier voices are still picked up
     * (used by the vocal range test), or restores the normal defaults.
     */
    fun setSensitiveDetection(enabled: Boolean) {
        setPitchConfidenceThreshold(
            if (enabled) SENSITIVE_CONFIDENCE_THRESHOLD else DEFAULT_CONFIDENCE_THRESHOLD
        )
        setMinVoicedMs(if (enabled) SENSITIVE_MIN_VOICED_MS else DEFAULT_MIN_VOICED_MS)
    }

    fun isRunning(): Boolean = running
}

/**
 * Holds the one [PitchEngine] for the whole process, so an in-progress recording keeps going when
 * the screen leaves composition, the app is backgrounded, or the activity is destroyed while
 * [RecordingService] keeps the process alive.
 */
object PitchEngineProvider {
    private var engine: PitchEngine? = null

    /** True while the main screen is on screen; lets the service know whether it may release the mic. */
    @Volatile
    var uiAttached: Boolean = false

    @Synchronized
    fun get(): PitchEngine = engine ?: PitchEngine().also { engine = it }
}
