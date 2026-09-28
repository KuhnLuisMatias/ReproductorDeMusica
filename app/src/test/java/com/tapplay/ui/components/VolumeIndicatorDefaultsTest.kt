package com.tapplay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeIndicatorDefaultsTest {
    @Test
    fun `clampVolumePercent constrains values between 0 and 100`() {
        assertEquals(0, VolumeIndicatorDefaults.clampVolumePercent(-10))
        assertEquals(0, VolumeIndicatorDefaults.clampVolumePercent(0))
        assertEquals(50, VolumeIndicatorDefaults.clampVolumePercent(50))
        assertEquals(100, VolumeIndicatorDefaults.clampVolumePercent(100))
        assertEquals(100, VolumeIndicatorDefaults.clampVolumePercent(120))
    }

    @Test
    fun `calculateSweepAngle calculates correct degrees for volume values`() {
        assertEquals(0f, VolumeIndicatorDefaults.calculateSweepAngle(0), 0.001f)
        assertEquals(180f, VolumeIndicatorDefaults.calculateSweepAngle(50), 0.001f)
        assertEquals(356.4f, VolumeIndicatorDefaults.calculateSweepAngle(99), 0.001f)
        assertEquals(360f, VolumeIndicatorDefaults.calculateSweepAngle(100), 0.001f)
    }
}
