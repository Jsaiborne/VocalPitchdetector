package com.example.vocalpitchdetector

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.log2
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.max
import kotlin.math.min

// Canonical internal types for drawing
private data class GraphPitchSample(val tMs: Long, val freq: Float, val midi: Float)
private data class StableMarker(val tMs: Long, val midi: Int)

/** Helpers */
private fun freqToMidiLocal(f: Double): Double = 69.0 + 12.0 * log2(f / 440.0)
private fun midiToNoteNameLocal(midi: Int): String {
    val names = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val octave = midi / 12 - 1
    return "${names[midi % 12]}$octave"
}

/** Build a smoothed path from points (Catmull-Rom -> cubic bezier) */
private fun buildSmoothedPath(points: List<Offset>, smoothing: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) { path.moveTo(points[0].x, points[0].y); return path }
    if (smoothing <= 0.001f) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }
    val factor = smoothing / 6f
    val pts = mutableListOf<Offset>()
    pts.add(points.first())
    pts.addAll(points)
    pts.add(points.last())
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until pts.size - 2) {
        val p0 = pts[i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[i + 2]
        val cp1 = Offset(
            x = p1.x + (p2.x - p0.x) * factor,
            y = p1.y + (p2.y - p0.y) * factor
        )
        val cp2 = Offset(
            x = p2.x - (p3.x - p1.x) * factor,
            y = p2.y - (p3.y - p1.y) * factor
        )
        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
    }
    return path
}

/**
 * High-level card that chooses rotated or horizontal rendering.
 * NEW: accepts playbackSamples (pitch trace from JSON) and playbackPositionMs (current playback cursor in ms).
 */
@Composable
fun PitchGraphCard(
    engine: PitchEngine,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onTogglePause: () -> Unit,
    startMidi: Int = 24,
    endMidi: Int = 84,
    whiteKeyWidthDp: Dp = 56.dp,
    scrollState: ScrollState? = null,
    alignmentOffsetDp: Dp = 0.dp,
    blackKeyShiftFraction: Float = 0.5f,
    timeWindowMs: Long = 8000L,
    rotated: Boolean = false,
    showNoteLabels: Boolean = true,
    showHorizontalGrid: Boolean = true,
    showCurve: Boolean = true,
    smoothing: Float = 0.5f,
    showWhiteTrace: Boolean = true,

    // NEW:
    playbackSamples: List<PitchSample>? = null,
    playbackPositionMs: Long? = null
) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp)
        ) {
            if (!rotated) {
                PitchGraphHorizontal(
                    engine = engine,
                    paused = paused,
                    modifier = Modifier.fillMaxSize(),
                    windowMs = timeWindowMs,
                    startMidi = startMidi,
                    endMidi = endMidi,
                    whiteKeyWidthDp = whiteKeyWidthDp,
                    scrollState = scrollState,
                    alignmentOffsetDp = alignmentOffsetDp,
                    blackKeyShiftFraction = blackKeyShiftFraction,
                    showNoteLabels = showNoteLabels,
                    showHorizontalGrid = showHorizontalGrid,
                    showCurve = showCurve,
                    smoothing = smoothing,
                    showWhiteTrace = showWhiteTrace,

                    playbackSamples = playbackSamples,
                    playbackPositionMs = playbackPositionMs
                )
            } else {
                PitchGraphVertical(
                    engine = engine,
                    paused = paused,
                    modifier = Modifier.fillMaxSize(),
                    windowMs = timeWindowMs,
                    startMidi = startMidi,
                    endMidi = endMidi,
                    whiteKeyWidthDp = whiteKeyWidthDp,
                    scrollState = scrollState,
                    alignmentOffsetDp = alignmentOffsetDp,
                    blackKeyShiftFraction = blackKeyShiftFraction,
                    showNoteLabels = showNoteLabels,
                    showHorizontalGrid = showHorizontalGrid,
                    showCurve = showCurve,
                    smoothing = smoothing,
                    showWhiteTrace = showWhiteTrace,

                    playbackSamples = playbackSamples,
                    playbackPositionMs = playbackPositionMs
                )
            }
        }
    }
}

/**
 * Horizontal graph: midi -> x, time -> y (left = older, bottom = latest).
 * Accepts playbackSamples and playbackPositionMs to render recorded traces aligned to piano.
 */
