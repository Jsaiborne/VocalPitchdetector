package com.example.vocalpitchdetector


import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow


fun freqToMidi(f: Double): Double {
    return 69.0 + 12.0 * log2(f / 440.0)
}


fun midiToNoteName(midi: Int): String {
    val names = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
    val octave = midi / 12 - 1
    return "${names[midi % 12]}$octave"
}


fun centsDifference(freq: Double, midiNote: Int): Double {
    val refFreq = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
    return 1200.0 * log2(freq / refFreq)
}