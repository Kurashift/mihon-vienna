package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.model.History
import tachiyomi.domain.manga.model.Manga

/**
 * Gets the chapter to continue reading.
 *
 * Prefers the most recently read chapter that is still in progress (not marked as read, with at
 * least one page read), so "continue" lands back on the page the user last stopped at. Falls back
 * to the next unread chapter with filters and sorting applied.
 */
fun List<Chapter>.getNextUnread(
    manga: Manga,
    downloadManager: DownloadManager,
    history: List<History> = emptyList(),
): Chapter? {
    val filtered = applyFilters(manga, downloadManager)
    getInProgressChapter(filtered, history)?.let { return it }
    return if (manga.sortDescending()) {
        filtered.findLast { !it.read }
    } else {
        filtered.find { !it.read }
    }
}

/**
 * Gets the chapter to continue reading.
 *
 * Prefers the most recently read chapter that is still in progress (not marked as read, with at
 * least one page read), so "continue" lands back on the page the user last stopped at. Falls back
 * to the next unread chapter with filters and sorting applied.
 */
fun List<ChapterList.Item>.getNextUnread(
    manga: Manga,
    history: List<History> = emptyList(),
): Chapter? {
    val filtered = applyFilters(manga).toList()
    getInProgressChapter(filtered.map { it.chapter }, history)?.let { return it }
    return if (manga.sortDescending()) {
        filtered.findLast { !it.chapter.read }
    } else {
        filtered.find { !it.chapter.read }
    }?.chapter
}

/**
 * Returns the most recently read chapter (per history) that is still in progress.
 */
private fun getInProgressChapter(chapters: List<Chapter>, history: List<History>): Chapter? {
    return history.asSequence()
        .filter { it.readAt != null }
        .sortedByDescending { it.readAt }
        .mapNotNull { historyItem -> chapters.find { it.id == historyItem.chapterId } }
        .firstOrNull { !it.read && it.lastPageRead > 0 }
}

/**
 * Returns the first chapter in reading order (the start of the story), regardless of read state.
 *
 * Used as the "continue" target when everything has been read, so the user can restart reading
 * from the beginning. Respects the display sort: when the list is shown newest-first, the story's
 * first chapter is the last item of the displayed order.
 */
fun List<ChapterList.Item>.getFirstChapter(manga: Manga): Chapter? {
    if (isEmpty()) return null
    val sorted = sortedWith { (c1), (c2) -> getChapterSort(manga).invoke(c1, c2) }
    return if (manga.sortDescending()) sorted.last().chapter else sorted.first().chapter
}
