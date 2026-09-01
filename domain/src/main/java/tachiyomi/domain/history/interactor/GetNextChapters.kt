package tachiyomi.domain.history.interactor

import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.withLocalChapterDisplayMode
import kotlin.math.max

class GetNextChapters(
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
    private val historyRepository: HistoryRepository,
    private val libraryPreferences: LibraryPreferences,
) {

    suspend fun await(onlyUnread: Boolean = true): List<Chapter> {
        val history = historyRepository.getLastHistory() ?: return emptyList()
        return await(history.mangaId, history.chapterId, onlyUnread)
    }

    suspend fun await(mangaId: Long, onlyUnread: Boolean = true): List<Chapter> {
        val manga = getManga.await(mangaId) ?: return emptyList()
        // Local chapters are ordered by the title the user actually sees, so keep the same
        // display mode the chapter list uses instead of whatever is still stored.
        val mangaForSorting = manga.withLocalChapterDisplayMode(
            libraryPreferences.localChapterDisplayMode.get(),
        )
        val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
            .sortedWith(getChapterSort(mangaForSorting, sortDescending = false))

        return if (onlyUnread) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }
    }

    suspend fun await(
        mangaId: Long,
        fromChapterId: Long,
        onlyUnread: Boolean = true,
    ): List<Chapter> {
        val chapters = await(mangaId, onlyUnread)
        val currChapterIndex = chapters.indexOfFirst { it.id == fromChapterId }
        val nextChapters = chapters.subList(max(0, currChapterIndex), chapters.size)

        if (onlyUnread) {
            return nextChapters
        }

        // The "next chapter" is either:
        // - The current chapter if it isn't completely read
        // - The chapters after the current chapter if the current one is completely read
        val fromChapter = chapters.getOrNull(currChapterIndex)
        return if (fromChapter != null && !fromChapter.read) {
            nextChapters
        } else {
            nextChapters.drop(1)
        }
    }
}
