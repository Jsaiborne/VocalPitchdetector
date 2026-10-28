@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

/** The editor shows every pattern rooted on C4; the root rows are highlighted. */
internal const val EDITOR_ROOT_MIDI = 60

private const val STEP_WIDTH_DP = 16
private const val ROW_HEIGHT_DP = 28
private const val GUTTER_DP = 40
private const val RULER_DP = 20
private const val HANDLE_TOUCH_DP = 20
private const val ROW_COUNT = EDITOR_MAX_OFFSET - EDITOR_MIN_OFFSET + 1

/** Empty beats shown after the last note, to draw into. */
private const val SPARE_STEPS = 4 * STEPS_PER_BEAT
private const val MIN_SHOWN_STEPS = 8 * STEPS_PER_BEAT
private val BLACK_KEY_PITCH_CLASSES = setOf(1, 3, 6, 8, 10)

/**
 * What the editor does with touches on the grid. Moves and resizes are relative to the note as it
 * was when the drag began.
 */
internal data class PianoRollActions(
    val onTapCell: (step: Int, offset: Int) -> Unit,
    val onDragStart: (RollNote) -> Unit,
    val onMove: (note: RollNote, start: Int, offset: Int) -> Unit,
    val onResize: (note: RollNote, length: Int) -> Unit,
    val onDragEnd: () -> Unit
)

/** What a touch started on. */
private sealed interface RollTarget {
    val note: RollNote?

    data object Grid : RollTarget {
        override val note: RollNote? = null
    }

    data class Body(override val note: RollNote) : RollTarget

    data class Handle(override val note: RollNote) : RollTarget
}

/** Everything the touch handling needs, refreshed on every recomposition. */
private class RollInput(
    val notes: List<RollNote>,
    val selected: RollNote?,
    val geometry: RollGeometry,
    val actions: PianoRollActions,
    val handlePx: Float,
    val onScroll: (dx: Float, dy: Float) -> Unit
)

/** Converts between grid cells and pixels on screen, given how far the grid is scrolled. */
private class RollGeometry(
    val stepPx: Float,
    val rowPx: Float,
    val gutterPx: Float,
    val rulerPx: Float,
    val scrollX: Float,
    val scrollY: Float
) {
    fun x(step: Float): Float = gutterPx + step * stepPx - scrollX

    /** Top edge of the row for [offset]. */
    fun y(offset: Int): Float = rulerPx + (EDITOR_MAX_OFFSET - offset) * rowPx - scrollY

    fun stepAt(x: Float): Int = floor((x - gutterPx + scrollX) / stepPx).toInt()

    fun offsetAt(y: Float): Int = EDITOR_MAX_OFFSET - floor((y - rulerPx + scrollY) / rowPx).toInt()
}

