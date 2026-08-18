package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `reading pool applies chapter state for each reading filter`() {
        val unread = Chapter.create().copy(id = 10, mangaId = 1, read = false)
        val read = Chapter.create().copy(id = 11, mangaId = 1, read = true)
        val current = Chapter.create().copy(id = 12, mangaId = 1, read = false)
        val chapters = sequenceOf(unread, read, current)

        assertEquals(
            listOf(unread, read),
            readingRandomPoolCandidates(chapters, BrowseSourceViewModel.ReadingFilter.ALL, current.id),
        )
        assertEquals(
            listOf(unread),
            readingRandomPoolCandidates(chapters, BrowseSourceViewModel.ReadingFilter.UNREAD, current.id),
        )
        assertEquals(
            listOf(unread),
            readingRandomPoolCandidates(chapters, BrowseSourceViewModel.ReadingFilter.IN_PROGRESS, current.id),
        )
        assertEquals(
            listOf(read),
            readingRandomPoolCandidates(chapters, BrowseSourceViewModel.ReadingFilter.FINISHED, current.id),
        )
    }

    @Test
    fun `good doujin pool keeps marked unfinished chapter`() {
        val markedUnfinished = Chapter.create().copy(id = 10, mangaId = 1, read = false)
        val siblingUnfinished = Chapter.create().copy(id = 11, mangaId = 1, read = false)
        val markedFinished = Chapter.create().copy(id = 12, mangaId = 1, read = true)
        val otherManga = Chapter.create().copy(id = 20, mangaId = 2, read = false)
        val marks = listOf(
            MangaMark(
                mangaId = 1,
                mangaTitle = "Marked manga",
                chapterId = markedUnfinished.id,
                chapterName = "Marked unfinished",
                markedAt = 0,
            ),
            MangaMark(
                mangaId = 1,
                mangaTitle = "Marked manga",
                chapterId = markedFinished.id,
                chapterName = "Marked finished",
                markedAt = 0,
            ),
        )

        assertEquals(
            listOf(markedUnfinished),
            goodDoujinRandomPoolCandidates(
                chapters = sequenceOf(markedUnfinished, siblingUnfinished, markedFinished, otherManga),
                marks = marks,
                currentChapterId = siblingUnfinished.id,
            ),
        )
    }

    @Test
    fun `random pool groups chapters by manga before selection`() {
        val firstMangaChapters = listOf(
            Chapter.create().copy(id = 10, mangaId = 1),
            Chapter.create().copy(id = 11, mangaId = 1),
            Chapter.create().copy(id = 12, mangaId = 1),
        )
        val secondMangaChapter = Chapter.create().copy(id = 20, mangaId = 2)

        val groups = randomPoolCandidateGroups(firstMangaChapters + secondMangaChapter)

        assertEquals(2, groups.size)
        assertEquals(setOf(1L, 2L), groups.map { it.first().mangaId }.toSet())
        assertTrue(groups.all { group -> group.all { it.mangaId == group.first().mangaId } })
        assertEquals(listOf(1, 3), groups.map { it.size }.sorted())
    }
}
