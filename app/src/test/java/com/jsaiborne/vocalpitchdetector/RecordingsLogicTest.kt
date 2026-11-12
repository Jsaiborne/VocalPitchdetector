package com.jsaiborne.vocalpitchdetector

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- Sorting, filtering and searching ---

    private fun session(
        id: String,
        number: Int = 0,
        name: String? = null,
        starred: Boolean = false,
        durationMs: Long = 0L,
        note: String? = null
    ) = RecordingSession(
        sessionId = id,
        timestampMs = id.toLong(),
        audioFile = File("session_${id}_audio.wav"),
        pitchFile = File("session_${id}_pitch.json"),
        sessionNumber = number,
        customName = name,
        isStarred = starred,
        durationMs = durationMs,
        note = note
    )

    private fun ids(sessions: List<RecordingSession>) = sessions.map { it.sessionId }

    @Test
    fun filterAndSort_ordersByTheChosenSort() {
        val sessions = listOf(
            session("200", name = "Bravo", durationMs = 5_000),
            session("100", name = "alpha", durationMs = 30_000),
            session("300", name = "Charlie", durationMs = 10_000)
        )

        fun sorted(sort: RecordingSort) = ids(filterAndSortSessions(sessions, "", false, sort))

        assertEquals(listOf("300", "200", "100"), sorted(RecordingSort.NEWEST))
        assertEquals(listOf("100", "200", "300"), sorted(RecordingSort.OLDEST))
        assertEquals(listOf("100", "300", "200"), sorted(RecordingSort.LONGEST))
        assertEquals(listOf("100", "200", "300"), sorted(RecordingSort.NAME)) // alpha, Bravo, Charlie
    }

    @Test
    fun filterAndSort_keepsStarredOnTopWhateverTheSort() {
        val sessions = listOf(
            session("100", name = "A"),
            session("200", name = "B", starred = true),
            session("300", name = "C")
        )

        assertEquals("200", ids(filterAndSortSessions(sessions, "", false, RecordingSort.NEWEST)).first())
        assertEquals("200", ids(filterAndSortSessions(sessions, "", false, RecordingSort.OLDEST)).first())
        assertEquals("200", ids(filterAndSortSessions(sessions, "", false, RecordingSort.NAME)).first())
    }

    @Test
    fun filterAndSort_searchMatchesNameNoteAndDefaultTitleIgnoringCase() {
        val sessions = listOf(
            session("100", name = "Chorus take"),
            session("200", number = 7, note = "Watch the HIGH notes"),
            session("300", number = 3)
        )

        fun search(text: String) = ids(filterAndSortSessions(sessions, text, false, RecordingSort.OLDEST))

        assertEquals(listOf("100"), search("chorus"))
        assertEquals(listOf("200"), search("high"))
        assertEquals(listOf("200"), search("  session 7  ")) // "Vocal Session 7", surrounding spaces ignored
        assertEquals(listOf("200", "300"), search("vocal"))
        assertEquals(emptyList<String>(), search("nothing like this"))
    }

    @Test
    fun filterAndSort_starredOnlyHidesTheRest() {
        val sessions = listOf(session("100"), session("200", starred = true), session("300", starred = true))

        val shown = filterAndSortSessions(sessions, "", true, RecordingSort.NEWEST)

        assertEquals(listOf("300", "200"), ids(shown))
    }

    @Test
    fun starTargetFor_unstarsOnlyWhenEverythingIsAlreadyStarred() {
        assertFalse(starTargetFor(listOf(session("1", starred = true), session("2", starred = true))))
        assertTrue(starTargetFor(listOf(session("1", starred = true), session("2"))))
        assertTrue(starTargetFor(listOf(session("1"), session("2"))))
    }

    // --- Leftover files ---

    @Test
    fun findOrphanFiles_returnsOnlyOldFilesWhosePartnerIsMissing() {
        val dir: File = tmp.newFolder("orphans")
        val now = 10_000_000L
        val old = now - 5 * 60_000L

        fun create(name: String, modifiedAt: Long) {
            File(dir, name).apply {
                writeText("x")
                setLastModified(modifiedAt)
            }
        }
        create("session_1_audio.wav", old) // complete pair
        create("session_1_pitch.json", old)
        create("session_2_audio.wav", old) // audio only
        create("session_3_pitch.json", old) // pitch only
        create("session_4_audio.wav", now - 10_000L) // audio only, but just written: may still be saving
        create("notes.txt", old) // not a session file

        val orphans = findOrphanFiles(dir, now).map { it.name }

        assertEquals(listOf("session_2_audio.wav", "session_3_pitch.json"), orphans)
    }

    @Test
    fun findOrphanFiles_isEmptyWhenTheFolderDoesNotExist() {
        assertTrue(findOrphanFiles(File(tmp.root, "missing"), nowMs = 1L).isEmpty())
    }
}
