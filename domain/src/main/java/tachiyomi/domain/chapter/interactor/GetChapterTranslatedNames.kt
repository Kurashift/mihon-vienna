package tachiyomi.domain.chapter.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.repository.ChapterRepository

/**
 * Loads the manually assigned Chinese translated names (中文译名) of the chapters belonging to the
 * given manga, keyed by manga id. Used by library search so a chapter's translated name reaches its
 * manga even though the manga title itself is still in the original script.
 */
class GetChapterTranslatedNames(
    private val chapterRepository: ChapterRepository,
) {

    fun observe(mangaIds: List<Long>): Flow<Map<Long, List<String>>> {
        val distinctIds = mangaIds.distinct()
        return chapterRepository.observeTranslatedNames(distinctIds).map { entries ->
            entries
                .groupBy { it.mangaId }
                .mapValues { (_, names) -> names.map { it.name }.distinct() }
        }
    }
}
