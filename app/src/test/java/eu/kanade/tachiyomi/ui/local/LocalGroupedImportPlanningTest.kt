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

    @Test
    fun `folder whose children are containers contributes one collection per container`() {
        assertEquals(
            listOf("Author A", "Author B"),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape(
                        displayName = "Root",
                        isDirectory = true,
                        groupNames = listOf("Author A", "Author B"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `folder holding the works directly is itself the collection`() {
        assertEquals(
            listOf("My Collection"),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape(
                        displayName = "My Collection",
                        isDirectory = true,
                        groupNames = emptyList(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `several folders become several collections named after each`() {
        assertEquals(
            listOf("One", "Two"),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape("One", isDirectory = true, groupNames = emptyList()),
                    LocalImportSourceShape("Two", isDirectory = true, groupNames = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `two folders of one name are one collection`() {
        assertEquals(
            listOf("Same"),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape("Same", isDirectory = true, groupNames = emptyList()),
                    LocalImportSourceShape("Same", isDirectory = true, groupNames = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `collection names are sanitized for the target directory`() {
        assertEquals(
            listOf("A_B_C"),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape("A:B/C", isDirectory = true, groupNames = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `a batch holding a file contributes no collections`() {
        assertEquals(
            emptyList<String>(),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape("One", isDirectory = true, groupNames = emptyList()),
                    LocalImportSourceShape("Chapter.cbz", isDirectory = false, groupNames = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `a batch of folders is a collection import`() {
        assertTrue(
            isLocalCollectionImport(
                listOf(
                    LocalImportSourceShape("One", isDirectory = true, groupNames = emptyList()),
                    LocalImportSourceShape("Two", isDirectory = true, groupNames = listOf("Author")),
                ),
            ),
        )
    }

    @Test
    fun `a batch holding a file is not a collection import`() {
        assertFalse(
            isLocalCollectionImport(
                listOf(
                    LocalImportSourceShape("One", isDirectory = true, groupNames = emptyList()),
                    LocalImportSourceShape("Chapter.cbz", isDirectory = false, groupNames = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `an empty batch is not a collection import`() {
        assertFalse(isLocalCollectionImport(emptyList()))
    }
}
