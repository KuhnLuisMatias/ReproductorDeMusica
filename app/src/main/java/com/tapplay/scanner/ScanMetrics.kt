package com.tapplay.scanner

/**
 * Timing/count summary of one scan, logged under tag `ScanMetrics`.
 * Timestamps come from SystemClock.elapsedRealtime. Counts and ms only:
 * never file names or URIs. firstListMs = time until the ordered list exists
 * (listing + sorting); today the UI waits for the whole scan.
 */
internal data class ScanMetrics(
    val files: Int,
    val startMs: Long,
    val listedMs: Long,
    val sortedMs: Long,
    val extractedMs: Long,
    val cacheHits: Int,
    val extractions: Int,
) {
    fun format(): String =
        "files=$files listMs=${listedMs - startMs} sortMs=${sortedMs - listedMs} " +
            "extractMs=${extractedMs - sortedMs} totalMs=${extractedMs - startMs} " +
            "firstListMs=${sortedMs - startMs} cacheHits=$cacheHits extractions=$extractions"
}
