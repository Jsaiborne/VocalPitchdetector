@file:Suppress("TooManyFunctions")

package com.jsaiborne.vocalpitchdetector

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Data Model ---
data class RecordingSession(
    val sessionId: String,
    val timestampMs: Long,
    val audioFile: File,
    val pitchFile: File,
    val sessionNumber: Int = 0,
    val customName: String? = null,
    val isStarred: Boolean = false,
    val durationMs: Long = 0L,
    val note: String? = null,
    /** Audio plus pitch file, so the list can show how much space recordings take. */
    val sizeBytes: Long = 0L
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

/** How the recordings list is ordered. Starred recordings are always kept on top. */
enum class RecordingSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LONGEST("Longest first"),
    NAME("Name (A–Z)")
}

/** Leftover session files (audio without pitch data, or the reverse) that never show up in the list. */
data class IncompleteFiles(val count: Int = 0, val sizeBytes: Long = 0L)

// --- Session files, numbering and duration (shared with the playback screen) ---

private const val RECORDING_SAMPLE_RATE = 44100
private const val BYTES_PER_SAMPLE = 2 // mono, 16-bit
private const val WAV_HEADER_SIZE = 44L
private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

private const val NUMBERS_PREFS = "recording_numbers"
private const val NOTES_PREFS = "recording_notes"
private const val NAMES_PREFS = "recording_names"
private const val STARS_PREFS = "recording_stars"

/** The note the user attached to a recording, or null when there is none. */
internal fun readRecordingNote(context: Context, sessionId: String): String? =
    context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
        .getString(sessionId, null)
        ?.takeIf { it.isNotBlank() }
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

/** Every session audio file and every session pitch file in [recordingsDir], each keyed by session id. */
private fun scanSessionFiles(recordingsDir: File): Pair<Map<String, File>, Map<String, File>> {
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
    return audio to pitch
}

/**
 * Lists complete recordings (both the audio and the pitch file exist) as sessionId -> files.
 * Ids are the millisecond timestamp the recording was started at.
 */
internal fun listSessionFiles(recordingsDir: File): Map<String, Pair<File, File>> {
    val (audio, pitch) = scanSessionFiles(recordingsDir)
    return audio.filterKeys { it in pitch }.mapValues { (id, audioFile) -> audioFile to pitch.getValue(id) }
}

private const val ORPHAN_MIN_AGE_MS = 60_000L

/**
 * Session files whose partner is missing (audio without pitch data, or the reverse). They never show in
 * the list but still use disk space. Files touched within [minAgeMs] are skipped: right after a recording
 * stops, its pitch file is still being written.
 */
internal fun findOrphanFiles(recordingsDir: File, nowMs: Long, minAgeMs: Long = ORPHAN_MIN_AGE_MS): List<File> {
    val (audio, pitch) = scanSessionFiles(recordingsDir)
    val lonely = audio.filterKeys { it !in pitch }.values + pitch.filterKeys { it !in audio }.values
    return lonely.filter { nowMs - it.lastModified() >= minAgeMs }.sortedBy { it.name }
}

private fun RecordingSession.matches(query: String, starredOnly: Boolean): Boolean {
    if (starredOnly && !isStarred) return false
    if (query.isEmpty()) return true
    return displayTitle.contains(query, ignoreCase = true) || note?.contains(query, ignoreCase = true) == true
}

/**
 * Applies the search text (matched against the title and note, ignoring case), the starred-only filter
 * and the sort order. Starred recordings stay on top whichever sort is chosen.
 */
