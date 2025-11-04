package com.example.vocalpitchdetector

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.ScrollState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }
    val state by engine.state.collectAsState()

    // Auto-center toggle
    var autoCenter by remember { mutableStateOf(true) }

    // track last stable note (used for auto-centering)
    var stableMidi by remember { mutableStateOf<Int?>(null) }

    // white key width control (in dp). Interpreted as key *thickness* in rotated mode.
    var whiteKeyWidthDpFloat by remember { mutableStateOf(56f) }

    // graph pause/hold
    var graphPaused by remember { mutableStateOf(false) }

    // alignment tweak (dp) — small range to nudge pitch -> x mapping
    var graphAlignmentDp by remember { mutableStateOf(0f) } // in dp; can be negative

    // graph visual toggles (lifted so top bar can control them)
    var showNoteLabels by remember { mutableStateOf(true) }
    var showHorizontalGrid by remember { mutableStateOf(true) }
    var showCurve by remember { mutableStateOf(true) }

    // shared scroll state (will be used horizontally in portrait, vertically in landscape)
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
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLandscape) {
            // Top bar spanning full width: note, confidence, small controls, Hold + gear
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val noteText = activeMidi?.let { midiToNoteName(it) } ?: "-"
                            Text(text = noteText)
                            Text(
                                text = "Confidence: ${String.format("%.2f", state.confidence)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Controls on right
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Key width", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.width(8.dp))
                                Slider(
                                    value = whiteKeyWidthDpFloat,
                                    onValueChange = { whiteKeyWidthDpFloat = it },
                                    valueRange = 36f..96f,
                                    modifier = Modifier.width(140.dp)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Align", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.width(8.dp))
                                Slider(
                                    value = graphAlignmentDp,
                                    onValueChange = { graphAlignmentDp = it },
                                    valueRange = -16f..16f,
                                    modifier = Modifier.width(140.dp)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Auto-centre", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.width(6.dp))
                                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Hold
                            Button(onClick = { graphPaused = !graphPaused }) {
                                Text(if (graphPaused) "Resume" else "Hold")
                            }

                            // Gear menu
                            var menuExpanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Graph options")
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "Note labels", modifier = Modifier.weight(1f))
                                            Switch(checked = showNoteLabels, onCheckedChange = { showNoteLabels = it })
                                        }
                                    },
                                    onClick = { /* no-op; state changed via Switch */ }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "Grid lines", modifier = Modifier.weight(1f))
                                            Switch(checked = showHorizontalGrid, onCheckedChange = { showHorizontalGrid = it })
                                        }
                                    },
                                    onClick = { }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "Curve", modifier = Modifier.weight(1f))
                                            Switch(checked = showCurve, onCheckedChange = { showCurve = it })
                                        }
                                    },
                                    onClick = { }
                                )
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Main content: left piano, right graph (both reflowed for rotated appearance)
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Left piano column — in rotated mode we'll render piano vertically and make the scroll vertical
                Box(modifier = Modifier.fillMaxHeight().width(360.dp).padding(6.dp)) {
                    Piano(
                        startMidi = 24,
                        endMidi = 84,
                        onKeyPressed = { _, _ -> },
                        activeMidi = activeMidi,
                        autoCenter = autoCenter,
                        stableMidi = stableMidi,
                        whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                        blackKeyShiftFraction = 0.5f,
                        rotated = true,
                        scrollState = sharedScroll
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Graph (takes remaining space) — rotated appearance (pitch axis vertically aligned with piano). scrollState shared vertically.
                PitchGraphCard(
                    engine = engine,
                    modifier = Modifier.fillMaxHeight().weight(1f).padding(6.dp),
                    paused = graphPaused,
                    onTogglePause = { graphPaused = !graphPaused },
                    startMidi = 24,
                    endMidi = 84,
                    whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                    scrollState = sharedScroll,
                    alignmentOffsetDp = graphAlignmentDp.dp,
                    timeWindowMs = 8000L,
                    blackKeyShiftFraction = 0.5f,
                    rotated = true,
                    showNoteLabels = showNoteLabels,
                    showHorizontalGrid = showHorizontalGrid,
                    showCurve = showCurve
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom ad placeholder bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Ad placeholder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        } else {
            // PORTRAIT: original (horizontal piano on top, graph below). Keep controls in header + gear in header.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Vocal Pitch Monitor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Auto-center")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
                }

                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = { graphPaused = !graphPaused }) { Text(if (graphPaused) "Resume" else "Hold") }

                Spacer(modifier = Modifier.width(8.dp))
                // Gear for portrait
                var menuExpandedPortrait by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpandedPortrait = true }) {
                    Icon(imageVector = Icons.Filled.Settings, contentDescription = "Options")
                }
                DropdownMenu(expanded = menuExpandedPortrait, onDismissRequest = { menuExpandedPortrait = false }) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Note labels", modifier = Modifier.weight(1f))
                                Switch(checked = showNoteLabels, onCheckedChange = { showNoteLabels = it })
                            }
                        },
                        onClick = { }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Grid lines", modifier = Modifier.weight(1f))
                                Switch(checked = showHorizontalGrid, onCheckedChange = { showHorizontalGrid = it })
                            }
                        },
                        onClick = { }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Curve", modifier = Modifier.weight(1f))
                                Switch(checked = showCurve, onCheckedChange = { showCurve = it })
                            }
                        },
                        onClick = { }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Info card (short)
            val freqText = if (state.frequency > 0f) "${String.format("%.1f", state.frequency)} Hz" else "--"
            val noteText = activeMidi?.let { midiToNoteName(it) } ?: "-"
            Card(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "Frequency: $freqText")
                    Text(text = "Confidence: ${String.format("%.2f", state.confidence)}")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Detected: $noteText")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Controls: key width and align
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(
                    text = "Key width: ${whiteKeyWidthDpFloat.toInt()} dp — approx ${(config.screenWidthDp / whiteKeyWidthDpFloat).toInt()} keys visible",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(value = whiteKeyWidthDpFloat, onValueChange = { whiteKeyWidthDpFloat = it }, valueRange = 36f..96f)
                Text(text = "Align: ${String.format("%.1f", graphAlignmentDp)} dp", style = MaterialTheme.typography.bodySmall)
                Slider(value = graphAlignmentDp, onValueChange = { graphAlignmentDp = it }, valueRange = -16f..16f)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Piano (horizontal)
            Piano(
                startMidi = 24,
                endMidi = 84,
                onKeyPressed = { _, _ -> },
                activeMidi = activeMidi,
                autoCenter = autoCenter,
                stableMidi = stableMidi,
                whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                blackKeyShiftFraction = 0.5f,
                rotated = false,
                scrollState = sharedScroll
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Graph (horizontal)
            PitchGraphCard(
                engine = engine,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(6.dp),
                paused = graphPaused,
                onTogglePause = { graphPaused = !graphPaused },
                startMidi = 24,
                endMidi = 84,
                whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                scrollState = sharedScroll,
                alignmentOffsetDp = graphAlignmentDp.dp,
                timeWindowMs = 8000L,
                blackKeyShiftFraction = 0.5f,
                rotated = false,
                showNoteLabels = showNoteLabels,
                showHorizontalGrid = showHorizontalGrid,
                showCurve = showCurve
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Ad placeholder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
