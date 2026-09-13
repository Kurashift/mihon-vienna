package eu.kanade.tachiyomi.data.manga

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

class LocalRandomScopeTest {

    private val mangaRepository = mockk<MangaRepository>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>()
    private val scope = LocalRandomScope(mangaRepository, categoryRepository)

    @Test
    fun `filed anchor scopes to its categories and never asks for the default shelf`() = runTest {
        coEvery { categoryRepository.getCategoriesByMangaId(1L) } returns listOf(
            category(id = 0L, isSystem = true),
            category(id = 7L, isSystem = false),
        )
        coEvery {
            mangaRepository.getLocalMangaIdsOnSameShelf(listOf(7L), includeDefault = false)
        } returns listOf(1L, 2L, 3L)

        assertEquals(listOf(1L, 2L, 3L), scope.resolveLocalMangaIds(1L))
    }

    @Test
    fun `unfiled anchor scopes to the default shelf instead of the whole library`() = runTest {
        coEvery { categoryRepository.getCategoriesByMangaId(1L) } returns emptyList()
        coEvery {
            mangaRepository.getLocalMangaIdsOnSameShelf(emptyList(), includeDefault = true)
        } returns listOf(1L, 4L)

        assertEquals(listOf(1L, 4L), scope.resolveLocalMangaIds(1L))
    }

    @Test
    fun `anchor whose only category is the system one is treated as unfiled`() = runTest {
        coEvery { categoryRepository.getCategoriesByMangaId(1L) } returns listOf(
            category(id = 0L, isSystem = true),
        )
        coEvery {
            mangaRepository.getLocalMangaIdsOnSameShelf(emptyList(), includeDefault = true)
        } returns listOf(1L, 4L)

        assertEquals(listOf(1L, 4L), scope.resolveLocalMangaIds(1L))
    }

    @Test
    fun `invalid anchor id never queries the repository`() = runTest {
        assertNull(scope.resolveLocalMangaIds(0L))
        assertNull(scope.resolveLocalMangaIds(-1L))
        coVerify(exactly = 0) { mangaRepository.getLocalMangaIdsOnSameShelf(any(), any()) }
    }

    private fun category(id: Long, isSystem: Boolean): Category {
        return Category(id = id, name = "c$id", order = 0L, flags = 0L).also {
            assertEquals(isSystem, it.isSystemCategory, "category $id system flag")
        }
    }
}
