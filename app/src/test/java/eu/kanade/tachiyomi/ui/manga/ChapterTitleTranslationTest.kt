package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterTitleTranslationTest {

    @Test
    fun `translation follows a moved chapter with the same name`() {
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
    fun `single manga import follows a moved chapter that has a unique name`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Short Story",
                originalUrl = "Old Author/Short Story.cbz",
                translatedTitle = "短篇故事",
            ),
        )
        val movedChapter = chapter(20, "New Author/Short Story.cbz", "Short Story")

        val plan = ChapterTitleTranslationCodec.planImport(document, listOf(movedChapter))

        plan.updates.single().id shouldBe 20L
        plan.updates.single().translatedName shouldBe "短篇故事"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `single manga import uses database id only after portable matches fail`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Repeated Name",
                originalUrl = "Old Author/Repeated Name.cbz",
                translatedTitle = "正确译名",
            ),
        ).copy(exportInstanceId = "device-a")
        val moved = chapter(10, "New Author/Repeated Name.cbz", "Repeated Name")
        val sameName = chapter(20, "New Author/Repeated Name (2).cbz", "Repeated Name")

        val plan = ChapterTitleTranslationCodec.planImport(
            document = document,
            currentChapters = listOf(moved, sameName),
            currentInstanceId = "device-a",
        )

        plan.updates.single().id shouldBe 10L
        plan.updates.single().translatedName shouldBe "正确译名"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `single manga unique name wins over a colliding database id`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Story",
                originalUrl = "Old Author/Story.cbz",
                translatedTitle = "故事",
            ),
        ).copy(exportInstanceId = "device-a")
        val idCollision = chapter(10, "Other Author/Different.cbz", "Different")
        val correctByName = chapter(20, "New Author/Story.cbz", "Story")

        val plan = ChapterTitleTranslationCodec.planImport(
            document = document,
            currentChapters = listOf(idCollision, correctByName),
            currentInstanceId = "device-a",
        )

        plan.updates.single().id shouldBe 20L
        plan.updates.single().translatedName shouldBe "故事"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `ambiguous title alone never assigns a translation to a new chapter`() {
        val document = document(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Repeated Name",
                originalUrl = "Author A/Repeated Name.cbz",
                translatedTitle = "旧篇目",
            ),
        )
        // Two chapters share that name, so there is no unambiguous target.
        val first = chapter(10, "Author B/Repeated Name.cbz", "Repeated Name")
        val second = chapter(21, "Author B/Repeated Name (2).cbz", "Repeated Name")

        val plan = ChapterTitleTranslationCodec.planImport(document, listOf(first, second))

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
    fun `export instance id round trips through json and csv`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val chapter = chapter(10, "Author/Story.cbz", "Story")

        ChapterTitleTranslationFormat.entries.forEach { format ->
            val decoded = ChapterTitleTranslationCodec.decode(
                ChapterTitleTranslationCodec.encode(
                    manga = manga,
                    chapters = listOf(chapter),
                    format = format,
                    exportInstanceId = "device-a",
                ),
            )

            decoded.exportInstanceId shouldBe "device-a"
        }
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
    fun `csv import keeps rows whose stable key is missing or malformed`() {
        // Hand editing a spreadsheet can easily break this column. It is no longer used to match a
        // chapter, so it must not discard an otherwise usable row.
        val csv = buildString {
            append('\uFEFF')
            appendLine("stable_key,漫画原名,漫画路径,篇目原名,篇目路径,中文名")
            appendLine(",Author A,Author A,Story,Author A/Story.cbz,故事 A")
            appendLine("not-a-number,Author B,Author B,Story,Author B/Story.cbz,故事 B")
        }

        val decoded = ChapterTitleTranslationCodec.decodeLocalLibrary(csv)

        decoded.mangas.map { it.mangaTitle } shouldBe listOf("Author A", "Author B")
        decoded.mangas.map { it.chapters.single().translatedTitle } shouldBe listOf("故事 A", "故事 B")
    }

    @Test
    fun `csv export uses spreadsheet columns and preserves quoted values`() {
        val manga = Manga.create().copy(id = 7, title = "Author, Circle", url = "Author, Circle")
        val chapter = chapter(10, "Author, Circle/Story \"A\".cbz", "Story \"A\"")
            .copy(translatedName = "故事, A")

        val csv = ChapterTitleTranslationCodec.encode(
            manga = manga,
            chapters = listOf(chapter),
            format = ChapterTitleTranslationFormat.CSV,
        )
        val decoded = ChapterTitleTranslationCodec.decode(csv)

        csv.startsWith("\uFEFFstable_key,漫画原名,漫画路径,篇目原名,篇目路径,中文名") shouldBe true
        decoded.mangaTitle shouldBe "Author, Circle"
        decoded.chapters.single() shouldBe ChapterTitleTranslationEntry(
            chapterId = 10,
            originalTitle = "Story \"A\"",
            originalUrl = "Author, Circle/Story \"A\".cbz",
            translatedTitle = "故事, A",
        )
    }

    @Test
    fun `csv import accepts english headers and excel line breaks`() {
        val csv = """
            stable_key,manga_title,manga_path,original_title,chapter_path,translated_title
            10,Author,Author,"Story
            Part 2",Author/Story.cbz,故事
        """.trimIndent()

        val document = ChapterTitleTranslationCodec.decode(csv)

        document.chapters.single().originalTitle shouldBe "Story\nPart 2"
        document.chapters.single().translatedTitle shouldBe "故事"
    }

    @Test
    fun `full library csv round trip keeps separate manga groups`() {
        val firstManga = Manga.create().copy(id = 7, title = "Author A", url = "Author A")
        val secondManga = Manga.create().copy(id = 8, title = "Author B", url = "Author B")

        val csv = ChapterTitleTranslationCodec.encodeLocalLibrary(
            mangas = listOf(
                firstManga to listOf(chapter(10, "Author A/Story.cbz", "Story")),
                secondManga to listOf(chapter(20, "Author B/Other.cbz", "Other")),
            ),
            format = ChapterTitleTranslationFormat.CSV,
        )
        val decoded = ChapterTitleTranslationCodec.decodeLocalLibrary(csv)

        decoded.mangas.map { it.mangaTitle } shouldBe listOf("Author A", "Author B")
        decoded.mangas.map { it.chapters.single().chapterId } shouldBe listOf(10L, 20L)
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

    @Test
    fun `database id is never used to import a translation across different instances`() {
        val exported = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Author A/Story.cbz",
                        translatedTitle = "旧译名",
                    ),
                ).copy(
                    exportInstanceId = "device-a",
                    mangaId = 7,
                    mangaTitle = "Author A",
                    mangaUrl = "Author A",
                ),
            ),
        )
        // Same database id and manga id, different name and path. Ids are device local, so they
        // must not be enough to place a translation.
        val unrelatedManga = Manga.create().copy(id = 7, title = "Author A", url = "Other Author")
        val unrelatedChapter = chapter(10, "Other Author/Different.cbz", "Different").copy(mangaId = 7)

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = exported,
            currentMangas = listOf(unrelatedManga to listOf(unrelatedChapter)),
            currentInstanceId = "device-b",
        )

        plan.updates shouldBe emptyList()
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `cross device import matches a reorganized chapter by its unique name`() {
        // Exported on another device before the library was reorganized: the manga folder was
        // renamed and the chapter moved, so neither manga nor chapter path matches anymore.
        val exported = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Old Author/Story.cbz",
                        translatedTitle = "故事",
                    ),
                ).copy(mangaId = 99, mangaTitle = "Old Author", mangaUrl = "Old Author"),
            ),
        )
        val reorganizedManga = Manga.create().copy(id = 7, title = "New Author", url = "New Author")
        val movedChapter = chapter(10, "New Author/Story.cbz", "Story").copy(mangaId = 7)

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = exported,
            currentMangas = listOf(reorganizedManga to listOf(movedChapter)),
        )

        plan.updates.single().id shouldBe 10L
        plan.updates.single().translatedName shouldBe "故事"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `cross device import skips an ambiguous name instead of guessing`() {
        // Exported on another device from a manga that does not exist here.
        val exported = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Missing Author/Story.cbz",
                        translatedTitle = "别的作品的译名",
                    ),
                ).copy(
                    exportInstanceId = "device-a",
                    mangaId = 99,
                    mangaTitle = "Missing Author",
                    mangaUrl = "Missing Author",
                ),
            ),
        )
        // Two unrelated chapters share that name, so there is no unambiguous target.
        val firstManga = Manga.create().copy(id = 7, title = "Author A", url = "Author A")
        val secondManga = Manga.create().copy(id = 8, title = "Author B", url = "Author B")

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = exported,
            currentMangas = listOf(
                firstManga to listOf(chapter(10, "Author A/Story.cbz", "Story").copy(mangaId = 7)),
                secondManga to listOf(chapter(11, "Author B/Story.cbz", "Story").copy(mangaId = 8)),
            ),
            currentInstanceId = "device-b",
        )

        plan.updates shouldBe emptyList()
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `library import uses database id as a final fallback for a moved duplicate name`() {
        val exported = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Repeated Name",
                        originalUrl = "Old Author/Repeated Name.cbz",
                        translatedTitle = "正确译名",
                    ),
                ).copy(
                    exportInstanceId = "device-a",
                    mangaId = 7,
                    mangaTitle = "Old Author",
                    mangaUrl = "Old Author",
                ),
            ),
        )
        val newManga = Manga.create().copy(id = 8, title = "New Author", url = "New Author")
        val moved = chapter(10, "New Author/Repeated Name.cbz", "Repeated Name").copy(mangaId = 8)
        val sameName = chapter(20, "New Author/Repeated Name (2).cbz", "Repeated Name").copy(mangaId = 8)

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = exported,
            currentMangas = listOf(newManga to listOf(moved, sameName)),
            currentInstanceId = "device-a",
        )

        plan.updates.single().id shouldBe 10L
        plan.updates.single().translatedName shouldBe "正确译名"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `library unique name wins over a colliding database id`() {
        val exported = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "Story",
                        originalUrl = "Old Author/Story.cbz",
                        translatedTitle = "故事",
                    ),
                ).copy(
                    exportInstanceId = "device-a",
                    mangaId = 99,
                    mangaTitle = "Old Author",
                    mangaUrl = "Old Author",
                ),
            ),
        )
        val unrelatedManga = Manga.create().copy(id = 7, title = "Other Author", url = "Other Author")
        val correctManga = Manga.create().copy(id = 8, title = "New Author", url = "New Author")
        val idCollision = chapter(10, "Other Author/Different.cbz", "Different").copy(mangaId = 7)
        val correctByName = chapter(20, "New Author/Story.cbz", "Story").copy(mangaId = 8)

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = exported,
            currentMangas = listOf(
                unrelatedManga to listOf(idCollision),
                correctManga to listOf(correctByName),
            ),
            currentInstanceId = "device-a",
        )

        plan.updates.single().id shouldBe 20L
        plan.updates.single().translatedName shouldBe "故事"
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `json untranslated checklist includes translated siblings as naming context`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val translated = chapter(10, "Author/Story.cbz", "Story").copy(translatedName = "故事")
        val untranslated = chapter(11, "Author/Other.cbz", "Other")

        val decoded = ChapterTitleTranslationCodec.decode(
            ChapterTitleTranslationCodec.encode(
                manga = manga,
                chapters = listOf(translated, untranslated),
                onlyUntranslated = true,
            ),
        )

        decoded.chapters shouldBe listOf(
            ChapterTitleTranslationEntry(
                chapterId = 10,
                originalTitle = "Story",
                originalUrl = "Author/Story.cbz",
                translatedTitle = "故事",
                referenceOnly = true,
            ),
            ChapterTitleTranslationEntry(
                chapterId = 11,
                originalTitle = "Other",
                originalUrl = "Author/Other.cbz",
                translatedTitle = "",
            ),
        )
    }

    @Test
    fun `csv untranslated checklist includes translated siblings and blank chinese cells`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val translated = chapter(10, "Author/Story.cbz", "Story").copy(translatedName = "故事")
        val untranslated = chapter(11, "Author/Other.cbz", "Other")

        val csv = ChapterTitleTranslationCodec.encode(
            manga = manga,
            chapters = listOf(translated, untranslated),
            format = ChapterTitleTranslationFormat.CSV,
            onlyUntranslated = true,
        )
        val decoded = ChapterTitleTranslationCodec.decode(csv)

        csv.lineSequence().count() shouldBe 3
        decoded.chapters.map { it.chapterId to it.translatedTitle } shouldBe listOf(
            10L to "故事",
            11L to "",
        )
        decoded.chapters.map { it.referenceOnly } shouldBe listOf(true, false)
    }

    @Test
    fun `local library export with only untranslated drops fully translated mangas`() {
        val translatedManga = Manga.create().copy(id = 7, title = "Author A", url = "Author A")
        val mixedManga = Manga.create().copy(id = 8, title = "Author B", url = "Author B")
        val translated = chapter(10, "Author A/Story.cbz", "Story").copy(translatedName = "故事")
        val untranslated = chapter(11, "Author B/Other.cbz", "Other")

        val decoded = ChapterTitleTranslationCodec.decodeLocalLibrary(
            ChapterTitleTranslationCodec.encodeLocalLibrary(
                mangas = listOf(
                    translatedManga to listOf(translated),
                    mixedManga to listOf(untranslated),
                ),
                onlyUntranslated = true,
            ),
        )

        decoded.mangas.map { it.mangaTitle } shouldBe listOf("Author B")
        decoded.mangas.single().chapters.single().chapterId shouldBe 11L
    }

    @Test
    fun `local library untranslated checklist includes all chapters from a matching manga`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val translated = chapter(10, "Author/Story.cbz", "Story").copy(translatedName = "故事")
        val untranslated = chapter(11, "Author/Other.cbz", "Other")

        val decoded = ChapterTitleTranslationCodec.decodeLocalLibrary(
            ChapterTitleTranslationCodec.encodeLocalLibrary(
                mangas = listOf(manga to listOf(translated, untranslated)),
                onlyUntranslated = true,
            ),
        )

        decoded.mangas.single().chapters.map { it.chapterId to it.translatedTitle } shouldBe listOf(
            10L to "故事",
            11L to "",
        )
        decoded.mangas.single().chapters.map { it.referenceOnly } shouldBe listOf(true, false)
    }

    @Test
    fun `moved untranslated chapter imports by its name while old context is ignored`() {
        val oldManga = Manga.create().copy(id = 7, title = "Author A", url = "Author A")
        val context = chapter(10, "Author A/Context.cbz", "Context").copy(translatedName = "旧参考译名")
        val untranslated = chapter(11, "Author A/Story.cbz", "Story")
        val exported = ChapterTitleTranslationCodec.decodeLocalLibrary(
            ChapterTitleTranslationCodec.encodeLocalLibrary(
                mangas = listOf(oldManga to listOf(context, untranslated)),
                onlyUntranslated = true,
            ),
        )
        val filled = exported.copy(
            mangas = exported.mangas.map { manga ->
                manga.copy(
                    chapters = manga.chapters.map { entry ->
                        if (entry.chapterId == 11L) entry.copy(translatedTitle = "新位置译名") else entry
                    },
                )
            },
        )

        val newManga = Manga.create().copy(id = 8, title = "Author B", url = "Author B")
        val moved = chapter(11, "Author B/Story.cbz", "Story").copy(mangaId = 8)
        val changedContext = context.copy(translatedName = "软件内后来修改的译名")
        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = filled,
            currentMangas = listOf(
                oldManga to listOf(changedContext),
                newManga to listOf(moved),
            ),
        )

        plan.updates.map { it.id to it.translatedName } shouldBe listOf(11L to "新位置译名")
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `fully translated single manga produces an empty untranslated checklist`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val translated = chapter(10, "Author/Story.cbz", "Story").copy(translatedName = "故事")

        val decoded = ChapterTitleTranslationCodec.decode(
            ChapterTitleTranslationCodec.encode(
                manga = manga,
                chapters = listOf(translated),
                onlyUntranslated = true,
            ),
        )

        decoded.chapters shouldBe emptyList()
    }

    @Test
    fun `whitespace only translation is untranslated on export and skipped on import`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val blank = chapter(10, "Author/Story.cbz", "Story").copy(translatedName = "  ")

        val decoded = ChapterTitleTranslationCodec.decode(
            ChapterTitleTranslationCodec.encode(
                manga = manga,
                chapters = listOf(blank),
                onlyUntranslated = true,
            ),
        )
        decoded.chapters.single().chapterId shouldBe 10L

        val plan = ChapterTitleTranslationCodec.planImport(decoded, listOf(blank))
        plan.updates shouldBe emptyList()
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `import skips blank rows so a partially filled checklist only writes filled rows`() {
        val manga = Manga.create().copy(id = 7, title = "Author", url = "Author")
        val first = chapter(10, "Author/A.cbz", "A")
        val second = chapter(11, "Author/B.cbz", "B")
        val document = LocalLibraryChapterTitleTranslationDocument(
            mangas = listOf(
                document(
                    ChapterTitleTranslationEntry(
                        chapterId = 10,
                        originalTitle = "A",
                        originalUrl = "Author/A.cbz",
                        translatedTitle = "",
                    ),
                    ChapterTitleTranslationEntry(
                        chapterId = 11,
                        originalTitle = "B",
                        originalUrl = "Author/B.cbz",
                        translatedTitle = "B 名",
                    ),
                ).copy(mangaId = 7, mangaTitle = "Author", mangaUrl = "Author"),
            ),
        )

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = document,
            currentMangas = listOf(manga to listOf(first, second)),
        )

        plan.updates.single().id shouldBe 11L
        plan.updates.single().translatedName shouldBe "B 名"
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `single manga json checklist can be partially filled without clearing existing titles`() {
        val first = chapter(10, "Author/A.cbz", "A").copy(translatedName = "已有译名")
        val second = chapter(11, "Author/B.cbz", "B")
        val json = ChapterTitleTranslationCodec.encode(
            manga = Manga.create().copy(id = 7, title = "Author", url = "Author"),
            chapters = listOf(first, second),
        ).replace(
            "\"translatedTitle\": \"已有译名\"",
            "\"translatedTitle\": \"   \"",
        ).replace(
            "\"translatedTitle\": \"\"",
            "\"translatedTitle\": \"新译名\"",
        )

        val plan = ChapterTitleTranslationCodec.planImport(
            document = ChapterTitleTranslationCodec.decode(json),
            currentChapters = listOf(first, second),
        )

        plan.updates.map { it.id to it.translatedName } shouldBe listOf(11L to "新译名")
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `single manga csv checklist can be partially filled without clearing existing titles`() {
        val first = chapter(10, "Author/A.cbz", "A").copy(translatedName = "已有译名")
        val second = chapter(11, "Author/B.cbz", "B")
        val csv = """
            stable_key,漫画原名,漫画路径,篇目原名,篇目路径,中文名
            10,Author,Author,A,Author/A.cbz,
            11,Author,Author,B,Author/B.cbz,新译名
        """.trimIndent()

        val plan = ChapterTitleTranslationCodec.planImport(
            document = ChapterTitleTranslationCodec.decode(csv),
            currentChapters = listOf(first, second),
        )

        plan.updates.map { it.id to it.translatedName } shouldBe listOf(11L to "新译名")
        plan.ignoredCount shouldBe 1
    }

    @Test
    fun `single manga header only checklist import is a no-op`() {
        val csv = "stable_key,漫画原名,漫画路径,篇目原名,篇目路径,中文名"

        val plan = ChapterTitleTranslationCodec.planImport(
            document = ChapterTitleTranslationCodec.decode(csv),
            currentChapters = listOf(chapter(10, "Author/A.cbz", "A")),
        )

        plan.updates shouldBe emptyList()
        plan.ignoredCount shouldBe 0
    }

    @Test
    fun `header only checklist import is a no-op instead of an error`() {
        val csv = "stable_key,漫画原名,漫画路径,篇目原名,篇目路径,中文名"

        val plan = ChapterTitleTranslationCodec.planLocalLibraryImport(
            document = ChapterTitleTranslationCodec.decodeLocalLibrary(csv),
            currentMangas = listOf(
                Manga.create().copy(id = 7, title = "Author", url = "Author") to
                    listOf(chapter(10, "Author/A.cbz", "A")),
            ),
        )

        plan.updates shouldBe emptyList()
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
