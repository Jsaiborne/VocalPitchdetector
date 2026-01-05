package com.example.vocalpitchdetector

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TopAppBarLandscapeCompact(
    detectedFreq: Float,
    detectedConfidence: Float,
    activeMidi: Int?,
    whiteKeyWidthDpFloat: Float,
    onWhiteKeyWidthChange: (Float) -> Unit,
    autoCenter: Boolean,
    onAutoCenterToggle: (Boolean) -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    showNoteLabels: Boolean,
    onToggleShowNoteLabels: (Boolean) -> Unit,
    showHorizontalGrid: Boolean,
    onToggleShowHorizontalGrid: (Boolean) -> Unit,
    showCurve: Boolean,
    onToggleShowCurve: (Boolean) -> Unit,
    smoothing: Float,
    onSmoothingChange: (Float) -> Unit,
    showWhiteTrace: Boolean,
    onShowWhiteTraceChange: (Boolean) -> Unit
) {
    SmallTopAppBarCompat(titleContent = {
        Row {
            Text("Freq: ${String.format("%.1f", detectedFreq)} Hz")
            Text(
                "  Conf: ${String.format("%.2f", detectedConfidence)}",
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }, actions = {
        IconButton(onClick = onTogglePause) {
            Text(if (paused) "Resume" else "Pause")
        }
    })
}
