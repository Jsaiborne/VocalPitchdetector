package com.jsaiborne.vocalpitchdetector

import android.content.Context
import android.media.SoundPool
import kotlin.math.abs
import kotlin.math.pow

object SamplePlayer {
    private var soundPool: SoundPool? = null

    // Map to store: MIDI Note Number -> SoundPool ID
    private val sampleMap = mutableMapOf<Int, Int>()
    private var loadedSamplesCount = 0

    // Define your available samples and their MIDI values
    private val AVAILABLE_SAMPLES = mapOf(
        36 to R.raw.piano_c2,
        48 to R.raw.piano_c3,
        60 to R.raw.piano_c4,
        72 to R.raw.piano_c5
    )

    fun init(context: Context) {
        if (soundPool != null) return

        // Allow up to 8 sounds to play at once (polyphony)
        soundPool = SoundPool.Builder().setMaxStreams(8).build()

        for ((midi, resId) in AVAILABLE_SAMPLES) {
            val id = soundPool!!.load(context, resId, 1)
            sampleMap[midi] = id
        }

        soundPool!!.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) loadedSamplesCount++
        }
    }

    fun play(midi: Int): Boolean {
        // Return false if not ready
        if (soundPool == null || loadedSamplesCount < AVAILABLE_SAMPLES.size) return false

        val bestBaseMidi = sampleMap.keys.minByOrNull { abs(it - midi) } ?: return false
        val sampleId = sampleMap[bestBaseMidi] ?: return false

        val semitoneDelta = (midi - bestBaseMidi).toDouble()
        val rate = 2.0.pow(semitoneDelta / 12.0).toFloat()
        val clampedRate = rate.coerceIn(0.5f, 2.0f)

        soundPool?.play(sampleId, 1f, 1f, 1, 0, clampedRate)

        return true // Successfully played
    }

    // This function must exist for MainScreen.kt to compile
    fun release() {
        soundPool?.release()
        soundPool = null
        sampleMap.clear()
        loadedSamplesCount = 0
    }
}
