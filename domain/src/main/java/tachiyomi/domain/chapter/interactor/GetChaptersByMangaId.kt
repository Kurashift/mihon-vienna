package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return try {
            chapterRepository.getChapterByMangaId(mangaId, applyScanlatorFilter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    suspend fun await(mangaIds: List<Long>, applyScanlatorFilter: Boolean = false): List<Chapter> {
        if (mangaIds.isEmpty()) return emptyList()
        return try {
            mangaIds.distinct()
                .chunked(MANGA_IDS_QUERY_CHUNK_SIZE)
                .flatMap { chapterRepository.getChaptersByMangaIds(it, applyScanlatorFilter) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }
}

private const val MANGA_IDS_QUERY_CHUNK_SIZE = 500
