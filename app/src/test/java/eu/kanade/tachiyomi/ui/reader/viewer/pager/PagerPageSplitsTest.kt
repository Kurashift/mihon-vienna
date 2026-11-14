package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagerPageSplitsTest {
    @Test
    fun `adjacent chapters keep separate splits at the same page index`() {
        val splits = PagerPageSplits()
        val previous = page(1, 4)
        val next = page(3, 4)
        splits.add(previous)
        splits.add(next)

        assertEquals(listOf(previous), splits.forChapter(1))
        assertEquals(listOf(next), splits.forChapter(3))
        assertTrue(splits.forChapter(2).isEmpty())
    }

    @Test
    fun `splits insert from the last index without shifting earlier pages`() {
        val splits = PagerPageSplits()
        splits.add(page(2, 1))
        splits.add(page(2, 5))
        splits.add(page(2, 3))

        assertEquals(listOf(5, 3, 1), splits.forChapter(2).map { it.index })
    }

    @Test
    fun `repeated split events preserve the original page`() {
        val splits = PagerPageSplits()
        val original = page(2, 1)
        splits.add(original)
        splits.add(page(2, 1))

        assertEquals(1, splits.forChapter(2).size)
        assertSame(original, splits.forChapter(2).single())
    }

    @Test
    fun `chapter changes discard consumed splits and retain both neighbours`() {
        val splits = PagerPageSplits()
        (1L..4L).forEach { splits.add(page(it, 2)) }
        splits.retainChapters(setOf(1, 3))

        assertTrue(splits.forChapter(2).isEmpty())
        assertTrue(splits.forChapter(4).isEmpty())
        assertEquals(1, splits.forChapter(1).size)
        assertEquals(1, splits.forChapter(3).size)
        splits.retainChapters(emptySet())
        assertTrue(splits.forChapter(1).isEmpty())
    }

    private fun page(chapterId: Long, index: Int): InsertPage {
        val chapter = ReaderChapter(
            ChapterImpl().apply {
                id = chapterId
                url = chapterId.toString()
            },
        )
        return InsertPage(ReaderPage(index).apply { this.chapter = chapter })
    }
}
