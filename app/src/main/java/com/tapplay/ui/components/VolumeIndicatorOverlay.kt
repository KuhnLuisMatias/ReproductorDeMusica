package com.tapplay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

object VolumeIndicatorDefaults {
    fun clampVolumePercent(volumePercent: Int): Int = volumePercent.coerceIn(0, 100)

    fun calculateSweepAngle(volumePercent: Int): Float {
        val fraction = (clampVolumePercent(volumePercent) / 100f)
        return fraction * 360f
    }
}

/**
 * Modern circular volume indicator overlay:
 * - Enlarged 300.dp square container ensuring 1:1 concentric alignment for both arc and text.
 * - Perfectly centered neon circular arc surrounding the volume digits inside the circle.
 * - Offset y = -60.dp to align vertically at the exact level of the song title.
 * - Fades in fast on drag start, and delays 250ms then smoothly fades out when finger lifts.
 */
@Composable
fun VolumeIndicatorOverlay(
    volumePercent: Int,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val alphaAnim = remember { Animatable(0f) }

    val clampedVolume = VolumeIndicatorDefaults.clampVolumePercent(volumePercent)
    val animatedVolumeFraction by animateFloatAsState(
        targetValue = clampedVolume / 100f,
        animationSpec = tween(durationMillis = 150),
        label = "volumeArcFraction",
    )

    LaunchedEffect(isDragging) {
        if (isDragging) {
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 150))
        } else if (alphaAnim.value > 0f) {
            delay(250)
            alphaAnim.animateTo(0f, animationSpec = tween(durationMillis = 400))
        }
    }

    if (alphaAnim.value > 0.01f) {
        val currentAlpha = alphaAnim.value

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = currentAlpha * 0.45f))
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .align(Alignment.Center)
                    .offset(y = (-60).dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val diameter = size.minDimension
                    val strokeWidthPx = 8.dp.toPx()
                    val glowWidthPx = 18.dp.toPx()
                    val padding = glowWidthPx / 2f
                    val arcSize = Size(diameter - glowWidthPx, diameter - glowWidthPx)
                    val topLeft = Offset(padding, padding)

                    // Background circular track
                    drawArc(
                        color = Color.White.copy(alpha = currentAlpha * 0.12f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                    )

                    // Active volume arc with glowing neon effect
                    if (animatedVolumeFraction > 0f) {
                        val sweepAngle = animatedVolumeFraction * 360f

                        // Outer soft glow layer
                        drawArc(
                            color = Color.White.copy(alpha = currentAlpha * 0.25f),
                            startAngle = -90f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = glowWidthPx, cap = StrokeCap.Round),
                        )

                        // Main illuminated white arc stroke
                        drawArc(
                            color = Color.White.copy(alpha = currentAlpha * 0.95f),
                            startAngle = -90f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                        )
                    }
                }

                // Central percentage display perfectly concentric with the circular arc
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = "$clampedVolume",
                        color = Color.White.copy(alpha = currentAlpha * 0.95f),
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-2).sp,
                    )
                    Text(
                        text = "%",
                        color = Color.White.copy(alpha = currentAlpha * 0.70f),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
