@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Playback graph for portrait: time runs top to bottom, pitch runs left to right. */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
    val playheadColor = MaterialTheme.colorScheme.error
    val camera = rememberPlaybackCamera()
    val paints = rememberGraphPaints()

    val pixelsPerSecond = PLAYBACK_PIXELS_PER_SECOND * camera.zoomScale
    val pixelsPerMidi = PLAYBACK_PIXELS_PER_MIDI * camera.zoomScale

    val currentPoint = remember(currentPositionMs, pitchData) { pitchData.nearestTo(currentPositionMs) }

    val targetMidi = currentPoint?.midiFloat ?: 60f
    val animatedCenterMidi by animateFloatAsState(
        targetValue = targetMidi,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "HorizontalCameraPan"
    )

    LaunchedEffect(autoCenter) {
        if (!autoCenter && camera.canvasSize != Size.Zero) {
            val playheadVirtualY = (currentPositionMs / 1000f) * pixelsPerSecond
            camera.freezeAt(
                baseViewportX = (animatedCenterMidi * pixelsPerMidi) - camera.canvasSize.width / 2f,
                baseViewportY = playheadVirtualY - camera.canvasSize.height * PLAYHEAD_SCREEN_FRACTION
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(GraphStyle.backgroundTop, GraphStyle.backgroundBottom)))
            .onSizeChanged { camera.canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .playbackCameraGestures(camera, autoCenter)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            val playheadScreenY = canvasH * PLAYHEAD_SCREEN_FRACTION
            val playheadVirtualY = (currentPositionMs / 1000f) * pixelsPerSecond

            val baseViewportX = (animatedCenterMidi * pixelsPerMidi) - (canvasW / 2f)
            val baseViewportY = playheadVirtualY - playheadScreenY

            val viewportX = if (autoCenter) baseViewportX - camera.panAnimX.value else camera.manualViewportX
            val viewportY = if (autoCenter) baseViewportY - camera.panAnimY.value else camera.manualViewportY

            translate(left = -viewportX, top = -viewportY) {
                // 1. Draw Background Grids
                val minVisibleMidi = (viewportX / pixelsPerMidi).toInt() - 1
                val maxVisibleMidi = ((viewportX + canvasW) / pixelsPerMidi).toInt() + 1

                for (midi in minVisibleMidi..maxVisibleMidi) {
                    val x = midi * pixelsPerMidi
                    drawLine(
                        color = GraphStyle.pitchGrid,
                        start = Offset(x, viewportY),
                        end = Offset(x, viewportY + canvasH),
                        strokeWidth = 1f
                    )

                    val noteName = midiToNoteName(midi)
                    if (!noteName.contains("#")) {
                        drawContext.canvas.nativeCanvas.drawText(
                            noteName,
                            x + 8f,
                            viewportY + canvasH - 24f,
                            paints.label
                        )
                    }
                }

                val minVisibleSec = (viewportY / pixelsPerSecond).toInt() - 1
                val maxVisibleSec = ((viewportY + canvasH) / pixelsPerSecond).toInt() + 1
                for (sec in minVisibleSec..maxVisibleSec) {
                    if (sec < 0) continue
                    val y = sec * pixelsPerSecond
                    drawLine(
                        color = GraphStyle.timeGrid,
                        start = Offset(viewportX, y),
                        end = Offset(viewportX + canvasW, y),
                        strokeWidth = 1f
                    )
                }

                // 2. Draw Stable Markers
                if (showNoteLabels) {
                    var activeMarker = stableMarkers.lastOrNull { it.timestampMs <= currentPositionMs }

                    if (activeMarker != null && currentPoint != null) {
                        val timeSinceLastSinging = abs(currentPoint.timestampMs - currentPositionMs)
                        if (timeSinceLastSinging > 1000L) {
                            activeMarker = null
                        }
                    }

                    for (m in stableMarkers) {
                        val x = m.midiNote * pixelsPerMidi
                        val y = (m.timestampMs / 1000f) * pixelsPerSecond

                        if (m == activeMarker) {
                            drawLine(
                                color = GraphStyle.stableMarker,
                                start = Offset(x, viewportY),
                                end = Offset(x, viewportY + canvasH),
                                strokeWidth = 2f
                            )
                        }

                        if (y in (viewportY - 100f)..(viewportY + canvasH + 100f)) {
                            drawContext.canvas.nativeCanvas.drawText(
                                midiToNoteName(m.midiNote),
                                x + 6f,
                                y - 10f,
                                paints.yellow
                            )
                        }
                    }
                }

                // 3. Draw Pitch Data (only the part near the viewport)
                val minVisibleTimeMs = ((viewportY - canvasH) / pixelsPerSecond) * 1000f - 1000f
                val maxVisibleTimeMs = ((viewportY + canvasH * 2) / pixelsPerSecond) * 1000f + 1000f
                val segments = buildTraceSegments(
                    points = pitchData,
                    minTimeMs = minVisibleTimeMs.toLong(),
                    maxTimeMs = maxVisibleTimeMs.toLong(),
                    breakDistance = canvasW * 0.35f
                ) { point ->
                    Offset(point.midiFloat * pixelsPerMidi, (point.timestampMs / 1000f) * pixelsPerSecond)
                }
                drawTraceSegments(segments, showBars, showWhiteDots, showCurve)

                // 4. Draw Playhead
                drawLine(
                    color = playheadColor,
                    start = Offset(viewportX, playheadVirtualY),
                    end = Offset(viewportX + canvasW, playheadVirtualY),
                    strokeWidth = 2.dp.toPx()
                )

                currentPoint?.let { point ->
                    val cx = point.midiFloat * pixelsPerMidi
                    drawCircle(color = playheadColor, radius = 6.dp.toPx(), center = Offset(cx, playheadVirtualY))
                }
            }
        }
    }
}
