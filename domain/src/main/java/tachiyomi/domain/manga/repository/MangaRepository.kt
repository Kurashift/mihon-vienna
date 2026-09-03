package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgressByMangaId
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount

interface MangaRepository {

    suspend fun getMangaById(id: Long): Manga

    /**
     * Same read as [getMangaById] for a caller that can outlive the row: a local directory that
     * disappears takes its manga with it, and ids kept across an update then point at nothing.
     */
    suspend fun getMangaByIdOrNull(id: Long): Manga?

    fun getMangaByIdAsFlow(id: Long): Flow<Manga>

    suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga?

    fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?>

    suspend fun getFavorites(): List<Manga>

    /** Returns the ids of every manga that belongs to the local library. */
    suspend fun getLocalMangaIds(): List<Long>

    /**
     * Url by id for [ids]. Ids that no longer exist are simply absent from the result, so a
     * caller holding ids from another source of truth can resolve them without a per-id read.
     */
    suspend fun getMangaUrlsByIds(ids: Set<Long>): Map<Long, String>

    suspend fun getReadMangaNotInLibrary(): List<Manga>

    suspend fun getLibraryManga(): List<LibraryManga>

    fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>>

    suspend fun getFavoriteIdsBySourceId(sourceId: Long): List<Long>

    /**
     * Urls of the works of this source that are in the library.
     *
     * "Hide in-library items" is applied to the shelf by url, so the toolbar count needs the
     * same view of it; ids would have to be translated back to urls first, and any work the
     * translation does not cover would be counted while the shelf hides it.
     */
    suspend fun getFavoriteUrlsBySourceId(sourceId: Long): List<String>

    suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount>

    fun getMangaProgressBySourceAsFlow(sourceId: Long): Flow<List<MangaProgressByMangaId>>

    suspend fun getMangaProgressBySource(sourceId: Long): List<MangaProgressByMangaId>

    suspend fun getUpcomingManga(
        statuses: Set<Long>,
        excludedCategories: List<Long>,
        includedCategories: List<Long>,
    ): Flow<List<Manga>>

    suspend fun resetViewerFlags(): Boolean

    suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>)

    /**
     * Deletes the manga row. Chapters, history, categories, tracking, good-doujin flags and
     * excluded scanlators follow through foreign key cascade.
     */
    suspend fun deleteMangaById(mangaId: Long)

    suspend fun update(update: MangaUpdate): Boolean

    suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean

    suspend fun insertNetworkManga(manga: List<Manga>): List<Manga>
}
