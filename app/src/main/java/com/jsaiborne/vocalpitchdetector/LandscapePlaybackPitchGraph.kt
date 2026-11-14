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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Playback graph for landscape: time runs left to right, pitch runs bottom to top. */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun LandscapePlaybackPitchGraph(
    pitchData: List<RecordedPitchPoint>,
    currentPositionMs: Long,
    totalDurationMs: Long = 0L,
    isPlaying: Boolean = false,
    onSeek: (Long) -> Unit = {},
    showCurve: Boolean = true,
    showBars: Boolean = false,
    showWhiteDots: Boolean = false,
    // Held-note blocks and their names
    showNoteLabels: Boolean = true,
    autoCenter: Boolean = true,
    colors: GraphColors = GraphColors.Default
) {
    val playheadColor = MaterialTheme.colorScheme.error
    val camera = rememberPlaybackCamera(timeAxisVertical = false)
    val paints = rememberGraphPaints()
    val notes = rememberPlaybackNotes(pitchData)

    val pixelsPerSecond = PLAYBACK_PIXELS_PER_SECOND * camera.zoomScale
    val pixelsPerMidi = PLAYBACK_PIXELS_PER_MIDI * camera.zoomScale

    // Tapping the graph moves the playhead to that time
    camera.update(currentPositionMs, totalDurationMs, isPlaying) { tap ->
        val canvasW = camera.canvasSize.width
        val baseX = (currentPositionMs / 1000f) * pixelsPerSecond - canvasW * PLAYHEAD_SCREEN_FRACTION
        val viewportX = if (autoCenter) baseX - camera.panAnimX.value else camera.manualViewportX
        val tapMs = (((viewportX + tap.x) / pixelsPerSecond) * 1000f).toLong().coerceIn(0L, totalDurationMs)
        onSeek(tapMs)
        if (autoCenter) {
            // Keep the view where it is; the playhead just moves to the tapped point
            val newBaseX = (tapMs / 1000f) * pixelsPerSecond - canvasW * PLAYHEAD_SCREEN_FRACTION
            camera.scope.launch { camera.panAnimX.snapTo(newBaseX - viewportX) }
        }
    }
    LaunchedEffect(isPlaying) {
        if (isPlaying) camera.timePan.animateTo(0f, spring())
    }

    val currentPoint = remember(currentPositionMs, pitchData) { pitchData.nearestTo(currentPositionMs) }
    val playhead = playheadState(currentPositionMs, currentPoint, notes)

    val targetMidi = currentPoint?.midiFloat ?: 60f
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
                val axes = GraphAxes(
                    timeRunsDown = false,
                    timeStartPx = viewportX,
                    timeEndPx = viewportX + canvasW,
                    timeToPx = { t -> (t / 1000f) * pixelsPerSecond },
                    pitchToPx = { m -> -m * pixelsPerMidi }
                )

                // 1. Note lanes, named along the left edge
                val maxVisibleMidi = (-viewportY / pixelsPerMidi).toInt() + 1
                val minVisibleMidi = (-(viewportY + canvasH) / pixelsPerMidi).toInt() - 1
                drawNoteLanes(
                    axes,
                    LaneStyle(
                        midiRange = minVisibleMidi..maxVisibleMidi,
                        lanePx = pixelsPerMidi,
                        activeMidi = playhead.singingMidi,
                        activeColor = colors.curve,
                        namesAtPx = viewportX + 8.dp.toPx()
                    ),
                    paints
                )

                // 2. A line every second
                val minVisibleSec = (viewportX / pixelsPerSecond).toInt() - 1
                val maxVisibleSec = ((viewportX + canvasW) / pixelsPerSecond).toInt() + 1
                for (sec in minVisibleSec..maxVisibleSec) {
                    if (sec < 0) continue
                    val x = sec * pixelsPerSecond
                    drawLine(
                        color = GraphStyle.timeGrid,
                        start = Offset(x, viewportY),
                        end = Offset(x, viewportY + canvasH),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 3. Held notes, the one under the playhead brighter
                if (showNoteLabels) {
                    drawNoteBlocks(axes, notes, playbackNoteStyle(pixelsPerMidi, colors, playhead.note), paints)
                }

                // 4. Pitch data (only the part near the viewport)
                val minVisibleTimeMs = ((viewportX - canvasW) / pixelsPerSecond) * 1000f - 1000f
                val maxVisibleTimeMs = ((viewportX + canvasW * 2) / pixelsPerSecond) * 1000f + 1000f
                val segments = buildTraceSegments(
                    points = pitchData,
                    minTimeMs = minVisibleTimeMs.toLong(),
                    maxTimeMs = maxVisibleTimeMs.toLong(),
                    breakDistance = canvasH * 0.35f
                ) { point -> axes.at(point.timestampMs, point.midiFloat) }
                drawTraceSegments(segments, TraceStyle(colors, showCurve, showWhiteDots, showBars))

                // 5. Playhead, with the note being sung there
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadVirtualX, viewportY),
                    end = Offset(playheadVirtualX, viewportY + canvasH),
                    strokeWidth = 2.dp.toPx()
                )
                playhead.singing?.let { point ->
                    val at = Offset(playheadVirtualX, -point.midiFloat * pixelsPerMidi)
                    drawNowTag(
                        nowTag(at, point.midiFloat, colors, dotColor = playheadColor),
                        timeRunsDown = false,
                        bounds = Rect(viewportX, viewportY, viewportX + canvasW, viewportY + canvasH),
                        paints = paints
                    )
                }
            }
        }
    }
}
