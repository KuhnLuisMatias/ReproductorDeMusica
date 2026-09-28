package com.tapplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

object SegmentedProgressBarDefaults {
    fun calculateProgressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / max(1L, durationMs).toFloat()).coerceIn(0f, 1f)
    }

    fun calculateSeekTarget(touchFraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (touchFraction.coerceIn(0f, 1f) * durationMs.toFloat()).toLong()
    }

    fun calculateSegmentAlpha(index: Int, totalSegments: Int, progressFraction: Float): Float {
        val count = max(1, totalSegments)
        val step = 1f / count
        val segmentStart = index * step
        val rawFill = ((progressFraction - segmentStart) / step).coerceIn(0f, 1f)
        return 0.20f + (0.75f * rawFill)
    }
}

/**
 * Modern segmented vertical block progress bar:
 * - A series of vertical rectangular blocks that illuminate sequentially as the song advances.
 * - Smooth alpha transition per block as progress moves.
 * - Tap and drag seeking support via [onSeek].
 */
@Composable
fun MinimalProgressBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onSeek: ((Long) -> Unit)? = null,
    segmentCount: Int = 20,
    barHeight: Dp = 16.dp,
    gapWidth: Dp = 3.5.dp,
) {
    val targetFraction = SegmentedProgressBarDefaults.calculateProgressFraction(positionMs, durationMs)

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 200),
        label = "progressBarFraction",
    )

    val touchModifier = if (onSeek != null && durationMs > 0L) {
        Modifier.pointerInput(durationMs, onSeek) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.toFloat()
                if (width > 0f) {
                    val fraction = (down.position.x / width).coerceIn(0f, 1f)
                    onSeek(SegmentedProgressBarDefaults.calculateSeekTarget(fraction, durationMs))
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    if (width > 0f) {
                        val fraction = (change.position.x / width).coerceIn(0f, 1f)
                        onSeek(SegmentedProgressBarDefaults.calculateSeekTarget(fraction, durationMs))
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .then(touchModifier),
        horizontalArrangement = Arrangement.spacedBy(gapWidth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val totalSegments = max(1, segmentCount)

        for (i in 0 until totalSegments) {
            val alpha = SegmentedProgressBarDefaults.calculateSegmentAlpha(i, totalSegments, animatedFraction)
            val shape = RoundedCornerShape(2.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}
