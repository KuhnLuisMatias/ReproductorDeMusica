package com.tapplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameUtilsTest {
    @Test
    fun `mp3 files are supported`() {
        assertTrue(FileNameUtils.isSupportedAudio("song.mp3"))
    }

    @Test
    fun `all seven supported extensions pass the filter`() {
        val extensions = listOf("mp3", "flac", "wav", "ogg", "aac", "m4a", "wma")
        extensions.forEach { ext ->
            assertTrue("extension $ext should be supported", FileNameUtils.isSupportedAudio("track.$ext"))
        }
    }

    @Test
    fun `extension filter is case insensitive`() {
        assertTrue(FileNameUtils.isSupportedAudio("SONG.MP3"))
        assertTrue(FileNameUtils.isSupportedAudio("Song.Flac"))
    }

    @Test
    fun `unsupported files are ignored`() {
        assertFalse(FileNameUtils.isSupportedAudio("notes.txt"))
        assertFalse(FileNameUtils.isSupportedAudio("photo.jpg"))
        assertFalse(FileNameUtils.isSupportedAudio("video.mp4"))
    }

    @Test
    fun `file without extension is not supported`() {
        assertFalse(FileNameUtils.isSupportedAudio("noextension"))
    }

    @Test
    fun `title is file name without extension`() {
        assertEquals("01 - Song Name", FileNameUtils.titleFromFileName("01 - Song Name.mp3"))
    }

    @Test
    fun `title keeps inner dots but drops only the last extension`() {
        assertEquals("a.b", FileNameUtils.titleFromFileName("a.b.mp3"))
    }

    @Test
    fun `title falls back to the whole name when there is no extension`() {
        assertEquals("plain", FileNameUtils.titleFromFileName("plain"))
    }
}
