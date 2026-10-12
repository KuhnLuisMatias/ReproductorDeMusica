package com.tapplay.player

import com.tapplay.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SortModeTest {
    private fun song(
        title: String,
        artist: String,
        lastModified: Long? = null,
        addedAtMs: Long? = null,
    ) = Song(
        uri = "uri://$title",
        path = "path://$title",
        title = title,
        artist = artist,
        album = "album",
        durationMs = 1_000L,
        lastModified = lastModified,
        addedAtMs = addedAtMs,
    )

    @Test
    fun `ADDED mode is the identity`() {
        val songs = listOf(song("b", "B"), song("a", "A"))

        val result = SortMode.apply(songs, SortMode.ADDED)

        assertSame(songs, result)
        assertSame(songs[0], result[0])
        assertSame(songs[1], result[1])
    }

    @Test
    fun `artist sort is case insensitive with title tiebreak`() {
        val songs =
            listOf(
                song(title = "Help", artist = "beatles"),
                song(title = "Airbag", artist = "Arista"),
                song(title = "Abbey", artist = "Beatles"),
            )

        val ordered = SortMode.apply(songs, SortMode.ARTIST)

        assertEquals(listOf("Airbag", "Abbey", "Help"), ordered.map { it.title })
    }

    @Test
    fun `date modified sorts newest first with nulls strictly last`() {
        val songs =
            listOf(
                song(title = "old", artist = "a", lastModified = 1_000L),
                song(title = "newest", artist = "b", lastModified = 3_000L),
                song(title = "missing", artist = "c", lastModified = null),
                song(title = "middle", artist = "d", lastModified = 2_000L),
            )

        val ordered = SortMode.apply(songs, SortMode.DATE_MODIFIED)

        assertEquals(listOf("newest", "middle", "old", "missing"), ordered.map { it.title })
    }

    @Test
    fun `date modified with all nulls keeps incoming order (stable)`() {
        val songs = listOf(song("c", "a"), song("a", "b"), song("b", "c"))

        val ordered = SortMode.apply(songs, SortMode.DATE_MODIFIED)

        assertEquals(listOf("c", "a", "b"), ordered.map { it.title })
    }

    @Test
    fun `date added sorts newest first with nulls strictly last`() {
        val songs =
            listOf(
                song(title = "old", artist = "a", addedAtMs = 1_000L),
                song(title = "newest", artist = "b", addedAtMs = 3_000L),
                song(title = "missing", artist = "c", addedAtMs = null),
                song(title = "middle", artist = "d", addedAtMs = 2_000L),
            )

        val ordered = SortMode.apply(songs, SortMode.DATE_ADDED)

        assertEquals(listOf("newest", "middle", "old", "missing"), ordered.map { it.title })
    }

    @Test
    fun `date added with all nulls keeps incoming order (stable)`() {
        val songs = listOf(song("c", "a"), song("a", "b"), song("b", "c"))

        val ordered = SortMode.apply(songs, SortMode.DATE_ADDED)

        assertEquals(listOf("c", "a", "b"), ordered.map { it.title })
    }

    @Test
    fun `date added ascending is oldest first with nulls last`() {
        val songs =
            listOf(
                song(title = "old", artist = "a", addedAtMs = 1_000L),
                song(title = "newest", artist = "b", addedAtMs = 3_000L),
                song(title = "missing", artist = "c", addedAtMs = null),
                song(title = "middle", artist = "d", addedAtMs = 2_000L),
            )

        val ordered = SortMode.apply(songs, SortMode.DATE_ADDED, descending = false)

        assertEquals(listOf("old", "middle", "newest", "missing"), ordered.map { it.title })
    }

    @Test
    fun `date added descending is newest first with nulls last`() {
        val songs =
            listOf(
                song(title = "old", artist = "a", addedAtMs = 1_000L),
                song(title = "newest", artist = "b", addedAtMs = 3_000L),
                song(title = "missing", artist = "c", addedAtMs = null),
                song(title = "middle", artist = "d", addedAtMs = 2_000L),
            )

        val ordered = SortMode.apply(songs, SortMode.DATE_ADDED, descending = true)

        assertEquals(listOf("newest", "middle", "old", "missing"), ordered.map { it.title })
    }

    @Test
    fun `date modified ascending is oldest first with nulls last`() {
        val songs =
            listOf(
                song(title = "old", artist = "a", lastModified = 1_000L),
                song(title = "newest", artist = "b", lastModified = 3_000L),
                song(title = "missing", artist = "c", lastModified = null),
                song(title = "middle", artist = "d", lastModified = 2_000L),
            )

        val ordered = SortMode.apply(songs, SortMode.DATE_MODIFIED, descending = false)

        assertEquals(listOf("old", "middle", "newest", "missing"), ordered.map { it.title })
    }

    @Test
    fun `date modified descending is newest first with nulls last`() {
        val songs =
            listOf(
                song(title = "old", artist = "a", lastModified = 1_000L),
                song(title = "newest", artist = "b", lastModified = 3_000L),
                song(title = "missing", artist = "c", lastModified = null),
                song(title = "middle", artist = "d", lastModified = 2_000L),
            )

        val ordered = SortMode.apply(songs, SortMode.DATE_MODIFIED, descending = true)

        assertEquals(listOf("newest", "middle", "old", "missing"), ordered.map { it.title })
    }

    @Test
    fun `artist descending reverses ascending`() {
        val songs =
            listOf(
                song(title = "Help", artist = "beatles"),
                song(title = "Airbag", artist = "Arista"),
                song(title = "Abbey", artist = "Beatles"),
            )

        val ordered = SortMode.apply(songs, SortMode.ARTIST, descending = true)

        assertEquals(listOf("Help", "Abbey", "Airbag"), ordered.map { it.title })
    }

    @Test
    fun `added descending reverses scan order`() {
        val songs = listOf(song("b", "B"), song("a", "A"), song("c", "C"))

        val ordered = SortMode.apply(songs, SortMode.ADDED, descending = true)

        assertEquals(listOf("c", "a", "b"), ordered.map { it.title })
    }

    // ---- S14: ARTIST con claves precalculadas == orden anterior (caracterización) ----

    // Implementación previa (comparador con lowercase por comparación), congelada como oráculo.
    private fun legacyArtistAscending(songs: List<Song>): List<Song> =
        songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

    // album = índice de entrada: distingue canciones con artista y título iguales.
    private fun mixedLibrary(): List<Song> =
        listOf(
            song("Zeta", "ÁLVARO"),
            song("alfa", "Álvaro"),
            song("10 Años", "beatles"),
            song("2 Minutes", "Beatles"),
            song("Help", ""),
            song("", "Beatles"),
            song("help", "BEATLES"),
            song("Help", "beatles"),
            song("Help", "beatles"),
            song("ñandú", "Ñu"),
            song("Zeta", "zebra"),
            song("1", "1"),
            song("", ""),
            song("Éxito", "Desconocido"),
        ).mapIndexed { index, s -> s.copy(album = "i$index") }

    @Test
    fun `artist ascending keeps exactly the legacy order with accents case numbers empties and duplicates`() {
        val songs = mixedLibrary()

        val ordered = SortMode.apply(songs, SortMode.ARTIST, descending = false)

        assertEquals(legacyArtistAscending(songs).map { it.album }, ordered.map { it.album })
    }

    @Test
    fun `artist descending is the exact reverse of the ascending list`() {
        val songs = mixedLibrary()

        val asc = SortMode.apply(songs, SortMode.ARTIST, descending = false)
        val desc = SortMode.apply(songs, SortMode.ARTIST, descending = true)

        assertEquals(asc.reversed().map { it.album }, desc.map { it.album })
    }

    @Test
    fun `artist sort of an empty list is empty`() {
        assertEquals(emptyList<Song>(), SortMode.apply(emptyList(), SortMode.ARTIST, descending = false))
        assertEquals(emptyList<Song>(), SortMode.apply(emptyList(), SortMode.ARTIST, descending = true))
    }

    @Test
    fun `artist sort is stable when artist and title are equal`() {
        val songs =
            listOf(
                song("Help", "Beatles").copy(album = "first"),
                song("help", "beatles").copy(album = "second"),
                song("HELP", "BEATLES").copy(album = "third"),
            )

        val ordered = SortMode.apply(songs, SortMode.ARTIST, descending = false)

        assertEquals(listOf("first", "second", "third"), ordered.map { it.album })
    }

    @Test
    fun `from parses exact names`() {
        assertEquals(SortMode.ADDED, SortMode.from("ADDED"))
        assertEquals(SortMode.ARTIST, SortMode.from("ARTIST"))
        assertEquals(SortMode.DATE_MODIFIED, SortMode.from("DATE_MODIFIED"))
        assertEquals(SortMode.DATE_ADDED, SortMode.from("DATE_ADDED"))
    }

    @Test
    fun `from falls back to ADDED on garbage legacy null or case mismatch`() {
        assertEquals(SortMode.ADDED, SortMode.from("NOPE"))
        assertEquals(SortMode.ADDED, SortMode.from(""))
        assertEquals(SortMode.ADDED, SortMode.from(null))
        assertEquals(SortMode.ADDED, SortMode.from("ADDED "))
        assertEquals(SortMode.ADDED, SortMode.from("artist"))
    }
}
