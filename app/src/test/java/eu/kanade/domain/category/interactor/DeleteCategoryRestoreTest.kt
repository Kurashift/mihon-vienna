package eu.kanade.domain.category.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DeleteCategoryRestoreTest {

    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val updateCategories = stringSetPreference("update_categories")
    private val updateCategoriesExclude = stringSetPreference("update_categories_exclude")
    private val removeExcludeCategories = stringSetPreference("remove_exclude_categories")
    private val downloadNewChapterCategories = stringSetPreference("download_new_categories")
    private val downloadNewChapterCategoriesExclude = stringSetPreference("download_new_categories_exclude")
    private val defaultCategory = mockk<Preference<Int>>(relaxed = true)

    private val libraryPreferences = mockk<LibraryPreferences> {
        every { updateCategories } returns this@DeleteCategoryRestoreTest.updateCategories
        every { updateCategoriesExclude } returns this@DeleteCategoryRestoreTest.updateCategoriesExclude
        every { defaultCategory } returns this@DeleteCategoryRestoreTest.defaultCategory
    }
    private val downloadPreferences = mockk<DownloadPreferences> {
        every { removeExcludeCategories } returns this@DeleteCategoryRestoreTest.removeExcludeCategories
        every { downloadNewChapterCategories } returns this@DeleteCategoryRestoreTest.downloadNewChapterCategories
        every {
            downloadNewChapterCategoriesExclude
        } returns this@DeleteCategoryRestoreTest.downloadNewChapterCategoriesExclude
    }

    private val subject = DeleteCategory(categoryRepository, libraryPreferences, downloadPreferences)

    @Test
    fun `restoring brings back the name, the slot, the works and the filter ticks`() = runBlocking {
        coEvery { categoryRepository.getAll() } returns listOf(
            category(id = 1, name = "A", order = 0),
            category(id = 3, name = "C", order = 1),
        )
        coEvery { categoryRepository.insertReturningId(any()) } returns 9L

        assertTrue(subject.restore(snapshot()))

        coVerify { categoryRepository.insertReturningId(match { it.name == "B" && it.flags == 7L }) }
        coVerify { categoryRepository.addMangaCategoryIfMissing(10, 9) }
        coVerify { categoryRepository.addMangaCategoryIfMissing(11, 9) }
        // The old id is dead: the restored row takes its slot in the tab order.
        coVerify { categoryRepository.updateAllOrders(listOf(1L, 9L, 3L)) }
        // It was the default shelf and a filter tick: both point at the new row.
        verify { defaultCategory.set(9) }
        verify { updateCategories.set(setOf("1", "9")) }
        // Not selected anywhere before, so nothing else is rewritten.
        verify(exactly = 0) { updateCategoriesExclude.set(any()) }
        verify(exactly = 0) { downloadNewChapterCategories.set(any()) }
    }

    @Test
    fun `a same-named category that appeared meanwhile absorbs the restored one`() = runBlocking {
        // The pill is on screen while the user can keep tapping: a second row reading "B" would be
        // two shelves with one name, so the restored works and ticks merge into the existing one.
        coEvery { categoryRepository.getAll() } returns listOf(
            category(id = 1, name = "A", order = 0),
            category(id = 3, name = "C", order = 1),
            category(id = 7, name = "B", order = 2),
        )

        assertTrue(subject.restore(snapshot()))

        coVerify(exactly = 0) { categoryRepository.insertReturningId(any()) }
        coVerify { categoryRepository.addMangaCategoryIfMissing(10, 7) }
        coVerify { categoryRepository.addMangaCategoryIfMissing(11, 7) }
        coVerify { categoryRepository.updateAllOrders(listOf(1L, 7L, 3L)) }
        verify { updateCategories.set(setOf("1", "7")) }
    }

    @Test
    fun `the default shelf is left alone when the deleted category was not it`() = runBlocking {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { categoryRepository.insertReturningId(any()) } returns 9L

        assertTrue(subject.restore(snapshot(defaultCategory = 1)))

        verify(exactly = 0) { defaultCategory.set(any()) }
    }

    @Test
    fun `deleting captures the members and the slot before the row goes`() = runBlocking {
        val deleted = category(id = 2, name = "B", order = 1)
        every { defaultCategory.get() } returns 2
        every { updateCategories.get() } returns setOf("2", "5")
        coEvery { categoryRepository.get(2) } returns deleted
        // Before the delete, then after it: the second read is what the renumbering works from.
        coEvery { categoryRepository.getAll() } returnsMany listOf(
            listOf(category(1, "A", 0), deleted, category(3, "C", 2)),
            listOf(category(1, "A", 0), category(3, "C", 1)),
        )
        coEvery { categoryRepository.getMangaIdsByCategoryId(2) } returns listOf(10, 11)

        val result = subject.await(2) as DeleteCategory.Result.Success

        assertEquals(listOf(1L, 2L, 3L), result.snapshot.orderedIds)
        assertEquals(listOf(10L, 11L), result.snapshot.mangaIds)
        assertEquals(2, result.snapshot.defaultCategory)
        assertEquals(setOf("update_categories"), result.snapshot.selectedPreferenceKeys)
        coVerify { categoryRepository.delete(2) }
    }

    @Test
    fun `a category that is already gone is not reported as an internal error`() = runBlocking {
        coEvery { categoryRepository.get(2) } returns null

        assertEquals(DeleteCategory.Result.NotFound, subject.await(2))
        coVerify(exactly = 0) { categoryRepository.delete(any()) }
    }

    private fun snapshot(defaultCategory: Int = 2) = DeleteCategory.Snapshot(
        category = category(id = 2, name = "B", order = 1),
        orderedIds = listOf(1L, 2L, 3L),
        mangaIds = listOf(10L, 11L),
        defaultCategory = defaultCategory,
        selectedPreferenceKeys = setOf("update_categories"),
    )

    private fun category(id: Long, name: String, order: Long) = Category(
        id = id,
        name = name,
        order = order,
        flags = 7,
    )

    private fun stringSetPreference(key: String): Preference<Set<String>> {
        val preference = mockk<Preference<Set<String>>>(relaxed = true)
        every { preference.key() } returns key
        every { preference.get() } returns setOf("1")
        return preference
    }
}
