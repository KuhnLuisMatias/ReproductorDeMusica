package com.tapplay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.foundation.Canvas
import androidx.compose.material.Text
import androidx.compose.material.DismissDirection
import androidx.compose.material.DismissValue
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.SwipeToDismiss
import androidx.compose.material.rememberDismissState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tapplay.R
import com.tapplay.model.Song
import com.tapplay.player.SortMode
import com.tapplay.player.matchesQuery
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Queue sheet palette (spec: queue-view "Sheet visual palette"). */
val QueueSheetBackground = Color.Black
private val SeparatorColor = Color.White.copy(alpha = 0.08f)
private val ScrollbarColor = Color.White.copy(alpha = 0.35f)
private val ScrollbarWidth = 8.dp
private val ScrollbarEndPadding = 6.dp
private val ScrollbarMinThumbHeight = 32.dp
private val ScrollbarHitWidth = 28.dp
private val ScrubPreviewHeight = 32.dp
private val HeaderButtonSize = 40.dp
private val SortLabelMaxWidth = 130.dp
private const val ARTIST_SUGGESTION_LIMIT = 6

/** Immutable view model of one queue sheet row (pure, JVM-tested). */
data class QueueRow(
    val song: Song,
    val index: Int,
    val isCurrent: Boolean,
    val durationText: String,
)

/** Formats a duration in milliseconds as m:ss (spec: queue-view "Queue rows"). */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** Threshold for animate-vs-jump in locateCurrent: rows beyond this glide instantly. */
const val SCROLL_ANIMATE_MAX_DISTANCE = 15

/** Animated glide only for short jumps; long jumps scroll instantly (perf). */
fun shouldAnimateScroll(
    currentRow: Int,
    firstVisibleItemIndex: Int,
    maxDistance: Int = SCROLL_ANIMATE_MAX_DISTANCE,
): Boolean = abs(currentRow - firstVisibleItemIndex) <= maxDistance

/** Builds the sheet row models: current-track highlight + formatted duration. */
fun queueRows(
    queue: List<Song>,
    currentIndex: Int,
): List<QueueRow> =
    queue.mapIndexed { index, song ->
        QueueRow(
            song = song,
            index = index,
            isCurrent = index == currentIndex,
            durationText = formatDuration(song.durationMs),
        )
    }

/**
 * Pure visual filter (JVM-tested): blank query → all rows; otherwise
 * case-insensitive `contains` on title, artist OR album. Rows keep their
 * REAL queue index — tap-to-jump stays correct on a filtered list. D
 * contract: while [query] is non-blank, drag-reorder is disabled (filtered
 * positions ≠ queue indices).
 * Matching delegates to Song.matchesQuery (R14): what the sheet shows == what playback plays.
 */
fun filterRows(
    rows: List<QueueRow>,
    query: String,
): List<QueueRow> =
    if (query.isBlank()) {
        rows
    } else {
        rows.filter { it.song.matchesQuery(query) }
    }

/**
 * Pure pinned-bar visibility (queue-polish S1): the bar shows only when there
 * is a rendered list, a current playing track, AND the current row is
 * offscreen — hidden on open-at-current (new regime), appears on scroll-away,
 * hides again at the scrolled-to destination. [currentRowOffscreen] is derived
 * structural state (currentRowOffscreen derivedStateOf): a filtered-out
 * current row is constantly offscreen (its uri key never appears in the
 * LazyColumn), so "current filtered out -> bar visible; tap is a locate
 * no-op" (handled at locateCurrent, not here). [rows] is what the sheet will
 * render so an empty/fully-filtered-from-empty list hides the bar; [currentUri]
 * is derived from the FULL row list (playback state, not viewport state).
 */
fun shouldShowPinnedBar(
    rows: List<QueueRow>,
    currentUri: String?,
    currentRowOffscreen: Boolean,
): Boolean =
    rows.isNotEmpty() && currentUri != null && currentRowOffscreen

/** Which trailing control the search field shows, if any (search-style R3/R7). */
enum class SearchTrailing { CLOSE, BROOM }

