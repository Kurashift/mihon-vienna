package eu.kanade.tachiyomi.ui.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.track.model.Track

internal fun libraryTrackingFlow(
    categories: Flow<List<Category>>,
    filters: Flow<Map<Long, TriState>>,
    tracks: Flow<Map<Long, List<Track>>>,
): Flow<Map<Long, List<Track>>> = combine(categories, filters) { currentCategories, currentFilters ->
    currentFilters.values.any { it != TriState.DISABLED } ||
        currentCategories.any { it.sort.type == LibrarySort.Type.TrackerMean }
}
    .distinctUntilChanged()
    .flatMapLatest { needsTracks -> if (needsTracks) tracks else flowOf(emptyMap()) }
