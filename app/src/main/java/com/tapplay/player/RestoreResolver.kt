package com.tapplay.player

import com.tapplay.model.Song

/** Where and how the player should resume the persisted session (design D7). */
data class RestorePlan(
    val index: Int,
    val positionMs: Long,
    val volumeLevel: Int,
)

/**
 * Pure resolution of the restore plan from persisted session values.
 * The saved song path locates the queue index; not found → index 0.
 * Volume is coerced to the 0–15 system range; [VOLUME_UNSET] skips restore.
 */
object RestoreResolver {
    const val VOLUME_UNSET = -1

    fun resolve(
        queue: List<Song>,
        savedSongPath: String?,
        savedPositionMs: Long,
        savedVolumeLevel: Int,
    ): RestorePlan {
        val index =
            savedSongPath
                ?.let { path -> queue.indexOfFirst { it.path == path } }
                ?.takeIf { it >= 0 }
                ?: 0
        return RestorePlan(
            index = index,
            positionMs = savedPositionMs.coerceAtLeast(0L),
            volumeLevel =
                if (savedVolumeLevel < 0) {
                    VOLUME_UNSET
                } else {
                    savedVolumeLevel.coerceIn(0, 15)
                },
        )
    }
}