/**
 * Pure seam (JVM-tested, no Compose): text + focused → X (erase-to-keep-typing);
 * text + unfocused → broom; blank in ANY focus state → null (no control rendered).
 * Blank == isNotBlank — parity with the IME Search predicate (BasicTextField L~816).
 */
fun searchTrailingAction(
    query: String,
    isFocused: Boolean,
): SearchTrailing? =
    when {
        query.isNotBlank() && isFocused -> SearchTrailing.CLOSE
        query.isNotBlank() -> SearchTrailing.BROOM
        else -> null
    }

/**
 * Pure drag math (JVM-tested, no Compose types): [centers] holds each visible
 * row's vertical center in viewport coordinates, in row order (from
 * LazyListState.layoutInfo.visibleItemsInfo: offset + size / 2). The dragged
 * row's visual center is centers[draggedAt] + [dragOffsetY]; the target slot
 * is the farthest neighbor center it has crossed. Clamped to the visible
 * range — rows beyond the viewport are unreachable (no auto-scroll ceiling).
 */
fun targetPosition(
    draggedAt: Int,
    dragOffsetY: Float,
    centers: List<Float>,
): Int {
    if (centers.isEmpty()) return draggedAt
    if (draggedAt !in centers.indices) return draggedAt.coerceIn(0, centers.lastIndex)
    var target = draggedAt
    val visual = centers[draggedAt] + dragOffsetY
    if (dragOffsetY > 0f) {
        for (i in draggedAt + 1 until centers.size) {
            if (visual >= centers[i]) target = i else break
        }
    } else if (dragOffsetY < 0f) {
        for (i in draggedAt - 1 downTo 0) {
            if (visual <= centers[i]) target = i else break
        }
    }
    return target
}

/**
 * Pure per-row mid-drag shift (JVM-tested): rows between [draggedAt] and
 * [target] open the landing slot by moving one dragged-row height
 * ([draggedHeight]); everything else (including the dragged row) shifts 0f.
 * ponytail: uniform shift per crossed slot — real rows are uniform (~66dp);
 * if variable heights ever ship, accumulate per-neighbor heights here
 * (post-drop animateItemPlacement remains the placement source of truth).
 */
fun neighborShift(
    position: Int,
    draggedAt: Int,
    target: Int,
    draggedHeight: Float,
): Float =
    when {
        target > draggedAt -> if (position in draggedAt + 1..target) -draggedHeight else 0f
        target < draggedAt -> if (position in target until draggedAt) draggedHeight else 0f
        else -> 0f
    }

/**
 * Pure viewport→list resolution (JVM-tested): [targetViewportIndex] is a
 * position within the visible-items subset (from `targetPosition`, viewport
 * order), which only coincides with an index into [rows] when the list is
 * scrolled to the very top. Any scroll offset makes the two index spaces
 * diverge; this looks up the song uri at that viewport slot in [visibleKeys]
 * and resolves it back to its real position in [rows]. Falls back to
 * [fallback] when the viewport index or its uri can't be resolved (e.g. the
 * dragged row scrolled out of the visible range).
 */
fun resolveDropTarget(
    rows: List<QueueRow>,
    visibleKeys: List<String>,
    targetViewportIndex: Int,
    fallback: Int,
): Int {
    val key = visibleKeys.getOrNull(targetViewportIndex) ?: return fallback
    val index = rows.indexOfFirst { it.song.uri == key }
    return if (index >= 0) index else fallback
}

/**
 * Pure geometry for the queue scrollbar thumb (JVM-tested): returns
 * (offsetY, heightPx) or null when the list fits without scrolling.
 * Thumb height reflects how much of the list is visible (list length
 * signal); offset reflects the scroll position.
 */
fun scrollbarThumb(
    totalItems: Int,
    visibleItems: Int,
    firstIndex: Int,
    firstItemOffsetPx: Float,
    firstItemSizePx: Float,
    viewportPx: Float,
    minThumbPx: Float,
): Pair<Float, Float>? {
    if (totalItems <= 0 || visibleItems >= totalItems) return null
    val thumbHeight =
        (viewportPx * visibleItems / totalItems.toFloat())
            .coerceIn(minThumbPx, viewportPx)
    val itemFraction = if (firstItemSizePx > 0f) -firstItemOffsetPx / firstItemSizePx else 0f
    val scrollableItems = (totalItems - visibleItems).toFloat()
    val fraction = ((firstIndex + itemFraction) / scrollableItems).coerceIn(0f, 1f)
    return Pair(fraction * (viewportPx - thumbHeight), thumbHeight)
}

