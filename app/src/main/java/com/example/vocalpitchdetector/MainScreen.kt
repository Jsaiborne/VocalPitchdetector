package com.example.vocalpitchdetector

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }
    val state by engine.state.collectAsState()

    DisposableEffect(Unit) {
        engine.start()
        onDispose { engine.stop() }
    }

// compute nearest MIDI note (nullable)
    val activeMidi: Int? by remember(state.frequency) {
        mutableStateOf(if (state.frequency > 0f) freqToMidi(state.frequency.toDouble()).roundToInt() else null)
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

        Text(text = "Vocal Pitch Monitor", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val freqText = if (state.frequency > 0f) "${String.format("%.1f", state.frequency)} Hz" else "--"
                Text(text = "Frequency: $freqText")
                Text(text = "Confidence: ${String.format("%.2f", state.confidence)}")
                Spacer(modifier = Modifier.height(6.dp))
                val noteText = if (activeMidi != null) midiToNoteName(activeMidi!!) else "-"
                Text(text = "Detected: $noteText")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { /* TODO: Record */ }) { Text("Record") }
            Button(onClick = { /* TODO: Open graph */ }) { Text("Graph") }
            Button(onClick = {
// Play middle A reference tone for quick check
                scope.launch { ToneGenerator.playTone(440.0, 600) }
            }) { Text("Play A4") }
        }

        Spacer(modifier = Modifier.height(16.dp))

// full piano
        Piano(startMidi = 24, endMidi = 84, onKeyPressed = { _, _ -> }, activeMidi = activeMidi)

        Spacer(modifier = Modifier.height(12.dp))

// Graph placeholder (will implement real-time graph next)
        Card(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Pitch graph (live) — coming next")
            }
        }
    }
}