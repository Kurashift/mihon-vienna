package eu.kanade.tachiyomi.ui.manga

import org.junit.jupiter.api.Assertions.assertFalse
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
}
