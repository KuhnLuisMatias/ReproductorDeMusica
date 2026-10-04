package com.tapplay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapplay.TapPlayApplication
import com.tapplay.player.PlaybackManager
import com.tapplay.player.ScanPhase
import com.tapplay.ui.components.PlayPauseTransientOverlay
import com.tapplay.ui.components.QueueSheetBackground
import com.tapplay.ui.components.QueueSheetContent
import com.tapplay.ui.components.SongInfo
import com.tapplay.ui.components.VolumeIndicatorOverlay
import com.tapplay.ui.components.backdropEffect
import com.tapplay.ui.components.formatDuration
import com.tapplay.ui.components.pushRecentSearch
import com.tapplay.ui.components.queueBackdrop
import com.tapplay.ui.components.queueRows
import com.tapplay.util.GestureAction
import com.tapplay.util.GestureHandler
import com.tapplay.util.GestureZone
import com.tapplay.util.SwipeDirection
import com.tapplay.util.passiveLongPress
import com.tapplay.util.tapPlayGestures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

private const val SEEK_STEP_MS = 10_000L
private const val DEFAULT_ACCENT = 0xFF8E8E93

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val playbackManager =
        remember {
            (context.applicationContext as TapPlayApplication).playbackManager
        }
    val prefs = remember { (context.applicationContext as TapPlayApplication).prefs }
    var scrollAnchorUri by remember { mutableStateOf(prefs.queueScrollAnchorUri) }
    var recentSearches by remember { mutableStateOf(prefs.recentSearches) }
    val state by playbackManager.uiState.collectAsStateWithLifecycle()
    val volumePercent by playbackManager.volumePercent.collectAsStateWithLifecycle()
    val song = state.currentSong
    val scope = rememberCoroutineScope()
    val folderPickerFlow =
        rememberFolderPickerFlow(
            onFolderPicked = { uri ->
                handleFolderPicked(context, playbackManager, scope, uri)
            },
        )
    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        requestPostNotificationsIfNeeded(context) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tapTriggerCount by remember { mutableStateOf(0L) }
    var isAdjustingVolume by remember { mutableStateOf(false) }

    val songInfoBlur by animateDpAsState(
        targetValue = if (isAdjustingVolume) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "songInfoBlur",
    )

    val accent = Color(DEFAULT_ACCENT)

    // skipHalfExpanded: the sheet is capped at 100% of screen height (see maxSheetHeight
    // in QueueSheetContent), which is tall enough that M2 would otherwise offer a
    // HalfExpanded stop on the way up — show() should jump straight to full height.
    val sheetState =
        rememberModalBottomSheetState(
            initialValue = ModalBottomSheetValue.Hidden,
            skipHalfExpanded = true,
        )
    BackHandler(enabled = sheetState.isVisible) {
        scope.launch { sheetState.hide() }
    }

    // B2: backdrop amount tracks the sheet's target (not just isVisible) so the
    // blur/dim animates in step with the sheet's own open/close motion.
    val backdropAmount by animateFloatAsState(
        targetValue = if (sheetState.targetValue != ModalBottomSheetValue.Hidden) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "queueBackdropAmount",
    )
    // Fully open, the sheet covers 100% of the screen, so the backdrop is only ever visible
    // mid-animation. Skip it at rest instead of re-blurring an invisible player every tick.
    val backdrop =
        backdropEffect(
            sdkInt = Build.VERSION.SDK_INT,
            amount = if (backdropAmount >= 1f) 0f else backdropAmount,
        )

    val density = LocalDensity.current
    val bottomExclusionPx =
        max(
            WindowInsets.systemGestures.getBottom(density).toFloat(),
            with(density) { GestureHandler.BOTTOM_EDGE_EXCLUSION_DP.dp.toPx() },
        )
    val topExclusionPx =
        max(
            WindowInsets.systemGestures.getTop(density).toFloat(),
            with(density) { GestureHandler.TOP_EDGE_EXCLUSION_DP.dp.toPx() },
        )

    ModalBottomSheetLayout(
        sheetState = sheetState,
        sheetShape = RoundedCornerShape(24.dp),
        sheetBackgroundColor = QueueSheetBackground,
        scrimColor = Color.Transparent, // R2: Transparent IS specified → M2 Scrim keeps the tap-dismiss hit box
        sheetContent = {
            QueueSheetContent(
                // The sheet stays composed while hidden, so rebuilding one QueueRow per song on
                // every recomposition is real work; only redo it when the queue or current track changes.
                rows = remember(state.queue, state.currentIndex) { queueRows(state.queue, state.currentIndex) },
                accent = accent,
                sortMode = state.queueSortMode,
                descending = state.queueSortDescending,
                isVisible = sheetState.isVisible,
                isPlaying = state.isPlaying,
                onSortSelected = { mode ->
                    scope.launch { playbackManager.reorderQueue(mode, state.queueSortDescending) }
                },
                onToggleDirection = {
                    scope.launch { playbackManager.reorderQueue(state.queueSortMode, !state.queueSortDescending) }
                },
                onRowTap = { index -> playbackManager.jumpTo(index) },
                onMoveRow = { from, to -> playbackManager.moveQueueItem(from, to) },
                onRemoveRow = { index -> playbackManager.removeFromQueue(index) },
                onQueryChanged = { query -> playbackManager.onQueryChanged(query) },
                onPickFolder = {
                    scope.launch { sheetState.hide() }
                    folderPickerFlow.onFolderIconTapped()
                },
                onTogglePlayPause = { playbackManager.togglePlayPause() },
                onClose = { scope.launch { sheetState.hide() } },
                scrollAnchorUri = scrollAnchorUri,
                onScrollAnchorChanged = { uri ->
                    scrollAnchorUri = uri
                    prefs.queueScrollAnchorUri = uri
                },
                recentSearches = recentSearches,
                onCommitSearch = { queryText ->
                    val updated = pushRecentSearch(recentSearches, queryText)
                    recentSearches = updated
                    prefs.recentSearches = updated
                },
                onSearchYoutube = { query -> openYoutubeSearch(context, query) },
            )
        },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .queueBackdrop(backdrop)
                    .tapPlayGestures(
                        onAction = { action ->
                            handleGesture(
                                action = action,
                                playbackManager = playbackManager,
                                onCenterTap = {
                                    if (state.queue.isEmpty()) {
                                        folderPickerFlow.onFolderIconTapped()
                                    } else {
                                        playbackManager.togglePlayPause()
                                        tapTriggerCount++
                                    }
                                },
                                onVolumeDragChanged = { isAdjustingVolume = it },
                            )
                        },
                        topExclusionPx = topExclusionPx,
                        bottomExclusionPx = bottomExclusionPx,
                    ),
        ) {
            // Traversal phase: no folder counts yet — indeterminate feedback (spec T1)
            if (state.scanPhase == ScanPhase.SCANNING_FOLDERS) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 32.dp, end = 32.dp)
                            .align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White.copy(alpha = 0.7f),
                        backgroundColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = "Cargando biblioteca…",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Scanning progress indicator (only when scanning library)
            if (state.isScanning) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp, start = 32.dp, end = 32.dp)
                            .align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(
                        progress = (state.scanLoaded / state.scanTotal.toFloat()).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White.copy(alpha = 0.7f),
                        backgroundColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = "Cargando biblioteca ${state.scanLoaded}/${state.scanTotal}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Typography: Centered in the upper-middle area (blurs during volume drag)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .align(Alignment.Center)
                        .padding(bottom = 120.dp)
                        .then(
                            if (songInfoBlur > 0.dp) Modifier.blur(songInfoBlur) else Modifier
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SongInfo(
                    title = song?.title ?: if (state.queue.isEmpty()) "Toca para abrir carpeta" else "Sin título",
                    artist = song?.artist ?: if (state.queue.isEmpty()) "TapPlay" else "Desconocido",
                    modifier =
                        Modifier.passiveLongPress(
                            enabled = !sheetState.isVisible,
                            onLongPress = { scope.launch { sheetState.show() } },
                        ),
                )
            }

            // Center Play/Pause Transient Animated Overlay (disappears after a moment)
            PlayPauseTransientOverlay(
                isPlaying = state.isPlaying,
                triggerCount = tapTriggerCount,
                modifier = Modifier.align(Alignment.Center),
            )

            // Remaining time, in place of the old animated progress bar (no motion, no drag-seek —
            // seeking stays available via the left/right ±10s tap zones).
            RemainingTimeLabel(
                positionMs = playbackManager.positionMs,
                durationMs = state.durationMs,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp),
            )

            // Full-screen volume wash while dragging; fades out once the finger lifts.
            // Drawn last so it sits above the rest of the player visually.
            VolumeIndicatorOverlay(
                volumePercent = volumePercent,
                isDragging = isAdjustingVolume,
            )
        }
    }
}

