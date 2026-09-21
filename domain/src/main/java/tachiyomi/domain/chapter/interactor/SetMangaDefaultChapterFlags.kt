package tachiyomi.domain.chapter.interactor

import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga

class SetMangaDefaultChapterFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val getFavorites: GetFavorites,
) {

    /**
     * Hands [manga] the current defaults, writing only if they would change something.
     *
     * Delegates to [awaitIfChanged] rather than writing the flags straight through: an update to a
     * manga row also refreshes its `last_modified_at` through a trigger, so a write that changes
     * nothing is not free - and [awaitAll] runs this for the whole library at once.
     *
     * @return true when flags were written.
     */
    suspend fun await(manga: Manga): Boolean = awaitIfChanged(manga)

    /**
     * Applies the chapter display defaults only when they would actually change the manga's
     * current flags. Opening a browse/local manga repeatedly writes the same flags on every
     * visit and, because it re-emits the chapter flow, re-sorts the list each time. Skipping
     * the no-op write also preserves any explicit sort/filter the user chose on the detail page.
     *
     * @return true when flags were written, false when they already matched the defaults.
     */
    suspend fun awaitIfChanged(manga: Manga): Boolean {
        return withNonCancellableContext {
            with(libraryPreferences) {
                val isLocal = manga.source == 0L
                val sortingMode = Manga.normalizeChapterSorting(
                    if (isLocal) sortChapterBySourceOrNumber.get() else sortCloudChapterBySourceOrNumber.get(),
                )
                val sortingDirection = if (isLocal) {
                    sortChapterByAscendingOrDescending.get()
                } else {
                    sortCloudChapterByAscendingOrDescending.get()
                }

                val current = manga.chapterFlags
                val unreadFilter = filterChapterByRead.get()
                val downloadedFilter = filterChapterByDownloaded.get()
                val bookmarkedFilter = filterChapterByBookmarked.get()
                val displayMode = if (isLocal) {
                    localChapterDisplayMode.get()
                } else {
                    displayChapterByNameOrNumber.get()
                }

                val matches =
                    current and Manga.CHAPTER_UNREAD_MASK == unreadFilter and Manga.CHAPTER_UNREAD_MASK &&
                        current and Manga.CHAPTER_DOWNLOADED_MASK == downloadedFilter and
                        Manga.CHAPTER_DOWNLOADED_MASK &&
                        current and Manga.CHAPTER_BOOKMARKED_MASK == bookmarkedFilter and
                        Manga.CHAPTER_BOOKMARKED_MASK &&
                        current and Manga.CHAPTER_SORTING_MASK == sortingMode and Manga.CHAPTER_SORTING_MASK &&
                        current and Manga.CHAPTER_SORT_DIR_MASK == sortingDirection and Manga.CHAPTER_SORT_DIR_MASK &&
                        current and Manga.CHAPTER_DISPLAY_MASK == displayMode and Manga.CHAPTER_DISPLAY_MASK

                if (matches) {
                    false
                } else {
                    setMangaChapterFlags.awaitSetAllFlags(
                        mangaId = manga.id,
                        unreadFilter = unreadFilter,
                        downloadedFilter = downloadedFilter,
                        bookmarkedFilter = bookmarkedFilter,
                        sortingMode = sortingMode,
                        sortingDirection = sortingDirection,
                        displayMode = displayMode,
                    )
                    true
                }
            }
        }
    }

    suspend fun awaitDisplayModeIfChanged(manga: Manga): Boolean {
        return withNonCancellableContext {
            val displayMode = libraryPreferences.localChapterDisplayMode.get()
            if (manga.displayMode == displayMode) {
                false
            } else {
                setMangaChapterFlags.awaitSetDisplayMode(manga, displayMode)
            }
        }
    }

    suspend fun awaitAll() {
        withNonCancellableContext {
            getFavorites.await().forEach { await(it) }
        }
    }
}
