package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Top bars, header and dialogs for the recordings list (RecordingsScreen.kt holds the list itself).

/** A file size the way the system shows it, e.g. "3.6 MB". */
internal fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun countLabel(count: Int, singular: String): String =
    if (count == 1) "1 $singular" else "$count ${singular}s"

/** What the buttons in the selection top bar do. */
internal data class SelectionActions(
    val onClose: () -> Unit,
    val onSelectAll: () -> Unit,
    val onToggleStar: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit
)

/** The normal top bar: back, search, sort and the Select button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsTopBar(
    sort: RecordingSort,
    onNavigateUp: () -> Unit,
    onSearch: () -> Unit,
    onSortChange: (RecordingSort) -> Unit,
    onSelect: () -> Unit
) {
    TopAppBar(
        title = { Text("Recordings") },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search recordings")
            }
            SortMenuButton(sort = sort, onSortChange = onSortChange)
            IconButton(onClick = onSelect) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Select recordings")
            }
        }
    )
}

@Composable
private fun SortMenuButton(sort: RecordingSort, onSortChange: (RecordingSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Sort, contentDescription = "Sort recordings")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in RecordingSort.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = {
                        if (option == sort) Icon(Icons.Default.Check, contentDescription = "Current sort")
                    },
                    onClick = {
                        open = false
                        onSortChange(option)
                    }
                )
            }
        }
    }
}

/** Replaces the normal top bar while searching; the text filters the list as you type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Search names and notes") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        }
    )
}

/**
 * The top bar while selecting: how many are ticked (and how much space they take), plus select all,
 * star/unstar, share and delete. [willStar] says whether the star button would star or unstar them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selectedCount: Int,
    sizeBytes: Long,
    willStar: Boolean,
    actions: SelectionActions
) {
    val context = LocalContext.current
    val hasSelection = selectedCount > 0
    TopAppBar(
        title = {
            Column {
                Text(
                    text = if (hasSelection) "$selectedCount selected" else "Select recordings",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasSelection) {
                    Text(
                        text = formatSize(context, sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Default.Close, contentDescription = "Exit selection")
            }
        },
        actions = {
            IconButton(onClick = actions.onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
            }
            IconButton(onClick = actions.onToggleStar, enabled = hasSelection) {
                Icon(
                    imageVector = if (willStar) Icons.Default.StarBorder else Icons.Default.Star,
                    contentDescription = if (willStar) "Star selected" else "Unstar selected"
                )
            }
            IconButton(onClick = actions.onShare, enabled = hasSelection) {
                Icon(Icons.Default.Share, contentDescription = "Share selected audio")
            }
            IconButton(onClick = actions.onDelete, enabled = hasSelection) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
            }
        }
    )
}

/**
 * Above the list: a "Starred" filter and a one-line summary of what's shown, plus (only when there are
 * leftover files from recordings that never finished saving) a row offering to clean them up.
 */
@Suppress("LongParameterList")
@Composable
internal fun RecordingsHeader(
    total: Int,
    shownCount: Int,
    shownBytes: Long,
    filtering: Boolean,
    starredOnly: Boolean,
    onStarredOnlyChange: (Boolean) -> Unit,
    incomplete: IncompleteFiles,
    onCleanUp: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        if (total > 0) {
            val summary = if (filtering) "$shownCount of $total" else countLabel(total, "recording")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FilterChip(
                    selected = starredOnly,
                    onClick = { onStarredOnlyChange(!starredOnly) },
                    label = { Text("Starred") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
                Text(
                    text = "$summary · ${formatSize(context, shownBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (incomplete.count > 0) {
            val leftoverSize = formatSize(context, incomplete.sizeBytes)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${countLabel(incomplete.count, "incomplete file")} ($leftoverSize)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onCleanUp) { Text("Clean up") }
            }
        }
    }
}

/** Confirms deleting one recording (from its card) or several (from the selection). */
@Composable
internal fun DeleteRecordingsDialog(
    sessions: List<RecordingSession>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val single = sessions.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single != null) "Delete Recording" else "Delete ${sessions.size} Recordings") },
        text = {
            Text(
                if (single != null) {
                    "Delete \"${single.displayTitle}\"? This action cannot be undone."
                } else {
                    val size = formatSize(context, sessions.sumOf { it.sizeBytes })
                    "This permanently removes the audio and pitch data of ${sessions.size} recordings ($size). " +
                        "This action cannot be undone."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Confirms removing the leftover files of recordings that never finished saving. */
@Composable
internal fun CleanUpDialog(incomplete: IncompleteFiles, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clean up incomplete files") },
        text = {
            Text(
                "These are leftovers from recordings that never finished saving, so they don't show in your " +
                    "list. Removing them frees ${formatSize(context, incomplete.sizeBytes)}. " +
                    "Your saved recordings are not affected."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Clean up") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Shown when the search or the starred filter hides every recording. */
@Composable
internal fun NoMatchMessage(onClearFilters: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No recordings match",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onClearFilters) { Text("Clear filters") }
    }
}
