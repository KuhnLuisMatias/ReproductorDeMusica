package com.tapplay.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence contract for the session schema (design: SharedPreferences
 * `tapplay_prefs`, spec: playback-state-persistence "Keys saved").
 */
interface PrefsStore {
    var lastFolderUri: String?
    var currentSongPath: String?
    var positionMs: Long
    var volumeLevel: Int
    var notificationsRequested: Boolean
    var queueSortMode: String
    var queueSortDescending: Boolean
    var queueSnapshot: String?

    /** Song uri to scroll the queue sheet back to on reopen (design C2), or null if none saved yet. */
    var queueScrollAnchorUri: String?

    /** Most recent search queries, newest first, capped at 5 (design C3). */
    var recentSearches: List<String>
}

class SharedPreferencesStore(
    private val prefs: SharedPreferences,
) : PrefsStore {
    override var lastFolderUri: String?
        get() = prefs.getString(KEY_LAST_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_LAST_FOLDER_URI, value).apply()

    override var currentSongPath: String?
        get() = prefs.getString(KEY_CURRENT_SONG_PATH, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_SONG_PATH, value).apply()

    override var positionMs: Long
        get() = prefs.getLong(KEY_POSITION_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_POSITION_MS, value).apply()

    override var volumeLevel: Int
        get() = prefs.getInt(KEY_VOLUME_LEVEL, 0)
        set(value) = prefs.edit().putInt(KEY_VOLUME_LEVEL, value).apply()

    override var notificationsRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_REQUESTED, value).apply()

    override var queueSortMode: String
        get() = prefs.getString(KEY_QUEUE_SORT_MODE, "ADDED") ?: "ADDED"
        set(value) = prefs.edit().putString(KEY_QUEUE_SORT_MODE, value).apply()

    override var queueSortDescending: Boolean
        get() = prefs.getBoolean(KEY_QUEUE_SORT_DESCENDING, true)
        set(value) = prefs.edit().putBoolean(KEY_QUEUE_SORT_DESCENDING, value).apply()

    override var queueSnapshot: String?
        get() = prefs.getString(KEY_QUEUE_SNAPSHOT, null)
        set(value) = prefs.edit().putString(KEY_QUEUE_SNAPSHOT, value).apply()

    override var queueScrollAnchorUri: String?
        get() = prefs.getString(KEY_QUEUE_SCROLL_ANCHOR_URI, null)
        set(value) = prefs.edit().putString(KEY_QUEUE_SCROLL_ANCHOR_URI, value).apply()

    override var recentSearches: List<String>
        get() =
            prefs.getString(KEY_RECENT_SEARCHES, null)
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        set(value) = prefs.edit().putString(KEY_RECENT_SEARCHES, value.joinToString("\n")).apply()

    companion object {
        const val PREFS_NAME = "tapplay_prefs"
        const val KEY_LAST_FOLDER_URI = "last_folder_uri"
        const val KEY_CURRENT_SONG_PATH = "current_song_path"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_VOLUME_LEVEL = "volume_level"
        const val KEY_NOTIFICATIONS_REQUESTED = "notifications_requested"
        const val KEY_QUEUE_SORT_MODE = "queue_sort_mode"
        const val KEY_QUEUE_SORT_DESCENDING = "queue_sort_descending"
        const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
        const val KEY_QUEUE_SCROLL_ANCHOR_URI = "queue_scroll_anchor_uri"
        const val KEY_RECENT_SEARCHES = "recent_searches"

        fun from(context: Context): SharedPreferencesStore =
            SharedPreferencesStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
