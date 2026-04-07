package com.jsaiborne.vocalpitchdetector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
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

fun buildSmoothedPath(points: List<Offset>, smoothing: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    if (points.size == 1) {
        path.moveTo(points[0].x, points[0].y); return path
    }
    if (smoothing <= 0.001f) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    val t = smoothing
    val factor = t / 6f

    val pts = mutableListOf<Offset>()
    pts.add(points.first())
    pts.addAll(points)
    pts.add(points.last())

    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until pts.size - 2) {
        val p0 = pts[i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[i + 2]

        val cp1 = Offset(
            x = p1.x + (p2.x - p0.x) * factor,
            y = p1.y + (p2.y - p0.y) * factor
        )
        val cp2 = Offset(
            x = p2.x - (p3.x - p1.x) * factor,
            y = p2.y - (p3.y - p1.y) * factor
        )
        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, p2.x, p2.y)
    }
    return path
}
