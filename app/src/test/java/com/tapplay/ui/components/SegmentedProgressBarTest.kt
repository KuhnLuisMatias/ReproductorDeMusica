package com.tapplay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentedProgressBarTest {
    @Test
    fun `calculateProgressFraction calculates correct normalized fraction`() {
        assertEquals(0f, SegmentedProgressBarDefaults.calculateProgressFraction(0L, 100000L), 0.001f)
        assertEquals(0.5f, SegmentedProgressBarDefaults.calculateProgressFraction(50000L, 100000L), 0.001f)
        assertEquals(1.0f, SegmentedProgressBarDefaults.calculateProgressFraction(100000L, 100000L), 0.001f)
        assertEquals(1.0f, SegmentedProgressBarDefaults.calculateProgressFraction(150000L, 100000L), 0.001f)
    }

    @Test
    fun `calculateProgressFraction returns zero when duration is invalid`() {
        assertEquals(0f, SegmentedProgressBarDefaults.calculateProgressFraction(5000L, 0L), 0.001f)
        assertEquals(0f, SegmentedProgressBarDefaults.calculateProgressFraction(5000L, -100L), 0.001f)
    }

    @Test
    fun `calculateSeekTarget returns correct target milliseconds from touch fraction`() {
        assertEquals(0L, SegmentedProgressBarDefaults.calculateSeekTarget(0f, 180000L))
        assertEquals(90000L, SegmentedProgressBarDefaults.calculateSeekTarget(0.5f, 180000L))
        assertEquals(180000L, SegmentedProgressBarDefaults.calculateSeekTarget(1.0f, 180000L))
        assertEquals(180000L, SegmentedProgressBarDefaults.calculateSeekTarget(1.2f, 180000L))
        assertEquals(0L, SegmentedProgressBarDefaults.calculateSeekTarget(-0.5f, 180000L))
    }

    @Test
    fun `calculateSegmentAlpha illuminates segments correctly based on progress`() {
        val totalSegments = 10

        // At 0% progress, all segments should have dim background alpha (0.20f)
        for (i in 0 until totalSegments) {
            assertEquals(0.20f, SegmentedProgressBarDefaults.calculateSegmentAlpha(i, totalSegments, 0f), 0.001f)
        }

        // At 50% progress, first 5 segments (0..4) should be fully illuminated (0.95f)
        for (i in 0 until 5) {
            assertEquals(0.95f, SegmentedProgressBarDefaults.calculateSegmentAlpha(i, totalSegments, 0.5f), 0.001f)
        }
        // Segments 5..9 should be dim (0.20f)
        for (i in 5 until 10) {
            assertEquals(0.20f, SegmentedProgressBarDefaults.calculateSegmentAlpha(i, totalSegments, 0.5f), 0.001f)
        }

        // Partial segment illumination at 25% (2.5 segments)
        // Segment 2 (20% to 30%) should be 50% illuminated -> alpha = 0.20 + 0.75 * 0.5 = 0.575f
        assertEquals(0.575f, SegmentedProgressBarDefaults.calculateSegmentAlpha(2, totalSegments, 0.25f), 0.001f)
    }
}
