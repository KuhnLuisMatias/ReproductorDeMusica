package com.tapplay.scanner

import com.tapplay.util.FileNameUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pure DFS flattening of a [TreeFolder] hierarchy (spec: folder-selection
 * recursive scan). Guards:
 * - visited-URI set: each document URI is visited at most once (cycle safety);
 * - depth cap of [MAX_DEPTH] levels: traversal stops without crash or hang;
 * - unsupported audio extensions are filtered out.
 *
 * Parallel traversal: one worker per TOP-LEVEL child, bounded by
 * [TRAVERSAL_CONCURRENCY]; each worker recurses serially inside its subtree.
 * Entry order is unspecified (parallel traversal); ScanOrdering owns final order.
 * Ceiling: gain scales with the number of top-level subtrees — a flat folder or
 * a single-chain tree gains nothing.
 */
object TreeFlatten {
    const val MAX_DEPTH = 32

    private const val TRAVERSAL_CONCURRENCY = 4

    // ponytail: object-level limiter shared across flatten calls — overlapping
    // scans (restore + folder pick) jointly stay at 4 concurrent subtree walks.
    private val traversalLimiter = Semaphore(TRAVERSAL_CONCURRENCY)

    /** A supported audio file found at [folderPath] ("" for root-level files). */
    data class Entry(
        val folderPath: String,
        val file: TreeFolder,
    )

    suspend fun flatten(root: TreeFolder): List<Entry> {
        val visited = ConcurrentHashMap.newKeySet<String>()
        val files = ConcurrentLinkedQueue<Entry>()
        coroutineScope {
            for (child in root.children) { // forces the single root listFiles round-trip
                async {
                    traversalLimiter.withPermit {
                        visitChild(child, parentPath = "", parentDepth = 0, visited = visited, files = files)
                    }
                }
            }
        }
        return files.toList()
    }

    /** One iteration of [visitFolder]'s child loop — folderPath building and
     *  the depth cap are byte-for-byte the serial semantics, now shared by the
     *  root-level workers and the recursion. */
    private fun visitChild(
        child: TreeFolder,
        parentPath: String,
        parentDepth: Int,
        visited: MutableSet<String>,
        files: MutableCollection<Entry>,
    ) {
        val childPath = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
        if (!visited.add(child.uri)) return
        if (child.isFile) {
            if (FileNameUtils.isSupportedAudio(child.name)) {
                files += Entry(folderPath = parentPath, file = child)
            }
        } else if (parentDepth + 1 < MAX_DEPTH) {
            visitFolder(child, childPath, parentDepth + 1, visited, files)
        }
    }

    private fun visitFolder(
        folder: TreeFolder,
        folderPath: String,
        depth: Int,
        visited: MutableSet<String>,
        files: MutableCollection<Entry>,
    ) {
        for (child in folder.children) {
            visitChild(child, folderPath, depth, visited, files)
        }
    }
}
