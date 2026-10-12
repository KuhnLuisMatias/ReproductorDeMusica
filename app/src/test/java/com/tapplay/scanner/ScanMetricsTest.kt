package com.tapplay.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanMetricsTest {
    @Test
    fun `format derives phase durations from timestamps and contains only counts and ms`() {
        val metrics =
            ScanMetrics(
                files = 3,
                startMs = 1_000,
                listedMs = 1_200,
                sortedMs = 1_250,
                extractedMs = 2_000,
                cacheHits = 1,
                extractions = 2,
            )
        assertEquals(
            "files=3 listMs=200 sortMs=50 extractMs=750 totalMs=1000 firstListMs=250 cacheHits=1 extractions=2",
            metrics.format(),
        )
    }
}
