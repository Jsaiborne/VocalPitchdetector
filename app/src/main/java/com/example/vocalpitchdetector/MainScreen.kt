// MainScreen.kt
@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.vocalpitchdetector

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn

@Composable
fun MainScreen() {
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }
    val state by engine.state.collectAsState()

    val context = LocalContext.current

    // Persist across rotations using rememberSaveable
    var autoCenter by rememberSaveable { mutableStateOf(true) }
    var whiteKeyWidthDpFloat by rememberSaveable { mutableStateOf(56f) }
    var smoothing by rememberSaveable { mutableStateOf(0.5f) }
    var showNoteLabels by rememberSaveable { mutableStateOf(true) }
    var showHorizontalGrid by rememberSaveable { mutableStateOf(true) }
    var showCurve by rememberSaveable { mutableStateOf(true) }
    var showWhiteTrace by rememberSaveable { mutableStateOf(true) }
    var volumeThreshold by rememberSaveable { mutableStateOf(0.02f) } // normalized 0..1
    var thresholdDb by rememberSaveable { mutableStateOf(-34f) }
    var bpm by rememberSaveable { mutableStateOf(120f) }
    var showWhiteDots by rememberSaveable { mutableStateOf(true) } // <-- NEW

    // NEW: use sample player toggle
    var useSamplePlayer by rememberSaveable { mutableStateOf(false) }

    // NEW: show rectangular bars instead of dots
    var showBars by rememberSaveable { mutableStateOf(false) }

    // transient state that doesn't need to persist across rotation
    var graphPaused by remember { mutableStateOf(false) }
    var stableMidi by remember { mutableStateOf<Int?>(null) }
    var graphAlignmentDp by remember { mutableStateOf(0f) }

    // shared scroll state (will be used horizontally in portrait, vertically in landscape)
    val sharedScroll = rememberScrollState()

    // Start / stop engine as before
    DisposableEffect(Unit) {
        engine.start()
        engine.setVolumeThreshold(dbToRms(thresholdDb)) // apply initial
        onDispose { engine.stop() }
    }

    // Initialize / release SamplePlayer when toggled
    DisposableEffect(useSamplePlayer) {
        if (useSamplePlayer) {
            SamplePlayer.init(context)
        } else {
            // if toggled off, release resources
            SamplePlayer.release()
        }
        onDispose {
            // ensure release when composable leaves
            SamplePlayer.release()
        }
    }

    // collect stable notes from engine and update stableMidi
    LaunchedEffect(engine) {
        engine.stableNotes.collect { note -> stableMidi = note.midi }
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
            .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {

        if (isLandscape) {
            // Top bar spanning full width: detected note, confidence, compact controls (Hold & Gear only)
            TopAppBarLandscapeCompact(
                detectedFreq = state.frequency,
                detectedConfidence = state.confidence,
                activeMidi = activeMidi,
                // pass current settings
                whiteKeyWidthDpFloat = whiteKeyWidthDpFloat,
                onWhiteKeyWidthChange = { whiteKeyWidthDpFloat = it },
                autoCenter = autoCenter,
                onAutoCenterToggle = { autoCenter = it },
                paused = graphPaused,
                onTogglePause = { graphPaused = !graphPaused },
                showNoteLabels = showNoteLabels,
                onToggleShowNoteLabels = { showNoteLabels = it },
                showHorizontalGrid = showHorizontalGrid,
                onToggleShowHorizontalGrid = { showHorizontalGrid = it },
                showCurve = showCurve,
                onToggleShowCurve = { showCurve = it },
                smoothing = smoothing,
                onSmoothingChange = { smoothing = it },
                showWhiteTrace = showWhiteTrace,
                onShowWhiteTraceChange = { showWhiteTrace = it },
                thresholdDb = thresholdDb,
                onThresholdChange = { newDb ->
                    thresholdDb = newDb
                    engine.setVolumeThreshold(dbToRms(newDb))
                },
                bpm = bpm,
                onBpmChange = { bpm = it },
                // NEW: bars
                showBars = showBars,
                onToggleShowBars = { showBars = it },
                // NEW: sample player
                useSamplePlayer = useSamplePlayer,
                onToggleUseSamplePlayer = { useSamplePlayer = it },
                // NEW: white dots toggle (single occurrence)
                showWhiteDots = showWhiteDots,
                onShowWhiteDotsChange = { showWhiteDots = it }
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
                        blackKeyShiftFraction = 0.5f,
                        useSamplePlayer = useSamplePlayer
                    )
                }

                // <-- reduced gap to make more space for piano + graph -->
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
                        showNoteLabels = showNoteLabels,
                        showHorizontalGrid = showHorizontalGrid,
                        showCurve = showCurve,
                        rotated = true,
                        blackKeyShiftFraction = 0.5f,
                        smoothing = smoothing,
                        showWhiteTrace = showWhiteTrace,
                        showWhiteDots = showWhiteDots,
                        bpm = bpm,
                        // NEW: pass through the bars toggle
                        showBars = showBars
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

                Spacer(modifier = Modifier.width(12.dp))

                // Hold button in portrait header
                Button(onClick = { graphPaused = !graphPaused }) {
                    Text(text = if (graphPaused) "Resume" else "Hold")
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Gear menu in portrait header
                var menuExpandedPortrait by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpandedPortrait = true }) { Icon(imageVector = Icons.Filled.Settings, contentDescription = "Options") }
                DropdownMenu(
                    expanded = menuExpandedPortrait,
                    onDismissRequest = { menuExpandedPortrait = false },
                    modifier = Modifier.width(360.dp)
                ) {
                    // make the portrait menu scrollable with a constrained max height
                    val portraitMenuScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .heightIn(max = 360.dp)
                            .verticalScroll(portraitMenuScroll)
                    ) {
                        // --- TOGGLES: stacked vertically ---
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Note labels", modifier = Modifier.weight(1f))
                                Switch(checked = showNoteLabels, onCheckedChange = { showNoteLabels = it })
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Grid lines", modifier = Modifier.weight(1f))
                                Switch(checked = showHorizontalGrid, onCheckedChange = { showHorizontalGrid = it })
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Curve", modifier = Modifier.weight(1f))
                                Switch(checked = showCurve, onCheckedChange = { showCurve = it })
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show rectangular bars", modifier = Modifier.weight(1f))
                                Switch(checked = showBars, onCheckedChange = { showBars = it })
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Auto-center", modifier = Modifier.weight(1f))
                                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show white trace", modifier = Modifier.weight(1f))
                                Switch(checked = showWhiteTrace, onCheckedChange = { showWhiteTrace = it })
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show white dots", modifier = Modifier.weight(1f))
                                Switch(checked = showWhiteDots, onCheckedChange = { showWhiteDots = it })
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // NEW: Use piano samples toggle placed below the other toggles
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Use piano samples", modifier = Modifier.weight(1f))
                                Switch(checked = useSamplePlayer, onCheckedChange = { useSamplePlayer = it })
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // --- SLIDERS: stacked vertically ---
                        // Smoothing slider (0 = discrete, 1 = very smooth)
                        Text(text = "Smoothing: ${(smoothing * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Slider(
                            value = smoothing,
                            onValueChange = { smoothing = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // BPM slider (60 - 240)
                        Text(text = "Tempo: ${bpm.roundToInt()} BPM", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Slider(
                            value = bpm,
                            onValueChange = { newBpm -> bpm = newBpm },
                            valueRange = 60f..240f,
                            steps = 180, // 1 BPM increments across the 60..240 range
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Volume Threshold
                        Text(text = "Volume threshold: ${thresholdDb.roundToInt()} dB")
                        Spacer(modifier = Modifier.height(6.dp))
                        Slider(
                            value = thresholdDb,
                            onValueChange = { newDb ->
                                thresholdDb = newDb
                                val rms = dbToRms(thresholdDb)
                                engine.setVolumeThreshold(rms)
                            },
                            valueRange = -80f..-6f,
                            steps = 74, // gives ~1 dB steps
                            modifier = Modifier.fillMaxWidth()
                        )

                    }
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

            // Piano (horizontal) - pass useSamplePlayer
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
                blackKeyShiftFraction = 0.5f,
                useSamplePlayer = useSamplePlayer
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
                blackKeyShiftFraction = 0.5f,
                smoothing = smoothing,
                showWhiteTrace = showWhiteTrace,
                showWhiteDots = showWhiteDots,
                bpm = bpm,
                // NEW: pass through the bars toggle
                showBars = showBars
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

/**
 * Compact landscape TopAppBar:
 * - holds only Hold/Resume + Gear. Gear contains grouped toggles first, then sliders (no key width)
 * - app bar height reduced to reclaim vertical space in landscape
 */
@Composable
private fun TopAppBarLandscapeCompact(
    detectedFreq: Float,
    detectedConfidence: Float,
    activeMidi: Int?,
    whiteKeyWidthDpFloat: Float,
    onWhiteKeyWidthChange: (Float) -> Unit,
    autoCenter: Boolean,
    onAutoCenterToggle: (Boolean) -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    // visual toggles lifted here so the gear lives in the top bar
    showNoteLabels: Boolean,
    onToggleShowNoteLabels: (Boolean) -> Unit,
    showHorizontalGrid: Boolean,
    onToggleShowHorizontalGrid: (Boolean) -> Unit,
    showCurve: Boolean,
    onToggleShowCurve: (Boolean) -> Unit,
    smoothing: Float,
    onSmoothingChange: (Float) -> Unit,
    showWhiteTrace: Boolean,
    onShowWhiteTraceChange: (Boolean) -> Unit,
    thresholdDb: Float,
    onThresholdChange: (Float) -> Unit,
    bpm: Float,
    onBpmChange: (Float) -> Unit,
    // NEW: bars
    showBars: Boolean,
    onToggleShowBars: (Boolean) -> Unit,
    // NEW: sample player
    useSamplePlayer: Boolean,
    onToggleUseSamplePlayer: (Boolean) -> Unit,
    // NEW: white dots toggle (parameters must be declared like this)
    showWhiteDots: Boolean,
    onShowWhiteDotsChange: (Boolean) -> Unit
) {
    TopAppBar(
        // smaller height in landscape
        modifier = Modifier.height(32.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: note + frequency + confidence on one line to save horizontal space
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val noteText = if (activeMidi != null) midiToNoteName(activeMidi) else "-"
                    val freqText = if (detectedFreq > 0f) "${String.format("%.1f", detectedFreq)} Hz" else "--"
                    Text(text = noteText, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = freqText, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Confidence: ${String.format("%.2f", detectedConfidence)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Right compact controls: Hold & Gear only (gear contains grouped toggles + sliders)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.wrapContentWidth()
                ) {
                    // Hold / Resume — slightly smaller to free up space
                    Button(
                        onClick = onTogglePause,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (paused) "Resume" else "Hold", style = MaterialTheme.typography.labelSmall)
                    }

                    // Gear menu (graph options) - grouped toggles then sliders
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Graph options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.width(360.dp)
                    ) {
                        // make the landscape menu scrollable with constrained max height
                        val landscapeMenuScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .heightIn(max = 360.dp)
                                .verticalScroll(landscapeMenuScroll)
                        ) {
                            // --- TOGGLES ---
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Note labels", modifier = Modifier.weight(1f))
                                    Switch(checked = showNoteLabels, onCheckedChange = { onToggleShowNoteLabels(it) })
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Grid lines", modifier = Modifier.weight(1f))
                                    Switch(checked = showHorizontalGrid, onCheckedChange = { onToggleShowHorizontalGrid(it) })
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Curve", modifier = Modifier.weight(1f))
                                    Switch(checked = showCurve, onCheckedChange = { onToggleShowCurve(it) })
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Show rectangular bars", modifier = Modifier.weight(1f))
                                    Switch(checked = showBars, onCheckedChange = { onToggleShowBars(it) })
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Auto-center", modifier = Modifier.weight(1f))
                                    Switch(checked = autoCenter, onCheckedChange = { onAutoCenterToggle(it) })
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Show white trace", modifier = Modifier.weight(1f))
                                    Switch(checked = showWhiteTrace, onCheckedChange = { onShowWhiteTraceChange(it) })
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Show white dots", modifier = Modifier.weight(1f))
                                    Switch(checked = showWhiteDots, onCheckedChange = { onShowWhiteDotsChange(it) })
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // NEW: Use piano samples toggle placed below the other toggles
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(text = "Use piano samples", modifier = Modifier.weight(1f))
                                    Switch(checked = useSamplePlayer, onCheckedChange = { onToggleUseSamplePlayer(it) })
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(12.dp))

                            // --- SLIDERS ---
                            // Smoothing slider
                            Text(text = "Smoothing: ${(smoothing * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(6.dp))
                            Slider(
                                value = smoothing,
                                onValueChange = onSmoothingChange,
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // BPM (tempo) slider 60..240
                            Text(text = "Tempo: ${bpm.roundToInt()} BPM", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(6.dp))
                            Slider(
                                value = bpm,
                                onValueChange = onBpmChange,
                                valueRange = 60f..240f,
                                steps = 180,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Volume Threshold
                            Text(text = "Volume threshold: ${thresholdDb.roundToInt()} dB")
                            Spacer(modifier = Modifier.height(6.dp))
                            Slider(
                                value = thresholdDb,
                                onValueChange = onThresholdChange,
                                valueRange = -80f..-6f,
                                steps = 74, // ~1 dB steps
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    )
}
