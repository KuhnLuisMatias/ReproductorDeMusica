package com.tapplay.util

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

/** Horizontal tap zone on the cover: 15% left / 70% center / 15% right. */
enum class GestureZone { LEFT, CENTER, RIGHT }

/** Dominant-axis swipe direction. */
enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

/** Actions resolved by [GestureHandler]; executed silently by the UI. */
sealed interface GestureAction {
    data class Tap(val zone: GestureZone) : GestureAction

    data class DoubleTap(val zone: GestureZone) : GestureAction

    data class LongPress(val zone: GestureZone) : GestureAction

    data class HorizontalSwipe(val direction: SwipeDirection) : GestureAction

    /** Signed number of 5% volume steps applied in real time (+ up / - down). */
    data class VolumeSteps(val steps: Int) : GestureAction

    /** Finger lifted after a vertical volume drag; UI should fade out the volume overlay. */
    data object VolumeDragEnd : GestureAction
}

/**
 * Pure gesture classification (design D4) plus the custom
 * `awaitEachGesture` detection loop. Pure functions are unit-testable;
 * the [tapPlayGestures] modifier wires them to pointer events.
 */
object GestureHandler {
    const val SWIPE_THRESHOLD_DP = 50f

    /** Vertical drag distance (dp) that equals one 5% volume step. */
    const val VOLUME_STEP_DP = 20f
    const val VOLUME_STEP_PERCENT = 5
    const val DOUBLE_TAP_WINDOW_MS = 250L

    /** Max movement (dp) tolerated while a long press is pending. */
    const val LONG_PRESS_SLOP_DP = 12f

    /**
     * Bottom-band start floor (dp): volume drags starting below
     * `screenHeight - max(systemGestureInset, this)` are suppressed so they
     * never fight Android gesture navigation.
     */
    const val BOTTOM_EDGE_EXCLUSION_DP = 48f

    /**
     * Top-band start floor (dp), symmetric to [BOTTOM_EDGE_EXCLUSION_DP]:
     * volume drags starting above `max(systemGestureInset, this)` are
     * suppressed so they never fight the notification shade gesture.
     */
    const val TOP_EDGE_EXCLUSION_DP = 48f

    fun resolveZone(
        x: Float,
        width: Float,
    ): GestureZone =
        when {
            x < width * 0.15f -> GestureZone.LEFT
            x < width * 0.85f -> GestureZone.CENTER
            else -> GestureZone.RIGHT
        }

    fun isSwipe(distanceDp: Float): Boolean = distanceDp > SWIPE_THRESHOLD_DP

    /** True while the pointer stays still enough for a long press to remain pending. */
    fun isWithinLongPressSlop(distanceDp: Float): Boolean = distanceDp <= LONG_PRESS_SLOP_DP

    fun volumeStepsFor(distanceDp: Float): Int = (distanceDp / VOLUME_STEP_DP).toInt()

    /**
     * Signed cumulative volume steps for a signed vertical drag (dp), used by the
     * finger-as-slider model: dragging up (negative dy) raises (+ steps) and dragging
     * down (positive dy) lowers (- steps). Rounding is symmetric on the magnitude,
     * so a 30dp drag rounds to 2 steps in either direction.
     */
    fun signedVolumeStepsFor(dyDp: Float): Int = if (dyDp < 0f) Math.round(-dyDp / VOLUME_STEP_DP) else -Math.round(dyDp / VOLUME_STEP_DP)

    fun isDoubleTap(
        zone: GestureZone,
        secondZone: GestureZone,
        elapsedMs: Long,
    ): Boolean = zone == secondZone && elapsedMs <= DOUBLE_TAP_WINDOW_MS

    fun resolveSwipeDirection(
        dx: Float,
        dy: Float,
    ): SwipeDirection =
        if (abs(dx) >= abs(dy)) {
            if (dx >= 0f) SwipeDirection.RIGHT else SwipeDirection.LEFT
        } else {
            if (dy >= 0f) SwipeDirection.DOWN else SwipeDirection.UP
        }

    /** True when a pointer down at [startYpx] lands in the bottom-edge trigger band. */
    fun isInBottomEdgeZone(
        startYpx: Float,
        heightPx: Float,
        exclusionPx: Float,
    ): Boolean = startYpx > heightPx - exclusionPx

