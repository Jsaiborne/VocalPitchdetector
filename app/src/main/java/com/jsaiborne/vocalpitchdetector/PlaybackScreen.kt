@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.content.res.Configuration
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Data model for the recorded points.
data class RecordedPitchPoint(
    val timestampMs: Long,
    val frequencyHz: Float,
    val midiNote: Int
)

/**
 * Un-rounded MIDI pitch, so playback curves glide between semitone lines like the live graph.
 * [RecordedPitchPoint.midiNote] is rounded at record time; this is recovered from [RecordedPitchPoint.frequencyHz].
 */
internal val RecordedPitchPoint.midiFloat: Float
    get() = if (frequencyHz > 0f) freqToMidi(frequencyHz.toDouble()).toFloat() else midiNote.toFloat()

class PlaybackViewModel : ViewModel() {
    private var mediaPlayer: MediaPlayer? = null

    var isPlaying by mutableStateOf(false)
        private set
    var currentPositionMs by mutableStateOf(0L)
        private set
    var totalDurationMs by mutableStateOf(0L)
        private set
    var pitchData by mutableStateOf<List<RecordedPitchPoint>>(emptyList())
        private set
    var stableMarkers by mutableStateOf<List<RecordedPitchPoint>>(emptyList())
        private set
    var sessionTitle by mutableStateOf("")
        private set
    var sessionDate by mutableStateOf("")
        private set

    private var loadedAudioPath: String? = null
    private var loadJob: Job? = null

    fun loadSession(
        context: Context,
        audioFile: File,
        pitchFile: File
    ) {
        // The screen re-runs this on recomposition; don't rebuild the player for the same file.
        if (loadedAudioPath == audioFile.absolutePath) return
        loadedAudioPath = audioFile.absolutePath

        loadJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        currentPositionMs = 0L
        totalDurationMs = 0L

        val appContext = context.applicationContext
        loadJob = viewModelScope.launch {
            // MediaPlayer.prepare() blocks on file IO, so keep it off the main thread
            val player = withContext(Dispatchers.IO) { createPlayer(audioFile) }
            if (!isActive) {
                player?.release()
                return@launch
            }

            mediaPlayer = player
            totalDurationMs = player?.duration?.toLong() ?: 0L

            val (data, markers) = parsePitchData(pitchFile)
            pitchData = data
            stableMarkers = markers

            val (title, date) = calculateMetadata(appContext, audioFile)
            sessionTitle = title
            sessionDate = date
        }
    }

