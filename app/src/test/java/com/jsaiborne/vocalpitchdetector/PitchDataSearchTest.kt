package com.jsaiborne.vocalpitchdetector

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PitchDataSearchTest {

    private fun points(vararg timesMs: Long) =
        timesMs.map { RecordedPitchPoint(timestampMs = it, frequencyHz = 440f, midiNote = 69) }

    @Test
    fun firstIndexAtOrAfter_findsBoundaries() {
        val data = points(0, 10, 20, 30)

        assertEquals(0, data.firstIndexAtOrAfter(-5))
        assertEquals(0, data.firstIndexAtOrAfter(0))
        assertEquals(1, data.firstIndexAtOrAfter(5))
        assertEquals(1, data.firstIndexAtOrAfter(10))
        assertEquals(3, data.firstIndexAtOrAfter(30))
        assertEquals(4, data.firstIndexAtOrAfter(31))
        assertEquals(0, emptyList<RecordedPitchPoint>().firstIndexAtOrAfter(10))
    }

    @Test
    fun nearestTo_picksClosestPoint() {
        val data = points(0, 10, 20, 30)

        assertEquals(10L, data.nearestTo(14)!!.timestampMs)
        assertEquals(20L, data.nearestTo(16)!!.timestampMs)
        assertEquals(0L, data.nearestTo(-100)!!.timestampMs)
        assertEquals(30L, data.nearestTo(1000)!!.timestampMs)
        assertNull(emptyList<RecordedPitchPoint>().nearestTo(5))
    }

    @Test
    fun nearestTo_matchesLinearScanOnEveryQuery() {
        val data = points(0, 7, 19, 20, 55, 130, 131, 400)

        for (t in -10L..420L) {
            val expected = data.minByOrNull { kotlin.math.abs(it.timestampMs - t) }!!
            val actual = data.nearestTo(t)!!
            assertEquals(
                "t=$t",
                kotlin.math.abs(expected.timestampMs - t),
                kotlin.math.abs(actual.timestampMs - t)
            )
        }
    }

    @Test
    fun buildTraceSegments_splitsOnSilenceGap() {
        val data = points(0, 10, 20, 500, 510)

        val segments = buildTraceSegments(data, 0, 10_000, breakDistance = 1_000f) {
            Offset(it.timestampMs.toFloat(), 0f)
        }

        assertEquals(2, segments.size)
        assertEquals(3, segments[0].size)
        assertEquals(2, segments[1].size)
    }

    @Test
    fun buildTraceSegments_splitsOnLargeJump() {
        val data = listOf(
            RecordedPitchPoint(0, 440f, 69),
            RecordedPitchPoint(10, 880f, 81),
            RecordedPitchPoint(20, 880f, 81)
        )

        val segments = buildTraceSegments(data, 0, 10_000, breakDistance = 50f) { Offset(0f, it.midiNote * 10f) }

        assertEquals(2, segments.size)
        assertEquals(1, segments[0].size)
        assertEquals(2, segments[1].size)
    }

    @Test
    fun buildTraceSegments_onlyIncludesRequestedWindow() {
        val data = points(0, 10, 20, 30, 40)

        val segments = buildTraceSegments(data, 10, 30, breakDistance = 1_000f) {
            Offset(it.timestampMs.toFloat(), 0f)
        }

        assertEquals(1, segments.size)
        assertEquals(listOf(10f, 20f, 30f), segments[0].map { it.x })
    }
}
