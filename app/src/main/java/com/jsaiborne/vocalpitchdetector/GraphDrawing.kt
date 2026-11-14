@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Maps graph time (ms) and pitch (MIDI, may be fractional) to canvas pixels, in either orientation:
 * when [timeRunsDown] time is the y axis and pitch the x axis, otherwise time is x and pitch y.
 * [timeStartPx]..[timeEndPx] is the stretch of the time axis that's drawn.
 */
internal class GraphAxes(
    val timeRunsDown: Boolean,
    val timeStartPx: Float,
    val timeEndPx: Float,
    val timeToPx: (Long) -> Float,
    val pitchToPx: (Float) -> Float
) {
    fun at(timeMs: Long, midi: Float): Offset = point(timeToPx(timeMs), pitchToPx(midi))

    /** The canvas point [alongTimePx] along the time axis and [alongPitchPx] along the pitch axis. */
    fun point(alongTimePx: Float, alongPitchPx: Float): Offset =
        if (timeRunsDown) Offset(alongPitchPx, alongTimePx) else Offset(alongTimePx, alongPitchPx)

    /** The band from [fromPx] to [toPx] along the time axis, [pitchCentrePx] ± [halfPx] along the pitch axis. */
    fun band(fromPx: Float, toPx: Float, pitchCentrePx: Float, halfPx: Float): Rect {
        val a = point(fromPx, pitchCentrePx - halfPx)
        val b = point(toPx, pitchCentrePx + halfPx)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }
}

// --- Note lanes ------------------------------------------------------------------------------

/** What [drawNoteLanes] draws. */
internal class LaneStyle(
    val midiRange: IntRange,
    /** Length of one semitone along the pitch axis. */
    val lanePx: Float,
    /** The lane of the note being sung right now, lit up in [activeColor]; null for none. */
    val activeMidi: Int?,
    val activeColor: Color,
    /**
     * Where the lane names go along the time axis: their baseline when time runs down, otherwise their
     * left edge. Null draws no names.
     */
    val namesAtPx: Float?
)

private const val ACTIVE_LANE_ALPHA = 0.16f

/** One lane per semitone: darker for the black keys, a line between lanes, a brighter one under each C. */
internal fun DrawScope.drawNoteLanes(axes: GraphAxes, style: LaneStyle, paints: GraphPaints) {
    val half = style.lanePx / 2f
    val separatorWidth = 1.dp.toPx()
    val octaveWidth = 1.5.dp.toPx()
    for (midi in style.midiRange) {
        val centre = axes.pitchToPx(midi.toFloat())
        val lane = axes.band(axes.timeStartPx, axes.timeEndPx, centre, half)
        if (!isWhiteKey(midi)) drawRect(GraphStyle.blackLane, lane.topLeft, lane.size)
        if (midi == style.activeMidi) {
            drawRect(style.activeColor.copy(alpha = ACTIVE_LANE_ALPHA), lane.topLeft, lane.size)
        }

        // The edge on the lower-pitch side of the lane
        val isC = Math.floorMod(midi, 12) == 0
        val edge = axes.pitchToPx(midi - 0.5f)
        drawLine(
            color = if (isC) GraphStyle.octaveLine else GraphStyle.laneSeparator,
            start = axes.point(axes.timeStartPx, edge),
            end = axes.point(axes.timeEndPx, edge),
            strokeWidth = if (isC) octaveWidth else separatorWidth
        )

        val namesAt = style.namesAtPx
        if (namesAt != null && isWhiteKey(midi)) drawLaneName(axes, midi, centre, namesAt, style.lanePx, paints)
    }
}

/** The name of a white-key lane, if there's room for it (the Cs are always named). */
private fun DrawScope.drawLaneName(
    axes: GraphAxes,
    midi: Int,
    centre: Float,
    namesAt: Float,
    lanePx: Float,
    paints: GraphPaints
) {
    val isC = Math.floorMod(midi, 12) == 0
    val paint = if (isC) paints.laneNameC else paints.laneName
    val name = midiToDisplayName(midi)
    val width = paint.measureText(name)
    // Names may spill over the narrower black-key lanes either side, but not into the next name
    val fits = if (axes.timeRunsDown) width <= lanePx * 1.8f else paint.textSize * 1.25f <= lanePx
    if (!fits && !isC) return
    if (axes.timeRunsDown) {
        drawContext.canvas.nativeCanvas.drawText(name, centre - width / 2f, namesAt, paint)
    } else {
        drawContext.canvas.nativeCanvas.drawText(name, namesAt, centre + paint.textSize * 0.36f, paint)
    }
}

// --- Held-note blocks ------------------------------------------------------------------------

/** How [drawNoteBlocks] draws the held notes. */
internal class NoteBlockStyle(
    /** Thickness of a block across the pitch axis. */
    val thicknessPx: Float,
    val colorFor: (NoteSegment) -> Color,
    /** Write each block's note name at its start. */
    val showNames: Boolean,
    /** Drawn brighter, e.g. the note under the playback playhead. */
    val highlighted: NoteSegment? = null
)

/** Held-note blocks fill this share of their lane. */
internal const val NOTE_BLOCK_LANE_FRACTION = 0.6f

/** A held note's colour: by how well it was tuned, or the user's own colour when that's switched off. */
internal fun noteBlockColor(note: NoteSegment, colors: GraphColors): Color =
    if (colors.colorByTuning) tuningBand(note.cents).color else colors.noteLabels

