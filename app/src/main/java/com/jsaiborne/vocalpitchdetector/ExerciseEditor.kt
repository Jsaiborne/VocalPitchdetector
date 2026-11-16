@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private const val UNDO_LIMIT = 100
private const val TAP_NOTE_MS = 400
private const val BREATH_STEP = 0.5f
private const val TOOL_BUTTON_DP = 36
private const val STEPPER_BUTTON_DP = 36

private val LENGTH_LABELS = listOf("¼", "½", "1", "2", "3", "4")

/** What the editor's toolbar can do right now. */
private data class EditorTools(
    val activeLength: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val canDelete: Boolean,
    val canPlay: Boolean,
    val playing: Boolean
)

/** What the toolbar's buttons do. */
private data class EditorToolActions(
    val onLength: (Int) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onDelete: () -> Unit
)

/** The notes on the grid as a flat list of numbers (each note's start, length and offset), to save with the screen. */
private val ROLL_SAVER = Saver<List<RollNote>, IntArray>(
    save = { roll -> roll.flatMap { listOf(it.start, it.length, it.offset) }.toIntArray() },
    restore = { numbers ->
        numbers.toList().chunked(3).map { (start, length, offset) -> RollNote(start, length, offset) }
    }
)

/**
 * Draws an exercise on a piano roll rooted on C4; the player transposes it to the singer's range.
 * Nothing is written until Save, and leaving with unsaved changes asks first.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun ExerciseEditor(
    exercise: Exercise,
    isNew: Boolean,
    isLandscape: Boolean,
    onSave: (Exercise) -> Unit,
    onClose: () -> Unit
) {
    val originalRoll = remember(exercise) { rollNotesOf(exercise.notes) }
    // What the exercise looks like once it has been through the grid, to tell real edits apart
    val unchanged = remember(exercise) { exercise.copy(notes = exerciseNotesOf(originalRoll), builtIn = false) }

    // What has been changed so far is saved with the activity, so unsaved work survives Android
    // recreating the screen (a theme or font-size change, or the app being closed in the background).
    // The undo history isn't kept
    var name by rememberSaveable { mutableStateOf(exercise.name) }
    var description by rememberSaveable { mutableStateOf(exercise.description) }
    var bpm by rememberSaveable { mutableFloatStateOf(exercise.bpm) }
    var breathBeats by rememberSaveable { mutableFloatStateOf(exercise.breathBeats) }
    var rootMidi by rememberSaveable { mutableIntStateOf(exercise.editorRootMidi) }
    var roll by rememberSaveable(stateSaver = ROLL_SAVER) { mutableStateOf(originalRoll) }
    val undo = remember { mutableStateListOf<List<RollNote>>() }
    val redo = remember { mutableStateListOf<List<RollNote>>() }
    var selected by remember { mutableStateOf<RollNote?>(null) }
    var pen by rememberSaveable { mutableIntStateOf(STEPS_PER_BEAT) }
    // The notes as they were when the current drag began
    var dragBase by remember { mutableStateOf<List<RollNote>?>(null) }
    var playing by remember { mutableStateOf(false) }
    var playhead by remember { mutableStateOf<Float?>(null) }
    var editingDetails by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val edited = exercise.copy(
        name = name.trim(),
        description = description.trim(),
        notes = exerciseNotesOf(roll),
        bpm = bpm,
        breathBeats = breathBeats,
        builtIn = false,
        editorRootMidi = rootMidi
    )
    val dirty = edited != unchanged
    val canSave = roll.isNotEmpty() && edited.name.isNotEmpty() && (dirty || isNew)

    // A new edit can be undone, and leaves nothing to redo
    fun keepForUndo(previous: List<RollNote>) {
        undo += previous
        if (undo.size > UNDO_LIMIT) undo.removeAt(0)
        redo.clear()
    }

    fun commit(next: List<RollNote>, select: RollNote?) {
        if (next != roll) {
            keepForUndo(roll)
            roll = next
        }
        selected = select
    }

    fun stopPreview() {
        playing = false
        playhead = null
        ToneGenerator.stop()
    }

    val leave: () -> Unit = {
        stopPreview()
        if (dirty) confirmDiscard = true else onClose()
    }
    BackHandler(onBack = leave)
    // The preview follows the screen's frames, which stop while the app is away; left running, it
    // would play every note it had missed at once on the way back
    OnStopEffect { stopPreview() }

    // Plays the pattern once at C4, with a line sweeping across the grid
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val notes = roll.sortedBy { it.start }
        val stepMs = 60_000f / bpm / STEPS_PER_BEAT
        val totalMs = (notes.endStep * stepMs).toLong()
        val startMs = System.currentTimeMillis()
        var next = 0
        var elapsed = 0L
        while (elapsed < totalMs) {
            while (next < notes.size && notes[next].start * stepMs <= elapsed) {
                val note = notes[next]
                playGuideNote(rootMidi + note.offset, (note.length * stepMs).toInt())
                next++
            }
            playhead = elapsed / stepMs
            withFrameMillis { }
            elapsed = System.currentTimeMillis() - startMs
        }
        playhead = null
        playing = false
    }

    val rollActions = PianoRollActions(
        onTapCell = { step, offset ->
            val hit = roll.noteAt(step, offset)
            if (hit != null) {
                selected = if (hit == selected) null else hit
            } else {
                val start = roll.startForTap(step, minOf(pen, STEPS_PER_BEAT))
                val note = roll.placing(start, offset, pen)
                commit(roll.withNote(note), note)
            }
            playGuideNote(rootMidi + offset, TAP_NOTE_MS, ring = true)
        },
        onDragStart = { note ->
            dragBase = roll
            selected = note
        },
        onMove = { note, start, offset ->
            val (next, moved) = (dragBase ?: roll).moving(note, start, offset)
            val newRow = moved.offset != selected?.offset
            if (newRow) playGuideNote(rootMidi + moved.offset, TAP_NOTE_MS, ring = true)
            roll = next
            selected = moved
        },
        onResize = { note, length ->
            val (next, resized) = (dragBase ?: roll).resizing(note, length)
            roll = next
            selected = resized
        },
        onDragEnd = {
            val base = dragBase
            dragBase = null
            if (base != null && base != roll) keepForUndo(base)
        }
    )

    val tools = EditorTools(
        activeLength = selected?.length ?: pen,
        canUndo = undo.isNotEmpty(),
        canRedo = redo.isNotEmpty(),
        canDelete = selected != null,
        canPlay = roll.isNotEmpty(),
        playing = playing
    )
    val togglePlay: () -> Unit = { if (playing) stopPreview() else playing = true }
    val timing: @Composable (Modifier) -> Unit = { timingModifier ->
        TimingControls(
            bpm = bpm,
            breathBeats = breathBeats,
            onBpmChange = { bpm = it },
            onBreathChange = { breathBeats = it },
            rootMidi = rootMidi,
            onRootChange = {
                rootMidi = it
                playGuideNote(it, TAP_NOTE_MS, ring = true)
            },
            modifier = timingModifier
        )
    }

    // In landscape the timings share the toolbar row, taking whatever room the tools leave
    val landscapeTiming: @Composable RowScope.() -> Unit = { timing(Modifier.weight(1f)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { editingDetails = true }
            ) {
                Text(
                    edited.name.ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${roll.size} notes · ${beatsText(edited.totalBeats)} beats · tap to rename",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = togglePlay, enabled = tools.canPlay || tools.playing) {
                if (tools.playing) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play the pattern")
                }
            }
            Button(
                onClick = {
                    stopPreview()
                    onSave(edited)
                },
                enabled = canSave
            ) { Text("Save") }
        }

        EditorToolbar(
            tools = tools,
            actions = EditorToolActions(
                onLength = { length ->
                    pen = length
                    selected?.let {
                        val (next, resized) = roll.resizing(it, length)
                        commit(next, resized)
                    }
                },
                onUndo = {
                    if (undo.isNotEmpty()) {
                        redo += roll
                        roll = undo.removeAt(undo.lastIndex)
                    }
                    selected = null
                },
                onRedo = {
                    if (redo.isNotEmpty()) {
                        undo += roll
                        roll = redo.removeAt(redo.lastIndex)
                    }
                    selected = null
                },
                onDelete = { selected?.let { commit(roll - it, null) } }
            ),
            trailing = landscapeTiming.takeIf { isLandscape }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PianoRoll(
                notes = roll,
                selected = selected,
                rootMidi = rootMidi,
                playheadStep = playhead,
                actions = rollActions
            )
            if (roll.isEmpty()) {
                Text(
                    "Tap the grid to add notes.\nDrag a note to move it; drag its end to change its length.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 48.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (!isLandscape) timing(Modifier.fillMaxWidth())
    }

    if (editingDetails) {
        ExerciseDetailsDialog(
            title = "Name & description",
            initialName = name,
            initialDescription = description,
            onConfirm = { newName, newDescription ->
                name = newName
                description = newDescription
                editingDetails = false
            },
            onDismiss = { editingDetails = false }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to \"${edited.name.ifEmpty { "Untitled" }}\" haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            }
        )
    }
}

/**
 * Note lengths, then undo, redo and delete; [trailing] fills whatever room is left on the end. Kept
 * tight so that all of it fits across a narrow phone held upright.
 */
