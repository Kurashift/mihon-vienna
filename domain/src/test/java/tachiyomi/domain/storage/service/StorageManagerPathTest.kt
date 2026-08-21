package tachiyomi.domain.storage.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StorageManagerPathTest {

    @Test
    fun `primary document id maps to a relative path`() {
        assertEquals("A_IMPORTANT/MIHON", primaryStorageRelativePath("primary:A_IMPORTANT/MIHON"))
    }

    @Test
    fun `non primary and parent traversal paths are rejected`() {
        assertNull(primaryStorageRelativePath("1234-5678:MIHON"))
        assertNull(primaryStorageRelativePath("primary:A_IMPORTANT/../Android"))
    }
}
