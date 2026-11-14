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

/** Playback graph for portrait: time runs top to bottom, pitch runs left to right. */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun PortraitPlaybackPitchGraph(
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
    val camera = rememberPlaybackCamera(timeAxisVertical = true)
    val paints = rememberGraphPaints()
    val notes = rememberPlaybackNotes(pitchData)

    val pixelsPerSecond = PLAYBACK_PIXELS_PER_SECOND * camera.zoomScale
    val pixelsPerMidi = PLAYBACK_PIXELS_PER_MIDI * camera.zoomScale

    // Tapping the graph moves the playhead to that time
    camera.update(currentPositionMs, totalDurationMs, isPlaying) { tap ->
        val canvasH = camera.canvasSize.height
        val baseY = (currentPositionMs / 1000f) * pixelsPerSecond - canvasH * PLAYHEAD_SCREEN_FRACTION
        val viewportY = if (autoCenter) baseY - camera.panAnimY.value else camera.manualViewportY
        val tapMs = (((viewportY + tap.y) / pixelsPerSecond) * 1000f).toLong().coerceIn(0L, totalDurationMs)
        onSeek(tapMs)
        if (autoCenter) {
            // Keep the view where it is; the playhead just moves to the tapped point
            val newBaseY = (tapMs / 1000f) * pixelsPerSecond - canvasH * PLAYHEAD_SCREEN_FRACTION
            camera.scope.launch { camera.panAnimY.snapTo(newBaseY - viewportY) }
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
                val axes = GraphAxes(
                    timeRunsDown = true,
                    timeStartPx = viewportY,
                    timeEndPx = viewportY + canvasH,
                    timeToPx = { t -> (t / 1000f) * pixelsPerSecond },
                    pitchToPx = { m -> m * pixelsPerMidi }
                )

                // 1. Note lanes, named along the bottom edge
                val minVisibleMidi = (viewportX / pixelsPerMidi).toInt() - 1
                val maxVisibleMidi = ((viewportX + canvasW) / pixelsPerMidi).toInt() + 1
                drawNoteLanes(
                    axes,
                    LaneStyle(
                        midiRange = minVisibleMidi..maxVisibleMidi,
                        lanePx = pixelsPerMidi,
                        activeMidi = playhead.singingMidi,
                        activeColor = colors.curve,
                        namesAtPx = viewportY + canvasH - 10.dp.toPx()
                    ),
                    paints
                )

                // 2. A line every second
                val minVisibleSec = (viewportY / pixelsPerSecond).toInt() - 1
                val maxVisibleSec = ((viewportY + canvasH) / pixelsPerSecond).toInt() + 1
                for (sec in minVisibleSec..maxVisibleSec) {
                    if (sec < 0) continue
                    val y = sec * pixelsPerSecond
                    drawLine(
                        color = GraphStyle.timeGrid,
                        start = Offset(viewportX, y),
                        end = Offset(viewportX + canvasW, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 3. Held notes, the one under the playhead brighter
                if (showNoteLabels) {
                    drawNoteBlocks(axes, notes, playbackNoteStyle(pixelsPerMidi, colors, playhead.note), paints)
                }

                // 4. Pitch data (only the part near the viewport)
                val minVisibleTimeMs = ((viewportY - canvasH) / pixelsPerSecond) * 1000f - 1000f
                val maxVisibleTimeMs = ((viewportY + canvasH * 2) / pixelsPerSecond) * 1000f + 1000f
                val segments = buildTraceSegments(
                    points = pitchData,
                    minTimeMs = minVisibleTimeMs.toLong(),
                    maxTimeMs = maxVisibleTimeMs.toLong(),
                    breakDistance = canvasW * 0.35f
                ) { point -> axes.at(point.timestampMs, point.midiFloat) }
                drawTraceSegments(segments, TraceStyle(colors, showCurve, showWhiteDots, showBars))

                // 5. Playhead, with the note being sung there
                drawLine(
                    color = playheadColor,
                    start = Offset(viewportX, playheadVirtualY),
                    end = Offset(viewportX + canvasW, playheadVirtualY),
                    strokeWidth = 2.dp.toPx()
                )
                playhead.singing?.let { point ->
                    val at = Offset(point.midiFloat * pixelsPerMidi, playheadVirtualY)
                    drawNowTag(
                        nowTag(at, point.midiFloat, colors, dotColor = playheadColor),
                        timeRunsDown = true,
                        bounds = Rect(viewportX, viewportY, viewportX + canvasW, viewportY + canvasH),
                        paints = paints
                    )
                }
            }
        }
    }
}
