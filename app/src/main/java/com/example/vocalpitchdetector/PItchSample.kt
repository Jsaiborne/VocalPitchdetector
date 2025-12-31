package com.example.vocalpitchdetector

/**
 * Canonical pitch sample used across:
 * - recording
 * - JSON persistence
 * - playback
 * - pitch graph rendering
 */
data class PitchSample(
    val tMs: Long,
    val freq: Float,
    val midi: Float? = null
)
