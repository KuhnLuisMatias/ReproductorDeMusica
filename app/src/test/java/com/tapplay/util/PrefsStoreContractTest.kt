package com.tapplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the persisted session schema (design: SharedPreferences
 * `tapplay_prefs`). Runs against an in-memory fake mirroring the
 * [SharedPreferencesStore] semantics so the schema round-trip is testable on
 * the JVM without Android framework bindings.
 */
class PrefsStoreContractTest {
    private class InMemoryPrefsStore : PrefsStore {
        private val strings = mutableMapOf<String, String?>()
        private val longs = mutableMapOf<String, Long>()
        private val ints = mutableMapOf<String, Int>()
        private val booleans = mutableMapOf<String, Boolean>()

        override var lastFolderUri: String?
            get() = strings[SharedPreferencesStore.KEY_LAST_FOLDER_URI]
            set(value) {
                strings[SharedPreferencesStore.KEY_LAST_FOLDER_URI] = value
            }

        override var currentSongPath: String?
            get() = strings[SharedPreferencesStore.KEY_CURRENT_SONG_PATH]
            set(value) {
                strings[SharedPreferencesStore.KEY_CURRENT_SONG_PATH] = value
            }

        override var positionMs: Long
            get() = longs[SharedPreferencesStore.KEY_POSITION_MS] ?: 0L
            set(value) {
                longs[SharedPreferencesStore.KEY_POSITION_MS] = value
            }

        override var volumeLevel: Int
            get() = ints[SharedPreferencesStore.KEY_VOLUME_LEVEL] ?: 0
            set(value) {
                ints[SharedPreferencesStore.KEY_VOLUME_LEVEL] = value
            }

        override var notificationsRequested: Boolean
            get() = booleans[SharedPreferencesStore.KEY_NOTIFICATIONS_REQUESTED] ?: false
            set(value) {
                booleans[SharedPreferencesStore.KEY_NOTIFICATIONS_REQUESTED] = value
            }

        override var queueSortMode: String
            get() = strings[SharedPreferencesStore.KEY_QUEUE_SORT_MODE] ?: "ADDED"
            set(value) {
                strings[SharedPreferencesStore.KEY_QUEUE_SORT_MODE] = value
            }

        override var queueSortDescending: Boolean
            get() = booleans[SharedPreferencesStore.KEY_QUEUE_SORT_DESCENDING] ?: true
            set(value) {
                booleans[SharedPreferencesStore.KEY_QUEUE_SORT_DESCENDING] = value
            }

        override var queueSnapshot: String?
            get() = strings[SharedPreferencesStore.KEY_QUEUE_SNAPSHOT]
            set(value) {
                strings[SharedPreferencesStore.KEY_QUEUE_SNAPSHOT] = value
            }
    }

    private lateinit var prefs: PrefsStore

    @Before
    fun setUp() {
        prefs = InMemoryPrefsStore()
    }

    @Test
    fun `schema keys match the documented design names`() {
        assertEquals("tapplay_prefs", SharedPreferencesStore.PREFS_NAME)
        assertEquals("last_folder_uri", SharedPreferencesStore.KEY_LAST_FOLDER_URI)
        assertEquals("current_song_path", SharedPreferencesStore.KEY_CURRENT_SONG_PATH)
        assertEquals("position_ms", SharedPreferencesStore.KEY_POSITION_MS)
        assertEquals("volume_level", SharedPreferencesStore.KEY_VOLUME_LEVEL)
        assertEquals("notifications_requested", SharedPreferencesStore.KEY_NOTIFICATIONS_REQUESTED)
        assertEquals("queue_sort_mode", SharedPreferencesStore.KEY_QUEUE_SORT_MODE)
        assertEquals("queue_sort_descending", SharedPreferencesStore.KEY_QUEUE_SORT_DESCENDING)
        assertEquals("queue_snapshot", SharedPreferencesStore.KEY_QUEUE_SNAPSHOT)
    }

    @Test
    fun `defaults are empty session state`() {
        assertNull(prefs.lastFolderUri)
        assertNull(prefs.currentSongPath)
        assertEquals(0L, prefs.positionMs)
        assertEquals(0, prefs.volumeLevel)
        assertFalse(prefs.notificationsRequested)
        assertEquals("ADDED", prefs.queueSortMode)
        assertTrue(prefs.queueSortDescending)
        assertNull(prefs.queueSnapshot)
    }

    @Test
    fun `all five session keys round trip`() {
        prefs.lastFolderUri = "content://com.android.externalstorage/tree/primary%3AMusic"
        prefs.currentSongPath = "content://com.android.providers.downloads/document/7"
        prefs.positionMs = 42_000L
        prefs.volumeLevel = 9
        prefs.queueSortMode = "ARTIST"
        prefs.queueSnapshot = "{\"folderUri\":\"content://x\",\"tracks\":[]}"

        assertEquals("content://com.android.externalstorage/tree/primary%3AMusic", prefs.lastFolderUri)
        assertEquals("content://com.android.providers.downloads/document/7", prefs.currentSongPath)
        assertEquals(42_000L, prefs.positionMs)
        assertEquals(9, prefs.volumeLevel)
        assertEquals("ARTIST", prefs.queueSortMode)
        assertEquals("{\"folderUri\":\"content://x\",\"tracks\":[]}", prefs.queueSnapshot)
    }

    @Test
    fun `folder uri survives overwrite`() {
        prefs.lastFolderUri = "content://tree/old"
        prefs.lastFolderUri = "content://tree/new"
        assertEquals("content://tree/new", prefs.lastFolderUri)
    }

    @Test
    fun `folder uri can be cleared to null`() {
        prefs.lastFolderUri = "content://tree/old"
        prefs.lastFolderUri = null
        assertNull(prefs.lastFolderUri)
    }

    @Test
    fun `volume boundaries 0 and 15 round trip`() {
        prefs.volumeLevel = 0
        assertEquals(0, prefs.volumeLevel)
        prefs.volumeLevel = 15
        assertEquals(15, prefs.volumeLevel)
    }

    @Test
    fun `position accepts long durations`() {
        prefs.positionMs = Long.MAX_VALUE / 2
        assertEquals(Long.MAX_VALUE / 2, prefs.positionMs)
    }

    @Test
    fun `notifications requested flag round trips`() {
        prefs.notificationsRequested = true
        assertTrue(prefs.notificationsRequested)
        prefs.notificationsRequested = false
        assertFalse(prefs.notificationsRequested)
    }

    @Test
    fun `queue sort descending flag round trips`() {
        prefs.queueSortDescending = false
        assertFalse(prefs.queueSortDescending)
        prefs.queueSortDescending = true
        assertTrue(prefs.queueSortDescending)
    }
}
