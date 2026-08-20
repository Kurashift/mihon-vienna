package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

@Execution(ExecutionMode.CONCURRENT)
class ChapterOrderTest {

    @Test
    fun `visible reorder preserves hidden chapter slots`() {
        mergeVisibleChapterOrder(
            currentIds = listOf(1L, 2L, 3L, 4L, 5L),
            orderedVisibleIds = listOf(5L, 3L, 1L),
        ) shouldBe listOf(5L, 2L, 3L, 4L, 1L)
    }

    @Test
    fun `full reorder replaces every chapter slot`() {
        mergeVisibleChapterOrder(
            currentIds = listOf(1L, 2L, 3L),
            orderedVisibleIds = listOf(3L, 1L, 2L),
        ) shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `unknown and duplicate visible ids are ignored`() {
        mergeVisibleChapterOrder(
            currentIds = listOf(1L, 2L, 3L),
            orderedVisibleIds = listOf(3L, 99L, 3L, 1L),
        ) shouldBe listOf(3L, 2L, 1L)
    }

    @Test
    fun `translated sort uses translated title then original title`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_TRANSLATED or Manga.CHAPTER_SORT_ASC,
        )
        val chapters = listOf(
            chapter(1, "Zulu", "同名"),
            chapter(2, "Alpha", "同名"),
            chapter(3, "Beta", null),
        )

        chapters.sortedWith(getChapterSort(manga)).map { it.id } shouldBe listOf(3L, 2L, 1L)
    }

    private fun chapter(id: Long, name: String, translatedName: String?): Chapter {
        return Chapter.create().copy(id = id, name = name, translatedName = translatedName)
    }
}
