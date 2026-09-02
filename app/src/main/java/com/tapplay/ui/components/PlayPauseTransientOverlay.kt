package com.tapplay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Modern, semi-transparent Play or Pause icon with transient fade-out animation.
 *
 * Requirements:
 * - Shows ONLY Play OR Pause, NEVER both together.
 * - Appears centered when triggered, stays briefly, and smoothly fades out to 0 alpha.
 * - Ultra-clean iOS aesthetic.
 */
@Composable
fun PlayPauseTransientOverlay(
    isPlaying: Boolean,
    triggerCount: Long,
    modifier: Modifier = Modifier,
) {
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(triggerCount) {
        if (triggerCount > 0) {
            alphaAnim.snapTo(0f)
            alphaAnim.animateTo(0.65f, animationSpec = tween(durationMillis = 150))
            delay(750)
            alphaAnim.animateTo(0f, animationSpec = tween(durationMillis = 450))
        }
    }

    if (alphaAnim.value > 0.01f) {
        Box(
            modifier = modifier.size(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            val currentAlpha = alphaAnim.value
            val iconColor = Color.White.copy(alpha = currentAlpha)

            Canvas(modifier = Modifier.size(56.dp)) {
                val w = size.width
                val h = size.height

                if (isPlaying) {
                    // Modern iOS-style Pause icon: two vertical rounded pills
                    val barWidth = w * 0.22f
                    val barHeight = h * 0.72f
                    val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    val topY = (h - barHeight) / 2f
                    val leftBarX = w * 0.20f
                    val rightBarX = w * 0.58f

                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(leftBarX, topY),
                        size = Size(barWidth, barHeight),
                        cornerRadius = cornerRadius,
                    )
                    drawRoundRect(
                        color = iconColor,
                        topLeft = Offset(rightBarX, topY),
                        size = Size(barWidth, barHeight),
                        cornerRadius = cornerRadius,
                    )
                } else {
                    // Modern iOS-style Play icon: smooth equilateral triangle
                    val path =
                        Path().apply {
                            val startX = w * 0.28f
                            val endX = w * 0.80f
                            val topY = h * 0.16f
                            val bottomY = h * 0.84f
                            val midY = h * 0.50f

                            moveTo(startX, topY)
                            lineTo(endX, midY)
                            lineTo(startX, bottomY)
                            close()
                        }
                    drawPath(
                        path = path,
                        color = iconColor,
                        style = Fill,
                    )
                }
            }
        }
    }
}
