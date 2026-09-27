package com.tapplay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen volume wash: a soft gradient rises from the bottom edge to a
 * height proportional to the current volume, superimposed over the whole
 * player. Fades in fast on the first drag step and fades out only after
 * [isDragging] turns false, i.e. once the finger lifts.
 */
@Composable
fun VolumeIndicatorOverlay(
    volumePercent: Int,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(isDragging) {
        if (isDragging) {
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 150))
        } else if (alphaAnim.value > 0f) {
            delay(150)
            alphaAnim.animateTo(0f, animationSpec = tween(durationMillis = 500))
        }
    }

    if (alphaAnim.value > 0.01f) {
        val currentAlpha = alphaAnim.value
        val fillFraction = (volumePercent / 100f).coerceIn(0f, 1f)

        Box(modifier = modifier.fillMaxSize()) {
            // Gradient wash: transparent at the rising edge, soft white toward
            // the bottom, so it blends into the black background with no hard line.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fraction = fillFraction)
                        .background(
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0f to Color.White.copy(alpha = 0f),
                                        0.7f to Color.White.copy(alpha = currentAlpha * 0.10f),
                                        1f to Color.White.copy(alpha = currentAlpha * 0.24f),
                                    ),
                            ),
                        ),
            )

            Text(
                text = "$volumePercent%",
                color = Color.White.copy(alpha = currentAlpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp),
            )
        }
    }
}
