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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.log2
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

private data class PitchSample(val tMs: Long, val freq: Float, val midi: Float)
private data class StableMarker(val tMs: Long, val midi: Int)

private fun freqToMidiLocal(f: Double): Double = 69.0 + 12.0 * log2(f / 440.0)
private fun midiToNoteNameLocal(midi: Int): String {
    val names = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val octave = midi / 12 - 1
    return "${names[midi % 12]}$octave"
}

private fun buildSmoothedPath(points: List<Offset>, smoothing: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) { path.moveTo(points[0].x, points[0].y); return path }
    if (smoothing <= 0.001f) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    val t = smoothing
    val factor = t / 6f

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
    bpm: Float = 120f,
    rotated: Boolean = false,
    showNoteLabels: Boolean = true,
    showHorizontalGrid: Boolean = true,
    showCurve: Boolean = true,
    smoothing: Float = 0.5f,
    showWhiteTrace: Boolean = true,
    showBars: Boolean = false,
    showWhiteDots: Boolean = true // <-- NEW
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
                    bpm = bpm,
                    showBars = showBars,
                    showWhiteDots = showWhiteDots // forwarded
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
                    bpm = bpm,
                    showBars = showBars,
                    showWhiteDots = showWhiteDots // forwarded
                )
            }
        }
    }
}

