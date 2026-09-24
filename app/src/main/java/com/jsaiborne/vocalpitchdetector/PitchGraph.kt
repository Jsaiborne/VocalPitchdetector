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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

private data class PitchSample(val tMs: Long, val freq: Float, val midi: Float)
private data class StableMarker(val tMs: Long, val midi: Int)

/**
 * Rolling window of recent pitch samples and stable-note markers. Backed by ArrayDeques so
 * trimming the oldest entries is O(1). [version] changes whenever the contents do; a Canvas that
 * calls [observeChanges] therefore redraws when new data arrives.
 */
@Stable
private class TraceBuffer {
    val samples = ArrayDeque<PitchSample>()
    val markers = ArrayDeque<StableMarker>()
    private var version by mutableLongStateOf(0L)

    fun observeChanges(): Long = version

    fun addSample(sample: PitchSample, cutoffMs: Long) {
        samples.addLast(sample)
        while (samples.isNotEmpty() && samples.first().tMs < cutoffMs) samples.removeFirst()
        version++
    }

    fun addMarker(marker: StableMarker, cutoffMs: Long) {
        markers.addLast(marker)
        while (markers.isNotEmpty() && markers.first().tMs < cutoffMs) markers.removeFirst()
        version++
    }
}

@Composable
private fun rememberTraceBuffer(engine: PitchEngine, paused: Boolean, windowMs: Long): TraceBuffer {
    val buffer = remember { TraceBuffer() }

    LaunchedEffect(engine, paused, windowMs) {
        engine.state.collectLatest { s ->
            if (!paused) {
                val t = System.currentTimeMillis()
                val midi = if (s.frequency > 0f) freqToMidi(s.frequency.toDouble()).toFloat() else Float.NaN
                buffer.addSample(PitchSample(tMs = t, freq = s.frequency, midi = midi), cutoffMs = t - windowMs)
            }
        }
    }

    LaunchedEffect(engine, windowMs) {
        engine.stableNotes.collectLatest { sn ->
            val now = System.currentTimeMillis()
            buffer.addMarker(StableMarker(now, sn.midi), cutoffMs = now - windowMs)
        }
    }

    return buffer
}

