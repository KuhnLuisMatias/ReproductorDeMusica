package com.tapplay.player

import com.tapplay.scanner.QueueSnapshot

/**
 * Pure cold-start restore decision: restore instantly only when a snapshot
 * exists, is non-empty, and belongs to the folder we would have scanned.
 * Any mismatch → caller falls back to the existing scan path.
 */
object QueueSnapshotPolicy {
    fun shouldRestore(
        snapshot: QueueSnapshot?,
        folderUri: String?,
    ): Boolean =
        snapshot != null &&
            snapshot.tracks.isNotEmpty() &&
            folderUri != null &&
            snapshot.folderUri == folderUri
}
