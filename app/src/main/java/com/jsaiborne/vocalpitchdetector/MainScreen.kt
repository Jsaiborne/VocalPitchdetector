@file:OptIn(ExperimentalMaterial3Api::class)

package com.jsaiborne.vocalpitchdetector

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import kotlin.math.roundToInt

@Composable
fun MainScreen(navController: NavHostController? = null) {
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }
    val state by engine.state.collectAsState()

    val context = LocalContext.current

    // small UI constants (purely visual)
    val outerPadding = 8.dp
    val smallGap = 8.dp
    val menuWidth = 320.dp
    val compactAppBarHeight = 40.dp

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
    // 1. Track whether we have permission in a Compose state
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 2. Create a launcher to request permission if we don't have it
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    // 3. Request permission on first launch if needed
    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 4. ONLY start the engine when hasMicPermission is true
    DisposableEffect(hasMicPermission) {
        if (hasMicPermission) {
            engine.start()
            engine.setVolumeThreshold(dbToRms(thresholdDb))
        }
        onDispose {
            engine.stop()
        }
    }

    // Initialize / release SamplePlayer when toggled
    DisposableEffect(useSamplePlayer) {
        if (useSamplePlayer) {
            SamplePlayer.init(context.applicationContext)
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
            .padding(outerPadding),
        horizontalAlignment = Alignment.CenterHorizontally
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
                // bars
                showBars = showBars,
                onToggleShowBars = { showBars = it },
                // sample player
                useSamplePlayer = useSamplePlayer,
                onToggleUseSamplePlayer = { useSamplePlayer = it },
                // white dots toggle
                showWhiteDots = showWhiteDots,
                onShowWhiteDotsChange = { showWhiteDots = it },
                // pass optional nav controller for About navigation
                navController = navController
            )

            Spacer(modifier = Modifier.height(smallGap))

            // Main content: left piano, right graph — do NOT rotate outer boxes; use rotated=true in components
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Left piano column — Reduced width from 360.dp to 250.dp to remove empty gap
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(250.dp) // <--- CHANGED: Reduced width significantly
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                ) { // <--- CHANGED: Tighter padding
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

                // <-- reduced spacer from 8.dp to 2.dp for tighter fit -->
                Spacer(modifier = Modifier.width(2.dp))

                // Right graph area — ask PitchGraphCard to render rotated layout and share scroll vertically
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                ) { // <--- CHANGED: Tighter padding
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
                        // pass through the bars toggle
                        showBars = showBars
                    )
                }
            }

            Spacer(modifier = Modifier.height(smallGap))
        } else {
            // PORTRAIT: header row with hold + gear, info card, piano (horizontal), graph (horizontal)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vocal Pitch Monitor",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Hold button in portrait header (slightly larger for touch)
                Button(
                    onClick = { graphPaused = !graphPaused },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = if (graphPaused) "Resume" else "Hold")
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Gear menu in portrait header
                var menuExpandedPortrait by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { menuExpandedPortrait = true },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open settings"
                    )
                }
                DropdownMenu(
                    expanded = menuExpandedPortrait,
                    onDismissRequest = { menuExpandedPortrait = false },
                    modifier = Modifier.width(menuWidth)
                ) {
                    // make the portrait menu scrollable with a constrained max height
                    val portraitMenuScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .heightIn(max = 360.dp)
                            .verticalScroll(portraitMenuScroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // --- TOGGLES: stacked vertically ---
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show note labels", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = showNoteLabels, onCheckedChange = { showNoteLabels = it })
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show grid lines", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = showHorizontalGrid, onCheckedChange = { showHorizontalGrid = it })
                            }

                            // COMBINED TOGGLE: Curve + White trace
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show curve & trace", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = showCurve && showWhiteTrace,
                                    onCheckedChange = { checked ->
                                        showCurve = checked
                                        showWhiteTrace = checked
                                    }
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show rectangular bars", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = showBars, onCheckedChange = { showBars = it })
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Auto-center", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Show white dots", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = showWhiteDots, onCheckedChange = { showWhiteDots = it })
                            }

                            // NEW: Use piano samples toggle placed below the other toggles
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(text = "Use piano samples", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = useSamplePlayer, onCheckedChange = { useSamplePlayer = it })
                            }
                        }

                        Divider()

                        // --- SLIDERS: stacked vertically ---
                        // Smoothing slider (0 = discrete, 1 = very smooth)
                        Text(
                            text = "Smoothing: ${(smoothing * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = smoothing,
                            onValueChange = { smoothing = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // BPM slider (60 - 240)
                        Text(text = "Tempo: ${bpm.roundToInt()} BPM", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = bpm,
                            onValueChange = { newBpm -> bpm = newBpm },
                            valueRange = 60f..240f,
                            steps = 180, // 1 BPM increments across the 60..240 range
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Volume Threshold
                        Text(text = "Volume threshold: ${thresholdDb.roundToInt()} dB", style = MaterialTheme.typography.bodySmall)
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

                    // Divider + About button (added)
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    DropdownMenuItem(
                        text = { Text("About") },
                        onClick = {
                            menuExpandedPortrait = false
                            navController?.navigate("about")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Small info card (reduced height) - subtle tonal container for readability
            // Small info card (reduced height) - subtle tonal container for readability
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val freqText = if (state.frequency > 0f) "${String.format("%.1f", state.frequency)} Hz" else "--"
                    Text(text = "Frequency: $freqText", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Confidence: ${String.format("%.2f", state.confidence)}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    val noteText = if (activeMidi != null) midiToNoteName(activeMidi!!) else "-"
                    Text(text = "Detected: $noteText", style = MaterialTheme.typography.bodyMedium)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(6.dp),
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
    onShowWhiteDotsChange: (Boolean) -> Unit,
    // optional nav controller to navigate to about
    navController: NavHostController? = null
) {
    TopAppBar(
        // slightly taller for reliable touch target in landscape
        modifier = Modifier.height(40.dp),
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
                    // Hold / Resume — slightly smaller to free up space (but still accessible)
                    Button(
                        onClick = onTogglePause,
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
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
                        modifier = Modifier.width(320.dp)
                    ) {
                        // make the landscape menu scrollable with constrained max height
                        val landscapeMenuScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .heightIn(max = 360.dp)
                                .verticalScroll(landscapeMenuScroll),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // --- TOGGLES ---
                            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Show note labels", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = showNoteLabels, onCheckedChange = { onToggleShowNoteLabels(it) })
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Show grid lines", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = showHorizontalGrid,
                                        onCheckedChange = { onToggleShowHorizontalGrid(it) }
                                    )
                                }

                                // COMBINED TOGGLE: Curve + White trace
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Show curve & trace", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = showCurve && showWhiteTrace,
                                        onCheckedChange = { checked ->
                                            onToggleShowCurve(checked)
                                            onShowWhiteTraceChange(checked)
                                        }
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Show rectangular bars", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = showBars, onCheckedChange = { onToggleShowBars(it) })
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Auto-center", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = autoCenter, onCheckedChange = { onAutoCenterToggle(it) })
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Show white dots", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = showWhiteDots, onCheckedChange = { onShowWhiteDotsChange(it) })
                                }

                                // NEW: Use piano samples toggle placed below the other toggles
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = "Use piano samples", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = useSamplePlayer, onCheckedChange = { onToggleUseSamplePlayer(it) })
                                }
                            }

                            Divider()

                            // --- SLIDERS ---
                            // Smoothing slider
                            Text(
                                text = "Smoothing: ${(smoothing * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = smoothing,
                                onValueChange = onSmoothingChange,
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // BPM (tempo) slider 60..240
                            Text(text = "Tempo: ${bpm.roundToInt()} BPM", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = bpm,
                                onValueChange = onBpmChange,
                                valueRange = 60f..240f,
                                steps = 180,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Volume Threshold
                            Text(text = "Volume threshold: ${thresholdDb.roundToInt()} dB", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = thresholdDb,
                                onValueChange = onThresholdChange,
                                valueRange = -80f..-6f,
                                steps = 74, // ~1 dB steps
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Divider + About (added)
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))

                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuExpanded = false
                                navController?.navigate("about")
                            }
                        )
                    }
                }
            }
        }
    )
}
