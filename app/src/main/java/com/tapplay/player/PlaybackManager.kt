package com.tapplay.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    private var reorderJob: Job? = null

    // Last sort actually applied: the rollback target when an optimistic reorder is aborted.
    private var appliedSortMode: SortMode = SortMode.from(prefs.queueSortMode)
    private var appliedSortDescending: Boolean = prefs.queueSortDescending

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

    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Separate from PlayerUiState (same rationale as filter state above): the volume
    // overlay is the only consumer and updates on every gesture step, so it stays
    // out of the copy-heavy UI state to avoid unrelated recompositions.
    private val _volumePercent = MutableStateFlow(volumePercentFor(currentStreamVolume()))
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    // Kept out of PlayerUiState on purpose: it ticks twice a second, and inside the shared
    // state it recomposed the whole player screen (queue sheet included) on every tick.
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

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
        val mediaController = controller ?: return
        val willPlay = !mediaController.isPlaying
        if (willPlay) mediaController.play() else mediaController.pause()
        // Optimistic: onIsPlayingChanged confirms this later, but callers (e.g. the
        // play/pause transient overlay) read uiState synchronously right after this
        // call — without this, they'd render the PRE-toggle icon for one frame.
        _uiState.update { it.copy(isPlaying = willPlay) }
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
            mediaController.play() // tapping a row starts playback, even if paused
            updateState()
            return
        }
        val base = fullQueue ?: queueManager.currentQueue
        val subset = base.filter { it.matchesQuery(activeQuery) }
        if (queueManager.currentQueue == subset) {
            mediaController.seekTo(index, 0L) // synced: index already subset-relative
            mediaController.play()
            updateState()
            return
        }
        val tapped = queueManager.currentQueue.getOrNull(index) ?: return
        val target = indexOfSong(subset, tapped.path) // visible row ⇒ found; ?: 0 guard
        if (fullQueue == null) fullQueue = base
        swapTimeline(subset, target, 0L) // tapped track starts at 0; swapTimeline calls updateState()
        mediaController.play()
        updateState() // re-sync isPlaying: swapTimeline's updateState() ran before play() above
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
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        _volumePercent.value = volumePercentFor(currentStreamVolume())
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
     * Reorders the live queue to [mode] keeping the current song playing at
     * its position, without re-preparing it (no dip). Sorting and MediaItem
     * building run on Dispatchers.Default; the timeline is then rewritten on
     * Main in two replaceMediaItems calls around the current item (never
     * replaced) and the queue manager adopts the new list once. The sort
     * mode/direction are published optimistically at the start (the arrow
     * flips at once) and rolled back if the reorder is aborted. Only one
     * reorder is in flight: a new request cancels the previous one (last
     * tap wins). A result computed against a queue that changed meanwhile
     * (drag/remove) is discarded and recomputed once.
     * DATE_MODIFIED lazily fetches missing lastModified values once (one
     * bounded pass, merged in memory and persisted into the metadata cache).
     * No indicator during the fetch (design D0.9).
     * While filtered, operates on the captured full queue and re-swaps the
     * sorted subset via swapTimeline when the current song matches (self-heals
     * holds; re-prepare there is a pre-existing ceiling); otherwise stays
     * holding.
     */
    suspend fun reorderQueue(
        mode: SortMode,
        descending: Boolean,
    ) {
        if (controller == null || queueManager.currentQueue.isEmpty()) return
        reorderJob?.cancel()
        val job = scope.launch { runReorder(mode, descending) }
        reorderJob = job
        job.join()
    }

    private suspend fun runReorder(
        mode: SortMode,
        descending: Boolean,
    ) {
        val tTotal = SystemClock.elapsedRealtime()
        prefs.queueSortMode = mode.name
        prefs.queueSortDescending = descending
        _uiState.update { it.copy(queueSortMode = mode, queueSortDescending = descending) }
        try {
            // applied* are set inside applyReorder right after adopting (before any suspension)
            if (!applyReorder(mode, descending, tTotal)) rollbackSort()
        } catch (e: CancellationException) {
            throw e // superseded by a newer request, which owns the sort state now
        } catch (e: Exception) {
            Log.e("QueueReorder", "reorder failed: ${e.javaClass.simpleName}")
            rollbackSort()
        }
    }

    private fun rollbackSort() {
        prefs.queueSortMode = appliedSortMode.name
        prefs.queueSortDescending = appliedSortDescending
        _uiState.update { it.copy(queueSortMode = appliedSortMode, queueSortDescending = appliedSortDescending) }
    }

    /** True when the new order was applied (or intentionally held); false = aborted, nothing touched. */
    private suspend fun applyReorder(
        mode: SortMode,
        descending: Boolean,
        tTotal: Long,
    ): Boolean {
        for (attempt in 1..MAX_REORDER_ATTEMPTS) {
            val mediaController = controller ?: return false
            val wasFiltered = fullQueue != null // NOT activeQuery: BROWSING re-sorts the full queue in place (T15, D2)
            val queueRef = queueManager.currentQueue
            val fullRef = fullQueue
            val queryAtStart = activeQuery
            val base = fullRef ?: queueRef
            if (base.isEmpty()) return false
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

            val tSort = SystemClock.elapsedRealtime()
            val (sorted, items) =
                withContext(Dispatchers.Default) {
                    val ordered = SortMode.apply(songs, mode, descending)
                    ordered to if (wasFiltered) emptyList() else toMediaItems(ordered)
                }
            val sortMs = SystemClock.elapsedRealtime() - tSort

            // Back on Main with no suspension until the end of the apply: atomic.
            val stale =
                isStaleReorder(
                    capturedController = mediaController,
                    currentController = controller,
                    queueRef = queueRef,
                    currentQueue = queueManager.currentQueue,
                    fullRef = fullRef,
                    currentFull = fullQueue,
                    queryAtStart = queryAtStart,
                    activeQuery = activeQuery,
                )
            if (stale) {
                Log.w("QueueReorder", "controller or queue changed while sorting, discarded attempt=$attempt")
                continue
            }
            val tApply = SystemClock.elapsedRealtime()
            if (!wasFiltered) {
                if (!applyAroundCurrent(mediaController, sorted, items)) {
                    Log.w("QueueReorder", "current song not found in the queue, aborted")
                    return false
                }
            } else {
                fullQueue = sorted
                val currentPath = _uiState.value.currentSong?.path
                val subset = sorted.filter { it.matchesQuery(activeQuery) }
                val indexInSubset = subset.indexOfFirst { it.path == currentPath } // sign matters: absence = hold (D4)
                if (indexInSubset >= 0) {
                    val positionMs = mediaController.currentPosition // MUST be read BEFORE setMediaItems (reads 0 after)
                    swapTimeline(subset, indexInSubset, positionMs) // seamless re-sort, self-heals holds (T16)
                }
                // else: stay holding — timeline (old subset) untouched, audio unaffected (T17/R15 ceiling)
            }
            updateState()
            // Right after adopting, before any suspension: a cancel during persist must not
            // leave the arrow/prefs out of step with the order really applied.
            appliedSortMode = mode
            appliedSortDescending = descending
            val applyMs = SystemClock.elapsedRealtime() - tApply

            val tPersist = SystemClock.elapsedRealtime()
            // always the FULL list (base was full or the captured full queue) — R10; encoded off Main,
            // written back on Main only if no newer persist (remove/move/setQueue) superseded it.
            val folderUri = prefs.lastFolderUri
            if (folderUri != null) {
                val encoded =
                    withContext(Dispatchers.Default) { encodeQueueSnapshot(folderUri, sorted) }
                val live = fullQueue ?: queueManager.currentQueue
                if (shouldWriteSnapshot(live, sorted) && prefs.lastFolderUri == folderUri) {
                    prefs.queueSnapshot = encoded
                } else {
                    Log.w("QueueReorder", "snapshot write discarded, queue changed while encoding")
                }
            }
            Log.d(
                "QueueReorder",
                "reorder n=${sorted.size} sortMs=$sortMs applyMs=$applyMs " +
                    "persistMs=${SystemClock.elapsedRealtime() - tPersist} " +
                    "totalMs=${SystemClock.elapsedRealtime() - tTotal}",
            )
            return true
        }
        Log.w("QueueReorder", "queue kept changing, gave up after $MAX_REORDER_ATTEMPTS attempts")
        return false
    }

    /**
     * Rewrites the timeline to [sorted] with two replaceMediaItems calls that
     * leave the playing item untouched (never re-prepared), then adopts the
     * list once. False (nothing touched) when the playing song is not in it.
     */
    private fun applyAroundCurrent(
        mediaController: MediaController,
        sorted: List<Song>,
        items: List<MediaItem>,
    ): Boolean {
        val curIdx = mediaController.currentMediaItemIndex
        val curUri = queueManager.currentQueue.getOrNull(curIdx)?.uri ?: return false
        val (before, after) = splitAroundCurrent(sorted.map { it.uri }, curUri) ?: return false
        val count = mediaController.mediaItemCount
        if (count > curIdx + 1 || !after.isEmpty()) {
            mediaController.replaceMediaItems(curIdx + 1, count, items.slice(after))
        }
        if (curIdx > 0 || !before.isEmpty()) {
            mediaController.replaceMediaItems(0, curIdx, items.slice(before))
        }
        queueManager.adopt(sorted)
        return true
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
     * Removes an item at [index] from the media controller and queue manager.
     */
    fun removeFromQueue(index: Int) {
        val mediaController = controller ?: return
        val queue = queueManager.currentQueue
        if (index !in queue.indices) return
        mediaController.removeMediaItem(index)
        queueManager.removeItem(index)
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
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        _volumePercent.value = volumePercentFor(level)
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
        prefs.queueSnapshot = encodeQueueSnapshot(folderUri, songs)
    }

    private fun encodeQueueSnapshot(
        folderUri: String,
        songs: List<Song>,
    ): String = QueueSnapshotCodec.encode(QueueSnapshot(folderUri, songs.map { it.toCachedTrack() }))

    private fun persistPosition() {
        prefs.positionMs = controller?.currentPosition ?: _positionMs.value
    }

    private fun currentStreamVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun volumePercentFor(level: Int): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max == 0) 0 else ((level * 100f) / max).roundToInt().coerceIn(0, 100)
    }

    private fun updateTicker(isPlaying: Boolean) {
        tickerJob?.cancel()
        if (!isPlaying) return
        tickerJob =
            scope.launch {
                while (isActive) {
                    _positionMs.value = controller?.currentPosition ?: 0L
                    delay(POSITION_TICK_MS)
                }
            }
    }

    private fun updateState() {
        val mediaController = controller ?: return
        val index = mediaController.currentMediaItemIndex
        val song = queueManager.currentQueue.getOrNull(index)
        val duration = mediaController.duration
        _positionMs.value = mediaController.currentPosition
        _uiState.update {
            it.copy(
                isPlaying = mediaController.isPlaying,
                currentSong = song,
                durationMs = if (duration != C.TIME_UNSET) duration else 0L,
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
        const val MAX_REORDER_ATTEMPTS = 2
    }
}
