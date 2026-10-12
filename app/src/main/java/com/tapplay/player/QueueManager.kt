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
        return toMediaItems(songs)
    }

    /**
     * Adopts [sorted] as the new queue in one assignment (the controller
     * timeline was already rewritten to the same order). Main thread only.
     */
    fun adopt(sorted: List<Song>) {
        currentQueue = sorted
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
 * Ranges of [sortedUris] strictly before and strictly after the song playing
 * ([currentUri]) in the new order; null when it is absent. Lets the timeline
 * be rewritten in two replaceMediaItems calls around the current item, which
 * is never replaced (so it is never re-prepared). Pure.
 */
fun splitAroundCurrent(
    sortedUris: List<String>,
    currentUri: String,
): Pair<IntRange, IntRange>? {
    val k = sortedUris.indexOf(currentUri)
    if (k < 0) return null
    return (0 until k) to (k + 1 until sortedUris.size)
}

/**
 * A snapshot encoded off Main may be written only while the queue it was built
 * from is still the live one (same reference); otherwise a newer persist won. Pure.
 */
fun shouldWriteSnapshot(
    liveQueue: List<Song>,
    sorted: List<Song>,
): Boolean = liveQueue === sorted

/**
 * True when a reorder computed off Main must be discarded: the controller, the
 * queue/full-queue references or the query changed while suspended. Pure.
 */
fun isStaleReorder(
    capturedController: Any,
    currentController: Any?,
    queueRef: List<Song>,
    currentQueue: List<Song>,
    fullRef: List<Song>?,
    currentFull: List<Song>?,
    queryAtStart: String,
    activeQuery: String,
): Boolean =
    capturedController !== currentController || queueRef !== currentQueue ||
        fullRef !== currentFull || queryAtStart != activeQuery

/** Pure, thread-safe MediaItem construction (safe to run off the main thread). */
fun toMediaItems(songs: List<Song>): List<MediaItem> = songs.map { it.toMediaItem() }

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
