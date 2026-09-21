package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterScopeTest {

    private val marked = setOf(11L, 12L)
    private val goodDoujins = setOf(12L, 13L)

    @Test
    fun `all keeps every chapter whether marked or not`() {
        assertTrue(ChapterScope.ALL.includes(11L, marked, goodDoujins))
        assertTrue(ChapterScope.ALL.includes(12L, marked, goodDoujins))
        assertTrue(ChapterScope.ALL.includes(99L, marked, goodDoujins))
    }

    @Test
    fun `flagged keeps only the flagged chapters`() {
        assertTrue(ChapterScope.FLAGGED.includes(11L, marked, goodDoujins))
        assertTrue(ChapterScope.FLAGGED.includes(12L, marked, goodDoujins))
        assertFalse(ChapterScope.FLAGGED.includes(13L, marked, goodDoujins))
        assertFalse(ChapterScope.FLAGGED.includes(99L, marked, goodDoujins))
    }

    @Test
    fun `good doujin keeps only the good doujin chapters`() {
        assertTrue(ChapterScope.GOOD_DOUJIN.includes(12L, marked, goodDoujins))
        assertTrue(ChapterScope.GOOD_DOUJIN.includes(13L, marked, goodDoujins))
        assertFalse(ChapterScope.GOOD_DOUJIN.includes(11L, marked, goodDoujins))
        assertFalse(ChapterScope.GOOD_DOUJIN.includes(99L, marked, goodDoujins))
    }

    /**
     * The two marks live in different stores, so a chapter can be in both and must stay reachable
     * from either scope - the work is on both lists and neither one owns the chapter.
     */
    @Test
    fun `a chapter carrying both marks is kept by either scope`() {
        assertTrue(ChapterScope.FLAGGED.includes(12L, marked, goodDoujins))
        assertTrue(ChapterScope.GOOD_DOUJIN.includes(12L, marked, goodDoujins))
    }

    /**
     * Nothing is marked in a work whose marks were all removed while the scope was up. That leaves
     * an empty list, which is the honest answer to "show me the flagged chapters of a work with
     * none" - falling back to everything would contradict the lit filter icon.
     */
    @Test
    fun `a mark scope with no marks keeps nothing`() {
        assertFalse(ChapterScope.FLAGGED.includes(11L, emptySet(), emptySet()))
        assertFalse(ChapterScope.GOOD_DOUJIN.includes(11L, emptySet(), emptySet()))
    }

    /**
     * A work with no marks at all is a real case: the scope can be switched to from the filter
     * sheet on any work.
     */
    @Test
    fun `marks of another work do not leak in`() {
        assertFalse(ChapterScope.FLAGGED.includes(11L, setOf(21L, 22L), emptySet()))
        assertFalse(ChapterScope.GOOD_DOUJIN.includes(11L, emptySet(), setOf(21L, 22L)))
    }

    /**
     * A random jump has to land inside the scope on screen. Landing outside it opens a work whose
     * page then shows none of the marked chapters - an empty screen the reader cannot read from.
     */
    @Test
    fun `a mark scope narrows the random pool to its own works`() {
        assertEquals(setOf(1L, 2L), ChapterScope.FLAGGED.randomPoolMangaIds(setOf(1L, 2L), setOf(2L, 3L)))
        assertEquals(
            setOf(2L, 3L),
            ChapterScope.GOOD_DOUJIN.randomPoolMangaIds(setOf(1L, 2L), setOf(2L, 3L)),
        )
    }

    /** No scope narrows nothing, which is what leaves the whole library as the pool. */
    @Test
    fun `the all scope leaves the random pool alone`() {
        assertNull(ChapterScope.ALL.randomPoolMangaIds(setOf(1L), setOf(2L)))
    }

    /**
     * Nothing is marked, so nothing is eligible. Returning an empty set rather than null matters:
     * null would read as "no narrowing" and hand the whole library back, which is the empty page
     * this exists to prevent.
     */
    @Test
    fun `a mark scope with no marks has an empty random pool, not an open one`() {
        assertEquals(emptySet<Long>(), ChapterScope.FLAGGED.randomPoolMangaIds(emptyList(), emptyList()))
        assertEquals(emptySet<Long>(), ChapterScope.GOOD_DOUJIN.randomPoolMangaIds(emptyList(), emptyList()))
    }

    /**
     * The good-doujin gesture draws from the good-doujin list whatever filter the screen it started
     * from carries, so the only scope it can hand over is the one that list agrees with.
     */
    @Test
    fun `a good doujin jump keeps its scope only where the reader asked for it`() {
        assertEquals(ChapterScope.GOOD_DOUJIN, ChapterScope.GOOD_DOUJIN.opensGoodDoujinJump())
    }

    /**
     * Under 标记 the destination has to open whole. The pick never consulted the mark filter, so the
     * work it lands on may carry no marked chapters at all - keeping 标记 would open the empty page
     * this whole hand-over exists to avoid. And the reader did not ask for a mark-narrowed page by
     * dragging from a screen whose mode says nothing about which chapters to show.
     */
    @Test
    fun `a good doujin jump opens the whole work under a scope its pool does not agree with`() {
        assertEquals(ChapterScope.ALL, ChapterScope.ALL.opensGoodDoujinJump())
        assertEquals(ChapterScope.ALL, ChapterScope.FLAGGED.opensGoodDoujinJump())
    }
}