private const val BLOCK_FILL_ALPHA = 0.36f
private const val BLOCK_HIGHLIGHT_ALPHA = 0.62f
private const val BLOCK_OUTLINE_ALPHA = 0.9f

/** Draws each held note as a rounded block in its lane, cut to the visible part of the time axis. */
internal fun DrawScope.drawNoteBlocks(
    axes: GraphAxes,
    segments: List<NoteSegment>,
    style: NoteBlockStyle,
    paints: GraphPaints
) {
    val half = style.thicknessPx / 2f
    val corner = CornerRadius(half, half)
    val outline = Stroke(width = 1.dp.toPx())
    for (segment in segments) {
        val startPx = axes.timeToPx(segment.startMs)
        val from = startPx.coerceAtLeast(axes.timeStartPx)
        val to = axes.timeToPx(segment.endMs).coerceAtMost(axes.timeEndPx)
        if (to < from) continue

        val centre = axes.pitchToPx(segment.midi.toFloat())
        // Even the shortest note is at least a round dot
        val block = axes.band(from, maxOf(to, from + style.thicknessPx), centre, half)
        val color = style.colorFor(segment)
        val fillAlpha = if (segment == style.highlighted) BLOCK_HIGHLIGHT_ALPHA else BLOCK_FILL_ALPHA
        drawRoundRect(color.copy(alpha = fillAlpha), block.topLeft, block.size, corner)
        drawRoundRect(color.copy(alpha = BLOCK_OUTLINE_ALPHA), block.topLeft, block.size, corner, style = outline)

        if (style.showNames) {
            // The name stays at the visible start of the block, fading out as the block leaves the screen
            val fade = if (startPx < axes.timeStartPx) ((to - from) / (paints.noteName.textSize * 2f)) else 1f
            drawBlockName(axes, segment.midi, block, from, color, fade.coerceIn(0f, 1f), paints.noteName)
        }
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawBlockName(
    axes: GraphAxes,
    midi: Int,
    block: Rect,
    startPx: Float,
    color: Color,
    alpha: Float,
    paint: Paint
) {
    if (alpha <= 0f) return
    val gap = 4.dp.toPx()
    paint.color = color.copy(alpha = alpha).toArgb()
    val name = midiToDisplayName(midi)
    if (axes.timeRunsDown) {
        // Beside the top of the block
        drawContext.canvas.nativeCanvas.drawText(name, block.right + gap, startPx + paint.textSize, paint)
    } else {
        // Above the start of the block
        drawContext.canvas.nativeCanvas.drawText(name, startPx, block.top - gap, paint)
    }
}

// --- Now tag ---------------------------------------------------------------------------------

/** The newest pitch: a glowing dot with the note name (and optionally its cents) in a pill beside it. */
internal class NowTag(
    val point: Offset,
    val note: String,
    /** E.g. "+12", or null to show just the note. */
    val cents: String?,
    val centsColor: Color,
    val dotColor: Color
)

/** The tag for [pitch] (a fractional MIDI note) at [point]; it shows cents when notes are coloured by tuning. */
internal fun nowTag(point: Offset, pitch: Float, colors: GraphColors, dotColor: Color): NowTag {
    val note = pitch.roundToInt()
    val cents = (pitch - note) * 100f
    return NowTag(
        point = point,
        note = midiToDisplayName(note),
        cents = if (colors.colorByTuning) String.format(Locale.US, "%+d", cents.roundToInt()) else null,
        centsColor = tuningBand(cents).color,
        dotColor = dotColor
    )
}

/**
 * Draws [tag], keeping the pill inside [bounds]. The pill sits beside the dot on the higher-pitch side
 * (right when time runs down, above otherwise) and flips to the other side when there's no room.
 */
internal fun DrawScope.drawNowTag(tag: NowTag, timeRunsDown: Boolean, bounds: Rect, paints: GraphPaints) {
    drawCircle(tag.dotColor.copy(alpha = 0.28f), radius = 9.dp.toPx(), center = tag.point)
    drawCircle(tag.dotColor, radius = 4.5.dp.toPx(), center = tag.point)

    val padH = 8.dp.toPx()
    val padV = 4.dp.toPx()
    val gap = 14.dp.toPx()
    val centsGap = 6.dp.toPx()
    val noteWidth = paints.tagNote.measureText(tag.note)
    val cents = tag.cents
    val centsWidth = if (cents != null) centsGap + paints.tagCents.measureText(cents) else 0f
    val metrics = paints.tagNote.fontMetrics
    val width = padH * 2 + noteWidth + centsWidth
    val height = padV * 2 + (metrics.descent - metrics.ascent)

    val p = tag.point
    val left: Float
    val top: Float
    if (timeRunsDown) {
        left = if (p.x + gap + width <= bounds.right) p.x + gap else p.x - gap - width
        top = (p.y - height / 2f).coerceAtMost(bounds.bottom - height).coerceAtLeast(bounds.top)
    } else {
        top = if (p.y - gap - height >= bounds.top) p.y - gap - height else p.y + gap
        left = (p.x - width / 2f).coerceAtMost(bounds.right - width).coerceAtLeast(bounds.left)
    }

    drawRoundRect(
        color = GraphStyle.tagBackground,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f, height / 2f)
    )
    val baseline = top + padV - metrics.ascent
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText(tag.note, left + padH, baseline, paints.tagNote)
    if (cents != null) {
        paints.tagCents.color = tag.centsColor.toArgb()
        canvas.drawText(cents, left + padH + noteWidth + centsGap, baseline, paints.tagCents)
    }
}
