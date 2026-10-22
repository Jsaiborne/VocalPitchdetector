package com.jsaiborne.vocalpitchdetector

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** At most this share of the live graph shows the future; the rest is the trace already sung. */
internal const val MAX_LOOKAHEAD_FRACTION = 0.6f

/** Target bars are this fraction of a white key wide: about one semitone. */
internal const val TARGET_THICKNESS_FRACTION = 0.45f

private const val TARGET_OUTLINE_PX = 2f

/**
 * Draws exercise [targets] as bars along the time axis, under the live curve. [timeToPx] maps a
 * time to its position on the time axis and [pitchToPx] a MIDI note to its position on the pitch
 * axis. Time runs down the graph when [timeRunsDown], otherwise left to right.
 */
@Suppress("LongParameterList")
internal fun DrawScope.drawExerciseTargets(
    targets: List<TargetEvent>,
    nowMs: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    timeToPx: (Long) -> Float,
    pitchToPx: (Int) -> Float,
    thicknessPx: Float,
    timeRunsDown: Boolean
) {
    val corner = CornerRadius(thicknessPx / 4f, thicknessPx / 4f)
    for (target in targets) {
        if (target.endMs < windowStartMs || target.startMs > windowEndMs) continue
        val timeStart = timeToPx(target.startMs)
        val length = timeToPx(target.endMs) - timeStart
        val pitchCentre = pitchToPx(target.midi) - thicknessPx / 2f
        val topLeft = if (timeRunsDown) Offset(pitchCentre, timeStart) else Offset(timeStart, pitchCentre)
        val size = if (timeRunsDown) Size(thicknessPx, length) else Size(length, thicknessPx)

        if (target.kind.scored) {
            val color = when {
                nowMs >= target.endMs -> GraphStyle.targetPast
                nowMs >= target.startMs -> GraphStyle.targetActive
                else -> GraphStyle.targetUpcoming
            }
            drawRoundRect(color = color, topLeft = topLeft, size = size, cornerRadius = corner)
        } else {
            // Played for the singer to hear, not sung: an outline only
            drawRoundRect(
                color = GraphStyle.targetGuide,
                topLeft = topLeft,
                size = size,
                cornerRadius = corner,
                style = Stroke(width = TARGET_OUTLINE_PX)
            )
        }
    }
}
