// RecordingsBrowser.kt
package com.example.vocalpitchdetector

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.io.File

@Composable
fun RecordingsBrowser(
    onUseSamples: (List<PitchSample>) -> Unit,
    onUseMediaPlayer: (MediaPlayer) -> Unit
) {
    val ctx = LocalContext.current
    val dir = File(ctx.getExternalFilesDir(null), "recordings")
    val files = (dir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }.toList()

    Column {
        LazyColumn {
            items(files) { f ->
                // show only .wav or other audio; you can adjust
                if (f.isFile) {
                    Text(
                        text = f.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    // create MediaPlayer
                                    val mp = MediaPlayer()
                                    mp.setDataSource(f.absolutePath)
                                    mp.prepare()

                                    // find pitch JSON (two common variants)
                                    val base = f.nameWithoutExtension
                                    val candidates = listOf(
                                        File(dir, "${base}_pitch.json"),
                                        File(dir, "$base.json")
                                    )

                                    val jsonFile = candidates.firstOrNull { it.exists() }
                                    val samples = if (jsonFile != null) {
                                        parsePitchJson(jsonFile)
                                    } else emptyList()

                                    // normalize times so tMs starts at 0 if needed
                                    val normalized = normalizeSamples(samples)

                                    onUseMediaPlayer(mp)
                                    onUseSamples(normalized)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            .padding(12.dp)
                    )
                }
            }
        }
        if (files.isEmpty()) {
            Text("No recordings found", modifier = Modifier.padding(12.dp))
        }
    }
}

// Helper: parse JSON array of pitch samples [{ "tMs":123, "freq":440.0, "midi":69.0 }, ...]
private fun parsePitchJson(file: File): List<PitchSample> {
    return try {
        val text = file.readText()
        val arr = JSONArray(text)
        val list = mutableListOf<PitchSample>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val tMs = obj.getLong("tMs")
            val freq = obj.getDouble("freq").toFloat()
            val midi = if (obj.has("midi") && !obj.isNull("midi")) obj.getDouble("midi").toFloat() else null
            list.add(PitchSample(tMs = tMs, freq = freq, midi = midi))
        }
        list
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

// Normalize start time to 0 (if samples stored as absolute times)
private fun normalizeSamples(samples: List<PitchSample>): List<PitchSample> {
    if (samples.isEmpty()) return samples
    val base = samples.first().tMs
    return samples.map { s -> PitchSample(tMs = s.tMs - base, freq = s.freq, midi = s.midi) }
}
