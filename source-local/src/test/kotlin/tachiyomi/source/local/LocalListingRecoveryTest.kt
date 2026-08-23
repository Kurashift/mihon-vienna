package tachiyomi.source.local

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalListingRecoveryTest {

    @Test
    fun `unchanged directory does not replace a populated index with an empty scan`() {
        assertTrue(
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = 0,
                persistedEntryCount = 539,
            ),
        )
    }

    @Test
    fun `changed directory does not let a transient empty scan erase a populated index`() {
        assertTrue(
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = 0,
                persistedEntryCount = 539,
            ),
        )
    }

    @Test
    fun `first empty library does not need a previous index`() {
        assertFalse(
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = 0,
                persistedEntryCount = 0,
            ),
        )
    }

    @Test
    fun `reliable refresh removes a folder whose last chapter was deleted`() {
        assertEquals(0, resolvedLocalChapterCount(emptySet(), measuredChapterCount = 4))
    }

    @Test
    fun `reliable scan wins over a transient empty metadata read`() {
        assertEquals(
            2,
            resolvedLocalChapterCount(setOf("Story 1.cbz", "Story 2.cbz"), measuredChapterCount = 0),
        )
    }

    @Test
    fun `unconfirmed empty chapter read keeps the last nonempty cbz count`() {
        assertEquals(
            2,
            resolvedLocalChapterCount(
                scannedChapterFiles = null,
                measuredChapterCount = 0,
                previousConfirmedChapterCount = 2,
            ),
        )
    }

    @Test
    fun `reliable empty chapter scan removes the last known cbz files`() {
        assertEquals(
            0,
            resolvedLocalChapterCount(
                scannedChapterFiles = emptySet(),
                measuredChapterCount = 0,
                previousConfirmedChapterCount = 2,
            ),
        )
    }

    @Test
    fun `reliable refresh does not revive a directory missing from its scan`() {
        assertFalse(
            shouldIncludeLocalMangaDirectory(
                mangaUrl = "Deleted author",
                scannedChapterFileNames = mapOf("Existing author" to setOf("Story.cbz")),
            ),
        )
    }

    @Test
    fun `more than 64 real directory removals can be confirmed`() = runBlocking {
        val missing = (1..65).mapTo(linkedSetOf()) { "Author $it" }

        assertTrue(confirmMissingLocalMangaDirectoriesGone(missing) { false })
    }

    @Test
    fun `one existing directory rejects a bulk removal snapshot`() = runBlocking {
        val missing = (1..65).mapTo(linkedSetOf()) { "Author $it" }

        assertFalse(confirmMissingLocalMangaDirectoriesGone(missing) { it == "Author 37" })
    }
}
