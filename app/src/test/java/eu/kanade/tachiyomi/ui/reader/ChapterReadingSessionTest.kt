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

        assertNull(session.onSettled(11))
        assertNull(session.onSettled(10))
    }

    @Test
    fun `three distinct settled pages confirm reading without completing`() {
        val session = backwardSession(totalPages = 12)

        assertNull(session.onSettled(11))
        assertNull(session.onSettled(10))
        val decision = session.onSettled(9)

        assertEquals(9, decision?.pageIndex)
        assertFalse(decision?.completed ?: true)
    }

    @Test
    fun `fast jump across pages does not complete until tail is reached forward`() {
        val session = backwardSession(totalPages = 12)

        session.onSettled(11)
        session.onSettled(7)
        val armed = session.onSettled(3)
        val forward = session.onSettled(8)
        val completed = session.onSettled(11)

        assertFalse(armed?.completed ?: true)
        assertFalse(forward?.completed ?: true)
        assertTrue(completed?.completed == true)
    }

    @Test
    fun `revisiting tail without forward movement remains unfinished`() {
        val session = backwardSession(totalPages = 12)

        session.onSettled(11)
        session.onSettled(10)
        session.onSettled(9)

        assertFalse(session.onSettled(9)?.completed ?: true)
        assertFalse(session.onSettled(8)?.completed ?: true)
    }

    @Test
    fun `two page backward entry requires first page then forward return`() {
        val session = backwardSession(totalPages = 2)

        assertNull(session.onSettled(1))
        assertFalse(session.onSettled(0)?.completed ?: true)
        assertTrue(session.onSettled(1)?.completed == true)
    }

    @Test
    fun `single page backward entry never auto completes`() {
        val session = backwardSession(totalPages = 1)

        assertNull(session.onSettled(0))
        assertNull(session.onSettled(0))
    }

    @Test
    fun `direct and forward entry complete normally at final page`() {
        val direct = ChapterReadingSession(3, ChapterEntryDirection.Direct, alreadyRead = false)
        val forward = ChapterReadingSession(3, ChapterEntryDirection.Forward, alreadyRead = false)

        assertTrue(direct.onSettled(2)?.completed == true)
        assertTrue(forward.onSettled(2)?.completed == true)
    }

    @Test
    fun `normal reading completes from final three pages only when leaving`() {
        val session = ChapterReadingSession(12, ChapterEntryDirection.Direct, alreadyRead = false)

        assertFalse(session.onSettled(9)?.completed ?: true)
        assertTrue(session.onExit(9)?.completed == true)
    }

    @Test
    fun `page before tail allowance remains unfinished on exit`() {
        val session = ChapterReadingSession(12, ChapterEntryDirection.Direct, alreadyRead = false)

        assertFalse(session.onExit(8)?.completed ?: true)
    }

    @Test
    fun `short chapters require at least eighty percent progress`() {
        assertEquals(3, tailCompletionStartIndex(totalPages = 5))
        assertEquals(2, tailCompletionStartIndex(totalPages = 3))

        val fivePages = ChapterReadingSession(5, ChapterEntryDirection.Direct, alreadyRead = false)
        val threePages = ChapterReadingSession(3, ChapterEntryDirection.Direct, alreadyRead = false)

        assertFalse(fivePages.onExit(2)?.completed ?: true)
        assertTrue(fivePages.onExit(3)?.completed == true)
        assertFalse(threePages.onExit(1)?.completed ?: true)
        assertTrue(threePages.onExit(2)?.completed == true)
    }

    @Test
    fun `unconfirmed backward entry cannot use tail exit allowance`() {
        val session = backwardSession(totalPages = 12)

        session.onSettled(11)
        session.onSettled(10)

        assertNull(session.onExit(10))
    }

    @Test
    fun `confirmed backward entry needs forward reading before tail exit completion`() {
        val session = backwardSession(totalPages = 12)

        session.onSettled(11)
        session.onSettled(10)
        session.onSettled(9)
        assertFalse(session.onExit(9)?.completed ?: true)

        session.onSettled(8)
        session.onSettled(9)
        assertTrue(session.onExit(9)?.completed == true)
    }

    @Test
    fun `already read chapter does not emit a downgrade decision`() {
        val session = ChapterReadingSession(12, ChapterEntryDirection.Forward, alreadyRead = true)

        assertNull(session.onSettled(0))
        assertNull(session.onSettled(5))
    }

    private fun backwardSession(totalPages: Int) = ChapterReadingSession(
        totalPages = totalPages,
        entryDirection = ChapterEntryDirection.Backward,
        alreadyRead = false,
    )
}
