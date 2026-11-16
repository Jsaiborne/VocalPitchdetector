package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleClockTest {

    // 1 sample per ms, so a read of 20 samples is 20 ms of audio
    private fun clock() = SampleClock(sampleRate = 1000)

    @Test
    fun firstRead_endsAtItsReturnTime() {
        assertEquals(5_000L, clock().advance(20, wallMs = 5_000))
    }

    @Test
    fun jitteryReads_giveEvenlySpacedTimes() {
        val clock = clock()
        // The audio starts at 10 000 ms; each read comes back 5 ms late plus 0–8 ms of jitter
        val jitter = listOf(0L, 6L, 3L, 8L, 1L, 7L, 2L)

        val times = jitter.mapIndexed { i, j -> clock.advance(20, wallMs = 10_000 + 20 * (i + 1) + 5 + j) }

        assertEquals(jitter.indices.map { 10_005L + 20 * (it + 1) }, times)
    }

    @Test
    fun lateFirstRead_isCorrectedByAnEarlierOne() {
        val clock = clock()

        clock.advance(20, wallMs = 10_030) // 10 ms late
        val times = listOf(10_040L, 10_060L, 10_080L).map { clock.advance(20, wallMs = it) }

        assertEquals(listOf(10_040L, 10_060L, 10_080L), times)
    }

    @Test
    fun burstOfReads_isSpreadOut() {
        val clock = clock()
        // The third read is held up and comes back with the fourth
        val returns = listOf(10_020L, 10_040L, 10_078L, 10_080L, 10_100L)

        val times = returns.map { clock.advance(20, wallMs = it) }

        assertEquals(listOf(10_020L, 10_040L, 10_060L, 10_080L, 10_100L), times)
    }

    @Test
    fun lostAudio_reanchorsToTheWallClock() {
        val clock = clock()
        clock.advance(20, wallMs = 10_020)
        clock.advance(20, wallMs = 10_040)

        // 160 ms passed but only 20 ms of audio arrived: the rest was lost
        assertEquals(10_200L, clock.advance(20, wallMs = 10_200))
        assertEquals(10_220L, clock.advance(20, wallMs = 10_220))
    }

    @Test
    fun smallLag_staysOnTheAudioClock() {
        val clock = clock()
        clock.advance(20, wallMs = 10_020)

        // 90 ms late is within the allowed lag, so the time still follows the audio
        assertEquals(10_040L, clock.advance(20, wallMs = 10_130))
    }
}
