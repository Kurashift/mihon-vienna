package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SearchResultUpdatesTest {
    @Test
    fun `simultaneous results retain both sources after a state update retry`() {
        val first = source(1)
        val second = source(2)
        val state = MutableStateFlow(
            SearchViewModel.State(
                items = mapOf(first to SearchItemResult.Loading, second to SearchItemResult.Loading),
            ),
        )
        val barrier = CyclicBarrier(2)
        val firstAttempt = ThreadLocal.withInitial { true }
        val comparator: (Map<Source, SearchItemResult>) -> Comparator<Source> = {
            if (firstAttempt.get() == true) {
                firstAttempt.set(false)
                barrier.await(5, TimeUnit.SECONDS)
            }
            compareBy { it.id }
        }
        val success = SearchItemResult.Success(emptyList())
        val error = SearchItemResult.Error(IllegalStateException("source failed"))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val updates = listOf(
                executor.submit { state.updateSearchItem(first, success, comparator) },
                executor.submit { state.updateSearchItem(second, error, comparator) },
            )
            updates.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertSame(success, state.value.items[first])
        assertSame(error, state.value.items[second])
        assertEquals(2, state.value.progress)
        assertEquals(2, state.value.total)
    }

    @Test
    fun `result updates keep query filters and other source results`() {
        val first = source(1)
        val second = source(2)
        val state = MutableStateFlow(
            SearchViewModel.State(
                searchQuery = "current query",
                sourceFilter = SourceFilter.All,
                onlyShowHasResults = true,
                items = mapOf(first to SearchItemResult.Loading, second to SearchItemResult.Success(emptyList())),
            ),
        )
        val result = SearchItemResult.Error(IllegalStateException("source failed"))
        state.updateSearchItem(first, result) { compareByDescending { it.id } }

        assertEquals("current query", state.value.searchQuery)
        assertEquals(SourceFilter.All, state.value.sourceFilter)
        assertEquals(true, state.value.onlyShowHasResults)
        assertEquals(listOf(second, first), state.value.items.keys.toList())
        assertSame(result, state.value.items[first])
        assertEquals(2, state.value.progress)
    }

    private fun source(sourceId: Long): Source = mockk {
        every { id } returns sourceId
    }
}