    private fun createPlayer(audioFile: File): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setDataSource(audioFile.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                isPlaying = false
                currentPositionMs = 0L
                it.seekTo(0)
            }
            player
        } catch (e: java.io.IOException) {
            android.util.Log.e("PlaybackViewModel", "Failed to prepare audio", e)
            player.release()
            null
        } catch (e: IllegalStateException) {
            android.util.Log.e("PlaybackViewModel", "Failed to prepare audio", e)
            player.release()
            null
        }
    }

    private suspend fun calculateMetadata(
        context: Context,
        audioFile: File
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val dir = audioFile.parentFile
        val name = audioFile.name
        val idStr = name.substringAfter("session_").substringBefore("_audio.wav")
        val currentTimestamp = sessionTimestamp(idStr, audioFile)

        // Same persisted numbering the recordings list uses, so titles always agree
        val timestamps = (dir?.let { listSessionFiles(it) } ?: emptyMap())
            .mapValues { (id, files) -> sessionTimestamp(id, files.first) } + (idStr to currentTimestamp)
        val sessionNum = sessionNumbersFor(context, timestamps)[idStr] ?: 1

        val prefs = context.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
        val customName = prefs.getString(idStr, null)
        val titleName = if (!customName.isNullOrBlank()) {
            customName
        } else {
            "Vocal Session $sessionNum"
        }

        val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
        val dateStr = formatter.format(Date(currentTimestamp))

        Pair(titleName, dateStr)
    }

    private suspend fun parsePitchData(
        pitchFile: File
    ): Pair<List<RecordedPitchPoint>, List<RecordedPitchPoint>> = withContext(Dispatchers.IO) {
        val pitchDataList = mutableListOf<RecordedPitchPoint>()
        val stableMarkersList = mutableListOf<RecordedPitchPoint>()

        if (pitchFile.exists()) {
            try {
                val jsonString = pitchFile.readText().trimStart()

                if (jsonString.startsWith("[")) {
                    // --- OLD FORMAT (Legacy support for previous recordings) ---
                    val jsonArray = JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        pitchDataList.add(
                            RecordedPitchPoint(
                                timestampMs = obj.getLong("timestampMs"),
                                frequencyHz = obj.getDouble("frequencyHz").toFloat(),
                                midiNote = obj.getInt("midiNote")
                            )
                        )
                    }
                } else if (jsonString.startsWith("{")) {
                    // --- NEW FORMAT (Includes stable markers) ---
                    val rootObj = JSONObject(jsonString)

                    // Extract Pitch Data
                    if (rootObj.has("pitchData")) {
                        val pitchArray = rootObj.getJSONArray("pitchData")
                        for (i in 0 until pitchArray.length()) {
                            val obj = pitchArray.getJSONObject(i)
                            pitchDataList.add(
                                RecordedPitchPoint(
                                    timestampMs = obj.getLong("timestampMs"),
                                    frequencyHz = obj.getDouble("frequencyHz").toFloat(),
                                    midiNote = obj.getInt("midiNote")
                                )
                            )
                        }
                    }

                    // Extract Stable Markers
                    if (rootObj.has("stableNotes")) {
                        val stableArray = rootObj.getJSONArray("stableNotes")
                        for (i in 0 until stableArray.length()) {
                            val obj = stableArray.getJSONObject(i)
                            stableMarkersList.add(
                                RecordedPitchPoint(
                                    timestampMs = obj.getLong("timestampMs"),
                                    frequencyHz = obj.getDouble("frequencyHz").toFloat(),
                                    midiNote = obj.getInt("midiNote")
                                )
                            )
                        }
                    }
                }
            } catch (e: org.json.JSONException) {
                android.util.Log.e("PlaybackViewModel", "Failed to parse pitch JSON", e)
            } catch (e: java.io.IOException) {
                android.util.Log.e("PlaybackViewModel", "Failed to read pitch file", e)
            }
        }
        Pair(pitchDataList, stableMarkersList)
    }

    fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
            } else {
                player.start()
                isPlaying = true
            }
        }
    }

    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        currentPositionMs = positionMs
    }

    fun updatePositionFromPlayer() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                currentPositionMs = player.currentPosition.toLong()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun PlaybackScreen(
    audioFile: File,
    pitchFile: File,
    onNavigateUp: () -> Unit,
    viewModel: PlaybackViewModel = viewModel()
) {
    val context = LocalContext.current
    DisposableEffect(audioFile.absolutePath) {
        viewModel.loadSession(context, audioFile, pitchFile)
        onDispose {
            if (viewModel.isPlaying) viewModel.pause()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(viewModel.isPlaying) {
        while (viewModel.isPlaying) {
            viewModel.updatePositionFromPlayer()
            delay(16L)
        }
    }

    val canShowAds = LocalCanShowAds.current

    var showCurve by remember { mutableStateOf(true) }
    var showDots by remember { mutableStateOf(false) }
    var showBars by remember { mutableStateOf(false) }
    var showNoteLabels by remember { mutableStateOf(true) }
    var autoCenter by remember { mutableStateOf(true) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    Scaffold(
        topBar = {
            if (isPortrait) {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = viewModel.sessionTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Auto-Center") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = autoCenter,
                                        onCheckedChange = { autoCenter = it }
                                    )
                                },
                                onClick = { autoCenter = !autoCenter }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Show Curve") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = showCurve,
                                        onCheckedChange = { showCurve = it }
                                    )
                                },
                                onClick = { showCurve = !showCurve }
                            )
                            DropdownMenuItem(
                                text = { Text("Show Dots") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = showDots,
                                        onCheckedChange = { showDots = it }
                                    )
                                },
                                onClick = { showDots = !showDots }
                            )
                            DropdownMenuItem(
                                text = { Text("Show Bars") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = showBars,
                                        onCheckedChange = { showBars = it }
                                    )
                                },
                                onClick = { showBars = !showBars }
                            )
                            DropdownMenuItem(
                                text = { Text("Markers & Labels") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = showNoteLabels,
                                        onCheckedChange = { showNoteLabels = it }
                                    )
                                },
                                onClick = { showNoteLabels = !showNoteLabels }
                            )
                        }
                    }
                )
            } else {
                // Custom landscape TopAppBar to hold the ad
                TopAppBarPlaybackLandscape(
                    sessionTitle = viewModel.sessionTitle,
                    onNavigateUp = onNavigateUp,
                    canShowAds = canShowAds,
                    showSettingsMenu = showSettingsMenu,
                    onToggleSettingsMenu = { showSettingsMenu = it },
                    autoCenter = autoCenter,
                    onToggleAutoCenter = { autoCenter = it },
                    showCurve = showCurve,
                    onToggleShowCurve = { showCurve = it },
                    showDots = showDots,
                    onToggleShowDots = { showDots = it },
                    showBars = showBars,
                    onToggleShowBars = { showBars = it },
                    showNoteLabels = showNoteLabels,
                    onToggleShowNoteLabels = { showNoteLabels = it }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                if (isPortrait) {
                    PortraitPlaybackPitchGraph(
                        pitchData = viewModel.pitchData,
                        currentPositionMs = viewModel.currentPositionMs,
                        stableMarkers = viewModel.stableMarkers,
                        showCurve = showCurve,
                        showWhiteDots = showDots,
                        showBars = showBars,
                        showNoteLabels = showNoteLabels,
                        autoCenter = autoCenter
                    )
                } else {
                    LandscapePlaybackPitchGraph(
                        pitchData = viewModel.pitchData,
                        currentPositionMs = viewModel.currentPositionMs,
                        stableMarkers = viewModel.stableMarkers,
                        showCurve = showCurve,
                        showWhiteDots = showDots,
                        showBars = showBars,
                        showNoteLabels = showNoteLabels,
                        autoCenter = autoCenter
                    )
                }
            }

            // --- Compact Controls Section ---
            val formatTime = ::formatDuration

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { viewModel.togglePlayPause() }) {
                    Icon(
                        imageVector = if (viewModel.isPlaying) {
                            Icons.Default.Pause
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (viewModel.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = formatTime(viewModel.currentPositionMs),
                    style = MaterialTheme.typography.bodySmall
                )

                Slider(
                    value = viewModel.currentPositionMs.toFloat(),
                    valueRange = 0f..(viewModel.totalDurationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = { newPos -> viewModel.seekTo(newPos.toLong()) },
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatTime(viewModel.totalDurationMs),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // --- Portrait Banner Ad ---
            if (isPortrait && canShowAds) {
                Spacer(modifier = Modifier.height(4.dp))
                AdaptiveBannerAd(
                    modifier = Modifier.fillMaxWidth(),
                    adUnitId = BuildConfig.BANNER_AD_UNIT_PLAYBACK_PORTRAIT_ID
                )
            } else if (isPortrait) {
                Spacer(modifier = Modifier.height(60.dp))
            }
        }
    }
}

// --- UPDATED COMPOSABLE: Custom Landscape TopAppBar for Playback Screen ---
@Suppress("LongParameterList")
@Composable
private fun TopAppBarPlaybackLandscape(
    sessionTitle: String,
    onNavigateUp: () -> Unit,
    canShowAds: Boolean,
    showSettingsMenu: Boolean,
    onToggleSettingsMenu: (Boolean) -> Unit,
    autoCenter: Boolean,
    onToggleAutoCenter: (Boolean) -> Unit,
    showCurve: Boolean,
    onToggleShowCurve: (Boolean) -> Unit,
    showDots: Boolean,
    onToggleShowDots: (Boolean) -> Unit,
    showBars: Boolean,
    onToggleShowBars: (Boolean) -> Unit,
    showNoteLabels: Boolean,
    onToggleShowNoteLabels: (Boolean) -> Unit
) {
    // Replaced Box with Row so the title automatically respects the ad boundaries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT SIDE: Back Button
        IconButton(onClick = onNavigateUp) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }

        Spacer(modifier = Modifier.width(4.dp))

        // LEFT/CENTER: Dynamic Title Text that wraps and doesn't push into the ad
        Text(
            text = sessionTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )

        // RIGHT/CENTER: Floating Landscape Ad
        if (canShowAds) {
            val config = LocalConfiguration.current
            val screenWidth = config.screenWidthDp

            // Reserve more space for back button/title and settings to push the ad rightward
            val reservedSpace = 280
            val adWidth = screenWidth - reservedSpace

            if (adWidth >= 320) {
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    AdaptiveBannerAd(
                        adUnitId = BuildConfig.BANNER_AD_UNIT_PLAYBACK_LANDSCAPE_ID,
                        customWidth = adWidth
                    )
                }
            }
        }

        // RIGHT SIDE: Settings Menu
        Box {
            IconButton(onClick = { onToggleSettingsMenu(true) }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
            DropdownMenu(
                expanded = showSettingsMenu,
                onDismissRequest = { onToggleSettingsMenu(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Auto-Center") },
                    trailingIcon = {
                        Checkbox(
                            checked = autoCenter,
                            onCheckedChange = { onToggleAutoCenter(it) }
                        )
                    },
                    onClick = { onToggleAutoCenter(!autoCenter) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Show Curve") },
                    trailingIcon = {
                        Checkbox(
                            checked = showCurve,
                            onCheckedChange = { onToggleShowCurve(it) }
                        )
                    },
                    onClick = { onToggleShowCurve(!showCurve) }
                )
                DropdownMenuItem(
                    text = { Text("Show Dots") },
                    trailingIcon = {
                        Checkbox(
                            checked = showDots,
                            onCheckedChange = { onToggleShowDots(it) }
                        )
                    },
                    onClick = { onToggleShowDots(!showDots) }
                )
                DropdownMenuItem(
                    text = { Text("Show Bars") },
                    trailingIcon = {
                        Checkbox(
                            checked = showBars,
                            onCheckedChange = { onToggleShowBars(it) }
                        )
                    },
                    onClick = { onToggleShowBars(!showBars) }
                )
                DropdownMenuItem(
                    text = { Text("Markers & Labels") },
                    trailingIcon = {
                        Checkbox(
                            checked = showNoteLabels,
                            onCheckedChange = { onToggleShowNoteLabels(it) }
                        )
                    },
                    onClick = { onToggleShowNoteLabels(!showNoteLabels) }
                )
            }
        }
    }
}
