package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.components.LocalBottomNavFabPadding
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.IndexLabel
import eu.kanade.presentation.library.components.LastReadBadge
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.ProgressBadge
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    mangaList: LazyPagingItems<BrowseSourceUiModel>,
    contentPadding: PaddingValues,
    showIndex: Boolean = false,
    lastReadMangaId: Long? = null,
    locateMangaId: Long? = null,
    favoriteIds: Set<Long>? = null,
    progressFor: (mangaId: Long, url: String) -> MangaProgress = { _, _ -> MangaProgress.EMPTY },
    isLastRead: (mangaId: Long) -> Boolean = { false },
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    onLocateMangaHandled: () -> Unit = {},
    scrollToTopRequest: Long = 0L,
    onRandomManga: (() -> Unit)? = null,
    onRandomGoodDoujin: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val locateTransition = rememberBrowseSourceLocateTransition()
    val leadingItemCount = if (mangaList.loadState.prepend is LoadState.Loading) 1 else 0
    var isLocating by remember { mutableStateOf(false) }
    var isThumbDragging by remember { mutableStateOf(false) }
    val isLoadingPaused = isLocating || isThumbDragging
    var handledLocateManga by remember { mutableStateOf<Long?>(null) }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }

    // Brief highlight on the located last-read manga; fades out cleanly on its own.
    var highlightedMangaId by remember { mutableStateOf<Long?>(null) }
    val highlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlightedMangaId) {
        val target = highlightedMangaId ?: return@LaunchedEffect
        highlightAlpha.snapTo(1f)
        delay(HIGHLIGHT_HOLD_MILLIS)
        highlightAlpha.animateTo(0f, tween(HIGHLIGHT_FADE_OUT_MILLIS))
        highlightedMangaId = null
    }

    LaunchedEffect(locateMangaId, mangaList.itemCount) {
        val target = locateMangaId ?: return@LaunchedEffect
        if (handledLocateManga == target) return@LaunchedEffect

        val index = mangaList.itemSnapshotList.items.indexOfFirst {
            it is BrowseSourceUiModel.Item && it.manga.id == target
        }
        if (index < 0) return@LaunchedEffect

        handledLocateManga = target
        isLocating = true
        try {
            listState.smoothLocateToItem(index + leadingItemCount, locateTransition)
        } finally {
            isLocating = false
        }
        onLocateMangaHandled()
    }

    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest != handledScrollToTopRequest) {
            handledScrollToTopRequest = scrollToTopRequest
            isLocating = true
            try {
                listState.smoothLocateToItem(0, locateTransition)
            } finally {
                isLocating = false
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        BrowseSourceLazyColumn(
            fastScroll = showIndex,
            state = listState,
            modifier = Modifier.browseSourceLocateTransition(locateTransition),
            onThumbDraggedChanged = { isThumbDragging = it },
            contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
        ) {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                item {
                    BrowseSourceLoadingItem()
                }
            }

            items(
                count = mangaList.itemCount,
                key = { index ->
                    when (val item = mangaList.itemSnapshotList[index]) {
                        is BrowseSourceUiModel.Header -> "latest-header-${item.timestamp}"
                        is BrowseSourceUiModel.Item -> item.manga.id
                        null -> -index - 1L
                    }
                },
            ) { index ->
                val item = if (isLoadingPaused) mangaList.peek(index) else mangaList[index]
                when (item) {
                    is BrowseSourceUiModel.Header -> {
                        BrowseSourceDateHeader(text = relativeDateText(item.timestamp))
                    }
                    is BrowseSourceUiModel.Item -> {
                        val manga = item.manga
                        BrowseSourceListItem(
                            item = item,
                            index = if (showIndex) mangaList.mangaNumberAt(index) else null,
                            favoriteIds = favoriteIds,
                            loadCover = !isLoadingPaused,
                            progressFor = progressFor,
                            isLastRead = isLastRead,
                            highlightColor = if (highlightedMangaId == manga.id) {
                                MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha.value)
                            } else {
                                Color.Transparent
                            },
                            onClick = { onMangaClick(manga) },
                            onLongClick = { onMangaLongClick(manga) },
                        )
                    }
                    null -> {
                        BrowseSourceListItemPlaceholder()
                    }
                }
            }

            item {
                if (mangaList.loadState.refresh is LoadState.Loading ||
                    mangaList.loadState.append is LoadState.Loading
                ) {
                    BrowseSourceLoadingItem()
                }
            }
        }

        BrowseSourceLastReadFab(
            mangaList = mangaList,
            lastReadMangaId = lastReadMangaId,
            scrollState = listState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                )
                .padding(
                    start = 16.dp,
                    bottom = 16.dp + LocalBottomNavFabPadding.current,
                ),
            visibleItemsRange = remember(listState, leadingItemCount) {
                snapshotFlow {
                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) {
                        null
                    } else {
                        val first = (visibleItems.first().index - leadingItemCount).coerceAtLeast(0)
                        val last = (visibleItems.last().index - leadingItemCount).coerceAtLeast(first)
                        first..last
                    }
                }.distinctUntilChanged()
            },
            onScrollToIndex = { index ->
                scope.launch {
                    if (isLocating) return@launch
                    highlightedMangaId = lastReadMangaId
                    isLocating = true
                    try {
                        listState.smoothLocateToItem(index + leadingItemCount, locateTransition)
                    } finally {
                        isLocating = false
                    }
                }
            },
            onOpenManga = onMangaClick,
            onRandomManga = onRandomManga,
            onRandomGoodDoujin = onRandomGoodDoujin,
        )
    }
}

@Composable
private fun BrowseSourceListItem(
    item: BrowseSourceUiModel.Item,
    index: Int? = null,
    favoriteIds: Set<Long>? = null,
    loadCover: Boolean = true,
    progressFor: (mangaId: Long, url: String) -> MangaProgress = { _, _ -> MangaProgress.EMPTY },
    isLastRead: (mangaId: Long) -> Boolean = { false },
    highlightColor: Color = Color.Transparent,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val manga = item.manga
    val progress = progressFor(manga.id, manga.url)
    val isFavorite = if (favoriteIds != null) manga.id in favoriteIds else manga.favorite
    Box(
        modifier = Modifier.border(
            width = 3.dp,
            color = highlightColor,
            shape = RoundedCornerShape(8.dp),
        ),
    ) {
        MangaListItem(
            title = manga.title,
            coverData = MangaCover(
                mangaId = manga.id,
                sourceId = manga.source,
                isMangaFavorite = manga.favorite,
                url = manga.thumbnailUrl,
                lastModified = manga.coverLastModified,
            ),
            coverAlpha = if (isFavorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
            loadCover = loadCover,
            leading = {
                if (index != null) {
                    IndexLabel(index = index)
                }
            },
            badge = {
                InLibraryBadge(enabled = isFavorite)
                if (isLastRead(manga.id)) {
                    LastReadBadge()
                }
                ProgressBadge(
                    finishedCount = progress.finishedCount,
                    totalChapters = progress.totalChapters,
                    filled = false,
                )
            },
            subtitle = item.matchedChapter?.let { chapter ->
                stringResource(MR.strings.browse_source_matched_chapter, chapter)
            },
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

private const val HIGHLIGHT_HOLD_MILLIS = 1200L
private const val HIGHLIGHT_FADE_OUT_MILLIS = 350
