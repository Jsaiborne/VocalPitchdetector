@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A moment to settle before the count-in note sounds. */
private const val RUN_LEAD_IN_MS = 800L

/** Share of the graph showing what's coming, so the next notes are visible before they're due. */
private const val EXERCISE_LOOKAHEAD = 0.4f

private const val UI_TICK_MS = 50L
private const val LANDSCAPE_EXERCISE_PIANO_WIDTH_DP = 96

/** How a run ended: its score, and whether every repetition was sung or it was stopped early. */
internal data class RunOutcome(val result: ExerciseResult, val totalReps: Int, val completed: Boolean)

/**
 * Plays the exercise and shows the live pitch over its target bars. Reports the score when the
 * last repetition ends, or on Stop for whatever was sung so far; [onStop] is only called when
 * nothing had been sung yet.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun ExerciseRun(
    exercise: Exercise,
    settings: ExerciseSettings,
    engine: PitchEngine,
    runKey: Int,
    isLandscape: Boolean,
    keyWidthDp: Float,
    graphColors: GraphColors,
    onStop: () -> Unit,
    onFinished: (RunOutcome) -> Unit
) {
    // Everything runs on this clock, which stands still while paused, like the graph's own clock
    val clock = remember(runKey) { ExerciseClock() }
    var schedule by remember(runKey) {
        mutableStateOf(
            buildSchedule(
                exercise = exercise,
                lowMidi = settings.lowMidi,
                highMidi = settings.highMidi,
                direction = settings.direction,
                mode = settings.mode,
                startMs = clock.now() + RUN_LEAD_IN_MS
            )
        )
    }
    // Kept across pauses, which cancel and restart the playback and sampling
    val frames = remember(runKey) { mutableListOf<PitchFrame>() }
    var paused by remember(runKey) { mutableStateOf(false) }
    val latestOnFinished by rememberUpdatedState(onFinished)

    // Playback and scoring run side by side; pausing or leaving the screen cancels both
    LaunchedEffect(schedule, paused) {
        if (paused) return@LaunchedEffect
        launch { playSchedule(schedule, clock) }
        recordFrames(engine, schedule, clock, frames)
        val result = scoreFrames(frames, schedule, settings.toleranceCents)
        latestOnFinished(RunOutcome(result, schedule.reps.size, completed = true))
    }
    DisposableEffect(Unit) {
        onDispose { ToneGenerator.stop() }
    }
    // A run lasts a few minutes with no touches, so keep the screen from sleeping
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val togglePause: () -> Unit = {
        if (!paused) {
            clock.pause()
            ToneGenerator.stop()
            paused = true
        } else {
            clock.resume()
            val resumeAt = clock.now()
            // The interrupted repetition starts again after a short count-in, so the singer doesn't
            // come back in mid-pattern; what was sung of it is dropped
            schedule.repAt(resumeAt)?.let { rep ->
                val repStart = rep.listenStartMs ?: rep.singStartMs
                frames.removeAll { it.tMs >= repStart }
                schedule = schedule.restartFrom(rep.index, resumeAt + (COUNT_IN_BEATS * exercise.beatMs).toLong())
            }
            paused = false
        }
    }
    val stop: () -> Unit = {
        // Score whatever has been sung so far, the current repetition included
        val result = scoreFrames(frames, schedule.truncatedAt(clock.now()), settings.toleranceCents)
        if (result.frames == 0) {
            onStop()
        } else {
            latestOnFinished(RunOutcome(result, schedule.reps.size, completed = false))
        }
    }

    var now by remember { mutableLongStateOf(clock.now()) }
    LaunchedEffect(clock) {
        while (true) {
            now = clock.now()
            delay(UI_TICK_MS)
        }
    }
    // Derived so the screen only recomposes when the phase or repetition actually changes
    val phase by remember(schedule) { derivedStateOf { schedule.phaseAt(now) } }
    val rep by remember(schedule) { derivedStateOf { schedule.repAt(now) } }

    val pitch by engine.state.collectAsState()
    val sungMidi = if (pitch.frequency > 0f) freqToMidi(pitch.frequency.toDouble()).roundToInt() else null
    // Keeps the keyboard and graph centred on the repetition being sung
    val centreMidi = (rep ?: schedule.reps.lastOrNull())?.let {
        it.rootMidi + (exercise.minOffset + exercise.maxOffset) / 2
    }
    val windowMs = (exercise.beatMs * 10f).toLong().coerceIn(5_000L, 10_000L)
    val sharedScroll = rememberScrollState()

    val piano: @Composable (Modifier) -> Unit = { m ->
        Piano(
            modifier = m,
            startMidi = 24,
            endMidi = 84,
            activeMidi = sungMidi,
            autoCenter = true,
            stableMidi = centreMidi,
            whiteKeyHeight = 120.dp,
            blackKeyHeight = 76.dp,
            whiteKeyWidthDp = keyWidthDp.dp,
            scrollState = sharedScroll,
            rotated = isLandscape
        )
    }
    val graph: @Composable (Modifier) -> Unit = { m ->
        PitchGraphCard(
            engine = engine,
            modifier = m,
            // Freezes the graph's clock along with the exercise clock, so the targets stay aligned
            paused = paused,
            startMidi = 24,
            endMidi = 84,
            whiteKeyWidthDp = keyWidthDp.dp,
            scrollState = sharedScroll,
            timeWindowMs = windowMs,
            // A bpm of 60 leaves the window as it is (the live graph scales it by its scroll speed)
            bpm = 60f,
            rotated = isLandscape,
            showHorizontalGrid = false,
            showWhiteDots = false,
            colors = graphColors,
            targets = schedule.events,
            lookaheadFraction = EXERCISE_LOOKAHEAD
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RunStatus(
            exercise = exercise,
            phase = phase,
            mode = settings.mode,
            rep = rep,
            repCount = schedule.reps.size,
            paused = paused,
            onTogglePause = togglePause,
            onStop = stop
        )
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                piano(Modifier.fillMaxHeight().width(LANDSCAPE_EXERCISE_PIANO_WIDTH_DP.dp))
                graph(Modifier.fillMaxHeight().weight(1f).padding(start = 2.dp))
            }
        } else {
            piano(Modifier)
            graph(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** What to do right now ("Listen…", "Sing!"), which repetition this is, and pause/stop buttons. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun RunStatus(
    exercise: Exercise,
    phase: ExercisePhase,
    mode: GuideMode,
    rep: RepSpan?,
    repCount: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit
) {
    // Between repetitions, rep is already the next one
    val nextHint = rep?.let { "Next one starts on ${midiToDisplayName(it.rootMidi)}" }.orEmpty()
    val pausedHint = if (rep != null) "Resume to sing this repetition from the top" else ""
    val (headline, hint) = when {
        paused -> "Paused" to pausedHint
        phase == ExercisePhase.COUNT_IN -> "Get ready…" to "Here's your starting note"
        phase == ExercisePhase.LISTEN -> "Listen…" to "Then sing it back"
        phase == ExercisePhase.SING && mode == GuideMode.SING_ALONG -> "Sing along!" to "Follow the green bars"
        phase == ExercisePhase.SING -> "Your turn: sing!" to "Follow the green bars"
        phase == ExercisePhase.BREATH -> "Breathe" to nextHint
        else -> "Nice work!" to "Working out your score…"
    }
    val singing = phase == ExercisePhase.SING && !paused
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (singing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodySmall)
                Text(
                    buildString {
                        append(exercise.name)
                        if (rep != null) {
                            append(" · repetition ${rep.index + 1} of $repCount")
                            append(" · from ${midiToDisplayName(rep.rootMidi)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = onTogglePause, enabled = phase != ExercisePhase.DONE) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(if (paused) " Resume" else " Pause")
                }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

/** The score (of the whole exercise, or of what was sung before Stop), how each repetition went, and what next. */
@Suppress("LongMethod")
@Composable
internal fun ExerciseResultView(
    outcome: RunOutcome,
    newBest: Boolean,
    onReplay: () -> Unit,
    onChangeSettings: () -> Unit,
    onAllExercises: () -> Unit
) {
    val result = outcome.result
    val sungReps = result.reps.filter { it.frames > 0 }
    // Only worth pointing out when one key clearly lagged behind the rest
    val toughest = sungReps.minByOrNull { it.accuracy }
        ?.takeIf { sungReps.size > 1 && it.accuracy < result.accuracy - 0.1f }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (result.frames == 0) {
            Text(
                "Nothing was scored. Try a wider range or a slower tempo.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        } else {
            Text("${result.percent}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text("on pitch", style = MaterialTheme.typography.titleMedium)
            if (!outcome.completed) {
                Text(
                    "Stopped early, after ${result.reps.size} of ${outcome.totalReps} repetitions. " +
                        "Only full runs count towards your best.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (newBest) {
                Text(
                    "New personal best!",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Box(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                RepBars(reps = result.reps)
            }
            Text(
                "Each bar is one repetition, labelled with its starting note.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            toughest?.let {
                Text(
                    "Toughest key: starting on ${midiToDisplayName(it.rootMidi)} " +
                        "(${(it.accuracy * 100f).roundToInt()}%)",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            tendencyText(result.meanCentsError)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onReplay, modifier = Modifier.fillMaxWidth()) { Text("Sing it again") }
            OutlinedButton(onClick = onChangeSettings, modifier = Modifier.fillMaxWidth()) { Text("Change settings") }
            TextButton(onClick = onAllExercises, modifier = Modifier.fillMaxWidth()) { Text("All exercises") }
        }
    }
}

/** One bar per repetition, as tall as its accuracy, coloured from red (missed) to the theme colour. */
@Composable
private fun RepBars(reps: List<RepScore>) {
    val good = MaterialTheme.colorScheme.primary
    val fair = MaterialTheme.colorScheme.tertiary
    val poor = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPaint = remember(labelColor) {
        Paint().apply {
            color = labelColor.toArgb()
            textSize = 24f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        if (reps.isEmpty()) return@Canvas
        val labelSpace = 32f
        val chartHeight = size.height - labelSpace
        val slot = size.width / reps.size
        val barWidth = (slot * 0.7f).coerceAtLeast(2f)
        // Label only every few bars when there are too many to fit
        val labelEvery = ceil(reps.size * 56f / size.width).toInt().coerceAtLeast(1)

        for ((i, rep) in reps.withIndex()) {
            val left = i * slot + (slot - barWidth) / 2f
            val corner = CornerRadius(barWidth / 4f)
            drawRoundRect(track, Offset(left, 0f), Size(barWidth, chartHeight), corner)
            val barHeight = chartHeight * rep.accuracy
            val color = when {
                rep.accuracy >= 0.8f -> good
                rep.accuracy >= 0.5f -> fair
                else -> poor
            }
            if (barHeight > 0f) {
                drawRoundRect(color, Offset(left, chartHeight - barHeight), Size(barWidth, barHeight), corner)
            }
            if (i % labelEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    midiToDisplayName(rep.rootMidi),
                    left + barWidth / 2f,
                    size.height - 6f,
                    labelPaint
                )
            }
        }
    }
}
