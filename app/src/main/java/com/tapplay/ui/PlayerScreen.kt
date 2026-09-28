package com.tapplay.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.tapplay.player.PlayerUiState
import com.tapplay.player.ScanPhase
import com.tapplay.ui.components.MinimalProgressBar
import com.tapplay.ui.components.PlayPauseTransientOverlay
import com.tapplay.ui.components.QueueSheetBackground
import com.tapplay.ui.components.QueueSheetContent
import com.tapplay.ui.components.SongInfo
import com.tapplay.ui.components.VolumeIndicatorOverlay
import com.tapplay.ui.components.queueRows
import com.tapplay.util.GestureAction
import com.tapplay.util.GestureHandler
import com.tapplay.util.GestureZone
import com.tapplay.util.SwipeDirection
import com.tapplay.util.bottomEdgeSwipeToOpen
import com.tapplay.util.tapPlayGestures
import kotlinx.coroutines.CoroutineScope
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

    val sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Hidden)
    BackHandler(enabled = sheetState.isVisible) {
        scope.launch { sheetState.hide() }
    }

    val density = LocalDensity.current
    val edgeExclusionPx =
        max(
            WindowInsets.systemGestures.getBottom(density).toFloat(),
            with(density) { GestureHandler.EDGE_EXCLUSION_DP.dp.toPx() },
        )

    ModalBottomSheetLayout(
        sheetState = sheetState,
        sheetBackgroundColor = QueueSheetBackground,
        scrimColor = Color.Transparent, // R2: Transparent IS specified → M2 Scrim keeps the tap-dismiss hit box
        sheetContent = {
            QueueSheetContent(
                rows = queueRows(state.queue, state.currentIndex),
                accent = accent,
                sortMode = state.queueSortMode,
                descending = state.queueSortDescending,
                isVisible = sheetState.isVisible,
                onSortSelected = { mode ->
                    scope.launch { playbackManager.reorderQueue(mode, state.queueSortDescending) }
                },
                onToggleDirection = {
                    scope.launch { playbackManager.reorderQueue(state.queueSortMode, !state.queueSortDescending) }
                },
                onRowTap = { index -> playbackManager.jumpTo(index) },
                onMoveRow = { from, to -> playbackManager.moveQueueItem(from, to) },
                onQueryChanged = { query -> playbackManager.onQueryChanged(query) },
                onPickFolder = {
                    scope.launch { sheetState.hide() }
                    folderPickerFlow.onFolderIconTapped()
                },
            )
        },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .bottomEdgeSwipeToOpen(
                        exclusionPx = edgeExclusionPx,
                        enabled = !sheetState.isVisible,
                        onOpen = { scope.launch { sheetState.show() } },
                    )
                    .tapPlayGestures(
                        onAction = { action ->
                            handleGesture(
                                action = action,
                                playbackManager = playbackManager,
                                state = state,
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
                        bottomExclusionPx = edgeExclusionPx,
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
                )
            }

            // Center Play/Pause Transient Animated Overlay (disappears after a moment)
            PlayPauseTransientOverlay(
                isPlaying = state.isPlaying,
                triggerCount = tapTriggerCount,
                modifier = Modifier.align(Alignment.Center),
            )

            // Bottom Segmented Vertical Block Progress Bar
            MinimalProgressBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = { targetMs -> playbackManager.seekTo(targetMs) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
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
    state: PlayerUiState,
    onCenterTap: () -> Unit,
    onVolumeDragChanged: (Boolean) -> Unit,
) {
    when (action) {
        is GestureAction.Tap ->
            when (action.zone) {
                GestureZone.CENTER -> onCenterTap()
                GestureZone.LEFT -> seekBy(playbackManager, state, -SEEK_STEP_MS)
                GestureZone.RIGHT -> seekBy(playbackManager, state, SEEK_STEP_MS)
            }
        is GestureAction.DoubleTap ->
            when (action.zone) {
                GestureZone.CENTER -> playbackManager.next()
                GestureZone.LEFT -> playbackManager.previous()
                GestureZone.RIGHT -> playbackManager.next()
            }
        is GestureAction.LongPress -> playbackManager.seekTo(0L)
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

private fun seekBy(
    playbackManager: PlaybackManager,
    state: PlayerUiState,
    deltaMs: Long,
) {
    val target = (state.positionMs + deltaMs).coerceIn(0L, state.durationMs)
    playbackManager.seekTo(target)
}
