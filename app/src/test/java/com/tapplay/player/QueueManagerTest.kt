package com.tapplay.player

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class QueueManagerTest {
    private fun song(path: String) =
        Song(
            uri = "content://tree/$path",
            path = "content://tree/$path",
            title = path,
            artist = "artist",
            album = "album",
            durationMs = 0L,
            artBytes = null,
        )

    @Test
    fun `buildMediaItems preserves incoming group order without re-sorting`() {
        val manager = QueueManager()
        val groupOrdered =
            listOf(
                song("z.mp3"),
                song("Alpha/a.mp3"),
                song("Alpha/x.mp3"),
                song("beta/m.mp3"),
            )

        val items = manager.buildMediaItems(groupOrdered)

        assertEquals(groupOrdered, manager.currentQueue)
        assertEquals(groupOrdered.map { it.title }, items.map { it.mediaMetadata.title })
    }

    @Test
    fun `empty input yields empty queue and no media items`() {
        val manager = QueueManager()

        val items = manager.buildMediaItems(emptyList())

        assertEquals(emptyList<Song>(), manager.currentQueue)
        assertEquals(0, items.size)
    }

    @Test
    fun `moveItem moves a row down keeping the pre-move list intact`() {
        val manager = QueueManager()
        manager.buildMediaItems(listOf(song("a"), song("b"), song("c")))
        val preMove = manager.currentQueue

        manager.moveItem(from = 0, to = 2)

        assertEquals(listOf("b", "c", "a"), manager.currentQueue.map { it.title })
        assertEquals(listOf("a", "b", "c"), preMove.map { it.title })
        assertNotSame(preMove, manager.currentQueue)
        assertFalse(manager.currentQueue === preMove)
    }

    @Test
    fun `moveItem moves the last row to the start`() {
        val manager = QueueManager()
        manager.buildMediaItems(listOf(song("a"), song("b"), song("c")))

        manager.moveItem(from = 2, to = 0)

        assertEquals(listOf("c", "a", "b"), manager.currentQueue.map { it.title })
    }

    @Test
    fun `moveItem with the same index is a no-op keeping the same instances`() {
        val manager = QueueManager()
        manager.buildMediaItems(listOf(song("a"), song("b"), song("c")))
        val before = manager.currentQueue

        manager.moveItem(from = 1, to = 1)

        assertEquals(listOf("a", "b", "c"), manager.currentQueue.map { it.title })
        assertSame(before, manager.currentQueue)
        assertSame(before[1], manager.currentQueue[1])
    }

    @Test
    fun `moveItem with out-of-bounds indices is a no-op`() {
        val manager = QueueManager()
        manager.buildMediaItems(listOf(song("a"), song("b"), song("c")))
        val before = manager.currentQueue

        manager.moveItem(from = -1, to = 0)
        manager.moveItem(from = 0, to = 3)
        manager.moveItem(from = 5, to = 0)

        assertEquals(listOf("a", "b", "c"), manager.currentQueue.map { it.title })
        assertSame(before, manager.currentQueue)
    }
}
