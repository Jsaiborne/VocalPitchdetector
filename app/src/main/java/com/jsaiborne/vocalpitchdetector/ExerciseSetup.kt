@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

internal const val BPM_STEP = 5f

private const val STEPPER_BUTTON_SIZE_DP = 36
private val OPTION_SHAPE = RoundedCornerShape(12.dp)

/** One end of a stepper: what its button is called, and what it does (null greys it out). */
private class StepButton(val description: String, val onClick: (() -> Unit)?)

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

    // The settings scroll; Start stays put underneath them, always in reach
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = "Pattern", icon = Icons.Filled.MusicNote) {
                PatternPreview(exercise = exercise, modifier = Modifier.height(56.dp))
                Text(
                    "${exercise.notes.count { !it.isRest }} notes · spans ${spanText(exercise.span)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = "Your range", icon = Icons.Filled.Height) {
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!hasRangeTest) {
                    TextButton(onClick = onOpenRangeTest) { Text("Find your range with the vocal range test") }
                }
            }

            SectionCard(title = "Direction", icon = Icons.Filled.SwapVert) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((direction, label) in DIRECTION_LABELS) {
                        FilterChip(
                            selected = settings.direction == direction,
                            onClick = { onSettingsChange(settings.copy(direction = direction)) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = TINT_ALPHA),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            SectionCard(title = "How to practise", icon = Icons.Filled.RecordVoiceOver) {
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

            SectionCard(title = "Tempo & scoring", icon = Icons.Filled.Speed) {
                ValueStepper(
                    label = "Tempo",
                    value = "${exercise.bpm.roundToInt()} BPM",
                    minus = StepButton(
                        "Slower",
                        { onBpmChange((exercise.bpm - BPM_STEP).coerceAtLeast(MIN_EXERCISE_BPM)) }
                            .takeIf { exercise.bpm > MIN_EXERCISE_BPM }
                    ),
                    plus = StepButton(
                        "Faster",
                        { onBpmChange((exercise.bpm + BPM_STEP).coerceAtMost(MAX_EXERCISE_BPM)) }
                            .takeIf { exercise.bpm < MAX_EXERCISE_BPM }
                    )
                )
                SwitchRow(
                    label = "Strict scoring (within 25 cents)",
                    checked = settings.strict,
                    onCheckedChange = { onSettingsChange(settings.copy(strict = it)) }
                )
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 4.dp)
                .widthIn(max = EXERCISE_CONTENT_MAX_WIDTH_DP.dp)
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val DIRECTION_LABELS = listOf(
    Direction.UP to "Up",
    Direction.DOWN to "Down",
    Direction.UP_THEN_DOWN to "Up & down"
)

@Composable
private fun NoteStepper(label: String, midi: Int, range: IntRange, onChange: (Int) -> Unit) {
    ValueStepper(
        label = label,
        value = midiToDisplayName(midi),
        minus = StepButton("$label down a semitone", { onChange(midi - 1) }.takeIf { midi - 1 in range }),
        plus = StepButton("$label up a semitone", { onChange(midi + 1) }.takeIf { midi + 1 in range })
    )
}

/** A label, then − and + either side of the value they change, so the three read as one control. */
@Composable
private fun ValueStepper(label: String, value: String, minus: StepButton, plus: StepButton) {
    val buttonColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = TINT_ALPHA),
        contentColor = MaterialTheme.colorScheme.primary
    )
    val buttonModifier = Modifier.size(STEPPER_BUTTON_SIZE_DP.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        FilledTonalIconButton(
            onClick = { minus.onClick?.invoke() },
            enabled = minus.onClick != null,
            colors = buttonColors,
            modifier = buttonModifier
        ) {
            Icon(Icons.Filled.Remove, contentDescription = minus.description, modifier = Modifier.size(20.dp))
        }
        Pill(value, modifier = Modifier.widthIn(min = 76.dp), style = MaterialTheme.typography.titleSmall)
        FilledTonalIconButton(
            onClick = { plus.onClick?.invoke() },
            enabled = plus.onClick != null,
            colors = buttonColors,
            modifier = buttonModifier
        ) {
            Icon(Icons.Filled.Add, contentDescription = plus.description, modifier = Modifier.size(20.dp))
        }
    }
}

/** One way to practise, as a tile; the chosen one is tinted and outlined in the theme colour. */
@Composable
private fun ModeOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = OPTION_SHAPE,
        color = if (selected) colors.primary.copy(alpha = TINT_ALPHA) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(OPTION_SHAPE)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
