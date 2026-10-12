package com.tapplay.scanner

/** Pure, unit-testable ordering helpers used by [MusicScanner]. */
object ScanOrdering {
    fun orderedByFileName(items: List<String>): List<String> = items.sortedBy { it.lowercase() }

    fun <T> orderedByFileName(
        items: List<T>,
        fileNameOf: (T) -> String,
    ): List<T> = items.sortedBy { fileNameOf(it).lowercase() }

    /**
     * Folder-GROUPED ordering — single source of truth for queue order across
     * [MusicScanner] and queue rebuilds (spec: folder-selection "Group ordering
     * across root+subfolders"). Root files (folder path "") form the FIRST
     * group; groups sort alphabetically case-insensitive by relative subfolder
     * path; files within each group sort alphabetically case-insensitive.
     */
    fun <T> groupOrdered(
        items: List<T>,
        folderPathOf: (T) -> String,
        fileNameOf: (T) -> String,
    ): List<T> {
        // Decorate-sort-undecorate: keys are computed once per item so the
        // accessors are never called per comparison. sortedWith is stable, so
        // ties keep input order exactly as before.
        class Keyed(
            val folderKey: String,
            val nameKey: String,
            val item: T,
        )
        return items
            .map { Keyed(folderPathOf(it).lowercase(), fileNameOf(it).lowercase(), it) }
            .sortedWith(compareBy({ it.folderKey }, { it.nameKey }))
            .map { it.item }
    }
}
