package com.jsaiborne.vocalpitchdetector

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// --- Data Model ---
data class RecordingSession(
    val sessionId: String,
    val timestampMs: Long,
    val audioFile: File,
    val pitchFile: File,
    val sessionNumber: Int = 0,
    val customName: String? = null,
    val isStarred: Boolean = false,
    val durationMs: Long = 0L
) {
    val formattedDate: String
        get() {
            val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return formatter.format(Date(timestampMs))
        }

    val formattedDuration: String
        get() = formatDuration(durationMs)

    val displayTitle: String
        get() = if (!customName.isNullOrBlank()) customName else "Vocal Session $sessionNumber"
}

// --- Session files, numbering and duration (shared with the playback screen) ---

private const val RECORDING_SAMPLE_RATE = 44100
private const val BYTES_PER_SAMPLE = 2 // mono, 16-bit
private const val WAV_HEADER_SIZE = 44L
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

private const val NUMBERS_PREFS = "recording_numbers"
private const val NEXT_NUMBER_KEY = "__next"
private val numberingLock = Any()

/** Length of a recording made by [AudioRecordPitchDetector] (44.1 kHz mono 16-bit PCM WAV). */
internal fun wavDurationMs(fileLengthBytes: Long): Long {
    val dataBytes = (fileLengthBytes - WAV_HEADER_SIZE).coerceAtLeast(0L)
    return dataBytes * MS_PER_SECOND / (RECORDING_SAMPLE_RATE * BYTES_PER_SAMPLE)
}

