package eu.kanade.presentation.browse.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
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
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdate
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

@Composable
fun BrowseSourceList(
    mangaList: LazyPagingItems<BrowseSourceUiModel>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    showIndex: Boolean = false,
    lastReadMangaId: Long? = null,
    locateMangaId: Long? = null,
    favoriteIds: Set<Long>? = null,
    progressContext: BrowseSourceViewModel.ProgressContext = BrowseSourceViewModel.ProgressContext(
        emptyMap(),
        emptyMap(),
        emptyMap(),
    ),
    coverUpdates: Map<Long, MangaCoverUpdate> = emptyMap(),
    trailingSlotCount: Int = 0,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    onLocateMangaHandled: () -> Unit = {},
    scrollToTopRequest: Long = 0L,
    onRandomManga: (() -> Unit)? = null,
    onRandomGoodDoujin: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val locateTransition = rememberBrowseSourceLocateTransition()
    val leadingItemCount = if (mangaList.loadState.prepend is LoadState.Loading) 1 else 0
    val headerAwareIndex = showIndex && mangaList.hasDateHeaders()
    var isLocating by remember { mutableStateOf(false) }
    var isThumbDragging by remember { mutableStateOf(false) }
    val isLoadingPaused = isLocating || isThumbDragging
    var handledLocateManga by remember { mutableStateOf<Long?>(null) }
    var handledScrollToTopRequest by remember { mutableStateOf(scrollToTopRequest) }

    // Skeleton slots standing in for the page that has not landed yet. They keep the listing
    // long enough to drag the scroller from one end to the other in a single gesture, and
    // reading one is what pulls that page in.
    val appendEnded = (mangaList.loadState.append as? LoadState.NotLoading)?.endOfPaginationReached == true
    val trailingSlots = if (appendEnded || mangaList.loadState.refresh is LoadState.Loading) {
        0
    } else {
        trailingSlotCount
    }

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
        // The skeleton slots give the scroller room to travel past the last loaded entry, but
        // nothing in them reads the pager, so Paging never gets the hint on its own - landing
        // straight on one leaves it a skeleton forever. Fire on the viewport reaching the end
        // of what is loaded, the cue Paging would follow anyway, so one trip past the end
        // pulls exactly one more page instead of racing to the end of the list.
        LaunchedEffect(listState, mangaList, trailingSlots) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { lastVisible ->
                    if (trailingSlots > 0 &&
                        mangaList.itemCount > 0 &&
                        lastVisible >= mangaList.itemCount - 1
                    ) {
                        mangaList[mangaList.itemCount - 1]
                    }
                }
        }

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
                key = { index -> mangaList.peekKey(index) },
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
                            index = when {
                                !showIndex -> null
                                headerAwareIndex -> mangaList.mangaNumberAt(index)
                                else -> index + 1
                            },
                            favoriteIds = favoriteIds,
                            loadCover = !isLoadingPaused,
                            progress = progressContext.progressFor(manga.id, manga.url),
                            isLastRead = progressContext.lastReadMangaId == manga.id,
                            coverUpdates = coverUpdates,
                            highlightedMangaId = highlightedMangaId,
                            highlightAlpha = highlightAlpha,
                            onClick = { onMangaClick(manga) },
                            onLongClick = { onMangaLongClick(manga) },
                        )
                    }
                    null -> {
                        BrowseSourceListItemPlaceholder()
                    }
                }
            }

            if (mangaList.loadState.refresh is LoadState.Loading) {
                item {
                    BrowseSourceLoadingItem()
                }
            }

            // An append in flight is shown on the first slot rather than on a row of its own:
            // a row that comes and goes on every page load changes the length of the list, and
            // the scroller reads that as the content moving underneath the thumb.
            items(
                count = trailingSlots,
                key = { index -> BROWSE_SOURCE_TRAILING_SLOT_KEY_PREFIX + index },
            ) { index ->
                BrowseSourceTrailingSlot(
                    showSpinner = index == 0 && mangaList.loadState.append is LoadState.Loading,
                ) {
                    BrowseSourceListItemPlaceholder()
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
    progress: MangaProgress = MangaProgress.EMPTY,
    isLastRead: Boolean = false,
    coverUpdates: Map<Long, MangaCoverUpdate> = emptyMap(),
    highlightedMangaId: Long? = null,
    highlightAlpha: Animatable<Float, *> = Animatable(0f),
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    val manga = item.manga
    val isFavorite = if (favoriteIds != null) manga.id in favoriteIds else manga.favorite
    BrowseSourceHighlightBorder(
        mangaId = manga.id,
        highlightedMangaId = highlightedMangaId,
        highlightAlpha = highlightAlpha,
    ) {
        MangaListItem(
            title = manga.title,
            coverData = manga.asDisplayedCover(coverUpdates),
            coverAlpha = if (isFavorite) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f,
            loadCover = loadCover,
            leading = {
                if (index != null) {
                    IndexLabel(index = index)
                }
            },
            badge = {
                InLibraryBadge(enabled = isFavorite)
                if (isLastRead) {
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