@Composable
private fun EditorToolbar(
    tools: EditorTools,
    actions: EditorToolActions,
    trailing: (@Composable RowScope.() -> Unit)?
) {
    val buttonModifier = Modifier.size(TOOL_BUTTON_DP.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for ((length, label) in NOTE_LENGTH_STEPS.zip(LENGTH_LABELS)) {
            LengthChip(
                label = label,
                description = "$label beat",
                selected = tools.activeLength == length,
                onClick = { actions.onLength(length) }
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = actions.onUndo, enabled = tools.canUndo, modifier = buttonModifier) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = actions.onRedo, enabled = tools.canRedo, modifier = buttonModifier) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        IconButton(onClick = actions.onDelete, enabled = tools.canDelete, modifier = buttonModifier) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete note")
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun LengthChip(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier
            .size(34.dp)
            .semantics {
                this.selected = selected
                contentDescription = description
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Tempo, the breath between repetitions, and the key the pattern is written in, all saved with
 * the exercise. Each takes a third of [modifier]'s width, so the + buttons always stay on screen.
 */
@Suppress("LongParameterList")
@Composable
private fun TimingControls(
    bpm: Float,
    breathBeats: Float,
    onBpmChange: (Float) -> Unit,
    onBreathChange: (Float) -> Unit,
    rootMidi: Int,
    onRootChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        MiniStepper(
            label = "Tempo",
            value = "${bpm.roundToInt()}",
            onMinus = { onBpmChange((bpm - BPM_STEP).coerceAtLeast(MIN_EXERCISE_BPM)) }
                .takeIf { bpm > MIN_EXERCISE_BPM },
            onPlus = { onBpmChange((bpm + BPM_STEP).coerceAtMost(MAX_EXERCISE_BPM)) }
                .takeIf { bpm < MAX_EXERCISE_BPM },
            modifier = Modifier.weight(1f)
        )
        MiniStepper(
            label = "Breath",
            value = beatsText(breathBeats),
            onMinus = { onBreathChange((breathBeats - BREATH_STEP).coerceAtLeast(0f)) }
                .takeIf { breathBeats > 0f },
            onPlus = { onBreathChange((breathBeats + BREATH_STEP).coerceAtMost(MAX_BREATH_BEATS)) }
                .takeIf { breathBeats < MAX_BREATH_BEATS },
            modifier = Modifier.weight(1f)
        )
        MiniStepper(
            label = "Key",
            value = midiToDisplayName(rootMidi),
            onMinus = { onRootChange(rootMidi - 1) }.takeIf { rootMidi > EDITOR_ROOT_RANGE.first },
            onPlus = { onRootChange(rootMidi + 1) }.takeIf { rootMidi < EDITOR_ROOT_RANGE.last },
            modifier = Modifier.weight(1f)
        )
    }
}

/** − and + around a small label and its value; a null action greys its button out. */
@Composable
private fun MiniStepper(
    label: String,
    value: String,
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onMinus?.invoke() },
            enabled = onMinus != null,
            modifier = Modifier.size(STEPPER_BUTTON_DP.dp)
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Less $label")
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = { onPlus?.invoke() },
            enabled = onPlus != null,
            modifier = Modifier.size(STEPPER_BUTTON_DP.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "More $label")
        }
    }
}

/** 2 → "2", 1.5 → "1.5". */
private fun beatsText(beats: Float): String =
    if (beats % 1f == 0f) beats.toInt().toString() else String.format(Locale.ROOT, "%.2f", beats).trimEnd('0')
