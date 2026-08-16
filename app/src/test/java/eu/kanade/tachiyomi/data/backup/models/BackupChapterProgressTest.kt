package eu.kanade.tachiyomi.data.backup.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupChapterProgressTest {

    @Test
    fun `completed restore is normalized to final page`() {
        assertEquals(12, normalizeRestoredLastPageRead(read = true, lastPageRead = 8, totalPages = 12))
    }

    @Test
    fun `unread restore cannot occupy completion slot`() {
        assertEquals(11, normalizeRestoredLastPageRead(read = false, lastPageRead = 12, totalPages = 12))
        assertEquals(0, normalizeRestoredLastPageRead(read = false, lastPageRead = 1, totalPages = 1))
    }

    @Test
    fun `unknown page count preserves nonnegative progress`() {
        assertEquals(7, normalizeRestoredLastPageRead(read = false, lastPageRead = 7, totalPages = 0))
        assertEquals(0, normalizeRestoredLastPageRead(read = false, lastPageRead = -1, totalPages = 0))
    }
}
