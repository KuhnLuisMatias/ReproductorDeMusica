package com.tapplay.player

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // ---- surgicalMoves / applyMoves: greedy transform plan (queue-polish S2) ----

    private fun movesTransforms(current: List<String>, sorted: List<String>) =
        applyMoves(current, surgicalMoves(current, sorted)) == sorted

    @Test
    fun `surgicalMoves identity is empty`() {
        val list = listOf("a", "b", "c")
        assertEquals(emptyList<Pair<Int, Int>>(), surgicalMoves(list, list))
    }

    @Test
    fun `surgicalMoves swap yields one move and applies to sorted`() {
        val moves = surgicalMoves(listOf("a", "b", "c"), listOf("b", "a", "c"))
        assertEquals(listOf(1 to 0), moves)
        assertTrue(movesTransforms(listOf("a", "b", "c"), listOf("b", "a", "c")))
    }

    @Test
    fun `surgicalMoves reversal yields two greedy moves and applies`() {
        val moves = surgicalMoves(listOf("a", "b", "c"), listOf("c", "b", "a"))
        assertEquals(listOf(2 to 0, 2 to 1), moves)
        assertEquals(listOf("c", "b", "a"), applyMoves(listOf("a", "b", "c"), moves))
    }

    @Test
    fun `surgicalMoves current-at-front yields two moves covering rotation`() {
        val current = listOf("a", "b", "c")
        val sorted = listOf("b", "c", "a")
        assertEquals(2, surgicalMoves(current, sorted).size)
        assertTrue(movesTransforms(current, sorted))
    }

    @Test
    fun `surgicalMoves scaled partial shuffle applies to sorted`() {
        val sorted = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val shuffled = listOf("c", "a", "h", "b", "g", "d", "f", "e")
        val moves = surgicalMoves(shuffled, sorted)
        assertTrue(moves.isNotEmpty())
        assertEquals(sorted, applyMoves(shuffled, moves)) // load-bearing: simulates moveMediaItem sequence
    }

    @Test
    fun `surgicalMoves both empty is empty`() {
        assertEquals(emptyList<Pair<Int, Int>>(), surgicalMoves(emptyList(), emptyList()))
    }
}
