package com.tapplay.player

import com.tapplay.scanner.CachedTrack
import com.tapplay.scanner.QueueSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSnapshotPolicyTest {
    private fun snapshot(
        folderUri: String,
        trackCount: Int,
    ) = QueueSnapshot(
        folderUri = folderUri,
        tracks =
            (0 until trackCount).map { i ->
                CachedTrack("content://x/$i", "t$i", "a", "al", 0L, null)
            },
    )

    @Test
    fun `null snapshot does not restore`() {
        assertFalse(QueueSnapshotPolicy.shouldRestore(null, "content://folder"))
    }

    @Test
    fun `folder mismatch does not restore`() {
        assertFalse(QueueSnapshotPolicy.shouldRestore(snapshot("content://a", 3), "content://b"))
    }

    @Test
    fun `empty tracks do not restore`() {
        assertFalse(QueueSnapshotPolicy.shouldRestore(snapshot("content://a", 0), "content://a"))
    }

    @Test
    fun `valid matching snapshot restores`() {
        assertTrue(QueueSnapshotPolicy.shouldRestore(snapshot("content://a", 3), "content://a"))
    }
}
