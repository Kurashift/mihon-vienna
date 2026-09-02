package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.ReadingFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.model.MangaProgressByMangaId

class LocalReadingFilterTest {

    @Test
    fun `unread filter drops a fully read manga`() {
        val finished = progress(total = 3, read = 3, finished = 3)
        val started = progress(total = 3, read = 1, finished = 0)

        assertFalse(matches(ReadingFilter.UNREAD, finished))
        assertTrue(matches(ReadingFilter.UNREAD, started))
    }

    @Test
    fun `unread filter keeps a manga read only part way`() {
        val partial = progress(total = 5, read = 4, finished = 4)

        assertTrue(matches(ReadingFilter.UNREAD, partial))
    }

    @Test
    fun `in progress filter needs some progress but not completion`() {
        val finished = progress(total = 2, read = 2, finished = 2)
        val untouched = progress(total = 2, read = 0, finished = 0)
        val started = progress(total = 2, read = 1, finished = 0)

        assertFalse(matches(ReadingFilter.IN_PROGRESS, finished))
        assertFalse(matches(ReadingFilter.IN_PROGRESS, untouched))
        assertTrue(matches(ReadingFilter.IN_PROGRESS, started))
    }

    @Test
    fun `all filter keeps everything including finished manga`() {
        val finished = progress(total = 2, read = 2, finished = 2)

        assertTrue(matches(ReadingFilter.ALL, finished))
        assertTrue(matches(ReadingFilter.ALL, progress(2, 0, 0)))
    }

    /**
     * A manga with no chapters counts as unfinished, so the predicate alone would happily offer
     * it. That is why the random pool comes from a query joining chapters: a deleted leftover
     * leaves no row at all and never reaches this predicate.
     */
    @Test
    fun `manga without chapters is not finished`() {
        assertFalse(MangaProgress.EMPTY.hasFinished)
        assertTrue(matches(ReadingFilter.UNREAD, MangaProgress.EMPTY))
    }

    @Test
    fun `random pool covers the whole filtered set not just the loaded page`() {
        // The pool is ids of every matching entry, so a 3000-entry library filtered down to
        // "unread" still yields every unread id rather than the first PAGE_SIZE loaded rows.
        val entries = (1L..500L).map { id ->
            val finished = id % 2 == 0L
            entry(id, progress(total = 2, read = if (finished) 2 else 0, finished = if (finished) 2 else 0))
        }

        val pool = LocalReadingFilter.randomPickIds(entries, ReadingFilter.UNREAD)

        assertEquals(250, pool.size)
        assertEquals((1L..500L).filter { it % 2 == 1L }, pool)
    }

    @Test
    fun `random pool excludes finished manga under unread filter`() {
        val finished = entry(1L, progress(total = 3, read = 3, finished = 3))
        val untouched = entry(2L, progress(total = 3, read = 0, finished = 0))

        assertEquals(
            listOf(2L),
            LocalReadingFilter.randomPickIds(listOf(finished, untouched), ReadingFilter.UNREAD),
        )
    }

    @Test
    fun `filter is read back per source and survives an unknown value`() {
        // InMemoryPreferenceStore hands out a fresh Preference per getString, so the stored
        // value is supplied up front instead of through set().
        fun store(value: String? = null) = InMemoryPreferenceStore(
            initialPreferences = listOfNotNull(
                value?.let {
                    InMemoryPreferenceStore.InMemoryPreference(
                        key = "browse_reading_filter_0",
                        data = it,
                        defaultValue = ReadingFilter.ALL.name,
                    )
                },
            ).asSequence(),
        )

        assertEquals(ReadingFilter.ALL, LocalReadingFilter.read(0L, store()))
        assertEquals(ReadingFilter.UNREAD, LocalReadingFilter.read(0L, store(ReadingFilter.UNREAD.name)))
        assertEquals(ReadingFilter.FINISHED, LocalReadingFilter.read(0L, store(ReadingFilter.FINISHED.name)))
        // A different source id keeps its own value.
        assertEquals(ReadingFilter.ALL, LocalReadingFilter.read(1L, store(ReadingFilter.UNREAD.name)))
        // A value written by a future/older build falls back instead of crashing.
        assertEquals(ReadingFilter.ALL, LocalReadingFilter.read(0L, store("NOT_A_FILTER")))
    }

    private fun matches(filter: ReadingFilter, progress: MangaProgress) =
        LocalReadingFilter.matches(filter, progress)

    private fun progress(total: Long, read: Long, finished: Long) =
        MangaProgress(total, read, finished, lastRead = 0)

    private fun entry(mangaId: Long, progress: MangaProgress) =
        MangaProgressByMangaId(mangaId, "url-$mangaId", progress, lastOpenedAt = 0)
}
