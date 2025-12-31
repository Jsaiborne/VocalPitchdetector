package com.example.vocalpitchdetector

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Coordinates pitch-sample collection (from engine.state Flow) and streaming audio to disk.
 * - call startRecording() to begin (creates temp wav in cacheDir and starts wavStreamer)
 * - your audio read loop should call writePcmFromShorts(shorts, len) while recording
 * - call stopAndSave() to finalize wav, move to external files dir and write pitch JSON
 */
class RecordingManager(
    private val context: Context,
    private val engine: PitchEngine,
    private val sampleRate: Int,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val samples = mutableListOf<Triple<Long, Float, Float>>() // (tMs, freq, midi)
    private var collectJob: Job? = null
    private var isRecordingInternal = false
    private var isPausedInternal = false

    private var wavStreamer: WavStreamer? = null
    private var tempWavFile: File? = null

    val isRecording: Boolean get() = isRecordingInternal
    val isPaused: Boolean get() = isPausedInternal

    fun startRecording() {
// clear previous
        samples.clear()
        collectJob?.cancel()
        tempWavFile?.delete()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        tempWavFile = File(context.cacheDir, "rec_temp_$ts.wav")
        tempWavFile!!.parentFile?.mkdirs()

        wavStreamer = WavStreamer(sampleRate)
        wavStreamer?.start(tempWavFile!!)

        isRecordingInternal = true
        isPausedInternal = false

        collectJob = scope.launch {
            engine.state.collectLatest { s ->
                if (!isRecordingInternal || isPausedInternal) return@collectLatest
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidi(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(Triple(t, s.frequency, midiF))
            }
        }
    }

    fun pauseRecording() {
        if (!isRecordingInternal) return
        isPausedInternal = true
        wavStreamer?.pause()
    }

    fun resumeRecording() {
        if (!isRecordingInternal) return
        isPausedInternal = false
        wavStreamer?.resume()
    }

    /**
     * Called from the audio read loop to stream captured PCM to disk.
     * Safe to call from audio thread.
     */
    fun writePcmFromShorts(shorts: ShortArray, len: Int) {
        wavStreamer?.writeFromShorts(shorts, len)
    }

    /**
     * Finalize WAV, move to external files dir and write pitch JSON. Returns pair(wav,json) or null on error.
     */
    suspend fun stopAndSave(prefix: String = "rec"): Pair<File, File>? = withContext(Dispatchers.IO) {
        if (!isRecordingInternal) return@withContext null
        isRecordingInternal = false
        isPausedInternal = false

        val finalized = wavStreamer?.stopAndFinalize() ?: return@withContext null

        val outDir = File(context.getExternalFilesDir(null), "recordings")
        if (!outDir.exists()) outDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val finalWav = File(outDir, "${prefix}_${timestamp}.wav")
        finalized.copyTo(finalWav, overwrite = true)
        try { finalized.delete() } catch (_: Exception) {}

        val jsonFile = File(outDir, "${prefix}_${timestamp}_pitch.json")
        val arr = JSONArray()
        for ((t, f, m) in samples) {
            val o = JSONObject().apply {
                put("tMs", t)
                put("freq", f)
                put("midi", if (m.isNaN()) JSONObject.NULL else m)
            }
            arr.put(o)
        }
        FileOutputStream(jsonFile).use { it.write(arr.toString(2).toByteArray()) }

        samples.clear()
        collectJob?.cancel()
        collectJob = null
        wavStreamer = null
        tempWavFile = null

        return@withContext Pair(finalWav, jsonFile)
    }

    fun discardRecording() {
        isRecordingInternal = false
        isPausedInternal = false
        collectJob?.cancel()
        collectJob = null
        samples.clear()
        wavStreamer?.discard()
        wavStreamer = null
        try { tempWavFile?.delete() } catch (_: Exception) {}
        tempWavFile = null
    }

    /** Share a file using FileProvider */
    fun shareFile(file: File, chooserTitle: String = "Send recording") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension.lowercase(Locale.US)) {
                "wav" -> "audio/wav"
                "json" -> "application/json"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}