/** "m:ss" for a duration in milliseconds. */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / MS_PER_SECOND
    return String.format(Locale.US, "%d:%02d", totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

/**
 * Lists complete recordings (both the audio and the pitch file exist) as sessionId -> files.
 * Ids are the millisecond timestamp the recording was started at.
 */
internal fun listSessionFiles(recordingsDir: File): Map<String, Pair<File, File>> {
    val audio = mutableMapOf<String, File>()
    val pitch = mutableMapOf<String, File>()
    recordingsDir.listFiles()?.forEach { file ->
        val name = file.name
        when {
            name.startsWith("session_") && name.endsWith("_audio.wav") ->
                audio[name.substringAfter("session_").substringBefore("_audio.wav")] = file
            name.startsWith("session_") && name.endsWith("_pitch.json") ->
                pitch[name.substringAfter("session_").substringBefore("_pitch.json")] = file
        }
    }
    return audio.filterKeys { it in pitch }.mapValues { (id, audioFile) -> audioFile to pitch.getValue(id) }
}

internal fun sessionTimestamp(sessionId: String, audioFile: File): Long =
    sessionId.toLongOrNull() ?: audioFile.lastModified()

/**
 * Keeps the numbers already handed out and gives new sessions (oldest first) the next free ones,
 * so deleting an old recording never renumbers the rest. Returns the numbers for [timestamps] and
 * the next unused number.
 */
internal fun assignSessionNumbers(
    existing: Map<String, Int>,
    nextNumber: Int,
    timestamps: Map<String, Long>
): Pair<Map<String, Int>, Int> {
    val result = existing.filterKeys { it in timestamps }.toMutableMap()
    var next = maxOf(nextNumber, (result.values.maxOrNull() ?: 0) + 1)
    timestamps.entries
        .filter { it.key !in result }
        .sortedBy { it.value }
        .forEach { result[it.key] = next++ }
    return result to next
}

/** Stable "Vocal Session N" numbers, persisted so they survive deletions. */
internal fun sessionNumbersFor(context: Context, timestamps: Map<String, Long>): Map<String, Int> =
    synchronized(numberingLock) {
        val prefs = context.getSharedPreferences(NUMBERS_PREFS, Context.MODE_PRIVATE)
        val existing = timestamps.keys
            .filter { prefs.contains(it) }
            .associateWith { prefs.getInt(it, 0) }
        val (numbers, next) = assignSessionNumbers(existing, prefs.getInt(NEXT_NUMBER_KEY, 1), timestamps)

        if (numbers.size != existing.size) {
            prefs.edit().apply {
                numbers.forEach { (id, number) -> putInt(id, number) }
                putInt(NEXT_NUMBER_KEY, next)
            }.apply()
        }
        numbers
    }

class RecordingsViewModel : ViewModel() {
    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    fun loadSessions(context: Context, recordingsDir: File) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingsDir.exists()) {
                _sessions.value = emptyList()
                return@launch
            }

            val namePrefs = appContext.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
            val starPrefs = appContext.getSharedPreferences("recording_stars", Context.MODE_PRIVATE)

            val files = listSessionFiles(recordingsDir)
            val numbers = sessionNumbersFor(
                appContext,
                files.mapValues { (id, pair) -> sessionTimestamp(id, pair.first) }
            )

            val sessions = files.map { (id, pair) ->
                val (audio, pitch) = pair
                RecordingSession(
                    sessionId = id,
                    timestampMs = sessionTimestamp(id, audio),
                    audioFile = audio,
                    pitchFile = pitch,
                    sessionNumber = numbers[id] ?: 0,
                    customName = namePrefs.getString(id, null),
                    isStarred = starPrefs.getBoolean(id, false),
                    durationMs = wavDurationMs(audio.length())
                )
            }

            // Starred recordings first, then newest first
            _sessions.value = sessions.sortedWith(
                compareByDescending<RecordingSession> { it.isStarred }.thenByDescending { it.timestampMs }
            )
        }
    }

    fun renameSession(context: Context, sessionId: String, newName: String, recordingsDir: File) {
        val prefs = context.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
        if (newName.isNotBlank()) {
            prefs.edit().putString(sessionId, newName.trim()).apply()
        } else {
            prefs.edit().remove(sessionId).apply()
        }
        loadSessions(context, recordingsDir)
    }

    fun toggleStar(context: Context, sessionId: String, currentlyStarred: Boolean, recordingsDir: File) {
        val starPrefs = context.getSharedPreferences("recording_stars", Context.MODE_PRIVATE)
        starPrefs.edit().putBoolean(sessionId, !currentlyStarred).apply()
        loadSessions(context, recordingsDir)
    }

    fun deleteSession(context: Context, session: RecordingSession, recordingsDir: File) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            session.audioFile.delete()
            session.pitchFile.delete()

            // Clean up SharedPreferences so deleted session IDs don't pile up
            appContext.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
                .edit().remove(session.sessionId).apply()
            appContext.getSharedPreferences("recording_stars", Context.MODE_PRIVATE)
                .edit().remove(session.sessionId).apply()
            // The counter (NEXT_NUMBER_KEY) is left alone so numbers are never reused
            appContext.getSharedPreferences(NUMBERS_PREFS, Context.MODE_PRIVATE)
                .edit().remove(session.sessionId).apply()

            loadSessions(appContext, recordingsDir)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onNavigateUp: () -> Unit,
    onSessionSelected: (String) -> Unit,
    viewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val recordingsDir = remember { File(context.filesDir, "recordings") }
    val sessions by viewModel.sessions.collectAsState()

    var sessionToRename by remember { mutableStateOf<RecordingSession?>(null) }
    var sessionToDelete by remember { mutableStateOf<RecordingSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadSessions(context, recordingsDir)
    }

    // --- Rename Dialog ---
    sessionToRename?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Rename Recording") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text("e.g., Chorus Take 1") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(context, session.sessionId, renameText, recordingsDir)
                    sessionToRename = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) { Text("Cancel") }
            }
        )
    }

    // --- Delete Confirmation Dialog ---
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Recording") },
            text = {
                Text("Delete \"${session.displayTitle}\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(context, session, recordingsDir)
                    sessionToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (sessions.isEmpty()) {
                EmptyStateMessage(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        RecordingItem(
                            session = session,
                            onClick = { onSessionSelected(session.sessionId) },
                            onToggleStar = {
                                viewModel.toggleStar(
                                    context,
                                    session.sessionId,
                                    session.isStarred,
                                    recordingsDir
                                )
                            },
                            onRename = {
                                renameText = session.customName ?: ""
                                sessionToRename = session
                            },
                            onDelete = { sessionToDelete = session }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingItem(
    session: RecordingSession,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = session.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${session.formattedDate} • ${session.formattedDuration}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleStar) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = if (session.isStarred) "Unstar Session" else "Star Session",
                        // Primary colour if starred, otherwise a faded surface-variant colour
                        tint = if (session.isStarred) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                }

                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Rename Session",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Session",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No recordings yet.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Hit the record button on the main screen to capture your pitch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
