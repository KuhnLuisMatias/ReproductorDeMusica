package com.tapplay.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Artist-hero song info typography (JetBrains Mono via AppTypography):
 * - Artist name: 46sp ExtraBold, tight tracking, pure white (top), 3-line max.
 * - Song title: 14sp Medium dim subtitle (bottom), 2-line max.
 * - Centered alignment in upper-middle screen area (placement unchanged).
 */
@Composable
fun SongInfo(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = artist.ifBlank { "TapPlay" },
            color = Color.White,
            fontSize = 46.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
            lineHeight = 50.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title.ifBlank { "Toca para reproducir" },
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
