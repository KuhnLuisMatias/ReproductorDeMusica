package com.tapplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureHandlerTest {
    // ---- Zone resolution: 15% left / 70% center / 15% right ----

    @Test
    fun `far left tap resolves to LEFT zone`() {
        assertEquals(GestureZone.LEFT, GestureHandler.resolveZone(0f, 1000f))
        assertEquals(GestureZone.LEFT, GestureHandler.resolveZone(149.9f, 1000f))
    }

    @Test
    fun `tap exactly at 15 percent width belongs to CENTER zone`() {
        assertEquals(GestureZone.CENTER, GestureHandler.resolveZone(150f, 1000f))
    }

    @Test
    fun `middle tap resolves to CENTER zone`() {
        assertEquals(GestureZone.CENTER, GestureHandler.resolveZone(500f, 1000f))
    }

    @Test
    fun `tap just before 85 percent width belongs to CENTER zone`() {
        assertEquals(GestureZone.CENTER, GestureHandler.resolveZone(849.9f, 1000f))
    }

    @Test
    fun `tap at and beyond 85 percent width resolves to RIGHT zone`() {
        assertEquals(GestureZone.RIGHT, GestureHandler.resolveZone(850f, 1000f))
        assertEquals(GestureZone.RIGHT, GestureHandler.resolveZone(999f, 1000f))
    }

    // ---- 50dp tap vs swipe threshold ----

    @Test
    fun `movement of exactly 50dp is a tap not a swipe`() {
        assertFalse(GestureHandler.isSwipe(50f))
    }

    @Test
    fun `movement just over 50dp is a swipe`() {
        assertTrue(GestureHandler.isSwipe(50.5f))
    }

    @Test
    fun `no movement is a tap`() {
        assertFalse(GestureHandler.isSwipe(0f))
    }

    // ---- Long press slop: near-still finger only ----

    @Test
    fun `movement within 12dp keeps long press pending`() {
        assertTrue(GestureHandler.isWithinLongPressSlop(0f))
        assertTrue(GestureHandler.isWithinLongPressSlop(12f))
    }

    @Test
    fun `movement beyond 12dp cancels long press pending state`() {
        assertFalse(GestureHandler.isWithinLongPressSlop(12.5f))
        assertFalse(GestureHandler.isWithinLongPressSlop(49f))
    }

    // ---- Volume delta math: one 5 percent step per 20dp ----
    @Test
    fun `drag under 20dp produces zero volume steps`() {
        assertEquals(0, GestureHandler.volumeStepsFor(19.9f))
        assertEquals(0, GestureHandler.volumeStepsFor(0f))
    }

    @Test
    fun `drag of exactly 20dp produces one volume step`() {
        assertEquals(1, GestureHandler.volumeStepsFor(20f))
    }

    @Test
    fun `drag of 45dp produces two volume steps`() {
        assertEquals(2, GestureHandler.volumeStepsFor(45f))
    }

    @Test
    fun `drag just under the second step boundary stays at one step`() {
        assertEquals(1, GestureHandler.volumeStepsFor(39.9f))
    }

    @Test
    fun `volume step percent is 5`() {
        assertEquals(5, GestureHandler.VOLUME_STEP_PERCENT)
    }

    // ---- Signed volume steps: finger-as-slider (up = negative dy = raise) ----

    @Test
    fun `upward drag of 20dp raises one signed step`() {
        assertEquals(1, GestureHandler.signedVolumeStepsFor(-20f))
    }

    @Test
    fun `downward drag of 20dp lowers one signed step`() {
        assertEquals(-1, GestureHandler.signedVolumeStepsFor(20f))
    }

    @Test
    fun `upward drag of 30dp rounds up to two signed steps`() {
        assertEquals(2, GestureHandler.signedVolumeStepsFor(-30f))
    }

    @Test
    fun `downward drag of 30dp rounds down to minus two signed steps`() {
        assertEquals(-2, GestureHandler.signedVolumeStepsFor(30f))
    }

    @Test
    fun `zero signed drag produces zero steps`() {
        assertEquals(0, GestureHandler.signedVolumeStepsFor(0f))
    }

    // ---- Double tap window ----

    @Test
    fun `second tap in same zone within 250ms is a double tap`() {
        assertTrue(GestureHandler.isDoubleTap(GestureZone.CENTER, GestureZone.CENTER, 200L))
    }

    @Test
    fun `second tap after the 250ms window is not a double tap`() {
        assertFalse(GestureHandler.isDoubleTap(GestureZone.CENTER, GestureZone.CENTER, 251L))
    }

    @Test
    fun `second tap in a different zone is not a double tap`() {
        assertFalse(GestureHandler.isDoubleTap(GestureZone.CENTER, GestureZone.LEFT, 100L))
    }

    // ---- Swipe direction by dominant axis ----

    @Test
    fun `horizontal dominant movement resolves left and right`() {
        assertEquals(SwipeDirection.RIGHT, GestureHandler.resolveSwipeDirection(100f, 10f))
        assertEquals(SwipeDirection.LEFT, GestureHandler.resolveSwipeDirection(-100f, 10f))
    }

    @Test
    fun `vertical dominant movement resolves up and down`() {
        assertEquals(SwipeDirection.UP, GestureHandler.resolveSwipeDirection(10f, -100f))
        assertEquals(SwipeDirection.DOWN, GestureHandler.resolveSwipeDirection(10f, 100f))
    }

    // ---- Bottom-edge zone: starts only in the band below the gesture exclusion ----

    @Test
    fun `start inside the bottom exclusion band is an edge start`() {
        // 1600px screen, 99px exclusion (48dp at 2.0625 density) -> band starts at 1501
        assertTrue(GestureHandler.isInBottomEdgeZone(1560f, 1600f, 99f))
        assertTrue(GestureHandler.isInBottomEdgeZone(1599f, 1600f, 99f))
    }

    @Test
    fun `start above the exclusion band is not an edge start`() {
        assertFalse(GestureHandler.isInBottomEdgeZone(1500f, 1600f, 99f))
        assertFalse(GestureHandler.isInBottomEdgeZone(0f, 1600f, 99f))
    }

    @Test
    fun `start exactly at the band boundary is not an edge start`() {
        assertFalse(GestureHandler.isInBottomEdgeZone(1501f, 1600f, 99f))
    }

    // ---- Volume suppression band: same geometry as the edge band ----

    @Test
    fun `down inside the bottom band suppresses volume drag`() {
        // 1600px screen, 99px exclusion (48dp at 2.0625 density) -> band starts at 1501
        assertTrue(GestureHandler.isVolumeDragSuppressed(1560f, 1600f, 99f))
        assertTrue(GestureHandler.isVolumeDragSuppressed(1599f, 1600f, 99f))
    }

    @Test
    fun `down above the bottom band keeps volume drags live`() {
        assertFalse(GestureHandler.isVolumeDragSuppressed(1500f, 1600f, 99f))
        assertFalse(GestureHandler.isVolumeDragSuppressed(0f, 1600f, 99f))
    }

    @Test
    fun `down exactly at the band boundary is not suppressed`() {
        assertFalse(GestureHandler.isVolumeDragSuppressed(1501f, 1600f, 99f))
    }

    // ---- Quick upward swipe: vertical dominant, up, whole gesture within window ----

    @Test
    fun `fast upward vertical dominant swipe qualifies as quick`() {
        assertTrue(GestureHandler.isQuickUpwardSwipe(dx = 10f, dy = -300f, elapsedMs = 150L))
        assertTrue(GestureHandler.isQuickUpwardSwipe(dx = 0f, dy = -51f, elapsedMs = 600L))
    }

    @Test
    fun `swipe completed after the 600ms window is too slow`() {
        assertFalse(GestureHandler.isQuickUpwardSwipe(dx = 0f, dy = -300f, elapsedMs = 601L))
        assertFalse(GestureHandler.isQuickUpwardSwipe(dx = 0f, dy = -680f, elapsedMs = 2500L))
    }

    @Test
    fun `downward movement never qualifies as an upward quick swipe`() {
        assertFalse(GestureHandler.isQuickUpwardSwipe(dx = 0f, dy = 300f, elapsedMs = 100L))
    }

    @Test
    fun `horizontal dominant movement never qualifies as an upward quick swipe`() {
        assertFalse(GestureHandler.isQuickUpwardSwipe(dx = 300f, dy = -100f, elapsedMs = 100L))
    }

    @Test
    fun `quick swipe window is 600ms`() {
        assertEquals(600L, GestureHandler.QUICK_SWIPE_WINDOW_MS)
    }

    @Test
    fun `bottom edge exclusion floor is 48dp`() {
        assertEquals(48f, GestureHandler.EDGE_EXCLUSION_DP)
    }
}
