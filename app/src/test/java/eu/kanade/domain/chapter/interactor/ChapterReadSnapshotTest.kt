package eu.kanade.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterReadSnapshotTest {

    @Test
    fun `marking read only touches chapters that are not read yet`() {
        val unread = chapter(id = 1, read = false)
        val halfRead = chapter(id = 2, read = false, lastPageRead = 3, totalPages = 20)
        val read = chapter(id = 3, read = true, lastPageRead = 20, totalPages = 20)

        assertEquals(
            listOf(unread, halfRead),
            listOf(unread, halfRead, read).readStateCandidates(read = true),
        )
    }

    @Test
    fun `marking unread includes chapters whose completion survived a progress reset`() {
        val unread = chapter(id = 1, read = false)
        val halfRead = chapter(id = 2, read = false, lastPageRead = 3, totalPages = 20)
        val read = chapter(id = 3, read = true, lastPageRead = 20, totalPages = 20)

        assertEquals(
            listOf(unread, halfRead, read),
            listOf(unread, halfRead, read).readStateCandidates(read = false),
        )
    }

    private fun chapter(
        id: Long,
        read: Boolean,
        lastPageRead: Long = 0,
        totalPages: Long = 0,
    ): Chapter = Chapter.create().copy(
        id = id,
        read = read,
        lastPageRead = lastPageRead,
        totalPages = totalPages,
    )
}