/**
 * Horizontal graph: midi -> x, time -> y
 * Time flows downwards (Top = Old, Bottom = New)
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
    bpm: Float = 120f,
    showBars: Boolean = false,
    showWhiteDots: Boolean = true

) {
    val samples = remember { mutableStateListOf<PitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    val windowMsEffective = remember(windowMs, bpm) {
        (windowMs.toFloat() * (60f / bpm)).toLong()
    }

    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(PitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMsEffective
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    LaunchedEffect(engine) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            stableMarkers.add(StableMarker(now, sn.midi))
            val cutoff = now - windowMsEffective
            while (stableMarkers.isNotEmpty() && stableMarkers.first().tMs < cutoff) stableMarkers.removeAt(0)
        }
    }

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

    // Define colors for the graph background
    val bgTopColor = Color(0xFF081226)
    val bgBottomColor = Color(0xFF0F2A3F)

    // Halo color for dots (semi-transparent dark)
    val haloColor = Color(0x88000000)
    val dotWhite = Color.White
    // Horizontal bar color: soft translucent blue (matches curve)
    val horizontalBarColor = Color(0xCC42A5F5)

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
                drawRect(brush = Brush.verticalGradient(listOf(bgTopColor, bgBottomColor)), size = Size(w, h))

                val padTop = 12f
                val padBottom = 20f
                val padLeft = 6f
                val padRight = 6f
                val innerH = h - padTop - padBottom
                val innerW = w - padLeft - padRight

                val minMidi = startMidi
                val maxMidi = endMidi

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

                // Grid and Semitone Lines
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

                if (showHorizontalGrid) {
                    val step = innerH / 6f
                    for (i in 0..6) {
                        val yy = padTop + i * step
                        drawLine(color = Color(0x2233AAFF), start = Offset(padLeft, yy), end = Offset(padLeft + innerW, yy), strokeWidth = 1f)
                    }
                }
                drawLine(color = Color(0x22FFFFFF), start = Offset(padLeft, padTop + innerH / 2f), end = Offset(padLeft + innerW, padTop + innerH / 2f), strokeWidth = 1f)

                if (samples.isEmpty()) return@Canvas

                // --- FIX 1: Time Anchoring ---
                val nowTime = if (paused) samples.last().tMs else System.currentTimeMillis()
                val windowStart = nowTime - windowMsEffective

                // build path (time -> y, midi -> x)
                val bluePath = Path()
                var started = false
                // We'll collect segments of continuous points so smoothing doesn't bridge silences/gaps
                val pointsSegments = mutableListOf<MutableList<Offset>>()
                var currentSeg: MutableList<Offset>? = null
                var prevPoint: Offset? = null
                var prevTime: Long = Long.MIN_VALUE
                val breakDistance = innerH * 0.35f
                val silenceGapMs = 150L

                for (s in samples) {
                    val x = xForMidiFloat(s.midi)
                    val y = padTop + innerH * ((s.tMs - windowStart).toFloat() / windowMsEffective.toFloat())

                    // treat NaN midi as a break in the trace
                    if (s.midi.isNaN()) {
                        prevPoint = null
                        currentSeg = null
                        started = false
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    // Optimization: Skip drawing if points are off-screen (above the top)
                    if (y < padTop - 50) {
                        prevPoint = null
                        currentSeg = null
                        started = false
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    val p = Offset(x, y)
                    val t = s.tMs

                    if (prevPoint == null) {
                        bluePath.moveTo(p.x, p.y)
                        started = true
                        currentSeg = mutableListOf()
                        pointsSegments.add(currentSeg)
                        currentSeg.add(p)
                    } else {
                        val dx = kotlin.math.abs(p.x - prevPoint.x)
                        val dy = kotlin.math.abs(p.y - prevPoint.y)
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val timeGap = if (prevTime == Long.MIN_VALUE) 0L else t - prevTime

                        if (dist > breakDistance || timeGap > silenceGapMs) {
                            // gap detected — start a new segment
                            bluePath.moveTo(p.x, p.y)
                            currentSeg = mutableListOf()
                            pointsSegments.add(currentSeg)
                            currentSeg.add(p)
                        } else {
                            bluePath.lineTo(p.x, p.y)
                            currentSeg?.add(p)
                        }
                    }

                    prevPoint = p
                    prevTime = t
                }

                // Build smoothed paths for each continuous segment
                val smoothedPaths = mutableListOf<Path>()
                for (seg in pointsSegments) {
                    if (seg.size >= 2) smoothedPaths.add(buildSmoothedPath(seg, smoothing.coerceIn(0f, 1f)))
                }

                if (showCurve) {
                    if (smoothedPaths.isNotEmpty()) {
                        // draw each smoothed segment separately so we don't bridge silences/gaps
                        for (segPath in smoothedPaths) {
                            drawPath(path = segPath, color = Color(0xFF7AD3FF), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    } else {
                        // fallback: draw the raw polyline stroke only
                        drawPath(path = bluePath, color = Color(0xFF7AD3FF), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }

                if (showWhiteTrace && smoothedPaths.isNotEmpty()) {
                    // draw white trace segments on top of the blue stroke
                    for (segPath in smoothedPaths) {
                        drawPath(path = segPath, color = Color(0xCCFFFFFF), style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }

                // Bar visual parameters
                val barWidth = (whiteKeyWidthPx * 0.12f).coerceAtLeast(4f).coerceAtMost(18f)
                val barCorner = CornerRadius(3f, 3f)
                val minBarLenPx = 6f
                val barColor = horizontalBarColor

                if (showBars) {
                    // draw bars (group consecutive same-integer-midi runs)
                    var i = 0
                    while (i < samples.size) {
                        val s0 = samples[i]
                        if (s0.midi.isNaN()) { i++; continue }
                        val midiInt = s0.midi.toInt()
                        var j = i + 1
                        while (j < samples.size) {
                            val sj = samples[j]
                            if (sj.midi.isNaN()) break
                            if (sj.midi.toInt() != midiInt) break
                            j++
                        }

                        val runStart = samples[i].tMs.coerceAtLeast(windowStart)
                        val runEnd = samples[j - 1].tMs.coerceAtMost(nowTime)

                        val yStart = padTop + innerH * ((runStart - windowStart).toFloat() / windowMsEffective.toFloat())
                        val yEnd = padTop + innerH * ((runEnd - windowStart).toFloat() / windowMsEffective.toFloat())

                        if (yEnd > padTop) {
                            var heightPx = (yEnd - yStart).coerceAtLeast(minBarLenPx)

                            // Snap xCenter to the integer MIDI value
                            val xCenter = xForMidiFloat(midiInt.toFloat())

                            val top = if (heightPx <= minBarLenPx) ( (yStart + yEnd)/2f - minBarLenPx/2f ) else yStart
                            val size = Size(barWidth, heightPx)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(xCenter - barWidth / 2f, top),
                                size = size,
                                cornerRadius = barCorner
                            )
                        }
                        i = j
                    }

                    // Draw small white dots on top of bars if the user requested them (halo + white)
                    if (showWhiteDots) {
                        for (s in samples) {
                            if (s.midi.isNaN()) continue
                            val x = xForMidiFloat(s.midi)
                            val y = padTop + innerH * ((s.tMs - windowStart).toFloat() / windowMsEffective.toFloat())
                            if (y > padTop && x >= padLeft && x <= padLeft + innerW) {
                                // halo below dot
                                drawCircle(color = haloColor, radius = 4.2f, center = Offset(x, y))
                                drawCircle(color = dotWhite, radius = 2.6f, center = Offset(x, y))
                            }
                        }
                    }
                } else {
                    // no bars: draw dots if requested (halo + white)
                    if (showWhiteDots) {
                        for (s in samples) {
                            if (s.midi.isNaN()) continue
                            val x = xForMidiFloat(s.midi)
                            val y = padTop + innerH * ((s.tMs - windowStart).toFloat() / windowMsEffective.toFloat())
                            if (y > padTop) {
                                drawCircle(color = haloColor, radius = 4.2f, center = Offset(x, y))
                                drawCircle(color = dotWhite, radius = 2.6f, center = Offset(x, y))
                            }
                        }
                    }
                }

                // stable markers
                for (m in stableMarkers) {
                    val x = midiX[m.midi - minMidi]
                    val y = padTop + innerH * ((m.tMs - windowStart).toFloat() / windowMsEffective.toFloat())
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
                    val y = padTop + innerH * ((last.tMs - windowStart).toFloat() / windowMsEffective.toFloat())
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(nearest), x + 6f, y + 10f, labelPaint) }
                    }
                }

                // --- FIX 3: Fading Exit Effect ---
                val fadeHeight = innerH * 0.15f // Top 15% fades out
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(bgTopColor, Color.Transparent),
                        startY = padTop,
                        endY = padTop + fadeHeight,
                        tileMode = TileMode.Clamp
                    ),
                    topLeft = Offset(0f, padTop),
                    size = Size(w, fadeHeight)
                )
            }
        }
    }
}

/**
 * Vertical (rotated) graph:
 * - midi -> y (rows align with piano keys; reversed so lower notes at bottom)
 * - time -> x (left to right)
 * Time flows Rightwards (Left = Old, Right = New)
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
    bpm: Float = 120f,
    showBars: Boolean = false,
    showWhiteDots: Boolean = true // <-- NEW
) {
    val samples = remember { mutableStateListOf<PitchSample>() }
    val stableMarkers = remember { mutableStateListOf<StableMarker>() }
    val density = LocalDensity.current

    val windowMsEffective = remember(windowMs, bpm) {
        (windowMs.toFloat() * (60f / bpm)).toLong()
    }

    LaunchedEffect(engine, paused) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midiF = if (s.frequency > 0f) freqToMidiLocal(s.frequency.toDouble()).toFloat() else Float.NaN
                samples.add(PitchSample(tMs = t, freq = s.frequency, midi = midiF))
                val cutoff = t - windowMsEffective
                while (samples.isNotEmpty() && samples.first().tMs < cutoff) samples.removeAt(0)
            }
        }
    }

    LaunchedEffect(engine) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            stableMarkers.add(StableMarker(now, sn.midi))
            val cutoff = now - windowMsEffective
            while (stableMarkers.isNotEmpty() && stableMarkers.first().tMs < cutoff) stableMarkers.removeAt(0)
        }
    }

    // paints for labels
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

    val bgLeftColor = Color(0xFF081226)
    val bgRightColor = Color(0xFF0F2A3F)

    // Halo color and white dot color
    val haloColor = Color(0x88000000)
    val dotWhite = Color.White
    // Vertical bar color: soft translucent coral/salmon
    val verticalBarColor = Color(0xCCEF9A9A)

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

                val whiteList = mutableListOf<Int>()
                for (m in minMidi..maxMidi) if (!midiToNoteNameLocal(m).contains("#")) whiteList.add(m)
                val whiteIndexMap = mutableMapOf<Int, Int>()
                whiteList.forEachIndexed { idx, midi -> whiteIndexMap[midi] = (whiteCount - 1 - idx) }

                val blackLeftIndexMap = mutableMapOf<Int, Int>()
                var whiteIdxCounter = 0
                for (m in minMidi..maxMidi) {
                    val name = midiToNoteNameLocal(m)
                    if (name.contains("#")) {
                        blackLeftIndexMap[m] = maxOf(0, whiteIdxCounter - 1)
                    } else whiteIdxCounter++
                }

                val alignPx = with(density) { alignmentOffsetDp.toPx() }

                val midiCount = maxMidi - minMidi + 1
                val midiY = FloatArray(midiCount)
                for (m in minMidi..maxMidi) {
                    val idx = m - minMidi
                    val y = if (whiteIndexMap.containsKey(m)) {
                        val widx = whiteIndexMap[m]!!
                        padTop + (widx + 0.5f) * keyThicknessPx + alignPx
                    } else {
                        val left = blackLeftIndexMap[m] ?: 0
                        val reversedLeft = whiteCount - 1 - left
                        val center = (reversedLeft + 0.5f) * keyThicknessPx
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
                drawRect(brush = Brush.horizontalGradient(listOf(bgLeftColor, bgRightColor)), size = Size(w, h))

                // draw pitch lines
                for (m in minMidi..maxMidi) {
                    val y = midiY[m - minMidi]
                    val isNatural = !midiToNoteNameLocal(m).contains("#")
                    val col = if (isNatural) Color(0x33FFFFFF) else Color(0x22FFFFFF)
                    drawLine(color = col, start = Offset(padLeft, y), end = Offset(padLeft + innerW, y), strokeWidth = if (isNatural) 1.6f else 0.9f)
                    if (isNatural) drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(midiToNoteNameLocal(m), padLeft + 6f, y - 6f, smallPaint) }
                }

                if (showHorizontalGrid) {
                    val step = innerW / 6f
                    for (i in 0..6) {
                        val xx = padLeft + i * step
                        drawLine(color = Color(0x2233AAFF), start = Offset(xx, padTop), end = Offset(xx, padTop + innerH), strokeWidth = 1f)
                    }
                }

                if (samples.isEmpty()) return@Canvas

                val nowTime = if (paused) samples.last().tMs else System.currentTimeMillis()
                val windowStart = nowTime - windowMsEffective

                // time -> x
                fun xForTime(tMs: Long): Float {
                    val rel = (tMs - windowStart).toFloat() / windowMsEffective.toFloat()
                    return padLeft + rel * innerW
                }

                // build path
                val bluePath = Path()
                var started = false
                // We'll collect segments of continuous points so smoothing doesn't bridge silences/gaps
                val pointsSegments = mutableListOf<MutableList<Offset>>()
                var currentSeg: MutableList<Offset>? = null
                var prevPoint: Offset? = null
                var prevTime: Long = Long.MIN_VALUE
                val breakDistance = innerW * 0.35f
                val silenceGapMs = 150L

                for (s in samples) {
                    val x = xForTime(s.tMs)
                    val y = yForMidiFloat(s.midi)

                    // treat NaN midi as a break in the trace
                    if (s.midi.isNaN()) {
                        prevPoint = null
                        currentSeg = null
                        started = false
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    if (x < padLeft - 50) {
                        prevPoint = null
                        currentSeg = null
                        started = false
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    val p = Offset(x, y)
                    val t = s.tMs

                    if (prevPoint == null) {
                        bluePath.moveTo(p.x, p.y)
                        started = true
                        currentSeg = mutableListOf()
                        pointsSegments.add(currentSeg)
                        currentSeg.add(p)
                    } else {
                        val dx = kotlin.math.abs(p.x - prevPoint.x)
                        val dy = kotlin.math.abs(p.y - prevPoint.y)
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val timeGap = if (prevTime == Long.MIN_VALUE) 0L else t - prevTime

                        if (dist > breakDistance || timeGap > silenceGapMs) {
                            bluePath.moveTo(p.x, p.y)
                            currentSeg = mutableListOf()
                            pointsSegments.add(currentSeg)
                            currentSeg.add(p)
                        } else {
                            bluePath.lineTo(p.x, p.y)
                            currentSeg?.add(p)
                        }
                    }

                    prevPoint = p
                    prevTime = t
                }

                // Build smoothed paths for each continuous segment
                val smoothedPaths = mutableListOf<Path>()
                for (seg in pointsSegments) {
                    if (seg.size >= 2) smoothedPaths.add(buildSmoothedPath(seg, smoothing.coerceIn(0f, 1f)))
                }

                if (showCurve) {
                    if (smoothedPaths.isNotEmpty()) {
                        // draw each smoothed segment separately so we don't bridge silences/gaps
                        for (segPath in smoothedPaths) {
                            drawPath(path = segPath, color = Color(0xFF7AD3FF), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    } else {
                        // fallback: draw the raw polyline stroke only
                        drawPath(path = bluePath, color = Color(0xFF7AD3FF), style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }

                if (showWhiteTrace && smoothedPaths.isNotEmpty()) {
                    // draw white trace segments on top of the blue stroke
                    for (segPath in smoothedPaths) {
                        drawPath(path = segPath, color = Color(0xCCFFFFFF), style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }

                val barHeight = (keyThicknessPx * 0.12f).coerceAtLeast(4f).coerceAtMost(18f)
                val barCorner = CornerRadius(3f, 3f)
                val minBarLenPx = 6f

                // Vertical bar color
                val barColor = verticalBarColor

                if (showBars) {
                    // draw bars grouped by integer MIDI runs
                    var i = 0
                    while (i < samples.size) {
                        val s0 = samples[i]
                        if (s0.midi.isNaN()) { i++; continue }
                        val midiInt = s0.midi.toInt()
                        var j = i + 1
                        while (j < samples.size) {
                            val sj = samples[j]
                            if (sj.midi.isNaN()) break
                            if (sj.midi.toInt() != midiInt) break
                            j++
                        }

                        val runStart = samples[i].tMs.coerceAtLeast(windowStart)
                        val runEnd = samples[j - 1].tMs.coerceAtMost(nowTime)

                        val xStart = xForTime(runStart)
                        val xEnd = xForTime(runEnd)

                        if (xEnd > padLeft) {
                            var widthPx = (xEnd - xStart).coerceAtLeast(minBarLenPx)
                            val yCenter = yForMidiFloat(midiInt.toFloat())

                            val left = if (widthPx <= minBarLenPx) ( (xStart + xEnd)/2f - minBarLenPx/2f ) else xStart
                            val size = Size(widthPx, barHeight)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(left, yCenter - barHeight / 2f),
                                size = size,
                                cornerRadius = barCorner
                            )
                        }
                        i = j
                    }

                    // draw sample dots on top if requested (halo + white)
                    if (showWhiteDots) {
                        for (s in samples) {
                            if (s.midi.isNaN()) continue
                            val x = xForTime(s.tMs)
                            val y = yForMidiFloat(s.midi)
                            if (x > padLeft && y >= padTop && y <= padTop + innerH) {
                                drawCircle(color = haloColor, radius = 4.2f, center = Offset(x, y))
                                drawCircle(color = dotWhite, radius = 2.6f, center = Offset(x, y))
                            }
                        }
                    }
                } else {
                    // no bars: draw sample dots if requested (halo + white)
                    if (showWhiteDots) {
                        for (s in samples) {
                            if (s.midi.isNaN()) continue
                            val x = xForTime(s.tMs)
                            val y = yForMidiFloat(s.midi)
                            if (x > padLeft && y >= padTop && y <= padTop + innerH) {
                                drawCircle(color = haloColor, radius = 4.2f, center = Offset(x, y))
                                drawCircle(color = dotWhite, radius = 2.6f, center = Offset(x, y))
                            }
                        }
                    }
                }

                // --- Stable markers for Vertical Graph ---
                for (m in stableMarkers) {
                    val y = midiY[m.midi - minMidi]
                    val x = xForTime(m.tMs)
                    drawLine(color = Color(0xFFFFD54F), start = Offset(padLeft, y), end = Offset(padLeft + innerW, y), strokeWidth = 2f)
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas ->
                            val noteName = midiToNoteNameLocal(m.midi)
                            val textWidth = yellowPaint.measureText(noteName)
                            var labelX = x + 8f
                            val labelY = y - 10f
                            val rightEdge = padLeft + innerW
                            if (labelX + textWidth > rightEdge - 6f) labelX = x - textWidth - 8f
                            if (labelX < padLeft + 6f) labelX = padLeft + 6f
                            canvas.nativeCanvas.drawText(noteName, labelX, labelY, yellowPaint)
                        }
                    }
                }

                // latest note label (snapped to integer MIDI), positioned near right edge
                if (showNoteLabels) {
                    val lastSample = samples.lastOrNull()
                    if (lastSample != null && !lastSample.midi.isNaN()) {
                        val nearest = lastSample.midi.roundToInt().coerceIn(minMidi, maxMidi)
                        val y = midiY[nearest - minMidi]
                        val noteName = midiToNoteNameLocal(nearest)
                        val textWidth = labelPaint.measureText(noteName)
                        val rightEdge = padLeft + innerW
                        var labelX = rightEdge - textWidth - 8f
                        labelX = labelX.coerceAtLeast(padLeft + 6f)
                        val labelY = y - 8f // Positioned slightly above the snapped horizontal line
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(noteName, labelX, labelY, labelPaint)
                        }
                    }
                }

                // Fade exit
                val fadeWidth = innerW * 0.15f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(bgLeftColor, Color.Transparent),
                        startX = padLeft,
                        endX = padLeft + fadeWidth,
                        tileMode = TileMode.Clamp
                    ),
                    topLeft = Offset(padLeft, 0f),
                    size = Size(fadeWidth, h)
                )
            }
        }
    }
}
