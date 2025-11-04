package com.example.vocalpitchdetector

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.nativeCanvas

private data class PitchSample(val tMs: Long, val freq: Float, val midi: Float)
private data class StableMarker(val tMs: Long, val midi: Int)

private fun freqToMidiLocal(f: Double): Double = 69.0 + 12.0 * log2(f / 440.0)
private fun midiToNoteNameLocal(midi: Int): String {
    val names = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val octave = midi / 12 - 1
    return "${names[midi % 12]}$octave"
}

/**
 * High-level card that chooses rotated or horizontal rendering.
 *
 * rotated=false -> original horizontal pitch axis (midi -> x)
 * rotated=true  -> pitch axis vertical (midi -> y), vertical scrolling to match rotated piano.
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
    showCurve: Boolean = true
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
                    showCurve = showCurve
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
                    showCurve = showCurve
                )
            }
        }
    }
}

/**
 * Original-style horizontal graph:
 * - midi -> x (columns line up with piano)
 * - time -> y (latest at bottom)
 * - uses horizontalScroll for panning when needed.
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
    showCurve: Boolean = true
) {
    val samples = remember { mutableStateListOf<PitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    // Collect live pitch samples
    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(PitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMs
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    // stable events
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

                // build maps
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

                // midi -> x
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

                // draw vertical semitone lines and labels
                for (m in minMidi..maxMidi) {
                    val x = midiX[m - minMidi]
                    val isNatural = !midiToNoteNameLocal(m).contains("#")
                    if (isNatural) {
                        drawLine(color = Color(0x33FFFFFF), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 1.6f)
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m), x + 6f, padTop + 18f, smallPaint) }
                    } else {
                        drawLine(color = Color(0x22FFFFFF), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 0.9f)
                    }
                }

                if (samples.isEmpty()) {
                    if (showHorizontalGrid) {
                        val step = innerH / 6f
                        for (i in 0..6) {
                            val yy = padTop + i * step
                            drawLine(color = Color(0x2233AAFF), start = Offset(padLeft, yy), end = Offset(padLeft + innerW, yy), strokeWidth = 1f)
                        }
                    }
                    drawLine(color = Color(0x22FFFFFF), start = Offset(padLeft, padTop + innerH / 2f), end = Offset(padLeft + innerW, padTop + innerH / 2f), strokeWidth = 1f)
                    return@Canvas
                }

                // build path (time -> y, midi -> x)
                val path = Path()
                var started = false
                val nowLast = samples.last().tMs
                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = padTop + innerH * ((s.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }

                if (showCurve) {
                    val areaPath = Path().apply { addPath(path) }
                    areaPath.lineTo(padLeft + innerW, padTop + innerH)
                    areaPath.lineTo(padLeft, padTop + innerH)
                    areaPath.close()
                    drawPath(path = areaPath, brush = Brush.verticalGradient(listOf(Color(0x5522B6FF), Color(0x1122B6FF))), style = Fill)
                    drawPath(path = path, color = Color(0xFF7AD3FF), style = Stroke(width = 3f))
                }

                // points
                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = padTop + innerH * ((s.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
                }

                // horizontal grid = time grid
                if (showHorizontalGrid) {
                    val rows = 6
                    val step = innerH / rows.toFloat()
                    for (i in 0..rows) {
                        val yy = padTop + i * step
                        drawLine(color = Color(0x2233AAFF), start = Offset(padLeft, yy), end = Offset(padLeft + innerW, yy), strokeWidth = 1f)
                    }
                }

                // stable markers + labels
                for (m in stableMarkers) {
                    val x = midiX[m.midi - minMidi]
                    val y = padTop + innerH * ((m.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    drawLine(color = Color(0xFFFFD54F), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 2f)
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m.midi), x + 6f, y - 10f, yellowPaint) }
                    }
                }

                // latest label
                val last = samples.last()
                if (!last.midi.isNaN()) {
                    val nearest = last.midi.toInt().coerceIn(minMidi, maxMidi)
                    val x = midiX[nearest - minMidi]
                    val y = padTop + innerH * ((last.tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat())
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(nearest), x + 6f, y + 10f, labelPaint) }
                    }
                }
            }
        }
    }
}

/**
 * Vertical (rotated) graph:
 * - midi -> y (rows align with piano keys)
 * - time -> x (left to right)
 * - uses verticalScroll for panning so it can share scrollState with a vertical piano.
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
    showCurve: Boolean = true
) {
    val samples = remember { mutableStateListOf<PitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    // Collect samples
    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(PitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMs
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    // stable events
    LaunchedEffect(engine) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            stableMarkers.add(StableMarker(now, sn.midi))
            val cutoff = now - windowMs
            while (stableMarkers.isNotEmpty() && stableMarkers.first().tMs < cutoff) stableMarkers.removeAt(0)
        }
    }

    // paints (reuse same paint objects as horizontal)
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
    val keyThicknessPx = with(density) { whiteKeyWidthDp.toPx() }
    val contentPitchPx = whiteCount * keyThicknessPx
    val contentPitchDp = with(density) { contentPitchPx.toDp() }

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

                val padTop = 12f
                val padBottom = 20f
                val padLeft = 12f
                val padRight = 12f
                val innerW = w - padLeft - padRight
                val innerH = h - padTop - padBottom

                val minMidi = startMidi
                val maxMidi = endMidi

                // build maps
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

                // midi -> y
                val midiCount = maxMidi - minMidi + 1
                val midiY = FloatArray(midiCount)
                for (m in minMidi..maxMidi) {
                    val idx = m - minMidi
                    val y = if (whiteIndexMap.containsKey(m)) {
                        val widx = whiteIndexMap[m]!!
                        padTop + (widx + 0.5f) * keyThicknessPx + alignPx
                    } else {
                        val left = blackLeftIndexMap[m] ?: 0
                        val center = (left + 0.5f) * keyThicknessPx
                        val shiftPx = keyThicknessPx * blackKeyShiftFraction
                        padTop + center + shiftPx + alignPx
                    }
                    midiY[idx] = y
                }

                fun yForMidiFloat(midiFloat: Float): Float {
                    if (midiFloat.isNaN()) return -10000f
                    val floorM = midiFloat.toInt().coerceIn(minMidi, maxMidi)
                    val ceilM = (floorM + 1).coerceAtMost(maxMidi)
                    val y0 = midiY[floorM - minMidi]
                    val y1 = midiY[ceilM - minMidi]
                    val frac = midiFloat - floorM
                    return y0 + frac * (y1 - y0)
                }

                // background
                drawRect(brush = Brush.horizontalGradient(listOf(Color(0xFF081226), Color(0xFF0F2A3F))), size = Size(w, h))

                // draw horizontal pitch lines
                for (m in minMidi..maxMidi) {
                    val y = midiY[m - minMidi]
                    val isNatural = !midiToNoteNameLocal(m).contains("#")
                    val col = if (isNatural) Color(0x33FFFFFF) else Color(0x22FFFFFF)
                    drawLine(color = col, start = Offset(padLeft, y), end = Offset(padLeft + innerW, y), strokeWidth = if (isNatural) 1.6f else 0.9f)
                    if (isNatural) drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m), padLeft + 6f, y - 6f, smallPaint) }
                }

                if (samples.isEmpty()) {
                    if (showHorizontalGrid) {
                        val step = innerW / 6f
                        for (i in 0..6) {
                            val xx = padLeft + i * step
                            drawLine(color = Color(0x2233AAFF), start = Offset(xx, padTop), end = Offset(xx, padTop + innerH), strokeWidth = 1f)
                        }
                    }
                    return@Canvas
                }

                // time -> x
                val nowLast = samples.last().tMs
                fun xForTime(tMs: Long): Float {
                    val rel = (tMs - (nowLast - windowMs)).toFloat() / windowMs.toFloat()
                    return padLeft + rel * innerW
                }

                // path
                val path = Path()
                var started = false
                for (s in samples) {
                    val x = xForTime(s.tMs)
                    val y = yForMidiFloat(s.midi)
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }

                if (showCurve) {
                    val areaPath = Path().apply { addPath(path) }
                    areaPath.lineTo(padLeft + innerW, padTop + innerH)
                    areaPath.lineTo(padLeft, padTop + innerH)
                    areaPath.close()
                    drawPath(path = areaPath, brush = Brush.horizontalGradient(listOf(Color(0x5522B6FF), Color(0x1122B6FF))), style = Fill)
                    drawPath(path = path, color = Color(0xFF7AD3FF), style = Stroke(width = 3f))
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

                // stable markers and label placement
                for (m in stableMarkers) {
                    val x = xForTime(m.tMs)
                    val y = midiY[m.midi - minMidi]
                    drawLine(color = Color(0xFFFFD54F), start = Offset(x, padTop), end = Offset(x, padTop + innerH), strokeWidth = 2f)
                    if (showNoteLabels) drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m.midi), x + 6f, y - 10f, yellowPaint) }
                }

                // latest sample label
                val last = samples.last()
                if (!last.midi.isNaN()) {
                    val nearest = last.midi.toInt().coerceIn(minMidi, maxMidi)
                    val x = xForTime(last.tMs)
                    val y = yForMidiFloat(last.midi)
                    if (showNoteLabels) drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(nearest), x + 6f, y + 10f, labelPaint) }
                }
            }
        }
    }
}
