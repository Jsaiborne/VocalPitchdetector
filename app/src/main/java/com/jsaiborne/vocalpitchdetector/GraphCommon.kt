@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Colours shared by the live and playback pitch graphs. */
internal object GraphStyle {
    val backgroundTop = Color(0xFF081226)
    val backgroundBottom = Color(0xFF0F2A3F)
    val dotHalo = Color(0x88000000)
    val dot = Color.White
    val bar = Color(0xCCEF9A9A)
    val curve = Color(0xFF7AD3FF)
    val whiteTrace = Color(0xCCFFFFFF)
    val stableMarker = Color(0xFFFFD54F)
    val pitchGrid = Color(0x2233AAFF)
    val timeGrid = Color(0x22FFFFFF)
}

/** Text paints shared by the pitch graphs. */
internal class GraphPaints(
    val label: Paint,
    val small: Paint,
    val yellow: Paint
)

@Composable
internal fun rememberGraphPaints(): GraphPaints = remember {
    GraphPaints(
        label = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        },
        small = Paint().apply {
            color = android.graphics.Color.argb(200, 255, 255, 255)
            textSize = 18f
            isAntiAlias = true
        },
        yellow = Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 18f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
}

// --- Playback camera -------------------------------------------------------------------------

internal const val PLAYBACK_PIXELS_PER_SECOND = 300f
internal const val PLAYBACK_PIXELS_PER_MIDI = 60f
internal const val PLAYBACK_SILENCE_GAP_MS = 150L
internal const val PLAYBACK_CURVE_SMOOTHING = 1.8f
internal const val PLAYHEAD_SCREEN_FRACTION = 0.2f

/**
 * Pan/zoom state for the playback graphs. In auto-center mode the camera follows the playhead
 * and the user's drag is a temporary offset that springs back; otherwise the drag moves a
 * manual viewport.
 */
internal class PlaybackCamera(val scope: CoroutineScope) {
    var zoomScale by mutableFloatStateOf(1f)
    var canvasSize by mutableStateOf(Size.Zero)
    var manualViewportX by mutableFloatStateOf(0f)
    var manualViewportY by mutableFloatStateOf(0f)
    val panAnimX = Animatable(0f)
    val panAnimY = Animatable(0f)

    /** Turns the current auto-centred view into the manual viewport (when auto-center is switched off). */
    suspend fun freezeAt(baseViewportX: Float, baseViewportY: Float) {
        manualViewportX = baseViewportX - panAnimX.value
        manualViewportY = baseViewportY - panAnimY.value
        panAnimX.snapTo(0f)
        panAnimY.snapTo(0f)
    }
}

@Composable
internal fun rememberPlaybackCamera(): PlaybackCamera {
    val scope = rememberCoroutineScope()
    return remember { PlaybackCamera(scope) }
}

internal fun Modifier.playbackCameraGestures(camera: PlaybackCamera, autoCenter: Boolean): Modifier =
    pointerInput(autoCenter) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (autoCenter) {
                camera.scope.launch {
                    camera.panAnimX.stop()
                    camera.panAnimY.stop()
                }
            }

            do {
                val event = awaitPointerEvent()
                val canceled = event.changes.any { it.isConsumed }
                if (!canceled) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = false)

                    val newScale = (camera.zoomScale * zoomChange).coerceIn(0.3f, 4f)
                    val actualZoom = newScale / camera.zoomScale
                    camera.zoomScale = newScale

                    if (autoCenter) {
                        camera.scope.launch {
                            camera.panAnimX.snapTo(camera.panAnimX.value + panChange.x)
                            camera.panAnimY.snapTo(camera.panAnimY.value + panChange.y)
                        }
                    } else {
                        camera.manualViewportX -= panChange.x
                        camera.manualViewportY -= panChange.y

                        // Guard against an Unspecified/NaN centroid when fingers lift off
                        if (actualZoom != 1f && centroid != Offset.Unspecified) {
                            camera.manualViewportX =
                                (camera.manualViewportX + centroid.x) * actualZoom - centroid.x
                            camera.manualViewportY =
                                (camera.manualViewportY + centroid.y) * actualZoom - centroid.y
                        }
                    }

                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (!canceled && event.changes.any { it.pressed })

            if (autoCenter) {
                camera.scope.launch { camera.panAnimX.animateTo(0f, spring()) }
                camera.scope.launch { camera.panAnimY.animateTo(0f, spring()) }
            }
        }
    }

// --- Trace building / drawing ---------------------------------------------------------------

/**
 * Splits the recorded points between [minTimeMs] and [maxTimeMs] into continuous runs, starting
 * a new run at every silence gap or large jump so curves are not drawn across them.
 * [toOffset] maps a recorded point to graph space.
 */
internal fun buildTraceSegments(
    points: List<RecordedPitchPoint>,
    minTimeMs: Long,
    maxTimeMs: Long,
    breakDistance: Float,
    toOffset: (RecordedPitchPoint) -> Offset
): List<List<Offset>> {
    val segments = mutableListOf<MutableList<Offset>>()
    var current: MutableList<Offset>? = null
    var prev: Offset? = null
    var prevTime = Long.MIN_VALUE

    var i = points.firstIndexAtOrAfter(minTimeMs)
    while (i < points.size) {
        val point = points[i++]
        if (point.timestampMs > maxTimeMs) break

        val p = toOffset(point)
        val previous = prev
        val startNewSegment = previous == null || current == null ||
            hypot((p.x - previous.x).toDouble(), (p.y - previous.y).toDouble()) > breakDistance ||
            point.timestampMs - prevTime > PLAYBACK_SILENCE_GAP_MS

        if (startNewSegment) {
            current = mutableListOf(p)
            segments.add(current)
        } else {
            current.add(p)
        }
        prev = p
        prevTime = point.timestampMs
    }
    return segments
}

/** Draws bars, dots and the smoothed curve for each segment (same look in both orientations). */
internal fun DrawScope.drawTraceSegments(
    segments: List<List<Offset>>,
    showBars: Boolean,
    showWhiteDots: Boolean,
    showCurve: Boolean,
    smoothing: Float = PLAYBACK_CURVE_SMOOTHING
) {
    val barSize = 8.dp.toPx()
    val barCorner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    val dotRadius = 3.dp.toPx()
    val haloRadius = 5.dp.toPx()

    for (seg in segments) {
        if (showBars) {
            for (p in seg) {
                drawRoundRect(
                    color = GraphStyle.bar,
                    topLeft = Offset(p.x - barSize / 2, p.y - barSize / 2),
                    size = Size(barSize, barSize),
                    cornerRadius = barCorner
                )
            }
        }
        if (showWhiteDots) {
            for (p in seg) {
                drawCircle(color = GraphStyle.dotHalo, radius = haloRadius, center = p)
                drawCircle(color = GraphStyle.dot, radius = dotRadius, center = p)
            }
        }
        if (showCurve && seg.size >= 2) {
            drawPath(
                path = buildSmoothedPath(seg, smoothing),
                color = GraphStyle.curve,
                style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
