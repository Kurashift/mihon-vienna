package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.data.database.models.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MissingChaptersTest {

    @Test
    fun `local source ignores numbers embedded in independent work names`() {
        assertEquals(
            0,
            calculateChapterGap(
                readerChapter(id = 4L, chapterNumber = 4f),
                readerChapter(id = 2L, chapterNumber = 2f),
                isLocalSource = true,
            ),
        )
    }

    @Test
    fun `local source does not read numbers in standalone work titles as a chapter gap`() {
        // Standalone works like "86" and "5" have no relationship to each other, so the 80
        // chapters in between are not missing.
        assertEquals(
            0,
            calculateChapterGap(
                readerChapter(id = 86L, chapterNumber = 86f),
                readerChapter(id = 5L, chapterNumber = 5f),
                isLocalSource = true,
            ),
        )
    }

    @Test
    fun `online source keeps missing chapter detection`() {
        assertEquals(
            1,
            calculateChapterGap(
                readerChapter(id = 4L, chapterNumber = 4f),
                readerChapter(id = 2L, chapterNumber = 2f),
                isLocalSource = false,
            ),
        )
    }

    private fun readerChapter(id: Long, chapterNumber: Float) = ReaderChapter(
        ChapterImpl().apply {
            this.id = id
            manga_id = 1L
            url = "chapter-$id"
            name = "Independent work $id"
            chapter_number = chapterNumber
        },
    )
}
