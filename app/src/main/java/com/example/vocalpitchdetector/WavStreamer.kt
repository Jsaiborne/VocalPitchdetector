package com.example.vocalpitchdetector

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight thread-safe streaming WAV writer.
 * Write 16-bit PCM little-endian via writeFromShorts(shorts, len).
 * Call start(tempFile) before writing. After you're done call stopAndFinalize() to patch header.
 */
class WavStreamer(private val sampleRate: Int) {
    private var fos: FileOutputStream? = null
    private var tempFile: File? = null
    private var totalPcmBytes: Long = 0L
    private val recording = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    // start writing to temp file (will write WAV header placeholder)
    @Synchronized
    fun start(temp: File) {
        discardInternal()
        tempFile = temp
        fos = FileOutputStream(temp)
        totalPcmBytes = 0L
        writeWavHeaderPlaceholder(fos!!)
        try { fos!!.fd.sync() } catch (_: Exception) {}
        recording.set(true)
        paused.set(false)
    }

    @Synchronized
    fun pause() { paused.set(true) }

    @Synchronized
    fun resume() { paused.set(false) }

    /**
     * Write PCM data supplied as ShortArray (16-bit signed). This method is safe to call
     * from your audio thread; it does a synchronized write to underlying stream.
     */
    @Synchronized
    fun writeFromShorts(shorts: ShortArray, len: Int) {
        if (!recording.get() || paused.get()) return
// Convert shorts to little-endian bytes
        val bb = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until len) bb.putShort(shorts[i])
        val arr = bb.array()
        try {
            fos?.write(arr)
            totalPcmBytes += arr.size
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Stop writing and finalize WAV header. Returns the finalized file on success, null on error.
     */
    @Synchronized
    fun stopAndFinalize(): File? {
        recording.set(false)
        paused.set(false)
        try {
            fos?.flush()
            try { fos?.fd?.sync() } catch (_: Exception) {}
            fos?.close()
            fos = null
            val f = tempFile ?: return null
            patchWavHeader(f, totalPcmBytes.toInt())
// reset state
            tempFile = null
            totalPcmBytes = 0L
            return f
        } catch (e: Exception) {
            e.printStackTrace()
            try { fos?.close() } catch (_: Exception) {}
            fos = null
            tempFile = null
            totalPcmBytes = 0L
            return null
        }
    }

    @Synchronized
    fun discard() {
        recording.set(false)
        paused.set(false)
        discardInternal()
    }

    private fun discardInternal() {
        try { fos?.close() } catch (_: Exception) {}
        fos = null
        try { tempFile?.delete() } catch (_: Exception) {}
        tempFile = null
        totalPcmBytes = 0L
    }

    // --- WAV header helpers ---
    private fun writeWavHeaderPlaceholder(out: FileOutputStream) {
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intToLE(0)) // placeholder for chunk size
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intToLE(16)) // subchunk1 size
        out.write(shortToLE(1)) // PCM
        out.write(shortToLE(1)) // channels = 1 (mono)
        out.write(intToLE(sampleRate))
        val byteRate = sampleRate * 1 * 16 / 8
        out.write(intToLE(byteRate))
        out.write(shortToLE((1 * 16 / 8).toShort().toInt())) // block align
        out.write(shortToLE(16)) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intToLE(0)) // placeholder for data chunk size
    }

    private fun patchWavHeader(file: File, dataBytes: Int) {
        val totalDataLen = dataBytes + 36
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToLE(totalDataLen))
            raf.seek(40)
            raf.write(intToLE(dataBytes))
        }
    }

    private fun intToLE(i: Int): ByteArray {
        return byteArrayOf((i and 0xff).toByte(), ((i shr 8) and 0xff).toByte(), ((i shr 16) and 0xff).toByte(), ((i shr 24) and 0xff).toByte())
    }
    private fun shortToLE(s: Int): ByteArray {
        return byteArrayOf((s and 0xff).toByte(), ((s shr 8) and 0xff).toByte())
    }
}