    /** True when a pointer down at [startYpx] lands in the top-edge trigger band. */
    fun isInTopEdgeZone(
        startYpx: Float,
        exclusionPx: Float,
    ): Boolean = startYpx < exclusionPx

    /**
     * True when a gesture starting at [downYpx] must not emit volume steps —
     * either the bottom edge band or the top edge band.
     */
    fun isVolumeDragSuppressed(
        downYpx: Float,
        heightPx: Float,
        topExclusionPx: Float,
        bottomExclusionPx: Float,
    ): Boolean =
        isInTopEdgeZone(downYpx, topExclusionPx) ||
            isInBottomEdgeZone(downYpx, heightPx, bottomExclusionPx)
}

/**
 * Detects taps (with double-tap window), long presses and swipes on the
 * attached node. Horizontal swipes fire once at threshold crossing;
 * vertical swipes apply volume steps in real time during the drag and
 * track the finger both ways, so reversing mid-gesture is immediate.
 *
 * Gestures starting in the top [topExclusionPx] band or the bottom
 * [bottomExclusionPx] band suppress volume emission (the top band protects
 * the notification shade gesture, the bottom band protects gesture
 * navigation); gestures whose first down was consumed by a child (icon
 * clickables) are ignored entirely.
 */
fun Modifier.tapPlayGestures(
    onAction: (GestureAction) -> Unit,
    topExclusionPx: Float = 0f,
    bottomExclusionPx: Float = 0f,
): Modifier =
    composed {
        val currentOnAction by rememberUpdatedState(onAction)
        val currentTopExclusionPx by rememberUpdatedState(topExclusionPx)
        val currentBottomExclusionPx by rememberUpdatedState(bottomExclusionPx)
        pointerInput(Unit) {
            val swipeThresholdPx = GestureHandler.SWIPE_THRESHOLD_DP.dp.toPx()
            val volumeStepPx = GestureHandler.VOLUME_STEP_DP.dp.toPx()
            val longPressSlopPx = GestureHandler.LONG_PRESS_SLOP_DP.dp.toPx()
            val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
            val density = this
            awaitEachGesture {
                handleGestureCycle(
                    density = density,
                    size = size,
                    swipeThresholdPx = swipeThresholdPx,
                    volumeStepPx = volumeStepPx,
                    longPressSlopPx = longPressSlopPx,
                    longPressTimeoutMs = longPressTimeoutMs,
                    topExclusionPx = currentTopExclusionPx,
                    bottomExclusionPx = currentBottomExclusionPx,
                ) { currentOnAction(it) }
            }
        }
    }

private suspend fun AwaitPointerEventScope.handleGestureCycle(
    density: Density,
    size: IntSize,
    swipeThresholdPx: Float,
    volumeStepPx: Float,
    longPressSlopPx: Float,
    longPressTimeoutMs: Long,
    topExclusionPx: Float,
    bottomExclusionPx: Float,
    onAction: (GestureAction) -> Unit,
) {
    var down = awaitFirstDown(requireUnconsumed = false)
    if (down.isConsumed) return
    val suppressVolume =
        GestureHandler.isVolumeDragSuppressed(
            downYpx = down.position.y,
            heightPx = size.height.toFloat(),
            topExclusionPx = topExclusionPx,
            bottomExclusionPx = bottomExclusionPx,
        )
    while (true) {
        val tapZone =
            awaitTapOutcome(down, density, size, swipeThresholdPx, volumeStepPx, longPressSlopPx, longPressTimeoutMs, suppressVolume, onAction)
                ?: return
        val secondDown =
            withTimeoutOrNull(GestureHandler.DOUBLE_TAP_WINDOW_MS) {
                awaitFirstDown(requireUnconsumed = false)
            }
        if (secondDown == null || secondDown.isConsumed) {
            onAction(GestureAction.Tap(tapZone))
            return
        }
        val secondZone = GestureHandler.resolveZone(secondDown.position.x, size.width.toFloat())
        if (GestureHandler.isDoubleTap(tapZone, secondZone, 0L)) {
            onAction(GestureAction.DoubleTap(secondZone))
            awaitPointerUp(secondDown.id)
            return
        }
        down = secondDown
    }
}

/**
 * Tracks one pointer from down to lift. Returns the tap zone when the
 * gesture ended as a plain tap, or null when it ended as swipe,
 * long press or was cancelled.
 *
 * Vertical drags act as a finger-as-slider: signed volume deltas are
 * emitted in real time relative to the finger's current position, so
 * reversing direction mid-gesture (without lifting) immediately applies
 * the opposite steps. When [suppressVolume] is set (bottom band) no
 * volume steps are emitted.
 */
