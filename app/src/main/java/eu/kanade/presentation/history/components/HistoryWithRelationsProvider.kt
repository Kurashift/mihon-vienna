package eu.kanade.presentation.history.components

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import tachiyomi.domain.history.model.HistoryWithRelations
import java.util.Date

internal class HistoryWithRelationsProvider : PreviewParameterProvider<HistoryWithRelations> {

    private val simple = HistoryWithRelations(
        id = 1L,
        chapterId = 2L,
        mangaId = 3L,
        title = "Test Title",
        chapterNumber = 10.2,
        readAt = Date(1697247357L),
        readDuration = 123L,
        coverData = tachiyomi.domain.manga.model.MangaCover(
            mangaId = 3L,
            sourceId = 4L,
            isMangaFavorite = false,
            url = "https://example.com/cover.png",
            lastModified = 5L,
        ),
        chapterFlags = tachiyomi.domain.manga.model.Manga.CHAPTER_DISPLAY_NAME,
        chapterName = "Chapter 1",
        chapterTranslatedName = null,
        chapterUrl = "chapter-1",
        chapterVersion = 1L,
        chapterDateUpload = 1L,
        chapterLastModifiedAt = 1L,
    )

    private val historyWithoutReadAt = HistoryWithRelations(
        id = 1L,
        chapterId = 2L,
        mangaId = 3L,
        title = "Test Title",
        chapterNumber = 10.2,
        readAt = null,
        readDuration = 123L,
        coverData = tachiyomi.domain.manga.model.MangaCover(
            mangaId = 3L,
            sourceId = 4L,
            isMangaFavorite = false,
            url = "https://example.com/cover.png",
            lastModified = 5L,
        ),
        chapterFlags = tachiyomi.domain.manga.model.Manga.CHAPTER_DISPLAY_NAME,
        chapterName = "Chapter 1",
        chapterTranslatedName = null,
        chapterUrl = "chapter-1",
        chapterVersion = 1L,
        chapterDateUpload = 1L,
        chapterLastModifiedAt = 1L,
    )

    private val historyWithNegativeChapterNumber = HistoryWithRelations(
        id = 1L,
        chapterId = 2L,
        mangaId = 3L,
        title = "Test Title",
        chapterNumber = -2.0,
        readAt = Date(1697247357L),
        readDuration = 123L,
        coverData = tachiyomi.domain.manga.model.MangaCover(
            mangaId = 3L,
            sourceId = 4L,
            isMangaFavorite = false,
            url = "https://example.com/cover.png",
            lastModified = 5L,
        ),
        chapterFlags = tachiyomi.domain.manga.model.Manga.CHAPTER_DISPLAY_NAME,
        chapterName = "Chapter 1",
        chapterTranslatedName = null,
        chapterUrl = "chapter-1",
        chapterVersion = 1L,
        chapterDateUpload = 1L,
        chapterLastModifiedAt = 1L,
    )

    override val values: Sequence<HistoryWithRelations>
        get() = sequenceOf(simple, historyWithoutReadAt, historyWithNegativeChapterNumber)
}