/**
 * The piano-roll grid: pitch runs up the screen, time to the right, in quarter-beat steps. Tap
 * a cell to add a note, drag a note to move it, drag the selected note's end to change its length,
 * and drag anywhere else to scroll.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun PianoRoll(
    notes: List<RollNote>,
    selected: RollNote?,
    playheadStep: Float?,
    actions: PianoRollActions,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val stepPx = with(density) { STEP_WIDTH_DP.dp.toPx() }
    val rowPx = with(density) { ROW_HEIGHT_DP.dp.toPx() }
    val gutterPx = with(density) { GUTTER_DP.dp.toPx() }
    val rulerPx = with(density) { RULER_DP.dp.toPx() }
    val handlePx = with(density) { HANDLE_TOUCH_DP.dp.toPx() }
    val textPx = with(density) { 11.sp.toPx() }

    val colors = MaterialTheme.colorScheme
    val labelPaint = remember(colors.onSurfaceVariant, textPx) {
        Paint().apply {
            isAntiAlias = true
            textSize = textPx
            color = colors.onSurfaceVariant.toArgb()
        }
    }
    val rootLabelPaint = remember(colors.primary, textPx) {
        Paint(labelPaint).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = colors.primary.toArgb()
        }
    }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }
    var centred by remember { mutableStateOf(false) }

    val shownSteps = (notes.endStep + SPARE_STEPS).coerceIn(MIN_SHOWN_STEPS, MAX_PATTERN_STEPS)
    val maxScrollX = (gutterPx + shownSteps * stepPx - viewSize.width).coerceAtLeast(0f)
    val maxScrollY = (rulerPx + ROW_COUNT * rowPx - viewSize.height).coerceAtLeast(0f)
    val geometry = RollGeometry(
        stepPx,
        rowPx,
        gutterPx,
        rulerPx,
        scrollX.coerceIn(0f, maxScrollX),
        scrollY.coerceIn(0f, maxScrollY)
    )

    // Start with the pattern (or the octave above the root) in the middle of the view
    LaunchedEffect(viewSize) {
        if (centred || viewSize.height == 0) return@LaunchedEffect
        val middle = if (notes.isEmpty()) 6f else (notes.minOf { it.offset } + notes.maxOf { it.offset }) / 2f
        scrollY = ((EDITOR_MAX_OFFSET - middle + 0.5f) * rowPx - (viewSize.height - rulerPx) / 2f)
            .coerceIn(0f, maxScrollY)
        centred = true
    }

    val input = rememberUpdatedState(
        RollInput(notes, selected, geometry, actions, handlePx) { dx, dy ->
            scrollX = (scrollX.coerceIn(0f, maxScrollX) + dx).coerceIn(0f, maxScrollX)
            scrollY = (scrollY.coerceIn(0f, maxScrollY) + dy).coerceIn(0f, maxScrollY)
        }
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .rollGestures(input)
    ) {
        val g = geometry
        val gridBottom = g.y(EDITOR_MIN_OFFSET) + rowPx

        // Rows: the roots stand out, and the black keys are shaded like a keyboard
        for (offset in EDITOR_MIN_OFFSET..EDITOR_MAX_OFFSET) {
            val top = g.y(offset)
            if (top < size.height && top + rowPx > rulerPx) {
                val midi = EDITOR_ROOT_MIDI + offset
                val fill = when {
                    offset % 12 == 0 -> colors.primary.copy(alpha = 0.14f)
                    midi % 12 in BLACK_KEY_PITCH_CLASSES -> colors.onSurface.copy(alpha = 0.06f)
                    else -> null
                }
                fill?.let { drawRect(it, Offset(gutterPx, top), Size(size.width - gutterPx, rowPx)) }
                drawLine(colors.outlineVariant.copy(alpha = 0.4f), Offset(gutterPx, top), Offset(size.width, top))
            }
        }

        // Steps: beat lines are stronger than the quarter-beat lines between them
        val firstStep = g.stepAt(gutterPx).coerceAtLeast(0)
        val lastStep = minOf(g.stepAt(size.width) + 1, MAX_PATTERN_STEPS)
        for (step in firstStep..lastStep) {
            val x = g.x(step.toFloat())
            val onBeat = step % STEPS_PER_BEAT == 0
            drawLine(
                if (onBeat) colors.outline.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.3f),
                Offset(x, rulerPx),
                Offset(x, gridBottom),
                strokeWidth = if (onBeat) 2f else 1f
            )
        }
        val limitX = g.x(MAX_PATTERN_STEPS.toFloat())
        if (limitX < size.width) {
            drawRect(
                colors.onSurface.copy(alpha = 0.12f),
                Offset(limitX, rulerPx),
                Size(size.width - limitX, size.height)
            )
        }

        // Notes, the selected one with a grip at its end for changing the length
        for (note in notes) {
            val left = g.x(note.start.toFloat()) + 1f
            val top = g.y(note.offset) + 3f
            val width = note.length * stepPx - 2f
            val height = rowPx - 6f
            val isSelected = note == selected
            drawRoundRect(
                if (isSelected) colors.tertiary else colors.primary,
                Offset(left, top),
                Size(width, height),
                CornerRadius(6f)
            )
            if (isSelected) {
                val gripWidth = 6f
                drawRoundRect(
                    colors.onTertiary,
                    Offset(left + width - gripWidth - 4f, top + height * 0.25f),
                    Size(gripWidth, height * 0.5f),
                    CornerRadius(gripWidth / 2f)
                )
            }
        }

        playheadStep?.let {
            val x = g.x(it)
            drawLine(colors.error, Offset(x, rulerPx), Offset(x, gridBottom), strokeWidth = 3f)
        }

        // Note names down the side, beat numbers across the top; both stay put while the grid scrolls
        drawRect(colors.surface, Offset.Zero, Size(gutterPx, size.height))
        drawRect(colors.surface, Offset.Zero, Size(size.width, rulerPx))
        val canvas = drawContext.canvas.nativeCanvas
        for (offset in EDITOR_MIN_OFFSET..EDITOR_MAX_OFFSET) {
            val top = g.y(offset)
            if (top < size.height && top + rowPx > rulerPx) {
                val paint = if (offset % 12 == 0) rootLabelPaint else labelPaint
                canvas.drawText(midiToDisplayName(EDITOR_ROOT_MIDI + offset), 6f, top + rowPx / 2f + textPx / 3f, paint)
            }
        }
        for (step in firstStep..lastStep) {
            if (step % STEPS_PER_BEAT == 0 && step < MAX_PATTERN_STEPS && g.x(step.toFloat()) >= gutterPx) {
                canvas.drawText("${step / STEPS_PER_BEAT + 1}", g.x(step.toFloat()) + 4f, rulerPx - 6f, labelPaint)
            }
        }
    }
}

/**
 * Taps add notes; drags move a note, stretch the selected one by its grip, or scroll the grid.
 * A drag only starts once the finger has moved past the touch slop, so a tap never nudges a note.
 */
