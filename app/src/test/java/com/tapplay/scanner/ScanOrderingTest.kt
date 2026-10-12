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

class ScanOrderingEquivalenceTest {
    private data class Item(val folder: String, val name: String, val id: Int)

    private val oldOrder: (List<Item>) -> List<Item> = { items ->
        items.sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
    }

    private val sample =
        listOf(
            Item("", "Zeta.mp3", 0), Item("", "álbum.mp3", 1), Item("", "Alfa.mp3", 2),
            Item("", "alfa.mp3", 3), Item("", "ALFA.mp3", 4), Item("", "10 - x.mp3", 5),
            Item("", "2 - x.mp3", 6), Item("", "😀 song.mp3", 7), Item("", "a😀.mp3", 8),
            Item("Ñandú", "b.mp3", 9), Item("ñandú", "a.mp3", 10), Item("Rock/Live", "a\nb.mp3", 11),
            Item("rock", "z.mp3", 12), Item("", "sin_extension", 13), Item("", "", 14),
            Item("", "Alfa.mp3", 15), Item("Rock/Live", "A.mp3", 16), Item("ROCK/live", "a.mp3", 17),
        )

    @Test
    fun `precomputed keys give exactly the same order as the old comparator`() {
        val new = ScanOrdering.groupOrdered(sample, { it.folder }, { it.name })
        assertEquals(oldOrder(sample).map { it.id }, new.map { it.id })
        val shuffled = sample.reversed()
        assertEquals(
            oldOrder(shuffled).map { it.id },
            ScanOrdering.groupOrdered(shuffled, { it.folder }, { it.name }).map { it.id },
        )
    }

    @Test
    fun `name and folder are read at most once per item`() {
        val reads = IntArray(sample.size)
        ScanOrdering.groupOrdered(sample, { it.folder }, {
            reads[it.id]++
            it.name
        })
        assertEquals(true, reads.all { it <= 1 })
    }
}
