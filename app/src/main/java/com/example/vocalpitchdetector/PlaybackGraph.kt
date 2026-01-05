// PlaybackGraph.kt
package com.example.vocalpitchdetector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackGraph(
    samples: List<PitchSample>,
    playbackPositionMs: Long? = null, // if null, show the full static trace
    modifier: Modifier = Modifier,
    startMidi: Int = 24,
    endMidi: Int = 84
) {
    if (samples.isEmpty()) return

    // compute bounds (samples are normalized to start at 0 by RecordingsBrowser)
    val totalMs = (samples.last().tMs - samples.first().tMs).coerceAtLeast(1L)
    val minMidi = startMidi
    val maxMidi = endMidi

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // helper maps
        fun xForTime(tMs: Long): Float {
            val rel = (tMs - samples.first().tMs).toFloat() / totalMs.toFloat()
            return rel * w
        }
        fun yForMidi(midiF: Float?): Float {
            if (midiF == null || midiF.isNaN()) return h / 2f
            val mf = midiF.coerceIn(minMidi.toFloat(), maxMidi.toFloat())
            val rel = (mf - minMidi) / (maxMidi - minMidi)
            // invert so low midi on bottom
            return h - rel * h
        }

        // choose which samples are visible based on playbackPositionMs
        val visibleUntil = playbackPositionMs ?: (samples.last().tMs - samples.first().tMs)
        val visible = samples.filter { (it.tMs - samples.first().tMs) <= visibleUntil }

        // draw path
        if (visible.size >= 2) {
            val path = Path()
            var started = false
            for (s in visible) {
                val x = xForTime(s.tMs)
                val y = yForMidi(s.midi ?: Float.NaN)
                if (!started) { path.moveTo(x, y); started = true }
                else path.lineTo(x, y)
            }
            drawPath(path = path, color = Color(0xFF7AD3FF))
        }

        // draw current cursor when playing
        if (playbackPositionMs != null) {
            val curX = xForTime(samples.first().tMs + playbackPositionMs)
            drawLine(color = Color.Yellow, start = Offset(curX, 0f), end = Offset(curX, h), strokeWidth = 2f)
        }
    }
}
