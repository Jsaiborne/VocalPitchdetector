package com.jsaiborne.vocalpitchdetector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A detected pitch as a fractional MIDI note, or NaN for silence. */
internal data class PitchSample(val tMs: Long, val midi: Float)

/**
 * The live graph's recent pitch and its clock: a rolling window of samples, backed by an ArrayDeque
 * so trimming the oldest entries is O(1). [version] changes whenever the contents do; a Canvas that
 * calls [observeChanges] therefore redraws when new data arrives.
 *
 * A screen whose layout changes with the orientation keeps one above the switch
 * ([rememberTraceBuffer]) and gives it to the graph in each layout, so the trace carries on across
 * a rotation instead of starting blank.
 */
@Stable
class TraceBuffer {
    internal val samples = ArrayDeque<PitchSample>()
    private var version by mutableLongStateOf(0L)

    // The graph runs on its own clock that stands still while frozen, so Freeze stops the curve
    // where it is and Resume carries on from the same spot
    private var pausedAtMs: Long? = null
    private var pausedTotalMs = 0L

    // Wall-clock time of the last Resume; readings detected before it belong to the frozen stretch
    private var resumedAtMs = 0L

    internal fun observeChanges(): Long = version

    /** Graph time: wall-clock time minus the time spent frozen. */
    internal fun now(): Long = (pausedAtMs ?: System.currentTimeMillis()) - pausedTotalMs

    internal fun setPaused(paused: Boolean) {
        val wallMs = System.currentTimeMillis()
        val pausedAt = pausedAtMs
        if (paused && pausedAt == null) {
            pausedAtMs = wallMs
        } else if (!paused && pausedAt != null) {
            pausedTotalMs += wallMs - pausedAt
            pausedAtMs = null
            resumedAtMs = wallMs
            version++
        }
    }

    /**
     * Adds a reading at the time the engine detected it ([detectedAtMs], wall clock), rather than when
     * the screen got round to it, so UI hiccups don't bunch the points up. A reading from before the
     * last Resume, or no newer than the last one, is skipped: the engine's latest state is handed over
     * again whenever the graph starts listening (after Resume or a rotation).
     */
    internal fun addReading(detectedAtMs: Long, midi: Float, windowMs: Long) {
        if (detectedAtMs < resumedAtMs) return
        val now = now()
        val t = (detectedAtMs - pausedTotalMs).coerceAtMost(now)
        if (t <= (samples.lastOrNull()?.tMs ?: Long.MIN_VALUE)) return
        samples.addLast(PitchSample(tMs = t, midi = midi))
        trim(now - windowMs)
        version++
    }

    /**
     * Drops data older than [cutoffMs] and asks the graph to redraw, so the trace keeps scrolling
     * even when no new pitch data arrives.
     */
    internal fun tick(cutoffMs: Long) {
        trim(cutoffMs)
        version++
    }

    /** True while any sung pitch newer than [sinceMs] is still in the buffer. */
    internal fun hasContentSince(sinceMs: Long): Boolean {
        for (i in samples.indices.reversed()) {
            val s = samples[i]
            if (s.tMs < sinceMs) break
            if (!s.midi.isNaN()) return true
        }
        return false
    }

    private fun trim(cutoffMs: Long) {
        while (samples.isNotEmpty() && samples.first().tMs < cutoffMs) samples.removeFirst()
    }
}

/**
 * Display frames closer together than this are skipped, so a 120 Hz screen redraws the graph at 60 fps;
 * how often to check for a trace while idle; and how long to keep scrolling after the last sung note.
 */
private const val SCROLL_MIN_FRAME_GAP_MS = 10L
private const val SCROLL_IDLE_POLL_MS = 150L
private const val SCROLL_EXIT_MARGIN_MS = 500L

/** A trace for [PitchGraphCard] that lasts as long as the caller, e.g. across an orientation change below it. */
@Composable
fun rememberTraceBuffer(): TraceBuffer = remember { TraceBuffer() }

/**
 * Feeds [buffer] from the engine and keeps it scrolling. A graph that comes back (after a rotation)
 * restarts this and carries on the same trace.
 */
