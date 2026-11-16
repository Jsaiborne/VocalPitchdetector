@file:Suppress(

    "MagicNumber"

)
package com.jsaiborne.vocalpitchdetector

import kotlin.math.abs
import kotlin.math.sqrt

data class YinResult(
    val pitchHz: Double?,
    val cmndfMin: Double,
    val rms: Double
)

class YinPitchDetector(
    private val sampleRate: Int = 44100,
    bufferSize: Int = 2048,
    private val minFreq: Float = 60f,
    private val maxFreq: Float = 1200f,
    private val threshold: Float = 0.12f
) {
    // Reused buffers to avoid allocation
    private val difference = FloatArray(bufferSize / 2)
    private val cmndf = FloatArray(bufferSize / 2)

    /**
     * Analyze a frame.
     * @param preComputedRms Optimization: pass RMS if already calculated to avoid re-looping.
     */
    @Suppress(
        "CyclomaticComplexMethod",
        "ReturnCount"
    )
    fun getPitch(buffer: FloatArray, readLen: Int, preComputedRms: Double? = null): YinResult {
        if (readLen <= 0) return YinResult(null, 1.0, 0.0)

        // 1. RMS Calculation (only if not provided)
        val rms = preComputedRms ?: computeRms(buffer, readLen)

        // 2. Lag bounds
        val maxLag = (sampleRate / minFreq).toInt().coerceAtMost(readLen / 2 - 1)
        val minLag = (sampleRate / maxFreq).toInt().coerceAtLeast(2)

        if (minLag >= maxLag) return YinResult(null, 1.0, rms)

        // 3. Difference Function (Autocorrelation-like)
        // Note: Zeroing the array is required as the loop doesn't write every index in existence
        difference.fill(0f, minLag, maxLag + 1)

        // This is the heavy O(N^2) loop.
        // Optimization: buffer[i] access is fast, but we limit 'tau' range strictly.
        val limit = readLen
        for (tau in minLag..maxLag) {
            var sum = 0f
            val n = limit - tau
            for (i in 0 until n) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            difference[tau] = sum
        }

        // 4. Cumulative Mean Normalized Difference (CMNDF)
        cmndf.fill(0f, minLag, maxLag + 1)
        var runningSum = 0f
        cmndf[0] = 1f // Guard

        for (tau in minLag..maxLag) {
            runningSum += difference[tau]
            cmndf[tau] = if (runningSum == 0f) 1f else (difference[tau] * tau) / runningSum
        }

        // 5. Absolute Threshold Search
        var tauEstimate = -1
        for (tau in minLag..maxLag) {
            if (cmndf[tau] < threshold) {
                var bestTau = tau
                var bestVal = cmndf[tau]
                var k = tau + 1
                while (k <= maxLag && cmndf[k] < bestVal) {
                    bestVal = cmndf[k]
                    bestTau = k
                    k++
                }
                tauEstimate = bestTau
                break
            }
        }

        // 6. Global Minimum fallback
        if (tauEstimate == -1) {
            var bestTau = minLag
            var bestVal = cmndf[minLag]
            for (tau in (minLag + 1)..maxLag) {
                if (cmndf[tau] < bestVal) {
                    bestVal = cmndf[tau]
                    bestTau = tau
                }
            }
            // If the best guess is still terrible, abort
            if (bestVal > YIN_MAX_ACCEPTABLE_CMND) {
                return YinResult(null, bestVal.toDouble(), rms)
            }
            tauEstimate = bestTau
        }

        // 7. The pick may be a fraction of the true period (a harmonic); see preferTruePeriod
        tauEstimate = preferTruePeriod(tauEstimate, minLag, maxLag)

        // 8. Parabolic Interpolation
        val betterTau = parabolicInterpolation(cmndf, tauEstimate, minLag, maxLag)
        val frequency = sampleRate.toFloat() / betterTau
        val normValue = cmndf[tauEstimate].coerceIn(0f, 1f)

        // Final sanity check on frequency bounds
        if (frequency < minFreq || frequency > maxFreq) {
            return YinResult(null, normValue.toDouble(), rms)
        }

        return YinResult(frequency.toDouble(), normValue.toDouble(), rms)
    }

    /**
     * A voice whose fundamental and other odd harmonics are weak (an open vowel high in a man's range,
     * where the vowel boosts the 2nd harmonic) repeats almost exactly every half period, so the threshold
     * search can stop at a dip that's a fraction of the true period, reading A4 as A5. The true period is
     * then a whole multiple of [tau] with a clearly deeper dip; this returns that multiple, or else [tau].
     * On a correct pick, the dips at its multiples are about as deep as its own, never half, so it stays.
     */
    private fun preferTruePeriod(tau: Int, minLag: Int, maxLag: Int): Int {
        var best = tau
        for (k in 2..MAX_PERIOD_MULTIPLE) {
            val centre = k * tau
            if (centre + k > maxLag) break
            // The lowest point near k × tau (rounding in tau grows k-fold), then the bottom of its dip
            var dip = centre - k
            for (i in centre - k + 1..centre + k) if (cmndf[i] < cmndf[dip]) dip = i
            while (dip < maxLag && cmndf[dip + 1] < cmndf[dip]) dip++
            while (dip > minLag && cmndf[dip - 1] < cmndf[dip]) dip--
            if (cmndf[dip] < cmndf[best] * SUBHARMONIC_RATIO && cmndf[best] - cmndf[dip] > SUBHARMONIC_MARGIN) {
                best = dip
            }
        }
        return best
    }

    private fun parabolicInterpolation(y: FloatArray, tau: Int, minIdx: Int, maxIdx: Int): Float {
        if (tau <= minIdx || tau >= maxIdx) return tau.toFloat()
        val y0 = y[tau - 1]
        val y1 = y[tau]
        val y2 = y[tau + 1]

        val denom = (y0 - 2f * y1 + y2)
        if (abs(denom) < 1e-6f) return tau.toFloat()

        val delta = (y0 - y2) / (2f * denom)
        return tau + delta
    }

    private fun computeRms(buffer: FloatArray, readLen: Int): Double {
        var sum = 0.0
        for (i in 0 until readLen) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / readLen.toDouble())
    }

    private companion object {
        private const val YIN_MAX_ACCEPTABLE_CMND = 0.45f

        /**
         * A dip at a multiple of the pick is taken as the true period when it's below this share of the
         * pick's dip and at least [SUBHARMONIC_MARGIN] lower.
         */
        private const val SUBHARMONIC_RATIO = 0.5f
        private const val SUBHARMONIC_MARGIN = 0.02f

        /** Multiples of the pick checked: an octave up (2), an octave and a fifth (3), two octaves (4). */
        private const val MAX_PERIOD_MULTIPLE = 4
    }
}
