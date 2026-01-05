package com.example.vocalpitchdetector

import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "RecordingControls"

/**
 * RecordingControls now accepts a RecordingManager instance and a PitchEngine instance.
 * Also accepts onOpenBrowser callback to open the recordings browser (Load button).
 */
@Composable
fun RecordingControls(
    engine: PitchEngine,
    sampleRate: Int,
    manager: RecordingManager,
    hasMicPermission: Boolean,
    requestMicPermission: () -> Unit,
    onOpenBrowser: () -> Unit  // NEW: open the recordings browser dialog
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
                    engine.startRecording()
                    isRecording = true
                    isPaused = false
                    lastSaved = null
                    Log.d(TAG, "Recording started (engine.startRecording)")
                }
            } else if (isPaused) {
                engine.resumeRecording()
                isPaused = false
                Log.d(TAG, "Recording resumed (engine.resumeRecording)")
            } else {
                engine.pauseRecording()
                isPaused = true
                Log.d(TAG, "Recording paused (engine.pauseRecording)")
            }
        }) {
            Text(text = if (!isRecording) "Record" else if (isPaused) "Resume" else "Pause")
        }

        Button(
            onClick = {
                if (isRecording && !isPaused) {
                    engine.pauseRecording()
                    isPaused = true
                }
            },
            enabled = isRecording && !isPaused
        ) { Text("Pause") }

        Button(onClick = {
            if (isRecording) {
                isRecording = false
                isPaused = false
            }
        }, enabled = isRecording) { Text("Stop") }

        Button(onClick = {
            if (!isRecording) {
                scope.launch {
                    try {
                        val pair = engine.stopAndSaveRecording(prefix = "rec")
                        if (pair != null) {
                            lastSaved = pair
                            Toast.makeText(ctx, "Saved: ${pair.first.name}", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "Save succeeded: ${pair.first.absolutePath}")
                        } else {
                            Toast.makeText(ctx, "Save failed: no recording data", Toast.LENGTH_LONG).show()
                            Log.w(TAG, "stopAndSaveRecording returned null — save failed or no data")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during stopAndSaveRecording", e)
                        Toast.makeText(ctx, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(ctx, "Stop recording before saving", Toast.LENGTH_SHORT).show()
            }
        }, enabled = !isRecording) { Text("Save") }

        Button(
            onClick = {
                lastSaved?.let { (wav, _) ->
                    try {
                        manager.shareFile(wav)
                        Log.d(TAG, "Share invoked for ${wav.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Share failed", e)
                        Toast.makeText(ctx, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } ?: run {
                    Toast.makeText(ctx, "No saved recording to send", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = lastSaved != null
        ) { Text("Send") }

        Button(onClick = {
            try {
                engine.discardRecording()
                isRecording = false
                isPaused = false
                lastSaved = null
                Toast.makeText(ctx, "Recording cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Discard failed", e)
                Toast.makeText(ctx, "Clear failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }) { Text("Discard") }

        // NEW: Load button that opens the RecordingsBrowser dialog in the parent
        Button(onClick = { onOpenBrowser() }) { Text("Load") }
    }
}
