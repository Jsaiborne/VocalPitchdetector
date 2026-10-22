@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_EXERCISE_BPM = 40f
private const val MAX_EXERCISE_BPM = 200f
private const val BPM_STEP = 5f

/** Choose the range, direction, guide mode and tempo before starting an exercise. */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun ExerciseSetup(
    exercise: Exercise,
    settings: ExerciseSettings,
    hasRangeTest: Boolean,
    onSettingsChange: (ExerciseSettings) -> Unit,
    onBpmChange: (Float) -> Unit,
    onOpenRangeTest: () -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    // Laid out from time 0 just to count the repetitions and the length
    val preview = remember(exercise, settings) {
        buildSchedule(exercise, settings.lowMidi, settings.highMidi, settings.direction, settings.mode, startMs = 0L)
    }
    val headphones = remember(settings.mode) { hasHeadphones(context) }
    val rangeTooNarrow = settings.highMidi - settings.lowMidi < exercise.span

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SetupCard(title = "Pattern") {
            PatternPreview(exercise = exercise, modifier = Modifier.height(48.dp))
            Text(
                "${exercise.notes.count { !it.isRest }} notes · spans ${spanText(exercise.span)}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SetupCard(title = "Your range") {
            NoteStepper(
                label = "Lowest note",
                midi = settings.lowMidi,
                range = EXERCISE_MIN_MIDI..settings.highMidi,
                onChange = { onSettingsChange(settings.copy(lowMidi = it)) }
            )
            NoteStepper(
                label = "Highest note",
                midi = settings.highMidi,
                range = settings.lowMidi..EXERCISE_MAX_MIDI,
                onChange = { onSettingsChange(settings.copy(highMidi = it)) }
            )
            Text(
                if (rangeTooNarrow) {
                    "This pattern needs more room than your range, so it's sung once in the middle."
                } else {
                    "${preview.reps.size} repetitions, stepping a semitone each time · " +
                        "about ${durationText(preview.endMs - preview.startMs)}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (!hasRangeTest) {
                TextButton(onClick = onOpenRangeTest) { Text("Find your range with the vocal range test") }
            }
        }

        SetupCard(title = "Direction") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((direction, label) in DIRECTION_LABELS) {
                    FilterChip(
                        selected = settings.direction == direction,
                        onClick = { onSettingsChange(settings.copy(direction = direction)) },
                        label = { Text(label) }
                    )
                }
            }
        }

        SetupCard(title = "How to practise") {
            ModeOption(
                title = "Listen, then sing",
                description = "The piano plays the pattern, then you sing it back. Works through the speaker.",
                selected = settings.mode == GuideMode.CALL_AND_RESPONSE,
                onSelect = { onSettingsChange(settings.copy(mode = GuideMode.CALL_AND_RESPONSE)) }
            )
            ModeOption(
                title = "Sing along",
                description = "The piano plays while you sing. Use headphones, or the mic hears the piano too.",
                selected = settings.mode == GuideMode.SING_ALONG,
                onSelect = { onSettingsChange(settings.copy(mode = GuideMode.SING_ALONG)) }
            )
            if (settings.mode == GuideMode.SING_ALONG && !headphones) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "  No headphones found. Plug some in for a fair score.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        SetupCard(title = "Tempo & scoring") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { onBpmChange((exercise.bpm - BPM_STEP).coerceAtLeast(MIN_EXERCISE_BPM)) },
                    enabled = exercise.bpm > MIN_EXERCISE_BPM
                ) { Icon(Icons.Filled.Remove, contentDescription = "Slower") }
                Text("${exercise.bpm.roundToInt()} BPM", fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { onBpmChange((exercise.bpm + BPM_STEP).coerceAtMost(MAX_EXERCISE_BPM)) },
                    enabled = exercise.bpm < MAX_EXERCISE_BPM
                ) { Icon(Icons.Filled.Add, contentDescription = "Faster") }
            }
            SwitchRow(
                label = "Strict scoring (within 25 cents)",
                checked = settings.strict,
                onCheckedChange = { onSettingsChange(settings.copy(strict = it)) }
            )
        }

        Button(onClick = onStart, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Text("Start")
        }
    }
}

private val DIRECTION_LABELS = listOf(
    Direction.UP to "Up",
    Direction.DOWN to "Down",
    Direction.UP_THEN_DOWN to "Up & down"
)

@Composable
private fun SetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun NoteStepper(label: String, midi: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(midi - 1) }, enabled = midi - 1 in range) {
            Icon(Icons.Filled.Remove, contentDescription = "$label down a semitone")
        }
        Text(
            midiToDisplayName(midi),
            modifier = Modifier.widthIn(min = 48.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = { onChange(midi + 1) }, enabled = midi + 1 in range) {
            Icon(Icons.Filled.Add, contentDescription = "$label up a semitone")
        }
    }
}

@Composable
private fun ModeOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun spanText(semitones: Int): String = when (semitones) {
    0 -> "a single note"
    12 -> "an octave"
    else -> "$semitones semitones"
}

/** Milliseconds as m:ss. */
internal fun durationText(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
