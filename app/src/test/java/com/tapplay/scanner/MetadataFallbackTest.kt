package com.tapplay.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataFallbackTest {
    @Test
    fun `tag title wins over file name`() {
        assertEquals("Real Title", MetadataFallbacks.resolveTitle("Real Title", "fallback.mp3"))
    }

    @Test
    fun `null title tag falls back to file name`() {
        assertEquals("fallback", MetadataFallbacks.resolveTitle(null, "fallback.mp3"))
    }

    @Test
    fun `blank title tag falls back to file name`() {
        assertEquals("fallback", MetadataFallbacks.resolveTitle("   ", "fallback.mp3"))
    }

    @Test
    fun `title fallback drops the extension`() {
        assertEquals("01 - Song Name", MetadataFallbacks.resolveTitle(null, "01 - Song Name.flac"))
    }

    @Test
    fun `title fallback keeps inner dots`() {
        assertEquals("a.b", MetadataFallbacks.resolveTitle(null, "a.b.mp3"))
    }

    @Test
    fun `title fallback keeps name without extension`() {
        assertEquals("plain", MetadataFallbacks.resolveTitle(null, "plain"))
    }

    @Test
    fun `missing artist falls back to Desconocido`() {
        assertEquals("Desconocido", MetadataFallbacks.resolveArtist(null))
        assertEquals("Desconocido", MetadataFallbacks.resolveArtist(""))
        assertEquals("Desconocido", MetadataFallbacks.resolveArtist("  "))
    }

    @Test
    fun `artist tag wins when present`() {
        assertEquals("Fito Paez", MetadataFallbacks.resolveArtist("Fito Paez"))
    }

    @Test
    fun `missing album falls back to Sin album`() {
        assertEquals("Sin álbum", MetadataFallbacks.resolveAlbum(null))
        assertEquals("Sin álbum", MetadataFallbacks.resolveAlbum(""))
    }

    @Test
    fun `album tag wins when present`() {
        assertEquals("El amor después del amor", MetadataFallbacks.resolveAlbum("El amor después del amor"))
    }
}
