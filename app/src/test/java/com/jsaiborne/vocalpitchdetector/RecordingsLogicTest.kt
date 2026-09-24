package com.jsaiborne.vocalpitchdetector

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingsLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun assignSessionNumbers_numbersOldestFirstFromOne() {
        val (numbers, next) = assignSessionNumbers(
            existing = emptyMap(),
            nextNumber = 1,
            timestamps = mapOf("300" to 300L, "100" to 100L, "200" to 200L)
        )

        assertEquals(mapOf("100" to 1, "200" to 2, "300" to 3), numbers)
        assertEquals(4, next)
    }

    @Test
    fun assignSessionNumbers_keepsExistingNumbersWhenAnOlderSessionIsDeleted() {
        // Sessions 1,2,3 existed; #1 was deleted; a new one arrives
        val (numbers, next) = assignSessionNumbers(
            existing = mapOf("200" to 2, "300" to 3),
            nextNumber = 4,
            timestamps = mapOf("200" to 200L, "300" to 300L, "400" to 400L)
        )

        assertEquals(2, numbers["200"])
        assertEquals(3, numbers["300"])
        assertEquals(4, numbers["400"])
        assertEquals(5, next)
    }

    @Test
    fun assignSessionNumbers_neverReusesANumberAfterDeletingTheNewest() {
        // #3 was deleted, but the counter had already moved on to 4
        val (numbers, _) = assignSessionNumbers(
            existing = mapOf("100" to 1, "200" to 2),
            nextNumber = 4,
            timestamps = mapOf("100" to 100L, "200" to 200L, "500" to 500L)
        )

        assertEquals(4, numbers["500"])
    }

    @Test
    fun assignSessionNumbers_ignoresStaleExistingEntries() {
        val (numbers, _) = assignSessionNumbers(
            existing = mapOf("gone" to 7),
            nextNumber = 8,
            timestamps = mapOf("100" to 100L)
        )

        assertEquals(mapOf("100" to 8), numbers)
    }

    @Test
    fun wavDuration_isDerivedFromDataSize() {
        assertEquals(0L, wavDurationMs(0L))
        assertEquals(0L, wavDurationMs(44L)) // header only
        assertEquals(1000L, wavDurationMs(44L + 88200L))
        assertEquals(2500L, wavDurationMs(44L + 88200L * 5 / 2))
    }

    @Test
    fun formatDuration_usesMinutesAndZeroPaddedSeconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_999))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("12:00", formatDuration(720_000))
    }

    @Test
    fun listSessionFiles_onlyReturnsCompletePairs() {
        val dir: File = tmp.newFolder("recordings")
        File(dir, "session_1_audio.wav").writeText("a")
        File(dir, "session_1_pitch.json").writeText("{}")
        File(dir, "session_2_audio.wav").writeText("a") // no pitch file -> incomplete
        File(dir, "session_3_pitch.json").writeText("{}") // no audio file -> incomplete
        File(dir, "notes.txt").writeText("ignore me")

        val sessions = listSessionFiles(dir)

        assertEquals(setOf("1"), sessions.keys)
        assertTrue(sessions.getValue("1").first.name.endsWith("_audio.wav"))
        assertTrue(sessions.getValue("1").second.name.endsWith("_pitch.json"))
    }
}
