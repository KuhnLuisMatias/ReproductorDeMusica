package com.tapplay.player

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueNavigationTest {
    private fun song(
        title: String,
        artist: String = "artist",
    ) = Song(
        uri = "content://tree/$title",
        path = "content://tree/$title",
        title = title,
        artist = artist,
        album = "album",
        durationMs = 0L,
        artBytes = null,
    )

    // matchesQuery (R13/R17)

    @Test
    fun `matchesQuery matches title case-insensitively`() {
        assertTrue(song("Love Song").matchesQuery("love"))
        assertFalse(song("Hate Song").matchesQuery("love"))
    }

    @Test
    fun `matchesQuery matches artist`() {
        assertTrue(song("a", artist = "Gonzalo").matchesQuery("gonz"))
        assertFalse(song("a", artist = "Other").matchesQuery("gonz"))
    }

    @Test
    fun `matchesQuery with blank query matches everything`() {
        assertTrue(song("anything").matchesQuery(""))
        assertTrue(song("anything").matchesQuery("   "))
    }

    // indexOfSong (R13/R17)

    @Test
    fun `indexOfSong finds the song by path`() {
        val songs = listOf(song("a"), song("b"), song("c"))
        assertEquals(1, indexOfSong(songs, songs[1].path))
        assertEquals(2, indexOfSong(songs, songs[2].path))
    }

    @Test
    fun `indexOfSong falls back to 0 for unknown or null paths`() {
        val songs = listOf(song("a"), song("b"))
        assertEquals(0, indexOfSong(songs, "missing"))
        assertEquals(0, indexOfSong(songs, null))
    }

    // engagementIndex (R13/R17)

    @Test
    fun `engagementIndex steps forward from the middle of the subset`() {
        val subset = listOf(song("a"), song("b"), song("c"))
        assertEquals(2, engagementIndex(subset, subset[1].path, forward = true))
    }

    @Test
    fun `engagementIndex steps backward from the middle of the subset`() {
        val subset = listOf(song("a"), song("b"), song("c"))
        assertEquals(0, engagementIndex(subset, subset[1].path, forward = false))
    }

    @Test
    fun `engagementIndex returns null at the subset boundary`() {
        val subset = listOf(song("a"), song("b"), song("c"))
        assertNull(engagementIndex(subset, subset[2].path, forward = true)) // forward at lastIndex
        assertNull(engagementIndex(subset, subset[0].path, forward = false)) // backward at 0
    }

    @Test
    fun `engagementIndex enters at first or last when current is out of the subset`() {
        val subset = listOf(song("a"), song("b"), song("c"))
        assertEquals(0, engagementIndex(subset, "missing", forward = true))
        assertEquals(2, engagementIndex(subset, "missing", forward = false))
        assertEquals(0, engagementIndex(subset, null, forward = true))
    }

    @Test
    fun `engagementIndex with empty subset is null`() {
        assertNull(engagementIndex(emptyList(), "missing", forward = true))
        assertNull(engagementIndex(emptyList(), "missing", forward = false))
    }
}
