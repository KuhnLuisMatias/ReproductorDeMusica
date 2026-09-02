package com.tapplay.player

import com.tapplay.model.Song

/**
 * User-selectable queue ordering (spec: queue-sort). Pure and JVM-testable,
 * mirroring ScanOrdering's style (lowercase-only comparisons, no trim).
 * Parsing is rename-tolerant: null/garbage/legacy persisted values → [ADDED].
 */
enum class SortMode {
    ADDED,
    ARTIST,
    DATE_MODIFIED,
    DATE_ADDED;

    companion object {
        fun from(raw: String?): SortMode = runCatching { valueOf(raw ?: "") }.getOrDefault(ADDED)

        /**
         * Legacy 2-arg ordering, frozen to its pre-direction-toggle behavior:
         * ADDED identity, ARTIST ascending, date modes newest-first (nulls
         * last). No production callers after C; kept for the existing tests.
         */
        fun apply(
            songs: List<Song>,
            mode: SortMode,
        ): List<Song> =
            when (mode) {
                ADDED -> songs
                ARTIST -> apply(songs, mode, descending = false)
                DATE_MODIFIED -> apply(songs, mode, descending = true)
                DATE_ADDED -> apply(songs, mode, descending = true)
            }

        /**
         * Direction-aware ordering (design: change C); never mutates [songs].
         * Date modes branch explicitly so nulls stay LAST in BOTH directions
         * (a blind .reversed() would move them first). Null-free modes reverse
         * the ascending list. Stable: equal keys keep incoming order.
         */
        fun apply(
            songs: List<Song>,
            mode: SortMode,
            descending: Boolean,
        ): List<Song> =
            when (mode) {
                ADDED -> if (descending) songs.reversed() else songs
                ARTIST -> {
                    val ascending =
                        songs.sortedWith(
                            compareBy(
                                { it.artist.lowercase() },
                                { it.title.lowercase() },
                            ),
                        )
                    if (descending) ascending.reversed() else ascending
                }
                DATE_MODIFIED ->
                    songs.sortedWith(
                        nullsLastComparator(
                            hasNull = { it.lastModified == null },
                            value = { it.lastModified ?: 0L },
                            descending = descending,
                        ),
                    )
                DATE_ADDED ->
                    songs.sortedWith(
                        nullsLastComparator(
                            hasNull = { it.addedAtMs == null },
                            value = { it.addedAtMs ?: 0L },
                            descending = descending,
                        ),
                    )
            }

        /** Nulls last in both directions (design KD-2): the null flag sorts first, the value flips. */
        private fun nullsLastComparator(
            hasNull: (Song) -> Boolean,
            value: (Song) -> Long,
            descending: Boolean,
        ): Comparator<Song> =
            if (descending) {
                compareBy(hasNull).thenByDescending(value)
            } else {
                compareBy(hasNull).thenBy(value)
            }
    }
}
