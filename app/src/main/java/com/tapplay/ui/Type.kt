package com.tapplay.ui

import androidx.compose.material.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.tapplay.R

/**
 * App-wide type: JetBrains Mono variable font. Each weight is pinned to a real
 * variable-font instance via FontVariation (no faux-bold, no static duplicates).
 * minSdk 30 > API 26 → no Build.VERSION guard needed.
 */
@OptIn(ExperimentalTextApi::class)
val AppFont: FontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.jetbrains_mono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.jetbrains_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(R.font.jetbrains_mono, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    )

/** M2 Typography has no defaultFontFamily — every style copies the family, keeping default sizes/weights/lineHeights. */
val AppTypography: Typography =
    with(Typography()) {
        copy(
            h1 = h1.copy(fontFamily = AppFont),
            h2 = h2.copy(fontFamily = AppFont),
            h3 = h3.copy(fontFamily = AppFont),
            h4 = h4.copy(fontFamily = AppFont),
            h5 = h5.copy(fontFamily = AppFont),
            h6 = h6.copy(fontFamily = AppFont),
            subtitle1 = subtitle1.copy(fontFamily = AppFont),
            subtitle2 = subtitle2.copy(fontFamily = AppFont),
            body1 = body1.copy(fontFamily = AppFont),
            body2 = body2.copy(fontFamily = AppFont),
            button = button.copy(fontFamily = AppFont),
            caption = caption.copy(fontFamily = AppFont),
            overline = overline.copy(fontFamily = AppFont),
        )
    }
