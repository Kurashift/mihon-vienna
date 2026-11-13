package eu.kanade.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterReadState

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
    fun `marking unread only touches chapters that carry progress`() {
        val unread = chapter(id = 1, read = false)
        val halfRead = chapter(id = 2, read = false, lastPageRead = 3, totalPages = 20)
        val read = chapter(id = 3, read = true, lastPageRead = 20, totalPages = 20)

        assertEquals(
            listOf(halfRead, read),
            listOf(unread, halfRead, read).readStateCandidates(read = false),
        )
    }

    @Test
    fun `snapshot keeps every field the undo has to write back`() {
        val states = listOf(
            chapter(id = 7, read = true, lastPageRead = 12, totalPages = 20, markedReadAt = 1_700_000_000_000),
            chapter(id = 8, read = false),
        ).toReadStates()

        assertEquals(
            listOf(
                ChapterReadState(chapterId = 7, read = true, lastPageRead = 12, markedReadAt = 1_700_000_000_000),
                ChapterReadState(chapterId = 8, read = false, lastPageRead = 0, markedReadAt = 0),
            ),
            states,
        )
    }

    @Test
    fun `a restored snapshot is the snapshot`() {
        val original = listOf(
            chapter(id = 1, read = true, lastPageRead = 4, totalPages = 30, markedReadAt = 42),
            chapter(id = 2, read = false),
        )
        val captured = original.toReadStates()

        // The undo hands these back to the repository as they are, so what was captured is what
        // gets written - nothing is recomputed from the chapter's current state.
        assertEquals(original.map { it.id }, captured.map { it.chapterId })
        assertEquals(original.map { it.read }, captured.map { it.read })
        assertEquals(original.map { it.lastPageRead }, captured.map { it.lastPageRead })
        assertEquals(original.map { it.markedReadAt }, captured.map { it.markedReadAt })
    }

    private fun chapter(
        id: Long,
        read: Boolean,
        lastPageRead: Long = 0,
        totalPages: Long = 0,
        markedReadAt: Long = 0,
    ): Chapter = Chapter.create().copy(
        id = id,
        read = read,
        lastPageRead = lastPageRead,
        totalPages = totalPages,
        markedReadAt = markedReadAt,
    )
}
