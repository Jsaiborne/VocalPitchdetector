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

/** Playback graph for landscape: time runs left to right, pitch runs bottom to top. */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun LandscapePlaybackPitchGraph(
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

    val targetMidi = currentPoint?.midiNote?.toFloat() ?: 60f
    val animatedCenterMidi by animateFloatAsState(
        targetValue = targetMidi,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "VerticalCameraPan"
    )

    LaunchedEffect(autoCenter) {
        if (!autoCenter && camera.canvasSize != Size.Zero) {
            val playheadVirtualX = (currentPositionMs / 1000f) * pixelsPerSecond
            camera.freezeAt(
                baseViewportX = playheadVirtualX - camera.canvasSize.width * PLAYHEAD_SCREEN_FRACTION,
                baseViewportY = -(animatedCenterMidi * pixelsPerMidi) - camera.canvasSize.height / 2f
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

            val playheadScreenX = canvasW * PLAYHEAD_SCREEN_FRACTION
            val playheadVirtualX = (currentPositionMs / 1000f) * pixelsPerSecond

            val baseViewportX = playheadVirtualX - playheadScreenX
            val baseViewportY = -(animatedCenterMidi * pixelsPerMidi) - canvasH / 2f

            val viewportX = if (autoCenter) baseViewportX - camera.panAnimX.value else camera.manualViewportX
            val viewportY = if (autoCenter) baseViewportY - camera.panAnimY.value else camera.manualViewportY

            translate(left = -viewportX, top = -viewportY) {
                // 1. Draw Background Grids
                val maxVisibleMidi = (-viewportY / pixelsPerMidi).toInt() + 1
                val minVisibleMidi = (-(viewportY + canvasH) / pixelsPerMidi).toInt() - 1

                for (midi in minVisibleMidi..maxVisibleMidi) {
                    val y = -midi * pixelsPerMidi
                    drawLine(
                        color = GraphStyle.pitchGrid,
                        start = Offset(viewportX, y),
                        end = Offset(viewportX + canvasW, y),
                        strokeWidth = 1f
                    )

                    val noteName = midiToNoteName(midi)
                    if (!noteName.contains("#")) {
                        drawContext.canvas.nativeCanvas.drawText(
                            noteName,
                            viewportX + 16f,
                            y - 8f,
                            paints.label
                        )
                    }
                }

                val minVisibleSec = (viewportX / pixelsPerSecond).toInt() - 1
                val maxVisibleSec = ((viewportX + canvasW) / pixelsPerSecond).toInt() + 1
                for (sec in minVisibleSec..maxVisibleSec) {
                    if (sec < 0) continue
                    val x = sec * pixelsPerSecond
                    drawLine(
                        color = GraphStyle.timeGrid,
                        start = Offset(x, viewportY),
                        end = Offset(x, viewportY + canvasH),
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
                        val x = (m.timestampMs / 1000f) * pixelsPerSecond
                        val y = -m.midiNote * pixelsPerMidi

                        if (m == activeMarker) {
                            drawLine(
                                color = GraphStyle.stableMarker,
                                start = Offset(viewportX, y),
                                end = Offset(viewportX + canvasW, y),
                                strokeWidth = 2f
                            )
                        }

                        if (x in (viewportX - 100f)..(viewportX + canvasW + 100f)) {
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
                val minVisibleTimeMs = ((viewportX - canvasW) / pixelsPerSecond) * 1000f - 1000f
                val maxVisibleTimeMs = ((viewportX + canvasW * 2) / pixelsPerSecond) * 1000f + 1000f
                val segments = buildTraceSegments(
                    points = pitchData,
                    minTimeMs = minVisibleTimeMs.toLong(),
                    maxTimeMs = maxVisibleTimeMs.toLong(),
                    breakDistance = canvasH * 0.35f
                ) { point ->
                    Offset((point.timestampMs / 1000f) * pixelsPerSecond, -point.midiNote * pixelsPerMidi)
                }
                drawTraceSegments(segments, showBars, showWhiteDots, showCurve)

                // 4. Draw Playhead
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadVirtualX, viewportY),
                    end = Offset(playheadVirtualX, viewportY + canvasH),
                    strokeWidth = 2.dp.toPx()
                )

                currentPoint?.let { point ->
                    val cy = -point.midiNote * pixelsPerMidi
                    drawCircle(color = playheadColor, radius = 6.dp.toPx(), center = Offset(playheadVirtualX, cy))
                }
            }
        }
    }
}
