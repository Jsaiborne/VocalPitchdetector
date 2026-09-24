package com.jsaiborne.vocalpitchdetector

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun wavHeader_describesPcmData() {
        val file: File = tmp.newFile("header.wav")
        val dataBytes = 88200 // one second of 44.1 kHz mono 16-bit

        RandomAccessFile(file, "rw").use { raf ->
            raf.write(ByteArray(44 + dataBytes))
            writeWavHeader(raf, dataBytes, sampleRate = 44100, channels = 1, bitDepth = 16)
        }

        val header = ByteBuffer.wrap(file.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val tag = ByteArray(4)

        header.get(tag)
        assertEquals("RIFF", String(tag, Charsets.US_ASCII))
        assertEquals(dataBytes + 36, header.int)
        header.get(tag)
        assertEquals("WAVE", String(tag, Charsets.US_ASCII))
        header.get(tag)
        assertEquals("fmt ", String(tag, Charsets.US_ASCII))
        assertEquals(16, header.int)
        assertEquals(1, header.short.toInt()) // PCM
        assertEquals(1, header.short.toInt()) // mono
        assertEquals(44100, header.int)
        assertEquals(88200, header.int) // byte rate
        assertEquals(2, header.short.toInt()) // block align
        assertEquals(16, header.short.toInt())
        header.get(tag)
        assertEquals("data", String(tag, Charsets.US_ASCII))
        assertEquals(dataBytes, header.int)
    }

    @Test
    fun pitchSession_roundTripsThroughJson() {
        val pitch = listOf(
            RecordedPitchPoint(timestampMs = 0L, frequencyHz = 440f, midiNote = 69),
            RecordedPitchPoint(timestampMs = 12L, frequencyHz = 261.63f, midiNote = 60)
        )
        val stable = listOf(RecordedPitchPoint(timestampMs = 500L, frequencyHz = 440f, midiNote = 69))

        val root = JSONObject(serializePitchSession(pitch, stable))

        val pitchArray = root.getJSONArray("pitchData")
        assertEquals(2, pitchArray.length())
        assertEquals(12L, pitchArray.getJSONObject(1).getLong("timestampMs"))
        assertEquals(60, pitchArray.getJSONObject(1).getInt("midiNote"))
        assertEquals(261.63, pitchArray.getJSONObject(1).getDouble("frequencyHz"), 0.01)

        val stableArray = root.getJSONArray("stableNotes")
        assertEquals(1, stableArray.length())
        assertEquals(69, stableArray.getJSONObject(0).getInt("midiNote"))
    }

    @Test
    fun emptySession_stillProducesValidJson() {
        val root = JSONObject(serializePitchSession(emptyList(), emptyList()))

        assertTrue(root.has("pitchData"))
        assertTrue(root.has("stableNotes"))
        assertEquals(0, root.getJSONArray("pitchData").length())
    }
}
