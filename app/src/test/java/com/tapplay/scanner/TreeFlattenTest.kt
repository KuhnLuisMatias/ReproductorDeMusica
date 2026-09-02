package com.tapplay.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFlattenTest {
    private class FakeNode(
        override val name: String,
        override val isFile: Boolean,
        override val children: List<TreeFolder> = emptyList(),
    ) : TreeFolder {
        override val uri: String = "uri://$name"
    }

    /** Node whose children are resolved lazily, enabling true reference cycles. */
    private class LazyNode(
        override val name: String,
        override val isFile: Boolean,
        childrenProvider: () -> List<TreeFolder>,
    ) : TreeFolder {
        override val uri: String = "uri://$name"
        override val children: List<TreeFolder> by lazy(childrenProvider)
    }

    private fun file(name: String) = FakeNode(name, isFile = true)

    private fun folder(
        name: String,
        vararg children: TreeFolder,
    ) = FakeNode(name, isFile = false, children = children.toList())

    @Test
    fun `multi level scan collects every file exactly once`() {
        val root =
            folder(
                "root",
                file("b.mp3"),
                folder(
                    "Alpha",
                    file("a.mp3"),
                    folder(
                        "Nested",
                        file("deep.mp3"),
                        folder("Deeper", file("deepest.mp3")),
                    ),
                ),
            )

        val entries = runBlocking { TreeFlatten.flatten(root) }

        assertEquals(
            listOf(
                "" to "b.mp3",
                "Alpha" to "a.mp3",
                "Alpha/Nested" to "deep.mp3",
                "Alpha/Nested/Deeper" to "deepest.mp3",
            ),
            entries.map { it.folderPath to it.file.name }.sortedWith(compareBy({ it.first }, { it.second })),
        )
    }

    @Test
    fun `cycle guard visits each document uri at most once and terminates`() {
        lateinit var a: LazyNode
        val b = LazyNode("b", isFile = false) { listOf(a) }
        a = LazyNode("a", isFile = false) { listOf(file("x.mp3"), b) }
        val root = folder("root", a)

        val entries = runBlocking { TreeFlatten.flatten(root) }

        assertEquals(listOf("x.mp3"), entries.map { it.file.name })
    }

    @Test
    fun `same uri appearing twice is not walked twice`() {
        val shared = folder("Shared", file("x.mp3"))
        val root = folder("root", shared, shared)

        val entries = runBlocking { TreeFlatten.flatten(root) }

        assertEquals(listOf("x.mp3"), entries.map { it.file.name })
    }

    @Test
    fun `depth cap 32 stops traversal without hang or crash`() {
        var node: TreeFolder = file("bottom.mp3")
        for (level in 40 downTo 1) {
            node = folder("f$level", file("f$level.mp3"), node)
        }
        val root = folder("root", file("root.mp3"), node)

        val entries = runBlocking { TreeFlatten.flatten(root) }

        // root file + files in folders f1..f31; folders beyond depth 32 are not traversed
        assertEquals(32, entries.size)
        val names = entries.map { it.file.name }
        assertTrue("root.mp3" in names)
        assertTrue("f31.mp3" in names)
    }

    @Test
    fun `unsupported extensions are filtered`() {
        val root =
            folder(
                "root",
                file("note.txt"),
                file("picture.jpg"),
                file("song.mp3"),
                file("SONG.MP3"),
                folder("Alpha", file("track.flac")),
            )

        val entries = runBlocking { TreeFlatten.flatten(root) }

        assertEquals(listOf("song.mp3", "SONG.MP3", "track.flac").sorted(), entries.map { it.file.name }.sorted())
    }

    @Test
    fun `empty tree yields empty result`() {
        val root = folder("root")

        val entries = runBlocking { TreeFlatten.flatten(root) }

        assertEquals(emptyList<TreeFlatten.Entry>(), entries)
    }

    @Test
    fun `shared subtree from two different top-level children is walked once under parallelism`() {
        val shared = folder("Shared", file("x.mp3"))
        val root = folder("root", folder("Left", shared), folder("Right", shared))

        val entries = runBlocking(Dispatchers.Default) { TreeFlatten.flatten(root) }

        assertEquals(1, entries.size)
        assertEquals("x.mp3", entries.single().file.name)
    }
}
