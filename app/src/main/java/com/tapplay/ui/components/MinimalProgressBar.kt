package com.tapplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Ultra-minimalist progress bar:
 * - Extremely thin line (2.5dp)
 * - Rounded caps
 * - No thumb/circle
 * - No timestamp text
 * - Translucent background track + clean white progress fill
 */
@Composable
fun MinimalProgressBar(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val targetFraction =
        if (durationMs > 0L) {
            (positionMs.toFloat() / max(1L, durationMs).toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 250),
        label = "progressBarFraction",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(1.25.dp))
                .background(Color.White.copy(alpha = 0.18f)),
    ) {
        if (animatedFraction > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(animatedFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.25.dp))
                        .background(Color.White.copy(alpha = 0.85f)),
            )
        }
    }
}
