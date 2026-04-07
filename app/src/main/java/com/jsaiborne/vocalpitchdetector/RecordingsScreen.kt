package com.jsaiborne.vocalpitchdetector

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.util.*
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
    val customName: String? = null // NEW: Holds the user-defined name
) {
    val formattedDate: String
        get() {
            val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return formatter.format(Date(timestampMs))
        }

    val displayTitle: String
        get() = if (!customName.isNullOrBlank()) customName else "Vocal Session $sessionNumber"
}

class RecordingsViewModel : ViewModel() {
    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    fun loadSessions(context: Context, recordingsDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingsDir.exists()) {
                _sessions.value = emptyList()
                return@launch
            }

            val prefs = context.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
            val sessionMap = mutableMapOf<String, Pair<File?, File?>>()

            recordingsDir.listFiles()?.forEach { file ->
                val name = file.name
                if (name.startsWith("session_") && name.endsWith("_audio.wav")) {
                    val id = name.substringAfter("session_").substringBefore("_audio.wav")
                    val existing = sessionMap[id] ?: Pair(null, null)
                    sessionMap[id] = existing.copy(first = file)
                } else if (name.startsWith("session_") && name.endsWith("_pitch.json")) {
                    val id = name.substringAfter("session_").substringBefore("_pitch.json")
                    val existing = sessionMap[id] ?: Pair(null, null)
                    sessionMap[id] = existing.copy(second = file)
                }
            }

            val validSessions = sessionMap.mapNotNull { (id, pair) ->
                val audio = pair.first
                val pitch = pair.second
                if (audio != null && pitch != null) {
                    val timestamp = id.toLongOrNull() ?: audio.lastModified()
                    val customName = prefs.getString(id, null)
                    RecordingSession(id, timestamp, audio, pitch, customName = customName)
                } else {
                    null
                }
            }

            // 1. Sort chronologically (oldest first) to assign correct serial numbers
            val chronologicallySorted = validSessions.sortedBy { it.timestampMs }

            // 2. Assign serial numbers (1, 2, 3...)
            val numberedSessions = chronologicallySorted.mapIndexed { index, session ->
                session.copy(sessionNumber = index + 1)
            }

            // 3. Reverse the list so the newest recordings appear at the top
            _sessions.value = numberedSessions.reversed()
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

    fun deleteSession(context: Context, session: RecordingSession, recordingsDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            session.audioFile.delete()
            session.pitchFile.delete()

            // Clean up SharedPreferences
            context.getSharedPreferences("recording_names", Context.MODE_PRIVATE)
                .edit().remove(session.sessionId).apply()

            loadSessions(context, recordingsDir)
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
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.loadSessions(context, recordingsDir)
    }

    // --- Rename Dialog ---
    if (sessionToRename != null) {
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
                    viewModel.renameSession(context, sessionToRename!!.sessionId, renameText, recordingsDir)
                    sessionToRename = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) { Text("Cancel") }
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
                    items(sessions) { session ->
                        RecordingItem(
                            session = session,
                            onClick = { onSessionSelected(session.sessionId) },
                            onRename = {
                                renameText = session.customName ?: ""
                                sessionToRename = session
                            },
                            onDelete = { viewModel.deleteSession(context, session, recordingsDir) }
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
                        text = session.formattedDate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row {
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
