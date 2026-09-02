package com.tapplay.scanner

import android.content.Context
import com.tapplay.util.SharedPreferencesStore
import org.json.JSONArray
import org.json.JSONObject

/** Persistence contract for the per-folder metadata cache (folderUri → JSON blob). */
interface MetadataCacheStore {
    fun read(folderUri: String): String?

    fun write(
        folderUri: String,
        json: String?,
    )
}

/** One cached track's tag metadata (no cover art — art is loaded on demand). */
data class CachedTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lastModified: Long? = null,
    val addedAtMs: Long? = null,
)

/** JSON codec for the metadata cache; corrupt entries decode to empty (forces re-extraction). */
object MetadataCacheCodec {
    fun encode(tracks: List<CachedTrack>): String = tracksToJson(tracks).toString()

    fun decode(json: String): List<CachedTrack> =
        try {
            tracksFromJson(JSONArray(json))
        } catch (e: Exception) {
            emptyList()
        }
}

internal fun tracksToJson(tracks: List<CachedTrack>): JSONArray =
    JSONArray().apply {
        tracks.forEach { track ->
            val entry =
                JSONObject()
                    .put(KEY_URI, track.uri)
                    .put(KEY_TITLE, track.title)
                    .put(KEY_ARTIST, track.artist)
                    .put(KEY_ALBUM, track.album)
                    .put(KEY_DURATION, track.durationMs)
            // JSONObject.put(key, null) REMOVES the key — only write non-null values.
            track.lastModified?.let { entry.put(KEY_LAST_MODIFIED, it) }
            track.addedAtMs?.let { entry.put(KEY_ADDED_AT, it) }
            put(entry)
        }
    }

internal fun tracksFromJson(array: JSONArray): List<CachedTrack> =
    (0 until array.length()).map { index ->
        val entry = array.getJSONObject(index)
        CachedTrack(
            uri = entry.getString(KEY_URI),
            title = entry.getString(KEY_TITLE),
            artist = entry.getString(KEY_ARTIST),
            album = entry.getString(KEY_ALBUM),
            durationMs = entry.getLong(KEY_DURATION),
            lastModified =
                if (entry.has(KEY_LAST_MODIFIED)) {
                    entry.getLong(KEY_LAST_MODIFIED)
                } else {
                    null
                },
            addedAtMs =
                if (entry.has(KEY_ADDED_AT)) {
                    entry.getLong(KEY_ADDED_AT)
                } else {
                    null
                },
        )
    }

private const val KEY_URI = "uri"
private const val KEY_TITLE = "title"
private const val KEY_ARTIST = "artist"
private const val KEY_ALBUM = "album"
private const val KEY_DURATION = "durationMs"
private const val KEY_LAST_MODIFIED = "lastModified"
private const val KEY_ADDED_AT = "addedAtMs"

/** Persisted cold-start snapshot: the full queue of one folder (design: change E). */
data class QueueSnapshot(
    val folderUri: String,
    val tracks: List<CachedTrack>,
)

/** JSON codec for [QueueSnapshot]; ANY decode failure → null (caller falls back to scan). */
object QueueSnapshotCodec {
    fun encode(snapshot: QueueSnapshot): String =
        JSONObject()
            .put(KEY_FOLDER_URI, snapshot.folderUri)
            .put(KEY_TRACKS, tracksToJson(snapshot.tracks))
            .toString()

    fun decode(json: String): QueueSnapshot? =
        try {
            val root = JSONObject(json)
            QueueSnapshot(
                folderUri = root.getString(KEY_FOLDER_URI),
                tracks = tracksFromJson(root.getJSONArray(KEY_TRACKS)),
            )
        } catch (e: Exception) {
            null
        }

    private const val KEY_FOLDER_URI = "folderUri"
    private const val KEY_TRACKS = "tracks"
}

/**
 * SharedPreferences-backed metadata cache (same `tapplay_prefs` file), one key
 * per folder URI. Traversal still runs every scan (SAF has no cheap change
 * detection), but cached file URIs skip `MediaMetadataRetriever` entirely.
 */
// ponytail: keyed by file URI only — retagged files show stale metadata until the
// app data is cleared; add per-file lastModified validation if tag editing matters.
class SharedPreferencesMetadataCache(
    context: Context,
) : MetadataCacheStore {
    private val prefs =
        context.getSharedPreferences(SharedPreferencesStore.PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(folderUri: String): String? = prefs.getString(key(folderUri), null)

    override fun write(
        folderUri: String,
        json: String?,
    ) {
        prefs.edit().putString(key(folderUri), json).apply()
    }

    private fun key(folderUri: String) = KEY_PREFIX + folderUri.hashCode()

    private companion object {
        const val KEY_PREFIX = "metadata_cache_"
    }
}
