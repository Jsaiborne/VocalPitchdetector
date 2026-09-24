@file:Suppress("MagicNumber")

package com.jsaiborne.vocalpitchdetector

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.log2
import kotlin.math.pow

fun freqToMidi(f: Double): Double {
    return 69.0 + 12.0 * log2(f / 440.0)
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Note name with octave, e.g. 60 -> "C4". Works for negative MIDI numbers too. */
fun midiToNoteName(midi: Int): String {
    val octave = Math.floorDiv(midi, 12) - 1
    return "${NOTE_NAMES[Math.floorMod(midi, 12)]}$octave"
}

/** Frequency in Hz of a MIDI note (A4 = 69 = 440 Hz). */
fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

fun centsDifference(freq: Double, midiNote: Int): Double {
    return 1200.0 * log2(freq / midiToFreq(midiNote))
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
    val size = points.size
    if (size == 0) return path
    if (size == 1) {
        path.moveTo(points[0].x, points[0].y)
        return path
    }
    if (smoothing <= 0.001f) {
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    val factor = smoothing / 6f

    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until size - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]

        // p0 is points[i-1] or points[i] if at start
        val p0 = if (i > 0) points[i - 1] else p1
        // p3 is points[i+2] or points[i+1] if at end
        val p3 = if (i < size - 2) points[i + 2] else p2

        val cp1x = p1.x + (p2.x - p0.x) * factor
        val cp1y = p1.y + (p2.y - p0.y) * factor
        val cp2x = p2.x - (p3.x - p1.x) * factor
        val cp2y = p2.y - (p3.y - p1.y) * factor

        path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
    }
    return path
}

fun openPlayStore(context: Context) {
    val packageName = context.packageName
    @Suppress("SwallowedException")
    try {
        // Try to open the native Play Store app
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        )
    } catch (e: ActivityNotFoundException) {
        // Fallback to web browser if the Play Store isn't installed
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