@Composable
fun PitchGraphHorizontal(
    engine: PitchEngine,
    paused: Boolean = false,
    modifier: Modifier = Modifier,
    windowMs: Long = 8000L,
    startMidi: Int = 24,
    endMidi: Int = 84,
    whiteKeyWidthDp: Dp = 56.dp,
    scrollState: ScrollState? = null,
    alignmentOffsetDp: Dp = 0.dp,
    blackKeyShiftFraction: Float = 0.5f,
    showNoteLabels: Boolean = true,
    showHorizontalGrid: Boolean = true,
    showCurve: Boolean = true,
    smoothing: Float = 0.5f,
    showWhiteTrace: Boolean = true,

    // NEW:
    playbackSamples: List<PitchSample>? = null,
    playbackPositionMs: Long? = null
) {
    val samples = remember { mutableStateListOf<GraphPitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    // Collect live pitch samples from engine.state
    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(GraphPitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMs
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    // stable note events
    LaunchedEffect(engine) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            stableMarkers.add(StableMarker(now, sn.midi))
            val cutoff = now - windowMs
            while (stableMarkers.isNotEmpty() && stableMarkers.first().tMs < cutoff) stableMarkers.removeAt(0)
        }
    }

    // paints
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
    }
    val smallPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = 18f
            isAntiAlias = true
        }
    }
    val yellowPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 18f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val whiteCount = (startMidi..endMidi).count { !midiToNoteNameLocal(it).contains("#") }
    val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
    val contentWidthPx = whiteCount * whiteKeyWidthPx
    val contentWidthDp = with(density) { contentWidthPx.toDp() }

    val sState = scrollState ?: rememberScrollState()

    Box(modifier = modifier) {
        Box(modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(sState)
        ) {
            Canvas(modifier = Modifier
                .width(contentWidthDp)
                .fillMaxHeight()
            ) {
                val w = size.width
                val h = size.height

                // background
                drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF081226), Color(0xFF0F2A3F))), size = Size(w, h))

                val padTop = 12f
                val padBottom = 20f
                val padLeft = 6f
                val padRight = 6f
                val innerH = h - padTop - padBottom
                val innerW = w - padLeft - padRight

                val minMidi = startMidi
                val maxMidi = endMidi

                // build key maps
                val whiteList = mutableListOf<Int>()
                for (m in minMidi..maxMidi) if (!midiToNoteNameLocal(m).contains("#")) whiteList.add(m)
                val whiteIndexMap = mutableMapOf<Int, Int>()
                whiteList.forEachIndexed { idx, midi -> whiteIndexMap[midi] = idx }

                val blackLeftIndexMap = mutableMapOf<Int, Int>()
                var whiteIdxCounter = 0
                for (m in minMidi..maxMidi) {
                    val name = midiToNoteNameLocal(m)
                    if (name.contains("#")) {
                        blackLeftIndexMap[m] = maxOf(0, whiteIdxCounter - 1)
                    } else whiteIdxCounter++
                }

                val alignPx = with(density) { alignmentOffsetDp.toPx() }

                // midi -> x map
                val midiCount = maxMidi - minMidi + 1
                val midiX = FloatArray(midiCount)
                for (m in minMidi..maxMidi) {
                    val idx = m - minMidi
                    val x = if (whiteIndexMap.containsKey(m)) {
                        val widx = whiteIndexMap[m]!!
                        padLeft + (widx + 0.5f) * whiteKeyWidthPx + alignPx
                    } else {
                        val left = blackLeftIndexMap[m] ?: 0
                        val center = (left + 0.5f) * whiteKeyWidthPx
                        val shiftPx = whiteKeyWidthPx * blackKeyShiftFraction
                        padLeft + center + shiftPx + alignPx
                    }
                    midiX[idx] = x
                }

                fun xForMidiFloat(midiFloat: Float): Float {
                    if (midiFloat.isNaN()) return -10000f
                    val floorM = midiFloat.toInt().coerceIn(minMidi, maxMidi)
                    val ceilM = (floorM + 1).coerceAtMost(maxMidi)
                    val x0 = midiX[floorM - minMidi]
                    val x1 = midiX[ceilM - minMidi]
                    val frac = midiFloat - floorM
                    return x0 + frac * (x1 - x0)
                }

                // live-sample time anchor (nowLast): last sample tMs if available, else system time
                val nowLast = if (samples.isNotEmpty()) samples.last().tMs else System.currentTimeMillis()

                // helper: map time -> y (aligned with window)
                fun yForTime(tMs: Long): Float {
                    val rel = (tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat()
                    return padTop + rel * innerH
                }

                // build path for live blue curve (time->y, midi->x)
                val bluePath = Path()
                var started = false
                val pointsForWhiteTrace = mutableListOf<Offset>()
                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = yForTime(s.tMs)
                    if (!started) {
                        bluePath.moveTo(x, y); started = true
                    } else {
                        bluePath.lineTo(x, y)
                    }
                    pointsForWhiteTrace.add(Offset(x, y))
                }

                if (showCurve && pointsForWhiteTrace.isNotEmpty()) {
                    val areaPath = Path().apply { addPath(bluePath) }
                    areaPath.lineTo(padLeft + innerW, padTop + innerH)
                    areaPath.lineTo(padLeft, padTop + innerH)
                    areaPath.close()
                    drawPath(path = areaPath, brush = Brush.horizontalGradient(listOf(Color(0x5522B6FF), Color(0x1122B6FF))), style = Fill)
                    drawPath(path = bluePath, color = Color(0xFF7AD3FF), style = Stroke(width = 3f))
                }

                // white trace smoothing
                if (showWhiteTrace && pointsForWhiteTrace.size >= 2) {
                    val smoothedPath = buildSmoothedPath(pointsForWhiteTrace, smoothing.coerceIn(0f, 1f))
                    drawPath(path = smoothedPath, color = Color(0xCCFFFFFF), style = Stroke(width = 2f))
                }

                // live sample points
                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = yForTime(s.tMs)
                    drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
                }

                // vertical (time) grid
                if (showHorizontalGrid) {
                    val rows = 6
                    val step = innerH / rows.toFloat()
                    for (i in 0..rows) {
                        val yy = padTop + i * step
                        drawLine(color = Color(0x2233AAFF), start = Offset(padLeft, yy), end = Offset(padLeft + innerW, yy), strokeWidth = 1f)
                    }
                }

                // stable markers + labels (vertical lines)
                for (m in stableMarkers) {
                    val x = xForMidiFloat(m.midi.toFloat())
                    val y = yForTime(m.tMs)
                    drawLine(color = Color(0xFFFFD54F), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 2f)
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m.midi), x + 6f, y - 10f, yellowPaint) }
                    }
                }

                // latest live label
                if (samples.isNotEmpty()) {
                    val last = samples.last()
                    if (!last.midi.isNaN()) {
                        val nearest = last.midi.toInt().coerceIn(minMidi, maxMidi)
                        val x = xForMidiFloat(nearest.toFloat())
                        val y = yForTime(last.tMs)
                        if (showNoteLabels) {
                            drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(nearest), x + 6f, y + 10f, labelPaint) }
                        }
                    }
                }

                // ------------ PLAYBACK TRACE (if provided) ------------
                if (playbackSamples != null && playbackSamples.isNotEmpty()) {
                    val p = playbackSamples
                    // playback samples expected relative tMs (0..duration). shift so that end aligns with nowLast
                    val recEnd = p.last().tMs
                    val shift = nowLast - recEnd
                    val playNowRaw = playbackPositionMs ?: recEnd
                    val playNowShifted = playNowRaw + shift

                    val visible = p.filter { (it.tMs + shift) <= playNowShifted }

                    if (visible.size >= 1) {
                        val pPath = Path()
                        var pStarted = false
                        val playbackColor = Color(0xFFFFA726)
                        for (s in visible) {
                            val x = xForMidiFloat(s.midi ?: Float.NaN)
                            val y = yForTime(s.tMs + shift)
                            if (!pStarted) { pPath.moveTo(x, y); pStarted = true }
                            else pPath.lineTo(x, y)
                        }
                        // draw path and dots
                        drawPath(path = pPath, color = Color(0xFFFFA726), style = Stroke(width = 3f))
                        for (s in visible) {
                            val x = xForMidiFloat(s.midi ?: Float.NaN)
                            val y = yForTime(s.tMs + shift)
                            drawCircle(color = playbackColor, radius = 3f, center = Offset(x, y))
                        }
                    }

                    // draw horizontal playback cursor (if playing)
                    if (playbackPositionMs != null) {
                        val cursorY = yForTime(playNowShifted)
                        drawLine(color = Color(0xFFFFFF00), start = Offset(padLeft, cursorY), end = Offset(padLeft + innerW, cursorY), strokeWidth = 2f)
                    }
                }
                // -------------------------------------------------------
            }
        }
    }
}

