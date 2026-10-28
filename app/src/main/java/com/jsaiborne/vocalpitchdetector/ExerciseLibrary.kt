@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MAX_NAME_LENGTH = 40
private const val MAX_DESCRIPTION_LENGTH = 120

/** What can be done from the exercise list. */
internal data class LibraryActions(
    val onSelect: (Exercise) -> Unit,
    val onNew: () -> Unit,
    val onEdit: (Exercise) -> Unit,
    val onRename: (Exercise) -> Unit,
    val onDuplicate: (Exercise) -> Unit,
    val onDelete: (Exercise) -> Unit
)

/**
 * The user's own exercises ([mine], null while they load), then the built-in warm-ups. A
 * built-in can't be changed, but its menu copies it into the editor.
 */
@Composable
internal fun ExerciseLibrary(
    mine: List<Exercise>?,
    builtIn: List<Exercise>,
    bestScore: (Exercise) -> Int?,
    actions: LibraryActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("My exercises", modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = actions.onNew) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New")
            }
        }
        when {
            mine == null -> Unit
            mine.isEmpty() -> Text(
                "Draw your own on the piano roll with New, or copy a warm-up below from its ⋮ menu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                for (exercise in mine) {
                    ExerciseCard(
                        exercise = exercise,
                        best = bestScore(exercise),
                        onClick = { actions.onSelect(exercise) },
                        menu = listOf(
                            "Edit" to { actions.onEdit(exercise) },
                            "Rename" to { actions.onRename(exercise) },
                            "Duplicate" to { actions.onDuplicate(exercise) },
                            "Delete" to { actions.onDelete(exercise) }
                        )
                    )
                }
            }
        }

        SectionTitle("Warm-ups", modifier = Modifier.padding(top = 8.dp))
        for (exercise in builtIn) {
            ExerciseCard(
                exercise = exercise,
                best = bestScore(exercise),
                onClick = { actions.onSelect(exercise) },
                menu = listOf("Copy & edit" to { actions.onDuplicate(exercise) })
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ExerciseCard(
    exercise: Exercise,
    best: Int?,
    onClick: () -> Unit,
    menu: List<Pair<String, () -> Unit>>
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (exercise.description.isNotEmpty()) {
                    Text(
                        exercise.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                PatternPreview(exercise = exercise, modifier = Modifier.widthIn(max = 220.dp).height(28.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    best?.let { "$it%" } ?: "–",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text("best", style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More for ${exercise.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for ((label, action) in menu) {
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                action()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Edits an exercise's name and description; OK stays greyed out until there's a name. */
@Composable
internal fun ExerciseDetailsDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(MAX_DESCRIPTION_LENGTH) },
                    label = { Text("Description (optional)") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), description.trim()) }, enabled = name.isNotBlank()) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** "Name (copy)", kept within the name length limit. */
internal fun copyName(name: String): String = name.take(MAX_NAME_LENGTH - 7) + " (copy)"
