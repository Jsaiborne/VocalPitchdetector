package com.example.vocalpitchdetector

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }
    val state by engine.state.collectAsState()

// Auto-center toggle
    var autoCenter by remember { mutableStateOf(true) }

// track last stable note (used for auto-centering)
    var stableMidi by remember { mutableStateOf<Int?>(null) }

// white key width control (in dp)
    var whiteKeyWidthDpFloat by remember { mutableStateOf(56f) }

    DisposableEffect(Unit) {
        engine.start()
        onDispose { engine.stop() }
    }

// collect stable notes from engine and update stableMidi
    LaunchedEffect(engine) {
        engine.stableNotes.collect { note ->
            stableMidi = note.midi
        }
    }

// compute nearest MIDI note (nullable) for live highlighting
    val activeMidi: Int? by remember(state.frequency) {
        mutableStateOf(if (state.frequency > 0f) freqToMidi(state.frequency.toDouble()).roundToInt() else null)
    }

    val config = LocalConfiguration.current
    val approxVisibleKeys = (config.screenWidthDp / whiteKeyWidthDpFloat).coerceAtLeast(1f)

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Vocal Pitch Monitor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
// Auto-center switch
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Auto-center")
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
            }
        }

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

// Key width control
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(text = "Key width: ${whiteKeyWidthDpFloat.toInt()} dp — approx ${approxVisibleKeys.toInt()} keys visible", style = MaterialTheme.typography.bodyMedium)
            Slider(value = whiteKeyWidthDpFloat, onValueChange = { whiteKeyWidthDpFloat = it }, valueRange = 36f..96f)
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

// full piano with optional auto-centering on stable notes, passing the adjustable whiteKeyWidth
        Piano(
            startMidi = 24,
            endMidi = 84,
            onKeyPressed = { _, _ -> },
            activeMidi = activeMidi,
            autoCenter = autoCenter,
            stableMidi = stableMidi,
            whiteKeyWidthDp = whiteKeyWidthDpFloat.dp
        )

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