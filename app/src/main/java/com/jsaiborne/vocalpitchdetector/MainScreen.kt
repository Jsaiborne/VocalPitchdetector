@file:OptIn(ExperimentalMaterial3Api::class)

package com.jsaiborne.vocalpitchdetector
import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import java.util.Locale
import kotlin.math.roundToInt
// Ensure your app's BuildConfig is imported if your IDE complains
@Suppress("MagicNumber", "LongMethod")
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
//    val compactAppBarHeight = 40.dp

    // Persist across rotations using rememberSaveable
    var autoCenter by rememberSaveable { mutableStateOf(true) }
    var whiteKeyWidthDpFloat by rememberSaveable { mutableFloatStateOf(56f) }
    var smoothing by rememberSaveable { mutableFloatStateOf(0.5f) }
    var showNoteLabels by rememberSaveable { mutableStateOf(true) }
    var showHorizontalGrid by rememberSaveable { mutableStateOf(true) }
    var showCurve by rememberSaveable { mutableStateOf(true) }
    var showWhiteTrace by rememberSaveable { mutableStateOf(true) }
//    var volumeThreshold by rememberSaveable { mutableFloatStateOf(0.02f) } // normalized 0..1
    var thresholdDb by rememberSaveable { mutableFloatStateOf(-34f) }
    var bpm by rememberSaveable { mutableFloatStateOf(60f) }
    var showWhiteDots by rememberSaveable { mutableStateOf(true) } // <-- NEW

    // NEW: use sample player toggle
    var useSamplePlayer by rememberSaveable { mutableStateOf(false) }

    // NEW: show rectangular bars instead of dots
    var showBars by rememberSaveable { mutableStateOf(false) }

    // transient state that doesn't need to persist across rotation
    var graphPaused by remember { mutableStateOf(false) }
    var stableMidi by remember { mutableStateOf<Int?>(null) }
    var graphAlignmentDp by remember { mutableFloatStateOf(0f) }

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

    // Initialize ConsentManager and a state to track ad readiness
    val consentManager = remember { ConsentManager(context as Activity) }
    var canShowAds by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        consentManager.gatherConsent { error ->
            if (error == null) {
                // Consent logic finished successfully, now initialize Mobile Ads SDK
                MobileAds.initialize(context) {
                    // Once initialized, check if we actually have permission to show ads
                    canShowAds = consentManager.canRequestAds()
                }
            } else {
                // Handle error (optional: e.g., proceed with non-personalized ads or log error)
                canShowAds = consentManager.canRequestAds()
            }
        }
    }

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
                onResetDefaults = {
                    // Reset Sliders
                    smoothing = 0.5f
                    bpm = 60f
                    thresholdDb = -34f
                    engine.setVolumeThreshold(dbToRms(-34f))
                    whiteKeyWidthDpFloat = 56f

                    // Reset Toggles
                    autoCenter = true
                    showNoteLabels = true
                    showHorizontalGrid = true
                    showCurve = true
                    showWhiteTrace = true
                    showWhiteDots = true
                    useSamplePlayer = false
                    showBars = false
                },
                // pass optional nav controller for About navigation
                navController = navController,
                canShowAds = canShowAds
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
            // PORTRAIT MODE

            Spacer(modifier = Modifier.height(6.dp))

            // Consolidated Info & Control Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween // Pushes text to left, buttons to right
                ) {
                    val freqText = if (state.frequency > 0f) "%.1f Hz".format(Locale.US, state.frequency) else "--"
                    val noteText = if (activeMidi != null) midiToNoteName(activeMidi!!) else "-"

                    // LEFT SIDE: Note, Frequency, Confidence
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = noteText, // Removed "Note: "
                            style = MaterialTheme.typography.titleLarge // Slightly larger font
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = freqText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Conf: %.2f".format(Locale.US, state.confidence), // Shortened to save space
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // RIGHT SIDE: Hold Button & Gear Menu
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Smaller Hold Button
                        Button(
                            onClick = { graphPaused = !graphPaused },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (graphPaused) "Resume" else "Hold",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Box to anchor the Dropdown Menu to the Gear Icon
                        var menuExpandedPortrait by remember { mutableStateOf(false) }
                        Box {
                            // Smaller Gear Icon
                            IconButton(
                                onClick = { menuExpandedPortrait = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Open settings",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Dropdown Menu (Remains unchanged)
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
                                    // --- TOGGLES ---
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
                                                "Show rectangular bars",
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Switch(checked = showBars, onCheckedChange = { showBars = it })
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

                                    // --- SLIDERS ---
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

                                    Text(
                                        "Volume threshold: ${thresholdDb.roundToInt()} dB",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Slider(
                                        value = thresholdDb,
                                        onValueChange = { newDb ->
                                            thresholdDb = newDb
                                            engine.setVolumeThreshold(dbToRms(thresholdDb))
                                        },
                                        valueRange = -80f..-6f,
                                        steps = 74,
                                        modifier = Modifier.fillMaxWidth()
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

                                        // Reset Toggles
                                        autoCenter = true
                                        showNoteLabels = true
                                        showHorizontalGrid = true
                                        showCurve = true
                                        showWhiteTrace = true
                                        showWhiteDots = true
                                        useSamplePlayer = false
                                        showBars = false
                                        // Note: We don't close the menu here so the user
                                        // can actually see the sliders visually snap back.
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
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Piano (horizontal)
            Piano(
                startMidi = 24, endMidi = 84, onKeyPressed = { _, _ -> },
                activeMidi = activeMidi, autoCenter = autoCenter, stableMidi = stableMidi,
                whiteKeyWidthDp = whiteKeyWidthDpFloat.dp, scrollState = sharedScroll,
                rotated = false, blackKeyShiftFraction = 0.5f, useSamplePlayer = useSamplePlayer
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Graph (horizontal)
            PitchGraphCard(
                engine = engine,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(6.dp),
                paused = graphPaused, onTogglePause = { graphPaused = !graphPaused },
                startMidi = 24, endMidi = 84, whiteKeyWidthDp = whiteKeyWidthDpFloat.dp,
                scrollState = sharedScroll, alignmentOffsetDp = graphAlignmentDp.dp, timeWindowMs = 8000L,
                showNoteLabels = showNoteLabels, showHorizontalGrid = showHorizontalGrid,
                showCurve = showCurve, rotated = false, blackKeyShiftFraction = 0.5f,
                smoothing = smoothing, showWhiteTrace = showWhiteTrace, showWhiteDots = showWhiteDots,
                bpm = bpm, showBars = showBars
            )

            Spacer(modifier = Modifier.height(8.dp))

            // THE FIX: Only attempt to show the ad if consent is resolved and permission is granted
            // THE FIX: Only attempt to show the ad if consent is resolved and permission is granted
            if (canShowAds) {
                AdaptiveBannerAd(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    adUnitId = BuildConfig.BANNER_AD_UNIT_ID
                )
            }
        }
    }
}

// UPDATED COMPOSABLE: Adaptive Banner Ad Handler
@Composable
fun AdaptiveBannerAd(
    modifier: Modifier = Modifier,
    adUnitId: String,
    customWidth: Int? = null
) {
    val config = LocalConfiguration.current
    val adWidth = customWidth ?: config.screenWidthDp

    // The key block ensures that if the adWidth changes (e.g., orientation swap),
    // Compose will scrap the old AndroidView and trigger the 'factory' block again.
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
            update = {
                // LEAVE THIS EMPTY.
                // Do not attempt to call setAdSize() here.
            },
            onRelease = { adView ->
                // Properly clean up resources to prevent memory leaks
                adView.destroy()
            }
        )
    }
}

/**
 * Compact landscape TopAppBar:
 * - holds only Hold/Resume + Gear. Gear contains grouped toggles first, then sliders (no key width)
 * - app bar height reduced to reclaim vertical space in landscape
 */
@Suppress("LongParameterList", "LongMethod", "MagicNumber") // refactor later to reduce params/length
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
    thresholdDb: Float,
    onThresholdChange: (Float) -> Unit,
    bpm: Float,
    onBpmChange: (Float) -> Unit,
    showBars: Boolean,
    onToggleShowBars: (Boolean) -> Unit,
    useSamplePlayer: Boolean,
    onToggleUseSamplePlayer: (Boolean) -> Unit,
    showWhiteDots: Boolean,
    onShowWhiteDotsChange: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    canShowAds: Boolean, // NEW PARAMETER
    navController: NavHostController? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        // A Box allows the Ad to float in the center while elements pin to the sides
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // LEFT SIDE: Note, Freq, Conf (Multi-line)
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.Center
            ) {
                val noteText = if (activeMidi != null) midiToNoteName(activeMidi) else "-"
                val freqText = if (detectedFreq > 0f) "%.1f Hz".format(Locale.US, detectedFreq) else "--"

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = noteText, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = freqText, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(2.dp))
                // Moved confidence to the next line
                Text(
                    text = "Confidence: %.2f".format(Locale.US, detectedConfidence),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // CENTER: The Floating Landscape Ad
            if (canShowAds) {
                val config = LocalConfiguration.current
                val screenWidth = config.screenWidthDp

                // Reserve 130dp for left text, 80dp for right buttons, and 40dp for safety gaps
                val reservedSpace = 250
                val adWidth = screenWidth - reservedSpace

                // Only render if there is enough space (AdMob requires at least 320dp width)
                if (adWidth >= 320) {
                    AdaptiveBannerAd(
                        adUnitId = BuildConfig.BANNER_AD_UNIT_LANDSCAPE_ID,
                        customWidth = adWidth, // Passes calculated safe width
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // RIGHT SIDE: Hold button and Settings (Multi-line)
            Column(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalAlignment = Alignment.End
            ) {
                // Decreased size Hold Button
                Button(
                    onClick = onTogglePause,
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (paused) "Resume" else "Hold",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Gear menu placed below the hold button
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(28.dp) // Smaller footprint
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Graph options",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // DropdownMenu (Unchanged from your previous code)
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.width(320.dp)
                    ) {
                        val landscapeMenuScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .heightIn(max = 360.dp)
                                .verticalScroll(landscapeMenuScroll),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // --- TOGGLES ---
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
                                        onCheckedChange = onToggleShowNoteLabels
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
                                        onCheckedChange = onToggleShowHorizontalGrid
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
                                            onToggleShowCurve(checked)
                                            onShowWhiteTraceChange(checked)
                                        }
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Show rectangular bars",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(checked = showBars, onCheckedChange = onToggleShowBars)
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
                                    Switch(
                                        checked = autoCenter,
                                        onCheckedChange = onAutoCenterToggle
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
                                    Switch(checked = showWhiteDots, onCheckedChange = onShowWhiteDotsChange)
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
                                        onCheckedChange = onToggleUseSamplePlayer
                                    )
                                }
                            }

                            HorizontalDivider()

                            // --- SLIDERS ---
                            Text(
                                "Smoothing: ${(smoothing * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = smoothing,
                                onValueChange = onSmoothingChange,
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                "Tempo: ${bpm.roundToInt()} BPM",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = bpm,
                                onValueChange = onBpmChange,
                                valueRange = 60f..240f,
                                steps = 180,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                "Volume threshold: ${thresholdDb.roundToInt()} dB",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = thresholdDb,
                                onValueChange = onThresholdChange,
                                valueRange = -80f..-6f,
                                steps = 74,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        // DEFAULT BUTTON
                        DropdownMenuItem(
                            text = { Text("Reset to Defaults") },
                            onClick = {
                                onResetDefaults()
                            }
                        )

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
    }
}
