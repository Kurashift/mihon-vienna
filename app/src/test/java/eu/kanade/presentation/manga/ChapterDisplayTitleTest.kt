package eu.kanade.presentation.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterDisplayTitleTest {

    private fun resolve(
        chapter: Chapter,
        displayMode: Long,
        showOriginalTitle: Boolean = true,
        chapterNumberLabel: String? = "第 1 篇",
    ): ChapterDisplayTitle = resolveChapterDisplayTitle(
        chapter = chapter,
        displayMode = displayMode,
        showOriginalTitle = showOriginalTitle,
        chapterNumberLabel = chapterNumberLabel,
    )

    private fun chapter(
        name: String = "original-name",
        translatedName: String? = null,
    ): Chapter = Chapter.create().copy(name = name, translatedName = translatedName)

    @Test
    fun `original mode shows the original name only`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "译名"),
            displayMode = Manga.CHAPTER_DISPLAY_NAME,
        )

        assertEquals("原名", result.title)
        assertNull(result.originalTitle)
    }

    @Test
    fun `translated only mode shows the translation without the original`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "译名"),
            displayMode = Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY,
        )

        assertEquals("译名", result.title)
        assertNull(result.originalTitle)
    }

    @Test
    fun `translated and original mode keeps the original as a subtitle`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "译名"),
            displayMode = Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
        )

        assertEquals("译名", result.title)
        assertEquals("原名", result.originalTitle)
    }

    @Test
    fun `translated and original mode falls back to the original name`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = null),
            displayMode = Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
        )

        assertEquals("原名", result.title)
        assertNull(result.originalTitle)
    }

    @Test
    fun `blank translated name falls back to the original name`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "   "),
            displayMode = Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
        )

        assertEquals("原名", result.title)
        assertNull(result.originalTitle)
    }

    @Test
    fun `translated and original mode drops the subtitle in layouts that have no room`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "译名"),
            displayMode = Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            showOriginalTitle = false,
        )

        // 网格布局下退化成「仅译名」，但设置值本身没变；切回列表仍会带原名。
        assertEquals("译名", result.title)
        assertNull(result.originalTitle)
    }

    @Test
    fun `number mode shows the chapter number label`() {
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "译名"),
            displayMode = Manga.CHAPTER_DISPLAY_NUMBER,
        )

        assertEquals("第 1 篇", result.title)
        assertNull(result.originalTitle)
    }

    @Test
    fun `omitted number label never breaks the other display modes`() {
        // 非篇目号模式下调用方不构造标签（省掉数字格式化），这里必须照常解析、不能空指针。
        val result = resolve(
            chapter = chapter(name = "原名", translatedName = "译名"),
            displayMode = Manga.CHAPTER_DISPLAY_TRANSLATED_AND_ORIGINAL,
            chapterNumberLabel = null,
        )

        assertEquals("译名", result.title)
        assertEquals("原名", result.originalTitle)
    }
}
