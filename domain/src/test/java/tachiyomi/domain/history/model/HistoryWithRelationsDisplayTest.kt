package tachiyomi.domain.history.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.manga.model.LOCAL_SOURCE_ID
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

/**
 * 历史条目在库级显示模式下的标题。
 *
 * 这里覆盖的是历史页与详情页、阅读器同源的那一步：本地作品按当前偏好取显示名，
 * 而不是按 historyView 里每本 manga 上一次写库的 chapter_flags。
 */
@Execution(ExecutionMode.CONCURRENT)
class HistoryWithRelationsDisplayTest {

    @Test
    fun `local entry follows the library translated mode over its stored flags`() {
        val entry = history(chapterFlags = Manga.CHAPTER_DISPLAY_NAME, translatedName = "中文译名")

        entry.chapterDisplayName shouldBe "原名"
        entry.withLocalChapterDisplayMode(Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY)
            .chapterDisplayName shouldBe "中文译名"
    }

    @Test
    fun `local entry keeps the original title when it has no translation`() {
        val entry = history(translatedName = null)

        entry.withLocalChapterDisplayMode(Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY)
            .chapterDisplayName shouldBe "原名"
    }

    @Test
    fun `blank translation falls back to the original title`() {
        val entry = history(translatedName = "   ")

        entry.withLocalChapterDisplayMode(Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL)
            .chapterDisplayName shouldBe "原名"
    }

    @Test
    fun `library original mode hides a translation that is already stored`() {
        val entry = history(
            chapterFlags = Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            translatedName = "中文译名",
        )

        entry.withLocalChapterDisplayMode(Manga.CHAPTER_DISPLAY_NAME)
            .chapterDisplayName shouldBe "原名"
    }

    @Test
    fun `cloud entries keep their own display mode`() {
        val entry = history(
            sourceId = 42L,
            chapterFlags = Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
            translatedName = "中文译名",
        )

        val display = entry.withLocalChapterDisplayMode(Manga.CHAPTER_DISPLAY_NAME)

        display.chapterFlags shouldBe entry.chapterFlags
        display.chapterDisplayName shouldBe "中文译名"
    }

    @Test
    fun `applying the preference only touches the display bits`() {
        val entry = history(
            chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or
                Manga.CHAPTER_DISPLAY_NAME or
                Manga.CHAPTER_SORT_DESC,
        )

        val display = entry.withLocalChapterDisplayMode(Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY)

        display.displayMode shouldBe Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY
        display.chapterFlags and Manga.CHAPTER_SORTING_MASK shouldBe Manga.CHAPTER_SORTING_ALPHABET
        display.chapterFlags and Manga.CHAPTER_SORT_DIR_MASK shouldBe Manga.CHAPTER_SORT_DESC
    }

    private fun history(
        sourceId: Long = LOCAL_SOURCE_ID,
        chapterFlags: Long = Manga.CHAPTER_DISPLAY_NAME,
        translatedName: String? = null,
    ): HistoryWithRelations {
        return HistoryWithRelations(
            id = 1L,
            chapterId = 2L,
            mangaId = 3L,
            title = "作品名",
            chapterNumber = 1.0,
            readAt = Date(0L),
            readDuration = 0L,
            coverData = MangaCover(
                mangaId = 3L,
                sourceId = sourceId,
                isMangaFavorite = true,
                url = null,
                lastModified = 0L,
            ),
            chapterFlags = chapterFlags,
            chapterName = "原名",
            chapterTranslatedName = translatedName,
            chapterUrl = "chapter-url",
            chapterVersion = 1L,
            chapterDateUpload = 0L,
            chapterLastModifiedAt = 0L,
            chapterLastPageRead = 0L,
            chapterTotalPages = 10L,
            chapterRead = false,
        )
    }
}
