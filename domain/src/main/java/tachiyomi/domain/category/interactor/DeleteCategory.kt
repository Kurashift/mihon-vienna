package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DeleteCategory(
    private val categoryRepository: CategoryRepository,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
) {

    /**
     * What a delete takes away, kept so the pill that follows can put it back.
     *
     * A category is not one row: it is the row, every work filed under it, the slot it sat in, and
     * its id inside the update/download category filters. Undoing the delete has to bring all of
     * that back - and the id cannot be reused, so the restored category is a new row (or an
     * existing same-named one) and every reference is rewritten to point at it.
     */
    data class Snapshot(
        val category: Category,
        val orderedIds: List<Long>,
        val mangaIds: List<Long>,
        val defaultCategory: Int,
        /** Keys of the category filters this category was selected in before the delete. */
        val selectedPreferenceKeys: Set<String>,
    )

    private val categoryPreferences: List<Preference<Set<String>>>
        get() = listOf(
            libraryPreferences.updateCategories,
            libraryPreferences.updateCategoriesExclude,
            downloadPreferences.removeExcludeCategories,
            downloadPreferences.downloadNewChapterCategories,
            downloadPreferences.downloadNewChapterCategoriesExclude,
        )

    suspend fun await(categoryId: Long) = withNonCancellableContext {
        val category = categoryRepository.get(categoryId)
            ?: return@withNonCancellableContext Result.NotFound

        // Captured before anything is removed: the members and the order are gone with the row.
        val snapshot = Snapshot(
            category = category,
            orderedIds = categoryRepository.getAll().map { it.id },
            mangaIds = categoryRepository.getMangaIdsByCategoryId(categoryId),
            defaultCategory = libraryPreferences.defaultCategory.get(),
            selectedPreferenceKeys = categoryPreferences
                .filter { categoryId.toString() in it.get() }
                .mapTo(HashSet()) { it.key() },
        )

        try {
            categoryRepository.delete(categoryId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        val orderedIds = categoryRepository.getAll().map { it.id }

        if (snapshot.defaultCategory == categoryId.toInt()) {
            libraryPreferences.defaultCategory.delete()
        }

        val categoryIdString = categoryId.toString()
        categoryPreferences.forEach { preference ->
            val ids = preference.get()
            if (categoryIdString !in ids) return@forEach
            preference.set(ids.minus(categoryIdString))
        }

        try {
            categoryRepository.updateAllOrders(orderedIds = orderedIds)
            Result.Success(snapshot)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.InternalError(e)
        }
    }

    /**
     * Puts the category back, members, slot and filter ticks included.
     *
     * If a category with the same name has appeared in the meantime - the pill is on screen while
     * the user can keep tapping - the deleted one merges into it instead of leaving two rows that
     * read the same: one category, the same shelves, the same filter ticks.
     */
    suspend fun restore(snapshot: Snapshot): Boolean = withNonCancellableContext {
        try {
            val sameName = categoryRepository.getAll().firstOrNull { it.name == snapshot.category.name }
            val targetId = sameName?.id
                ?: categoryRepository.insertReturningId(snapshot.category.copy(id = 0))

            snapshot.mangaIds.forEach { mangaId ->
                categoryRepository.addMangaCategoryIfMissing(mangaId, targetId)
            }

            // The old id is dead: the restored row takes its slot in the tab order instead.
            categoryRepository.updateAllOrders(
                snapshot.orderedIds
                    .map { if (it == snapshot.category.id) targetId else it }
                    .distinct(),
            )

            val targetIdString = targetId.toString()
            categoryPreferences
                .filter { it.key() in snapshot.selectedPreferenceKeys }
                .forEach { preference ->
                    val ids = preference.get()
                    if (targetIdString !in ids) preference.set(ids + targetIdString)
                }

            if (snapshot.defaultCategory == snapshot.category.id.toInt()) {
                libraryPreferences.defaultCategory.set(targetId.toInt())
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    sealed interface Result {
        data class Success(val snapshot: Snapshot) : Result
        data object NotFound : Result
        data class InternalError(val error: Throwable) : Result
    }
}
