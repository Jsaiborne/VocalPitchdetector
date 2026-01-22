package com.example.vocalpitchdetector

import kotlin.math.abs

/**
 * YIN pitch detector (Kotlin).
 *
 * Based on the original YIN algorithm. Compact, reusable implementation.
 */
class YinPitchDetector(
    private val sampleRate: Int = 44100,
    private val bufferSize: Int = 2048,
    private val minFreq: Float = 60f,
    private val maxFreq: Float = 1200f,
    private val threshold: Float = 0.12f
) {
    private val difference = FloatArray(bufferSize / 2)
    private val cumulativeMeanNormalizedDifference = FloatArray(bufferSize / 2)

    fun getPitch(buffer: FloatArray, readLen: Int): Pair<Float, Float> {
        if (readLen <= 0) return Pair(-1f, 0f)

        val maxLag = (sampleRate / minFreq).toInt().coerceAtMost(readLen / 2 - 1)
        val minLag = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
        if (minLag >= maxLag) return Pair(-1f, 0f)

// 1) difference
        for (i in difference.indices) difference[i] = 0f
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

// 2) cumulative mean normalized difference
        for (i in cumulativeMeanNormalizedDifference.indices) cumulativeMeanNormalizedDifference[i] = 0f
        var runningSum = 0f
        for (tau in minLag..maxLag) {
            runningSum += difference[tau]
            cumulativeMeanNormalizedDifference[tau] = if (runningSum == 0f) 1f else (difference[tau] * tau) / runningSum
        }

// 3) absolute threshold
        var tauEstimate = -1
        for (tau in minLag..maxLag) {
            if (cumulativeMeanNormalizedDifference[tau] < threshold) {
                var bestTau = tau
                var bestVal = cumulativeMeanNormalizedDifference[tau]
                var k = tau + 1
                while (k <= maxLag && cumulativeMeanNormalizedDifference[k] < bestVal) {
                    bestVal = cumulativeMeanNormalizedDifference[k]
                    bestTau = k
                    k++
                }
                tauEstimate = bestTau
                break
            }
        }

        if (tauEstimate == -1) {
            var bestTau = minLag
            var bestVal = cumulativeMeanNormalizedDifference[minLag]
            for (tau in (minLag + 1)..maxLag) {
                val v = cumulativeMeanNormalizedDifference[tau]
                if (v < bestVal) {
                    bestVal = v
                    bestTau = tau
                }
            }
            if (bestVal > 0.5f) return Pair(-1f, 0f)
            tauEstimate = bestTau
        }

        val betterTau = parabolicInterpolation(cumulativeMeanNormalizedDifference, tauEstimate, minLag, maxLag)
        val frequency = sampleRate.toFloat() / betterTau.toFloat()
        val normValue = cumulativeMeanNormalizedDifference[tauEstimate].coerceIn(0f, 1f)
        val confidence = (1f - normValue).coerceIn(0f, 1f)
        return Pair(frequency, confidence)
    }

    private fun parabolicInterpolation(y: FloatArray, tau: Int, minIdx: Int, maxIdx: Int): Float {
        if (tau <= minIdx || tau >= maxIdx) return tau.toFloat()
        val y0 = y[tau - 1]
        val y1 = y[tau]
        val y2 = y[tau + 1]
        val denom = (y0 - 2f * y1 + y2)
        if (abs(denom) < 1e-12f) return tau.toFloat()
        val delta = (y0 - y2) / (2f * denom)
        return tau + delta
    }
}
