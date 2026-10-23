@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class ExerciseStep { LIBRARY, SETUP, RUN, RESULT }

/**
 * Vocal exercises: pick one, set it up, sing it against target bars, then see the score. Shown
 * instead of the main screen while open; the pitch engine and mic keep running underneath.
 */
@Suppress("LongParameterList", "LongMethod")
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

    var step by remember { mutableStateOf(ExerciseStep.LIBRARY) }
    var exercise by remember { mutableStateOf<Exercise?>(null) }
    var settings by remember { mutableStateOf(readExerciseSettings(prefs, savedRange)) }
    var bpm by remember { mutableFloatStateOf(100f) }
    var outcome by remember { mutableStateOf<RunOutcome?>(null) }
    var newBest by remember { mutableStateOf(false) }
    // Bumped on every start, so "Sing it again" builds a fresh schedule
    var runKey by remember { mutableIntStateOf(0) }

    // Guide notes sound best as piano samples; load them for the exercises even if the user's
    // piano uses the synthesised tone, and release them again on the way out
    DisposableEffect(useSamplePlayer) {
        if (!useSamplePlayer) SamplePlayer.init(context.applicationContext)
        onDispose { if (!useSamplePlayer) SamplePlayer.release() }
    }

    val current = exercise
    // Back steps out one level at a time; from the list it closes the exercises
    BackHandler {
        when (step) {
            ExerciseStep.LIBRARY -> onExit()
            ExerciseStep.SETUP, ExerciseStep.RESULT -> step = ExerciseStep.LIBRARY
            ExerciseStep.RUN -> step = ExerciseStep.SETUP
        }
    }

    // A run uses nearly the whole screen so the piano and graph get as much room as possible
    val running = step == ExerciseStep.RUN
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (running) 6.dp else 16.dp, vertical = if (running) 2.dp else 8.dp)
    ) {
        if (step == ExerciseStep.LIBRARY || current == null) {
            ExerciseHeader(title = "Exercises", subtitle = "Warm-ups that follow your range", onClose = onExit)
            ExerciseLibrary(
                exercises = BUILT_IN_EXERCISES,
                bestScore = { readBestScore(prefs, it.id) },
                onSelect = {
                    exercise = it
                    bpm = it.bpm
                    step = ExerciseStep.SETUP
                }
            )
        } else {
            when (step) {
                ExerciseStep.SETUP -> {
                    ExerciseHeader(
                        title = current.name,
                        subtitle = current.description,
                        onBack = { step = ExerciseStep.LIBRARY }
                    )
                    ExerciseSetup(
                        exercise = current.copy(bpm = bpm),
                        settings = settings,
                        hasRangeTest = savedRange != null,
                        onSettingsChange = { settings = it },
                        onBpmChange = { bpm = it },
                        onOpenRangeTest = onOpenRangeTest,
                        onStart = {
                            saveExerciseSettings(prefs, settings)
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
}

/** Title row with a back arrow ([onBack]) or a close button ([onClose]). */
@Composable
internal fun ExerciseHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
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
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close exercises")
            }
        }
    }
}

@Composable
private fun ExerciseLibrary(
    exercises: List<Exercise>,
    bestScore: (Exercise) -> Int?,
    onSelect: (Exercise) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (exercise in exercises) {
            ExerciseCard(exercise = exercise, best = bestScore(exercise), onClick = { onSelect(exercise) })
        }
    }
}

@Composable
private fun ExerciseCard(exercise: Exercise, best: Int?, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    exercise.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
