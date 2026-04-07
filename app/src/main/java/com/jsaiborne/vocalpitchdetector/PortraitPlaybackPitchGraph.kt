package com.jsaiborne.vocalpitchdetector

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PortraitPlaybackPitchGraph(
    pitchData: List<RecordedPitchPoint>,
    currentPositionMs: Long,
    stableMarkers: List<RecordedPitchPoint> = emptyList(),
    showCurve: Boolean = true,
    showBars: Boolean = false,
    showWhiteDots: Boolean = false,
    showNoteLabels: Boolean = true,
    autoCenter: Boolean = true
) {
    val bgTopColor = Color(0xFF081226)
    val bgBottomColor = Color(0xFF0F2A3F)
    val haloColor = Color(0x88000000)
    val dotColor = Color.White
    val barColor = Color(0xCCEF9A9A)
    val curveColor = Color(0xFF7AD3FF)

    val pitchGridColor = Color(0x2233AAFF)
    val timeGridColor = Color(0x22FFFFFF)
    val playheadColor = MaterialTheme.colorScheme.error

    var zoomScale by remember { mutableFloatStateOf(1f) }

    val currentPixelsPerSecond = 300f * zoomScale
    val currentPixelsPerMidi = 60f * zoomScale
    val silenceGapMs = 150L
    val smoothing = 1.8f

    val currentPoint = remember(currentPositionMs, pitchData) {
        pitchData.minByOrNull { kotlin.math.abs(it.timestampMs - currentPositionMs) }
    }

    val targetMidi = currentPoint?.midiNote?.toFloat() ?: 60f
    val animatedCenterMidi by animateFloatAsState(
        targetValue = targetMidi,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "HorizontalCameraPan"
    )

    var isUserPanning by remember { mutableStateOf(false) }
    val panAnimX = remember { Animatable(0f) }
    val panAnimY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var manualViewportX by remember { mutableFloatStateOf(0f) }
    var manualViewportY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(autoCenter) {
        if (!autoCenter && canvasSize != Size.Zero) {
            val canvasW = canvasSize.width
            val canvasH = canvasSize.height
            val playheadScreenY = canvasH * 0.2f
            val playheadVirtualY = (currentPositionMs / 1000f) * currentPixelsPerSecond

            val baseViewportX = (animatedCenterMidi * currentPixelsPerMidi) - (canvasW / 2f)
            val baseViewportY = playheadVirtualY - playheadScreenY

            manualViewportX = baseViewportX - panAnimX.value
            manualViewportY = baseViewportY - panAnimY.value

            panAnimX.snapTo(0f)
            panAnimY.snapTo(0f)
        }
    }

    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            isAntiAlias = true
        }
    }
    val smallPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = 18f
            isAntiAlias = true
        }
    }
    val yellowPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 18f
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bgTopColor, bgBottomColor)))
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(autoCenter) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isUserPanning = true
                    if (autoCenter) {
                        coroutineScope.launch { panAnimX.stop(); panAnimY.stop() }
                    }

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)

                            val newScale = (zoomScale * zoomChange).coerceIn(0.3f, 4f)
                            val actualZoom = newScale / zoomScale
                            zoomScale = newScale

                            if (autoCenter) {
                                coroutineScope.launch {
                                    panAnimX.snapTo(panAnimX.value + panChange.x)
                                    panAnimY.snapTo(panAnimY.value + panChange.y)
                                }
                            } else {
                                // Pan
                                manualViewportX -= panChange.x
                                manualViewportY -= panChange.y

                                // FIX: Guard against Unspecified/NaN centroid corrupting the viewport
                                // when a user lifts their fingers off the screen.
                                if (actualZoom != 1f && centroid != Offset.Unspecified) {
                                    manualViewportX = (manualViewportX + centroid.x) * actualZoom - centroid.x
                                    manualViewportY = (manualViewportY + centroid.y) * actualZoom - centroid.y
                                }
                            }

                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (!canceled && event.changes.any { it.pressed })

                    isUserPanning = false
                    if (autoCenter) {
                        coroutineScope.launch { panAnimX.animateTo(0f, spring()) }
                        coroutineScope.launch { panAnimY.animateTo(0f, spring()) }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            val playheadScreenY = canvasH * 0.2f
            val playheadVirtualY = (currentPositionMs / 1000f) * currentPixelsPerSecond

            val baseViewportX = (animatedCenterMidi * currentPixelsPerMidi) - (canvasW / 2f)
            val baseViewportY = playheadVirtualY - playheadScreenY

            val viewportX = if (autoCenter) baseViewportX - panAnimX.value else manualViewportX
            val viewportY = if (autoCenter) baseViewportY - panAnimY.value else manualViewportY

            translate(left = -viewportX, top = -viewportY) {
                // 1. Draw Background Grids
                val minVisibleMidi = (viewportX / currentPixelsPerMidi).toInt() - 1
                val maxVisibleMidi = ((viewportX + canvasW) / currentPixelsPerMidi).toInt() + 1

                for (midi in minVisibleMidi..maxVisibleMidi) {
                    val x = midi * currentPixelsPerMidi
                    drawLine(
                        color = pitchGridColor,
                        start = Offset(x, viewportY),
                        end = Offset(x, viewportY + canvasH),
                        strokeWidth = 1f
                    )

                    val noteName = getNoteName(midi)
                    if (!noteName.contains("#")) {
                        drawContext.canvas.nativeCanvas.drawText(
                            noteName, x + 8f, viewportY + canvasH - 24f, labelPaint
                        )
                    }
                }

                val minVisibleSec = (viewportY / currentPixelsPerSecond).toInt() - 1
                val maxVisibleSec = ((viewportY + canvasH) / currentPixelsPerSecond).toInt() + 1
                for (sec in minVisibleSec..maxVisibleSec) {
                    if (sec < 0) continue
                    val y = sec * currentPixelsPerSecond
                    drawLine(
                        color = timeGridColor,
                        start = Offset(viewportX, y),
                        end = Offset(viewportX + canvasW, y),
                        strokeWidth = 1f
                    )
                }

                // 2. Draw Stable Markers
                if (showNoteLabels) {
                    var activeMarker = stableMarkers.lastOrNull { it.timestampMs <= currentPositionMs }

                    if (activeMarker != null && currentPoint != null) {
                        val timeSinceLastSinging = kotlin.math.abs(currentPoint.timestampMs - currentPositionMs)
                        if (timeSinceLastSinging > 1000L) {
                            activeMarker = null
                        }
                    }

                    for (m in stableMarkers) {
                        val x = m.midiNote * currentPixelsPerMidi
                        val y = (m.timestampMs / 1000f) * currentPixelsPerSecond

                        if (m == activeMarker) {
                            drawLine(
                                color = Color(0xFFFFD54F),
                                start = Offset(x, viewportY),
                                end = Offset(x, viewportY + canvasH),
                                strokeWidth = 2f
                            )
                        }

                        if (y in (viewportY - 100f)..(viewportY + canvasH + 100f)) {
                            drawContext.canvas.nativeCanvas.drawText(
                                getNoteName(m.midiNote),
                                x + 6f,
                                y - 10f,
                                yellowPaint
                            )
                        }
                    }
                }

                // 3. Draw Pitch Data
                if (pitchData.isNotEmpty()) {
                    val pointsSegments = mutableListOf<MutableList<Offset>>()
                    var currentSeg: MutableList<Offset>? = null
                    var prevPoint: Offset? = null
                    var prevTime: Long = Long.MIN_VALUE
                    val breakDistance = canvasW * 0.35f

                    val minVisibleTimeMs = ((viewportY - canvasH) / currentPixelsPerSecond) * 1000f - 1000f
                    val maxVisibleTimeMs = ((viewportY + canvasH * 2) / currentPixelsPerSecond) * 1000f + 1000f

                    for (point in pitchData) {
                        if (point.timestampMs < minVisibleTimeMs || point.timestampMs > maxVisibleTimeMs) continue

                        val x = point.midiNote * currentPixelsPerMidi
                        val y = (point.timestampMs / 1000f) * currentPixelsPerSecond
                        val p = Offset(x, y)
                        val t = point.timestampMs

                        if (prevPoint == null || currentSeg == null) {
                            currentSeg = mutableListOf(p)
                            pointsSegments.add(currentSeg)
                        } else {
                            val dx = p.x - prevPoint.x
                            val dy = p.y - prevPoint.y
                            val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            val timeGap = t - prevTime

                            if (dist > breakDistance || timeGap > silenceGapMs) {
                                currentSeg = mutableListOf(p)
                                pointsSegments.add(currentSeg)
                            } else {
                                currentSeg.add(p)
                            }
                        }
                        prevPoint = p
                        prevTime = t
                    }

                    val barThickness = 8.dp.toPx()
                    val dotRadius = 3.dp.toPx()
                    val haloRadius = 5.dp.toPx()

                    for (seg in pointsSegments) {
                        if (showBars) {
                            for (p in seg) {
                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(p.x - barThickness / 2, p.y - 4.dp.toPx()),
                                    size = Size(barThickness, 8.dp.toPx()),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }
                        if (showWhiteDots) {
                            for (p in seg) {
                                drawCircle(color = haloColor, radius = haloRadius, center = p)
                                drawCircle(color = dotColor, radius = dotRadius, center = p)
                            }
                        }
                        if (showCurve && seg.size >= 2) {
                            val path = buildSmoothedPath(seg, smoothing)
                            drawPath(
                                path = path,
                                color = curveColor,
                                style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }

                // 4. Draw Playhead
                drawLine(
                    color = playheadColor,
                    start = Offset(viewportX, playheadVirtualY),
                    end = Offset(viewportX + canvasW, playheadVirtualY),
                    strokeWidth = 2.dp.toPx()
                )

                currentPoint?.let { point ->
                    val cx = point.midiNote * currentPixelsPerMidi
                    drawCircle(color = playheadColor, radius = 6.dp.toPx(), center = Offset(cx, playheadVirtualY))
                }
            }
        }
    }
}

fun getNoteName(midi: Int): String {
    val index = ((midi % 12) + 12) % 12
    val notes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val note = notes[index]
    val octave = kotlin.math.floor(midi / 12.0).toInt() - 1
    return "$note$octave"
}
