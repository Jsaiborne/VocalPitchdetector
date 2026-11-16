package com.jsaiborne.vocalpitchdetector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.sign

/** Readings this close together, with no silence between, belong to one stretch of curve. */
internal const val TRACE_CONTINUOUS_MS = 150L

/** A hole up to this long, with about the same pitch on both sides, is bridged (see [TraceRun.bridged]). */
internal const val TRACE_BRIDGE_MS = 250L

/** How far apart the two sides of a bridged hole may be. */
internal const val TRACE_BRIDGE_SEMITONES = 1f

/**
 * A bigger jump between neighbouring readings is a glitch, such as an octave error, and breaks the curve.
 * The detector smooths its readings, so a real leap never moves this far from one reading to the next.
 */
internal const val TRACE_BREAK_SEMITONES = 6f

/**
 * One continuous stretch of the curve: the points of the input from [start] up to (not including) [end],
 * none of them silent. A [bridged] run carries on from the run before it across a short hole on about the
 * same pitch, usually a reading or two the detector lost in the middle of a note.
 */
internal data class TraceRun(val start: Int, val end: Int, val bridged: Boolean)

/**
 * Splits pitch readings (in time order, NaN for silence) into runs of curve. The rule is in time and
 * semitones, so where the curve breaks doesn't change with the zoom or the orientation.
 */
internal fun <T> traceRuns(points: List<T>, timeOf: (T) -> Long, midiOf: (T) -> Float): List<TraceRun> {
    val runs = mutableListOf<TraceRun>()
    var start = -1
    var bridged = false
    // The last sung point, and whether a silence has come since
    var last = -1
    var silenceSince = false
    for (i in points.indices) {
        val midi = midiOf(points[i])
        if (midi.isNaN()) {
            silenceSince = true
            continue
        }
        if (last < 0) {
            start = i
        } else {
            val gapMs = timeOf(points[i]) - timeOf(points[last])
            val jump = abs(midi - midiOf(points[last]))
            val continuous = !silenceSince && gapMs <= TRACE_CONTINUOUS_MS && jump <= TRACE_BREAK_SEMITONES
            if (!continuous) {
                runs.add(TraceRun(start, last + 1, bridged))
                bridged = gapMs <= TRACE_BRIDGE_MS && jump <= TRACE_BRIDGE_SEMITONES
                start = i
            }
        }
        last = i
        silenceSince = false
    }
    if (start >= 0) runs.add(TraceRun(start, last + 1, bridged))
    return runs
}

/**
 * The sung pitch ready to draw: continuous [runs] of points, faint [bridges] across short holes, and the
 * live graph's [head], which carries the newest pitch on to "now".
 */
internal data class TraceShape(
    val runs: List<List<Offset>>,
    val bridges: List<Pair<Offset, Offset>> = emptyList(),
    val head: Pair<Offset, Offset>? = null
)

/**
 * Splits [points] into runs ([traceRuns]) and maps them to the graph with [toOffset]. A bridged run is
 * joined on to the run before it when [seamless], and otherwise gets a faint bridge to it.
 */
internal fun <T> buildTraceShape(
    points: List<T>,
    timeOf: (T) -> Long,
    midiOf: (T) -> Float,
    seamless: Boolean,
    toOffset: (T) -> Offset
): TraceShape {
    val runs = mutableListOf<MutableList<Offset>>()
    val bridges = mutableListOf<Pair<Offset, Offset>>()
    for (run in traceRuns(points, timeOf, midiOf)) {
        val offsets = (run.start until run.end).mapTo(mutableListOf<Offset>()) { toOffset(points[it]) }
        val previous = runs.lastOrNull()
        if (run.bridged && previous != null && seamless) {
            previous.addAll(offsets)
            continue
        }
        if (run.bridged && previous != null) bridges.add(previous.last() to offsets.first())
        runs.add(offsets)
    }
    return TraceShape(runs, bridges)
}

/**
 * Slopes for a monotone cubic through the points ([xs], [ys]), by Steffen's method. The curve passes
 * through every point and is smooth, but never goes above or below the points either side, so it shows
 * no peaks or scoops that weren't measured. Uneven spacing is fine; [xs] must not decrease.
 */
internal fun monotoneSlopes(xs: FloatArray, ys: FloatArray): FloatArray {
    val n = xs.size
    val slopes = FloatArray(n)
    if (n < 2) return slopes
    // Slope of each straight piece; one of zero length counts as flat
    val secants = FloatArray(n - 1) { i ->
        val width = xs[i + 1] - xs[i]
        if (width > 0f) (ys[i + 1] - ys[i]) / width else 0f
    }
    slopes[0] = secants[0]
    slopes[n - 1] = secants[n - 2]
    for (i in 1 until n - 1) {
        val widthBefore = xs[i] - xs[i - 1]
        val widthAfter = xs[i + 1] - xs[i]
        val before = secants[i - 1]
        val after = secants[i]
        // Slope of the parabola through the point and its neighbours
        val total = widthBefore + widthAfter
        val parabola = if (total > 0f) (before * widthAfter + after * widthBefore) / total else 0f
        // Flat at a peak or dip, and never steeper than either side allows
        slopes[i] = (sign(before) + sign(after)) * minOf(abs(before), abs(after), abs(parabola) / 2f)
    }
    return slopes
}

/**
 * The curve through [points] (in time order) as a monotone cubic along the time axis, which runs along y
 * when [timeAlongY] and along x otherwise. See [monotoneSlopes].
 */
internal fun monotonePath(points: List<Offset>, timeAlongY: Boolean): Path {
    val path = Path()
    val n = points.size
    if (n == 0) return path
    val times = FloatArray(n) { if (timeAlongY) points[it].y else points[it].x }
    val pitches = FloatArray(n) { if (timeAlongY) points[it].x else points[it].y }
    val slopes = monotoneSlopes(times, pitches)
    fun at(time: Float, pitch: Float) = if (timeAlongY) Offset(pitch, time) else Offset(time, pitch)

    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until n - 1) {
        // The Bézier form of the cubic: control points a third of the way along, on each end's slope
        val third = (times[i + 1] - times[i]) / 3f
        val c1 = at(times[i] + third, pitches[i] + slopes[i] * third)
        val c2 = at(times[i + 1] - third, pitches[i + 1] - slopes[i + 1] * third)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, points[i + 1].x, points[i + 1].y)
    }
    return path
}
