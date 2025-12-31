@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.vocalpitchdetector

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log2
import kotlin.math.roundToInt


@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }

    // single shared RecordingManager instance
    val recordingManager = remember {
        RecordingManager(context, engine, 44100, scope)
    }

    LaunchedEffect(Unit) {
        engine.attachRecordingManager(recordingManager)
    }

    val state by engine.state.collectAsState()

    // runtime mic permission state & launcher
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    val requestMicPermission: () -> Unit = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    // Persistent Settings
    var autoCenter by rememberSaveable { mutableStateOf(true) }
    var whiteKeyWidthDpFloat by rememberSaveable { mutableStateOf(56f) }
    var smoothing by rememberSaveable { mutableStateOf(0.5f) }
    var showNoteLabels by rememberSaveable { mutableStateOf(true) }
    var showHorizontalGrid by rememberSaveable { mutableStateOf(true) }
    var showCurve by rememberSaveable { mutableStateOf(true) }
    var showWhiteTrace by rememberSaveable { mutableStateOf(true) }

    // UI States used by main UI (not the recorder)
    var graphPaused by remember { mutableStateOf(false) }
    var stableMidi by remember { mutableStateOf<Int?>(null) }
    var graphAlignmentDp by remember { mutableStateOf(0f) }

    val sharedScroll = rememberScrollState()

    DisposableEffect(Unit) {
        engine.start()
        onDispose { engine.stop() }
    }

    LaunchedEffect(engine) {
        engine.stableNotes.collect { note -> stableMidi = note.midi }
    }

    // Use derivedStateOf so this updates only when frequency changes
    val activeMidi by remember(state.frequency) {
        derivedStateOf {
            if (state.frequency > 0f) freqToMidi(state.frequency.toDouble()).roundToInt() else null
        }
    }

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Use the shared RecordingControls and pass permission helpers and manager
        RecordingControls(
            engine = engine,
            sampleRate = 44100,
            manager = recordingManager,
            hasMicPermission = hasMicPermission,
            requestMicPermission = requestMicPermission
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (isLandscape) {
            TopAppBarLandscapeCompact(
                detectedFreq = state.frequency,
                detectedConfidence = state.confidence,
                activeMidi = activeMidi,
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
                onShowWhiteTraceChange = { showWhiteTrace = it }
            )

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(modifier = Modifier.fillMaxHeight().width(300.dp).padding(4.dp)) {
                    Piano(
                        startMidi = 24, endMidi = 84, activeMidi = activeMidi,
                        autoCenter = autoCenter, stableMidi = stableMidi,
                        whiteKeyWidthDp = whiteKeyWidthDpFloat.dp, scrollState = sharedScroll,
                        rotated = true, blackKeyShiftFraction = 0.5f
                    )
                }
                Box(modifier = Modifier.fillMaxHeight().weight(1f).padding(4.dp)) {
                    PitchGraphCard(
                        engine = engine, modifier = Modifier.fillMaxSize(),
                        paused = graphPaused, onTogglePause = { graphPaused = !graphPaused },
                        startMidi = 24, endMidi = 84, whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                        scrollState = sharedScroll, alignmentOffsetDp = graphAlignmentDp.dp,
                        timeWindowMs = 8000L, showNoteLabels = showNoteLabels,
                        showHorizontalGrid = showHorizontalGrid, showCurve = showCurve,
                        rotated = true, blackKeyShiftFraction = 0.5f,
                        smoothing = smoothing, showWhiteTrace = showWhiteTrace
                    )
                }
            }
        } else {
            // PORTRAIT LAYOUT
            Card(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Frequency", style = MaterialTheme.typography.bodySmall)
                        Text("${String.format("%.1f", state.frequency)} Hz", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Note", style = MaterialTheme.typography.bodySmall)
                        Text(activeMidi?.let { midiToNoteName(it) } ?: "-", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Confidence", style = MaterialTheme.typography.bodySmall)
                        Text(String.format("%.2f", state.confidence), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-center", style = MaterialTheme.typography.bodySmall)
                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it }, modifier = Modifier.scale(0.8f))
                Spacer(modifier = Modifier.weight(1f))
                Text("Align: ${graphAlignmentDp.toInt()}dp", style = MaterialTheme.typography.bodySmall)
                Slider(value = graphAlignmentDp, onValueChange = { graphAlignmentDp = it }, valueRange = -20f..20f, modifier = Modifier.width(100.dp))
            }

            Slider(value = whiteKeyWidthDpFloat, onValueChange = { whiteKeyWidthDpFloat = it }, valueRange = 36f..96f)

            Piano(
                startMidi = 24, endMidi = 84, scrollState = sharedScroll, activeMidi = activeMidi,
                autoCenter = autoCenter, stableMidi = stableMidi,
                whiteKeyWidthDp = whiteKeyWidthDpFloat.dp, whiteKeyHeight = 120.dp
            )

            PitchGraphCard(
                engine = engine, modifier = Modifier.fillMaxWidth().weight(1f),
                paused = graphPaused, onTogglePause = { graphPaused = !graphPaused },
                startMidi = 24, endMidi = 84, whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                scrollState = sharedScroll, alignmentOffsetDp = graphAlignmentDp.dp,
                smoothing = smoothing, showCurve = showCurve
            )

            Box(modifier = Modifier.fillMaxWidth().height(50.dp).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Text("Ad Placeholder", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

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
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("${detectedFreq.toInt()}Hz", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

            Column(modifier = Modifier.width(100.dp)) {
                Text("Zoom", fontSize = 10.sp)
                Slider(value = whiteKeyWidthDpFloat, onValueChange = onWhiteKeyWidthChange, valueRange = 30f..90f)
            }

            FilterChip(
                selected = autoCenter,
                onClick = { onAutoCenterToggle(!autoCenter) },
                label = { Text("Auto-Center", fontSize = 10.sp) }
            )

            IconButton(onClick = onTogglePause) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "Resume" else "Pause"
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showCurve, onCheckedChange = onToggleShowCurve)
                Text("Curve", fontSize = 10.sp)
            }
        }
    }
}
