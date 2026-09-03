package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class GetMangaWithChapters(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun subscribe(id: Long, applyScanlatorFilter: Boolean = false): Flow<Pair<Manga, List<Chapter>>> {
        return combine(
            mangaRepository.getMangaByIdAsFlow(id),
            chapterRepository.getChapterByMangaIdAsFlow(id, applyScanlatorFilter),
        ) { manga, chapters ->
            Pair(manga, chapters)
        }
    }

    /**
     * Returns null when the row is already gone: the caller keeps ids across a local update, and a
     * directory that disappears takes its manga with it.
     */
    suspend fun awaitMangaOrNull(id: Long): Manga? {
        return mangaRepository.getMangaByIdOrNull(id)
    }

    suspend fun awaitChapters(id: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return chapterRepository.getChapterByMangaId(id, applyScanlatorFilter)
    }
}
