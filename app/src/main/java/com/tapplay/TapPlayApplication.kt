package com.tapplay

import android.app.Application
import com.tapplay.player.PlaybackManager
import com.tapplay.scanner.MetadataCacheStore
import com.tapplay.scanner.SharedPreferencesMetadataCache
import com.tapplay.util.PrefsStore
import com.tapplay.util.SharedPreferencesStore

class TapPlayApplication : Application() {
    val prefs: PrefsStore by lazy { SharedPreferencesStore.from(this) }
    val metadataCache: MetadataCacheStore by lazy { SharedPreferencesMetadataCache(this) }
    val playbackManager: PlaybackManager by lazy { PlaybackManager(this, prefs, metadataCache) }
}
