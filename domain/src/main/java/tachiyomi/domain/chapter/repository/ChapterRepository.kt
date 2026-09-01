package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterTranslatedName
import tachiyomi.domain.chapter.model.ChapterUpdate

interface ChapterRepository {

    suspend fun addAll(chapters: List<Chapter>): List<Chapter>

    suspend fun update(chapterUpdate: ChapterUpdate)

    suspend fun updateAll(chapterUpdates: List<ChapterUpdate>)

    suspend fun relocateAll(chapterUpdates: List<ChapterUpdate>)

    suspend fun mergeRelocatedChapter(chapterUpdate: ChapterUpdate, duplicateChapterId: Long)

    suspend fun bumpVersion(chapterId: Long)

    suspend fun updateReaderProgress(
        chapterId: Long,
        pageNumber: Long,
        totalPages: Long,
        completed: Boolean,
        completedAt: Long,
    )

    suspend fun removeChaptersWithIds(chapterIds: List<Long>)

    suspend fun setAllChaptersUnreadBySource(sourceId: Long)

    suspend fun setAllChaptersUnreadByMangaIds(mangaIds: List<Long>)

    suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getChaptersByMangaIds(mangaIds: List<Long>, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getTranslatedNames(mangaIds: List<Long>): List<ChapterTranslatedName>

    fun observeTranslatedNames(mangaIds: List<Long>): Flow<List<ChapterTranslatedName>>

    /**
     * Returns the manually assigned Chinese translated names (中文译名) of every chapter whose
     * manga belongs to [sourceId], keyed by the manga's url - the only identifier the local source
     * can match against, since it only ever sees directory names.
     *
     * Only chapters that actually carry a translated name are returned.
     */
    suspend fun getTranslatedNamesBySourceId(sourceId: Long): Map<String, List<String>>

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String>

    fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean = false): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter?
}