/**
 * Vertical (rotated) graph:
 * - midi -> y (rows align with piano keys; reversed so lower notes at bottom)
 * - time -> x (left to right)
 * Accepts playbackSamples and playbackPositionMs similarly to horizontal graph.
 */
@Composable
fun PitchGraphVertical(
    engine: PitchEngine,
    paused: Boolean = false,
    modifier: Modifier = Modifier,
    windowMs: Long = 8000L,
    startMidi: Int = 24,
    endMidi: Int = 84,
    whiteKeyWidthDp: Dp = 56.dp,
    scrollState: ScrollState? = null,
    alignmentOffsetDp: Dp = 0.dp,
    blackKeyShiftFraction: Float = 0.5f,
    showNoteLabels: Boolean = true,
    showHorizontalGrid: Boolean = true,
    showCurve: Boolean = true,
    smoothing: Float = 0.5f,
    showWhiteTrace: Boolean = true,

    // NEW:
    playbackSamples: List<PitchSample>? = null,
    playbackPositionMs: Long? = null
) {
    val samples = remember { mutableStateListOf<GraphPitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    // collect live state
    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(GraphPitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMs
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    LaunchedEffect(engine) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            stableMarkers.add(StableMarker(now, sn.midi))
            val cutoff = now - windowMs
            while (stableMarkers.isNotEmpty() && stableMarkers.first().tMs < cutoff) stableMarkers.removeAt(0)
        }
    }

    // paints
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
    }
    val smallPaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = 18f
            isAntiAlias = true
        }
    }
    val yellowPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 18f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val whiteCount = (startMidi..endMidi).count { !midiToNoteNameLocal(it).contains("#") }
    val contentPitchDp = whiteKeyWidthDp * whiteCount


    val sState = scrollState ?: rememberScrollState()

    Box(modifier = modifier) {
        Box(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(sState)
        ) {
            Canvas(modifier = Modifier
                .fillMaxWidth()
                .height(contentPitchDp)
            ) {
                val w = size.width
                val h = size.height

                // background
                drawRect(brush = Brush.horizontalGradient(listOf(Color(0xFF081226), Color(0xFF0F2A3F))), size = Size(w, h))

                val padTop = 12f
                val padBottom = 20f
                val padLeft = 6f
                val padRight = 6f
                val innerW = w - padLeft - padRight
                val innerH = h - padTop - padBottom

                val minMidi = startMidi
                val maxMidi = endMidi

                // build midi -> y mapping (lower notes at bottom)
                val midiCount = maxMidi - minMidi + 1
                val midiY = FloatArray(midiCount)
                val stepY = innerH / midiCount.toFloat()
                for (m in minMidi..maxMidi) {
                    val idx = m - minMidi
                    midiY[idx] = padTop + innerH - (idx + 0.5f) * stepY
                }

                fun yForMidiFloat(midiFloat: Float): Float {
                    if (midiFloat.isNaN()) return padTop + innerH / 2f
                    val floorM = midiFloat.toInt().coerceIn(minMidi, maxMidi)
                    val ceilM = (floorM + 1).coerceAtMost(maxMidi)
                    val y0 = midiY[floorM - minMidi]
                    val y1 = midiY[ceilM - minMidi]
                    val frac = midiFloat - floorM
                    return y0 + frac * (y1 - y0)
                }

                // nowLast anchor
                val nowLast = if (samples.isNotEmpty()) samples.last().tMs else System.currentTimeMillis()

                // helper: xForTime
                fun xForTime(tMs: Long): Float {
                    val rel = (tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat()
                    return padLeft + rel * innerW
                }

                // build path for live curve (time->x, midi->y)
                val bluePath = Path()
                var started = false
                val pointsForWhiteTrace = mutableListOf<Offset>()
                for (s in samples) {
                    val x = xForTime(s.tMs)
                    val y = yForMidiFloat(s.midi)
                    if (!started) { bluePath.moveTo(x, y); started = true } else bluePath.lineTo(x, y)
                    pointsForWhiteTrace.add(Offset(x, y))
                }

                if (showCurve && pointsForWhiteTrace.isNotEmpty()) {
                    val areaPath = Path().apply { addPath(bluePath) }
                    areaPath.lineTo(padLeft + innerW, padTop)
                    areaPath.lineTo(padLeft + innerW, padTop + innerH)
                    areaPath.lineTo(padLeft, padTop + innerH)
                    areaPath.close()
                    drawPath(path = areaPath, brush = Brush.verticalGradient(listOf(Color(0x5522B6FF), Color(0x1122B6FF))), style = Fill)
                    drawPath(path = bluePath, color = Color(0xFF7AD3FF), style = Stroke(width = 3f))
                }

                if (showWhiteTrace && pointsForWhiteTrace.size >= 2) {
                    val smoothedPath = buildSmoothedPath(pointsForWhiteTrace, smoothing.coerceIn(0f, 1f))
                    drawPath(path = smoothedPath, color = Color(0xCCFFFFFF), style = Stroke(width = 2f))
                }

                // points
                for (s in samples) {
                    val x = xForTime(s.tMs)
                    val y = yForMidiFloat(s.midi)
                    drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
                }

                // vertical time grid (columns)
                if (showHorizontalGrid) {
                    val cols = 6
                    val step = innerW / cols.toFloat()
                    for (i in 0..cols) {
                        val xx = padLeft + i * step
                        drawLine(color = Color(0x2233AAFF), start = Offset(xx, padTop), end = Offset(xx, padTop + innerH), strokeWidth = 1f)
                    }
                }

                // stable markers (horizontal lines) + labels
                for (m in stableMarkers) {
                    val x = xForTime(m.tMs)
                    val y = midiY[m.midi - minMidi]
                    drawLine(color = Color(0xFFFFD54F), start = Offset(padLeft, y), end = Offset(padLeft + innerW, y), strokeWidth = 2f)
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m.midi), padLeft + 6f, y - 10f, yellowPaint) }
                    }
                }

                // latest label from live
                if (samples.isNotEmpty()) {
                    val last = samples.last()
                    if (!last.midi.isNaN()) {
                        val nearest = last.midi.toInt().coerceIn(minMidi, maxMidi)
                        val x = xForTime(last.tMs)
                        val y = yForMidiFloat(last.midi)
                        if (showNoteLabels) drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(nearest), x + 6f, y + 10f, labelPaint) }
                    }
                }

                // ------------ PLAYBACK TRACE (vertical graph) ------------
                if (playbackSamples != null && playbackSamples.isNotEmpty()) {
                    val p = playbackSamples
                    val recEnd = p.last().tMs
                    val shift = nowLast - recEnd
                    val playNowRaw = playbackPositionMs ?: recEnd
                    val playNowShifted = playNowRaw + shift

                    val visible = p.filter { (it.tMs + shift) <= playNowShifted }

                    if (visible.isNotEmpty()) {
                        val pPath = Path()
                        var pStarted = false
                        for (s in visible) {
                            val x = xForTime(s.tMs + shift)
                            val y = yForMidiFloat(s.midi ?: Float.NaN)
                            if (!pStarted) { pPath.moveTo(x, y); pStarted = true } else pPath.lineTo(x, y)
                        }
                        drawPath(path = pPath, color = Color(0xFFFFA726), style = Stroke(width = 3f))
                        for (s in visible) {
                            val x = xForTime(s.tMs + shift)
                            val y = yForMidiFloat(s.midi ?: Float.NaN)
                            drawCircle(color = Color(0xFFFFA726), radius = 3f, center = Offset(x, y))
                        }
                    }

                    if (playbackPositionMs != null) {
                        val cursorX = xForTime(playNowShifted)
                        drawLine(color = Color(0xFFFFFF00), start = Offset(cursorX, padTop), end = Offset(cursorX, padTop + innerH), strokeWidth = 2f)
                    }
                }
                // ----------------------------------------------------------
            }
        }
    }
}
