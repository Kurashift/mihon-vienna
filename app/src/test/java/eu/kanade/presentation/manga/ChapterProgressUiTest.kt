package eu.kanade.presentation.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterProgressUiTest {

    @Test
    fun `unknown total pages has no determinate progress`() {
        val chapter = Chapter.create().copy(lastPageRead = 7, totalPages = 0)

        assertNull(chapter.toChapterProgressUi())
    }

    @Test
    fun `unstarted chapter shows zero progress`() {
        val progress = Chapter.create()
            .copy(lastPageRead = 0, totalPages = 19)
            .toChapterProgressUi()

        assertEquals(0, progress?.readPages)
        assertEquals(19, progress?.totalPages)
        assertEquals(0f, progress?.fraction)
    }

    @Test
    fun `partial chapter shows persisted progress`() {
        val progress = Chapter.create()
            .copy(lastPageRead = 7, totalPages = 19)
            .toChapterProgressUi()

        assertEquals(7, progress?.readPages)
        assertEquals(7f / 19f, progress?.fraction)
    }

    @Test
    fun `explicitly read chapter shows complete progress`() {
        val progress = Chapter.create()
            .copy(read = true, lastPageRead = 0, totalPages = 19)
            .toChapterProgressUi()

        assertEquals(19, progress?.readPages)
        assertEquals(1f, progress?.fraction)
    }

    @Test
    fun `stale unread progress never looks complete`() {
        val progress = Chapter.create()
            .copy(read = false, lastPageRead = 19, totalPages = 19)
            .toChapterProgressUi()

        assertEquals(18, progress?.readPages)
        assertEquals(18f / 19f, progress?.fraction)
    }

    @Test
    fun `single page unread chapter stays at zero`() {
        val progress = Chapter.create()
            .copy(read = false, lastPageRead = 1, totalPages = 1)
            .toChapterProgressUi()

        assertEquals(0, progress?.readPages)
        assertEquals(0f, progress?.fraction)
    }
}