@Suppress("LongParameterList")
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
        Box(
            modifier = Modifier
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
    val density = LocalDensity.current

    val windowMsEffective = remember(windowMs, bpm) {
        (windowMs.toFloat() * (60f / bpm)).toLong()
    }

    val buffer = rememberTraceBuffer(engine, paused, windowMsEffective)
    val samples = buffer.samples
    val stableMarkers = buffer.markers
    val paints = rememberGraphPaints()

    val whiteCount = (startMidi..endMidi).count { !midiToNoteName(it).contains("#") }
    val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }
    val contentWidthPx = whiteCount * whiteKeyWidthPx
    val contentWidthDp = with(density) { contentWidthPx.toDp() }

    val padLeft = 6f
    val alignPx = with(density) { alignmentOffsetDp.toPx() }

    val midiX = remember(startMidi, endMidi, whiteKeyWidthPx, alignPx, blackKeyShiftFraction) {
        val count = endMidi - startMidi + 1
        val array = FloatArray(count)

        val whiteList = mutableListOf<Int>()
        for (m in startMidi..endMidi) if (!midiToNoteName(m).contains("#")) whiteList.add(m)
        val whiteIndexMap = mutableMapOf<Int, Int>()
        whiteList.forEachIndexed { idx, midi -> whiteIndexMap[midi] = idx }

        val blackLeftIndexMap = mutableMapOf<Int, Int>()
        var whiteIdxCounter = 0
        for (m in startMidi..endMidi) {
            val name = midiToNoteName(m)
            if (name.contains("#")) {
                blackLeftIndexMap[m] = maxOf(0, whiteIdxCounter - 1)
            } else {
                whiteIdxCounter++
            }
        }

        for (m in startMidi..endMidi) {
            val idx = m - startMidi
            val x = if (whiteIndexMap.containsKey(m)) {
                val widx = whiteIndexMap[m]!!
                padLeft + (widx + 0.5f) * whiteKeyWidthPx + alignPx
            } else {
                val left = blackLeftIndexMap[m] ?: 0
                val center = (left + 0.5f) * whiteKeyWidthPx
                val shiftPx = whiteKeyWidthPx * blackKeyShiftFraction
                padLeft + center + shiftPx + alignPx
            }
            array[idx] = x
        }
        array
    }

    val sState = scrollState ?: rememberScrollState()

    // Define colors for the graph background
    val bgTopColor = GraphStyle.backgroundTop
    val bgBottomColor = GraphStyle.backgroundBottom

    // Halo color for dots (semi-transparent dark)
    val haloColor = GraphStyle.dotHalo
    val dotWhite = GraphStyle.dot
    // Horizontal bar color: soft translucent blue (matches curve)
    val horizontalBarColor = GraphStyle.bar

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(sState)
        ) {
            Canvas(
                modifier = Modifier
                    .width(contentWidthDp)
                    .fillMaxHeight()
            ) {
                buffer.observeChanges()
                val w = size.width
                val h = size.height

                // background
                drawRect(brush = Brush.verticalGradient(listOf(bgTopColor, bgBottomColor)), size = Size(w, h))

                val padTop = 12f
                val padBottom = 20f
                val padRight = 6f
                val innerH = h - padTop - padBottom
                val innerW = w - padLeft - padRight

                val minMidi = startMidi
                val maxMidi = endMidi

                fun xForMidiFloat(midiFloat: Float): Float {
                    if (midiFloat.isNaN()) return -10000f

                    val floorM = midiFloat.toInt().coerceIn(minMidi, maxMidi)
                    val ceilM = (floorM + 1).coerceAtMost(maxMidi)

                    // FIX: Safely clamp the indices to ensure they never exceed the midiX array bounds
                    val floorIndex = (floorM - minMidi).coerceIn(0, midiX.lastIndex)
                    val ceilIndex = (ceilM - minMidi).coerceIn(0, midiX.lastIndex)

                    val x0 = midiX[floorIndex]
                    val x1 = midiX[ceilIndex]
                    val frac = midiFloat - floorM

                    return x0 + frac * (x1 - x0)
                }

                // Grid and Semitone Lines
                for (m in minMidi..maxMidi) {
                    // FIX: Clamp the index for X grid lines
                    val targetIndex = (m - minMidi).coerceIn(0, midiX.lastIndex)
                    val x = midiX[targetIndex]
                    val isNatural = !midiToNoteName(m).contains("#")
                    if (isNatural) {
                        drawLine(
                            color = Color(0x33FFFFFF),
                            start = Offset(x, padTop),
                            end = Offset(x, padTop + innerH),
                            strokeWidth = 1.6f
                        )
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                midiToNoteName(m),
                                x + 6f,
                                padTop + 18f,
                                paints.small
                            )
                        }
                    } else {
                        drawLine(
                            color = Color(0x22FFFFFF),
                            start = Offset(x, padTop),
                            end = Offset(x, padTop + innerH),
                            strokeWidth = 0.9f
                        )
                    }
                }

                if (showHorizontalGrid) {
                    val step = innerH / 6f
                    for (i in 0..6) {
                        val yy = padTop + i * step
                        drawLine(
                            color = GraphStyle.pitchGrid,
                            start = Offset(padLeft, yy),
                            end = Offset(padLeft + innerW, yy),
                            strokeWidth = 1f
                        )
                    }
                }
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(padLeft, padTop + innerH / 2f),
                    end = Offset(padLeft + innerW, padTop + innerH / 2f),
                    strokeWidth = 1f
                )

                if (samples.isEmpty()) return@Canvas

                // --- FIX 1: Time Anchoring ---
                val nowTime = if (paused) samples.last().tMs else System.currentTimeMillis()
                val windowStart = nowTime - windowMsEffective

                // build path (time -> y, midi -> x)
                val bluePath = Path()
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
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    // Optimization: Skip drawing if points are off-screen (above the top)
                    if (y < padTop - 50) {
                        prevPoint = null
                        currentSeg = null
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    val p = Offset(x, y)
                    val t = s.tMs

                    if (prevPoint == null) {
                        bluePath.moveTo(p.x, p.y)
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
                            drawPath(
                                path = segPath,
                                color = GraphStyle.curve,
                                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    } else {
                        // fallback: draw the raw polyline stroke only
                        drawPath(
                            path = bluePath,
                            color = GraphStyle.curve,
                            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }

                if (showWhiteTrace && smoothedPaths.isNotEmpty()) {
                    // draw white trace segments on top of the blue stroke
                    for (segPath in smoothedPaths) {
                        drawPath(
                            path = segPath,
                            color = GraphStyle.whiteTrace,
                            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
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
                        if (s0.midi.isNaN()) {
                            i++; continue
                        }
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

                        val yStart =
                            padTop + innerH * ((runStart - windowStart).toFloat() / windowMsEffective.toFloat())
                        val yEnd = padTop + innerH * ((runEnd - windowStart).toFloat() / windowMsEffective.toFloat())

                        if (yEnd > padTop) {
                            var heightPx = (yEnd - yStart).coerceAtLeast(minBarLenPx)

                            // Snap xCenter to the integer MIDI value
                            val xCenter = xForMidiFloat(midiInt.toFloat())

                            val top = if (heightPx <= minBarLenPx) ((yStart + yEnd) / 2f - minBarLenPx / 2f) else yStart
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
                    // FIX: Clamp marker X position
                    val targetIndex = (m.midi - minMidi).coerceIn(0, midiX.lastIndex)
                    val x = midiX[targetIndex]
                    val y = padTop + innerH * ((m.tMs - windowStart).toFloat() / windowMsEffective.toFloat())
                    drawLine(
                        color = GraphStyle.stableMarker,
                        start = Offset(x, padTop),
                        end = Offset(x, padTop + innerH),
                        strokeWidth = 2f
                    )
                    if (showNoteLabels) {
                        val labelY = y - 10f
                        // FIX: Check boundary so labels disappear at the top edge, just like the curve
                        if (labelY > padTop) {
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(
                                    midiToNoteName(m.midi),
                                    x + 6f,
                                    labelY,
                                    paints.yellow
                                )
                            }
                        }
                    }
                }

                // latest label
                val last = samples.last()
                if (!last.midi.isNaN()) {
                    // FIX 1: Use roundToInt() instead of toInt() to stop the C4 -> B flickering
                    val nearest = last.midi.roundToInt().coerceIn(minMidi, maxMidi)

                    // FIX 2: Safely clamp the index to prevent the OutOfBounds crash
                    val targetIndex = (nearest - minMidi).coerceIn(0, midiX.lastIndex)
                    val x = midiX[targetIndex]

                    // FIX 3: Lock the Y position to the physical bottom of the graph to stop the shaking
                    // (padTop + innerH represents the newest/bottom edge of your horizontal scrolling view)
                    val y = padTop + innerH

                    if (showNoteLabels) {
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                midiToNoteName(nearest),
                                x + 6f,
                                y - 10f, // Changed to - 10f so the text draws just above the bottom cut-off
                                paints.label
                            )
                        }
                    }
                }
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
    val density = LocalDensity.current

    val windowMsEffective = remember(windowMs, bpm) {
        (windowMs.toFloat() * (60f / bpm)).toLong()
    }

    val buffer = rememberTraceBuffer(engine, paused, windowMsEffective)
    val samples = buffer.samples
    val stableMarkers = buffer.markers
    val paints = rememberGraphPaints()

    val whiteCount = (startMidi..endMidi).count { !midiToNoteName(it).contains("#") }
    val keyThicknessPx = with(density) { whiteKeyWidthDp.toPx() }
    val contentPitchPx = whiteCount * keyThicknessPx
    val contentPitchDp = with(density) { contentPitchPx.toDp() }

    val padTop = 12f
    val alignPx = with(density) { alignmentOffsetDp.toPx() }

    val midiY = remember(startMidi, endMidi, keyThicknessPx, alignPx, blackKeyShiftFraction) {
        val count = endMidi - startMidi + 1
        val array = FloatArray(count)

        val whiteList = mutableListOf<Int>()
        for (m in startMidi..endMidi) if (!midiToNoteName(m).contains("#")) whiteList.add(m)
        val whiteIndexMap = mutableMapOf<Int, Int>()
        whiteList.forEachIndexed { idx, midi -> whiteIndexMap[midi] = (whiteCount - 1 - idx) }

        val blackLeftIndexMap = mutableMapOf<Int, Int>()
        var whiteIdxCounter = 0
        for (m in startMidi..endMidi) {
            val name = midiToNoteName(m)
            if (name.contains("#")) {
                blackLeftIndexMap[m] = maxOf(0, whiteIdxCounter - 1)
            } else {
                whiteIdxCounter++
            }
        }

        for (m in startMidi..endMidi) {
            val idx = m - startMidi
            val y = if (whiteIndexMap.containsKey(m)) {
                val widx = whiteIndexMap[m]!!
                padTop + (widx + 0.5f) * keyThicknessPx + alignPx
            } else {
                val left = blackLeftIndexMap[m] ?: 0
                val reversedLeft = whiteCount - 1 - left
                val center = (reversedLeft + 0.5f) * keyThicknessPx
                val shiftPx = keyThicknessPx * blackKeyShiftFraction
                padTop + center - shiftPx + alignPx
            }
            array[idx] = y
        }
        array
    }

    val sState = scrollState ?: rememberScrollState()

    val bgLeftColor = GraphStyle.backgroundTop
    val bgRightColor = GraphStyle.backgroundBottom

    // Halo color and white dot color
    val haloColor = GraphStyle.dotHalo
    val dotWhite = GraphStyle.dot
    // Vertical bar color
    val verticalBarColor = GraphStyle.bar

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(sState)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentPitchDp)
            ) {
                buffer.observeChanges()
                val w = size.width
                val h = size.height

                val padBottom = 20f
                val padLeft = 12f
                val padRight = 12f
                val innerW = w - padLeft - padRight
                val innerH = h - padTop - padBottom

                val minMidi = startMidi
                val maxMidi = endMidi

                fun yForMidiFloat(midiFloat: Float): Float {
                    if (midiFloat.isNaN()) return -10000f

                    val floorM = midiFloat.toInt().coerceIn(minMidi, maxMidi)
                    val ceilM = (floorM + 1).coerceAtMost(maxMidi)

                    // FIX: Clamp both the floor and ceiling indices!
                    val floorIndex = (floorM - minMidi).coerceIn(0, midiY.lastIndex)
                    val ceilIndex = (ceilM - minMidi).coerceIn(0, midiY.lastIndex)

                    val y0 = midiY[floorIndex]
                    val y1 = midiY[ceilIndex]
                    val frac = midiFloat - floorM

                    return y0 + frac * (y1 - y0)
                }

                // background
                drawRect(brush = Brush.horizontalGradient(listOf(bgLeftColor, bgRightColor)), size = Size(w, h))

                // draw pitch lines
                for (m in minMidi..maxMidi) {
                    // FIX: Clamp the index for Y grid lines
                    val targetIndex = (m - minMidi).coerceIn(0, midiY.lastIndex)
                    val y = midiY[targetIndex]
                    val isNatural = !midiToNoteName(m).contains("#")
                    val col = if (isNatural) Color(0x33FFFFFF) else Color(0x22FFFFFF)
                    drawLine(
                        color = col,
                        start = Offset(padLeft, y),
                        end = Offset(padLeft + innerW, y),
                        strokeWidth = if (isNatural) 1.6f else 0.9f
                    )
                    if (isNatural) {
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                midiToNoteName(m),
                                padLeft + 6f,
                                y - 6f,
                                paints.small
                            )
                        }
                    }
                }

                if (showHorizontalGrid) {
                    val step = innerW / 6f
                    for (i in 0..6) {
                        val xx = padLeft + i * step
                        drawLine(
                            color = GraphStyle.pitchGrid,
                            start = Offset(xx, padTop),
                            end = Offset(xx, padTop + innerH),
                            strokeWidth = 1f
                        )
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
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    if (x < padLeft - 50) {
                        prevPoint = null
                        currentSeg = null
                        prevTime = Long.MIN_VALUE
                        continue
                    }

                    val p = Offset(x, y)
                    val t = s.tMs

                    if (prevPoint == null) {
                        bluePath.moveTo(p.x, p.y)
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
                            drawPath(
                                path = segPath,
                                color = GraphStyle.curve,
                                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    } else {
                        // fallback: draw the raw polyline stroke only
                        drawPath(
                            path = bluePath,
                            color = GraphStyle.curve,
                            style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }

                if (showWhiteTrace && smoothedPaths.isNotEmpty()) {
                    // draw white trace segments on top of the blue stroke
                    for (segPath in smoothedPaths) {
                        drawPath(
                            path = segPath,
                            color = GraphStyle.whiteTrace,
                            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
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
                        if (s0.midi.isNaN()) {
                            i++; continue
                        }
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

                            val left = if (widthPx <= minBarLenPx) ((xStart + xEnd) / 2f - minBarLenPx / 2f) else xStart
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
                    // FIX: Clamp marker Y position
                    val targetIndex = (m.midi - minMidi).coerceIn(0, midiY.lastIndex)
                    val y = midiY[targetIndex]
                    val x = xForTime(m.tMs)
                    drawLine(
                        color = GraphStyle.stableMarker,
                        start = Offset(padLeft, y),
                        end = Offset(padLeft + innerW, y),
                        strokeWidth = 2f
                    )
                    if (showNoteLabels) {
                        drawIntoCanvas { canvas ->
                            val noteName = midiToNoteName(m.midi)
                            val textWidth = paints.yellow.measureText(noteName)
                            var labelX = x + 8f
                            val labelY = y - 10f
                            val rightEdge = padLeft + innerW
                            if (labelX + textWidth > rightEdge - 6f) labelX = x - textWidth - 8f
                            if (labelX < padLeft + 6f) labelX = padLeft + 6f
                            canvas.nativeCanvas.drawText(noteName, labelX, labelY, paints.yellow)
                        }
                    }
                }

                // latest note label (snapped to integer MIDI), positioned near right edge
                if (showNoteLabels) {
                    val lastSample = samples.lastOrNull()
                    if (lastSample != null && !lastSample.midi.isNaN()) {
                        val nearest = lastSample.midi.roundToInt().coerceIn(minMidi, maxMidi)
                        val targetIndex = (nearest - minMidi).coerceIn(0, midiY.lastIndex)
                        val y = midiY[targetIndex]
                        val noteName = midiToNoteName(nearest)
                        val textWidth = paints.label.measureText(noteName)
                        val rightEdge = padLeft + innerW
                        var labelX = rightEdge - textWidth - 8f
                        labelX = labelX.coerceAtLeast(padLeft + 6f)
                        val labelY = y - 8f // Positioned slightly above the snapped horizontal line
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(noteName, labelX, labelY, paints.label)
                        }
                    }
                }
            }
        }
    }
}
