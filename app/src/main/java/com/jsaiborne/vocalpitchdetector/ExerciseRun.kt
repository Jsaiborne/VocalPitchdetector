@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
 * whether it ran to the end or was stopped early. [startOffset] is the pattern's first note, in
 * semitones from the root, for naming each repetition by the note it starts on.
 */
internal data class RunOutcome(
    val result: ExerciseResult,
    val keysReached: Int,
    val totalKeys: Int,
    val completed: Boolean,
    val startOffset: Int
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
    // Kept across pauses and plan changes, which cancel and restart the playback
    val readings = remember(runKey) { mutableListOf<PitchFrame>() }
    val playback = remember(runKey) { GuidePlayback() }
    // One trace for both layouts, so rotating keeps it. Its clock is paused and resumed together
    // with the exercise clock, so the target bars stay lined up with it; a new run starts both afresh
    val graphTrace = remember(runKey) { TraceBuffer() }
    var paused by remember(runKey) { mutableStateOf(false) }
    var repeating by remember(runKey) { mutableStateOf(false) }
    val latestOnFinished by rememberUpdatedState(onFinished)

    // Every sung pitch is kept, timed on the exercise clock, and scored once the run is over
    LaunchedEffect(engine, clock) { collectReadings(engine, clock, readings) }

    // Plays the guide notes and waits for the end. Both follow the exercise clock, so they stand
    // still while it's paused even before this is cancelled: after leaving the app nothing on screen
    // is updated (or cancelled) until the user comes back
    LaunchedEffect(schedule, paused) {
        if (paused) return@LaunchedEffect
        launch { playSchedule(schedule, clock, playback) }
        clock.awaitTime(schedule.endMs + SCORE_TAIL_MS)
        val frames = framesFromReadings(readings, schedule.startMs, schedule.endMs)
        val result = scoreFrames(frames, schedule, settings.toleranceCents)
        latestOnFinished(RunOutcome(result, keyRoots.size, keyRoots.size, completed = true, exercise.firstOffset))
    }
    DisposableEffect(clock) {
        onDispose {
            ToneGenerator.stop()
            // Piano notes frozen by a pause would otherwise wait in the pool and sound in a later run
            if (clock.isPaused) SamplePlayer.stopAll()
        }
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

    // Freezes on the spot, piano notes and graph included; resuming carries on from the same moment.
    // The clocks are stopped here and now, not left to the next recomposition, which can be a long
    // way off when the app is being left
    val pauseRun: () -> Unit = {
        if (!paused) {
            clock.pause()
            graphTrace.setPaused(true)
            SamplePlayer.pauseAll()
            ToneGenerator.stop()
            paused = true
        }
    }
    // Leaving the app pauses the run: otherwise the guide notes would play on in the background and
    // the time away would be scored as silence. The user resumes it when they come back
    OnStopEffect(pauseRun)

    val controls = RunControls(
        paused = paused,
        repeating = repeating,
        onTogglePause = {
            if (paused) {
                clock.resume()
                graphTrace.setPaused(false)
                SamplePlayer.resumeAll()
                paused = false
            } else {
                pauseRun()
            }
        },
        onToggleRepeat = {
            // Turning it off takes back the go lined up next, so the exercise moves on after this one
            if (repeating && repIndex != null) plan = plan.withoutRepeatAfter(repIndex)
            repeating = !repeating
        },
        onStop = {
            // Score whatever has been sung so far, the current repetition included
            val sung = schedule.truncatedAt(clock.now())
            val frames = framesFromReadings(readings, sung.startMs, sung.endMs)
            val result = scoreFrames(frames, sung, settings.toleranceCents)
            val keysReached = plan.getOrNull(sung.reps.lastIndex)?.let { it.keyIndex + 1 } ?: 0
            if (result.frames == 0) {
                onStop()
            } else {
                val outcome = RunOutcome(result, keysReached, keyRoots.size, completed = false, exercise.firstOffset)
                latestOnFinished(outcome)
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
            lookaheadFraction = EXERCISE_LOOKAHEAD,
            trace = graphTrace
        )
    }

    val current = repIndex?.let { plan.getOrNull(it) }
    // Which go at this key it is: 1 for the first time through, then counting the repeats
    val attempt = repIndex?.let { index ->
        current?.let { rep -> plan.take(index + 1).count { it.keyIndex == rep.keyIndex } }
    } ?: 0
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
