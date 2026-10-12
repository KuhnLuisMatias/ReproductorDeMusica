package com.tapplay.scanner

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/**
 * End-to-end import timeline (ISA S7): one log line per phase, from the folder
 * pick until the first song plays. Logs carry ONLY numbers and fixed phase/key
 * names (any value is sanitized: no URIs, paths or file names). Measurement
 * only: no marker changes flow, state or stored data. Without an active run
 * every call is a no-op (restore, reorder).
 */
internal object ImportTimeline {
    private const val TAG = "ImportTimeline"
    private const val HEARTBEAT_MS = 50L
    private const val LAG_OVER_MS = 100L
    private const val MAX_RUN_MS = 300_000L

    @Volatile private var clock: () -> Long = { SystemClock.elapsedRealtime() }

    @Volatile private var sink: (String) -> Unit = { Log.d(TAG, it) }
    private val lock = Any()
    private var runs = 0
    private var active = false
    private var t0 = 0L
    private var published = false
    private var provider = "other"
    private var volume = "unknown"
    private var heartbeat: Job? = null
    private val progressUpdates = AtomicInteger(0)
    private val recompositions = AtomicInteger(0)
    private val lagMax = AtomicInteger(0)
    private val lagOver = AtomicInteger(0)

    internal fun setHooks(
        clock: () -> Long,
        sink: (String) -> Unit,
    ) {
        this.clock = clock
        this.sink = sink
    }

    internal fun resetHooks() {
        clock = { SystemClock.elapsedRealtime() }
        sink = { Log.d(TAG, it) }
    }

    val isActive: Boolean get() = active

    /** Starts a run (closing any previous one) and logs `picked`. Returns the run number. */
    fun begin(): Int {
        end()
        synchronized(lock) {
            runs++
            active = true
            published = false
            provider = "other"
            volume = "unknown"
            t0 = clock()
            progressUpdates.set(0)
            recompositions.set(0)
            lagMax.set(0)
            lagOver.set(0)
        }
        mark("picked")
        return runs
    }

    fun mark(
        phase: String,
        vararg fields: Pair<String, Any>,
    ) {
        val line =
            synchronized(lock) {
                if (!active) return
                if (phase == "queuePublished") published = true
                format(runs, phase, clock() - t0, fields.toList())
            }
        sink(line)
    }

    fun setKind(
        authority: String?,
        treeDocId: String?,
    ) {
        val kind = providerKind(authority, treeDocId)
        synchronized(lock) {
            if (!active) return
            provider = kind.first
            volume = kind.second
        }
    }

    fun countProgress() {
        if (active) progressUpdates.incrementAndGet()
    }

    fun countRecomposition() {
        if (active) recompositions.incrementAndGet()
    }

    /** `firstPlaying` counts only after the new queue was published; it also closes the run. */
    fun onPlaying() {
        if (!active || !published) return
        mark("firstPlaying")
        end()
    }

    /** Main-thread lag probe, alive only while the run is. Cancelled by [end]. */
    fun startHeartbeat(scope: CoroutineScope) {
        val job =
            scope.launch {
                val started = clock()
                while (isActive && active) {
                    val before = clock()
                    delay(HEARTBEAT_MS)
                    val lag = (clock() - before - HEARTBEAT_MS).coerceAtLeast(0L).toInt()
                    lagMax.updateAndGet { maxOf(it, lag) }
                    if (lag > LAG_OVER_MS) lagOver.incrementAndGet()
                    if (clock() - started > MAX_RUN_MS) {
                        mark("timeout")
                        end()
                    }
                }
            }
        synchronized(lock) {
            if (active) heartbeat = job else job.cancel()
        }
    }

    /** Idempotent: logs `end` with the summary once and stops the heartbeat. */
    fun end() {
        val line =
            synchronized(lock) {
                if (!active) return
                active = false
                heartbeat?.cancel()
                heartbeat = null
                format(
                    runs,
                    "end",
                    clock() - t0,
                    listOf(
                        "progressUpdates" to progressUpdates.get(),
                        "recompositions" to recompositions.get(),
                        "mainLagMaxMs" to lagMax.get(),
                        "mainLagOver100" to lagOver.get(),
                        "provider" to provider,
                        "volume" to volume,
                    ),
                )
            }
        sink(line)
    }

    internal fun format(
        run: Int,
        phase: String,
        tMs: Long,
        fields: List<Pair<String, Any>>,
    ): String =
        buildString {
            append(TAG).append(" run=").append(run)
            append(" phase=").append(clean(phase))
            append(" t=").append(tMs)
            fields.forEach { (k, v) -> append(' ').append(clean(k)).append('=').append(clean(v.toString())) }
        }

    /** Keeps only [A-Za-z0-9_.-]: a URI or path cannot survive. */
    private fun clean(s: String): String = s.map { if (it in SAFE_CHARS) it else '_' }.joinToString("")

    private val SAFE_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '.', '-')

    /** Nearest-rank percentile over an ascending array; empty -> 0. */
    internal fun percentile(
        sorted: LongArray,
        p: Int,
    ): Long {
        if (sorted.isEmpty()) return 0L
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

/** Thread-safe duration collector: p50 / p95 / max. */
internal class DurationStats {
    private val values = ArrayList<Long>()

    fun add(ms: Long) {
        synchronized(values) { values.add(ms) }
    }

    fun count(): Int = synchronized(values) { values.size }

    fun summary(): Triple<Long, Long, Long> {
        val sorted = synchronized(values) { values.toLongArray() }.also { it.sort() }
        return Triple(ImportTimeline.percentile(sorted, 50), ImportTimeline.percentile(sorted, 95), sorted.lastOrNull() ?: 0L)
    }
}

/** Whitelist by authority -> (provider, volume). Nothing else from the URI is ever kept. */
internal fun providerKind(
    authority: String?,
    treeDocId: String?,
): Pair<String, String> {
    val provider =
        when (authority) {
            "com.android.externalstorage.documents" -> "externalstorage"
            "com.android.providers.downloads.documents" -> "downloads"
            "com.android.providers.media.documents" -> "media"
            else -> "other"
        }
    val volume =
        when {
            treeDocId == null -> "unknown"
            treeDocId == "primary" || treeDocId.startsWith("primary:") -> "primary"
            else -> "secondary"
        }
    return provider to volume
}
