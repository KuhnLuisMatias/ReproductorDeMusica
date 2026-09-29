package com.tapplay.ui.components

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Pure result of [backdropEffect]: how much to blur and dim the content behind the queue sheet. */
data class BackdropEffect(
    val blurDp: Float,
    val dimAlpha: Float,
)

private const val MODERN_BLUR_DP = 12f
private const val MODERN_DIM_ALPHA = 0.25f
private const val LEGACY_DIM_ALPHA = 0.55f

/**
 * Pure backdrop math (JVM-tested, design B2): on API 31+ (blur support)
 * both blur and dim scale linearly with [amount] (0f hidden -> 1f fully
 * shown); below API 31 there is no blur API, so the fallback uses a
 * stronger dim alone, also scaled by [amount].
 */
fun backdropEffect(
    sdkInt: Int,
    amount: Float,
): BackdropEffect =
    if (sdkInt >= Build.VERSION_CODES.S) {
        BackdropEffect(blurDp = MODERN_BLUR_DP * amount, dimAlpha = MODERN_DIM_ALPHA * amount)
    } else {
        BackdropEffect(blurDp = 0f, dimAlpha = LEGACY_DIM_ALPHA * amount)
    }

/**
 * Applies [effect] as draw-only content on top of whatever this [Box] (or
 * other layout) already renders: a blur when [BackdropEffect.blurDp] is
 * positive, then a black dim overlay. Draw-only means hit-testing on the
 * underlying content (and the sheet's own tap-to-dismiss scrim, R2) is
 * untouched.
 */
fun Modifier.queueBackdrop(effect: BackdropEffect): Modifier =
    this
        .then(if (effect.blurDp > 0f) Modifier.blur(effect.blurDp.dp) else Modifier)
        .drawWithContent {
            drawContent()
            if (effect.dimAlpha > 0f) {
                drawRect(color = Color.Black.copy(alpha = effect.dimAlpha))
            }
        }
