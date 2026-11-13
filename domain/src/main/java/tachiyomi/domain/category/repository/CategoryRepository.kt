package tachiyomi.domain.category.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category

interface CategoryRepository {

    suspend fun get(id: Long): Category?

    suspend fun getAll(): List<Category>

    fun getAllAsFlow(): Flow<List<Category>>

    suspend fun getCategoriesByMangaId(mangaId: Long): List<Category>

    fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>>

    suspend fun insert(category: Category)

    /** Inserts [category] and returns the id the row got, for callers that must keep referring to it. */
    suspend fun insertReturningId(category: Category): Long

    suspend fun getMangaIdsByCategoryId(categoryId: Long): List<Long>

    /** Files [mangaId] under [categoryId] unless the pair is already there. */
    suspend fun addMangaCategoryIfMissing(mangaId: Long, categoryId: Long)

    suspend fun updateName(categoryId: Long, name: String)

    suspend fun updateFlags(categoryId: Long, flags: Long)

    suspend fun updateAllFlags(flags: Long?)

    suspend fun updateAllOrders(orderedIds: List<Long>)

    suspend fun delete(categoryId: Long)
}