private fun Modifier.rollGestures(input: State<RollInput>): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val atDown = input.value
        val g = atDown.geometry
        val step = g.stepAt(down.position.x)
        val offset = g.offsetAt(down.position.y)
        val inGrid = down.position.x >= g.gutterPx && down.position.y >= g.rulerPx &&
            offset in EDITOR_MIN_OFFSET..EDITOR_MAX_OFFSET
        val target = if (inGrid) rollTargetAt(down.position, step, offset, atDown) else RollTarget.Grid

        var dragging = false
        var last = down.position
        do {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change != null && change.pressed) {
                val total = change.position - down.position
                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    target.note?.let(input.value.actions.onDragStart)
                }
                if (dragging) {
                    dragTo(target, total, change.position - last, input.value)
                    change.consume()
                }
                last = change.position
            }
        } while (change?.pressed == true)

        val actions = input.value.actions
        when {
            !dragging && inGrid && step in 0 until MAX_PATTERN_STEPS -> actions.onTapCell(step, offset)
            dragging && target.note != null -> actions.onDragEnd()
        }
    }
}

/** Applies a drag that has moved [total] since it began and [delta] since the last event. */
private fun dragTo(target: RollTarget, total: Offset, delta: Offset, input: RollInput) {
    val steps = (total.x / input.geometry.stepPx).roundToInt()
    when (target) {
        is RollTarget.Body -> input.actions.onMove(
            target.note,
            target.note.start + steps,
            target.note.offset - (total.y / input.geometry.rowPx).roundToInt()
        )
        is RollTarget.Handle -> input.actions.onResize(target.note, target.note.length + steps)
        RollTarget.Grid -> input.onScroll(-delta.x, -delta.y)
    }
}

/** Which note (or the selected note's grip) is under [position], or the empty grid. */
private fun rollTargetAt(position: Offset, step: Int, offset: Int, input: RollInput): RollTarget {
    val selected = input.selected
    if (selected != null && selected.offset == offset) {
        // The grip reaches a little past the note's end so short notes can still be moved by their middle
        val endX = input.geometry.x(selected.end.toFloat())
        val inside = minOf(input.handlePx / 2f, selected.length * input.geometry.stepPx / 3f)
        if (position.x in (endX - inside)..(endX + input.handlePx * 0.75f)) return RollTarget.Handle(selected)
    }
    return input.notes.noteAt(step, offset)?.let { RollTarget.Body(it) } ?: RollTarget.Grid
}
