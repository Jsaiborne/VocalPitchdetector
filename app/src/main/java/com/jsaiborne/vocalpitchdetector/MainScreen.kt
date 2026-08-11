@file:OptIn(ExperimentalMaterial3Api::class)

package com.jsaiborne.vocalpitchdetector

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val PREFS_NAME = "AppPreferences"
private const val PREF_NEVER_SHOW_RATE = "NeverShowRateApp"

@Suppress("MagicNumber", "LongMethod")
@Composable
fun MainScreen(navController: NavHostController? = null) {
    val scope = rememberCoroutineScope()
    val engine = remember { PitchEngine(scope) }
    val state by engine.state.collectAsState()
    val volumeRms by engine.volumeRms.collectAsState()
    val currentVolumeDb = rmsToDb(volumeRms)
    val context = LocalContext.current

    val outerPadding = 8.dp
    val smallGap = 8.dp
    val menuWidth = 320.dp

    var autoCenter by rememberSaveable { mutableStateOf(true) }
    var whiteKeyWidthDpFloat by rememberSaveable { mutableFloatStateOf(56f) }
    var smoothing by rememberSaveable { mutableFloatStateOf(0.5f) }
    var showNoteLabels by rememberSaveable { mutableStateOf(true) }
    var showHorizontalGrid by rememberSaveable { mutableStateOf(true) }
    var showCurve by rememberSaveable { mutableStateOf(true) }
    var showWhiteTrace by rememberSaveable { mutableStateOf(true) }
    var thresholdDb by rememberSaveable { mutableFloatStateOf(-34f) }
    var bpm by rememberSaveable { mutableFloatStateOf(60f) }
    var showWhiteDots by rememberSaveable { mutableStateOf(true) }

    var useSamplePlayer by rememberSaveable { mutableStateOf(false) }

    var graphPaused by remember { mutableStateOf(false) }
    var stableMidi by remember { mutableStateOf<Int?>(null) }
    val graphAlignmentDp by remember { mutableFloatStateOf(0f) }

    val sharedScroll = rememberScrollState()

    var isRecording by rememberSaveable { mutableStateOf(false) }
    var isRecordingPaused by rememberSaveable { mutableStateOf(false) }
    var showSavedDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var currentSessionId by remember { mutableStateOf("") }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(hasMicPermission) {
        if (hasMicPermission) {
            engine.start()
            engine.setVolumeThreshold(dbToRms(thresholdDb))
        }
        onDispose {
            engine.stop()
        }
    }

    DisposableEffect(useSamplePlayer) {
        if (useSamplePlayer) {
            SamplePlayer.init(context.applicationContext)
        } else {
            SamplePlayer.release()
        }
        onDispose {
            SamplePlayer.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                if (isRecording && !isRecordingPaused) {
                    engine.pauseRecording()
                    isRecordingPaused = true
                }
                ToneGenerator.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ToneGenerator.stop()
        }
    }

    LaunchedEffect(engine) {
        engine.stableNotes.collect { note -> stableMidi = note.midi }
    }

    val activeMidi: Int? by remember(state.frequency) {
        mutableStateOf(if (state.frequency > 0f) freqToMidi(state.frequency.toDouble()).roundToInt() else null)
    }

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val consentManager = remember { ConsentManager(context as Activity) }
    var canShowAds by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        consentManager.gatherConsent { error ->
            if (error == null) {
                MobileAds.initialize(context) {
                    canShowAds = consentManager.canRequestAds()
                }
            } else {
                canShowAds = consentManager.canRequestAds()
            }
        }
    }

    RateAppDialogManager()

    if (showSavedDialog) {
        AlertDialog(
            onDismissRequest = { showSavedDialog = false },
            title = { Text("Recording Saved") },
            text = { Text("Your vocal session has been saved successfully.") },
            confirmButton = {
                TextButton(onClick = { showSavedDialog = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSavedDialog = false
                    navController?.navigate("recordings")
                }) { Text("View Recordings") }
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard Recording") },
            text = { Text("Are you sure you want to delete this recording? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        engine.cancelRecording()
                        isRecording = false
                        isRecordingPaused = false
                        showDiscardDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(outerPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                onShowWhiteTraceChange = { showWhiteTrace = it },
                thresholdDb = thresholdDb,
                currentVolumeDb = currentVolumeDb,
                onThresholdChange = { newDb ->
                    thresholdDb = newDb
                    engine.setVolumeThreshold(dbToRms(newDb))
                },
                bpm = bpm,
                onBpmChange = { bpm = it },
                useSamplePlayer = useSamplePlayer,
                onToggleUseSamplePlayer = { useSamplePlayer = it },
                showWhiteDots = showWhiteDots,
                onShowWhiteDotsChange = { showWhiteDots = it },
                onResetDefaults = {
                    smoothing = 0.5f
                    bpm = 60f
                    thresholdDb = -34f
                    engine.setVolumeThreshold(dbToRms(-34f))
                    whiteKeyWidthDpFloat = 56f
                    autoCenter = true
                    showNoteLabels = true
                    showHorizontalGrid = true
                    showCurve = true
                    showWhiteTrace = true
                    showWhiteDots = true
                    useSamplePlayer = false
                },
                navController = navController,
                canShowAds = canShowAds,
                isRecording = isRecording,
                isRecordingPaused = isRecordingPaused,
                onRecordStart = {
                    currentSessionId = System.currentTimeMillis().toString()
                    val recordingsDir = File(context.filesDir, "recordings")
                    recordingsDir.mkdirs()
                    val audioFile = File(recordingsDir, "session_${currentSessionId}_audio.wav")
                    val pitchFile = File(recordingsDir, "session_${currentSessionId}_pitch.json")
                    engine.startRecording(audioFile, pitchFile)
                    isRecording = true
                    isRecordingPaused = false
                },
                onRecordPauseResume = {
                    if (isRecordingPaused) {
                        engine.resumeRecording()
                        isRecordingPaused = false
                    } else {
                        engine.pauseRecording()
                        isRecordingPaused = true
                    }
                },
                onRecordStop = {
                    engine.stopRecording()
                    isRecording = false
                    isRecordingPaused = false
                    showSavedDialog = true
                },
                onRecordDiscard = { showDiscardDialog = true },
                onLibraryClick = { navController?.navigate("recordings") }
            )

            Spacer(modifier = Modifier.height(smallGap))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(250.dp)
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                ) {
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

                Spacer(modifier = Modifier.width(2.dp))

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                ) {
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
                        bpm = bpm
                    )
                }
            }
            Spacer(modifier = Modifier.height(smallGap))
        } else {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                var menuExpandedPortrait by remember { mutableStateOf(false) }

                InfoOverlay(
                    frequency = state.frequency,
                    confidence = state.confidence,
                    activeMidi = activeMidi,
                    graphPaused = graphPaused,
                    onTogglePause = { graphPaused = !graphPaused },
                    onOpenSettings = { menuExpandedPortrait = true }
                )

                Box(modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    DropdownMenu(
                        expanded = menuExpandedPortrait,
                        onDismissRequest = { menuExpandedPortrait = false },
                        modifier = Modifier.width(menuWidth)
                    ) {
                        val portraitMenuScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .heightIn(max = 360.dp)
                                .verticalScroll(portraitMenuScroll),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Show note labels",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = showNoteLabels,
                                        onCheckedChange = { showNoteLabels = it }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Show grid lines",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = showHorizontalGrid,
                                        onCheckedChange = { showHorizontalGrid = it }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Show curve & trace",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = showCurve && showWhiteTrace,
                                        onCheckedChange = { checked ->
                                            showCurve = checked
                                            showWhiteTrace = checked
                                        }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Show white dots",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = showWhiteDots,
                                        onCheckedChange = { showWhiteDots = it }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Auto-center",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(checked = autoCenter, onCheckedChange = { autoCenter = it })
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Use piano samples",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = useSamplePlayer,
                                        onCheckedChange = { useSamplePlayer = it }
                                    )
                                }
                            }
                            HorizontalDivider()
                            Text(
                                "Smoothing: ${(smoothing * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = smoothing,
                                onValueChange = { smoothing = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Tempo: ${bpm.roundToInt()} BPM",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = bpm,
                                onValueChange = { bpm = it },
                                valueRange = 60f..240f,
                                steps = 180,
                                modifier = Modifier.fillMaxWidth()
                            )
                            LiveVolumeSlider(
                                thresholdDb = thresholdDb,
                                currentVolumeDb = currentVolumeDb,
                                onThresholdChange = { newDb ->
                                    thresholdDb = newDb
                                    engine.setVolumeThreshold(dbToRms(newDb))
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        DropdownMenuItem(
                            text = { Text("Reset to Defaults") },
                            onClick = {
                                smoothing = 0.5f
                                bpm = 60f
                                thresholdDb = -34f
                                engine.setVolumeThreshold(dbToRms(-34f))
                                whiteKeyWidthDpFloat = 56f
                                autoCenter = true
                                showNoteLabels = true
                                showHorizontalGrid = true
                                showCurve = true
                                showWhiteTrace = true
                                showWhiteDots = true
                                useSamplePlayer = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuExpandedPortrait = false
                                navController?.navigate("about")
                            }
                        )
                    }
                }

                PortraitRecordingControls(
                    isRecording = isRecording,
                    isRecordingPaused = isRecordingPaused,
                    onRecordStart = {
                        currentSessionId = System.currentTimeMillis().toString()
                        val recordingsDir = File(context.filesDir, "recordings")
                        recordingsDir.mkdirs()
                        val audioFile = File(recordingsDir, "session_${currentSessionId}_audio.wav")
                        val pitchFile = File(recordingsDir, "session_${currentSessionId}_pitch.json")
                        engine.startRecording(audioFile, pitchFile)
                        isRecording = true
                        isRecordingPaused = false
                    },
                    onRecordPauseResume = {
                        if (isRecordingPaused) {
                            engine.resumeRecording()
                            isRecordingPaused = false
                        } else {
                            engine.pauseRecording()
                            isRecordingPaused = true
                        }
                    },
                    onRecordStop = {
                        engine.stopRecording()
                        isRecording = false
                        isRecordingPaused = false
                        showSavedDialog = true
                    },
                    onRecordDiscard = { showDiscardDialog = true },
                    onLibraryClick = { navController?.navigate("recordings") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Piano(
                startMidi = 24, endMidi = 84, onKeyPressed = { _, _ -> },
                activeMidi = activeMidi, autoCenter = autoCenter, stableMidi = stableMidi,
                whiteKeyWidthDp = whiteKeyWidthDpFloat.dp, scrollState = sharedScroll,
                rotated = false, blackKeyShiftFraction = 0.5f, useSamplePlayer = useSamplePlayer
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            PitchGraphCard(
                engine = engine,
                modifier = Modifier.fillMaxWidth().weight(1f),
                paused = graphPaused, onTogglePause = { graphPaused = !graphPaused },
                startMidi = 24, endMidi = 84, whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                scrollState = sharedScroll, alignmentOffsetDp = graphAlignmentDp.dp, timeWindowMs = 8000L,
                showNoteLabels = showNoteLabels, showHorizontalGrid = showHorizontalGrid,
                showCurve = showCurve, rotated = false, blackKeyShiftFraction = 0.5f,
                smoothing = smoothing, showWhiteTrace = showWhiteTrace, showWhiteDots = showWhiteDots,
                bpm = bpm
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (canShowAds) {
                AdaptiveBannerAd(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    adUnitId = BuildConfig.BANNER_AD_UNIT_ID
                )
            }
        }
    }
}

@Composable
private fun InfoOverlay(
    frequency: Float,
    confidence: Float,
    activeMidi: Int?,
    graphPaused: Boolean,
    onTogglePause: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val freqText = if (frequency > 0f) "%.1f Hz".format(Locale.US, frequency) else "--"
        val noteText = if (activeMidi != null) midiToNoteName(activeMidi) else "-"

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = noteText, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = freqText, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Conf: %.2f".format(Locale.US, confidence),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onTogglePause,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (graphPaused) "Resume" else "Hold",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Open settings",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PortraitRecordingControls(
    isRecording: Boolean,
    isRecordingPaused: Boolean,
    onRecordStart: () -> Unit,
    onRecordPauseResume: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordDiscard: () -> Unit,
    onLibraryClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)
    ) {
        IconButton(onClick = onLibraryClick) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = "View Saved Recordings",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (!isRecording) {
            Button(
                onClick = onRecordStart,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Record")
            }
        } else {
            IconButton(onClick = onRecordDiscard) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Discard Recording",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onRecordPauseResume) {
                Icon(
                    imageVector = if (isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isRecordingPaused) "Resume" else "Pause",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton(onClick = onRecordStop) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop and Save",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun AdaptiveBannerAd(
    modifier: Modifier = Modifier,
    adUnitId: String,
    customWidth: Int? = null
) {
    val config = LocalConfiguration.current
    val adWidth = customWidth ?: config.screenWidthDp
    key(adWidth) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                AdView(ctx).apply {
                    this.adUnitId = adUnitId
                    val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidth)
                    this.setAdSize(adSize)
                    this.loadAd(AdRequest.Builder().build())
                }
            },
            update = {},
            onRelease = { adView -> adView.destroy() }
        )
    }
}

@Suppress("LongParameterList", "LongMethod", "MagicNumber")
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
    currentVolumeDb: Float,
    thresholdDb: Float,
    onThresholdChange: (Float) -> Unit,
    bpm: Float,
    onBpmChange: (Float) -> Unit,
    useSamplePlayer: Boolean,
    onToggleUseSamplePlayer: (Boolean) -> Unit,
    showWhiteDots: Boolean,
    onShowWhiteDotsChange: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    canShowAds: Boolean,
    navController: NavHostController? = null,
    isRecording: Boolean,
    isRecordingPaused: Boolean,
    onRecordStart: () -> Unit,
    onRecordPauseResume: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordDiscard: () -> Unit,
    onLibraryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // LEFT SIDE: Note, Freq, Conf
            Column(verticalArrangement = Arrangement.Center) {
                val noteText = if (activeMidi != null) midiToNoteName(activeMidi) else "-"
                val freqText = if (detectedFreq > 0f) "%.1f Hz".format(Locale.US, detectedFreq) else "--"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = noteText, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = freqText, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Conf: %.2f".format(Locale.US, detectedConfidence),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // CENTER: The Floating Landscape Ad
            if (canShowAds) {
                val config = LocalConfiguration.current
                val screenWidth = config.screenWidthDp
                // Reserve space for left and right columns
                val reservedSpace = 320
                val adWidth = screenWidth - reservedSpace
                if (adWidth >= 320) {
                    AdaptiveBannerAd(
                        adUnitId = BuildConfig.BANNER_AD_UNIT_LANDSCAPE_ID,
                        customWidth = adWidth,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // RIGHT SIDE: Controls
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onTogglePause,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(if (paused) "Resume" else "Hold", style = MaterialTheme.typography.labelSmall)
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Graph options",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.width(320.dp)
                        ) {
                            val landscapeMenuScroll = rememberScrollState()
                            Column(
                                modifier = Modifier.padding(12.dp).heightIn(max = 360.dp).verticalScroll(landscapeMenuScroll),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("Show note labels", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showNoteLabels, onCheckedChange = onToggleShowNoteLabels)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("Show grid lines", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showHorizontalGrid, onCheckedChange = onToggleShowHorizontalGrid)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("Show curve & trace", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showCurve && showWhiteTrace, onCheckedChange = { checked -> onToggleShowCurve(checked); onShowWhiteTraceChange(checked) })
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("Show white dots", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showWhiteDots, onCheckedChange = onShowWhiteDotsChange)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("Auto-center", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = autoCenter, onCheckedChange = onAutoCenterToggle)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Text("Use piano samples", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Switch(
                                            checked = useSamplePlayer,
                                            onCheckedChange = onToggleUseSamplePlayer
                                        )
                                    }
                                }
                                HorizontalDivider()
                                Text("Smoothing: ${(smoothing * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                                Slider(value = smoothing, onValueChange = onSmoothingChange, valueRange = 0f..1f, modifier = Modifier.fillMaxWidth())
                                Text("Tempo: ${bpm.roundToInt()} BPM", style = MaterialTheme.typography.bodySmall)
                                Slider(value = bpm, onValueChange = onBpmChange, valueRange = 60f..240f, steps = 180, modifier = Modifier.fillMaxWidth())
                                LiveVolumeSlider(thresholdDb = thresholdDb, currentVolumeDb = currentVolumeDb, onThresholdChange = onThresholdChange)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            DropdownMenuItem(text = { Text("Reset to Defaults") }, onClick = { onResetDefaults() })
                            DropdownMenuItem(text = { Text("About") }, onClick = { menuExpanded = false; navController?.navigate("about") })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onLibraryClick, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = "View Saved Recordings", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    if (!isRecording) {
                        Button(onClick = onRecordStart, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Text("Record", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        IconButton(onClick = onRecordDiscard, modifier = Modifier.size(28.dp)) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Discard Recording", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onRecordPauseResume, modifier = Modifier.size(28.dp)) {
                            Icon(imageVector = if (isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (isRecordingPaused) "Resume" else "Pause", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onRecordStop, modifier = Modifier.size(28.dp)) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop and Save", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveVolumeSlider(thresholdDb: Float, currentVolumeDb: Float, onThresholdChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Volume threshold: ${thresholdDb.roundToInt()} dB", style = MaterialTheme.typography.bodySmall)
            Text("Live: ${currentVolumeDb.roundToInt()} dB", style = MaterialTheme.typography.bodySmall, color = if (currentVolumeDb >= thresholdDb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(6.dp))
        val fraction = ((currentVolumeDb - (-80f)) / (-6f - (-80f))).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = if (currentVolumeDb >= thresholdDb) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        Slider(value = thresholdDb, onValueChange = onThresholdChange, valueRange = -80f..-6f, steps = 74, modifier = Modifier.fillMaxWidth().height(32.dp))
    }
}

@Composable
fun RateAppDialogManager() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val neverShowAgain = prefs.getBoolean(PREF_NEVER_SHOW_RATE, false)
    var hasShownThisSession by rememberSaveable { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!neverShowAgain && !hasShownThisSession) {
            delay(30000)
            showDialog = true
            hasShownThisSession = true
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Enjoying the App?") },
            text = { Text("If you like using this app, would you mind taking a moment to rate it? It really helps out!") },
            confirmButton = { TextButton(onClick = { prefs.edit().putBoolean(PREF_NEVER_SHOW_RATE, true).apply(); showDialog = false; openPlayStore(context) }) { Text("Rate Us") } },
            dismissButton = {
                Row(modifier = Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showDialog = false }) { Text("Ask Me Later", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { prefs.edit().putBoolean(PREF_NEVER_SHOW_RATE, true).apply(); showDialog = false }) { Text("No Thanks", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}
