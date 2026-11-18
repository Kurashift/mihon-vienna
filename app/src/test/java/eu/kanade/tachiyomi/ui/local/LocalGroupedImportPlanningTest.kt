package eu.kanade.tachiyomi.ui.local

import eu.kanade.tachiyomi.data.local.LocalChapterTransferService
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
    fun `folder keys stay distinct until name collisions can be resolved`() {
        assertEquals(
            listOf("A:B/C", "A_B_C"),
            localImportCollectionNames(
                listOf(
                    LocalImportSourceShape("A:B/C", isDirectory = true, groupNames = emptyList()),
                    LocalImportSourceShape("A_B_C", isDirectory = true, groupNames = emptyList()),
                ),
            ),
        )
    }

    @Test
    fun `unusable groups never fall back to a valid manual destination`() {
        val originals = listOf("Alpha", "Beta")
        assertEquals(1, localGroupedImportNameCollisionCount(originals, mapOf("Beta" to "Alpha")))
        assertEquals(0, localGroupedImportNameCollisionCount(originals, mapOf("Beta" to "Gamma")))
        assertEquals(null, localImportDestination(true, true, false, 42L, "Previous"))
        assertEquals(LocalImportDestination.Grouped, localImportDestination(true, true, true, 42L, "Previous"))
        assertEquals(null, localImportDestination(false, false, false, 42L, "Previous"))
        assertEquals(null, localImportDestination(true, false, false, null, " "))
        assertEquals(LocalImportDestination.Existing(42L), localImportDestination(true, false, false, 42L, ""))
        assertEquals(LocalImportDestination.Named("A_B"), localImportDestination(true, false, false, null, "A:B"))
    }

    @Test
    fun `canceling a collection pick preserves either kind of target`() {
        val typed = LocalImportDraft(typedName = "Draft")
        assertEquals(typed, typed.afterCollectionPicker(null))
        val picked = typed.afterCollectionPicker(42L)
        assertEquals(typed.copy(pickedId = 42L), picked)
        assertEquals(picked, picked.afterCollectionPicker(null))
    }

    @Test
    fun `removing the last folder clears packing and an empty batch resets all options`() {
        val draft = LocalImportDraft("Draft", 42L, packFolders = true, deleteOriginals = true, expanded = true)
        val file = LocalImportSourceShape("chapter.cbz", false, emptyList())
        assertEquals(draft.copy(packFolders = false), draft.afterRemoval(listOf(file)))
        assertEquals(LocalImportDraft(), draft.afterRemoval(emptyList()))
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

    @Test
    fun `rejections accumulate while every pick is turned away`() {
        val first = listOf("Library" to LocalChapterTransferService.SourceRejection.InsideLibrary)
        val second = listOf("Empty" to LocalChapterTransferService.SourceRejection.NoContent)

        val afterSecond = localImportRejectionsAfterPick(
            previous = first,
            picked = second,
            usableSourceCount = 0,
        )

        assertEquals(first + second, afterSecond)
    }

    @Test
    fun `a pick that brings in a source clears the reasons nothing had appeared`() {
        // The reasons explained an empty screen. Leaving them above a source that just appeared
        // reads as a fresh failure of the pick that succeeded.
        val stale = listOf("Library" to LocalChapterTransferService.SourceRejection.InsideLibrary)

        val afterGoodPick = localImportRejectionsAfterPick(
            previous = stale,
            picked = emptyList(),
            usableSourceCount = 1,
        )

        assertEquals(emptyList<LocalImportRejection>(), afterGoodPick)
    }

    @Test
    fun `a mixed pick keeps only its own rejection`() {
        val stale = listOf("Library" to LocalChapterTransferService.SourceRejection.InsideLibrary)
        val own = listOf("Empty" to LocalChapterTransferService.SourceRejection.NoContent)

        val afterMixedPick = localImportRejectionsAfterPick(
            previous = stale,
            picked = own,
            usableSourceCount = 1,
        )

        assertEquals(own, afterMixedPick)
    }
}
