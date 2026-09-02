package com.tapplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorExtractorTest {
    @Test
    fun `same file name produces the same color twice`() {
        val first = ColorExtractor.argbFromFileName("my favorite song.mp3")
        val second = ColorExtractor.argbFromFileName("my favorite song.mp3")
        assertEquals(first, second)
    }

    @Test
    fun `file name is case insensitive for color generation`() {
        val lower = ColorExtractor.argbFromFileName("track one.flac")
        val upper = ColorExtractor.argbFromFileName("TRACK ONE.FLAC")
        assertEquals(lower, upper)
    }

    @Test
    fun `generated color is always fully opaque`() {
        val argb = ColorExtractor.argbFromFileName("another song.wav")
        val alpha = (argb ushr 24) and 0xFF
        assertEquals(0xFF, alpha)
    }

    @Test
    fun `hue wraps around at 360 degrees`() {
        assertEquals(
            ColorExtractor.hslToArgb(0f, 0.40f, 0.32f),
            ColorExtractor.hslToArgb(360f, 0.40f, 0.32f),
        )
    }

    @Test
    fun `hsl red hue maps to expected argb`() {
        assertEquals(0xFF723131.toInt(), ColorExtractor.hslToArgb(0f, 0.40f, 0.32f))
    }

    @Test
    fun `hsl blue hue maps to expected argb`() {
        assertEquals(0xFF313172.toInt(), ColorExtractor.hslToArgb(240f, 0.40f, 0.32f))
    }

    @Test
    fun `negative hue is normalized into range`() {
        val normalized = ColorExtractor.hslToArgb(-120f, 0.40f, 0.32f)
        val expected = ColorExtractor.hslToArgb(240f, 0.40f, 0.32f)
        assertEquals(expected, normalized)
    }

    @Test
    fun `int min value hash edge case still yields a color`() {
        val argb = ColorExtractor.argbFromFileName("a.mp3")
        assertTrue(argb != 0)
    }
}