private fun handleFolderPicked(
    context: Context,
    playbackManager: PlaybackManager,
    scope: CoroutineScope,
    uri: Uri,
) {
    val prefs = (context.applicationContext as TapPlayApplication).prefs
    prefs.lastFolderUri = uri.toString()
    scope.launch {
        val songs = playbackManager.scanLibrary(uri)
        if (songs.isNotEmpty()) {
            playbackManager.setQueue(songs)
            playbackManager.play()
        }
    }
}

/**
 * Lightweight "search elsewhere" fallback for a queue search with no local
 * matches: hands off to the YouTube app (or a browser, if it's not
 * installed) via an ACTION_VIEW intent. No WebView, no INTERNET permission —
 * the network request happens in the target app/browser, not this process,
 * which keeps TapPlay's offline-only footprint unchanged.
 */
private fun openYoutubeSearch(
    context: Context,
    query: String,
) {
    val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

private suspend fun requestPostNotificationsIfNeeded(
    context: Context,
    request: () -> Unit,
) {
    val prefs = (context.applicationContext as TapPlayApplication).prefs
    if (prefs.notificationsRequested) return
    prefs.notificationsRequested = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        request()
    }
}

private fun handleGesture(
    action: GestureAction,
    playbackManager: PlaybackManager,
    onCenterTap: () -> Unit,
    onVolumeDragChanged: (Boolean) -> Unit,
) {
    when (action) {
        is GestureAction.Tap ->
            when (action.zone) {
                GestureZone.CENTER -> onCenterTap()
                GestureZone.LEFT -> playbackManager.seekBy(-SEEK_STEP_MS)
                GestureZone.RIGHT -> playbackManager.seekBy(SEEK_STEP_MS)
            }
        is GestureAction.DoubleTap ->
            when (action.zone) {
                GestureZone.CENTER -> playbackManager.next()
                GestureZone.LEFT -> playbackManager.previous()
                GestureZone.RIGHT -> playbackManager.next()
            }
        is GestureAction.LongPress -> Unit
        is GestureAction.HorizontalSwipe ->
            when (action.direction) {
                SwipeDirection.LEFT -> playbackManager.next()
                SwipeDirection.RIGHT -> playbackManager.previous()
                SwipeDirection.UP, SwipeDirection.DOWN -> Unit
            }
        is GestureAction.VolumeSteps -> {
            onVolumeDragChanged(true)
            playbackManager.changeVolumeBySteps(action.steps)
        }
        is GestureAction.VolumeDragEnd -> onVolumeDragChanged(false)
    }
}

/**
 * Only composable that reads the position flow, so the twice-a-second tick recomposes this
 * label alone instead of the whole player screen.
 */
@Composable
private fun RemainingTimeLabel(
    positionMs: StateFlow<Long>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val position by positionMs.collectAsStateWithLifecycle()
    Text(
        text = formatDuration((durationMs - position).coerceAtLeast(0L)),
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 13.sp,
        modifier = modifier,
    )
}
