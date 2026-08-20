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

    suspend fun await(manga: Manga) {
        withNonCancellableContext {
            with(libraryPreferences) {
                setMangaChapterFlags.awaitSetAllFlags(
                    mangaId = manga.id,
                    unreadFilter = filterChapterByRead.get(),
                    downloadedFilter = filterChapterByDownloaded.get(),
                    bookmarkedFilter = filterChapterByBookmarked.get(),
                    sortingMode = if (manga.source == 0L) {
                        sortChapterBySourceOrNumber.get()
                    } else {
                        sortCloudChapterBySourceOrNumber.get()
                    },
                    sortingDirection = if (manga.source == 0L) {
                        sortChapterByAscendingOrDescending.get()
                    } else {
                        sortCloudChapterByAscendingOrDescending.get()
                    },
                    displayMode = displayChapterByNameOrNumber.get(),
                )
            }
        }
    }

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
                val sortingMode = if (isLocal) {
                    sortChapterBySourceOrNumber.get()
                } else {
                    sortCloudChapterBySourceOrNumber.get()
                }
                val sortingDirection = if (isLocal) {
                    sortChapterByAscendingOrDescending.get()
                } else {
                    sortCloudChapterByAscendingOrDescending.get()
                }

                val current = manga.chapterFlags
                val unreadFilter = filterChapterByRead.get()
                val downloadedFilter = filterChapterByDownloaded.get()
                val bookmarkedFilter = filterChapterByBookmarked.get()
                val displayMode = displayChapterByNameOrNumber.get()

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

    suspend fun awaitAll() {
        withNonCancellableContext {
            getFavorites.await().forEach { await(it) }
        }
    }
}
