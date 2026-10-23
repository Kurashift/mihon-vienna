package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.browse.components.presentedListGeneration
import eu.kanade.presentation.components.ADAPTIVE_MIN_CELL_WIDTH
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.GRID_CONTENT_PADDING
import eu.kanade.presentation.components.PinchColumns
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
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
    columns: Int,
    onColumnsCommit: (Int) -> Unit,
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
    // True when this list is replaced whole rather than grown a page at a time; see
    // [peekKey] for why that changes how a row is identified.
    wholeList: Boolean = false,
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

    // Which list the rows on screen belong to - read off those rows, not taken from the tap that
    // asked for them and not from the moment the list was produced. Both of those are earlier than
    // the rows arriving, so acting on them reset the list to its top while the previous cards were
    // still on screen: it jumped first and filled in afterwards.
    //
    // The generation in the rows is a one-way counter: it says the rows changed, not what they
    // became, so the listing requested at that moment names them. Keeping that pairing means a
    // listing the reader comes back to - clearing a search - resolves to the identity it had
    // before, and with it the slot holding where they were. Falls back to the requested identity
    // only while nothing is presented yet.
    val generation = mangaList.presentedListGeneration()
    val listingOfGeneration = remember { mutableMapOf<Long, Any?>() }
    val shownListing = if (generation == null) {
        listKey
    } else {
        listingOfGeneration.getOrPut(generation) { listKey }
    }

    // One scroll position per listing, held above the loading and empty branches below. Searching
    // composes the loading screen, and then the results or the empty screen, in place of the shelf;
    // a state created inside the grid went with it, so coming back from a search landed at the top
    // instead of at the row the reader left. A listing the reader has not seen starts its own slot
    // at the top, which is what a filter or a sort swap should do. Saveable because opening a work
    // pushes a page on top of this one, which disposes this composition.
    val scrollStates = rememberSaveable(saver = listingScrollStatesSaver) {
        mutableMapOf<String, BrowseScrollStates>()
    }
    val scrollState = scrollStates.getOrPut(shownListing.saveKey()) { BrowseScrollStates() }
    val gridState = scrollState.grid
    val listState = scrollState.list

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

    val layoutDirection = LocalLayoutDirection.current
    // Pinching the listing resizes its rows through the same column preference the library's
    // display settings write, so the two screens are one setting seen twice.
    PinchColumns(
        rawColumns = columns,
        onColumnsCommit = onColumnsCommit,
        enabled = displayMode != LibraryDisplayMode.List,
        horizontalContentInset = contentPadding.calculateStartPadding(layoutDirection) +
            contentPadding.calculateEndPadding(layoutDirection) +
            GRID_CONTENT_PADDING * 2,
        horizontalSpacing = CommonMangaItemDefaults.GridHorizontalSpacer,
    ) { effectiveColumns ->
        val cells = if (effectiveColumns == 0) {
            GridCells.Adaptive(ADAPTIVE_MIN_CELL_WIDTH)
        } else {
            GridCells.Fixed(effectiveColumns)
        }

        when (displayMode) {
            LibraryDisplayMode.ComfortableGrid -> {
                BrowseSourceComfortableGrid(
                    mangaList = mangaList,
                    columns = cells,
                    gridState = gridState,
                    contentPadding = contentPadding,
                    showIndex = source is LocalSource,
                    lastReadMangaId = lastReadMangaId,
                    locateMangaId = locateMangaId,
                    favoriteIds = favoriteIds,
                    progressContext = progressContext,
                    coverUpdates = coverUpdates,
                    trailingSlotCount = trailingSlotCount,
                    wholeList = wholeList,
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
                    wholeList = wholeList,
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
                    columns = cells,
                    gridState = gridState,
                    contentPadding = contentPadding,
                    showIndex = source is LocalSource,
                    lastReadMangaId = lastReadMangaId,
                    locateMangaId = locateMangaId,
                    favoriteIds = favoriteIds,
                    progressContext = progressContext,
                    coverUpdates = coverUpdates,
                    trailingSlotCount = trailingSlotCount,
                    wholeList = wholeList,
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
}

/**
 * Scroll positions of the list, per display mode, for one listing.
 *
 * Both modes are kept for the same slot so switching the display mode keeps the offset instead of
 * dropping the reader back to the top of the listing.
 */
private class BrowseScrollStates(
    val list: LazyListState = LazyListState(),
    val grid: LazyGridState = LazyGridState(),
)

/**
 * Stable name for a listing identity, so its slot can be saved and looked up again.
 *
 * The identities are data classes (see [BrowseListKey]) whose string form lists their fields, which
 * makes it the same for the same listing and different for another one.
 */
private fun Any?.saveKey(): String = this?.toString().orEmpty()

private const val SCROLL_STATE_SAVED_FIELDS = 5

private val listingScrollStatesSaver = listSaver<MutableMap<String, BrowseScrollStates>, Any>(
    save = { states ->
        states.flatMap { (key, state) ->
            listOf(
                key,
                state.list.firstVisibleItemIndex,
                state.list.firstVisibleItemScrollOffset,
                state.grid.firstVisibleItemIndex,
                state.grid.firstVisibleItemScrollOffset,
            )
        }
    },
    restore = { saved ->
        val states = mutableMapOf<String, BrowseScrollStates>()
        for (start in saved.indices step SCROLL_STATE_SAVED_FIELDS) {
            states[saved[start] as String] = BrowseScrollStates(
                list = LazyListState(
                    firstVisibleItemIndex = saved[start + 1] as Int,
                    firstVisibleItemScrollOffset = saved[start + 2] as Int,
                ),
                grid = LazyGridState(
                    firstVisibleItemIndex = saved[start + 3] as Int,
                    firstVisibleItemScrollOffset = saved[start + 4] as Int,
                ),
            )
        }
        states
    },
)

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
