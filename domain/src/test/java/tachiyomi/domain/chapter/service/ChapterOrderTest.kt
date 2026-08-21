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
    fun `title sort follows translated display then original title`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or
                Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY or
                Manga.CHAPTER_SORT_ASC,
        )
        val chapters = listOf(
            chapter(1, "Zulu", "同名"),
            chapter(2, "Alpha", "同名"),
            chapter(3, "Beta", null),
        )

        chapters.sortedWith(getChapterSort(manga)).map { it.id } shouldBe listOf(3L, 2L, 1L)
    }

    @Test
    fun `title sort uses original names in original display mode`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or Manga.CHAPTER_DISPLAY_NAME or Manga.CHAPTER_SORT_ASC,
        )
        val chapters = listOf(
            chapter(1, "Zulu", "Alpha"),
            chapter(2, "Alpha", "Zulu"),
        )

        chapters.sortedWith(getChapterSort(manga)).map { it.id } shouldBe listOf(2L, 1L)
    }

    @Test
    fun `title sort recognizes chinese and arabic chapter numbers`() {
        displayedTitlesSorted(
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
    fun `title sort applies sequence rules to original names`() {
        originalTitlesSorted("第十话", "第二话", "第一话") shouldBe listOf("第一话", "第二话", "第十话")
    }

    @Test
    fun `title sort recognizes front middle back and upper middle lower`() {
        displayedTitlesSorted(
            "魔法少女的报恩 后篇 [汉化]",
            "魔法少女的报恩 前篇 [汉化]",
            "魔法少女的报恩 中篇 [汉化]",
        ) shouldBe listOf(
            "魔法少女的报恩 前篇 [汉化]",
            "魔法少女的报恩 中篇 [汉化]",
            "魔法少女的报恩 后篇 [汉化]",
        )
        displayedTitlesSorted("星穹铁道妈妈 下", "星穹铁道妈妈 上", "星穹铁道妈妈 中") shouldBe
            listOf("星穹铁道妈妈 上", "星穹铁道妈妈 中", "星穹铁道妈妈 下")
        displayedTitlesSorted("故事 下篇", "故事 中篇", "故事 上篇") shouldBe
            listOf("故事 上篇", "故事 中篇", "故事 下篇")
    }

    @Test
    fun `title sort recognizes strict roman numeral suffixes`() {
        displayedTitlesSorted(
            "秘密花园Plus III (花骑士)",
            "秘密花园VIII (花骑士)",
            "秘密花园III (花骑士)",
            "秘密花园12 (花骑士)",
            "秘密花园XII (花骑士) [禁漫汉化组]",
            "秘密花园XI (花骑士)",
            "秘密花园IX (花骑士)",
            "秘密花园V (花骑士)",
            "秘密花园IV (花骑士)",
            "秘密花园Plus (花骑士)",
            "秘密花园Plus II (花骑士)",
            "秘密花园VII (花骑士)",
            "秘密花园II (花骑士)",
            "秘密花园X (花骑士)",
            "秘密花园VI (花骑士)",
            "秘密花园 (花骑士)",
        ) shouldBe listOf(
            "秘密花园 (花骑士)",
            "秘密花园II (花骑士)",
            "秘密花园III (花骑士)",
            "秘密花园IV (花骑士)",
            "秘密花园V (花骑士)",
            "秘密花园VI (花骑士)",
            "秘密花园VII (花骑士)",
            "秘密花园VIII (花骑士)",
            "秘密花园IX (花骑士)",
            "秘密花园X (花骑士)",
            "秘密花园XI (花骑士)",
            "秘密花园XII (花骑士) [禁漫汉化组]",
            "秘密花园12 (花骑士)",
            "秘密花园Plus (花骑士)",
            "秘密花园Plus II (花骑士)",
            "秘密花园Plus III (花骑士)",
        )
    }

    @Test
    fun `title sort keeps unnumbered base before numbered sequels with metadata`() {
        displayedTitlesSorted(
            "魅魔系大姐姐想炫耀3 [汉化组三] [数字版]",
            "魅魔系大姐姐想炫耀 [联合汉化组]",
            "魅魔系大姐姐想炫耀2 [汉化组二] [数字版]",
        ) shouldBe listOf(
            "魅魔系大姐姐想炫耀 [联合汉化组]",
            "魅魔系大姐姐想炫耀2 [汉化组二] [数字版]",
            "魅魔系大姐姐想炫耀3 [汉化组三] [数字版]",
        )
        displayedTitlesSorted(
            "Mukidashi Onaka 2 (Love Live! Sunshine!!) [Chinese] [绅士仓库汉化]",
            "Mukidashi Onaka 3 (Love Live! Sunshine!!) [Chinese] [绅士仓库汉化]",
            "Mukidashi Onaka (Love Live! Sunshine!!) [Chinese] [绅士仓库汉化]",
        ) shouldBe listOf(
            "Mukidashi Onaka (Love Live! Sunshine!!) [Chinese] [绅士仓库汉化]",
            "Mukidashi Onaka 2 (Love Live! Sunshine!!) [Chinese] [绅士仓库汉化]",
            "Mukidashi Onaka 3 (Love Live! Sunshine!!) [Chinese] [绅士仓库汉化]",
        )
        displayedTitlesSorted(
            "NUGIONAKA 2 玉体横陈2 [Chinese] [紫藤汉化组] [Decensored]",
            "NUGIONAKA [Loody个人去码] [神原祖母汉化组]",
        ) shouldBe listOf(
            "NUGIONAKA [Loody个人去码] [神原祖母汉化组]",
            "NUGIONAKA 2 玉体横陈2 [Chinese] [紫藤汉化组] [Decensored]",
        )
    }

    @Test
    fun `title sort does not treat english letters or metadata as sequence markers`() {
        ChapterTitleSortRules.sortKey("Honey x Honey") shouldBe "Honey x Honey"
        ChapterTitleSortRules.sortKey("Honey V Honey") shouldBe "Honey V Honey"
        ChapterTitleSortRules.sortKey("秘密花园MIX (花骑士)") shouldBe "秘密花园MIX (花骑士)"
        ChapterTitleSortRules.sortKey("邻家的女友 (COMIC X-EROS #32)") shouldBe
            "邻家的女友0 (COMIC X-EROS #32)"
        ChapterTitleSortRules.sortKey("故事前篇+中篇+后篇") shouldBe "故事前篇+中篇+后篇"
    }

    @Test
    fun `title sequence sort remains transitive with unrelated titles`() {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or
                Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY or
                Manga.CHAPTER_SORT_ASC,
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
    fun `title sort supports descending sequence order`() {
        displayedTitlesSorted(
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

    @Test
    fun `legacy translated sorting mode is treated as title sorting`() {
        val manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_TRANSLATED_LEGACY)

        manga.sorting shouldBe Manga.CHAPTER_SORTING_ALPHABET
    }

    private fun displayedTitlesSorted(vararg titles: String, descending: Boolean = false): List<String> {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or
                Manga.CHAPTER_DISPLAY_TRANSLATED_ONLY or
                if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
        return titles.mapIndexed { index, title -> chapter(index.toLong() + 1, "original-$index", title) }
            .sortedWith(getChapterSort(manga))
            .map { it.translatedNameOrNull.orEmpty() }
    }

    private fun originalTitlesSorted(vararg titles: String): List<String> {
        val manga = Manga.create().copy(
            chapterFlags = Manga.CHAPTER_SORTING_ALPHABET or Manga.CHAPTER_DISPLAY_NAME or Manga.CHAPTER_SORT_ASC,
        )
        return titles.mapIndexed { index, title -> chapter(index.toLong() + 1, title, "unused-$index") }
            .sortedWith(getChapterSort(manga))
            .map { it.name }
    }

    private fun chapter(id: Long, name: String, translatedName: String?): Chapter {
        return Chapter.create().copy(id = id, name = name, translatedName = translatedName)
    }
}
