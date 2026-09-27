package com.tapplay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt

/** Queue sheet palette (spec: queue-view "Sheet visual palette"). */
val QueueSheetBackground = Color.Black
private val SeparatorColor = Color.White.copy(alpha = 0.08f)
private val ScrollbarColor = Color.White.copy(alpha = 0.35f)
private val ScrollbarWidth = 4.dp
private val ScrollbarEndPadding = 6.dp
private val ScrollbarMinThumbHeight = 32.dp
private val ScrollbarHitWidth = 32.dp
private val ScrubPreviewHeight = 32.dp
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
 * case-insensitive `contains` on title OR artist (the only fields a row
 * renders). Rows keep their REAL queue index — tap-to-jump stays correct on
 * a filtered list. D contract: while [query] is non-blank, drag-reorder is
 * disabled (filtered positions ≠ queue indices).
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueSheetContent(
    rows: List<QueueRow>,
    accent: Color,
    sortMode: SortMode,
    descending: Boolean,
    isVisible: Boolean,
    onSortSelected: (SortMode) -> Unit,
    onToggleDirection: () -> Unit,
    onRowTap: (Int) -> Unit,
    onMoveRow: (from: Int, to: Int) -> Unit,
    onQueryChanged: (String) -> Unit,
    onPickFolder: () -> Unit,
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
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Only recomputed when the queue or the query actually changes — not on every
    // scrub/scroll recomposition, which would otherwise re-filter the whole list per frame.
    val visibleRows = remember(rows, query) { filterRows(rows, query) }
    var draggingUri by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var scrubIndex by remember { mutableStateOf<Int?>(null) }

    val allArtists = remember(rows) { rows.map { it.song.artist }.distinct() }
    val artistSuggestions = remember(query, allArtists) { artistSuggestionsFor(allArtists, query) }

    // Jump to the currently playing track whenever the sheet is opened, so
    // scrolling around and reopening it never loses your place.
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        val currentRow = visibleRows.indexOfFirst { it.isCurrent }
        if (currentRow >= 0) listState.scrollToItem(currentRow)
    }

    fun locateCurrent() {
        val currentRow = visibleRows.indexOfFirst { it.isCurrent }
        if (currentRow >= 0) scope.launch { listState.animateScrollToItem(currentRow) }
    }

    // Reads listState.layoutInfo (snapshot-backed) but only triggers recomposition of
    // the chip below when the boolean flips — not on every pixel scrolled.
    val currentUri = remember(rows) { rows.firstOrNull { it.isCurrent }?.song?.uri }
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
        val target =
            if (draggedAt >= 0 && draggedAtInfo >= 0) {
                targetPosition(draggedAtInfo, dragOffsetY, info.map { it.offset + it.size / 2f })
            } else {
                draggedAt
            }
        val from = visibleRows.getOrNull(draggedAt)?.index
        val to = visibleRows.getOrNull(target.coerceIn(visibleRows.indices))?.index
        draggingUri = null
        dragOffsetY = 0f
        if (from != null && to != null && from != to) onMoveRow(from, to)
    }
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(QueueSheetBackground),
    ) {
        SortHeader(
            sortMode = sortMode,
            descending = descending,
            searchVisible = searchVisible,
            onSortSelected = onSortSelected,
            onToggleDirection = onToggleDirection,
            onToggleSearch = { searchVisible = !searchVisible },
            onPickFolder = {
                onQueryChanged("")
                query = ""
                searchVisible = false
                onPickFolder()
            },
        )
        if (searchVisible) {
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
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Buscar canción o artista",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 15.sp,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                IconButton(
                    onClick = {
                        query = ""
                        onQueryChanged("")
                        searchVisible = false
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Cerrar búsqueda",
                        tint = Color.White,
                    )
                }
            }
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
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .queueScrollbar(listState, ScrollbarColor),
                state = listState,
            ) {
                if (visibleRows.isEmpty()) {
                    item {
                        Text(
                            text = "Sin resultados",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                        )
                    }
                }
                itemsIndexed(visibleRows, key = { _, row -> row.song.uri }) { index, row ->
                    if (index > 0) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(SeparatorColor),
                        )
                    }
                    QueueRowItem(
                        row = row,
                        accent = accent,
                        onRowTap = onRowTap,
                        showDragHandle = query.isBlank(),
                        onDragHandleStart = {
                            if (draggingUri == null) {
                                draggingUri = row.song.uri
                                dragOffsetY = 0f
                            }
                        },
                        onDragHandleDelta = { dragOffsetY += it },
                        onDragHandleEnd = { dropDragged() },
                        modifier =
                            Modifier
                                .zIndex(if (row.song.uri == draggingUri) 1f else 0f)
                                .graphicsLayer {
                                    translationY =
                                        when {
                                            row.song.uri == draggingUri -> dragOffsetY
                                            draggingUri == null -> 0f
                                            else -> {
                                                val info = listState.layoutInfo.visibleItemsInfo
                                                val draggedAt = info.indexOfFirst { it.key == draggingUri }
                                                val meAt = info.indexOfFirst { it.key == row.song.uri }
                                                if (draggedAt < 0 || meAt < 0) {
                                                    0f
                                                } else {
                                                    val target =
                                                        targetPosition(
                                                            draggedAt,
                                                            dragOffsetY,
                                                            info.map { it.offset + it.size / 2f },
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
                                .animateItemPlacement(),
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
                        .pointerInput(visibleRows.size) {
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
                        },
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
                            .padding(end = 40.dp)
                            .offset(y = previewY)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // Contextual chip: only takes header space when it's actually useful,
            // i.e. you've scrolled the currently playing track out of view.
            if (currentRowOffscreen) {
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { locateCurrent() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LocateCurrentIcon(tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Canción actual", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SortHeader(
    sortMode: SortMode,
    descending: Boolean,
    searchVisible: Boolean,
    onSortSelected: (SortMode) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleSearch: () -> Unit,
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
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable { onPickFolder() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.folder_24px),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = if (searchVisible) "Ocultar búsqueda" else "Buscar",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onToggleDirection) {
            Icon(
                imageVector =
                    if (descending) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.Filled.KeyboardArrowUp
                    },
                contentDescription =
                    if (descending) {
                        "Orden descendente"
                    } else {
                        "Orden ascendente"
                    },
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        Box {
            Row(
                modifier =
                    Modifier
                        .clickable { expanded = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = sortMode.label(), color = Color.White, fontSize = 13.sp)
                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color.White)
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
                            onSortSelected(mode)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.width(20.dp)) {
                                if (mode == sortMode) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                    )
                                }
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

/** Crosshair "locate" glyph (no material-icons-extended dependency needed for one icon). */
@Composable
private fun LocateCurrentIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.36f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = tint, radius = radius, center = center, style = Stroke(width = size.minDimension * 0.09f))
        drawCircle(color = tint, radius = size.minDimension * 0.08f, center = center)
        val tickLength = size.minDimension * 0.14f
        val strokeWidth = size.minDimension * 0.09f
        listOf(
            Offset(center.x, 0f) to Offset(center.x, tickLength),
            Offset(center.x, size.height) to Offset(center.x, size.height - tickLength),
            Offset(0f, center.y) to Offset(tickLength, center.y),
            Offset(size.width, center.y) to Offset(size.width - tickLength, center.y),
        ).forEach { (start, end) ->
            drawLine(color = tint, start = start, end = end, strokeWidth = strokeWidth)
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

@Composable
private fun QueueRowItem(
    modifier: Modifier = Modifier,
    row: QueueRow,
    accent: Color,
    onRowTap: (Int) -> Unit,
    showDragHandle: Boolean,
    onDragHandleStart: () -> Unit,
    onDragHandleDelta: (Float) -> Unit,
    onDragHandleEnd: () -> Unit,
) {
    val highlight = accent.copy(alpha = 0.25f)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (row.isCurrent) highlight else Color.Transparent)
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
        if (showDragHandle) {
            Spacer(modifier = Modifier.width(8.dp))
            DragHandle(
                onDragStart = onDragHandleStart,
                onDragDelta = onDragHandleDelta,
                onDragEnd = onDragHandleEnd,
            )
        }
    }
}

/**
 * Explicit drag grip: reordering starts the instant you press and move here —
 * no long-press wait — while the rest of the row stays a plain tap-to-jump
 * target. The enclosing itemsIndexed `key` already recreates this composable
 * per song uri, so `pointerInput(Unit)` is enough to rebind per row.
 */
@Composable
private fun DragHandle(
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Canvas(
        modifier =
            Modifier
                .size(width = 20.dp, height = 24.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            onDragDelta(dragAmount.y)
                            change.consume()
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                },
    ) {
        val lineSpacing = size.height / 4f
        val strokeWidth = size.height * 0.09f
        for (i in 1..3) {
            val y = lineSpacing * i
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokeWidth,
            )
        }
    }
}
