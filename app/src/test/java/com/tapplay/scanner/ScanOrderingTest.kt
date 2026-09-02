package com.tapplay.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanOrderingTest {
    @Test
    fun `file names sort alphabetically`() {
        val names = listOf("zebra.mp3", "alpha.flac", "mango.wav")
        assertEquals(
            listOf("alpha.flac", "mango.wav", "zebra.mp3"),
            ScanOrdering.orderedByFileName(names),
        )
    }

    @Test
    fun `sorting is case insensitive`() {
        val names = listOf("B.mp3", "a.mp3", "C.mp3")
        assertEquals(
            listOf("a.mp3", "B.mp3", "C.mp3"),
            ScanOrdering.orderedByFileName(names),
        )
    }

    @Test
    fun `numeric prefixes order by number string`() {
        val names = listOf("10 - Track.mp3", "2 - Track.mp3", "1 - Track.mp3")
        assertEquals(
            listOf("1 - Track.mp3", "10 - Track.mp3", "2 - Track.mp3"),
            ScanOrdering.orderedByFileName(names),
        )
    }

    @Test
    fun `generic overload orders items by extracted file name`() {
        data class Entry(val name: String, val payload: Int)
        val items = listOf(Entry("b.mp3", 1), Entry("a.mp3", 2))
        val ordered = ScanOrdering.orderedByFileName(items) { it.name }
        assertEquals(listOf(Entry("a.mp3", 2), Entry("b.mp3", 1)), ordered)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<String>(), ScanOrdering.orderedByFileName(emptyList()))
    }

    @Test
    fun `group ordered puts root files first then folders alphabetically`() {
        data class Item(val folder: String, val name: String)
        val items =
            listOf(
                Item("beta", "m.mp3"),
                Item("", "b.mp3"),
                Item("Alpha", "x.mp3"),
                Item("Alpha", "a.mp3"),
            )

        val ordered = ScanOrdering.groupOrdered(items, { it.folder }, { it.name })

        assertEquals(
            listOf("b.mp3", "a.mp3", "x.mp3", "m.mp3"),
            ordered.map { it.name },
        )
    }

    @Test
    fun `group ordering is case insensitive for groups and file names`() {
        data class Item(val folder: String, val name: String)
        val items =
            listOf(
                Item("Beta", "m.mp3"),
                Item("alpha", "B.mp3"),
                Item("ALPHA", "a.mp3"),
            )

        val ordered = ScanOrdering.groupOrdered(items, { it.folder }, { it.name })

        assertEquals(
            listOf("a.mp3", "B.mp3", "m.mp3"),
            ordered.map { it.name },
        )
    }

    @Test
    fun `group ordered empty input stays empty`() {
        data class Item(val folder: String, val name: String)
        assertEquals(emptyList<Item>(), ScanOrdering.groupOrdered(emptyList<Item>(), { it.folder }, { it.name }))
    }
}
