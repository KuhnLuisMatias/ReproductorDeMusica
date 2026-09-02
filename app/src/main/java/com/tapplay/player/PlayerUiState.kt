package com.tapplay.player

import com.tapplay.model.Song

enum class ScanPhase { IDLE, SCANNING_FOLDERS, EXTRACTING }

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val scanLoaded: Int = 0,
    val scanTotal: Int = 0,
    val scanPhase: ScanPhase = ScanPhase.IDLE,
    val queueSortMode: SortMode = SortMode.ADDED,
    val queueSortDescending: Boolean = true,
) {
    /** True while a library scan is in flight; auto-hides at completion, failure or empty scans. */
    val isScanning: Boolean get() = scanTotal > 0 && scanLoaded < scanTotal
}