@Composable
private fun FollowPitch(
    buffer: TraceBuffer,
    engine: PitchEngine,
    paused: Boolean,
    windowMs: Long,
    // Keeps the graph scrolling even with nothing sung, e.g. while exercise targets move across it
    keepAlive: Boolean
) {
    // Applied before the frame is drawn, so the clock stops on the same frame as the button
    SideEffect { buffer.setPaused(paused) }

    LaunchedEffect(buffer, engine, paused, windowMs) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val midi = if (s.frequency > 0f) freqToMidi(s.frequency.toDouble()).toFloat() else Float.NaN
                buffer.addReading(detectedAtMs = s.timestampMs, midi = midi, windowMs = windowMs)
            }
        }
    }

    // The engine reports silence only once, so the graph drives its own scrolling: it redraws on the
    // display's frames while a trace is still on screen, so it moves evenly, and stops redrawing once
    // the trace has scrolled away, so an idle screen costs almost nothing
    LaunchedEffect(buffer, paused, windowMs, keepAlive) {
        var lastFrameMs = 0L
        while (true) {
            if (!paused && (keepAlive || buffer.hasContentSince(buffer.now() - windowMs - SCROLL_EXIT_MARGIN_MS))) {
                withFrameMillis { frameMs ->
                    if (frameMs - lastFrameMs >= SCROLL_MIN_FRAME_GAP_MS) {
                        lastFrameMs = frameMs
                        buffer.tick(cutoffMs = buffer.now() - windowMs)
                    }
                }
            } else {
                delay(SCROLL_IDLE_POLL_MS)
            }
        }
    }
}

/** Room left after "now" along the time axis, so the newest point's glow isn't cut off. */
private val NOW_EDGE_INSET = 12.dp

/** The oldest part of the trace fades out over this share of the time already sung. */
private const val TRACE_FADE_FRACTION = 0.35f

/** Pitch older than this no longer counts as "now": its lane stops glowing and the tag goes. */
private const val NOW_MAX_AGE_MS = 250L

/** Number of gaps between the fixed lines across the graph (the "Grid lines" setting). */
private const val GRID_DIVISIONS = 6

/** The pitch axis, shared with the piano beside the graph. */
private class LivePitchLayout(
    val midiRange: IntRange,
    val axis: KeyboardPitchAxis,
    /** Length of the pitch axis (the same as the piano's), and the shift applied along it. */
    val lengthPx: Float,
    val alignPx: Float
)

/** Which parts of the live graph are shown (the Graph appearance switches). */
private class LiveGraphLayers(
    val blocks: Boolean,
    val grid: Boolean,
    val curve: Boolean,
    val dots: Boolean
)

/** Everything the live graph is drawn with, apart from the samples and the clock. */
private class LiveGraphSettings(
    val rotated: Boolean,
    val pitch: LivePitchLayout,
    val windowMs: Long,
    /** Share of the time axis showing the past; "now" sits at that point. */
    val pastFraction: Float,
    val layers: LiveGraphLayers,
    val colors: GraphColors,
    val targets: List<TargetEvent>
)

