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

    @Test
    fun `local library export groups mangas and keeps untranslated titles blank`() {
        val firstManga = Manga.create().copy(id = 7, title = "Author B", url = "Author B")
        val secondManga = Manga.create().copy(id = 8, title = "Author A", url = "Author A")
        val translated = chapter(10, "Author B/Story.cbz", "Story").copy(translatedName = "故事")
        val untranslated = chapter(11, "Author A/Other.cbz", "Other")

        val decoded = ChapterTitleTranslationCodec.decodeLocalLibrary(
            ChapterTitleTranslationCodec.encodeLocalLibrary(
                listOf(
                    firstManga to listOf(translated),
                    secondManga to listOf(untranslated),
                ),
            ),
        )

        decoded.formatVersion shouldBe 1
        decoded.mangas.map { it.mangaTitle } shouldBe listOf("Author A", "Author B")
        decoded.mangas.first().chapters.single().translatedTitle shouldBe ""
        decoded.mangas.last().chapters.single().translatedTitle shouldBe "故事"
    }

    @Test
    fun `local library import matches each manga before matching its chapters`() {
        val firstManga = Manga.create().copy(id = 7, title = "Author A", url = "Author A")
        val secondManga = Manga.create().copy(id = 8, title = "Author B", url = "Author B")
        val document = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Author A/Story.cbz",
                        translatedTitle = "故事",
                    ),
                ).copy(mangaId = 7, mangaTitle = "Author A", mangaUrl = "Author A"),
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 20,
                        originalTitle = "Other",
                        originalUrl = "Author B/Other.cbz",
                        translatedTitle = "其他",
                    ),
                ).copy(mangaId = 8, mangaTitle = "Author B", mangaUrl = "Author B"),
            ),
        )

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = document,
            currentMangas = listOf(
                firstManga to listOf(chapter(10, "Author A/Story.cbz", "Story")),
                secondManga to listOf(chapter(20, "Author B/Other.cbz", "Other")),
            ),
        )

        plan.updates.map { it.id to it.translatedName } shouldBe listOf(
            10L to "故事",
            20L to "其他",
        )
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `local library import ignores chapters from an unknown manga`() {
        val document = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Missing/Story.cbz",
                        translatedTitle = "故事",
                    ),
                ).copy(mangaId = 99, mangaTitle = "Missing", mangaUrl = "Missing"),
            ),
        )

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(document, emptyList())

        plan.updates shouldBe emptyList()
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `local library import follows a chapter moved to another manga`() {
        val oldDocument = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Author A/Story.cbz",
                        translatedTitle = "故事",
                    ),
                ).copy(mangaId = 7, mangaTitle = "Author A", mangaUrl = "Author A"),
            ),
        )
        val newManga = Manga.create().copy(id = 8, title = "Author B", url = "Author B")
        val movedChapter = chapter(10, "Author B/Story.cbz", "Story").copy(mangaId = 8)

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = oldDocument,
            currentMangas = listOf(newManga to listOf(movedChapter)),
        )

        plan.updates.single().id shouldBe 10L
        plan.updates.single().translatedName shouldBe "故事"
        plan.ignoredCount shouldBe 0
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
