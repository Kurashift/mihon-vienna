package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterTitleTranslationTest {

    @Test
    fun `translation follows a moved chapter with the same database id`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Short Story",
                originalUrl = "Author A/Short Story.cbz",
                translatedTitle = "短篇故事",
            ),
        )
        val current = chapter(10, "Author B/Short Story.cbz", "Short Story")

        val plan = ChapterTitleTranslationCodec.planImport(document, listOf(current))

        plan.updates.single().id shouldBe 10L
        plan.updates.single().translatedName shouldBe "短篇故事"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `exact url can recover a translation when database ids changed`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Short Story",
                originalUrl = "Author A/Short Story.cbz",
                translatedTitle = "短篇故事",
            ),
        )
        val current = chapter(99, "Author A/Short Story.cbz", "Short Story")

        ChapterTitleTranslationCodec.planImport(document, listOf(current)).updates.single().id shouldBe 99L
    }

    @Test
    fun `same title alone never assigns a translation to a new chapter`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Repeated Name",
                originalUrl = "Author A/Repeated Name.cbz",
                translatedTitle = "旧篇目",
            ),
        )
        val newChapter = chapter(20, "Author B/Repeated Name.cbz", "Repeated Name")

        val plan = ChapterTitleTranslationCodec.planImport(document, listOf(newChapter))

        plan.updates shouldBe emptyList()
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `export keeps original identity fields and existing translation`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val chapter = chapter(10, "Author/Story.cbz", "Story").copy(translatedName = "故事")

        val decoded = ChapterTitleTranslationCodec.decode(
            ChapterTitleTranslationCodec.encode(manga, listOf(chapter)),
        )

        decoded.mangaId shouldBe 7L
        decoded.chapters.single() shouldBe ChapterTitleTranslationEntry(
            chapterId = 10,
            originalTitle = "Story",
            originalUrl = "Author/Story.cbz",
            translatedTitle = "故事",
        )
    }

    private fun document(vararg entries: ChapterTitleTranslationEntry) = ChapterTitleTranslationDocument(
        mangaId = 1,
        mangaTitle = "Author",
        mangaUrl = "Author",
        chapters = entries.toList(),
    )

    private fun chapter(id: Long, url: String, name: String): Chapter {
        return Chapter.create().copy(id = id, mangaId = 1, url = url, name = name)
    }
}
