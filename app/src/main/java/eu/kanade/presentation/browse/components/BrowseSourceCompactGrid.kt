package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.IndexLabel
import eu.kanade.presentation.library.components.LastReadBadge
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.ProgressBadge
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceCompactGrid(
    mangaList: LazyPagingItems<BrowseSourceUiModel>,
    columns: GridCells,
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
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val locateTransition = rememberBrowseSourceLocateTransition()
    val leadingItemCount = if (mangaList.loadState.prepend is LoadState.Loading) 1 else 0
    var isLocating by remember { mutableStateOf(false) }
    var isThumbDragging by remember { mutableStateOf(false) }
    val isLoadingPaused = isLocating || isThumbDragging
    var handledLocateManga by remember { mutableStateOf<Long?>(null) }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }

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
            gridState.smoothLocateToItem(index + leadingItemCount, locateTransition)
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
                gridState.smoothLocateToItem(0, locateTransition)
            } finally {
                isLocating = false
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        BrowseSourceLazyVerticalGrid(
            fastScroll = showIndex,
            state = gridState,
            columns = columns,
            modifier = Modifier.browseSourceLocateTransition(locateTransition),
            onThumbDraggedChanged = { isThumbDragging = it },
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
            horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        ) {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BrowseSourceLoadingItem()
                }
            }

            items(
                count = mangaList.itemCount,
                span = { index ->
                    if (mangaList.itemSnapshotList[index] is BrowseSourceUiModel.Header) {
                        GridItemSpan(maxLineSpan)
                    } else {
                        GridItemSpan(1)
                    }
                },
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
                        BrowseSourceCompactGridItem(
                            item = item,
                            index = if (showIndex) mangaList.mangaNumberAt(index) else null,
                            favoriteIds = favoriteIds,
                            loadCover = !isLoadingPaused,
                            progressFor = progressFor,
                            isLastRead = isLastRead,
                            onClick = { onMangaClick(manga) },
                            onLongClick = { onMangaLongClick(manga) },
                        )
                    }
                    null -> {
                        BrowseSourceCompactGridItemPlaceholder()
                    }
                }
            }

            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BrowseSourceLoadingItem()
                }
            }
        }

        BrowseSourceLastReadFab(
            mangaList = mangaList,
            lastReadMangaId = lastReadMangaId,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start),
                )
                .padding(16.dp),
            visibleItemsRange = remember(gridState, leadingItemCount) {
                snapshotFlow {
                    val visibleItems = gridState.layoutInfo.visibleItemsInfo
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
                    isLocating = true
                    try {
                        gridState.smoothLocateToItem(index + leadingItemCount, locateTransition)
                    } finally {
                        isLocating = false
                    }
                }
            },
            onOpenManga = onMangaClick,
        )
    }
}

@Composable
private fun BrowseSourceCompactGridItem(
    item: BrowseSourceUiModel.Item,
    index: Int? = null,
    favoriteIds: Set<Long>? = null,
    loadCover: Boolean = true,
    progressFor: (mangaId: Long, url: String) -> MangaProgress = { _, _ -> MangaProgress.EMPTY },
    isLastRead: (mangaId: Long) -> Boolean = { false },
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val manga = item.manga
    val progress = progressFor(manga.id, manga.url)
    val isFavorite = if (favoriteIds != null) manga.id in favoriteIds else manga.favorite
    MangaCompactGridItem(
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
        coverBadgeStart = {
            if (index != null) {
                IndexLabel(
                    index = index,
                    style = MaterialTheme.typography.labelSmall.copy(color = Color.White),
                    background = Color.Black.copy(alpha = 0.55f),
                )
            }
        },
        coverBadgeBottomStart = {
            InLibraryBadge(enabled = isFavorite)
        },
        coverBadgeEnd = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (item.matchedChapter != null) {
                    MatchedChapterBadge(chapter = item.matchedChapter)
                }
                ProgressBadge(
                    finishedCount = progress.finishedCount,
                    totalChapters = progress.totalChapters,
                )
            }
        },
        coverBadgeBottomEnd = {
            if (isLastRead(manga.id)) {
                LastReadBadge()
            }
        },
        badgesStartFlush = true,
        badgesBottomEndFlush = true,
        badgesBottomStartFlush = true,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
