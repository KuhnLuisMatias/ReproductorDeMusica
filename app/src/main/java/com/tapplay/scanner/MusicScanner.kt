package com.tapplay.scanner

import android.content.Context
import android.net.Uri
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
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            val entries = TreeFlatten.flatten(DocumentTreeFolder(tree))
            val ordered =
                ScanOrdering.groupOrdered(entries, { it.folderPath }, { it.file.name })
            onProgress(0, ordered.size)
            val cachedByUri =
                cache
                    ?.read(treeUri.toString())
                    ?.let(MetadataCacheCodec::decode)
                    ?.associateBy { it.uri }
                    .orEmpty()
            val done = AtomicInteger(0)
            val songs =
                coroutineScope {
                    ordered.map { entry ->
                        async {
                            extractionLimiter.withPermit {
                                val document = entry.file as DocumentTreeFolder
                                val documentUri = document.file.uri.toString()
                                val fileName = document.file.name.orEmpty()
                                val metadata =
                                    cachedByUri[documentUri]?.let {
                                        SongMetadata(it.title, it.artist, it.album, it.durationMs)
                                    } ?: MetadataExtractor.extract(context, document.file.uri, fileName)
                Song(
                    uri = documentUri,
                    path = documentUri,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    artBytes = null,
                    lastModified = cachedByUri[documentUri]?.lastModified,
                    addedAtMs = cachedByUri[documentUri]?.addedAtMs ?: System.currentTimeMillis(),
                ).also { onProgress(done.incrementAndGet(), ordered.size) }
                            }
                        }
                    }
                }.awaitAll()
            cache?.write(treeUri.toString(), MetadataCacheCodec.encode(songs.map { it.toCachedTrack() }))
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
        artBytes = null,
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
