@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExerciseStep { LIBRARY, EDIT, SETUP, RUN, RESULT }

/**
 * Vocal exercises: pick one (or draw your own), set it up, sing it against target bars, then see
 * the score. Shown instead of the main screen while open; the pitch engine and mic keep running
 * underneath.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun ExerciseScreen(
    engine: PitchEngine,
    isLandscape: Boolean,
    keyWidthDp: Float,
    graphColors: GraphColors,
    useSamplePlayer: Boolean,
    onOpenRangeTest: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE) }
    val savedRange = remember { readSavedRange(context) }
    val store = remember { ExerciseStore(File(context.filesDir, EXERCISES_DIR)) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var step by remember { mutableStateOf(ExerciseStep.LIBRARY) }
    var exercise by remember { mutableStateOf<Exercise?>(null) }
    var settings by remember { mutableStateOf(readExerciseSettings(prefs, savedRange)) }
    var bpm by remember { mutableFloatStateOf(100f) }
    var outcome by remember { mutableStateOf<RunOutcome?>(null) }
    var newBest by remember { mutableStateOf(false) }
    // Bumped on every start, so "Sing it again" builds a fresh schedule
    var runKey by remember { mutableIntStateOf(0) }

    // The user's own exercises; null until they've been read from disk
    var mine by remember { mutableStateOf<List<SavedExercise>?>(null) }
    var draft by remember { mutableStateOf<Exercise?>(null) }
    var draftIsNew by remember { mutableStateOf(false) }
    // Where the editor goes back to: the list, or the setup of the exercise being edited
    var editReturn by remember { mutableStateOf(ExerciseStep.LIBRARY) }
    var renaming by remember { mutableStateOf<Exercise?>(null) }

    LaunchedEffect(store) { mine = withContext(Dispatchers.IO) { store.loadAll() } }

    // Guide notes sound best as piano samples; load them for the exercises even if the user's
    // piano uses the synthesised tone, and release them again on the way out
    DisposableEffect(useSamplePlayer) {
        if (!useSamplePlayer) SamplePlayer.init(context.applicationContext)
        onDispose { if (!useSamplePlayer) SamplePlayer.release() }
    }

    // Writes in the background; the list (and [onSaved]) only follow once the file is safely down
    fun persist(updated: Exercise, onSaved: (Exercise) -> Unit = {}) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    store.save(updated)
                } catch (ignored: IOException) {
                    null
                }
            }
            if (saved == null) {
                snackbar.showSnackbar("Couldn't save \"${updated.name}\"")
            } else {
                mine = listOf(saved) + mine.orEmpty().filter { it.exercise.id != updated.id }
                onSaved(saved.exercise)
            }
        }
    }

    fun delete(target: Exercise) {
        mine = mine?.filter { it.exercise.id != target.id }
        scope.launch {
            withContext(Dispatchers.IO) { store.delete(target.id) }
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                "Deleted \"${target.name}\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) persist(target)
        }
    }

    fun openEditor(target: Exercise, isNew: Boolean, returnTo: ExerciseStep) {
        draft = target
        draftIsNew = isNew
        editReturn = returnTo
        step = ExerciseStep.EDIT
    }

    val libraryActions = LibraryActions(
        onSelect = {
            exercise = it
            bpm = it.bpm
            step = ExerciseStep.SETUP
        },
        onNew = {
            val blank = Exercise(id = newExerciseId(), name = "My exercise", notes = emptyList())
            openEditor(blank, true, ExerciseStep.LIBRARY)
        },
        onEdit = { openEditor(it, false, ExerciseStep.LIBRARY) },
        onRename = { renaming = it },
        onDuplicate = {
            val copy = it.copy(id = newExerciseId(), name = copyName(it.name), builtIn = false)
            // A built-in is copied straight into the editor; a copy of your own is saved as it is
            if (it.builtIn) openEditor(copy, true, ExerciseStep.LIBRARY) else persist(copy)
        },
        onDelete = { delete(it) }
    )

    val current = exercise
    val editing = draft
    // Back steps out one level at a time; from the list it closes the exercises
    BackHandler {
        when (step) {
            ExerciseStep.LIBRARY -> onExit()
            ExerciseStep.EDIT -> step = editReturn
            ExerciseStep.SETUP, ExerciseStep.RESULT -> step = ExerciseStep.LIBRARY
            ExerciseStep.RUN -> step = ExerciseStep.SETUP
        }
    }

    // A run uses nearly the whole screen so the piano and graph get as much room as possible
    val compact = step == ExerciseStep.RUN || step == ExerciseStep.EDIT
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 6.dp else 16.dp, vertical = if (compact) 2.dp else 8.dp)
        ) {
            if (step == ExerciseStep.EDIT && editing != null) {
                ExerciseEditor(
                    exercise = editing,
                    isNew = draftIsNew,
                    isLandscape = isLandscape,
                    onSave = { updated ->
                        val notesChanged = updated.notes != editing.notes.dropLastWhile { it.isRest }
                        persist(updated) { saved ->
                            // An old best was sung to different notes, so it no longer counts
                            if (notesChanged && !draftIsNew) clearBestScore(prefs, saved.id)
                            if (editReturn == ExerciseStep.SETUP) {
                                exercise = saved
                                bpm = saved.bpm
                            }
                            draft = null
                            step = editReturn
                        }
                    },
                    onClose = {
                        draft = null
                        step = editReturn
                    }
                )
            } else if (step == ExerciseStep.LIBRARY || step == ExerciseStep.EDIT || current == null) {
                ExerciseHeader(title = "Exercises", subtitle = "Warm-ups that follow your range", onClose = onExit)
                ExerciseLibrary(
                    mine = mine
                        ?.sortedByDescending { maxOf(it.savedAtMs, readLastPlayed(prefs, it.exercise.id)) }
                        ?.map { it.exercise },
                    builtIn = BUILT_IN_EXERCISES,
                    bestScore = { readBestScore(prefs, it.id) },
                    actions = libraryActions
                )
            } else {
                when (step) {
                    ExerciseStep.SETUP -> {
                        ExerciseHeader(
                            title = current.name,
                            subtitle = current.description,
                            onBack = { step = ExerciseStep.LIBRARY }
                        ) {
                            if (!current.builtIn) {
                                IconButton(onClick = { openEditor(current, false, ExerciseStep.SETUP) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${current.name}")
                                }
                            }
                        }
                        ExerciseSetup(
                            exercise = current.copy(bpm = bpm),
                            settings = settings,
                            hasRangeTest = savedRange != null,
                            onSettingsChange = { settings = it },
                            onBpmChange = { bpm = it },
                            onOpenRangeTest = onOpenRangeTest,
                            onStart = {
                                saveExerciseSettings(prefs, settings)
                                saveLastPlayed(prefs, current.id)
                                runKey++
                                step = ExerciseStep.RUN
                            }
                        )
                    }
                    ExerciseStep.RUN -> {
                        ExerciseRun(
                            exercise = current.copy(bpm = bpm),
                            settings = settings,
                            engine = engine,
                            runKey = runKey,
                            isLandscape = isLandscape,
                            keyWidthDp = keyWidthDp,
                            graphColors = graphColors,
                            onStop = { step = ExerciseStep.SETUP },
                            onFinished = { finished ->
                                outcome = finished
                                // A run stopped early could score high on a single repetition, so only full
                                // runs set a best
                                newBest = finished.completed && finished.result.frames > 0 &&
                                    saveBestScore(prefs, current.id, finished.result.percent)
                                step = ExerciseStep.RESULT
                            }
                        )
                    }
                    else -> {
                        ExerciseHeader(title = current.name, subtitle = "Your result", onClose = onExit)
                        outcome?.let {
                            ExerciseResultView(
                                outcome = it,
                                newBest = newBest,
                                onReplay = {
                                    runKey++
                                    step = ExerciseStep.RUN
                                },
                                onChangeSettings = { step = ExerciseStep.SETUP },
                                onAllExercises = { step = ExerciseStep.LIBRARY }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    renaming?.let { target ->
        ExerciseDetailsDialog(
            title = "Rename",
            initialName = target.name,
            initialDescription = target.description,
            onConfirm = { name, description ->
                persist(target.copy(name = name, description = description))
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
}

/** Title row with a back arrow ([onBack]) or a close button ([onClose]), and any [actions] before it. */
@Composable
internal fun ExerciseHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        actions()
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close exercises")
            }
        }
    }
}

/** The pattern's shape: one bar per note, placed by length and by height above the root. */
@Composable
internal fun PatternPreview(exercise: Exercise, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.fillMaxWidth()) {
        val totalBeats = exercise.totalBeats
        if (totalBeats <= 0f) return@Canvas
        val rows = (exercise.span + 1).toFloat()
        val rowHeight = size.height / rows
        val barHeight = rowHeight.coerceIn(2f, 8f)
        val gap = 2f
        var beat = 0f
        for (note in exercise.notes) {
            val offset = note.offset
            if (offset != null) {
                val x = size.width * beat / totalBeats
                val w = (size.width * note.beats / totalBeats - gap).coerceAtLeast(2f)
                val y = size.height - (offset - exercise.minOffset + 0.5f) * rowHeight - barHeight / 2f
                drawRoundRect(color, Offset(x, y), Size(w, barHeight), CornerRadius(barHeight / 2f))
            }
            beat += note.beats
        }
    }
}
