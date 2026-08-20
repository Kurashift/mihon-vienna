package tachiyomi.source.local

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalListingRecoveryTest {

    @Test
    fun `unchanged directory does not replace a populated index with an empty scan`() {
        assertTrue(
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = 0,
                scannedBaseDirLastModified = 123L,
                persistedEntryCount = 539,
                persistedBaseDirLastModified = 123L,
            ),
        )
    }

    @Test
    fun `changed directory can become an intentionally empty library`() {
        assertFalse(
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = 0,
                scannedBaseDirLastModified = 456L,
                persistedEntryCount = 539,
                persistedBaseDirLastModified = 123L,
            ),
        )
    }

    @Test
    fun `first empty library does not need a previous index`() {
        assertFalse(
            shouldReuseListingAfterUnexpectedEmptyScan(
                scannedDirectoryCount = 0,
                scannedBaseDirLastModified = 123L,
                persistedEntryCount = 0,
                persistedBaseDirLastModified = 123L,
            ),
        )
    }
}
