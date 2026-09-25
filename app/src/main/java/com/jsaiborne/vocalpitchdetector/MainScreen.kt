@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("TooManyFunctions")

package com.jsaiborne.vocalpitchdetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val PREFS_NAME = "AppPreferences"
private const val PREF_NEVER_SHOW_RATE = "NeverShowRateApp"
private const val PREF_SOLFEGE = "UseSolfege"
private const val PREF_ASKED_NOTIFICATIONS = "AskedNotificationPermission"

/** Notifications only need a runtime permission from Android 13 (Tiramisu) on. */
private fun notificationPermissionGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val state = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    return state == PackageManager.PERMISSION_GRANTED
}

private data class RecordingCallbacks(
    val onRecordStart: () -> Unit,
    val onRecordPauseResume: () -> Unit,
    val onRecordStop: () -> Unit,
    val onRecordDiscard: () -> Unit,
    val onLibraryClick: () -> Unit
)

@Suppress("MagicNumber", "LongMethod")
@Composable
fun MainScreen(navController: NavHostController? = null) {
    // One engine for the whole process, so a recording outlives this screen (see RecordingService)
    val engine = remember { PitchEngineProvider.get() }
    val state by engine.state.collectAsState()
    val volumeRms by engine.volumeRms.collectAsState()
    val currentVolumeDb = rmsToDb(volumeRms)
    val context = LocalContext.current

    val outerPadding = 8.dp
    val smallGap = 8.dp

    var autoCenter by rememberSaveable { mutableStateOf(true) }
    var whiteKeyWidthDpFloat by rememberSaveable { mutableFloatStateOf(56f) }
    var showNoteLabels by rememberSaveable { mutableStateOf(true) }
    var showHorizontalGrid by rememberSaveable { mutableStateOf(true) }
    var showCurve by rememberSaveable { mutableStateOf(true) }
    var showWhiteTrace by rememberSaveable { mutableStateOf(true) }
    var thresholdDb by rememberSaveable { mutableFloatStateOf(-34f) }
    var bpm by rememberSaveable { mutableFloatStateOf(60f) }
    var showWhiteDots by rememberSaveable { mutableStateOf(true) }

    var useSamplePlayer by rememberSaveable { mutableStateOf(false) }
    var showCentsMeter by rememberSaveable { mutableStateOf(true) }
    var rangeTestActive by rememberSaveable { mutableStateOf(false) }

    var graphPaused by remember { mutableStateOf(false) }
    var stableMidi by remember { mutableStateOf<Int?>(null) }
    val graphAlignmentDp by remember { mutableFloatStateOf(0f) }

    val sharedScroll = rememberScrollState()
    val pitchZoom = rememberPitchAxisZoom(
        keySizeDp = { whiteKeyWidthDpFloat },
        setKeySizeDp = { whiteKeyWidthDpFloat = it },
        scroll = sharedScroll
    )

    val isRecording by engine.isRecording.collectAsState()
    val isRecordingPaused by engine.isPaused.collectAsState()
    var showSavedDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

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
            // A recording keeps going (RecordingService owns its notification); only release the mic
            // when nothing needs it
            if (!engine.isRecording.value) engine.stop()
        }
    }

    // Tells RecordingService whether a screen is open, so it knows if it may release the mic
    DisposableEffect(Unit) {
        PitchEngineProvider.uiAttached = true
        onDispose { PitchEngineProvider.uiAttached = false }
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
            // Recording deliberately keeps running when the screen turns off or the app is left
            if (event == Lifecycle.Event.ON_PAUSE) ToneGenerator.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ToneGenerator.stop()
        }
    }

    val solfegePrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        NoteNotation.useSolfege = solfegePrefs.getBoolean(PREF_SOLFEGE, false)
    }

    LaunchedEffect(engine) {
        engine.stableNotes.collect { note -> stableMidi = note.midi }
    }

    val activeMidi: Int? by remember(state.frequency) {
        mutableStateOf(
            if (state.frequency > 0f) {
                freqToMidi(state.frequency.toDouble()).roundToInt()
            } else {
                null
            }
        )
    }

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val canShowAds = LocalCanShowAds.current

    RateAppDialogManager()

    val startRecordingNow: () -> Unit = {
        val sessionId = System.currentTimeMillis().toString()
        val recordingsDir = File(context.filesDir, "recordings")
        recordingsDir.mkdirs()
        engine.startRecording(
            audioFile = File(recordingsDir, "session_${sessionId}_audio.wav"),
            pitchFile = File(recordingsDir, "session_${sessionId}_pitch.json")
        )
        // The service shows the notification and keeps the mic alive in the background
        if (engine.isRecording.value) RecordingService.start(context)
    }

    // Android 13+ needs a runtime permission for the "recording" notification. Recording starts
    // whatever the answer; without it the recording just has no visible notification.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { startRecordingNow() }

    val recordingCallbacks = remember(engine, navController) {
        RecordingCallbacks(
            onRecordStart = {
                val alreadyAsked = solfegePrefs.getBoolean(PREF_ASKED_NOTIFICATIONS, false)
                if (!notificationPermissionGranted(context) && !alreadyAsked) {
                    solfegePrefs.edit().putBoolean(PREF_ASKED_NOTIFICATIONS, true).apply()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startRecordingNow()
                }
            },
            onRecordPauseResume = {
                if (engine.isPaused.value) engine.resumeRecording() else engine.pauseRecording()
            },
            onRecordStop = {
                engine.stopRecording()
                showSavedDialog = true
            },
            onRecordDiscard = { showDiscardDialog = true },
            onLibraryClick = { navController?.navigate("recordings") }
        )
    }

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
            text = {
                Text(
                    "Are you sure you want to delete this recording? " +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        engine.cancelRecording()
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

    val resetToDefaults = {
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
        showCentsMeter = true
        NoteNotation.useSolfege = false
        solfegePrefs.edit().putBoolean(PREF_SOLFEGE, false).apply()
    }

    val menuSettings = MenuSettings(
        showNoteLabels = showNoteLabels,
        showHorizontalGrid = showHorizontalGrid,
        showCurve = showCurve,
        showWhiteTrace = showWhiteTrace,
        showWhiteDots = showWhiteDots,
        autoCenter = autoCenter,
        useSamplePlayer = useSamplePlayer,
        showCentsMeter = showCentsMeter,
        useSolfege = NoteNotation.useSolfege,
        rangeTestUnavailableReason = when {
            isRecording -> "Stop recording first"
            !hasMicPermission -> "Microphone permission needed"
            else -> null
        },
        bpm = bpm,
        thresholdDb = thresholdDb,
        currentVolumeDb = currentVolumeDb
    )

    val menuActions = MenuActions(
        onShowNoteLabelsChange = { showNoteLabels = it },
        onShowHorizontalGridChange = { showHorizontalGrid = it },
        onShowCurveAndTraceChange = { checked ->
            showCurve = checked
            showWhiteTrace = checked
        },
        onShowWhiteDotsChange = { showWhiteDots = it },
        onAutoCenterChange = { autoCenter = it },
        onUseSamplePlayerChange = { useSamplePlayer = it },
        onShowCentsMeterChange = { showCentsMeter = it },
        onUseSolfegeChange = { checked ->
            NoteNotation.useSolfege = checked
            solfegePrefs.edit().putBoolean(PREF_SOLFEGE, checked).apply()
        },
        onOpenRangeTest = { rangeTestActive = true },
        onBpmChange = { bpm = it },
        onThresholdChange = { newDb ->
            thresholdDb = newDb
            engine.setVolumeThreshold(dbToRms(newDb))
        },
        onResetDefaults = resetToDefaults,
        onAbout = { navController?.navigate("about") }
    )

    // The guided vocal range test takes over the whole screen (no ad, so its buttons can't be
    // mis-tapped); the pitch engine and mic keep running underneath.
    if (rangeTestActive) {
        BackHandler { rangeTestActive = false }
        VocalRangeTestScreen(
            engine = engine,
            isLandscape = isLandscape,
            thresholdDb = thresholdDb,
            onThresholdChange = menuActions.onThresholdChange,
            onExit = { rangeTestActive = false },
            modifier = Modifier.padding(outerPadding)
        )
        return
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
                paused = graphPaused,
                onTogglePause = { graphPaused = !graphPaused },
                menuSettings = menuSettings,
                menuActions = menuActions,
                showCentsMeter = showCentsMeter,
                canShowAds = canShowAds,
                isRecording = isRecording,
                isRecordingPaused = isRecordingPaused,
                callbacks = recordingCallbacks
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
                        .pitchAxisZoom(pitchZoom, vertical = true)
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
                        .pitchAxisZoom(pitchZoom, vertical = true)
                ) {
                    PitchGraphCard(
                        engine = engine,
                        modifier = Modifier.fillMaxSize(),
                        paused = graphPaused,
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

                // Sits in the top card, well away from the banner ad at the bottom of the screen
                if (showCentsMeter) {
                    CentsMeter(
                        frequency = state.frequency,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 4.dp)
                    )
                }

                Box(modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    SettingsMenu(
                        expanded = menuExpandedPortrait,
                        onDismiss = { menuExpandedPortrait = false },
                        settings = menuSettings,
                        actions = menuActions
                    )
                }

                PortraitRecordingControls(
                    isRecording = isRecording,
                    isRecordingPaused = isRecordingPaused,
                    callbacks = recordingCallbacks
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Piano(
                modifier = Modifier.pitchAxisZoom(pitchZoom, vertical = false),
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
                modifier = Modifier.fillMaxWidth().weight(1f).pitchAxisZoom(pitchZoom, vertical = false),
                paused = graphPaused,
                startMidi = 24, endMidi = 84, whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                scrollState = sharedScroll, alignmentOffsetDp = graphAlignmentDp.dp,
                timeWindowMs = 8000L,
                showNoteLabels = showNoteLabels, showHorizontalGrid = showHorizontalGrid,
                showCurve = showCurve, rotated = false, blackKeyShiftFraction = 0.5f,
                showWhiteTrace = showWhiteTrace,
                showWhiteDots = showWhiteDots,
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
        val freqText = if (frequency > 0f) {
            "%.1f Hz".format(Locale.US, frequency)
        } else {
            "--"
        }
        val noteText = if (activeMidi != null) midiToDisplayName(activeMidi) else "-"

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
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "Open menu",
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
    callbacks: RecordingCallbacks
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)
    ) {
        IconButton(onClick = callbacks.onLibraryClick) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = "View Saved Recordings",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (!isRecording) {
            Button(
                onClick = callbacks.onRecordStart,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Record")
            }
        } else {
            IconButton(onClick = callbacks.onRecordDiscard) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Discard Recording",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = callbacks.onRecordPauseResume) {
                Icon(
                    imageVector = if (isRecordingPaused) {
                        Icons.Default.PlayArrow
                    } else {
                        Icons.Default.Pause
                    },
                    contentDescription = if (isRecordingPaused) "Resume" else "Pause",
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton(onClick = callbacks.onRecordStop) {
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
                    val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                        ctx,
                        adWidth
                    )
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
    paused: Boolean,
    onTogglePause: () -> Unit,
    menuSettings: MenuSettings,
    menuActions: MenuActions,
    showCentsMeter: Boolean,
    canShowAds: Boolean,
    isRecording: Boolean,
    isRecordingPaused: Boolean,
    callbacks: RecordingCallbacks
) {
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // LEFT SIDE: Note, Freq, Conf
            Column(verticalArrangement = Arrangement.Center) {
                val noteText = if (activeMidi != null) midiToDisplayName(activeMidi) else "-"
                val freqText = if (detectedFreq > 0f) {
                    "%.1f Hz".format(Locale.US, detectedFreq)
                } else {
                    "--"
                }
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
                if (showCentsMeter) {
                    Spacer(modifier = Modifier.height(2.dp))
                    // Fixed small width so this column never grows into the centred ad
                    CentsMeter(
                        frequency = detectedFreq,
                        barWidth = 64.dp,
                        labelWidth = 34.dp,
                        height = 12.dp
                    )
                }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onTogglePause,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (paused) "Resume" else "Hold",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open menu",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        SettingsMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            settings = menuSettings,
                            actions = menuActions
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = callbacks.onLibraryClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = "View Saved Recordings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (!isRecording) {
                        Button(
                            onClick = callbacks.onRecordStart,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Record", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        IconButton(
                            onClick = callbacks.onRecordDiscard,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Discard Recording",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = callbacks.onRecordPauseResume,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecordingPaused) {
                                    Icons.Default.PlayArrow
                                } else {
                                    Icons.Default.Pause
                                },
                                contentDescription = if (isRecordingPaused) "Resume" else "Pause",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = callbacks.onRecordStop,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop and Save",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Current values shown in the settings dropdown (shared by the portrait and landscape layouts). */
private data class MenuSettings(
    val showNoteLabels: Boolean,
    val showHorizontalGrid: Boolean,
    val showCurve: Boolean,
    val showWhiteTrace: Boolean,
    val showWhiteDots: Boolean,
    val autoCenter: Boolean,
    val useSamplePlayer: Boolean,
    val showCentsMeter: Boolean,
    val useSolfege: Boolean,
    /** Why the vocal range test can't be opened right now, or null when it can. */
    val rangeTestUnavailableReason: String?,
    val bpm: Float,
    val thresholdDb: Float,
    val currentVolumeDb: Float
)

/** What the settings dropdown can change. */
private data class MenuActions(
    val onShowNoteLabelsChange: (Boolean) -> Unit,
    val onShowHorizontalGridChange: (Boolean) -> Unit,
    val onShowCurveAndTraceChange: (Boolean) -> Unit,
    val onShowWhiteDotsChange: (Boolean) -> Unit,
    val onAutoCenterChange: (Boolean) -> Unit,
    val onUseSamplePlayerChange: (Boolean) -> Unit,
    val onShowCentsMeterChange: (Boolean) -> Unit,
    val onUseSolfegeChange: (Boolean) -> Unit,
    val onOpenRangeTest: () -> Unit,
    val onBpmChange: (Float) -> Unit,
    val onThresholdChange: (Float) -> Unit,
    val onResetDefaults: () -> Unit,
    val onAbout: () -> Unit
)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MenuSectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * How many cents the sung pitch is from the nearest note, as a small bar with a moving needle.
 * Uses a fixed height and keeps its space when nothing is being sung, so the layout never jumps.
 * Give [barWidth] and [labelWidth] for a compact, fixed-size version; otherwise the bar fills the width.
 */
@Suppress("MagicNumber", "LongMethod")
@Composable
internal fun CentsMeter(
    frequency: Float,
    modifier: Modifier = Modifier,
    barWidth: Dp? = null,
    labelWidth: Dp = 56.dp,
    height: Dp = 20.dp
) {
    val hasPitch = frequency > 0f
    val cents = if (hasPitch) {
        val nearest = freqToMidi(frequency.toDouble()).roundToInt()
        centsDifference(frequency.toDouble(), nearest).toFloat().coerceIn(-MAX_CENTS, MAX_CENTS)
    } else {
        0f
    }
    val animatedCents by animateFloatAsState(
        targetValue = cents,
        animationSpec = tween(durationMillis = 120),
        label = "CentsNeedle"
    )

    val needleColor = when {
        !hasPitch -> Color.Transparent
        kotlin.math.abs(cents) <= IN_TUNE_CENTS -> Color(0xFF66BB6A)
        kotlin.math.abs(cents) <= NEAR_TUNE_CENTS -> Color(0xFFFFB74D)
        else -> Color(0xFFEF5350)
    }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(
            modifier = (if (barWidth != null) Modifier.width(barWidth) else Modifier.weight(1f))
                .fillMaxHeight()
        ) {
            val trackHeight = 4.dp.toPx()
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )
            // Ticks at -25, 0 and +25 cents
            for (fraction in floatArrayOf(-0.5f, 0f, 0.5f)) {
                val x = centerX + fraction * (size.width / 2f)
                val tickHalf = if (fraction == 0f) size.height * 0.4f else size.height * 0.25f
                drawLine(
                    color = tickColor,
                    start = Offset(x, centerY - tickHalf),
                    end = Offset(x, centerY + tickHalf),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
            if (hasPitch) {
                val needleX = centerX + (animatedCents / MAX_CENTS) * (size.width / 2f)
                drawLine(
                    color = needleColor,
                    start = Offset(needleX, centerY - size.height * 0.45f),
                    end = Offset(needleX, centerY + size.height * 0.45f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Text(
            text = if (hasPitch) String.format(Locale.US, "%+d¢", cents.roundToInt()) else "--",
            modifier = Modifier.width(labelWidth),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelSmall,
            color = if (hasPitch) needleColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val MAX_CENTS = 50f
private const val IN_TUNE_CENTS = 10f
private const val NEAR_TUNE_CENTS = 25f

@Suppress("MagicNumber", "LongMethod")
@Composable
private fun SettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    settings: MenuSettings,
    actions: MenuActions
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(320.dp)
    ) {
        // Tools stay pinned above the scrolling settings so they are always in reach
        val rangeTestReason = settings.rangeTestUnavailableReason
        DropdownMenuItem(
            text = {
                Column {
                    Text("Vocal range test", style = MaterialTheme.typography.titleSmall)
                    Text(
                        rangeTestReason ?: "Find your lowest and highest notes, guided step by step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
            enabled = rangeTestReason == null,
            onClick = {
                onDismiss()
                actions.onOpenRangeTest()
            }
        )
        HorizontalDivider()
        Column(
            modifier = Modifier
                .padding(12.dp)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MenuSectionHeader("Graph")
            SwitchRow("Show note labels", settings.showNoteLabels, actions.onShowNoteLabelsChange)
            SwitchRow("Show grid lines", settings.showHorizontalGrid, actions.onShowHorizontalGridChange)
            SwitchRow(
                "Show curve & trace",
                settings.showCurve && settings.showWhiteTrace,
                actions.onShowCurveAndTraceChange
            )
            SwitchRow("Show white dots", settings.showWhiteDots, actions.onShowWhiteDotsChange)
            SwitchRow("Auto-center", settings.autoCenter, actions.onAutoCenterChange)
            Text(
                "Tempo: ${settings.bpm.roundToInt()} BPM",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = settings.bpm,
                onValueChange = actions.onBpmChange,
                valueRange = 60f..240f,
                steps = 180,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            MenuSectionHeader("Notes & piano")
            SwitchRow("Show cents meter", settings.showCentsMeter, actions.onShowCentsMeterChange)
            SwitchRow("Solfège note names (Do, Re, Mi)", settings.useSolfege, actions.onUseSolfegeChange)
            SwitchRow("Use piano samples", settings.useSamplePlayer, actions.onUseSamplePlayerChange)

            HorizontalDivider()
            MenuSectionHeader("Microphone")
            LiveVolumeSlider(
                thresholdDb = settings.thresholdDb,
                currentVolumeDb = settings.currentVolumeDb,
                onThresholdChange = actions.onThresholdChange
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        DropdownMenuItem(
            text = { Text("Reset to Defaults") },
            onClick = actions.onResetDefaults
        )
        DropdownMenuItem(
            text = { Text("About") },
            onClick = {
                onDismiss()
                actions.onAbout()
            }
        )
    }
}

@Composable
fun LiveVolumeSlider(
    thresholdDb: Float,
    currentVolumeDb: Float,
    onThresholdChange: (Float) -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorActiveAlpha = 0.6f
    val indicatorInactiveAlpha = 0.3f
    val trackAlpha = 0.1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Volume threshold: ${thresholdDb.roundToInt()} dB",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Live: ${currentVolumeDb.roundToInt()} dB",
                style = MaterialTheme.typography.bodySmall,
                color = if (currentVolumeDb >= thresholdDb) activeColor else inactiveColor
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        val fraction = ((currentVolumeDb - (-80f)) / (-6f - (-80f))).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = fraction,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = if (currentVolumeDb >= thresholdDb) {
                activeColor.copy(alpha = indicatorActiveAlpha)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = indicatorInactiveAlpha)
            },
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = trackAlpha)
        )
        Slider(
            value = thresholdDb,
            onValueChange = onThresholdChange,
            valueRange = -80f..-6f,
            steps = 74,
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
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
            text = {
                Text(
                    "If you like using this app, " +
                        "would you mind taking a moment to rate it? It really helps out!"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putBoolean(PREF_NEVER_SHOW_RATE, true).apply()
                        showDialog = false
                        openPlayStore(context)
                    }
                ) {
                    Text("Rate Us")
                }
            },
            dismissButton = {
                Row(
                    modifier = Modifier.padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showDialog = false }) {
                        Text("Ask Me Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = {
                            prefs.edit().putBoolean(PREF_NEVER_SHOW_RATE, true).apply()
                            showDialog = false
                        }
                    ) {
                        Text("No Thanks", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }
}
