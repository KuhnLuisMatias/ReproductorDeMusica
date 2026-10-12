package com.tapplay.scanner

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImportTimelineTest {
    @After
    fun tearDown() {
        ImportTimeline.end()
        ImportTimeline.resetHooks()
    }

    @Test
    fun formatEmitsRunPhaseTimeAndFields() {
        val line = ImportTimeline.format(2, "listDone", 1234L, listOf("queries" to 7L, "provider" to "externalstorage"))
        assertEquals("ImportTimeline run=2 phase=listDone t=1234 queries=7 provider=externalstorage", line)
    }

    @Test
    fun formatNeverLeaksUrisOrPaths() {
        val line = ImportTimeline.format(1, "picked", 0L, listOf("x" to "content://a/b", "y" to "/storage/emulated/0/primary:Music"))
        assertFalse(line.contains("/"))
        assertFalse(line.contains("content:"))
        assertFalse(line.contains("primary:"))
    }

    @Test
    fun percentileEdgeCases() {
        assertEquals(0L, ImportTimeline.percentile(longArrayOf(), 50))
        assertEquals(5L, ImportTimeline.percentile(longArrayOf(5), 95))
        assertEquals(2L, ImportTimeline.percentile(longArrayOf(1, 2, 3, 4), 50))
        assertEquals(3L, ImportTimeline.percentile(longArrayOf(1, 2, 3, 4, 5), 50))
        assertEquals(10L, ImportTimeline.percentile(LongArray(10) { it + 1L }, 95))
    }

    @Test
    fun durationStatsSortsInputAndIsThreadSafe() {
        val stats = DurationStats()
        assertEquals(Triple(0L, 0L, 0L), stats.summary())
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { t -> pool.execute { for (i in 1..250) stats.add((t * 250 + i).toLong()) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        val (p50, p95, max) = stats.summary()
        assertEquals(500L, p50)
        assertEquals(950L, p95)
        assertEquals(1000L, max)
    }

    @Test
    fun providerKindUsesWhitelistOnly() {
        assertEquals("externalstorage" to "primary", providerKind("com.android.externalstorage.documents", "primary:Music"))
        assertEquals("other" to "secondary", providerKind("x.y.z", "ABCD-1234:Music"))
        assertEquals("other" to "unknown", providerKind(null, null))
    }

    @Test
    fun markIsNoOpWithoutActiveRunAndEndEmitsSummary() {
        val lines = mutableListOf<String>()
        var now = 100L
        ImportTimeline.setHooks(clock = { now }, sink = { lines += it })
        ImportTimeline.mark("scanStart")
        assertTrue(lines.isEmpty())
        ImportTimeline.begin()
        now = 150L
        ImportTimeline.mark("scanStart")
        ImportTimeline.countProgress()
        ImportTimeline.countRecomposition()
        now = 180L
        ImportTimeline.end()
        ImportTimeline.mark("scanStart")
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("phase=picked t=0"))
        assertTrue(lines[1].contains("phase=scanStart t=50"))
        assertTrue(lines[2].contains("phase=end t=80 progressUpdates=1 recompositions=1"))
    }
}
