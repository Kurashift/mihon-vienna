package eu.kanade.tachiyomi.data.manga

import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * Resolves which local manga a random pick may draw from, anchored on the work the user is
 * currently reading.
 *
 * Local random selection used to draw from the whole local source, so a pick started inside one
 * bookshelf could land in any other. Staying on the anchor's shelf keeps the jump meaningful:
 * entering a work from shelf A and swiping should stay in A.
 *
 * The default shelf needs care. It is never written to the membership table — a work is on it
 * precisely by having no category row — so an uncategorized anchor resolves to "every
 * uncategorized local work" rather than to no scope at all. That is the common case for a local
 * library browsed straight from the local source, where works are read without being filed.
 */
class LocalRandomScope(
    private val mangaRepository: MangaRepository,
    private val categoryRepository: CategoryRepository,
) {

    /**
     * Ids of the local manga on the same shelf as [anchorMangaId], always including the anchor
     * itself.
     *
     * Returns null only when no scope can be derived at all, which lets callers keep their own
     * pool instead of being handed an empty one that would silently disable the picker.
     */
    suspend fun resolveLocalMangaIds(anchorMangaId: Long): List<Long>? {
        if (anchorMangaId <= 0) return null
        val categoryIds = categoryRepository.getCategoriesByMangaId(anchorMangaId)
            .filterNot { it.isSystemCategory }
            .map { it.id }
        return mangaRepository.getLocalMangaIdsOnSameShelf(
            categoryIds = categoryIds,
            includeDefault = categoryIds.isEmpty(),
        )
    }
}
