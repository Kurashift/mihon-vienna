package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdate
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceUiModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.util.system.showSnackbarReplacing
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<BrowseSourceUiModel>,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
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
    listKey: Any? = null,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onLocalSourceHelpClick: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
    selectedIds: Set<Long> = emptySet(),
    dimInLibraryCovers: Boolean = true,
    onRefreshChapters: (() -> Unit)? = null,
    onLocateMangaHandled: () -> Unit = {},
    scrollToTopRequest: Long = 0L,
    onRandomManga: (() -> Unit)? = null,
    onRandomGoodDoujin: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbarReplacing(
                message = getErrorMessage(errorState),
                actionLabel = context.stringResource(MR.strings.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.retry()
            }
        }
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (mangaList.itemCount == 0) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = when (errorState) {
                is LoadState.Error -> getErrorMessage(errorState)
                else -> stringResource(MR.strings.no_results_found)
            },
            actions = if (source is LocalSource) {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_refresh_all_chapters,
                        icon = Icons.Outlined.Refresh,
                        onClick = onRefreshChapters ?: {},
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.local_source_help_guide,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOf(
                    EmptyScreenAction(
                        stringRes = MR.strings.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = mangaList::refresh,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.action_open_in_web_view,
                        icon = Icons.Outlined.Public,
                        onClick = onWebViewClick,
                    ),
                    EmptyScreenAction(
                        stringRes = MR.strings.label_help,
                        icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        onClick = onHelpClick,
                    ),
                )
            },
        )

        return
    }

    // Held here rather than inside the branch so switching the display mode keeps the offset
    // instead of dropping the reader back to the top of the listing.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    // A swap replaces the result set underneath a list that is still showing the previous one. The
    // pager serves the page it had cached and only then the refreshed one, and the lazy layout
    // follows the key of the first visible entry across each replacement, carrying the reader to
    // whatever position that entry now occupies - the list opens at its top and then springs to the
    // end of the first page, with no scroll involved. Asking for the top on each frame also forgets
    // that key, so a replacement has nothing left to follow.
    //
    // Only a swap this screen was around to see counts as one. Coming back to the tab is not a
    // swap: nothing has been observed yet, so the offset restored on the way in is left alone - it
    // is exactly what the reader came back for.
    var observedListKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(listKey) {
        val previous = observedListKey
        observedListKey = listKey
        if (previous == null || previous == listKey) return@LaunchedEffect

        var settledFrames = 0
        var lastCount = -1
        repeat(SWAP_SETTLE_MAX_FRAMES) {
            withFrameNanos { }
            // Scrolling means the reader has taken over; the position is theirs from here on.
            if (gridState.isScrollInProgress || listState.isScrollInProgress) return@LaunchedEffect
            val count = mangaList.itemCount
            // An empty list is not content that settled, it is content that has not arrived yet.
            settledFrames = if (count in 1..lastCount) settledFrames + 1 else 0
            lastCount = count
            if (settledFrames >= SWAP_SETTLED_FRAMES) return@LaunchedEffect
            gridState.requestScrollToItem(0, 0)
            listState.requestScrollToItem(0, 0)
        }
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                gridState = gridState,
                contentPadding = contentPadding,
                showIndex = source is LocalSource,
                lastReadMangaId = lastReadMangaId,
                locateMangaId = locateMangaId,
                favoriteIds = favoriteIds,
                progressContext = progressContext,
                coverUpdates = coverUpdates,
                trailingSlotCount = trailingSlotCount,
                listKey = listKey,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                selectedIds = selectedIds,
                dimInLibraryCovers = dimInLibraryCovers,
                onLocateMangaHandled = onLocateMangaHandled,
                scrollToTopRequest = scrollToTopRequest,
                onRandomManga = onRandomManga,
                onRandomGoodDoujin = onRandomGoodDoujin,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                listState = listState,
                contentPadding = contentPadding,
                showIndex = source is LocalSource,
                lastReadMangaId = lastReadMangaId,
                locateMangaId = locateMangaId,
                favoriteIds = favoriteIds,
                progressContext = progressContext,
                coverUpdates = coverUpdates,
                trailingSlotCount = trailingSlotCount,
                listKey = listKey,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                selectedIds = selectedIds,
                dimInLibraryCovers = dimInLibraryCovers,
                onLocateMangaHandled = onLocateMangaHandled,
                scrollToTopRequest = scrollToTopRequest,
                onRandomManga = onRandomManga,
                onRandomGoodDoujin = onRandomGoodDoujin,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                gridState = gridState,
                contentPadding = contentPadding,
                showIndex = source is LocalSource,
                lastReadMangaId = lastReadMangaId,
                locateMangaId = locateMangaId,
                favoriteIds = favoriteIds,
                progressContext = progressContext,
                coverUpdates = coverUpdates,
                trailingSlotCount = trailingSlotCount,
                listKey = listKey,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
                selectedIds = selectedIds,
                dimInLibraryCovers = dimInLibraryCovers,
                onLocateMangaHandled = onLocateMangaHandled,
                scrollToTopRequest = scrollToTopRequest,
                onRandomManga = onRandomManga,
                onRandomGoodDoujin = onRandomGoodDoujin,
            )
        }
    }
}

/**
 * Frames the presented count has to stand still for before a swap counts as delivered. Pages keep
 * arriving every few frames while one is loading, so this only elapses once they stop.
 */
private const val SWAP_SETTLED_FRAMES = 15

/**
 * Hard cap on holding a swapped listing at its top, so one that keeps streaming pages cannot be
 * held there indefinitely. Every frame of it is skipped once the reader scrolls.
 */
private const val SWAP_SETTLE_MAX_FRAMES = 60

@Composable
internal fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(MR.strings.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
