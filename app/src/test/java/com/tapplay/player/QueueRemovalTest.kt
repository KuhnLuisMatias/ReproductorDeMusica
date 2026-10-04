package com.tapplay.player

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRemovalTest {
    private fun createDummySong(id: String, title: String): Song =
        Song(
            uri = "file:///$id.mp3",
            path = "/music/$id.mp3",
            title = title,
            artist = "Artist $id",
            album = "Album $id",
            durationMs = 180000L,
            artBytes = null,
            lastModified = 1000L,
        )

    @Test
    fun `removeItem removes correct item at index`() {
        val manager = QueueManager()
        val song1 = createDummySong("1", "Song 1")
        val song2 = createDummySong("2", "Song 2")
        val song3 = createDummySong("3", "Song 3")
        manager.buildMediaItems(listOf(song1, song2, song3))

        assertEquals(3, manager.currentQueue.size)
        manager.removeItem(1) // removes song2

        assertEquals(2, manager.currentQueue.size)
        assertEquals("Song 1", manager.currentQueue[0].title)
        assertEquals("Song 3", manager.currentQueue[1].title)
    }

    @Test
    fun `removeItem out of bounds is safe no-op`() {
        val manager = QueueManager()
        val song1 = createDummySong("1", "Song 1")
        manager.buildMediaItems(listOf(song1))

        manager.removeItem(-1)
        assertEquals(1, manager.currentQueue.size)

        manager.removeItem(5)
        assertEquals(1, manager.currentQueue.size)
    }
}