/**
 * The live pitch graph. Pitch runs along the piano (across in portrait, up in landscape) on the same
 * axis as the keyboard beside it, and time runs down (portrait) or to the right (landscape).
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun PitchGraphCard(
    engine: PitchEngine,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    startMidi: Int = 24,
    endMidi: Int = 84,
    whiteKeyWidthDp: Dp = 56.dp,
    scrollState: ScrollState? = null,
    alignmentOffsetDp: Dp = 0.dp,
    timeWindowMs: Long = 8000L,
    bpm: Float = 120f,
    rotated: Boolean = false,
    // Held-note blocks and their names
    showNoteLabels: Boolean = true,
    showHorizontalGrid: Boolean = true,
    showCurve: Boolean = true,
    showWhiteDots: Boolean = true,
    // Shift for the scrolled graph while a pinch-zoom's scroll is catching up (see PitchAxisZoom)
    scrollCorrectionPx: () -> Float = { 0f },
    colors: GraphColors = GraphColors.Default,
    // Exercise notes to sing, drawn as bars under the live curve
    targets: List<TargetEvent> = emptyList(),
    // Share of the time window shown ahead of "now", so upcoming targets scroll into view
    lookaheadFraction: Float = 0f,
    // Pass one from above a layout switch to keep the trace when the graph is recreated (see TraceBuffer)
    trace: TraceBuffer = rememberTraceBuffer()
) {
    val density = LocalDensity.current
    val windowMs = remember(timeWindowMs, bpm) { (timeWindowMs.toFloat() * (60f / bpm)).toLong() }
    FollowPitch(trace, engine, paused, windowMs, keepAlive = targets.isNotEmpty())
    val paints = rememberGraphPaints()
    val scroll = scrollState ?: rememberScrollState()

    val keySizePx = with(density) { whiteKeyWidthDp.toPx() }
    val pitchAxis = remember(startMidi, keySizePx) { KeyboardPitchAxis(startMidi, keySizePx) }
    val whiteCount = remember(startMidi, endMidi) { (startMidi..endMidi).count { isWhiteKey(it) } }
    val contentPitchPx = whiteCount * keySizePx
    val contentPitchDp = with(density) { contentPitchPx.toDp() }

    val settings = LiveGraphSettings(
        rotated = rotated,
        pitch = LivePitchLayout(
            midiRange = startMidi..endMidi,
            axis = pitchAxis,
            lengthPx = contentPitchPx,
            alignPx = with(density) { alignmentOffsetDp.toPx() }
        ),
        windowMs = windowMs,
        pastFraction = 1f - lookaheadFraction.coerceIn(0f, MAX_LOOKAHEAD_FRACTION),
        layers = LiveGraphLayers(
            blocks = showNoteLabels,
            grid = showHorizontalGrid,
            curve = showCurve,
            dots = showWhiteDots
        ),
        colors = colors,
        targets = targets
    )

    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        // Padding only along the time axis: along the pitch axis the graph lines up with the piano
        val timeAxisPadding = if (rotated) Modifier.padding(horizontal = 6.dp) else Modifier.padding(vertical = 6.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .then(timeAxisPadding)
        ) {
            val scroller = if (rotated) {
                Modifier
                    .verticalScroll(scroll)
                    .graphicsLayer { translationY = scrollCorrectionPx() }
            } else {
                Modifier
                    .horizontalScroll(scroll)
                    .graphicsLayer { translationX = scrollCorrectionPx() }
            }
            Box(modifier = Modifier.fillMaxSize().then(scroller)) {
                val canvasSize = if (rotated) {
                    Modifier.fillMaxWidth().height(contentPitchDp)
                } else {
                    Modifier.width(contentPitchDp).fillMaxHeight()
                }
                Canvas(modifier = canvasSize) {
                    trace.observeChanges()
                    // The part of the pitch axis on screen, so the note tag can stay inside it
                    val visiblePitch = scroll.value.toFloat()..(scroll.value + scroll.viewportSize).toFloat()
                    // The trace starts a little before the window, so it runs right up to the edge
                    clipRect { drawLiveGraph(settings, trace.samples, trace.now(), visiblePitch, paints) }
                }
            }
        }
    }
}

/** Draws one frame of the live graph: lanes, grid, exercise targets, held notes, the trace and the now tag. */
@Suppress("LongMethod")
private fun DrawScope.drawLiveGraph(
    settings: LiveGraphSettings,
    samples: List<PitchSample>,
    nowMs: Long,
    visiblePitchPx: ClosedFloatingPointRange<Float>,
    paints: GraphPaints
) {
    val s = settings
    val pitch = s.pitch
    val timeRunsDown = !s.rotated
    val timeEndPx = (if (timeRunsDown) size.height else size.width) - NOW_EDGE_INSET.toPx()
    val windowStartMs = nowMs - (s.windowMs * s.pastFraction).toLong()
    val axes = GraphAxes(
        timeRunsDown = timeRunsDown,
        timeStartPx = 0f,
        timeEndPx = timeEndPx,
        timeToPx = { t -> (t - windowStartMs).toFloat() / s.windowMs * timeEndPx },
        // Landscape puts the low notes at the bottom, like the keyboard beside it
        pitchToPx = { m ->
            val centre = pitch.axis.centreOf(m)
            pitch.alignPx + (if (timeRunsDown) centre else pitch.lengthPx - centre)
        }
    )

    val background = listOf(GraphStyle.backgroundTop, GraphStyle.backgroundBottom)
    drawRect(if (timeRunsDown) Brush.verticalGradient(background) else Brush.horizontalGradient(background))

    val latest = samples.lastOrNull()?.takeIf { !it.midi.isNaN() && nowMs - it.tMs <= NOW_MAX_AGE_MS }
    drawNoteLanes(
        axes,
        LaneStyle(
            midiRange = pitch.midiRange,
            lanePx = pitch.axis.semitoneSize,
            activeMidi = latest?.midi?.roundToInt(),
            activeColor = s.colors.curve,
            namesAtPx = if (timeRunsDown) paints.laneName.textSize + 4.dp.toPx() else 6.dp.toPx()
        ),
        paints
    )

    if (s.layers.grid) {
        for (i in 0..GRID_DIVISIONS) {
            val alongTime = timeEndPx * i / GRID_DIVISIONS
            drawLine(
                color = GraphStyle.pitchGrid,
                start = axes.point(alongTime, 0f),
                end = axes.point(alongTime, pitch.lengthPx),
                strokeWidth = 1.dp.toPx()
            )
        }
    }

    if (s.targets.isNotEmpty()) {
        drawExerciseTargets(
            targets = s.targets,
            nowMs = nowMs,
            windowStartMs = windowStartMs,
            windowEndMs = windowStartMs + s.windowMs,
            timeToPx = axes.timeToPx,
            pitchToPx = { m -> axes.pitchToPx(m.toFloat()) },
            thicknessPx = pitch.axis.semitoneSize * TARGET_THICKNESS_FRACTION,
            timeRunsDown = timeRunsDown
        )
    }
    if (s.pastFraction < 1f) {
        val nowPx = timeEndPx * s.pastFraction
        drawLine(
            color = GraphStyle.nowLine,
            start = axes.point(nowPx, 0f),
            end = axes.point(nowPx, pitch.lengthPx),
            strokeWidth = 2.dp.toPx()
        )
    }

    // During exercises the target bars already show the notes, so the held-note blocks stay out of the way
    if (s.layers.blocks && s.targets.isEmpty()) {
        drawNoteBlocks(
            axes,
            segmentNotes(samples, PitchSample::tMs, PitchSample::midi),
            NoteBlockStyle(
                thicknessPx = pitch.axis.semitoneSize * NOTE_BLOCK_LANE_FRACTION,
                colorFor = { note -> noteBlockColor(note, s.colors) },
                showNames = true
            ),
            paints
        )
    }

    val trace = buildTraceShape(samples, PitchSample::tMs, PitchSample::midi, s.colors.seamlessGaps) { sample ->
        axes.at(sample.tMs, sample.midi)
    }
    // While you sing, the curve runs on at the newest pitch up to "now", so its end stays on the now
    // edge instead of slipping back between readings (never longer than the gap to the next reading)
    val head = latest?.let { axes.at(it.tMs, it.midi) to axes.at(nowMs, it.midi) }
    val fadeEndPx = timeEndPx * s.pastFraction * TRACE_FADE_FRACTION
    drawTraceSegments(
        trace.copy(head = head),
        TraceStyle(
            colors = s.colors,
            timeAlongY = timeRunsDown,
            showCurve = s.layers.curve,
            showDots = s.layers.dots,
            fadeStart = axes.point(0f, 0f),
            fadeEnd = axes.point(fadeEndPx, 0f)
        )
    )

    if (latest != null) {
        val bounds = if (timeRunsDown) {
            Rect(visiblePitchPx.start, 0f, visiblePitchPx.endInclusive, size.height)
        } else {
            Rect(0f, visiblePitchPx.start, size.width, visiblePitchPx.endInclusive)
        }
        drawNowTag(
            nowTag(axes.at(nowMs, latest.midi), latest.midi, s.colors, dotColor = s.colors.curve),
            timeRunsDown,
            bounds,
            paints
        )
    }
}
