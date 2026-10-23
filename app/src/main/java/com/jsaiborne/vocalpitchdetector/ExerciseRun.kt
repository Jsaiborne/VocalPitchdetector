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
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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

// The status bar is a little roomier in portrait, where height is easier to spare than in landscape
private const val STATUS_BUTTON_DP = 36
private const val STATUS_ICON_DP = 20
private const val PORTRAIT_STATUS_BUTTON_DP = 44
private const val PORTRAIT_STATUS_ICON_DP = 24

/**
 * How a run ended: its score, how far through the keys it got ([keysReached] of [totalKeys]), and
 * whether it ran to the end or was stopped early.
 */
internal data class RunOutcome(
    val result: ExerciseResult,
    val keysReached: Int,
    val totalKeys: Int,
    val completed: Boolean
)

/** The run's buttons and their state, handed to the status card as one. */
private data class RunControls(
    val paused: Boolean,
    val repeating: Boolean,
    val onTogglePause: () -> Unit,
    val onToggleRepeat: () -> Unit,
    val onStop: () -> Unit
)

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
    val startMs = remember(runKey) { clock.now() + RUN_LEAD_IN_MS }
    val keyRoots = remember(runKey) {
        exerciseRoots(exercise, settings.lowMidi, settings.highMidi, settings.direction)
    }
    // The repetitions to sing; "repeat this key" adds to it as the run goes
    var plan by remember(runKey) { mutableStateOf(initialPlan(keyRoots)) }
    val schedule = remember(plan, startMs) {
        buildScheduleForRoots(exercise, plan.map { it.rootMidi }, settings.mode, startMs)
    }
    // Kept across pauses and plan changes, which cancel and restart the playback and sampling
    val frames = remember(runKey) { mutableListOf<PitchFrame>() }
    var paused by remember(runKey) { mutableStateOf(false) }
    var repeating by remember(runKey) { mutableStateOf(false) }
    val latestOnFinished by rememberUpdatedState(onFinished)

    // Playback and scoring run side by side; pausing or leaving the screen cancels both
    LaunchedEffect(schedule, paused) {
        if (paused) return@LaunchedEffect
        launch { playSchedule(schedule, clock) }
        recordFrames(engine, schedule, clock, frames)
        val result = scoreFrames(frames, schedule, settings.toleranceCents)
        latestOnFinished(RunOutcome(result, keyRoots.size, keyRoots.size, completed = true))
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
    val repIndex = rep?.index

    // While repeating, there is always another go at the current repetition lined up after it
    LaunchedEffect(repeating, repIndex) {
        if (repeating && repIndex != null) plan = plan.withRepeatAfter(repIndex)
    }

    val controls = RunControls(
        paused = paused,
        repeating = repeating,
        onTogglePause = {
            // Freezes on the spot, piano notes included, and carries on from the same moment
            if (paused) {
                clock.resume()
                SamplePlayer.resumeAll()
            } else {
                clock.pause()
                SamplePlayer.pauseAll()
                ToneGenerator.stop()
            }
            paused = !paused
        },
        onToggleRepeat = {
            // Turning it off takes back the go lined up next, so the exercise moves on after this one
            if (repeating && repIndex != null) plan = plan.withoutRepeatAfter(repIndex)
            repeating = !repeating
        },
        onStop = {
            // Score whatever has been sung so far, the current repetition included
            val sung = schedule.truncatedAt(clock.now())
            val result = scoreFrames(frames, sung, settings.toleranceCents)
            val keysReached = plan.getOrNull(sung.reps.lastIndex)?.let { it.keyIndex + 1 } ?: 0
            if (result.frames == 0) {
                onStop()
            } else {
                latestOnFinished(RunOutcome(result, keysReached, keyRoots.size, completed = false))
            }
        }
    )

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

    val current = repIndex?.let { plan.getOrNull(it) }
    // Which go at this key it is: 1 for the first time through, then counting the repeats
    val attempt = if (current == null || repIndex == null) {
        0
    } else {
        plan.take(repIndex + 1).count { it.keyIndex == current.keyIndex }
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RunStatus(
            exercise = exercise,
            phase = phase,
            mode = settings.mode,
            current = current,
            attempt = attempt,
            keyCount = keyRoots.size,
            controls = controls,
            compact = isLandscape
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

/**
 * One compact row: what to do right now ("Listen…", "Sing!") with a short hint, which key this is,
 * and icon buttons to pause, repeat the key and stop. Kept slim so the piano and graph get the room.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun RunStatus(
    exercise: Exercise,
    phase: ExercisePhase,
    mode: GuideMode,
    current: RepPlan?,
    attempt: Int,
    keyCount: Int,
    controls: RunControls,
    compact: Boolean
) {
    // Between repetitions, current is already the next one
    val nextHint = current?.let { "next: ${midiToDisplayName(it.rootMidi)}" }.orEmpty()
    val (headline, hint) = when {
        controls.paused -> "Paused" to "resume carries on from here"
        phase == ExercisePhase.COUNT_IN -> "Get ready" to "starting note"
        phase == ExercisePhase.LISTEN -> "Listen…" to "then sing it back"
        phase == ExercisePhase.SING && mode == GuideMode.SING_ALONG -> "Sing along!" to "follow the bars"
        phase == ExercisePhase.SING -> "Sing!" to "follow the bars"
        phase == ExercisePhase.BREATH -> "Breathe" to nextHint
        else -> "Nice work!" to "scoring…"
    }
    val singing = phase == ExercisePhase.SING && !controls.paused
    val running = phase != ExercisePhase.DONE
    val buttonModifier = Modifier.size((if (compact) STATUS_BUTTON_DP else PORTRAIT_STATUS_BUTTON_DP).dp)
    val iconModifier = Modifier.size((if (compact) STATUS_ICON_DP else PORTRAIT_STATUS_ICON_DP).dp)
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
        Row(
            modifier = if (compact) {
                Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            } else {
                Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(headline) }
                        if (hint.isNotEmpty()) append("  ·  $hint")
                    },
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(exercise.name)
                        if (current != null) {
                            append(" · key ${current.keyIndex + 1}/$keyCount")
                            append(" · ${midiToDisplayName(current.rootMidi)}")
                            if (attempt > 1) append(" · go $attempt")
                        }
                    },
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalIconButton(onClick = controls.onTogglePause, enabled = running, modifier = buttonModifier) {
                Icon(
                    if (controls.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (controls.paused) "Resume" else "Pause",
                    modifier = iconModifier
                )
            }
            // Riyaz-style: keep singing the same key until it's switched off again
            FilledIconToggleButton(
                checked = controls.repeating,
                onCheckedChange = { controls.onToggleRepeat() },
                enabled = running,
                modifier = buttonModifier
            ) {
                Icon(
                    Icons.Filled.RepeatOne,
                    contentDescription = if (controls.repeating) "Stop repeating this key" else "Repeat this key",
                    modifier = iconModifier
                )
            }
            OutlinedIconButton(onClick = controls.onStop, modifier = buttonModifier) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop and see the score", modifier = iconModifier)
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
                    "Stopped early, at key ${outcome.keysReached} of ${outcome.totalKeys}. " +
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