internal fun filterAndSortSessions(
    sessions: List<RecordingSession>,
    query: String,
    starredOnly: Boolean,
    sort: RecordingSort
): List<RecordingSession> {
    val needle = query.trim()
    val order: Comparator<RecordingSession> = when (sort) {
        RecordingSort.NEWEST -> compareByDescending<RecordingSession> { it.timestampMs }
        RecordingSort.OLDEST -> compareBy<RecordingSession> { it.timestampMs }
        RecordingSort.LONGEST -> compareByDescending<RecordingSession> { it.durationMs }
        RecordingSort.NAME -> compareBy<RecordingSession, String>(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
    }
    return sessions
        .filter { it.matches(needle, starredOnly) }
        .sortedWith(compareByDescending<RecordingSession> { it.isStarred }.then(order))
}

/** What a batch star action should do: unstar when every selected recording is starred already, else star all. */
internal fun starTargetFor(selected: List<RecordingSession>): Boolean = !selected.all { it.isStarred }

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
    /** Every complete recording, in no particular order. The screen shows [visibleSessions]. */
    private val _sessions = MutableStateFlow<List<RecordingSession>>(emptyList())
    val sessions: StateFlow<List<RecordingSession>> = _sessions.asStateFlow()

    // Search, filter, sort and selection live here (not in the composable) so they survive rotation
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _starredOnly = MutableStateFlow(false)
    val starredOnly: StateFlow<Boolean> = _starredOnly.asStateFlow()

    private val _sort = MutableStateFlow(RecordingSort.NEWEST)
    val sort: StateFlow<RecordingSort> = _sort.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _incomplete = MutableStateFlow(IncompleteFiles())
    val incomplete: StateFlow<IncompleteFiles> = _incomplete.asStateFlow()

    /** What the list shows: [sessions] after the search text, the starred filter and the sort. */
    val visibleSessions: StateFlow<List<RecordingSession>> =
        combine(_sessions, _query, _starredOnly, _sort) { all, text, onlyStarred, order ->
            filterAndSortSessions(all, text, onlyStarred, order)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setStarredOnly(value: Boolean) {
        _starredOnly.value = value
    }

    fun setSort(value: RecordingSort) {
        _sort.value = value
    }

    fun clearFilters() {
        _query.value = ""
        _starredOnly.value = false
    }

    /** Starts selecting, optionally with [firstId] already ticked (a long-press on that card). */
    fun enterSelection(firstId: String? = null) {
        _selectionMode.value = true
        if (firstId != null) _selectedIds.update { it + firstId }
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelected(id: String) {
        _selectedIds.update { if (id in it) it - id else it + id }
    }

    /** Ticks every recording currently shown (so respecting the search and filter). */
    fun selectAllVisible() {
        _selectedIds.value = visibleSessions.value.map { it.sessionId }.toSet()
    }

    fun loadSessions(context: Context, recordingsDir: File) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            if (!recordingsDir.exists()) {
                _sessions.value = emptyList()
                _incomplete.value = IncompleteFiles()
                return@launch
            }

            val namePrefs = appContext.getSharedPreferences(NAMES_PREFS, Context.MODE_PRIVATE)
            val starPrefs = appContext.getSharedPreferences(STARS_PREFS, Context.MODE_PRIVATE)
            val notePrefs = appContext.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)

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
                    durationMs = wavDurationMs(audio.length()),
                    note = notePrefs.getString(id, null),
                    sizeBytes = audio.length() + pitch.length()
                )
            }

            // Ordering (starred first, then the chosen sort) is applied by visibleSessions
            _sessions.value = sessions

            // Forget ticks for recordings that no longer exist (e.g. just deleted)
            val existingIds = sessions.map { it.sessionId }.toSet()
            _selectedIds.update { it intersect existingIds }

            // A take that's still being recorded has its audio but no pitch file yet; it isn't a leftover
            val leftovers = if (PitchEngineProvider.get().isRecording.value) {
                emptyList()
            } else {
                findOrphanFiles(recordingsDir, System.currentTimeMillis())
            }
            _incomplete.value = IncompleteFiles(leftovers.size, leftovers.sumOf { it.length() })
        }
    }

    /** Saves the custom name and note together; blank values clear them. */
    fun saveDetails(context: Context, sessionId: String, newName: String, newNote: String, recordingsDir: File) {
        fun updateText(prefsName: String, value: String) {
            val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            if (value.isNotBlank()) editor.putString(sessionId, value.trim()) else editor.remove(sessionId)
            editor.apply()
        }
        updateText(NAMES_PREFS, newName)
        updateText(NOTES_PREFS, newNote)
        loadSessions(context, recordingsDir)
    }

    fun toggleStar(context: Context, sessionId: String, currentlyStarred: Boolean, recordingsDir: File) {
        setStarred(context, setOf(sessionId), !currentlyStarred, recordingsDir)
    }

    /** Stars or unstars every recording in [ids] with a single preferences write. */
    fun setStarred(context: Context, ids: Set<String>, starred: Boolean, recordingsDir: File) {
        context.getSharedPreferences(STARS_PREFS, Context.MODE_PRIVATE).edit()
            .apply { ids.forEach { putBoolean(it, starred) } }
            .apply()
        loadSessions(context, recordingsDir)
    }

    /** Deletes the recordings' files and forgets their names, stars, notes and numbers, then reloads once. */
    fun deleteSessions(context: Context, toDelete: List<RecordingSession>, recordingsDir: File) {
        if (toDelete.isEmpty()) return
        val appContext = context.applicationContext
        val ids = toDelete.map { it.sessionId }.toSet()

        // Drop them from the list right away; the files follow on the IO thread
        _sessions.update { all -> all.filterNot { it.sessionId in ids } }

        viewModelScope.launch(Dispatchers.IO) {
            toDelete.forEach { session ->
                session.audioFile.delete()
                session.pitchFile.delete()
            }

            // Clean up SharedPreferences so deleted session IDs don't pile up. The counter (NEXT_NUMBER_KEY)
            // is left alone so numbers are never reused.
            listOf(NAMES_PREFS, STARS_PREFS, NOTES_PREFS, NUMBERS_PREFS).forEach { prefsName ->
                appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                    .apply { ids.forEach { remove(it) } }
                    .apply()
            }

            loadSessions(appContext, recordingsDir)
        }
    }

    /** Deletes leftover session files. Looks them up again now, and does nothing while a take is being recorded. */
    fun cleanUpIncomplete(context: Context, recordingsDir: File) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            if (!PitchEngineProvider.get().isRecording.value) {
                findOrphanFiles(recordingsDir, System.currentTimeMillis()).forEach { it.delete() }
            }
            loadSessions(appContext, recordingsDir)
        }
    }
}

