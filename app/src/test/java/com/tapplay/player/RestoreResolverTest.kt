package com.tapplay.player

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreResolverTest {
    private fun song(path: String) =
        Song(
            uri = "content://media/$path",
            path = "content://media/$path",
            title = path,
            artist = "Desconocido",
            album = "Sin álbum",
            durationMs = 0L,
            artBytes = null,
        )

    private val queue = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))

    @Test
    fun `saved song path locates its queue index`() {
        val plan = RestoreResolver.resolve(queue, "content://media/b.mp3", 1500L, 8)
        assertEquals(1, plan.index)
        assertEquals(1500L, plan.positionMs)
        assertEquals(8, plan.volumeLevel)
    }

    @Test
    fun `unknown saved path falls back to index 0`() {
        val plan = RestoreResolver.resolve(queue, "content://media/gone.mp3", 1500L, 8)
        assertEquals(0, plan.index)
    }

    @Test
    fun `null saved path falls back to index 0`() {
        val plan = RestoreResolver.resolve(queue, null, 0L, 0)
        assertEquals(0, plan.index)
    }

    @Test
    fun `empty queue resolves to index 0`() {
        val plan = RestoreResolver.resolve(emptyList(), "content://media/b.mp3", 0L, 5)
        assertEquals(0, plan.index)
    }

    @Test
    fun `negative position is clamped to zero`() {
        val plan = RestoreResolver.resolve(queue, "content://media/b.mp3", -100L, 5)
        assertEquals(0L, plan.positionMs)
    }

    @Test
    fun `volume is clamped to the 0-15 range`() {
        assertEquals(0, RestoreResolver.resolve(queue, null, 0L, 0).volumeLevel)
        assertEquals(15, RestoreResolver.resolve(queue, null, 0L, 20).volumeLevel)
    }

    @Test
    fun `negative volume values are treated as unset`() {
        assertEquals(RestoreResolver.VOLUME_UNSET, RestoreResolver.resolve(queue, null, 0L, -5).volumeLevel)
    }

    @Test
    fun `unset volume sentinel is preserved so restore can skip it`() {
        val plan = RestoreResolver.resolve(queue, "content://media/a.mp3", 0L, RestoreResolver.VOLUME_UNSET)
        assertEquals(RestoreResolver.VOLUME_UNSET, plan.volumeLevel)
    }

    @Test
    fun `saved uri index matches group-ordered rebuild not flat order`() {
        // Group-ordered rebuild: root files first, then folders alphabetically.
        val groupOrdered =
            listOf(song("z.mp3"), song("Alpha/a.mp3"), song("Alpha/x.mp3"), song("beta/m.mp3"))

        // Saved URI lives in "beta" — group order puts it at index 3, while a
        // flat path-sort would put it at index 2 (after Alpha/*, before z.mp3).
        val plan = RestoreResolver.resolve(groupOrdered, "content://media/beta/m.mp3", 4200L, 7)

        assertEquals(3, plan.index)
        assertEquals(4200L, plan.positionMs)
        assertEquals(7, plan.volumeLevel)
    }
}
