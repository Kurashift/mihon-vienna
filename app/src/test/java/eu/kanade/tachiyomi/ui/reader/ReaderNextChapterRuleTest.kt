package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderNextChapterRuleTest {

    // read flags by index; "false" also covers half-read chapters (read = false, progress > 0).
    private fun readFlags(vararg flags: Boolean): (Int) -> Boolean = { flags[it] }

    @Test
    fun `skip-read off keeps the immediate neighbour`() {
        assertEquals(
            1,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 4,
                currentPos = 0,
                currentWasUnread = true,
                skipRead = false,
                readAt = readFlags(false, true, true, false),
            ),
        )
    }

    @Test
    fun `advancing from an unread chapter lands on the next unread one`() {
        // Two read chapters sit between the current chapter and the next unread one.
        assertEquals(
            3,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 5,
                currentPos = 0,
                currentWasUnread = true,
                skipRead = true,
                readAt = readFlags(false, true, true, false, false),
            ),
        )
    }

    @Test
    fun `a half-read chapter is never skipped`() {
        // Half-read chapters have read = false, so the very next one is the landing chapter.
        assertEquals(
            1,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 4,
                currentPos = 0,
                currentWasUnread = true,
                skipRead = true,
                readAt = readFlags(false, false, false, false),
            ),
        )
    }

    @Test
    fun `re-reading from a read chapter keeps the original order`() {
        // The user opened an already-read chapter on purpose: walk forward one by one.
        assertEquals(
            1,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 4,
                currentPos = 0,
                currentWasUnread = false,
                skipRead = true,
                readAt = readFlags(true, true, true, false),
            ),
        )
    }

    @Test
    fun `everything ahead read falls back to the immediate neighbour`() {
        // Dropping the transition entirely would strand the user at the end of the chain.
        assertEquals(
            1,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 3,
                currentPos = 0,
                currentWasUnread = true,
                skipRead = true,
                readAt = readFlags(false, true, true),
            ),
        )
    }

    @Test
    fun `the last chapter has no next`() {
        assertNull(
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 2,
                currentPos = 1,
                currentWasUnread = true,
                skipRead = true,
                readAt = readFlags(false, false),
            ),
        )
    }

    @Test
    fun `skipping is measured from the current position not the list start`() {
        assertEquals(
            4,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 6,
                currentPos = 2,
                currentWasUnread = true,
                skipRead = true,
                readAt = readFlags(true, true, false, true, false, true),
            ),
        )
    }

    @Test
    fun `an unknown current position falls back to the first chapter`() {
        // Defensive: a chapter object that is not in the chain must not crash the reader.
        assertEquals(
            0,
            ReaderNextChapterRule.nextIndex(
                chaptersSize = 3,
                currentPos = -1,
                currentWasUnread = true,
                skipRead = true,
                readAt = readFlags(false, false, false),
            ),
        )
    }
}