/**
 * Pure scroll-anchor lookup (JVM-tested, design C2): index of the row whose
 * song uri equals [uri], or null when [uri] is null or not present (e.g. the
 * saved anchor was dropped from the queue) — callers fall back to index 0 in
 * that case.
 */
fun anchorIndex(
    rows: List<QueueRow>,
    uri: String?,
): Int? {
    if (uri == null) return null
    val index = rows.indexOfFirst { it.song.uri == uri }
    return index.takeIf { it >= 0 }
}

private const val RECENT_SEARCHES_CAP = 5

/**
 * Pure recent-searches update (JVM-tested, design C3): trims [query],
 * ignores it if blank, moves a case-insensitive duplicate to the front
 * instead of adding a second entry, and caps the result at [cap] (oldest
 * entries dropped first).
 */
fun pushRecentSearch(
    recent: List<String>,
    query: String,
    cap: Int = RECENT_SEARCHES_CAP,
): List<String> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return recent
    val withoutDuplicate = recent.filterNot { it.equals(trimmed, ignoreCase = true) }
    return (listOf(trimmed) + withoutDuplicate).take(cap)
}

/**
 * Pure autocomplete (JVM-tested): distinct [artists] whose name contains
 * [query] (case-insensitive), excluding an exact match (already fully typed),
 * capped at [limit]. Blank query yields no suggestions.
 */
fun artistSuggestionsFor(
    artists: List<String>,
    query: String,
    limit: Int = ARTIST_SUGGESTION_LIMIT,
): List<String> =
    if (query.isBlank()) {
        emptyList()
    } else {
        artists
            .filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }
            .distinct()
            .take(limit)
    }

/**
 * Pure inverse of [scrollbarThumb] (JVM-tested): maps a raw drag [dragY]
 * (viewport-relative px) to a target item index, so scrubbing the scrollbar
 * jumps the list to follow the finger. Centers the thumb under the finger
 * (subtracts half the thumb height) rather than anchoring its top edge, which
 * reads as more natural when grabbing anywhere along the track.
 */
fun scrollbarDragToIndex(
    dragY: Float,
    totalItems: Int,
    visibleItems: Int,
    viewportPx: Float,
    minThumbPx: Float,
): Int {
    if (totalItems <= 0) return 0
    val thumbHeight = (viewportPx * visibleItems / totalItems.toFloat()).coerceIn(minThumbPx, viewportPx)
    val usableRange = (viewportPx - thumbHeight).coerceAtLeast(1f)
    val fraction = ((dragY - thumbHeight / 2f) / usableRange).coerceIn(0f, 1f)
    val scrollableItems = (totalItems - visibleItems).coerceAtLeast(0)
    return (fraction * scrollableItems).roundToInt().coerceIn(0, totalItems - 1)
}

/** Draws a slim vertical thumb on the trailing edge driven by [state]. */
fun Modifier.queueScrollbar(
    state: LazyListState,
    color: Color,
): Modifier =
    drawWithContent {
        drawContent()
        val first = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
        val thumb =
            scrollbarThumb(
                totalItems = state.layoutInfo.totalItemsCount,
                visibleItems = state.layoutInfo.visibleItemsInfo.size,
                firstIndex = first.index,
                firstItemOffsetPx = first.offset.toFloat(),
                firstItemSizePx = first.size.toFloat(),
                viewportPx = size.height,
                minThumbPx = ScrollbarMinThumbHeight.toPx(),
            ) ?: return@drawWithContent
        val width = ScrollbarWidth.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width - ScrollbarEndPadding.toPx(), thumb.first),
            size = Size(width, thumb.second),
            cornerRadius = CornerRadius(width / 2f, width / 2f),
        )
    }

