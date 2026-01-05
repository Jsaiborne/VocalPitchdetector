@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.vocalpitchdetector

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
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

    // Attach manager BEFORE starting engine (prevents detector->manager race)
    DisposableEffect(recordingManager, engine) {
        engine.attachRecordingManager(recordingManager)
        engine.start()
        onDispose {
            // stop engine safely
            try { engine.stop() } catch (_: Throwable) {}
            // release playback player if any
            // (we don't hold playbackPlayer here; it's released in stopPlayback below if present)
        }
    }

    // engine state
    val state by engine.state.collectAsState()

    // microphone permission
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    val requestMicPermission: () -> Unit = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    // persistent UI settings
    var autoCenter by rememberSaveable { mutableStateOf(true) }
    var whiteKeyWidthDpFloat by rememberSaveable { mutableStateOf(56f) }
    var smoothing by rememberSaveable { mutableStateOf(0.5f) }
    var showNoteLabels by rememberSaveable { mutableStateOf(true) }
    var showHorizontalGrid by rememberSaveable { mutableStateOf(true) }
    var showCurve by rememberSaveable { mutableStateOf(true) }
    var showWhiteTrace by rememberSaveable { mutableStateOf(true) }

    // UI state (non-recorder)
    var graphPaused by remember { mutableStateOf(false) }
    var stableMidi by remember { mutableStateOf<Int?>(null) }
    var graphAlignmentDp by remember { mutableStateOf(0f) }

    val sharedScroll = rememberScrollState()

    // collect stable notes from engine (assumes engine.stableNotes is a Flow)
    LaunchedEffect(engine) {
        engine.stableNotes.collect { note -> stableMidi = note.midi }
    }

    // derived active midi — use remember + derivedStateOf to avoid state creation errors
    val activeMidi by remember(state.frequency) {
        derivedStateOf {
            if (state.frequency > 0f) freqToMidi(state.frequency.toDouble()).roundToInt() else null
        }
    }

    // Load / Playback state (uses your RecordingsBrowser + SimplePitchPreview)
    var showBrowser by remember { mutableStateOf(false) }
    var loadedSamples by remember { mutableStateOf<List<PitchSample>>(emptyList()) }
    var playbackPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableStateOf(0L) }

    // callbacks handed to RecordingsBrowser
    val onUseSamples: (List<PitchSample>) -> Unit = { samples ->
        // assume samples are normalized (tMs starting at 0). If not, normalize here.
        loadedSamples = samples
    }

    val onUseMediaPlayer: (MediaPlayer) -> Unit = { mp ->
        // release any existing player
        try { playbackPlayer?.release() } catch (_: Exception) {}
        mp.setOnCompletionListener {
            // ensure we stop and reset UI at EOF
            try { mp.stop() } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
            playbackPlayer = null
            playbackPlaying = false
            playbackPositionMs = 0L
        }
        playbackPlayer = mp
        playbackPlaying = false
        playbackPositionMs = 0L
    }

    // playback helpers
    fun startPlayback(fromBeginning: Boolean = true) {
        playbackPlayer?.let { mp ->
            try {
                if (fromBeginning) mp.seekTo(0)
                mp.start()
                playbackPlaying = true
            } catch (e: Exception) {
                e.printStackTrace()
                playbackPlaying = false
            }
        }
    }
    fun pausePlayback() {
        playbackPlayer?.let { mp ->
            try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}
        }
        playbackPlaying = false
    }
    fun stopPlayback() {
        playbackPlayer?.let { mp ->
            try { if (mp.isPlaying) mp.stop() } catch (_: Exception) {}
            try { mp.reset() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        playbackPlayer = null
        playbackPlaying = false
        playbackPositionMs = 0L
    }

    // Poll MediaPlayer position while playing
    LaunchedEffect(playbackPlayer, playbackPlaying) {
        while (playbackPlaying && playbackPlayer != null) {
            try {
                playbackPositionMs = playbackPlayer?.currentPosition?.toLong() ?: 0L
            } catch (_: Exception) {}
            delay(50L)
        }
    }

    // Keep Auto-center visible (portrait) and provide Load + playback controls
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Recording controls (existing composable) — passes manager + permission helpers
        RecordingControls(
            engine = engine,
            sampleRate = 44100,
            manager = recordingManager,
            hasMicPermission = hasMicPermission,
            requestMicPermission = requestMicPermission,
            onOpenBrowser = { showBrowser = true }
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Top small control bar (always visible — provides Load + playback + autocenter)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Load recordings dialog
            Button(onClick = { showBrowser = true }) { Text("Load") }

            // Playback controls if a player is loaded
            if (playbackPlayer != null) {
                Button(onClick = { if (playbackPlaying) pausePlayback() else startPlayback(fromBeginning = false) }) {
                    Text(if (playbackPlaying) "Pause" else "Play")
                }
                Button(onClick = { stopPlayback() }) { Text("Stop") }
                Text(text = "Pos: ${playbackPositionMs}ms", modifier = Modifier.padding(start = 6.dp))
            } else {
                // if no MediaPlayer loaded but samples exist, show Play button that creates a MediaPlayer if needed via RecordingsBrowser
                if (loadedSamples.isNotEmpty()) {
                    Button(onClick = { /* user must Load to attach MediaPlayer; keep disabled or provide logic to prepare */ }) {
                        Text("Play (load audio)")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Auto-center visible here (portrait & landscape)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-center", style = MaterialTheme.typography.bodySmall)
                Switch(checked = autoCenter, onCheckedChange = { autoCenter = it }, modifier = Modifier.scale(0.8f))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // playback preview and controls area (compact)
        if (loadedSamples.isNotEmpty()) {
            // show a small preview graph (or reuse your existing preview)
            SimplePitchPreview(samples = loadedSamples, modifier = Modifier.fillMaxWidth().height(120.dp))
        }

        // recordings browser dialog (uses your RecordingsBrowser implementation)
        if (showBrowser) {
            AlertDialog(
                onDismissRequest = { showBrowser = false },
                confirmButton = {
                    TextButton(onClick = { showBrowser = false }) { Text("Close") }
                },
                text = {
                    Box(modifier = Modifier.height(360.dp).fillMaxWidth()) {
                        // RecordingsBrowser will call onUseMediaPlayer(mp) and onUseSamples(samples)
                        RecordingsBrowser(onUseSamples = onUseSamples, onUseMediaPlayer = onUseMediaPlayer)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main piano + graph area
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
                        smoothing = smoothing, showWhiteTrace = showWhiteTrace,

                        // PASS playback trace into the main graph
                        playbackSamples = loadedSamples,
                        playbackPositionMs = if (playbackPlaying) playbackPositionMs else null
                    )
                }
            }
        } else {
            // portrait layout

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
                smoothing = smoothing, showCurve = showCurve,

                // pass playback data here for portrait too
                playbackSamples = loadedSamples,
                playbackPositionMs = if (playbackPlaying) playbackPositionMs else null
            )

            Box(modifier = Modifier.fillMaxWidth().height(50.dp).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Text("Ad Placeholder", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
