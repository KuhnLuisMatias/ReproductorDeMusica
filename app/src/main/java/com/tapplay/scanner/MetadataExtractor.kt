package com.tapplay.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tapplay.util.FileNameUtils

/** Raw tag metadata resolved for a single audio file (text-first UI - cover art is never extracted). */
data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

/**
 * Pure fallback resolution for metadata tags (spec: metadata-extraction).
 * Fallback values come from the product spec (Documentación/Prompt.txt).
 */
object MetadataFallbacks {
    const val UNKNOWN_ARTIST = "Desconocido"
    const val NO_ALBUM = "Sin álbum"

    fun resolveTitle(
        tag: String?,
        fileName: String,
    ): String = normalize(tag) ?: FileNameUtils.titleFromFileName(fileName)

    fun resolveArtist(tag: String?): String = normalize(tag) ?: UNKNOWN_ARTIST

    fun resolveAlbum(tag: String?): String = normalize(tag) ?: NO_ALBUM

    private fun normalize(tag: String?): String? = tag?.takeIf { it.isNotBlank() }
}

/**
 * Extracts metadata from audio files via [MediaMetadataRetriever] using SAF
 * content URIs (design D5). Duration falls back to 0 so the player computes
 * it from the header once the item is prepared. Cover art is never extracted
 * anywhere in the app: the player UI is text-first and derives its per-song
 * color from the file name (ColorExtractor.argbFromFileName).
 */
object MetadataExtractor {
    fun extract(
        context: Context,
        uri: Uri,
        fileName: String,
    ): SongMetadata =
        try {
            extractUnsafe(context, uri, fileName)
        } catch (e: Exception) {
            SongMetadata(
                title = MetadataFallbacks.resolveTitle(null, fileName),
                artist = MetadataFallbacks.UNKNOWN_ARTIST,
                album = MetadataFallbacks.NO_ALBUM,
                durationMs = 0L,
            )
        }

    private fun extractUnsafe(
        context: Context,
        uri: Uri,
        fileName: String,
    ): SongMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            SongMetadata(
                title = MetadataFallbacks.resolveTitle(title, fileName),
                artist = MetadataFallbacks.resolveArtist(artist),
                album = MetadataFallbacks.resolveAlbum(album),
                durationMs = duration?.toLongOrNull() ?: 0L,
            )
        } finally {
            retriever.release()
        }
    }
}