@Suppress("LongMethod")
@Composable
fun RecordingsScreen(
    onNavigateUp: () -> Unit,
    onSessionSelected: (String) -> Unit,
    viewModel: RecordingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val recordingsDir = remember { File(context.filesDir, "recordings") }
    val sessions by viewModel.sessions.collectAsState()
    val visible by viewModel.visibleSessions.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val query by viewModel.query.collectAsState()
    val starredOnly by viewModel.starredOnly.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val incomplete by viewModel.incomplete.collectAsState()
    val selected = remember(visible, selectedIds) { visible.filter { it.sessionId in selectedIds } }

    var sessionToRename by remember { mutableStateOf<RecordingSession?>(null) }
    var sessionsToDelete by remember { mutableStateOf<List<RecordingSession>>(emptyList()) }
    var confirmCleanUp by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var renameText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val closeSearch = {
        searchOpen = false
        viewModel.setQuery("")
    }

    LaunchedEffect(Unit) {
        viewModel.loadSessions(context, recordingsDir)
    }

    // Back leaves selection first, then closes the search, before leaving the screen
    BackHandler(enabled = selectionMode) { viewModel.exitSelection() }
    BackHandler(enabled = searchOpen && !selectionMode, onBack = closeSearch)

    // --- Edit Details Dialog (name + note) ---
    sessionToRename?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Edit Recording") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text("e.g., Chorus Take 1") }
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("Note") },
                        placeholder = { Text("e.g., Watch the high notes") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveDetails(context, session.sessionId, renameText, noteText, recordingsDir)
                    sessionToRename = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) { Text("Cancel") }
            }
        )
    }

    // --- Delete Confirmation Dialog (one recording from its card, or the whole selection) ---
    if (sessionsToDelete.isNotEmpty()) {
        DeleteRecordingsDialog(
            sessions = sessionsToDelete,
            onConfirm = {
                val doomed = sessionsToDelete
                viewModel.deleteSessions(context, doomed, recordingsDir)
                viewModel.exitSelection()
                sessionsToDelete = emptyList()
                if (doomed.size > 1) {
                    Toast.makeText(context, "Deleted ${doomed.size} recordings", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { sessionsToDelete = emptyList() }
        )
    }

    // --- Clean up leftover files from recordings that never finished saving ---
    if (confirmCleanUp) {
        CleanUpDialog(
            incomplete = incomplete,
            onConfirm = {
                viewModel.cleanUpIncomplete(context, recordingsDir)
                confirmCleanUp = false
            },
            onDismiss = { confirmCleanUp = false }
        )
    }

    Scaffold(
        topBar = {
            when {
                selectionMode -> SelectionTopBar(
                    selectedCount = selected.size,
                    sizeBytes = selected.sumOf { it.sizeBytes },
                    willStar = starTargetFor(selected),
                    actions = SelectionActions(
                        onClose = viewModel::exitSelection,
                        onSelectAll = viewModel::selectAllVisible,
                        onToggleStar = {
                            viewModel.setStarred(context, selectedIds, starTargetFor(selected), recordingsDir)
                        },
                        onShare = {
                            shareFiles(context, selected.map { it.audioFile }, "audio/wav", "Share recordings")
                        },
                        onDelete = { sessionsToDelete = selected }
                    )
                )
                searchOpen -> SearchTopBar(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    onClose = closeSearch
                )
                else -> RecordingsTopBar(
                    sort = sort,
                    onNavigateUp = onNavigateUp,
                    onSearch = { searchOpen = true },
                    onSortChange = viewModel::setSort,
                    onSelect = { viewModel.enterSelection() }
                )
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (!selectionMode) {
                RecordingsHeader(
                    total = sessions.size,
                    shownCount = visible.size,
                    shownBytes = visible.sumOf { it.sizeBytes },
                    filtering = query.isNotBlank() || starredOnly,
                    starredOnly = starredOnly,
                    onStarredOnlyChange = viewModel::setStarredOnly,
                    incomplete = incomplete,
                    onCleanUp = { confirmCleanUp = true }
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    sessions.isEmpty() -> EmptyStateMessage(modifier = Modifier.align(Alignment.Center))
                    visible.isEmpty() -> NoMatchMessage(
                        onClearFilters = viewModel::clearFilters,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visible, key = { it.sessionId }) { session ->
                            RecordingItem(
                                session = session,
                                selectionMode = selectionMode,
                                selected = session.sessionId in selectedIds,
                                onClick = {
                                    if (selectionMode) {
                                        viewModel.toggleSelected(session.sessionId)
                                    } else {
                                        onSessionSelected(session.sessionId)
                                    }
                                },
                                onLongClick = { viewModel.enterSelection(session.sessionId) },
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
                                    noteText = session.note ?: ""
                                    sessionToRename = session
                                },
                                onShareAudio = {
                                    shareFile(context, session.audioFile, "audio/wav", "Share recording")
                                },
                                onShareCsv = {
                                    scope.launch {
                                        val csv = withContext(Dispatchers.IO) { writeSessionCsv(context, session) }
                                        shareFile(context, csv, "text/csv", "Share pitch data")
                                    }
                                },
                                onDelete = { sessionsToDelete = listOf(session) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList")
@Composable
fun RecordingItem(
    session: RecordingSession,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    onRename: () -> Unit,
    onShareAudio: () -> Unit,
    onShareCsv: () -> Unit,
    onDelete: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    // The size only shows while selecting, where the checkbox leaves more room than the four buttons do
    val sizeSuffix = if (selectionMode) " • ${formatSize(LocalContext.current, session.sizeBytes)}" else ""
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
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
                        text = "${session.formattedDate} • ${session.formattedDuration}$sizeSuffix",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!session.note.isNullOrBlank()) {
                        Text(
                            text = session.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (selectionMode) {
                // The whole card toggles on tap, so the checkbox only shows the state
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                RecordingActions(
                    isStarred = session.isStarred,
                    onToggleStar = onToggleStar,
                    onRename = onRename,
                    onShareAudio = onShareAudio,
                    onShareCsv = onShareCsv,
                    onDelete = onDelete
                )
            }
        }
    }
}

/** Star, edit, share and delete buttons for one recording card. */
@Composable
private fun RecordingActions(
    isStarred: Boolean,
    onToggleStar: () -> Unit,
    onRename: () -> Unit,
    onShareAudio: () -> Unit,
    onShareCsv: () -> Unit,
    onDelete: () -> Unit
) {
    var shareMenuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleStar) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = if (isStarred) "Unstar Session" else "Star Session",
                // Primary colour if starred, otherwise a faded surface-variant colour
                tint = if (isStarred) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                }
            )
        }

        IconButton(onClick = onRename) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Session",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Box {
            IconButton(onClick = { shareMenuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share Session",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            DropdownMenu(expanded = shareMenuOpen, onDismissRequest = { shareMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Share audio (WAV)") },
                    onClick = {
                        shareMenuOpen = false
                        onShareAudio()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share pitch data (CSV)") },
                    onClick = {
                        shareMenuOpen = false
                        onShareCsv()
                    }
                )
            }
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

/** Opens the system share sheet for [file], served through the app's FileProvider. */
private fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
    shareFiles(context, listOf(file), mimeType, chooserTitle)
}

/** Opens the system share sheet for one or more files, served through the app's FileProvider. */
private fun shareFiles(context: Context, files: List<File>, mimeType: String, chooserTitle: String) {
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    send.apply {
        type = mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Without this the read grant could cover only the first file of a multi-file share
        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
    }
    try {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    } catch (e: ActivityNotFoundException) {
        android.util.Log.w("RecordingsScreen", "No app can handle sharing", e)
        Toast.makeText(context, "No app available to share with", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Writes a session's pitch data to a CSV in the cache directory (columns: type, timestampMs,
 * frequencyHz, midiNote, noteName; type is "pitch" or "stable"). Blocking, so call it off the main thread.
 */
private fun writeSessionCsv(context: Context, session: RecordingSession): File {
    val (pitchPoints, stableNotes) = parsePitchFile(session.pitchFile)
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val safeName = session.displayTitle.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    val csv = File(exportDir, "${safeName}_pitch.csv")

    csv.bufferedWriter().use { out ->
        out.write("type,timestampMs,frequencyHz,midiNote,noteName\n")
        fun write(type: String, points: List<RecordedPitchPoint>) {
            for (p in points) {
                val freq = String.format(Locale.US, "%.2f", p.frequencyHz)
                out.write("$type,${p.timestampMs},$freq,${p.midiNote},${midiToNoteName(p.midiNote)}\n")
            }
        }
        write("pitch", pitchPoints)
        write("stable", stableNotes)
    }
    return csv
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
