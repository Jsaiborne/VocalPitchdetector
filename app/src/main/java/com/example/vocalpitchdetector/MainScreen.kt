@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.vocalpitchdetector

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {

        if (isLandscape) {
            // Top bar spanning full width: detected note, confidence, sliders, hold and gear
            TopAppBarLandscape(
                detectedFreq = state.frequency,
                detectedConfidence = state.confidence,
                activeMidi = activeMidi,
                whiteKeyWidthDpFloat = whiteKeyWidthDpFloat,
                onWhiteKeyWidthChange = { whiteKeyWidthDpFloat = it },
                graphAlignmentDp = graphAlignmentDp,
                onGraphAlignmentChange = { graphAlignmentDp = it },
                autoCenter = autoCenter,
                onAutoCenterToggle = { autoCenter = it },
                onTogglePause = { graphPaused = !graphPaused },
                // pass visual toggle state handlers
                showNoteLabels = showNoteLabels,
                onToggleShowNoteLabels = { showNoteLabels = it },
                showHorizontalGrid = showHorizontalGrid,
                onToggleShowHorizontalGrid = { showHorizontalGrid = it },
                showCurve = showCurve,
                onToggleShowCurve = { showCurve = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Main content: left piano, right graph — do NOT rotate outer boxes; use rotated=true in components
            Row(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)) {

                // Left piano column — instruct Piano to render rotated internally and share vertical scroll
                Box(modifier = Modifier
                    .fillMaxHeight()
                    .width(360.dp)
                    .padding(6.dp)) {
                    Piano(
                        startMidi = 24,
                        endMidi = 84,
                        onKeyPressed = { _, _ -> },
                        activeMidi = activeMidi,
                        autoCenter = autoCenter,
                        stableMidi = stableMidi,
                        whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                        scrollState = sharedScroll,
                        rotated = true,
                        blackKeyShiftFraction = 0.5f
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right graph area — ask PitchGraphCard to render rotated layout and share scroll vertically
                Box(modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(6.dp)) {
                    PitchGraphCard(
                        engine = engine,
                        modifier = Modifier.fillMaxSize(),
                        paused = graphPaused,
                        onTogglePause = { graphPaused = !graphPaused },
                        startMidi = 24,
                        endMidi = 84,
                        whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                        scrollState = sharedScroll,
                        alignmentOffsetDp = graphAlignmentDp.dp,
                        timeWindowMs = 8000L,
                        // pass visual toggle state
                        showNoteLabels = showNoteLabels,
                        showHorizontalGrid = showHorizontalGrid,
                        showCurve = showCurve,
                        rotated = true,
                        blackKeyShiftFraction = 0.5f
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom ad placeholder bar
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(text = "Ad placeholder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

        } else {
            // PORTRAIT: header row with hold + gear, info card, piano (horizontal), graph (horizontal)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Vocal Pitch Monitor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))

                // Auto-center switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Auto-center")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Hold button in portrait header
                Button(onClick = { graphPaused = !graphPaused }) {
                    Text(text = if (graphPaused) "Resume" else "Hold")
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Gear menu in portrait header
                var menuExpandedPortrait by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpandedPortrait = true }) { Icon(imageVector = Icons.Filled.Settings, contentDescription = "Options") }
                DropdownMenu(expanded = menuExpandedPortrait, onDismissRequest = { menuExpandedPortrait = false }) {
                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Note labels", modifier = Modifier.weight(1f))
                            Switch(checked = showNoteLabels, onCheckedChange = { showNoteLabels = it })
                        }
                    }, onClick = { })

                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Grid lines", modifier = Modifier.weight(1f))
                            Switch(checked = showHorizontalGrid, onCheckedChange = { showHorizontalGrid = it })
                        }
                    }, onClick = { })

                    DropdownMenuItem(text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Curve", modifier = Modifier.weight(1f))
                            Switch(checked = showCurve, onCheckedChange = { showCurve = it })
                        }
                    }, onClick = { })
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Small info card (reduced height)
            Card(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val freqText = if (state.frequency > 0f) "${String.format("%.1f", state.frequency)} Hz" else "--"
                    Text(text = "Frequency: $freqText")
                    Text(text = "Confidence: ${String.format("%.2f", state.confidence)}")
                    Spacer(modifier = Modifier.height(6.dp))
                    val noteText = if (activeMidi != null) midiToNoteName(activeMidi!!) else "-"
                    Text(text = "Detected: $noteText")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Key width control
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(text = "Key width: ${whiteKeyWidthDpFloat.toInt()} dp — approx ${(config.screenWidthDp / whiteKeyWidthDpFloat).toInt()} keys visible", style = MaterialTheme.typography.bodyMedium)
                Slider(value = whiteKeyWidthDpFloat, onValueChange = { whiteKeyWidthDpFloat = it }, valueRange = 36f..96f)
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
                scrollState = sharedScroll,
                rotated = false,
                blackKeyShiftFraction = 0.5f
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
                showNoteLabels = showNoteLabels,
                showHorizontalGrid = showHorizontalGrid,
                showCurve = showCurve,
                rotated = false,
                blackKeyShiftFraction = 0.5f
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom placeholder for ads (portrait)
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(text = "Ad placeholder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun TopAppBarLandscape(
    detectedFreq: Float,
    detectedConfidence: Float,
    activeMidi: Int?,
    whiteKeyWidthDpFloat: Float,
    onWhiteKeyWidthChange: (Float) -> Unit,
    graphAlignmentDp: Float,
    onGraphAlignmentChange: (Float) -> Unit,
    autoCenter: Boolean,
    onAutoCenterToggle: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
    // visual toggles lifted here so the gear lives in the top bar
    showNoteLabels: Boolean,
    onToggleShowNoteLabels: (Boolean) -> Unit,
    showHorizontalGrid: Boolean,
    onToggleShowHorizontalGrid: (Boolean) -> Unit,
    showCurve: Boolean,
    onToggleShowCurve: (Boolean) -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    val noteText = if (activeMidi != null) midiToNoteName(activeMidi) else "-"
                    Text(text = "$noteText")
                    Text(text = "Confidence: ${String.format("%.2f", detectedConfidence)}", style = MaterialTheme.typography.bodySmall)
                }

                // small controls in top bar
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Key width", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(value = whiteKeyWidthDpFloat, onValueChange = onWhiteKeyWidthChange, valueRange = 36f..96f, modifier = Modifier.width(140.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Align", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(value = graphAlignmentDp, onValueChange = onGraphAlignmentChange, valueRange = -16f..16f, modifier = Modifier.width(140.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Auto-centre", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(checked = autoCenter, onCheckedChange = onAutoCenterToggle)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Hold button moved to top bar
                    Button(onClick = onTogglePause) { Text("Hold") }

                    // Gear menu moved to top bar
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) { Icon(imageVector = Icons.Filled.Settings, contentDescription = "Graph options") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Note labels", modifier = Modifier.weight(1f))
                                Switch(checked = showNoteLabels, onCheckedChange = { onToggleShowNoteLabels(it) })
                            }
                        }, onClick = { })

                        DropdownMenuItem(text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Grid lines", modifier = Modifier.weight(1f))
                                Switch(checked = showHorizontalGrid, onCheckedChange = { onToggleShowHorizontalGrid(it) })
                            }
                        }, onClick = { })

                        DropdownMenuItem(text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Curve", modifier = Modifier.weight(1f))
                                Switch(checked = showCurve, onCheckedChange = { onToggleShowCurve(it) })
                            }
                        }, onClick = { })
                    }
                }
            }
        }
    )
}
