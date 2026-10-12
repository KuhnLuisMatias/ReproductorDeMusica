package com.tapplay.scanner

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tapplay.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recursively scans a SAF-picked folder tree for supported audio files and
 * builds the play queue (spec: folder-selection "Folder flow"). Traversal is a
 * DFS over all depths with a visited-URI cycle guard and a depth cap of 32.
 * Traversal runs in parallel across top-level subtrees (TreeFlatten); entries
 * arrive in unspecified order — groupOrdered below remains the single source
 * of queue order.
 * Order is folder-GROUPED via [ScanOrdering.groupOrdered] — root files first,
 * then subfolders alphabetically, alphabetical within each group — which is
 * the single source of truth for queue order (QueueManager and RestoreResolver
 * must stay consistent with it).
 *
 * Metadata extraction runs with bounded parallelism ([EXTRACTION_CONCURRENCY]
 * workers) and consults [cache] first: cached file URIs skip the
 * `MediaMetadataRetriever` round-trip entirely. Cover art is never extracted -
 * the player UI is text-first and derives its per-song color from the file name.
 */
object MusicScanner {
    private const val EXTRACTION_CONCURRENCY = 8

    private val extractionLimiter = Semaphore(EXTRACTION_CONCURRENCY)

    /**
     * Reports scan progress via [onProgress]: exactly one start emission
     * `(0, total)` right after ordering (even for an empty folder, where
     * total is 0) plus one `(done, total)` per song as extraction completes.
     * A null tree emits nothing. Done counts 1..total exactly once each, out
     * of order across [EXTRACTION_CONCURRENCY] workers.
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        cache: MetadataCacheStore? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Song> =
        withContext(Dispatchers.IO) {
            val startMs = SystemClock.elapsedRealtime()
            ImportTimeline.mark("scanStart")
            val listStats = DurationStats()
            val root = listedRoot(context, treeUri, listStats::add) ?: return@withContext emptyList()
            val entries = TreeFlatten.flatten(root)
            val listedMs = SystemClock.elapsedRealtime()
            val (listP50, _, listMax) = listStats.summary()
            ImportTimeline.mark("listDone", "queries" to listStats.count(), "listP50" to listP50, "listMax" to listMax)
            val ordered =
                ScanOrdering.groupOrdered(entries, { it.folderPath }, { it.file.name })
            val sortedMs = SystemClock.elapsedRealtime()
            ImportTimeline.mark("sortDone", "files" to ordered.size)
            onProgress(0, ordered.size)
            val cachedByUri =
                cache
                    ?.read(treeUri.toString())
                    ?.let(MetadataCacheCodec::decode)
                    ?.associateBy { it.uri }
                    .orEmpty()
            val done = AtomicInteger(0)
            val cacheHits = AtomicInteger(0)
            val extractions = AtomicInteger(0)
            val setDataSourceStats = DurationStats()
            val tagsStats = DurationStats()
            val waitStats = DurationStats()
            val thirds = List(3) { DurationStats() }
            val inFlight = AtomicInteger(0)
            val concMax = AtomicInteger(0)
            val songs =
                coroutineScope {
                    ordered.mapIndexed { index, entry ->
                        async {
                            val waitStartNs = System.nanoTime()
                            extractionLimiter.withPermit {
                                waitStats.add((System.nanoTime() - waitStartNs) / 1_000_000)
                                concMax.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                                val itemStartNs = System.nanoTime()
                                try {
                                    val document = entry.file
                                    val documentUri = document.uri
                                    val fileName = document.name
                                    val metadata =
                                        cachedByUri[documentUri]?.let {
                                            cacheHits.incrementAndGet()
                                            SongMetadata(it.title, it.artist, it.album, it.durationMs)
                                        } ?: run {
                                            extractions.incrementAndGet()
                                            MetadataExtractor.extract(context, Uri.parse(documentUri), fileName) { dsMs, tagMs ->
                                                setDataSourceStats.add(dsMs)
                                                tagsStats.add(tagMs)
                                            }
                                        }
                                    Song(
                                        uri = documentUri,
                                        path = documentUri,
                                        title = metadata.title,
                                        artist = metadata.artist,
                                        album = metadata.album,
                                        durationMs = metadata.durationMs,
                                        lastModified = cachedByUri[documentUri]?.lastModified,
                                        addedAtMs = cachedByUri[documentUri]?.addedAtMs ?: System.currentTimeMillis(),
                                    ).also { onProgress(done.incrementAndGet(), ordered.size) }
                                } finally {
                                    inFlight.decrementAndGet()
                                    thirds[index * 3 / ordered.size].add((System.nanoTime() - itemStartNs) / 1_000_000)
                                }
                            }
                        }
                    }
                }.awaitAll()
            val extractedMs = SystemClock.elapsedRealtime()
            val (dsP50, dsP95, dsMax) = setDataSourceStats.summary()
            val (tagP50, tagP95, tagMax) = tagsStats.summary()
            val (waitP50, waitP95, _) = waitStats.summary()
            ImportTimeline.mark(
                "extractDone",
                "files" to songs.size,
                "hits" to cacheHits.get(),
                "extractions" to extractions.get(),
                "conc_max" to concMax.get(),
                "dsP50" to dsP50,
                "dsP95" to dsP95,
                "dsMax" to dsMax,
                "tagsP50" to tagP50,
                "tagsP95" to tagP95,
                "tagsMax" to tagMax,
                "waitP50" to waitP50,
                "waitP95" to waitP95,
                "third1P50" to thirds[0].summary().first,
                "third2P50" to thirds[1].summary().first,
                "third3P50" to thirds[2].summary().first,
            )
            cache?.write(treeUri.toString(), MetadataCacheCodec.encode(songs.map { it.toCachedTrack() }))
            ImportTimeline.mark("cacheWritten", "ms" to SystemClock.elapsedRealtime() - extractedMs)
            val metrics =
                ScanMetrics(
                    files = songs.size,
                    startMs = startMs,
                    listedMs = listedMs,
                    sortedMs = sortedMs,
                    extractedMs = SystemClock.elapsedRealtime(),
                    cacheHits = cacheHits.get(),
                    extractions = extractions.get(),
                )
            Log.d("ScanMetrics", metrics.format())
            songs
        }

    /**
     * One bounded-parallel pass fetching `lastModified` for the given document
     * URIs (lazy DATE_MODIFIED support, paid once per file ever). Values ≤ 0 are
     * MISSING (excluded — 0 conflates with epoch); query failures also map to
     * missing so a revoked permission cannot crash a reorder.
     */
    suspend fun fetchLastModified(
        context: Context,
        uris: List<String>,
    ): Map<String, Long> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                uris
                    .map { uri ->
                        async {
                            extractionLimiter.withPermit {
                                val lastModified =
                                    runCatching {
                                        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.lastModified() ?: 0L
                                    }.getOrDefault(0L)
                                if (lastModified > 0L) uri to lastModified else null
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
                    .toMap()
            }
        }
}

internal fun Song.toCachedTrack() =
    CachedTrack(
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        lastModified = lastModified,
        addedAtMs = addedAtMs,
    )

/** Inverse mapping for snapshot restore: preserves the scanner's path==uri invariant; art is never extracted. */
internal fun CachedTrack.toSong(): Song =
    Song(
        uri = uri,
        path = uri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        lastModified = lastModified,
        addedAtMs = addedAtMs,
    )

/** [TreeFolder] adapter over a SAF [DocumentFile] node; children resolve lazily. */
private class DocumentTreeFolder(
    val file: DocumentFile,
) : TreeFolder {
    override val name: String
        get() = file.name.orEmpty()

    override val isFile: Boolean
        get() = file.isFile

    override val uri: String
        get() = file.uri.toString()

    override val children: List<TreeFolder> by lazy {
        file.listFiles().map { DocumentTreeFolder(it) }
    }
}

/**
 * Root of the listed tree: every folder is read with ONE provider query and
 * its children expose already-read name/isFile/uri. Null if the tree URI is invalid.
 */
private fun listedRoot(
    context: Context,
    treeUri: Uri,
    onQueryMs: ((Long) -> Unit)? = null,
): TreeFolder? {
    val rootDocId =
        runCatching {
            val docId =
                if (DocumentsContract.isDocumentUri(context, treeUri)) {
                    DocumentsContract.getDocumentId(treeUri)
                } else {
                    null
                }
            rootDocumentIdFor(DocumentsContract.getTreeDocumentId(treeUri), docId)
        }.getOrNull() ?: return null
    val lister = ContentResolverChildLister(context.contentResolver, treeUri, onQueryMs)

    fun folderOf(entry: ChildEntry): TreeFolder {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
        return ListedTreeFolder(
            entry = entry,
            uri = uri.toString(),
            lister = lister,
            childFactory = { folderOf(it) },
            fallbackChildren = {
                DocumentFile.fromTreeUri(context, uri)?.listFiles().orEmpty().map { DocumentTreeFolder(it) }
            },
            isFileFallback = { DocumentFile.fromTreeUri(context, uri)?.isFile ?: false },
        )
    }
    return folderOf(ChildEntry(rootDocId, "", DIRECTORY_MIME, 0L, 0L))
}
