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

    @Test
    fun `translated sort recognizes chinese and arabic chapter numbers`() {
        translatedTitlesSorted(
            "少女特异点 第十章",
            "少女特异点 第2章",
            "少女特异点 第三章",
            "少女特异点 第一章",
        ) shouldBe listOf(
            "少女特异点 第一章",
            "少女特异点 第2章",
            "少女特异点 第三章",
            "少女特异点 第十章",
        )
    }

    @Test
    fun `translated sort recognizes front middle back and upper middle lower`() {
        translatedTitlesSorted(
            "魔法少女的报恩 后篇 [汉化]",
            "魔法少女的报恩 前篇 [汉化]",
            "魔法少女的报恩 中篇 [汉化]",
        ) shouldBe listOf(
            "魔法少女的报恩 前篇 [汉化]",
            "魔法少女的报恩 中篇 [汉化]",
            "魔法少女的报恩 后篇 [汉化]",
        )
        translatedTitlesSorted("星穹铁道妈妈 下", "星穹铁道妈妈 上", "星穹铁道妈妈 中") shouldBe
            listOf("星穹铁道妈妈 上", "星穹铁道妈妈 中", "星穹铁道妈妈 下")
        translatedTitlesSorted("故事 下篇", "故事 中篇", "故事 上篇") shouldBe
            listOf("故事 上篇", "故事 中篇", "故事 下篇")
    }

    @Test
    fun `translated sort recognizes strict roman numeral suffixes`() {
        translatedTitlesSorted(
            "秘密花园Plus IV (花骑士)",
            "秘密花园Plus II (花骑士)",
            "秘密花园Plus III (花骑士)",
        ) shouldBe listOf(
            "秘密花园Plus II (花骑士)",
            "秘密花园Plus III (花骑士)",
            "秘密花园Plus IV (花骑士)",
        )
    }

    @Test
    fun `translated sort does not treat english letters or metadata as sequence markers`() {
        ChapterTitleSortRules.sortKey("Honey x Honey") shouldBe "Honey x Honey"
        ChapterTitleSortRules.sortKey("Honey V Honey") shouldBe "Honey V Honey"
        ChapterTitleSortRules.sortKey("邻家的女友 (COMIC X-EROS #32)") shouldBe
            "邻家的女友 (COMIC X-EROS #32)"
        ChapterTitleSortRules.sortKey("故事前篇+中篇+后篇") shouldBe "故事前篇+中篇+后篇"
    }

    @Test
    fun `translated sequence sort remains transitive with unrelated titles`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_TRANSLATED or Manga.CHAPTER_SORT_ASC,
        )
        val chapters = listOf(
            chapter(1, "one", "第一话"),
            chapter(2, "two", "第二话"),
            chapter(3, "other", "北京"),
            chapter(4, "roman", "Honey V Honey"),
        )
        val comparator = getChapterSort(manga)

        chapters.forEach { first ->
            chapters.forEach { second ->
                chapters.forEach { third ->
                    if (comparator(first, second) <= 0 && comparator(second, third) <= 0) {
                        (comparator(first, third) <= 0) shouldBe true
                    }
                }
            }
        }
    }

    @Test
    fun `translated sort supports descending sequence order`() {
        translatedTitlesSorted(
            "N's A COLORS #2 [汉化]",
            "N's A COLORS #10 [汉化]",
            "N's A COLORS #3 [汉化]",
            descending = true,
        ) shouldBe listOf(
            "N's A COLORS #10 [汉化]",
            "N's A COLORS #3 [汉化]",
            "N's A COLORS #2 [汉化]",
        )
    }

    private fun translatedTitlesSorted(vararg titles: String, descending: Boolean = false): List<String> {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_TRANSLATED or
                if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
        return titles.mapIndexed { index, title -> chapter(index.toLong() + 1, "original-$index", title) }
            .sortedWith(getChapterSort(manga))
            .map { it.translatedNameOrNull.orEmpty() }
    }

    private fun chapter(id: Long, name: String, translatedName: String?): Chapter {
        return Chapter.create().copy(id = id, name = name, translatedName = translatedName)
    }
}
