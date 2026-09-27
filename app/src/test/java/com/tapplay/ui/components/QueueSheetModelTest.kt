package com.tapplay.ui.components

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSheetModelTest {
    private fun song(
        title: String,
        durationMs: Long,
        artist: String = "artist",
    ) = Song(
        uri = "content://tree/$title",
        path = "content://tree/$title",
        title = title,
        artist = artist,
        album = "album",
        durationMs = durationMs,
        artBytes = null,
    )

    @Test
    fun `rows render every song and highlight only the current track`() {
        val queue = listOf(song("a", 61_000L), song("b", 200_000L), song("c", 0L))

        val rows = queueRows(queue, currentIndex = 1)

        assertEquals(3, rows.size)
        assertEquals(listOf("a", "b", "c"), rows.map { it.song.title })
        assertTrue(rows[1].isCurrent)
        assertFalse(rows[0].isCurrent)
        assertFalse(rows[2].isCurrent)
    }

    @Test
    fun `durations format as m_ss`() {
        val rows = queueRows(listOf(song("a", 200_000L), song("b", 65_000L), song("c", 0L)), currentIndex = -1)

        assertEquals("3:20", rows[0].durationText)
        assertEquals("1:05", rows[1].durationText)
        assertEquals("0:00", rows[2].durationText)
    }

    @Test
    fun `no row is current when index is out of range`() {
        val rows = queueRows(listOf(song("a", 0L)), currentIndex = -1)

        assertEquals(1, rows.size)
        assertFalse(rows[0].isCurrent)
    }

    @Test
    fun `empty queue yields empty rows`() {
        assertEquals(emptyList<QueueRow>(), queueRows(emptyList(), currentIndex = 0))
    }

    @Test
    fun `filterRows with blank query returns all rows`() {
        val rows = queueRows(listOf(song("a", 0L), song("b", 0L)), currentIndex = 0)

        assertEquals(rows, filterRows(rows, ""))
        assertEquals(rows, filterRows(rows, "   "))
    }

    @Test
    fun `filterRows matches title case-insensitively`() {
        val rows = queueRows(listOf(song("Alpha", 0L), song("beta", 0L)), currentIndex = 0)

        val filtered = filterRows(rows, "ALPH")

        assertEquals(listOf("Alpha"), filtered.map { it.song.title })
    }

    @Test
    fun `filterRows matches artist`() {
        val rows = queueRows(listOf(song("a", 0L, artist = "Gonzalo"), song("b", 0L)), currentIndex = 0)

        val filtered = filterRows(rows, "gonz")

        assertEquals(listOf("a"), filtered.map { it.song.title })
    }

    @Test
    fun `filterRows with no match returns empty`() {
        val rows = queueRows(listOf(song("a", 0L), song("b", 0L)), currentIndex = 0)

        assertTrue(filterRows(rows, "zzz").isEmpty())
    }

    @Test
    fun `filterRows preserves real queue indices`() {
        val rows =
            queueRows(
                listOf(song("a", 0L, artist = "zeta"), song("b", 0L), song("alpha", 0L, artist = "zeta")),
                currentIndex = 1,
            )

        val filtered = filterRows(rows, "zeta")

        assertEquals(listOf(0, 2), filtered.map { it.index })
    }

    @Test
    fun `scrollbar hidden when the list fits without scrolling`() {
        assertNull(
            scrollbarThumb(
                totalItems = 5,
                visibleItems = 5,
                firstIndex = 0,
                firstItemOffsetPx = 0f,
                firstItemSizePx = 100f,
                viewportPx = 500f,
                minThumbPx = 32f,
            ),
        )
        assertNull(
            scrollbarThumb(
                totalItems = 0,
                visibleItems = 0,
                firstIndex = 0,
                firstItemOffsetPx = 0f,
                firstItemSizePx = 100f,
                viewportPx = 500f,
                minThumbPx = 32f,
            ),
        )
    }

    @Test
    fun `thumb at top starts at zero with proportional height`() {
        val thumb =
            scrollbarThumb(
                totalItems = 10,
                visibleItems = 5,
                firstIndex = 0,
                firstItemOffsetPx = 0f,
                firstItemSizePx = 100f,
                viewportPx = 1000f,
                minThumbPx = 32f,
            )

        assertEquals(Pair(0f, 500f), thumb)
    }

    @Test
    fun `thumb at bottom reaches the viewport end`() {
        val thumb =
            scrollbarThumb(
                totalItems = 10,
                visibleItems = 5,
                firstIndex = 5,
                firstItemOffsetPx = 0f,
                firstItemSizePx = 100f,
                viewportPx = 1000f,
                minThumbPx = 32f,
            )

        assertEquals(Pair(500f, 500f), thumb)
    }

    @Test
    fun `thumb respects the minimum height`() {
        val thumb =
            scrollbarThumb(
                totalItems = 1000,
                visibleItems = 1,
                firstIndex = 0,
                firstItemOffsetPx = 0f,
                firstItemSizePx = 100f,
                viewportPx = 100f,
                minThumbPx = 32f,
            )

        assertEquals(32f, thumb?.second)
    }

    @Test
    fun `partial first item scrolls the thumb proportionally`() {
        val thumb =
            scrollbarThumb(
                totalItems = 10,
                visibleItems = 5,
                firstIndex = 0,
                firstItemOffsetPx = -50f,
                firstItemSizePx = 100f,
                viewportPx = 1000f,
                minThumbPx = 32f,
            )

        // 0.5 items scrolled of 5 scrollable → 10% of the free track (500px) = 50px.
        assertEquals(50f, thumb?.first)
        assertEquals(500f, thumb?.second)
    }

    @Test
    fun `targetPosition with no offset stays at the dragged slot`() {
        val centers = listOf(100f, 300f, 500f, 700f)

        assertEquals(1, targetPosition(draggedAt = 1, dragOffsetY = 0f, centers = centers))
        assertEquals(2, targetPosition(draggedAt = 2, dragOffsetY = 0f, centers = centers))
    }

    @Test
    fun `targetPosition crossing one center down moves one slot`() {
        val centers = listOf(100f, 300f, 500f, 700f)

        // Visual center 300 + 250 = 550 crosses 500 but not 700 → exactly one slot.
        assertEquals(2, targetPosition(draggedAt = 1, dragOffsetY = 250f, centers = centers))
    }

    @Test
    fun `targetPosition fast flick down crosses two slots`() {
        val centers = listOf(100f, 300f, 500f, 700f)

        // Visual center 300 + 450 = 750 crosses 500 and 700 → two slots.
        assertEquals(3, targetPosition(draggedAt = 1, dragOffsetY = 450f, centers = centers))
    }

    @Test
    fun `targetPosition moving up crosses centers upward`() {
        val centers = listOf(100f, 300f, 500f, 700f)

        // 700 - 250 = 450 crosses 500 → one slot up.
        assertEquals(2, targetPosition(draggedAt = 3, dragOffsetY = -250f, centers = centers))
        // 500 - 500 = 0 crosses 300 and 100 → two slots up.
        assertEquals(0, targetPosition(draggedAt = 2, dragOffsetY = -500f, centers = centers))
    }

    @Test
    fun `targetPosition with empty centers returns the dragged slot`() {
        assertEquals(4, targetPosition(draggedAt = 4, dragOffsetY = 100f, centers = emptyList()))
    }

    @Test
    fun `targetPosition clamps an out-of-range dragged slot`() {
        val centers = listOf(100f, 300f, 500f)

        assertEquals(2, targetPosition(draggedAt = 7, dragOffsetY = 0f, centers = centers))
        assertEquals(0, targetPosition(draggedAt = -1, dragOffsetY = 0f, centers = centers))
    }

    @Test
    fun `neighborShift opens the slot below when dragging down`() {
        // draggedAt=1 → target=3: rows 2 and 3 lift one height, the rest stay.
        assertEquals(-66f, neighborShift(position = 2, draggedAt = 1, target = 3, draggedHeight = 66f))
        assertEquals(-66f, neighborShift(position = 3, draggedAt = 1, target = 3, draggedHeight = 66f))
        assertEquals(0f, neighborShift(position = 0, draggedAt = 1, target = 3, draggedHeight = 66f))
    }

    @Test
    fun `neighborShift opens the slot above when dragging up`() {
        // draggedAt=3 → target=1: rows 1 and 2 drop one height, the rest stay.
        assertEquals(66f, neighborShift(position = 1, draggedAt = 3, target = 1, draggedHeight = 66f))
        assertEquals(66f, neighborShift(position = 2, draggedAt = 3, target = 1, draggedHeight = 66f))
        assertEquals(0f, neighborShift(position = 0, draggedAt = 3, target = 1, draggedHeight = 66f))
    }

    @Test
    fun `neighborShift never moves the dragged row`() {
        assertEquals(0f, neighborShift(position = 1, draggedAt = 1, target = 3, draggedHeight = 66f))
        assertEquals(0f, neighborShift(position = 3, draggedAt = 3, target = 1, draggedHeight = 66f))
    }

    @Test
    fun `neighborShift with no target change is all zeros`() {
        for (position in 0..4) {
            assertEquals(0f, neighborShift(position = position, draggedAt = 2, target = 2, draggedHeight = 66f))
        }
    }

    @Test
    fun `artistSuggestionsFor with blank query returns nothing`() {
        assertTrue(artistSuggestionsFor(listOf("Gonzalo", "Beta"), "").isEmpty())
        assertTrue(artistSuggestionsFor(listOf("Gonzalo", "Beta"), "   ").isEmpty())
    }

    @Test
    fun `artistSuggestionsFor matches case-insensitively and excludes exact match`() {
        val artists = listOf("Gonzalo", "Gonzalo Jr", "Beta")

        assertEquals(listOf("Gonzalo", "Gonzalo Jr"), artistSuggestionsFor(artists, "gonz"))
        assertEquals(listOf("Gonzalo Jr"), artistSuggestionsFor(artists, "Gonzalo"))
    }

    @Test
    fun `artistSuggestionsFor dedupes and respects the limit`() {
        val artists = listOf("Gonzalo", "Gonzalo", "Gonzalo Jr", "Gonzalo III")

        assertEquals(listOf("Gonzalo Jr"), artistSuggestionsFor(artists, "gonzalo j"))
        assertEquals(2, artistSuggestionsFor(artists, "gonzalo", limit = 2).size)
    }

    @Test
    fun `scrollbarDragToIndex clamps to the reachable scroll range`() {
        assertEquals(0, scrollbarDragToIndex(dragY = -100f, totalItems = 50, visibleItems = 5, viewportPx = 1000f, minThumbPx = 32f))
        // 45 = totalItems - visibleItems: the last index that still shows a full page (D0.9 scrollbarThumb convention).
        assertEquals(
            45,
            scrollbarDragToIndex(dragY = 10_000f, totalItems = 50, visibleItems = 5, viewportPx = 1000f, minThumbPx = 32f),
        )
    }

    @Test
    fun `scrollbarDragToIndex maps the middle of the track to the middle of the list`() {
        val index =
            scrollbarDragToIndex(dragY = 500f, totalItems = 101, visibleItems = 1, viewportPx = 1000f, minThumbPx = 32f)

        assertEquals(50, index)
    }

    @Test
    fun `scrollbarDragToIndex with nothing to scroll returns the first item`() {
        assertEquals(0, scrollbarDragToIndex(dragY = 500f, totalItems = 5, visibleItems = 5, viewportPx = 1000f, minThumbPx = 32f))
    }
}
