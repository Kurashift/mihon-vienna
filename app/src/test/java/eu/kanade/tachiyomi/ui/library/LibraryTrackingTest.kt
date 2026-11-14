package eu.kanade.tachiyomi.ui.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.track.model.Track

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTrackingTest {
    @Test
    fun `ordinary browsing never subscribes to tracking records`() = runTest {
        var subscriptions = 0
        val categories = MutableStateFlow(listOf(category(0)))
        val filters = MutableStateFlow(mapOf(1L to TriState.DISABLED))
        val tracks = flow<Map<Long, List<Track>>> {
            subscriptions += 1
            emit(mapOf(1L to emptyList()))
        }
        var observed: Map<Long, List<Track>>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            libraryTrackingFlow(categories, filters, tracks).collect { observed = it }
        }
        runCurrent()

        assertEquals(0, subscriptions)
        assertEquals(emptyMap<Long, List<Track>>(), observed)
    }

    @Test
    fun `filters and rating sort subscribe only while tracking is needed`() = runTest {
        var subscriptions = 0
        var activeSubscriptions = 0
        val categories = MutableStateFlow(listOf(category(0)))
        val filters = MutableStateFlow(mapOf(1L to TriState.DISABLED))
        val records = mapOf(7L to emptyList<Track>())
        val tracks = flow {
            subscriptions += 1
            activeSubscriptions += 1
            try {
                emit(records)
                awaitCancellation()
            } finally {
                activeSubscriptions -= 1
            }
        }
        var observed: Map<Long, List<Track>>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            libraryTrackingFlow(categories, filters, tracks).collect { observed = it }
        }
        runCurrent()
        filters.value = mapOf(1L to TriState.ENABLED_IS)
        runCurrent()
        assertEquals(records, observed)
        assertEquals(1, subscriptions)
        filters.value = mapOf(1L to TriState.ENABLED_NOT)
        runCurrent()
        assertEquals(1, subscriptions)
        categories.value = listOf(category(0, ratingSort = true))
        filters.value = mapOf(1L to TriState.DISABLED)
        runCurrent()
        assertEquals(records, observed)
        assertEquals(1, subscriptions)
        categories.value = listOf(category(0))
        runCurrent()
        assertEquals(0, activeSubscriptions)
        assertEquals(emptyMap<Long, List<Track>>(), observed)
        categories.value = listOf(category(0), category(2, ratingSort = true))
        runCurrent()
        assertEquals(records, observed)
        assertEquals(2, subscriptions)
        assertEquals(1, activeSubscriptions)
    }

    private fun category(categoryId: Long, ratingSort: Boolean = false): Category = Category(
        id = categoryId,
        name = categoryId.toString(),
        order = categoryId,
        flags = LibrarySort(
            if (ratingSort) LibrarySort.Type.TrackerMean else LibrarySort.Type.Alphabetical,
            LibrarySort.Direction.Ascending,
        ).flag,
    )
}
