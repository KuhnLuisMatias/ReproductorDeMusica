package com.tapplay.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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

/** Queue sheet palette (spec: queue-view "Sheet visual palette"). */
val QueueSheetBackground = Color.Black
private val SeparatorColor = Color.White.copy(alpha = 0.08f)
private val ScrollbarColor = Color.White.copy(alpha = 0.35f)
private val ScrollbarWidth = 4.dp
private val ScrollbarEndPadding = 6.dp
private val ScrollbarMinThumbHeight = 32.dp

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
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visibleRows = filterRows(rows, query)
    var draggingUri by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

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
        }
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
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
                                                neighborShift(meAt, draggedAt, target, info[draggedAt].size.toFloat())
                                            }
                                        }
                                    }
                            }
                            .animateItemPlacement()
                            .pointerInput(row.song.uri) {
                                // ponytail: drag limited to visible viewport — rows beyond
                                // the edge unreachable; add LazyListState edge auto-scroll
                                // coroutine if users report it.
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        if (query.isNotBlank()) return@detectDragGesturesAfterLongPress
                                        if (draggingUri != null) return@detectDragGesturesAfterLongPress
                                        draggingUri = row.song.uri
                                        dragOffsetY = 0f
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
    }
}
