package com.example.vocalpitchdetector

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import org.json.JSONArray

/**
 * RecordingControls now accepts a RecordingManager instance.
 * This prevents duplicate RecordingManager creation (use the single instance
 * created in MainScreen and pass it here).
 */
@Composable
fun RecordingControls(
    engine: PitchEngine,
    sampleRate: Int,
    manager: RecordingManager,
    hasMicPermission: Boolean,
    requestMicPermission: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<Pair<File, File>?>(null) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
        Button(onClick = {
            if (!isRecording) {
                // Request mic permission if needed
                if (!hasMicPermission) {
                    requestMicPermission()
                } else {
                    manager.startRecording()
                    isRecording = true
                    isPaused = false
                    lastSaved = null
                }
            } else if (isPaused) {
                manager.resumeRecording()
                isPaused = false
            }
        }) {
            Text(text = if (!isRecording) "Record" else if (isPaused) "Resume" else "Recording…")
        }

        Button(
            onClick = { if (isRecording && !isPaused) { manager.pauseRecording(); isPaused = true } },
            enabled = isRecording && !isPaused
        ) { Text("Pause") }

        Button(onClick = {
            if (isRecording) {
                // Stop in UI only — don't discard the buffered samples until user presses Save
                isRecording = false
                isPaused = false
            }
        }, enabled = isRecording) { Text("Stop") }

        Button(onClick = {
            if (!isRecording) {
                scope.launch {
                    val pair = manager.stopAndSave(prefix = "rec")
                    if (pair != null) lastSaved = pair
                }
            }
        }, enabled = !isRecording) { Text("Save") }

        Button(
            onClick = { lastSaved?.let { (wav, _) -> manager.shareFile(wav) } },
            enabled = lastSaved != null
        ) { Text("Send") }

        Button(onClick = {
            manager.discardRecording()
            isRecording = false
            isPaused = false
            lastSaved = null
        }) { Text("Discard") }
    }
}


/* ---------------------------
   RecordingsBrowser & preview
   (unchanged except referencing the same project helpers)
   --------------------------- */

fun loadPitchJson(file: File): List<PitchSample> {
    return try {
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            PitchSample(
                tMs = o.getLong("tMs"),
                freq = o.getDouble("freq").toFloat(),
                midi = if (o.isNull("midi")) null else o.getDouble("midi").toFloat()
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun RecordingsBrowser(
    onUseSamples: ((List<PitchSample>) -> Unit)? = null,
    onUseMediaPlayer: ((MediaPlayer) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val recordingsDir = remember { ctx.getExternalFilesDir("recordings") }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var selectedSamples by remember { mutableStateOf<List<PitchSample>>(emptyList()) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(recordingsDir) {
        files = recordingsDir?.listFiles { f -> f.extension.equals("wav", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()
    }

    LaunchedEffect(selectedFile) {
        val file = selectedFile ?: return@LaunchedEffect

        mediaPlayer?.release()

        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { isPlaying = false }
        }
        mediaPlayer = mp
        onUseMediaPlayer?.invoke(mp)
        isPlaying = false

        val base = file.nameWithoutExtension
        val jsonCandidate = File(file.parentFile, "${base}_pitch.json")
        val samples = if (jsonCandidate.exists()) loadPitchJson(jsonCandidate) else emptyList()
        selectedSamples = samples
        onUseSamples?.invoke(samples)
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("Recordings", modifier = Modifier.padding(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { f ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedFile = f }
                        .padding(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = f.name, color = if(selectedFile == f) Color.Blue else Color.Black)
                        Text(text = "size: ${f.length() / 1024} KB", modifier = Modifier.padding(top = 4.dp))
                    }

                    Button(onClick = {
                        if (selectedFile != f) {
                            selectedFile = f
                        } else {
                            mediaPlayer?.let { mp ->
                                if (mp.isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    mp.start()
                                    isPlaying = true
                                }
                            }
                        }
                    }) {
                        Text(if (selectedFile == f && isPlaying) "Pause" else "Play")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedSamples.isNotEmpty()) {
            SimplePitchPreview(samples = selectedSamples, modifier = Modifier.fillMaxWidth().height(180.dp))
        } else if (selectedFile != null) {
            Text("No pitch data for selected recording", modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun SimplePitchPreview(samples: List<PitchSample>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(8.dp)) {
        if (samples.isEmpty()) return@Canvas

        val t0 = samples.first().tMs.toDouble()
        val t1 = samples.last().tMs.toDouble()
        val width = size.width
        val height = size.height

        val freqs = samples.mapNotNull { if (it.freq > 0f) it.freq.toDouble() else null }
        val fmin = if (freqs.isEmpty()) 50.0 else freqs.minOrNull() ?: 50.0
        val fmax = if (freqs.isEmpty()) 2000.0 else freqs.maxOrNull() ?: 2000.0

        val logDiff = if (fmax != fmin) log10(fmax) - log10(fmin) else 1.0

        val points = samples.map { s ->
            val x = if (t1 == t0) 0f else ((s.tMs - t0) / (t1 - t0) * width).toFloat()
            val f = if (s.freq <= 0f) fmin else s.freq.toDouble()
            val y = (1.0 - (log10(f) - log10(fmin)) / logDiff).coerceIn(0.0, 1.0) * height
            Offset(x, y.toFloat())
        }

        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                drawLine(
                    color = Color.Green,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 2f
                )
            }
        }
    }
}
