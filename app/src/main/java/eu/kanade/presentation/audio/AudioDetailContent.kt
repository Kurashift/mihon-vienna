package eu.kanade.presentation.audio

import android.text.TextUtils
import android.util.TypedValue
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import eu.kanade.presentation.audio.components.AudioFolderRow
import eu.kanade.presentation.audio.components.AudioTrackRow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TitleSelectionAction
import eu.kanade.presentation.components.TitleSelectionCallback
import eu.kanade.presentation.components.TitleSelectionController
import eu.kanade.presentation.components.clearTextSelection
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.TagRef
import eu.kanade.tachiyomi.data.audio.TrackNode
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.audio.WorkEdition
import eu.kanade.tachiyomi.ui.audio.AudioDetailState
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioDetailContent(
    work: Work,
    state: AudioDetailState,
    playlistUrls: Set<String>,
    isFavorite: Boolean,
    bottomBar: @Composable () -> Unit,
    navigateUp: () -> Unit,
    onClickHome: () -> Unit,
    onRetry: () -> Unit,
    onClickTrack: (Int) -> Unit,
    onTogglePlaylist: (AudioPlayItem) -> Unit,
    onToggleWorkPlaylist: () -> Unit,
    onToggleFolderPlaylist: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onTitleSearch: (String) -> Unit,
    onClickCircle: (String) -> Unit,
    onClickVa: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickNeighbor: (Work) -> Unit,
    onClickEdition: (WorkEdition) -> Unit,
    onToggleNeighbors: () -> Unit,
) {
    var expanded by remember(state.rootNodes) { mutableStateOf(emptySet<String>()) }
    val audioIndexByUrl = remember(state.flatTracks) {
        state.flatTracks.mapIndexed { index, item -> item.mediaStreamUrl to index }.toMap()
    }
    val visibleRows = remember(state.rootNodes, expanded) {
        buildVisibleRows(state.rootNodes, expanded)
    }

    // 标题选区由原生 TextView 的 ActionMode 管理，与漫画详情页一致：返回键走 BackHandler，
    // 点标题外走 Scaffold 上的 pointerInput（只在选区激活时运行，点标题内让位、点标题外 clear）。
    val titleSelection = remember { TitleSelectionController() }
    BackHandler(enabled = titleSelection.isActive) { titleSelection.clear() }

    Scaffold(
        modifier = Modifier.pointerInput(titleSelection.isActive) {
            if (!titleSelection.isActive) return@pointerInput
            awaitEachGesture {
                if (titleSelection.isOutsideTitle(awaitFirstDown(requireUnconsumed = false).position)) {
                    titleSelection.clear()
                }
            }
        },
        topBar = { scrollBehavior ->
            AppBar(
                // No title: the header below already carries the work title, and a static
                // section name here repeated what the back stack makes obvious.
                title = null,
                navigateUp = navigateUp,
                navigationActions = {
                    // Landing on the details from the reader or a notification leaves no ASMR
                    // home underneath, so offer an explicit way into it. Sits next to the up
                    // button because it is a navigation target, not a command on this work.
                    IconButton(onClick = onClickHome) {
                        Icon(
                            Icons.Outlined.Home,
                            contentDescription = stringResource(MR.strings.audio_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.flatTracks.isNotEmpty()) {
                        val queuedCount = state.flatTracks.count { it.mediaStreamUrl in playlistUrls }
                        val allQueued = queuedCount == state.flatTracks.size
                        IconButton(onClick = onToggleWorkPlaylist) {
                            Icon(
                                imageVector = if (allQueued) {
                                    Icons.AutoMirrored.Outlined.PlaylistAddCheck
                                } else {
                                    Icons.AutoMirrored.Outlined.PlaylistAdd
                                },
                                contentDescription = stringResource(
                                    if (allQueued) {
                                        MR.strings.audio_playlist_remove_work
                                    } else {
                                        MR.strings.audio_add_work_to_playlist
                                    },
                                ),
                                tint = if (queuedCount > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) {
                                    Icons.Outlined.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = stringResource(
                                    if (isFavorite) MR.strings.audio_favorite_remove else MR.strings.audio_favorite_add,
                                ),
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { contentPadding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(contentPadding))
            state.error -> {
                val message = state.errorMessage?.let { "${stringResource(MR.strings.audio_load_failed)}: $it" }
                    ?: stringResource(MR.strings.audio_load_failed)
                EmptyScreen(
                    message = message,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.audio_retry,
                            icon = Icons.Outlined.Refresh,
                            onClick = onRetry,
                        ),
                    ),
                )
            }
            state.rootNodes.isEmpty() -> EmptyScreen(
                stringRes = MR.strings.audio_no_tracks,
                modifier = Modifier.padding(contentPadding),
            )
            else -> {
                val headerAndTracks: LazyListScope.() -> Unit = {
                    item {
                        WorkHeader(
                            work = work,
                            editions = state.editions,
                            titleSelection = titleSelection,
                            onTitleSearch = onTitleSearch,
                            onClickCircle = onClickCircle,
                            onClickVa = onClickVa,
                            onClickTag = onClickTag,
                            onClickEdition = onClickEdition,
                        )
                        HorizontalDivider()
                    }
                    items(visibleRows, key = { it.key }) { row ->
                        if (row.isFolder) {
                            val folderTracks = state.flatTracks.filter { it.folderPath == row.key }
                            val allQueued = folderTracks.isNotEmpty() &&
                                folderTracks.all { it.mediaStreamUrl in playlistUrls }
                            AudioFolderRow(
                                title = row.title,
                                expanded = row.key in expanded,
                                depth = row.depth,
                                onClick = {
                                    expanded = if (row.key in expanded) expanded - row.key else expanded + row.key
                                },
                                actions = {
                                    PlaylistToggleIcon(
                                        added = allQueued,
                                        onClick = { onToggleFolderPlaylist(row.key) },
                                    )
                                },
                            )
                        } else {
                            val index = audioIndexByUrl[row.audioUrl] ?: -1
                            AudioTrackRow(
                                title = row.title,
                                durationMs = row.durationMs,
                                depth = row.depth,
                                onClick = { if (index >= 0) onClickTrack(index) },
                                actions = {
                                    PlaylistToggleIcon(
                                        added = row.audioUrl in playlistUrls,
                                        onClick = {
                                            state.flatTracks.getOrNull(index)?.let(onTogglePlaylist)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                // One layout for both states: the row is the last item of the list and opens
                // downward into the cards, so expanding it never moves the folders above it and
                // there is no layout switch to jump through.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    headerAndTracks()
                    if (state.neighbors.isNotEmpty()) {
                        item {
                            NeighborRow(
                                works = state.neighbors,
                                expanded = state.neighborsExpanded,
                                onToggle = onToggleNeighbors,
                                onClick = onClickNeighbor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The works the backend considers similar, as one line under the track list.
 *
 * At the bottom on purpose: this page is a work and its tracks, and this row is what a reader who
 * has scrolled to the end is looking for next. It is set off as a block of its own rather than as
 * more rows of the list: the same divider the header uses opens it, the gap above is wider than any
 * gap inside the track list, and the label is a quiet caption instead of a second title. Without
 * those three a short track list left the row reading as its last two entries.
 */
@Composable
private fun NeighborRow(
    works: List<Work>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: (Work) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        // A wider gap than any row inside the track list: on a short work this divider is what
        // separates "the tracks end here" from "what comes next", so it needs room to read as a
        // break rather than as one more row spacing.
        HorizontalDivider(modifier = Modifier.padding(top = 48.dp))
        // The header is the whole control: a row of cards this tall pushed the track list off the
        // page on first open, so it starts folded and the count says what is behind it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.audio_similar_works),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = works.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(works, key = { it.id }) { work ->
                    NeighborCard(work = work, onClick = { onClick(work) })
                }
            }
        }
    }
}

/**
 * One card of the similar row: cover on top, title under it.
 *
 * Deliberately not the grid card. That one puts the cover beside the text and sizes both from a
 * fixed card height, so at this width the cover takes most of the card and leaves the title a strip
 * too narrow to read a work's name in. Stacking the two hands the title the full card width, which
 * is what titles this long need.
 */
@Composable
private fun NeighborCard(work: Work, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(NEIGHBOR_CARD_WIDTH)
            .clip(RoundedCornerShape(NEIGHBOR_CARD_CORNER))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl,
            contentDescription = work.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(NEIGHBOR_CARD_CORNER)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = work.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = NEIGHBOR_TITLE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = work.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (work.tags.isNotEmpty()) {
            TwoRowTagFlow(
                tags = work.tags,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The tags of a neighbour card: two tight rows that scroll sideways as one.
 *
 * Laying them out as columns of two left a gap under every short tag, because a column is as wide
 * as its widest chip. This measures the chips itself and packs each row to its own content, so
 * the second row starts directly under the first and the pair scrolls together.
 */
@Composable
private fun TwoRowTagFlow(tags: List<TagRef>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    Layout(
        content = {
            tags.forEach { tag ->
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        },
        modifier = modifier
            // Outside the scroll, not inside: onPostScroll runs inner-to-outer, so this connection
            // only sees the drag after the tag row has reached its edge, and swallowing what is
            // left there keeps the card row behind from starting to move. Vertical passes through.
            .nestedScroll(
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset = Offset(available.x, 0f)
                },
            )
            .horizontalScroll(scrollState),
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val rows = List(NEIGHBOR_TAG_LINES) { mutableListOf<Placeable>() }
        placeables.forEachIndexed { index, placeable ->
            rows[index % NEIGHBOR_TAG_LINES].add(placeable)
        }
        val horizontalGap = 4.dp.roundToPx()
        val verticalGap = 2.dp.roundToPx()
        val rowWidths = rows.map { row ->
            row.sumOf { it.width } + horizontalGap * (row.size - 1).coerceAtLeast(0)
        }
        val rowHeights = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
        layout(rowWidths.maxOrNull() ?: 0, rowHeights.sum() + verticalGap) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + horizontalGap
                }
                y += rowHeights[rowIndex] + verticalGap
            }
        }
    }
}

/** Narrow enough that the next card peeks in, wide enough for two lines of a long title. */
private val NEIGHBOR_CARD_WIDTH = 150.dp

private val NEIGHBOR_CARD_CORNER = 6.dp

private const val NEIGHBOR_TITLE_MAX_LINES = 2

/** Chips stack two deep per scroll column: one row would show a single tag in a 150 dp card. */
private const val NEIGHBOR_TAG_LINES = 2

/** One line of tag chips: fixed so the scrollable row measures against a known height. */
private val TAG_ROW_HEIGHT = 28.dp

private data class TreeRow(
    val key: String,
    val depth: Int,
    val isFolder: Boolean,
    val title: String,
    val durationMs: Long = 0,
    val audioUrl: String? = null,
)

private fun buildVisibleRows(nodes: List<TrackNode>, expanded: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()

    fun walk(list: List<TrackNode>, depth: Int, parentKey: String) {
        list.forEach { node ->
            val path = if (parentKey.isEmpty()) node.title else "$parentKey/${node.title}"
            when (node.type) {
                "folder" -> {
                    if (node.hasPlayableAudio()) {
                        rows += TreeRow(key = path, depth = depth, isFolder = true, title = node.title)
                        if (path in expanded) walk(node.children, depth + 1, path)
                    }
                }
                "audio" -> {
                    val url = node.mediaStreamUrl
                    if (!url.isNullOrBlank()) {
                        rows += TreeRow(
                            key = "audio:$url",
                            depth = depth,
                            isFolder = false,
                            title = node.title,
                            durationMs = ((node.duration ?: 0.0) * 1000).toLong(),
                            audioUrl = url,
                        )
                    }
                }
            }
        }
    }
    walk(nodes, 0, "")
    return rows
}

private fun TrackNode.hasPlayableAudio(): Boolean {
    return (type == "audio" && !mediaStreamUrl.isNullOrBlank()) || children.any { it.hasPlayableAudio() }
}

@Composable
private fun WorkHeader(
    work: Work,
    editions: List<WorkEdition>,
    titleSelection: TitleSelectionController,
    onTitleSearch: (String) -> Unit,
    onClickCircle: (String) -> Unit,
    onClickVa: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickEdition: (WorkEdition) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = work.mainCoverUrl ?: work.thumbnailCoverUrl ?: work.samCoverUrl,
                contentDescription = work.title,
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                SelectableWorkTitle(
                    title = work.title,
                    titleSelection = titleSelection,
                    onTitleSearch = onTitleSearch,
                )
                Text(
                    text = workMeta(work),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Circle and cast on one line: they are the same kind of fact — who made it — and the two
        // separate rows cost a line each without telling the reader anything extra.
        if (work.name.isNotBlank() || work.vas.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (work.name.isNotBlank()) {
                    Text(
                        text = work.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.combinedClickable(
                            onClick = { onClickCircle(work.name) },
                            onLongClick = { context.copyToClipboard(work.name, work.name) },
                        ),
                    )
                }
                work.vas.forEachIndexed { index, va ->
                    if (work.name.isNotBlank() || index > 0) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = va.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onClickVa(va.name) },
                    )
                }
            }
        }

        // Only works with more than one edition get this row: a single-language work would show
        // one chip that cannot be tapped, which is noise under every title.
        if (editions.size > 1) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                editions.forEach { edition ->
                    val isCurrent = edition.isCurrent
                    Text(
                        text = edition.lang,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable(enabled = !isCurrent) { onClickEdition(edition) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (work.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // One scrollable line rather than a wrapping block: a work can carry a dozen tags, and
            // wrapping them pushed the track list down by a second and third row. Same shape as the
            // tag line along the bottom of a browse card.
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TAG_ROW_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(work.tags.size) { index ->
                    TagChip(work.tags[index].name, onClick = { onClickTag(work.tags[index].name) })
                }
            }
        }
    }
}

/**
 * 标题选区交给原生 TextView，缘由与漫画详情页相同（MangaContentInfo 处有完整说明）：
 * Compose 的 SelectionContainer 在拖动手柄越过 TextLayoutResult 的排版边界时会整段误选，
 * 原生 TextView 的斜拖、跨行、越界回收都是系统标准行为。长按进入框选，菜单固定为
 * 复制 → 搜索 ASMR（作用于框选的文本）；单击仍是搜索整个标题。
 */
@Composable
private fun SelectableWorkTitle(
    title: String,
    titleSelection: TitleSelectionController,
    onTitleSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    // 跟随系统语言，和原生 TextView 的选区菜单保持一致。
    val copyLabel = remember(context) { context.getString(android.R.string.copy) }
    val searchLabel = stringResource(MR.strings.audio_title_search)
    val titleStyle = MaterialTheme.typography.titleMedium
    val titleColor = LocalContentColor.current.toArgb()

    // AndroidView 的 factory 只跑一次，里面的回调必须拿到最新的搜索 lambda，
    // 否则重组之后会跳到过期的页面状态。
    val currentTitleSearch by rememberUpdatedState(onTitleSearch)

    DisposableEffect(titleSelection) {
        onDispose { titleSelection.unbindClearAction() }
    }

    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextIsSelectable(true)
                // 关掉 smart text selection 的分类建议：标题只显示文本，用不到 URL/电话等
                // 智能识别。ROM 自己注入的 assist 项由 TitleSelectionCallback 删除。
                setTextClassifier(TextClassifier.NO_OP)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                includeFontPadding = false
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
        },
        update = { titleView ->
            titleView.text = title
            titleView.setTextColor(titleColor)
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleStyle.fontSize.value)
            // 选区还在（ActionMode 挂着）时不跳搜索：这一下点击只是用来收掉选区的。
            // 用 ActionMode 的存活状态而不是 hasSelection() 判断，因为系统会在派发
            // click 之前就把选区清掉，那时 hasSelection() 已经是 false，会误触发跳转。
            titleView.setOnClickListener {
                if (title.isNotBlank() && !titleSelection.isActive) {
                    currentTitleSearch(title)
                }
            }
            titleView.customSelectionActionModeCallback = TitleSelectionCallback(
                textView = titleView,
                actions = listOf(
                    TitleSelectionAction(AUDIO_TITLE_ACTION_COPY, copyLabel) { text ->
                        context.copyToClipboard(text, text)
                    },
                    TitleSelectionAction(AUDIO_TITLE_ACTION_SEARCH, searchLabel) { text ->
                        currentTitleSearch(text)
                    },
                ),
                onActionModeCreated = titleSelection::bindActionMode,
                onActionModeDestroyed = { titleSelection.bindActionMode(null) },
                onSelectionActiveChange = titleSelection::setActive,
            )
            titleSelection.bindClearAction { titleView.clearTextSelection() }
        },
        modifier = Modifier
            // 按文字实际宽度收拢：撑满整行时，短标题右侧那片空白也会算进点击区。长标题
            // 仍受父级宽度约束，正常换行。
            .wrapContentWidth(align = Alignment.Start)
            // 上报标题在窗口里的位置，供外层「点外部清选区」判断按下点是否落在标题内。
            .onGloballyPositioned { titleSelection.updateTitleRect(it.boundsInWindow()) },
    )
}

private const val AUDIO_TITLE_ACTION_COPY = 1
private const val AUDIO_TITLE_ACTION_SEARCH = 2

@Composable
private fun TagChip(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Shared by folder and track rows: adds the whole folder, or the single track, to the playlist. */
@Composable
private fun PlaylistToggleIcon(
    added: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (added) {
                Icons.AutoMirrored.Outlined.PlaylistAddCheck
            } else {
                Icons.AutoMirrored.Outlined.PlaylistAdd
            },
            contentDescription = stringResource(
                if (added) {
                    MR.strings.audio_playlist_remove
                } else {
                    MR.strings.audio_add_to_playlist
                },
            ),
            tint = if (added) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
