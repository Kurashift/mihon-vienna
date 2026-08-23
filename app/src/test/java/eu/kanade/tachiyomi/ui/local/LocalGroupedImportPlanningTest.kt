package eu.kanade.tachiyomi.ui.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalGroupedImportPlanningTest {

    @Test
    fun `valid author name is preserved`() {
        assertEquals("Author Name", localMangaDirectoryName(" Author Name "))
    }

    @Test
    fun `invalid target characters are replaced consistently`() {
        assertEquals("A_B_C", localMangaDirectoryName("A:B/C"))
    }

    @Test
    fun `same author from multiple roots shares one identity`() {
        assertEquals(
            localMangaDirectoryIdentity("Author"),
            localMangaDirectoryIdentity("author"),
        )
        assertEquals(0, localGroupedImportNameCollisionCount(listOf("Author", "author")))
    }

    @Test
    fun `different names that sanitize to one directory are rejected`() {
        assertEquals(1, localGroupedImportNameCollisionCount(listOf("A:B", "A_B")))
    }

    @Test
    fun `unicode equivalent names share one identity`() {
        assertEquals(
            localMangaDirectoryIdentity("Ａuthor"),
            localMangaDirectoryIdentity("Author"),
        )
        assertEquals(0, localGroupedImportNameCollisionCount(listOf("Ａuthor", "Author")))
    }

    @Test
    fun `blank author name is invalid`() {
        assertTrue(hasInvalidLocalGroupedImportName(listOf("Author", "   ")))
        assertFalse(hasInvalidLocalGroupedImportName(listOf("Author")))
    }

    @Test
    fun `existing exact author collection is reused`() {
        assertEquals(
            LocalGroupedImportTarget("Author", exists = true),
            resolveLocalGroupedImportTarget("Author", listOf("Author")),
        )
    }

    @Test
    fun `unique normalized author collection keeps its original url`() {
        assertEquals(
            LocalGroupedImportTarget("Ａuthor", exists = true),
            resolveLocalGroupedImportTarget("Author", listOf("Ａuthor")),
        )
    }

    @Test
    fun `missing author collection is planned for creation`() {
        assertEquals(
            LocalGroupedImportTarget("New Author", exists = false),
            resolveLocalGroupedImportTarget("New Author", listOf("Existing Author")),
        )
    }

    @Test
    fun `ambiguous normalized author collections are rejected`() {
        assertEquals(
            null,
            resolveLocalGroupedImportTarget("AUTHOR", listOf("Author", "author")),
        )
    }

    @Test
    fun `exact author wins when other normalized variants exist`() {
        assertEquals(
            LocalGroupedImportTarget("Author", exists = true),
            resolveLocalGroupedImportTarget("Author", listOf("Author", "author")),
        )
    }
}
