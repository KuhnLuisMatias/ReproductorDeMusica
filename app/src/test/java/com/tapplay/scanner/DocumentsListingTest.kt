package com.tapplay.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsListingTest {
    private fun entry(
        id: String,
        name: String = id,
        mime: String? = "audio/mpeg",
    ) = ChildEntry(id, name, mime, 0L, 0L)

    private fun folder(
        entry: ChildEntry,
        lister: ChildLister,
        fallback: () -> List<TreeFolder> = { emptyList() },
        isFileFallback: () -> Boolean = { true },
    ): ListedTreeFolder =
        ListedTreeFolder(
            entry,
            "uri:${entry.documentId}",
            lister,
            { child -> folder(child, lister) },
            fallback,
            isFileFallback,
        )

    @Test
    fun `row without document id is dropped`() {
        assertNull(childEntryOf(null, "a.mp3", "audio/mpeg", 1L, 2L))
    }

    @Test
    fun `null columns become safe defaults`() {
        val e = childEntryOf("id1", null, null, null, null)!!
        assertEquals(ChildEntry("id1", "", null, 0L, 0L), e)
    }

    @Test
    fun `directory mime is a folder and audio mime is a file`() {
        val lister = ChildLister { emptyList() }
        assertFalse(folder(entry("d", mime = DIRECTORY_MIME), lister).isFile)
        assertTrue(folder(entry("f", mime = "audio/flac"), lister).isFile)
    }

    @Test
    fun `null mime falls back to DocumentFile check`() {
        val lister = ChildLister { emptyList() }
        assertFalse(folder(entry("x", mime = null), lister, isFileFallback = { false }).isFile)
        assertTrue(folder(entry("x", mime = null), lister, isFileFallback = { true }).isFile)
    }

    @Test
    fun `blank mime falls back, directory is not a file, other mimes are files`() {
        val lister = ChildLister { emptyList() }
        assertFalse(folder(entry("x", mime = ""), lister, isFileFallback = { false }).isFile)
        assertTrue(folder(entry("x", mime = ""), lister, isFileFallback = { true }).isFile)
        assertFalse(folder(entry("x", mime = "  "), lister, isFileFallback = { false }).isFile)
        assertFalse(folder(entry("d", mime = DIRECTORY_MIME), lister, isFileFallback = { true }).isFile)
        assertTrue(folder(entry("a", mime = "audio/mpeg"), lister, isFileFallback = { false }).isFile)
        assertTrue(folder(entry("o", mime = "application/octet-stream"), lister, isFileFallback = { false }).isFile)
    }

    @Test
    fun `root id is the tree id without document segment`() {
        assertEquals("primary:Music", rootDocumentIdFor("primary:Music", null))
        assertEquals("primary:Music", rootDocumentIdFor("primary:Music", ""))
    }

    @Test
    fun `root id is the document id when the uri points to a subfolder`() {
        assertEquals("primary:Music/Rock", rootDocumentIdFor("primary:Music", "primary:Music/Rock"))
    }

    @Test
    fun `children are listed with one query and wrapped with listed names`() {
        var calls = 0
        val lister =
            ChildLister { parent ->
                calls++
                assertEquals("root", parent)
                listOf(entry("a", "A\nb 😀.mp3"), entry("sub", "Sub", DIRECTORY_MIME))
            }
        val root = folder(entry("root", mime = DIRECTORY_MIME), lister)
        assertEquals(listOf("A\nb 😀.mp3", "Sub"), root.children.map { it.name })
        root.children
        root.children.forEach {
            it.name
            it.isFile
            it.uri
        }
        assertEquals(1, calls)
    }

    @Test
    fun `empty folder has no children`() {
        val root = folder(entry("root", mime = DIRECTORY_MIME), ChildLister { emptyList() })
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun `lister failure falls back to DocumentFile children without crashing`() {
        val marker = folder(entry("m"), ChildLister { emptyList() })
        val root =
            folder(
                entry("root", mime = DIRECTORY_MIME),
                ChildLister { throw SecurityException("revoked") },
                fallback = { listOf(marker) },
            )
        assertEquals(listOf(marker), root.children)
    }
}
