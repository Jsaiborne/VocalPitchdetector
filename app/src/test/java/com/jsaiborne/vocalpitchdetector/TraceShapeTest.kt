package com.jsaiborne.vocalpitchdetector

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceShapeTest {

    private data class Reading(val t: Long, val midi: Float)

    private val silence = Float.NaN

    /** Readings every 46 ms from [fromMs], one per pitch. */
    private fun sung(fromMs: Long, vararg midis: Float) = midis.mapIndexed { i, m -> Reading(fromMs + i * 46L, m) }

    private fun runsOf(readings: List<Reading>) = traceRuns(readings, Reading::t, Reading::midi)

    private fun shapeOf(readings: List<Reading>, seamless: Boolean) =
        buildTraceShape(readings, Reading::t, Reading::midi, seamless) { Offset(it.t.toFloat(), it.midi) }

    /** The cubic between two points with slopes [m0] and [m1], evaluated at [x]. */
    @Suppress("LongParameterList")
    private fun hermite(x0: Float, x1: Float, y0: Float, y1: Float, m0: Float, m1: Float, x: Float): Float {
        val h = x1 - x0
        val t = (x - x0) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m0 +
            (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m1
    }

    /** The curve sampled finely across every piece, as (piece index, value) pairs. */
    private fun sampleCurve(xs: FloatArray, ys: FloatArray): List<Pair<Int, Float>> {
        val slopes = monotoneSlopes(xs, ys)
        val samples = mutableListOf<Pair<Int, Float>>()
        for (i in 0 until xs.size - 1) {
            for (step in 0..20) {
                val x = xs[i] + (xs[i + 1] - xs[i]) * step / 20f
                samples += i to hermite(xs[i], xs[i + 1], ys[i], ys[i + 1], slopes[i], slopes[i + 1], x)
            }
        }
        return samples
    }

    // --- monotoneSlopes ---

    @Test
    fun slopes_straightLineStaysStraight() {
        val slopes = monotoneSlopes(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 2f, 4f, 6f))

        for (s in slopes) assertEquals(2f, s, 1e-5f)
    }

    @Test
    fun slopes_stepDoesNotOvershoot() {
        val xs = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f)
        val ys = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f)

        for ((i, y) in sampleCurve(xs, ys)) {
            val low = minOf(ys[i], ys[i + 1])
            val high = maxOf(ys[i], ys[i + 1])
            assertTrue("piece $i went to $y", y >= low - 1e-4f && y <= high + 1e-4f)
        }
    }

    @Test
    fun slopes_peakIsFlatAtTheReading() {
        val slopes = monotoneSlopes(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 5f, 0f))

        assertEquals(0f, slopes[1], 0f)
    }

    @Test
    fun slopes_unevenSpacingStaysMonotone() {
        val xs = floatArrayOf(0f, 1f, 1.5f, 4f, 4.2f, 7f)
        val ys = floatArrayOf(0f, 3f, 3.5f, 8f, 9f, 9.5f)

        val samples = sampleCurve(xs, ys)
        for (k in 1 until samples.size) {
            assertTrue("sample $k goes back down", samples[k].second >= samples[k - 1].second - 1e-4f)
        }
        for ((i, y) in samples) {
            assertTrue("piece $i went to $y", y >= ys[i] - 1e-4f && y <= ys[i + 1] + 1e-4f)
        }
    }

    @Test
    fun slopes_equalTimesGiveNoNaN() {
        val slopes = monotoneSlopes(floatArrayOf(0f, 1f, 1f, 2f), floatArrayOf(0f, 1f, 2f, 3f))
        val allEqual = monotoneSlopes(floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 4f, 2f))

        for (s in slopes + allEqual) assertFalse(s.isNaN())
    }

    @Test
    fun slopes_shortInputs() {
        assertEquals(0, monotoneSlopes(floatArrayOf(), floatArrayOf()).size)
        assertEquals(0f, monotoneSlopes(floatArrayOf(3f), floatArrayOf(60f))[0], 0f)
    }

    // --- traceRuns ---

    @Test
    fun heldNoteIsOneRun() {
        val readings = sung(0, 60f, 60.1f, 59.9f, 60.2f, 60f, 59.8f, 60f, 60.1f)

        assertEquals(listOf(TraceRun(0, 8, bridged = false)), runsOf(readings))
    }

    @Test
    fun shortDropoutOnTheSamePitchIsBridged() {
        // The detector loses the note for ~230 ms and reports silence once in between
        val readings = sung(0, 60f, 60f, 60.2f) + Reading(138, silence) + sung(322, 60.1f, 60f)

        assertEquals(
            listOf(TraceRun(0, 3, bridged = false), TraceRun(4, 6, bridged = true)),
            runsOf(readings)
        )
    }

    @Test
    fun shortDropoutIntoAnotherNoteIsNotBridged() {
        val readings = sung(0, 60f, 60f, 60f) + Reading(138, silence) + sung(322, 63f, 63f)

        assertEquals(
            listOf(TraceRun(0, 3, bridged = false), TraceRun(4, 6, bridged = false)),
            runsOf(readings)
        )
    }

    @Test
    fun longSilenceBreaksTheCurve() {
        val readings = sung(0, 60f, 60f) + Reading(92, silence) + sung(700, 60f, 60f)

        assertEquals(
            listOf(TraceRun(0, 2, bridged = false), TraceRun(3, 5, bridged = false)),
            runsOf(readings)
        )
    }

    @Test
    fun octaveGlitchBreaksWithoutABridge() {
        val readings = sung(0, 60f, 60f, 72f, 60f, 60f)

        assertEquals(
            listOf(
                TraceRun(0, 2, bridged = false),
                TraceRun(2, 3, bridged = false),
                TraceRun(3, 5, bridged = false)
            ),
            runsOf(readings)
        )
    }

    @Test
    fun glideWithinOneReadingGapStaysJoined() {
        // Five semitones from one reading to the next is a fast leap, not a glitch
        val readings = sung(0, 60f, 62f, 67f, 67f)

        assertEquals(listOf(TraceRun(0, 4, bridged = false)), runsOf(readings))
    }

    @Test
    fun recordingGaps() {
        // Recorded data has no silence markers, only gaps in time
        assertEquals(
            listOf(TraceRun(0, 2, bridged = false)),
            runsOf(listOf(Reading(0, 60f), Reading(100, 60f)))
        )
        assertEquals(
            listOf(TraceRun(0, 1, bridged = false), TraceRun(1, 2, bridged = true)),
            runsOf(listOf(Reading(0, 60f), Reading(200, 60.5f)))
        )
    }

    @Test
    fun silenceOnlyOrNothingGivesNoRuns() {
        assertTrue(runsOf(emptyList()).isEmpty())
        assertTrue(runsOf(listOf(Reading(0, silence), Reading(46, silence))).isEmpty())
    }

    @Test
    fun leadingAndTrailingSilenceIsSkipped() {
        val readings = listOf(Reading(0, silence)) + sung(46, 60f, 60f) + Reading(138, silence)

        assertEquals(listOf(TraceRun(1, 3, bridged = false)), runsOf(readings))
    }

    // --- buildTraceShape ---

    @Test
    fun bridgedRunGetsAFaintBridgeByDefault() {
        val readings = sung(0, 60f, 60f) + Reading(92, silence) + sung(280, 60f, 60f)

        val shape = shapeOf(readings, seamless = false)

        assertEquals(2, shape.runs.size)
        assertEquals(listOf(Offset(46f, 60f) to Offset(280f, 60f)), shape.bridges)
    }

    @Test
    fun seamlessJoinsTheBridgedRun() {
        val readings = sung(0, 60f, 60f) + Reading(92, silence) + sung(280, 60f, 60f)

        val shape = shapeOf(readings, seamless = true)

        assertEquals(1, shape.runs.size)
        assertEquals(listOf(0f, 46f, 280f, 326f), shape.runs[0].map { it.x })
        assertTrue(shape.bridges.isEmpty())
    }

    @Test
    fun unbridgedBreaksStayOpenEitherWay() {
        val readings = sung(0, 60f, 60f) + Reading(92, silence) + sung(700, 60f, 60f)

        for (seamless in listOf(false, true)) {
            val shape = shapeOf(readings, seamless)
            assertEquals(2, shape.runs.size)
            assertTrue(shape.bridges.isEmpty())
        }
    }
}