/**
 * Content of the queue bottom sheet (spec: queue-view): black background,
 * one lazy row per song with title/artist/duration, 8%-white separators, the
 * current track accent-highlighted, tap-to-jump, and an explicit empty state.
 * The sheet is capped at half the screen height and shows a scrollbar thumb
 * that communicates scroll position and relative list length.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun QueueSheetContent(
    rows: List<QueueRow>,
    accent: Color,
    sortMode: SortMode,
    descending: Boolean,
    isVisible: Boolean,
    isPlaying: Boolean,
    onSortSelected: (SortMode) -> Unit,
    onToggleDirection: () -> Unit,
    onRowTap: (Int) -> Unit,
    onMoveRow: (from: Int, to: Int) -> Unit,
    onRemoveRow: (Int) -> Unit = {},
    onQueryChanged: (String) -> Unit,
    onPickFolder: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onClose: () -> Unit = {},
    scrollAnchorUri: String? = null,
    onScrollAnchorChanged: (String) -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onCommitSearch: (String) -> Unit = {},
    onSearchYoutube: (String) -> Unit = {},
) {
    if (rows.isEmpty()) {
        Text(
            text = "Queue is empty",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(QueueSheetBackground)
                    .padding(vertical = 32.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    // Only recomputed when the queue or the query actually changes — not on every
    // scrub/scroll recomposition, which would otherwise re-filter the whole list per frame.
    val visibleRows = remember(rows, query) { filterRows(rows, query) }
    var draggingUri by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragCenters by remember { mutableStateOf<List<Float>?>(null) }
    var scrubIndex by remember { mutableStateOf<Int?>(null) }

    val allArtists = remember(rows) { rows.map { it.song.artist }.distinct() }
    // Blank query shows ALL artists (the row is lazy, so unbounded is fine);
    // non-blank keeps the capped, filtered suggestions from artistSuggestionsFor.
    // This wins the blank slot over the recents row below, which now only fires
    // when the queue has no artists at all.
    val artistSuggestions =
        remember(query, allArtists) {
            if (query.isBlank()) allArtists else artistSuggestionsFor(allArtists, query)
        }
    val currentArtist = remember(rows) { rows.firstOrNull { it.isCurrent }?.song?.artist }

    // R3.7 (user-flagged, behavior decision): with the current track pinned
    // to the bottom bar and the locate chip for on-demand jumps, opening the
    // sheet no longer auto-scrolls to the playing song. Anchor restore is the
    // only scroll intent; no saved anchor (or anchor no longer in the queue)
    // opens at index 0.
    var anchorRestored by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            // Persist the anchor once, on close. Saving it per row crossed recomposed the
            // whole player screen and rewrote the prefs file (queue snapshot included) during
            // every scroll. Skipped until a restore has run, so an untouched index 0 never
            // overwrites a real saved anchor.
            if (anchorRestored && query.isBlank()) {
                visibleRows.getOrNull(listState.firstVisibleItemIndex)?.song?.uri?.let(onScrollAnchorChanged)
            }
            anchorRestored = false
            return@LaunchedEffect
        }
        listState.scrollToItem(anchorIndex(visibleRows, scrollAnchorUri) ?: 0)
        anchorRestored = true
    }

    fun locateCurrent() {
        val currentRow = visibleRows.indexOfFirst { it.isCurrent }
        if (currentRow < 0) return
        scope.launch {
            val firstVisible = listState.firstVisibleItemIndex
            if (shouldAnimateScroll(currentRow, firstVisible)) {
                listState.animateScrollToItem(currentRow)
            } else {
                listState.scrollToItem(currentRow)
            }
        }
    }

    // Pinned now-playing bar state (S3): the current row is derived from the FULL
    // row list (playback state, not viewport state), so a filter that drops the
    // current row still leaves the bar visible.
    val pinnedRow = remember(rows) { rows.firstOrNull { it.isCurrent } }
    // currentUri reads listState.layoutInfo (snapshot-backed, via currentRowOffscreen
    // below) but only triggers recomposition of the BAR when the boolean flips —
    // not on every pixel scrolled.
    val currentUri = remember(rows) { pinnedRow?.song?.uri }
    val currentRowOffscreen by remember(currentUri) {
        derivedStateOf {
            currentUri != null && listState.layoutInfo.visibleItemsInfo.none { it.key == currentUri }
        }
    }

    // Coalesces rapid scrollbar-drag updates into a single active scroll:
    // each new target cancels the previous scrollToItem via LaunchedEffect's
    // key-restart, so the list always follows the latest finger position.
    LaunchedEffect(scrubIndex) {
        scrubIndex?.let { listState.scrollToItem(it) }
    }

    fun dropDragged() {
        val draggedAt = visibleRows.indexOfFirst { it.song.uri == draggingUri }
        val info = listState.layoutInfo.visibleItemsInfo
        val draggedAtInfo = info.indexOfFirst { it.key == draggingUri }
        val centersSnapshot = dragCenters
        val targetViewportIndex =
            if (draggedAt >= 0 && draggedAtInfo >= 0) {
                // ponytail: centers snapshot is taken at drag start and never refreshed
                // mid-drag (no auto-scroll exists). If visibleItemsInfo changed during the
                // drag (row entered viewport) or snapshot is null, recompute that one list
                // here — drop-only cost. If auto-scroll ever ships, refresh dragCenters per
                // drag frame in onDrag instead.
                val centers =
                    if (centersSnapshot != null && centersSnapshot.size == info.size) {
                        centersSnapshot
                    } else {
                        info.map { it.offset + it.size / 2f }
                    }
                targetPosition(draggedAtInfo, dragOffsetY, centers)
            } else {
                draggedAtInfo
            }
        // targetViewportIndex is viewport-local (a position among visible items only);
        // resolve it back to its real position in visibleRows before using it as one.
        val target =
            resolveDropTarget(
                rows = visibleRows,
                visibleKeys = info.mapNotNull { it.key as? String },
                targetViewportIndex = targetViewportIndex,
                fallback = draggedAt,
            )
        val from = visibleRows.getOrNull(draggedAt)?.index
        val to = visibleRows.getOrNull(target.coerceIn(visibleRows.indices))?.index
        draggingUri = null
        dragOffsetY = 0f
        dragCenters = null
        if (from != null && to != null && from != to) onMoveRow(from, to)
    }
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp).dp
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(QueueSheetBackground)
                // R3.2 (LOAD-BEARING): the sheet's height cap moves here, above ALL
                // children — otherwise full-height list + trigger + pinned bar + footer
                // overflow the sheet. The list keeps weight(1f, fill = false), so short
                // queues still hug content and the bar sits flush.
                .heightIn(max = maxSheetHeight),
    ) {
        // S3: artist-suggestion chips render ABOVE the field so tapping one never
        // scrolls the field out of reach under the keyboard; recents stay below.
        if (artistSuggestions.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(artistSuggestions) { artist ->
                    Text(
                        text = artist,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .clickable {
                                    query = artist
                                    onQueryChanged(artist)
                                    onCommitSearch(artist)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        // Search field + suggestions, search stack now leads the sheet (queue-head C2).
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChanged(it)
                },
                singleLine = true,
                textStyle =
                    LocalTextStyle.current.copy(
                        color = Color.White,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Start,
                    ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = {
                        if (query.isNotBlank()) onCommitSearch(query)
                    }),
                modifier =
                    Modifier
                        .onFocusChanged { searchFocused = it.isFocused }
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.08f),
                                    Color.White.copy(alpha = 0.04f),
                                ),
                            ),
                        )
                        .weight(1f),
                decorationBox = { innerTextField ->
                    // Trailing action lives INSIDE the pill (trailing-icon pattern):
                    // the field keeps its full width, the icon stays within it.
                    val trailingAction = searchTrailingAction(query, searchFocused)
                    // Pill height grows (~48 → ~60dp): vertical padding inflates it,
                    // while the gradient background sits OUTSIDE decorationBox on the
                    // BasicTextField modifier chain, so it still fills the full pill.
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Buscar canción o artista",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 15.sp,
                                )
                            }
                            innerTextField()
                        }
                        when (trailingAction) {
                            SearchTrailing.CLOSE ->
                                IconButton(
                                    onClick = {
                                        query = ""
                                        onQueryChanged("")
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Borrar texto escrito",
                                        tint = Color.White,
                                    )
                                }
                            SearchTrailing.BROOM ->
                                IconButton(
                                    onClick = {
                                        query = ""
                                        onQueryChanged("")
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Limpiar búsqueda",
                                        tint = Color.White,
                                    )
                                }
                            null -> {}
                        }
                    }
                },
            )
        }
        if (artistSuggestions.isEmpty() && query.isBlank() && (currentArtist != null || recentSearches.isNotEmpty())) {
            // Shown only when nothing is typed yet (and no artist matches): the
            // artist currently playing (fastest way to filter to "what's on now"),
            // then recent searches.
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentArtist != null) {
                    item {
                        Text(
                            text = "▸ $currentArtist",
                            color = accent,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(accent.copy(alpha = 0.18f))
                                    .clickable {
                                        query = currentArtist
                                        onQueryChanged(currentArtist)
                                        onCommitSearch(currentArtist)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                items(recentSearches) { recent ->
                    Text(
                        text = recent,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .clickable {
                                    query = recent
                                    onQueryChanged(recent)
                                    onCommitSearch(recent)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        SortHeader(
            sortMode = sortMode,
            descending = descending,
            onSortSelected = onSortSelected,
            onToggleDirection = onToggleDirection,
            onPickFolder = {
                onQueryChanged("")
                query = ""
                onPickFolder()
            },
        )
        // weight(fill = false): the footer below is measured first, so the list can never
        // push it off the bottom of the screen; the list still wraps short content.
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (draggingUri != null) 2f else 0f)
                        .queueScrollbar(listState, ScrollbarColor),
                state = listState,
            ) {
                if (visibleRows.isEmpty()) {
                    item {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Sin resultados",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                            if (query.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Buscar \"$query\" en YouTube",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .clickable { onSearchYoutube(query) }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                itemsIndexed(visibleRows, key = { _, row -> row.song.uri }, contentType = { _, _ -> "queueRow" }) { index, row ->
                    if (index > 0) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(SeparatorColor),
                        )
                    }
                    val dismissState = rememberDismissState(
                        confirmStateChange = { dismissValue ->
                            if (dismissValue == DismissValue.DismissedToStart) {
                                onRemoveRow(row.index)
                                true
                            } else {
                                false
                            }
                        }
                    )
                    SwipeToDismiss(
                        state = dismissState,
                        directions = setOf(DismissDirection.EndToStart),
                        background = {
                            val isDismissing = dismissState.dismissDirection == DismissDirection.EndToStart
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isDismissing) Color(0xFFE53935) else Color.Transparent)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                if (isDismissing) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Remover",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "REMOVER",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        },
                        dismissContent = {
                            QueueRowItem(
                                row = row,
                                accent = accent,
                                onRowTap = onRowTap,
                                isDragging = row.song.uri == draggingUri,
                                isPlaying = isPlaying,
                                onTogglePlayPause = onTogglePlayPause,
                                modifier =
                                    Modifier
                                        .zIndex(if (row.song.uri == draggingUri) 1f else 0f)
                                        .graphicsLayer {
                                            translationY =
                                                when {
                                                    row.song.uri == draggingUri -> dragOffsetY
                                                    draggingUri == null -> 0f
                                                    else -> {
                                                        val centers = dragCenters
                                                        val info = listState.layoutInfo.visibleItemsInfo
                                                        if (centers == null || centers.size != info.size) {
                                                            0f
                                                        } else {
                                                            val draggedAt = info.indexOfFirst { it.key == draggingUri }
                                                            val meAt = info.indexOfFirst { it.key == row.song.uri }
                                                            if (draggedAt < 0 || meAt < 0) {
                                                                0f
                                                            } else {
                                                                val target =
                                                                    targetPosition(
                                                                        draggedAt,
                                                                        dragOffsetY,
                                                                        centers,
                                                                    )
                                                                neighborShift(
                                                                    meAt,
                                                                    draggedAt,
                                                                    target,
                                                                    info[draggedAt].size.toFloat(),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                        }
                                        .animateItemPlacement()
                                        .pointerInput(row.song.uri) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    if (query.isNotBlank()) return@detectDragGesturesAfterLongPress
                                                    if (draggingUri != null) return@detectDragGesturesAfterLongPress
                                                    draggingUri = row.song.uri
                                                    dragOffsetY = 0f
                                                    dragCenters = listState.layoutInfo.visibleItemsInfo.map { it.offset + it.size / 2f }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    dragOffsetY += dragAmount.y
                                                    change.consume()
                                                },
                                                onDragEnd = { dropDragged() },
                                                onDragCancel = { dropDragged() },
                                            )
                                        },
                            )
                        }
                    )
                }
            }

            // Invisible hit strip over the scrollbar's trailing edge: grabbing
            // and dragging anywhere along it scrubs the list (scrollbarDragToIndex),
            // instead of only reflecting position passively.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(ScrollbarHitWidth)
                        .zIndex(1f)
                        .then(
                            if (draggingUri == null) {
                                Modifier.pointerInput(visibleRows.size) {
                                    fun updateScrub(y: Float) {
                                        val visibleItems = listState.layoutInfo.visibleItemsInfo.size
                                        scrubIndex =
                                            scrollbarDragToIndex(
                                                dragY = y,
                                                totalItems = visibleRows.size,
                                                visibleItems = visibleItems,
                                                viewportPx = size.height.toFloat(),
                                                minThumbPx = ScrollbarMinThumbHeight.toPx(),
                                            )
                                    }
                                    detectDragGestures(
                                        onDragStart = { offset -> updateScrub(offset.y) },
                                        onDrag = { change, _ ->
                                            updateScrub(change.position.y)
                                            change.consume()
                                        },
                                        onDragEnd = { scrubIndex = null },
                                        onDragCancel = { scrubIndex = null },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
            )

            // Floating label previewing the artist you'd land on, following the
            // scrub position while dragging the scrollbar.
            val previewIndex = scrubIndex
            if (previewIndex != null && visibleRows.isNotEmpty()) {
                val fraction =
                    if (visibleRows.size > 1) previewIndex.toFloat() / (visibleRows.size - 1) else 0f
                val previewY = (maxHeight - ScrubPreviewHeight).coerceAtLeast(0.dp) * fraction
                Text(
                    text = visibleRows.getOrNull(previewIndex)?.song?.artist.orEmpty(),
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .zIndex(3f)
                            .padding(end = 40.dp)
                            .offset(y = previewY)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        // S3: pinned now-playing bar — a Column sibling of the list, NOT a LazyColumn
        // row: it never scrolls and is always visible however far the list is scrolled.
        if (shouldShowPinnedBar(rows = visibleRows, currentUri = pinnedRow?.song?.uri, currentRowOffscreen = currentRowOffscreen) && pinnedRow != null) {
            PinnedNowPlayingBar(
                row = pinnedRow,
                accent = accent,
                isPlaying = isPlaying,
                onLocate = { locateCurrent() },
                onTogglePlayPause = onTogglePlayPause,
            )
        }
        // Bottom minimize arrow: tapping this arrow minimizes the queue sheet
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClose() }
                .navigationBarsPadding()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Minimizar cola",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun SortHeader(
    sortMode: SortMode,
    descending: Boolean,
    onSortSelected: (SortMode) -> Unit,
    onToggleDirection: () -> Unit,
    onPickFolder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every fixed-size item below has a guaranteed width; only the Spacer flexes. Before,
        // the folder sat in the weighted slot and got squeezed to ~0 whenever the sort label
        // plus the icon buttons filled the row.
        Box(
            modifier =
                Modifier
                    .size(HeaderButtonSize)
                    .clickable { onPickFolder() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.folder_24px),
                contentDescription = "Cambiar carpeta de música",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier =
                    Modifier
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sortMode.label(),
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = SortLabelMaxWidth),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                // material 1.6.2 legacy DropdownMenuItem: ColumnScope content, no text/leadingIcon params.
                SortMode.values().forEach { mode ->
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            // Same-mode text-tap: close-noop — reorderQueue with
                            // identical args would be a pure-waste re-apply.
                            if (mode != sortMode) onSortSelected(mode)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                                if (mode == sortMode) {
                                    // Arrow tap flips direction IN PLACE: does NOT set
                                    // expanded=false → M2 menu stays open; reorderQueue
                                    // with flipped direction re-runs → arrow + list
                                    // flip live.
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(40.dp)
                                                .clickable { onToggleDirection() },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector =
                                                if (descending) {
                                                    Icons.Filled.KeyboardArrowDown
                                                } else {
                                                    Icons.Filled.KeyboardArrowUp
                                                },
                                            contentDescription = "Invertir sentido",
                                            tint = Color.White,
                                        )
                                    }
                                }
                                // non-selected rows: empty Box, no Check (alignment parity)
                            }
                            Text(
                                text = mode.label(),
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SortMode.label(): String =
    when (this) {
        SortMode.ADDED -> "Orden de adición"
        SortMode.ARTIST -> "Artista"
        SortMode.DATE_MODIFIED -> "Fecha de modificación"
        SortMode.DATE_ADDED -> "Fecha de agregado"
    }

private val DraggedRowBackground = Color(0xFF2A2A2A)

/**
 * Queue row: info column left, duration right-aligned on ALL rows
 * (beyond-ask: non-current rows' duration moved from leading to trailing
 * position), play/pause 28dp-glyph right of duration on the current row only.
 */
