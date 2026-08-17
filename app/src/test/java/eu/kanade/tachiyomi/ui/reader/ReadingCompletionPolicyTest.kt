package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReadingCompletionPolicyTest {

    @Test
    fun `completed chapter resumes at final page even with legacy partial progress`() {
        val chapter = ChapterImpl().apply {
            read = true
            last_page_read = 8
            total_pages = 12
        }

        assertEquals(11, ReaderChapter(chapter).resumePageIndex(12))
    }

    @Test
    fun `unfinished chapter resumes at its stored page`() {
        val chapter = ChapterImpl().apply {
            read = false
            last_page_read = 10
            total_pages = 12
        }

        assertEquals(9, ReaderChapter(chapter).resumePageIndex(12))
    }

    @Test
    fun `unread legacy progress equal to total pages starts at first page`() {
        val chapter = ChapterImpl().apply {
            read = false
            last_page_read = 12
            total_pages = 0
        }

        assertEquals(0, ReaderChapter(chapter).resumePageIndex(12))
    }

    @Test
    fun `random pool follows explicit read state`() {
        assertTrue(Chapter.create().copy(lastPageRead = 10, totalPages = 12).isEligibleForRandomPool)
        assertFalse(Chapter.create().copy(read = true, lastPageRead = 8, totalPages = 12).isEligibleForRandomPool)
        assertTrue(Chapter.create().copy(lastPageRead = 12, totalPages = 12).isEligibleForRandomPool)
    }
}
