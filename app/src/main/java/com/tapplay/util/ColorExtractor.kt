package com.tapplay.util

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic background color for songs without embedded cover art.
 *
 * Same file name always produces the same color: the hue comes from the
 * lower-cased file name hash; saturation and lightness are fixed.
 */
object ColorExtractor {
    private const val FIXED_SATURATION = 0.40f
    private const val FIXED_LIGHTNESS = 0.32f
    private const val FULLY_OPAQUE_ALPHA = 0xFF

    fun argbFromFileName(fileName: String): Int = hslToArgb(hueFromFileName(fileName), FIXED_SATURATION, FIXED_LIGHTNESS)

    private fun hueFromFileName(fileName: String): Float {
        val hash = abs(fileName.lowercase().hashCode().toLong())
        return (hash % 360L).toFloat()
    }

    fun hslToArgb(
        hue: Float,
        saturation: Float,
        lightness: Float,
    ): Int {
        val normalizedHue = ((hue % 360f) + 360f) % 360f
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val sector = normalizedHue / 60f
        val secondComponent = chroma * (1f - abs((sector % 2f) - 1f))
        val (redPrime, greenPrime, bluePrime) =
            when {
                normalizedHue < 60f -> Triple(chroma, secondComponent, 0f)
                normalizedHue < 120f -> Triple(secondComponent, chroma, 0f)
                normalizedHue < 180f -> Triple(0f, chroma, secondComponent)
                normalizedHue < 240f -> Triple(0f, secondComponent, chroma)
                normalizedHue < 300f -> Triple(secondComponent, 0f, chroma)
                else -> Triple(chroma, 0f, secondComponent)
            }
        val match = lightness - chroma / 2f
        val red = ((redPrime + match) * 255f).roundToInt()
        val green = ((greenPrime + match) * 255f).roundToInt()
        val blue = ((bluePrime + match) * 255f).roundToInt()
        return (FULLY_OPAQUE_ALPHA shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