private suspend fun AwaitPointerEventScope.awaitTapOutcome(
    down: PointerInputChange,
    density: Density,
    size: IntSize,
    swipeThresholdPx: Float,
    volumeStepPx: Float,
    longPressSlopPx: Float,
    longPressTimeoutMs: Long,
    suppressVolume: Boolean,
    onAction: (GestureAction) -> Unit,
): GestureZone? {
    var lastSignedSteps = 0
    var isSwiping = false
    var longPressFired = false
    var volumeDragActive = false
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) {
            if (change.isConsumed) return null
            if (longPressFired || isSwiping) {
                if (volumeDragActive) onAction(GestureAction.VolumeDragEnd)
                return null
            }
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (GestureHandler.isSwipe(density.pxToDp(hypot(dx, dy)))) {
                onAction(GestureAction.HorizontalSwipe(GestureHandler.resolveSwipeDirection(dx, dy)))
                return null
            }
            return GestureHandler.resolveZone(down.position.x, size.width.toFloat())
        }
        if (change.isConsumed && !isSwiping) {
            // A1b: a sibling (e.g. SongInfo's passiveLongPress) started consuming
            // this pointer mid-press — abort so a drag afterward never emits a
            // swipe or volume step on top of it.
            if (volumeDragActive) onAction(GestureAction.VolumeDragEnd)
            return null
        }
        val dx = change.position.x - down.position.x
        val dy = change.position.y - down.position.y
        val distancePx = hypot(dx, dy)
        if (!longPressFired && !isSwiping && !change.isConsumed &&
            change.uptimeMillis - down.uptimeMillis >= longPressTimeoutMs &&
            distancePx <= longPressSlopPx
        ) {
            longPressFired = true
            onAction(GestureAction.LongPress(GestureHandler.resolveZone(down.position.x, size.width.toFloat())))
        }
        if (!isSwiping && distancePx > swipeThresholdPx) {
            isSwiping = true
            if (abs(dx) >= abs(dy)) {
                onAction(GestureAction.HorizontalSwipe(GestureHandler.resolveSwipeDirection(dx, dy)))
            }
        }
        if (isSwiping) {
            change.consume()
            if (!suppressVolume && abs(dx) < abs(dy)) {
                val signedSteps = GestureHandler.signedVolumeStepsFor(density.pxToDp(dy))
                val delta = signedSteps - lastSignedSteps
                if (delta != 0) {
                    volumeDragActive = true
                    onAction(GestureAction.VolumeSteps(delta))
                    lastSignedSteps = signedSteps
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitPointerUp(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return
        if (!change.pressed) return
        change.consume()
    }
}

private fun Density.pxToDp(px: Float): Float = px.toDp().value

/**
 * Observes a press without consuming the initial down (A1): while
 * [enabled], waits up to the platform long-press timeout for the pointer to
 * lift or drift beyond [GestureHandler.LONG_PRESS_SLOP_DP]. If neither
 * happens before the timeout, it fires [onLongPress] (the caller applies
 * haptics) and starts consuming every subsequent change for that pointer so
 * a drag afterward cannot also trigger a sibling's tap/swipe/volume drag —
 * see the sibling-side abort in `awaitTapOutcome` (A1b).
 *
 * Because the down is never consumed up front, tap-to-play, double-tap,
 * swipes and volume drags starting on the same node keep working right up
 * until the long-press threshold actually fires.
 */
fun Modifier.passiveLongPress(
    enabled: Boolean = true,
    onLongPress: () -> Unit,
): Modifier =
    composed {
        val currentEnabled by rememberUpdatedState(enabled)
        val currentOnLongPress by rememberUpdatedState(onLongPress)
        pointerInput(Unit) {
            val longPressSlopPx = GestureHandler.LONG_PRESS_SLOP_DP.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEnabled || down.isConsumed) return@awaitEachGesture
                val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
                val firedNaturally =
                    withTimeoutOrNull(longPressTimeoutMs) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (!change.pressed) return@withTimeoutOrNull false
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (hypot(dx, dy) > longPressSlopPx) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                if (firedNaturally == null) {
                    currentOnLongPress()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed) return@awaitEachGesture
                        change.consume()
                    }
                }
            }
        }
    }
