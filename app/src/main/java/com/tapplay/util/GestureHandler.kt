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
     * Bottom-band start floor (dp): a queue-sheet edge swipe must begin below
     * `screenHeight - max(systemGestureInset, this)` so it never fights Android
     * gesture navigation.
     */
    const val EDGE_EXCLUSION_DP = 48f

    /**
     * Max elapsed time (down -> lift) for a bottom-edge swipe to count as a
     * quick flick; slower drags are ignored.
     */
    const val QUICK_SWIPE_WINDOW_MS = 600L

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

    /** True when a gesture starting at [downYpx] must not emit volume steps (bottom edge band). */
    fun isVolumeDragSuppressed(
        downYpx: Float,
        heightPx: Float,
        bottomExclusionPx: Float,
    ): Boolean = isInBottomEdgeZone(downYpx, heightPx, bottomExclusionPx)

    /**
     * True when a completed gesture is an upward, vertical-dominant swipe whose
     * whole duration (down -> lift) fits inside [QUICK_SWIPE_WINDOW_MS]. Measured
     * at lift rather than at threshold crossing so a slow drag (which already
     * crosses 50dp early) never triggers.
     */
    fun isQuickUpwardSwipe(
        dx: Float,
        dy: Float,
        elapsedMs: Long,
    ): Boolean = dy < 0f && abs(dy) > abs(dx) && elapsedMs <= QUICK_SWIPE_WINDOW_MS
}

/**
 * Detects taps (with double-tap window), long presses and swipes on the
 * attached node. Horizontal swipes fire once at threshold crossing;
 * vertical swipes apply volume steps in real time during the drag and
 * track the finger both ways, so reversing mid-gesture is immediate.
 *
 * Gestures starting in the bottom [bottomExclusionPx] band suppress volume
 * emission (the sheet-open edge detector owns that band); gestures whose
 * first down was consumed by a child (icon clickables) are ignored entirely.
 */
fun Modifier.tapPlayGestures(
    onAction: (GestureAction) -> Unit,
    bottomExclusionPx: Float = 0f,
): Modifier =
    composed {
        val currentOnAction by rememberUpdatedState(onAction)
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
    bottomExclusionPx: Float,
    onAction: (GestureAction) -> Unit,
) {
    var down = awaitFirstDown(requireUnconsumed = false)
    if (down.isConsumed) return
    val suppressVolume =
        GestureHandler.isVolumeDragSuppressed(
            downYpx = down.position.y,
            heightPx = size.height.toFloat(),
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
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) {
            if (change.isConsumed) return null
            if (longPressFired || isSwiping) return null
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (GestureHandler.isSwipe(density.pxToDp(hypot(dx, dy)))) {
                onAction(GestureAction.HorizontalSwipe(GestureHandler.resolveSwipeDirection(dx, dy)))
                return null
            }
            return GestureHandler.resolveZone(down.position.x, size.width.toFloat())
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
 * Bottom-edge shortcut: a quick upward flick starting in the screen's bottom
 * band (below [GestureHandler.EDGE_EXCLUSION_DP] or the system gesture inset,
 * whichever is larger) invokes [onOpen]. Slow drags and starts above the band
 * are ignored, so cover gestures and system navigation stay
 * untouched. No visual feedback is produced.
 *
 * Set [enabled] to false while the target sheet is already visible to avoid
 * re-triggering.
 */
fun Modifier.bottomEdgeSwipeToOpen(
    exclusionPx: Float,
    enabled: Boolean,
    onOpen: () -> Unit,
): Modifier =
    composed {
        val currentOnOpen by rememberUpdatedState(onOpen)
        val currentEnabled by rememberUpdatedState(enabled)
        val currentExclusionPx by rememberUpdatedState(exclusionPx)
        pointerInput(Unit) {
            val swipeThresholdPx = GestureHandler.SWIPE_THRESHOLD_DP.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEnabled ||
                    !GestureHandler.isInBottomEdgeZone(down.position.y, size.height.toFloat(), currentExclusionPx)
                ) {
                    return@awaitEachGesture
                }
                val downTime = down.uptimeMillis
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed) {
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val elapsed = change.uptimeMillis - downTime
                        if (abs(dy) > swipeThresholdPx &&
                            GestureHandler.isQuickUpwardSwipe(dx, dy, elapsed)
                        ) {
                            change.consume()
                            currentOnOpen()
                        }
                        return@awaitEachGesture
                    }
                }
            }
        }
    }
