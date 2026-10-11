package com.tapplay.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tapplay.model.Song

class QueueManager {
    var currentQueue: List<Song> = emptyList()
        private set

    /**
     * Builds playback media items trusting the incoming order — the scanner's
     * folder-grouped ordering is the single source of truth (spec:
     * folder-selection group ordering); no re-sorting happens here.
     */
    fun buildMediaItems(songs: List<Song>): List<MediaItem> {
        currentQueue = songs
        return songs.map { song -> song.toMediaItem() }
    }

    /**
     * Manual drag-reorder (design: change D): moves [from] → [to] in the
     * current queue (remove-then-insert). Bounds-safe no-op; does NOT touch
     * MediaItems — the controller is updated separately
     * (PlaybackManager.moveQueueItem). Reassigns [currentQueue] immutably.
     */
    fun moveItem(
        from: Int,
        to: Int,
    ) {
        if (from !in currentQueue.indices || to !in currentQueue.indices || from == to) return
        val moved = currentQueue.toMutableList()
        moved.add(to, moved.removeAt(from))
        currentQueue = moved
    }

    /**
     * Removes an item at [index] from the current queue.
     * Bounds-safe no-op; reassigns [currentQueue] immutably.
     */
    fun removeItem(index: Int) {
        if (index !in currentQueue.indices) return
        val mutable = currentQueue.toMutableList()
        mutable.removeAt(index)
        currentQueue = mutable
    }

    private fun Song.toMediaItem(): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}

/**
 * Whether [this] song matches the queue-sheet search [query]: case-
 * insensitive `contains` on title, artist OR album. Blank query matches
 * everything (total fn). Single source of truth for matching —
 * QueueSheet.filterRows delegates to it (R14): what the sheet shows == what
 * playback plays.
 */
fun Song.matchesQuery(query: String): Boolean =
    query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true) ||
        album.contains(query, ignoreCase = true)

/**
 * Index of the song whose path equals [path]; 0 as the safe fallback for
 * unknown/null paths (same ?: 0 precedent as the shipped reorderQueue
 * lookup). Only for callers where fallback-to-0 is correct (restore-on-
 * clear, tap target); hold branches use a raw indexOfFirst because absence
 * is meaningful there.
 */
fun indexOfSong(
    songs: List<Song>,
    path: String?,
): Int = songs.indexOfFirst { it.path == path }.takeIf { it >= 0 } ?: 0

/**
 * Target index for filtered next/previous within [subset] (spec R13):
 * current song in the subset → one step forward/backward, null at the
 * boundary (no-op seek); current song out of the subset (or null path) →
 * entry point, first for forward / last for backward; empty subset → null
 * (no target). Null always means "no navigation".
 */
fun engagementIndex(
    subset: List<Song>,
    currentPath: String?,
    forward: Boolean,
): Int? {
    if (subset.isEmpty()) return null
    val index = subset.indexOfFirst { it.path == currentPath }
    if (index < 0) return if (forward) 0 else subset.lastIndex
    val target = if (forward) index + 1 else index - 1
    return if (target in subset.indices) target else null
}

/**
 * Plans the timeline edits transforming [current] into [sorted] as a sequence
 * of index moves (remove-then-insert — same semantics as QueueManager.moveItem
 * and MediaController.moveMediaItem).
 *
 * Greedy index repair: for each target position i, find where sorted[i]
 * currently sits in the evolving list and move it to i. One sweep suffices: a
 * fixed point invariant holds — after filling slot i, slots 0..i match sorted,
 * and sorted[i] can never be found in 0..i-1 (those slots hold earlier,
 * distinct values), so every search index satisfies j >= i and moves never
 * disturb filled slots. Indices inside the returned pairs are LIVE indices:
 * each (from, to) is valid in the list state AFTER all earlier moves were
 * applied, not in the original input.
 *
 * Precondition (same assumption the queue LazyColumn's `key = song.uri`
 * already makes, QueueSheet L602): [current] and [sorted] contain the same
 * songs with unique uris. Duplicate-uri behavior is out of contract.
 *
 * ponytail: greedy in O(n) moves worst case (n indexOf scans -> O(n^2) total).
 * Upgrade path: minimal-moves via LIS only if profiling ever shows jank on
 * huge queues.
 */
fun surgicalMoves(current: List<String>, sorted: List<String>): List<Pair<Int, Int>> {
    val work = current.toMutableList()
    val moves = mutableListOf<Pair<Int, Int>>()
    for (i in sorted.indices) {
        val j = work.indexOf(sorted[i])
        if (j != i) {
            moves += j to i
            work.add(i, work.removeAt(j))
        }
    }
    return moves
}

/**
 * Applies surgicalMoves output: each pair is one remove-then-insert move on
 * the evolving list — the executable simulation of the controller + mirror
 * move pair (QueueManager.moveItem run in sequence). Pure; used by
 * QueueManagerTest to verify plans end-to-end.
 */
fun applyMoves(current: List<String>, moves: List<Pair<Int, Int>>): List<String> {
    val work = current.toMutableList()
    for ((from, to) in moves) work.add(to, work.removeAt(from))
    return work
}
