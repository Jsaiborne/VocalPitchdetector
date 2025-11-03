package com.example.vocalpitchdetector


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import kotlinx.coroutines.launch
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

    // graph pause/hold
    var graphPaused by remember { mutableStateOf(false) }

    // alignment tweak (dp) — small range to nudge pitch -> x mapping
    var graphAlignmentDp by remember { mutableStateOf(0f) } // in dp; can be negative

    // shared horizontal scroll state (piano + graph)
    val sharedScroll = rememberScrollState()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp), // reduced padding
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Vocal Pitch Monitor",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            // Auto-center switch (compact)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Auto-center", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.width(6.dp))
                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Compact info card (smaller height)
        Card(modifier = Modifier.fillMaxWidth().height(92.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val freqText = if (state.frequency > 0f) "${String.format("%.1f", state.frequency)} Hz" else "--"
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(text = "Frequency", style = MaterialTheme.typography.bodySmall)
                        Text(text = freqText, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = "Confidence", style = MaterialTheme.typography.bodySmall)
                        Text(text = String.format("%.2f", state.confidence), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                val noteText = if (activeMidi != null) midiToNoteName(activeMidi!!) else "-"
                Text(text = "Detected: $noteText", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Spacer(modifier = Modifier.height(8.dp))

        // Key width control — compact
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Key width: ${whiteKeyWidthDpFloat.toInt()} dp — approx ${approxVisibleKeys.toInt()} keys", style = MaterialTheme.typography.bodySmall)
                Slider(value = whiteKeyWidthDpFloat, onValueChange = { whiteKeyWidthDpFloat = it }, valueRange = 36f..96f)
            }
            Spacer(modifier = Modifier.width(8.dp))
            // alignment slider compact column
            Column(modifier = Modifier.width(140.dp)) {
                Text(text = "${String.format("%.1f", graphAlignmentDp)} dp", style = MaterialTheme.typography.bodySmall)
                Slider(value = graphAlignmentDp, onValueChange = { graphAlignmentDp = it }, valueRange = -16f..16f, steps = 32)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Piano: reduce height so it uses less vertical space
        Piano(
            startMidi = 24,
            endMidi = 84,
            scrollState = sharedScroll,          // <- shared scroll
            onKeyPressed = { _, _ -> },
            activeMidi = activeMidi,
            autoCenter = autoCenter,
            stableMidi = stableMidi,
            whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
            whiteKeyHeight = 140.dp,
            blackKeyShiftFraction = 0.5f        // ensure piano uses 0.5 fraction
        )


        Spacer(modifier = Modifier.height(10.dp))

        // Real-time pitch graph area (uses remaining vertical space)
        PitchGraphCard(
            engine = engine,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            paused = graphPaused,
            onTogglePause = { graphPaused = !graphPaused },
            startMidi = 24,
            endMidi = 84,
            whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
            scrollState = sharedScroll,         // <- same shared scroll
            alignmentOffsetDp = graphAlignmentDp.dp,
            blackKeyShiftFraction = 0.5f,       // same fraction for graph alignment
            timeWindowMs = 8000L
        )
        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(8.dp)
            .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(text = "Ad placeholder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }

    }
}
