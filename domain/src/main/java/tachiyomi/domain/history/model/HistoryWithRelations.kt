package tachiyomi.domain.history.model

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
) {
    val chapterTranslatedNameOrNull: String?
        get() = chapterTranslatedName?.trim()?.takeIf { it.isNotEmpty() }

    val chapterDisplayName: String
        get() = when (chapterFlags and Manga.CHAPTER_DISPLAY_MASK) {
            Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            -> chapterTranslatedNameOrNull ?: chapterName
            else -> chapterName
        }
}
