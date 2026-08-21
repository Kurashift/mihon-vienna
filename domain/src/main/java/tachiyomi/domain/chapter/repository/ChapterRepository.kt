package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.Chapter
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
    )

    suspend fun removeChaptersWithIds(chapterIds: List<Long>)

    suspend fun setAllChaptersUnreadBySource(sourceId: Long)

    suspend fun setAllChaptersUnreadByMangaIds(mangaIds: List<Long>)

    suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getChaptersByMangaIds(mangaIds: List<Long>, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String>

    fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean = false): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter?
}
