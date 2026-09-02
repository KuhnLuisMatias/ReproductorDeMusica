package com.tapplay.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tapplay.model.Song
import com.tapplay.scanner.MetadataCacheCodec
import com.tapplay.scanner.MetadataCacheStore
import com.tapplay.scanner.MusicScanner
import com.tapplay.scanner.QueueSnapshot
import com.tapplay.scanner.QueueSnapshotCodec
import com.tapplay.scanner.toCachedTrack
import com.tapplay.scanner.toSong
import com.tapplay.util.PrefsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackManager(
    private val context: Context,
    private val prefs: PrefsStore,
    private val metadataCache: MetadataCacheStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queueManager = QueueManager()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var tickerJob: Job? = null
    private var positionPersistJob: Job? = null
    private var volumePersistJob: Job? = null

    // Filter state (spec: queue-filtered-playback R4) — manager-internal, NOT UI state (R16:
    // PlayerUiState unchanged, zero new StateFlow emissions). fullQueue is the single
    // authoritative full queue while filtered: captured at every engagement BEFORE
    // swapTimeline replaces currentQueue; refreshed by reorderQueue; reset by setQueue.
    private var activeQuery: String = ""
    private var fullQueue: List<Song>? = null

    private val _uiState =
        MutableStateFlow(
            PlayerUiState(
                queueSortMode = SortMode.from(prefs.queueSortMode),
                queueSortDescending = prefs.queueSortDescending,
            ),
        )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
                updateTicker(isPlaying)
                if (isPlaying) {
                    schedulePeriodicPositionPersist()
                } else {
                    cancelPositionPersist()
                    persistCurrentSong()
                    persistPosition()
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                updateState()
                persistCurrentSong()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState()
            }
        }

    fun connect() {
        if (controllerFuture != null) return
        val token =
            SessionToken(context, ComponentName(context, MusicPlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val mediaController =
                    try {
                        future.get()
                    } catch (e: Exception) {
                        null
                    }
                controller = mediaController
                mediaController?.addListener(playerListener)
                updateState()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun disconnect() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller?.removeListener(playerListener)
        controller = null
    }

    fun setQueue(songs: List<Song>) {
        activeQuery = "" // folder pick resets the filter scope (R11)
        fullQueue = null
        val mediaController = controller ?: return
        val sorted = SortMode.apply(songs, SortMode.from(prefs.queueSortMode), prefs.queueSortDescending)
        mediaController.setMediaItems(queueManager.buildMediaItems(sorted))
        persistQueueSnapshot(sorted)
        mediaController.repeatMode = Player.REPEAT_MODE_OFF
        mediaController.prepare()
        updateState()
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        updateState()
    }

    /**
     * Jumps to the queue row at [index] without forcing play state (spec:
     * queue-view "Queue rows") — if playing it keeps playing, if paused it
     * stays paused at the new track. While the search filter is active the
     * index is sheet-relative (subset of the current query): on a synced
     * timeline it seeks directly; while holding it engages — swaps the
     * timeline to the current query's subset at the tapped song (spec:
     * queue-filtered-playback R7). A tapped row always matches the query
     * (filterRows delegates to the same matcher), so the subset lookup is
     * always found.
     */
    fun jumpTo(index: Int) {
        val mediaController = controller ?: return
        if (activeQuery.isBlank()) {
            mediaController.seekTo(index, 0L)
            updateState()
            return
        }
        val base = fullQueue ?: queueManager.currentQueue
        val subset = base.filter { it.matchesQuery(activeQuery) }
        if (queueManager.currentQueue == subset) {
            mediaController.seekTo(index, 0L) // synced: index already subset-relative
            updateState()
            return
        }
        val tapped = queueManager.currentQueue.getOrNull(index) ?: return
        val target = indexOfSong(subset, tapped.path) // visible row ⇒ found; ?: 0 guard
        if (fullQueue == null) fullQueue = base
        swapTimeline(subset, target, 0L) // tapped track starts at 0; play/pause untouched
    }

    fun next() {
        if (activeQuery.isBlank()) {
            controller?.seekToNextMediaItem()
            return
        }
        navigateFiltered(forward = true)
    }

    fun previous() {
        if (activeQuery.isBlank()) {
            controller?.seekToPreviousMediaItem()
            return
        }
        navigateFiltered(forward = false)
    }

    /**
     * Filtered next/previous (spec: queue-filtered-playback R7). Synced
     * timeline → plain seekToNext/PreviousMediaItem (boundary = no-op seek;
     * natural end of the last match = ENDED, identical to today's
     * end-of-queue with REPEAT_MODE_OFF). Otherwise engages: empty subset is
     * a no-op (AC13); current song in the subset → re-sync swap keeping
     * position, then step ±1 (null at the boundary → no seek — the swap
     * itself self-heals the hold); current song out → first (forward) / last
     * (backward) match starts at 0 ms.
     */
    private fun navigateFiltered(forward: Boolean) {
        val mediaController = controller ?: return
        val base = fullQueue ?: queueManager.currentQueue
        val subset = base.filter { it.matchesQuery(activeQuery) }
        if (subset.isEmpty()) return
        if (queueManager.currentQueue == subset) {
            if (forward) mediaController.seekToNextMediaItem() else mediaController.seekToPreviousMediaItem()
            return // parity with today's next/previous: no updateState, listener covers transitions
        }
        if (fullQueue == null) fullQueue = base // capture BEFORE the swap replaces currentQueue (R4/D6)
        val currentPath = _uiState.value.currentSong?.path
        val indexInSubset = subset.indexOfFirst { it.path == currentPath }
        if (indexInSubset >= 0) {
            val positionMs = mediaController.currentPosition // MUST be read BEFORE setMediaItems (reads 0 after)
            swapTimeline(subset, indexInSubset, positionMs)
            val target = engagementIndex(subset, currentPath, forward)
            if (target != null) mediaController.seekTo(target, 0L)
        } else {
            val target = engagementIndex(subset, currentPath, forward) ?: return // unreachable for non-empty subset
            swapTimeline(subset, target, 0L)
        }
    }

    /**
     * Updates the active queue-sheet search filter (spec:
     * queue-filtered-playback R5/R15). Query edits never touch the controller
     * (AC17) — the single exception is the one-time blank-restore swap:
     * clearing the filter while a full-queue capture exists restores the
     * original full queue with the current song continuing at its position
     * (position read BEFORE setMediaItems, which reads 0 after).
     *
     * Hold ceiling: while filtered, a query edit that drops the playing song
     * from the current subset leaves the timeline untouched — audio continues
     * uninterrupted and the sheet browses the old subset re-filtered by the
     * new query (subset-of-subset; may show "Sin resultados" for songs that
     * exist in the full queue). Self-heals on the next engagement (tap /
     * next / prev / sort with the current song matching) or on clear.
     */
    fun onQueryChanged(query: String) {
        activeQuery = query
        if (query.isNotBlank()) return
        val full = fullQueue ?: return
        val mediaController = controller ?: return // no controller → nothing playing; keep capture for retry
        fullQueue = null
        val index = indexOfSong(full, _uiState.value.currentSong?.path)
        val positionMs = mediaController.currentPosition // MUST be read BEFORE setMediaItems (reads 0 after)
        swapTimeline(full, index, positionMs)
    }

    /**
     * Atomically replaces the controller timeline with [list] starting at
     * [startIndex] and [positionMs] (3-arg setMediaItems, Media3 1.5.1 Player
     * API). Every swap routes through queueManager.buildMediaItems so
     * queueManager.currentQueue keeps mirroring the timeline (existing
     * invariant). Callers MUST read positionMs BEFORE calling —
     * currentPosition reads 0 after setMediaItems (reorderQueue precedent).
     * NEVER writes the queue snapshot: persisted queues are always full (R10).
     */
    private fun swapTimeline(
        list: List<Song>,
        startIndex: Int,
        positionMs: Long,
    ) {
        val mediaController = controller ?: return
        mediaController.setMediaItems(queueManager.buildMediaItems(list), startIndex, positionMs)
        updateState()
    }

    fun changeVolumeBySteps(steps: Int) {
        if (steps == 0) return
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        scheduleVolumePersist()
    }

    /**
     * Restores the last session (design D7, spec "Paused restore"): rescan the
     * persisted folder, locate the saved song path, seek to the saved position
     * with the saved volume, and remain PAUSED until the user taps to start.
     */
    fun restoreLastSession() {
        scope.launch {
            val mediaController = awaitController() ?: return@launch
            val folderUri = prefs.lastFolderUri ?: return@launch
            val snapshot =
                prefs.queueSnapshot
                    ?.let(QueueSnapshotCodec::decode)
                    ?.takeIf { QueueSnapshotPolicy.shouldRestore(it, folderUri) }
            val songs =
                if (snapshot != null) {
                    snapshot.tracks.map { it.toSong() } // hit: no scanLibrary → zero indicator churn
                } else {
                    val scanned = scanLibrary(Uri.parse(folderUri))
                    if (scanned.isEmpty()) return@launch
                    scanned
                }
            val sorted =
                if (snapshot != null) {
                    songs // hit path: snapshot already holds the displayed order (sorted + manual drags)
                } else {
                    SortMode.apply(songs, SortMode.from(prefs.queueSortMode), prefs.queueSortDescending)
                }
            if (snapshot == null) persistQueueSnapshot(sorted) // scan path only; hit path = zero I/O
            val plan =
                RestoreResolver.resolve(sorted, prefs.currentSongPath, prefs.positionMs, prefs.volumeLevel)
            mediaController.setMediaItems(queueManager.buildMediaItems(sorted))
            mediaController.repeatMode = Player.REPEAT_MODE_OFF
            mediaController.prepare()
            mediaController.seekTo(plan.index, plan.positionMs)
            if (plan.volumeLevel != RestoreResolver.VOLUME_UNSET) setStreamVolume(plan.volumeLevel)
            updateState()
        }
    }

    /**
     * Reorders the live queue to [mode] keeping the current song playing at its
     * position (setMediaItems + seekTo; repeatMode untouched). Persists the mode
     * first; DATE_MODIFIED lazily fetches missing lastModified values once (one
     * bounded pass, merged in memory and persisted into the metadata cache).
     * No indicator during the fetch (see design D0.9).
     * While filtered, operates on the captured full queue and re-swaps the sorted
     * subset when the current song matches (self-heals holds); otherwise stays holding.
     */
    suspend fun reorderQueue(
        mode: SortMode,
        descending: Boolean,
    ) {
        val mediaController = controller ?: return
        if (queueManager.currentQueue.isEmpty()) return

        prefs.queueSortMode = mode.name
        prefs.queueSortDescending = descending

        val wasFiltered = fullQueue != null // NOT activeQuery: BROWSING re-sorts the full queue in place (T15, D2)
        val base = fullQueue ?: queueManager.currentQueue
        var songs = base
        if (mode == SortMode.DATE_MODIFIED && songs.any { it.lastModified == null }) {
            val missing = songs.filter { it.lastModified == null }.map { it.uri }
            val fetched = MusicScanner.fetchLastModified(context, missing)
            songs = songs.map { song -> fetched[song.uri]?.let { song.copy(lastModified = it) } ?: song }
            // Queue always corresponds to prefs.lastFolderUri (set at folder pick;
            // restore rescans it). Null → skip persist; in-memory sort still works.
            prefs.lastFolderUri?.let { folderUri ->
                metadataCache.write(folderUri, MetadataCacheCodec.encode(songs.map { it.toCachedTrack() }))
            }
        }

        val sorted = SortMode.apply(songs, mode, descending)
        fullQueue = if (wasFiltered) sorted else null
        persistQueueSnapshot(sorted) // always the FULL list (base was full or the captured full queue) — R10
        val currentPath = _uiState.value.currentSong?.path
        if (!wasFiltered) {
            val newIndex = sorted.indexOfFirst { it.path == currentPath }.takeIf { it >= 0 } ?: 0
            val positionMs = mediaController.currentPosition // MUST be read BEFORE setMediaItems (reads 0 after)
            mediaController.setMediaItems(queueManager.buildMediaItems(sorted))
            mediaController.seekTo(newIndex, positionMs)
        } else {
            val subset = sorted.filter { it.matchesQuery(activeQuery) }
            val indexInSubset = subset.indexOfFirst { it.path == currentPath } // sign matters: absence = hold (D4)
            if (indexInSubset >= 0) {
                val positionMs = mediaController.currentPosition // MUST be read BEFORE setMediaItems (reads 0 after)
                swapTimeline(subset, indexInSubset, positionMs) // seamless re-sort, self-heals holds (T16)
            }
            // else: stay holding — timeline (old subset) untouched, audio unaffected (T17/R15 ceiling)
        }
        _uiState.update { it.copy(queueSortMode = mode, queueSortDescending = descending) }
        updateState()
    }

    /**
     * Manual drag-reorder (design: change D). Non-suspend: moveMediaItem is a
     * synchronous non-blocking facade call on MediaController and the rest are
     * plain calls (same optimistic masking trust as reorderQueue). Moving the
     * playing item keeps it playing — Media3 shifts currentIndex to follow
     * it; explicit updateState() because no listener entry fires for a pure
     * reorder (no onTimelineChanged override, no transition event).
     */
    fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        if (activeQuery.isNotBlank()) return // filtered: subset indices ≠ timeline indices; drag already disabled in UI (QueueSheet L406)
        val mediaController = controller ?: return
        val queue = queueManager.currentQueue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        mediaController.moveMediaItem(from, to)
        queueManager.moveItem(from, to)
        persistQueueSnapshot(queueManager.currentQueue)
        updateState()
    }

    /**
     * Single scan path for both call sites (folder pick and cold-start
     * restore): reports per-song progress into the UI state. Cancellation
     * resets progress and rethrows; any other failure resets progress and
     * returns an empty list so callers no-op gracefully.
     */
    suspend fun scanLibrary(treeUri: Uri): List<Song> =
        try {
            _uiState.update {
                it.copy(scanPhase = ScanPhase.SCANNING_FOLDERS, scanLoaded = 0, scanTotal = 0)
            }
            val songs =
                MusicScanner.scan(context, treeUri, metadataCache) { done, total ->
                    _uiState.update {
                        it.copy(scanPhase = ScanPhase.EXTRACTING, scanLoaded = done, scanTotal = total)
                    }
                }
            _uiState.update { it.copy(scanPhase = ScanPhase.IDLE) }
            songs
        } catch (e: CancellationException) {
            _uiState.update { it.copy(scanPhase = ScanPhase.IDLE, scanLoaded = 0, scanTotal = 0) }
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(scanPhase = ScanPhase.IDLE, scanLoaded = 0, scanTotal = 0) }
            emptyList()
        }

    private suspend fun awaitController(): MediaController? {
        var waited = 0L
        while (controller == null && waited < CONTROLLER_WAIT_TIMEOUT_MS) {
            delay(CONTROLLER_POLL_MS)
            waited += CONTROLLER_POLL_MS
        }
        return controller
    }

    private fun setStreamVolume(level: Int) {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    }

    private fun scheduleVolumePersist() {
        volumePersistJob?.cancel()
        volumePersistJob =
            scope.launch {
                delay(VOLUME_PERSIST_DEBOUNCE_MS)
                prefs.volumeLevel = currentStreamVolume()
            }
    }

    private fun schedulePeriodicPositionPersist() {
        positionPersistJob?.cancel()
        positionPersistJob =
            scope.launch {
                while (isActive) {
                    delay(POSITION_PERSIST_INTERVAL_MS)
                    val mediaController = controller ?: break
                    prefs.positionMs = mediaController.currentPosition
                }
            }
    }

    private fun cancelPositionPersist() {
        positionPersistJob?.cancel()
        positionPersistJob = null
    }

    private fun persistCurrentSong() {
        _uiState.value.currentSong?.let { prefs.currentSongPath = it.path }
    }

    /** Persists the queue snapshot for instant cold-start restore (no-op without a folder). */
    private fun persistQueueSnapshot(songs: List<Song>) {
        val folderUri = prefs.lastFolderUri ?: return
        prefs.queueSnapshot =
            QueueSnapshotCodec.encode(QueueSnapshot(folderUri, songs.map { it.toCachedTrack() }))
    }

    private fun persistPosition() {
        prefs.positionMs = controller?.currentPosition ?: _uiState.value.positionMs
    }

    private fun currentStreamVolume(): Int {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun updateTicker(isPlaying: Boolean) {
        tickerJob?.cancel()
        if (!isPlaying) return
        tickerJob =
            scope.launch {
                while (isActive) {
                    _uiState.update { it.copy(positionMs = controller?.currentPosition ?: 0L) }
                    delay(POSITION_TICK_MS)
                }
            }
    }

    private fun updateState() {
        val mediaController = controller ?: return
        val index = mediaController.currentMediaItemIndex
        val song = queueManager.currentQueue.getOrNull(index)
        val duration = mediaController.duration
        _uiState.update {
            it.copy(
                isPlaying = mediaController.isPlaying,
                currentSong = song,
                durationMs = if (duration != C.TIME_UNSET) duration else 0L,
                positionMs = mediaController.currentPosition,
                queue = queueManager.currentQueue,
                currentIndex = index,
            )
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 500L
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        const val VOLUME_PERSIST_DEBOUNCE_MS = 500L
        const val CONTROLLER_POLL_MS = 50L
        const val CONTROLLER_WAIT_TIMEOUT_MS = 5_000L
    }
}
