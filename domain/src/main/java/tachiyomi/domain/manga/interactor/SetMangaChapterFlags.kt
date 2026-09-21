package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaChapterFlags(
    private val mangaRepository: MangaRepository,
) {

    suspend fun awaitSetDownloadedFilter(manga: Manga, flag: Long): Boolean {
        val newFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_DOWNLOADED_MASK)
        if (newFlags == manga.chapterFlags) return false
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetUnreadFilter(manga: Manga, flag: Long): Boolean {
        val newFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_UNREAD_MASK)
        if (newFlags == manga.chapterFlags) return false
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetBookmarkFilter(manga: Manga, flag: Long): Boolean {
        val newFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_BOOKMARKED_MASK)
        if (newFlags == manga.chapterFlags) return false
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetDisplayMode(manga: Manga, flag: Long): Boolean {
        val newFlags = manga.chapterFlags.setFlag(flag, Manga.CHAPTER_DISPLAY_MASK)
        if (newFlags == manga.chapterFlags) return false
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(manga: Manga, flag: Long): Boolean {
        val newFlags = manga.chapterFlags.let {
            if (manga.sorting == flag) {
                // Just flip the order (custom order has no meaningful reverse, keep ascending)
                val orderFlag = if (flag == Manga.CHAPTER_SORTING_CUSTOM || manga.sortDescending()) {
                    Manga.CHAPTER_SORT_ASC
                } else {
                    Manga.CHAPTER_SORT_DESC
                }
                it.setFlag(flag, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(orderFlag, Manga.CHAPTER_SORT_DIR_MASK)
            } else {
                // Set new flag with ascending order
                it
                    .setFlag(flag, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DIR_MASK)
            }
        }
        if (newFlags == manga.chapterFlags) return false
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    suspend fun awaitSetSortingAndDirection(manga: Manga, sortingMode: Long, sortingDirection: Long): Boolean {
        val newFlags = manga.chapterFlags
            .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
            .setFlag(sortingDirection, Manga.CHAPTER_SORT_DIR_MASK)
        if (newFlags == manga.chapterFlags) return false
        return mangaRepository.update(
            MangaUpdate(
                id = manga.id,
                chapterFlags = newFlags,
            ),
        )
    }

    /**
     * Writes the whole flag set, unconditionally.
     *
     * No comparison is possible here: this overload takes an id, not the [Manga] the current flags
     * would have to be read off, so it cannot tell whether the write changes anything. Callers that
     * have the manga in hand should compare first - see [SetMangaDefaultChapterFlags.awaitIfChanged],
     * which is the shape to copy. The write matters more than it looks: any update to a manga row
     * also refreshes its `last_modified_at` through a trigger, so a no-op write is not free.
     */
    suspend fun awaitSetAllFlags(
        mangaId: Long,
        unreadFilter: Long,
        downloadedFilter: Long,
        bookmarkedFilter: Long,
        sortingMode: Long,
        sortingDirection: Long,
        displayMode: Long,
    ): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                chapterFlags = 0L.setFlag(unreadFilter, Manga.CHAPTER_UNREAD_MASK)
                    .setFlag(downloadedFilter, Manga.CHAPTER_DOWNLOADED_MASK)
                    .setFlag(bookmarkedFilter, Manga.CHAPTER_BOOKMARKED_MASK)
                    .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
                    .setFlag(sortingDirection, Manga.CHAPTER_SORT_DIR_MASK)
                    .setFlag(displayMode, Manga.CHAPTER_DISPLAY_MASK),
            ),
        )
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
