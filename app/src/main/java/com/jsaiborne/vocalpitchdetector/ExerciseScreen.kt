@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

private enum class ExerciseStep { LIBRARY, EDIT, SETUP, RUN, RESULT }

/** A run can't be picked up again after the screen is recreated, so it comes back as its setup. */
private val STEP_SAVER = Saver<ExerciseStep, String>(
    save = { step ->
        val resumable = step != ExerciseStep.RUN && step != ExerciseStep.RESULT
        (if (resumable) step else ExerciseStep.SETUP).name
    },
    restore = { name -> ExerciseStep.entries.firstOrNull { it.name == name } ?: ExerciseStep.LIBRARY }
)

/**
 * Keeps an exercise (or none) across the screen being recreated: a built-in by its id, one of the
 * user's own, or a draft that isn't on disk yet, as its JSON.
 */
private val EXERCISE_SAVER = Saver<Exercise?, String>(
    save = { exercise ->
        when {
            exercise == null -> ""
            exercise.builtIn -> exercise.id
            else -> exercise.toJson().toString()
        }
    },
    restore = { saved -> restoreExercise(saved) }
)

private fun restoreExercise(saved: String): Exercise? = when {
    saved.isEmpty() -> null
    !saved.startsWith("{") -> BUILT_IN_EXERCISES.firstOrNull { it.id == saved }
    else -> try {
        // A draft may have no notes yet
        exerciseFromJson(JSONObject(saved), requireNotes = false)
    } catch (ignored: JSONException) {
        null
    }
}

/** Calls [onStop] (the latest one given) when the app is left or the screen turns off. */
@Composable
internal fun OnStopEffect(onStop: () -> Unit) {
    val latestOnStop by rememberUpdatedState(onStop)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestOnStop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

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
    // Opens the vocal range test from the setup of the exercise with this id. The test replaces this
    // screen, which afterwards comes back with that id as [resumeExerciseId]
    onOpenRangeTest: (exerciseId: String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    // The exercise whose setup to reopen straight away (see [onOpenRangeTest]); null starts at the list
    resumeExerciseId: String? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE) }
    val savedRange = remember { readSavedRange(context) }
    val store = remember { ExerciseStore(File(context.filesDir, EXERCISES_DIR)) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // A built-in can be reopened at once; one of the user's own has to wait until they're read from disk
    val resumed = remember { BUILT_IN_EXERCISES.firstOrNull { it.id == resumeExerciseId } }
    // Where the user is, and what they're working on, is saved with the activity: Android recreates
    // it when the theme or font size changes, or after closing the app in the background, and an
    // exercise being drawn mustn't be lost to that. (The editor keeps its own unsaved changes.)
    var step by rememberSaveable(stateSaver = STEP_SAVER) {
        mutableStateOf(if (resumed != null) ExerciseStep.SETUP else ExerciseStep.LIBRARY)
    }
    var exercise by rememberSaveable(stateSaver = EXERCISE_SAVER) { mutableStateOf(resumed) }
    var settings by remember { mutableStateOf(readExerciseSettings(prefs, savedRange)) }
    var bpm by rememberSaveable { mutableFloatStateOf(resumed?.bpm ?: DEFAULT_EXERCISE_BPM) }
    var outcome by remember { mutableStateOf<RunOutcome?>(null) }
    var newBest by remember { mutableStateOf(false) }
    // Bumped on every start, so "Sing it again" builds a fresh schedule
    var runKey by remember { mutableIntStateOf(0) }

    // The user's own exercises; null until they've been read from disk
    var mine by remember { mutableStateOf<List<SavedExercise>?>(null) }
    var draft by rememberSaveable(stateSaver = EXERCISE_SAVER) { mutableStateOf<Exercise?>(null) }
    var draftIsNew by rememberSaveable { mutableStateOf(false) }
    // Where the editor goes back to: the list, or the setup of the exercise being edited
    var editReturn by rememberSaveable(stateSaver = STEP_SAVER) { mutableStateOf(ExerciseStep.LIBRARY) }
    var renaming by remember { mutableStateOf<Exercise?>(null) }

    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) { store.loadAll() }
        mine = loaded
        // Back from the range test: reopen the setup of the user's own exercise it was opened from,
        // unless they've already gone somewhere else in the meantime
        val own = loaded.firstOrNull { it.exercise.id == resumeExerciseId }?.exercise
        if (own != null && exercise == null && step == ExerciseStep.LIBRARY) {
            exercise = own
            bpm = own.bpm
            step = ExerciseStep.SETUP
        }
    }

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
            var undone = false
            try {
                val result = snackbar.showSnackbar(
                    "Deleted \"${target.name}\"",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                undone = result == SnackbarResult.ActionPerformed
            } finally {
                // Gone for good (also when the screen is left with the message still up), so its
                // best score and play history go too; Undo keeps them
                if (!undone) clearBestScore(prefs, target.id, lastPlayedToo = true)
            }
            if (undone) persist(target)
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
                            onOpenRangeTest = { onOpenRangeTest(current.id) },
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
