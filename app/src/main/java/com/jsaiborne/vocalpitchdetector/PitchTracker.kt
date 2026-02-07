@file:Suppress(
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "MagicNumber"

)

package com.jsaiborne.vocalpitchdetector

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * PitchTracker: uses YIN candidate (Hz), YIN cmndf min, and frame RMS energy
 * to produce a stable pitch output (Hz).
 *
 * IMPROVEMENTS:
 * - Zero-allocation median filtering (reuses sorting buffer).
 * - Improved octave jump logic.
 */
class PitchTracker(
    private val sampleRate: Int,
    private val hopSize: Int,
    private val hangoverMs: Int = 200,
    private val medianWindow: Int = 5,
    private val smoothingAlpha: Double = 0.18,
    private val maxJumpSemitonesPerFrame: Double = 12.0,
    private var energyThreshold: Double = DEFAULT_ENERGY_THRESHOLD,
    private var confidenceThreshold: Double = DEFAULT_CONF_THRESHOLD
) {

    private val hangoverFrames: Int =
        maxOf(
            MIN_HANGOVER_FRAMES,
            (hangoverMs * sampleRate / hopSize) / MILLIS_PER_SECOND
        )
    private var lastStablePitchSemi: Double? = null
    private var holdCounter = 0
    private var smoothedSemi: Double? = null

    // Optimized: ArrayDeque for history, reused DoubleArray for sorting to prevent GC churn
    private val medianBuffer = ArrayDeque<Double>(medianWindow)
    private val sortingBuffer = DoubleArray(medianWindow)

    fun processFrame(pitchHz: Double?, cmndfMin: Double, rms: Double): Double? {
        val clarity = (1.0 - cmndfMin).coerceIn(0.0, 1.0)
        val confidence = clarity
        val isVoiced = (rms >= energyThreshold) && (confidence >= confidenceThreshold)

        if (!isVoiced) {
            // Unvoiced logic
            if (lastStablePitchSemi != null && holdCounter < hangoverFrames) {
                holdCounter++
                smoothedSemi = lastStablePitchSemi
                return smoothedSemi?.let { semitoneToHz(it) }
            } else {
                lastStablePitchSemi = null
                smoothedSemi = null
                medianBuffer.clear()
                holdCounter = 0
                return null
            }
        }

        // Voiced logic
        holdCounter = 0
        val candidateSemi = pitchHz?.let { hzToSemitone(it) } ?: return null

        var chosenSemi = candidateSemi

        // Continuity / Octave Correction
        if (lastStablePitchSemi != null) {
            val diff = abs(chosenSemi - lastStablePitchSemi!!)
            if (diff > maxJumpSemitonesPerFrame) {
                // Only correct octave if confidence isn't practically perfect (e.g. > 0.95)
                // If confidence is super high, we assume the singer actually jumped that far.
                if (confidence < 0.95) {
                    val octaveShift = ((lastStablePitchSemi!! - chosenSemi) / 12.0).roundToInt()
                    val shifted = chosenSemi + 12.0 * octaveShift
                    if (abs(shifted - lastStablePitchSemi!!) < diff) {
                        chosenSemi = shifted
                    }
                }
            }
        }

        // Median Smoothing (Zero Allocation)
        if (medianBuffer.size >= medianWindow) medianBuffer.removeFirst()
        medianBuffer.addLast(chosenSemi)

        val med = calculateMedian()

        // Exponential Smoothing
        smoothedSemi = if (smoothedSemi == null) {
            med
        } else {
            (smoothingAlpha * med + (1 - smoothingAlpha) * smoothedSemi!!)
        }

        lastStablePitchSemi = smoothedSemi
        return smoothedSemi?.let { semitoneToHz(it) }
    }

    private fun calculateMedian(): Double {
        val size = medianBuffer.size
        if (size == 0) return 0.0

        // Copy deque to reusable array
        var index = 0
        for (v in medianBuffer) {
            sortingBuffer[index++] = v
        }

        // Sort only the filled portion
        sortingBuffer.sort(0, size)

        val m = size / 2
        return if (size % 2 == 1) {
            sortingBuffer[m]
        } else {
            (sortingBuffer[m - 1] + sortingBuffer[m]) / 2.0
        }
    }

    fun setEnergyThreshold(th: Double) { energyThreshold = th }
    fun setConfidenceThreshold(th: Double) { confidenceThreshold = th }

    private fun hzToSemitone(hz: Double): Double = 12.0 * ln(hz / 440.0) / ln(2.0)
    private fun semitoneToHz(semi: Double): Double = 440.0 * 2.0.pow(semi / 12.0)

    companion object {
        const val DEFAULT_ENERGY_THRESHOLD = 1e-6
        const val DEFAULT_CONF_THRESHOLD = 0.12

        private const val MILLIS_PER_SECOND = 1000
        private const val MIN_HANGOVER_FRAMES = 1
    }
}
