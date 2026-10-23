package eu.kanade.presentation.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.components.GRID_CONTENT_PADDING
import eu.kanade.presentation.components.PinchColumns
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun LibraryPager(
    state: PagerState,
    contentPadding: PaddingValues,
    hasActiveFilters: Boolean,
    selection: Set<Long>,
    searchQuery: String?,
    /** The keyword the rows were built with; [searchQuery] is what the search field holds. */
    appliedSearchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    getCategoryForPage: (Int) -> Category,
    getDisplayMode: () -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    onClickManga: (Category, LibraryManga) -> Unit,
    onLongClickManga: (Category, LibraryManga) -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
) {
    val displayMode by remember { getDisplayMode() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columnsState = if (displayMode != LibraryDisplayMode.List) {
        remember(isLandscape) { getColumnsForOrientation(isLandscape) }
    } else {
        remember { mutableIntStateOf(0) }
    }

    // The shelf's rows are resized by pinching them, through the same preference the display
    // settings' column slider writes: one setting, two ways to reach it.
    PinchColumns(
        rawColumns = columnsState.value,
        onColumnsCommit = { columnsState.value = it },
        enabled = displayMode != LibraryDisplayMode.List,
        horizontalContentInset = GRID_CONTENT_PADDING * 2,
        horizontalSpacing = CommonMangaItemDefaults.GridHorizontalSpacer,
    ) { columns ->
        HorizontalPager(
            modifier = Modifier.fillMaxSize(),
            state = state,
            verticalAlignment = Alignment.Top,
        ) { page ->
            if (page !in ((state.currentPage - 1)..(state.currentPage + 1))) {
                // To make sure only one offscreen page is being composed
                return@HorizontalPager
            }
            val category = getCategoryForPage(page)
            val items = getItemsForCategory(category)

            // One scroll position per keyword, held above the branch below: a keyword that matches
            // nothing composes the empty screen in place of the list, and a state created inside the
            // list started over at the top — which is why clearing a search landed at the top of the
            // shelf instead of where the user was. The keyword the library was browsed with (empty)
            // keeps its own slot, so the results scroll without moving the shelf underneath.
            //
            // The slot follows [appliedSearchQuery] rather than [searchQuery]: the filter is debounced,
            // so the field names a new keyword while the old rows are still on screen. Switching on
            // the field would attach the shelf's offset to the short result set for that moment and
            // clamp it there for good.
            val scrollStates = rememberSaveable(saver = categoryScrollStatesSaver) {
                mutableMapOf<String, CategoryScrollStates>()
            }
            val scrollState = scrollStates.getOrPut(appliedSearchQuery.orEmpty()) { CategoryScrollStates() }

            if (items.isEmpty()) {
                LibraryPagerEmptyScreen(
                    searchQuery = searchQuery,
                    hasActiveFilters = hasActiveFilters,
                    contentPadding = contentPadding,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
                return@HorizontalPager
            }

            val onClickManga: (LibraryManga) -> Unit = { onClickManga(category, it) }
            val onLongClickManga: (LibraryManga) -> Unit = { onLongClickManga(category, it) }

            when (displayMode) {
                LibraryDisplayMode.List -> {
                    LibraryList(
                        items = items,
                        contentPadding = contentPadding,
                        state = scrollState.list,
                        selection = selection,
                        onClick = onClickManga,
                        onLongClick = onLongClickManga,
                        onClickContinueReading = onClickContinueReading,
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                    )
                }
                LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
                    LibraryCompactGrid(
                        items = items,
                        showTitle = displayMode is LibraryDisplayMode.CompactGrid,
                        columns = columns,
                        contentPadding = contentPadding,
                        gridState = scrollState.grid,
                        selection = selection,
                        onClick = onClickManga,
                        onLongClick = onLongClickManga,
                        onClickContinueReading = onClickContinueReading,
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                    )
                }
                LibraryDisplayMode.ComfortableGrid -> {
                    LibraryComfortableGrid(
                        items = items,
                        columns = columns,
                        contentPadding = contentPadding,
                        gridState = scrollState.grid,
                        selection = selection,
                        onClick = onClickManga,
                        onLongClick = onLongClickManga,
                        onClickContinueReading = onClickContinueReading,
                        searchQuery = searchQuery,
                        onGlobalSearchClicked = onGlobalSearchClicked,
                    )
                }
            }
        }
    }
}

/**
 * Scroll positions of one category, in both shapes the library can take: a list and a grid.
 *
 * Both are held because the display mode can be switched between visits; the unused one simply
 * stays where it was.
 */
private class CategoryScrollStates(
    val list: LazyListState = LazyListState(),
    val grid: LazyGridState = LazyGridState(),
)

/**
 * Saved form of a category's scroll positions: one (keyword, list index, list offset, grid index,
 * grid offset) row per keyword.
 *
 * The states belong to a composition that is disposed whenever the list gives way to the empty or
 * loading screen, so only their primitives travel through the saved state.
 */
private val categoryScrollStatesSaver = listSaver<MutableMap<String, CategoryScrollStates>, Any>(
    save = { states ->
        states.flatMap { (query, state) ->
            listOf(
                query,
                state.list.firstVisibleItemIndex,
                state.list.firstVisibleItemScrollOffset,
                state.grid.firstVisibleItemIndex,
                state.grid.firstVisibleItemScrollOffset,
            )
        }
    },
    restore = { saved ->
        mutableMapOf<String, CategoryScrollStates>().apply {
            saved.chunked(SCROLL_STATE_SAVED_FIELDS).forEach { fields ->
                put(
                    fields[0] as String,
                    CategoryScrollStates(
                        list = LazyListState(fields[1] as Int, fields[2] as Int),
                        grid = LazyGridState(fields[3] as Int, fields[4] as Int),
                    ),
                )
            }
        }
    },
)

/** Fields one keyword occupies in [categoryScrollStatesSaver]. */
private const val SCROLL_STATE_SAVED_FIELDS = 5

@Composable
private fun LibraryPagerEmptyScreen(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding + PaddingValues(8.dp))
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            GlobalSearchItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }

        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.weight(1f),
        )
    }
}
