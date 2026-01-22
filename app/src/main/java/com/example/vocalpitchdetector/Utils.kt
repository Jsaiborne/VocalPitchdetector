package com.example.vocalpitchdetector

import kotlin.math.log2
import kotlin.math.pow

fun freqToMidi(f: Double): Double {
    return 69.0 + 12.0 * log2(f / 440.0)
}

fun midiToNoteName(midi: Int): String {
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val octave = midi / 12 - 1
    return "${names[midi % 12]}$octave"
}

fun centsDifference(freq: Double, midiNote: Int): Double {
    val refFreq = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
    return 1200.0 * log2(freq / refFreq)
}

fun dbToRms(db: Float, ref: Float = 1f): Float {
    return (10.0.pow((db / 20.0))).toFloat() * ref
}

/** convert RMS (0..1) to dBFS with floor to avoid -Inf */
fun rmsToDb(rms: Float, minDb: Float = -80f): Float {
    if (rms <= 1e-9f) return minDb
    val db = 20f * kotlin.math.log10(rms)
    return maxOf(db, minDb)
}
