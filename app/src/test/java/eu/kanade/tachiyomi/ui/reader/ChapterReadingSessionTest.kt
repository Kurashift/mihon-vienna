package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterReadingSessionTest {

    @Test
    fun `backward entry at unread tail does not change progress`() {
        val session = backwardSession(totalPages = 12)

        session.onUserPageSelected(11)
        assertNull(session.onSettled(11))
        session.onUserPageSelected(10)
        assertNull(session.onSettled(10))
    }

    @Test
    fun `backward entry reaches the start zone without writing its position`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 0)

        session.onUserPageSelected(11)
        assertNull(session.onSettled(11))
        session.onUserPageSelected(0)
        assertNull(session.onSettled(0))
    }

    @Test
    fun `backward entry resumes after moving forward out of the start zone`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 0)

        session.onUserPageSelected(11)
        session.onUserPageSelected(0)
        session.onUserPageSelected(3)

        assertEquals(3, session.onSettled(3)?.pageIndex)
        assertFalse(session.onSettled(3)?.completed ?: true)
    }

    @Test
    fun `backward entry with saved progress resumes after crossing that progress`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 10)

        session.onUserPageSelected(11)
        assertNull(session.onSettled(11))
        session.onUserPageSelected(8)
        assertNull(session.onSettled(8))
        session.onUserPageSelected(10)

        assertEquals(10, session.onSettled(10)?.pageIndex)
    }

    @Test
    fun `reaching saved progress without moving forward remains protected`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 10)

        session.onUserPageSelected(11)
        session.onUserPageSelected(9)

        assertNull(session.onSettled(9))
        assertNull(session.onExit(9))
    }

    @Test
    fun `normal reading can move backwards after protection is released`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 10)

        session.onUserPageSelected(11)
        session.onUserPageSelected(9)
        session.onUserPageSelected(10)

        assertEquals(10, session.onSettled(10)?.pageIndex)
        assertEquals(5, session.onSettled(5)?.pageIndex)
    }

    @Test
    fun `single page backward entry never auto completes`() {
        val session = backwardSession(totalPages = 1)

        assertNull(session.onSettled(0))
        assertNull(session.onExit(0))
    }

    @Test
    fun `single page direct and forward entry never auto completes`() {
        val direct = ChapterReadingSession(1, ChapterEntryDirection.Direct, alreadyRead = false, lastPageRead = 0)
        val forward = ChapterReadingSession(1, ChapterEntryDirection.Forward, alreadyRead = false, lastPageRead = 0)

        assertNull(direct.onSettled(0))
        assertNull(direct.onExit(0))
        assertNull(forward.onSettled(0))
        assertNull(forward.onExit(0))
    }

    @Test
    fun `direct and forward entry complete normally at final page`() {
        val direct = ChapterReadingSession(3, ChapterEntryDirection.Direct, alreadyRead = false, lastPageRead = 0)
        val forward = ChapterReadingSession(3, ChapterEntryDirection.Forward, alreadyRead = false, lastPageRead = 0)

        assertTrue(direct.onSettled(2)?.completed == true)
        assertTrue(forward.onSettled(2)?.completed == true)
    }

    @Test
    fun `normal reading completes from final three pages only when leaving`() {
        val session = ChapterReadingSession(12, ChapterEntryDirection.Direct, alreadyRead = false, lastPageRead = 0)

        assertFalse(session.onSettled(9)?.completed ?: true)
        assertTrue(session.onExit(9)?.completed == true)
    }

    @Test
    fun `page before tail allowance remains unfinished on exit`() {
        val session = ChapterReadingSession(12, ChapterEntryDirection.Direct, alreadyRead = false, lastPageRead = 0)

        assertFalse(session.onExit(8)?.completed ?: true)
    }

    @Test
    fun `short chapters require at least eighty percent progress`() {
        assertEquals(3, tailCompletionStartIndex(totalPages = 5))
        assertEquals(2, tailCompletionStartIndex(totalPages = 3))

        val fivePages = ChapterReadingSession(5, ChapterEntryDirection.Direct, alreadyRead = false, lastPageRead = 0)
        val threePages = ChapterReadingSession(3, ChapterEntryDirection.Direct, alreadyRead = false, lastPageRead = 0)

        assertFalse(fivePages.onExit(2)?.completed ?: true)
        assertTrue(fivePages.onExit(3)?.completed == true)
        assertFalse(threePages.onExit(1)?.completed ?: true)
        assertTrue(threePages.onExit(2)?.completed == true)
    }

    @Test
    fun `unconfirmed backward entry cannot use tail exit allowance`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 10)

        session.onUserPageSelected(11)
        session.onUserPageSelected(8)

        assertNull(session.onExit(8))
    }

    @Test
    fun `forward exit completes after backward entry resumes reading`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 10)

        session.onUserPageSelected(11)
        session.onUserPageSelected(8)
        session.onUserPageSelected(10)
        assertFalse(session.onExit(8)?.completed ?: true)

        session.onUserPageSelected(11)
        assertTrue(session.onExit(11)?.completed == true)
    }

    @Test
    fun `forward boundary cannot complete before backward entry resumes reading`() {
        val session = backwardSession(totalPages = 12, lastPageRead = 10)

        session.markForwardBoundaryCrossed()

        assertFalse(session.canCompleteOnForwardExit())
    }

    @Test
    fun `forward boundary completes a normal entry`() {
        val session = ChapterReadingSession(
            totalPages = 12,
            entryDirection = ChapterEntryDirection.Direct,
            alreadyRead = false,
            lastPageRead = 0,
        )

        session.markForwardBoundaryCrossed()

        assertTrue(session.canCompleteOnForwardExit())
    }

    @Test
    fun `already read chapter does not emit a downgrade decision`() {
        val session = ChapterReadingSession(12, ChapterEntryDirection.Forward, alreadyRead = true, lastPageRead = 10)

        assertNull(session.onSettled(0))
        assertNull(session.onSettled(5))
        session.markForwardBoundaryCrossed()
        assertFalse(session.canCompleteOnForwardExit())
    }

    private fun backwardSession(totalPages: Int, lastPageRead: Int = 0) = ChapterReadingSession(
        totalPages = totalPages,
        entryDirection = ChapterEntryDirection.Backward,
        alreadyRead = false,
        lastPageRead = lastPageRead,
    )
}
