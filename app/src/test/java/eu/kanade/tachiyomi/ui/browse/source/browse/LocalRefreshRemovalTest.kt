package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalRefreshRemovalTest {
    @Test
    fun `a work that only gained chapters does not need to wait for an unreadable neighbor`() {
        assertTrue(canRefreshLocalWorkDuringPartialScan(setOf("A.cbz"), setOf("A.cbz", "B.cbz"), setOf("Other")))
    }

    @Test
    fun `a possible move from an unreadable work is retried before a new identity is created`() {
        assertFalse(canRefreshLocalWorkDuringPartialScan(setOf("A.cbz"), setOf("A.cbz", "B.cbz"), setOf("B")))
    }

    @Test
    fun `a disappeared file may still be a move to an unreadable neighbor`() {
        assertTrue(hasMissingLocalChapterNames(setOf("A.cbz", "B.cbz"), setOf("B.cbz")))
        assertTrue(hasMissingLocalChapterNames(setOf("A.cbz"), setOf("A.epub")))
    }

    @Test
    fun `legacy extensionless names are not treated as deleted solely because the scan has full names`() {
        assertFalse(hasMissingLocalChapterNames(setOf("A"), setOf("A.cbz")))
        assertTrue(hasMissingLocalChapterNames(setOf("A"), setOf("B.cbz")))
    }
}
