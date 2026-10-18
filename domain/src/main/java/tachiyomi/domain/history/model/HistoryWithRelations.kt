package tachiyomi.domain.history.model

import tachiyomi.domain.manga.model.LOCAL_SOURCE_ID
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
    val chapterFlags: Long,
    val chapterName: String,
    val chapterTranslatedName: String?,
    val chapterUrl: String,
    val chapterVersion: Long,
    val chapterDateUpload: Long,
    val chapterLastModifiedAt: Long,
    val chapterLastPageRead: Long,
    val chapterTotalPages: Long,
    val chapterRead: Boolean,
) {
    val chapterTranslatedNameOrNull: String?
        get() = chapterTranslatedName?.trim()?.takeIf { it.isNotEmpty() }

    val displayMode: Long
        get() = chapterFlags and Manga.CHAPTER_DISPLAY_MASK

    val chapterDisplayName: String
        get() = when (displayMode) {
            Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            -> chapterTranslatedNameOrNull ?: chapterName
            else -> chapterName
        }
}

/**
 * Returns a copy of [this] with the library-wide local chapter display mode applied, leaving the
 * stored chapter flags untouched.
 *
 * Same contract as [tachiyomi.domain.manga.model.withLocalChapterDisplayMode], which the details
 * screen, the reader and the mark lists already use. History used to read the per-manga
 * `chapter_flags` straight out of `historyView`, so after switching local chapters to translated
 * titles only the works whose details screen had been opened showed the translated name there;
 * every other entry kept the old snapshot and fell back to the original title.
 *
 * The preference is applied to the copy used for display instead of being written back, so
 * scrolling through history never mutates the user's stored settings.
 *
 * @param localDisplayMode the library-wide display mode for local manga, see
 *   [tachiyomi.domain.library.service.LibraryPreferences.localChapterDisplayMode].
 */
fun HistoryWithRelations.withLocalChapterDisplayMode(localDisplayMode: Long): HistoryWithRelations {
    if (coverData.sourceId != LOCAL_SOURCE_ID) return this
    val targetMode = localDisplayMode and Manga.CHAPTER_DISPLAY_MASK
    if (displayMode == targetMode) return this
    return copy(
        chapterFlags = chapterFlags and Manga.CHAPTER_DISPLAY_MASK.inv() or targetMode,
    )
}
