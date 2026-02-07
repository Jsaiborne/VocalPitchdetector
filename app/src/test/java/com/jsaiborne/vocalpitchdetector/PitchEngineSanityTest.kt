package com.jsaiborne.vocalpitchdetector

import org.junit.Assert.assertFalse
import org.junit.Test

class PitchEngineSanityTest {

    @Test
    fun default_not_running_and_setVolumeThreshold_safe() {
        val engine = PitchEngine()
        // by default should not be running (we didn't call start())
        assertFalse(engine.isRunning())

        // Ensure setting threshold does not throw and clamps values
        engine.setVolumeThreshold(2.0f) // >1 -> should be clamped internally
        engine.setVolumeThreshold(-5.0f) // <0 -> clamped
        // no assertions on internal value (private), just ensuring no exceptions
    }
}