@Composable
private fun QueueRowItem(
    modifier: Modifier = Modifier,
    row: QueueRow,
    accent: Color,
    onRowTap: (Int) -> Unit,
    isDragging: Boolean,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
) {
    val highlight = accent.copy(alpha = 0.25f)
    val rowBackground =
        when {
            isDragging -> DraggedRowBackground
            row.isCurrent -> highlight
            else -> Color.Transparent
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(rowBackground)
                .clickable { onRowTap(row.index) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.song.title,
                color = if (row.isCurrent) accent else Color.White,
                fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.song.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = row.durationText,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
        if (row.isCurrent) {
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clickable { onTogglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseGlyph(
                    isPlaying = isPlaying,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Static bottom bar for the currently playing track (queue-instant S3,
 * queue-polish S1): a sibling of the LazyColumn — it never scrolls and is not
 * an overlay, so no contentInset / bottom-padding math is needed and the bar
 * is always visible however far the list is scrolled. Tapping the song zone
 * locates the current row (distance-aware glide/jump via locateCurrent);
 * trailing play/pause reuses the shared PlayPauseGlyph and the same 28dp-hit/
 * 16dp-glyph anatomy as QueueRowItem's in-row button.
 */
@Composable
private fun PinnedNowPlayingBar(
    row: QueueRow,
    accent: Color,
    isPlaying: Boolean,
    onLocate: () -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SeparatorColor),
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(QueueSheetBackground)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable { onLocate() },
        ) {
            Text(
                text = row.song.title,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.song.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .clickable { onTogglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            PlayPauseGlyph(
                isPlaying = isPlaying,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Small play/pause glyph for the current-track row (mirrors PlayPauseTransientOverlay's shapes). */
@Composable
private fun PlayPauseGlyph(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (isPlaying) {
            val barWidth = w * 0.28f
            val gap = w * 0.16f
            val leftX = (w - gap) / 2f - barWidth
            val rightX = (w + gap) / 2f
            val cornerRadius = CornerRadius(barWidth / 3f, barWidth / 3f)
            drawRoundRect(color = tint, topLeft = Offset(leftX, 0f), size = Size(barWidth, h), cornerRadius = cornerRadius)
            drawRoundRect(color = tint, topLeft = Offset(rightX, 0f), size = Size(barWidth, h), cornerRadius = cornerRadius)
        } else {
            val path =
                Path().apply {
                    moveTo(w * 0.2f, 0f)
                    lineTo(w * 0.85f, h / 2f)
                    lineTo(w * 0.2f, h)
                    close()
                }
            drawPath(path = path, color = tint)
        }
    }
}
