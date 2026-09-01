package eu.kanade.tachiyomi.ui.manga

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga

@Serializable
internal data class ChapterTitleTranslationDocument(
    val formatVersion: Int = 1,
    val exportInstanceId: String? = null,
    val mangaId: Long,
    val mangaTitle: String,
    val mangaUrl: String,
    val chapters: List<ChapterTitleTranslationEntry>,
)

@Serializable
internal data class LocalLibraryChapterTitleTranslationDocument(
    val formatVersion: Int = 1,
    val mangas: List<ChapterTitleTranslationDocument>,
)

@Serializable
internal data class ChapterTitleTranslationEntry(
    val chapterId: Long,
    val originalTitle: String,
    val originalUrl: String,
    val translatedTitle: String = "",
    val referenceOnly: Boolean = false,
)

internal data class ChapterTitleImportPlan(
    val updates: List<ChapterUpdate>,
    val ignoredCount: Int,
)

internal data class LocalLibraryChapterTitleImportPlan(
    val updates: List<ChapterUpdate>,
    val ignoredCount: Int,
)

/**
 * Shared predicate between export filtering and import planning: a translation counts as
 * missing when it is null, empty, or whitespace only. Export keeps exactly the rows import
 * would skip, so a blank-name checklist round-trips without surprises.
 */
internal fun Chapter.isUntranslated(): Boolean = translatedNameOrNull.isNullOrBlank()

enum class ChapterTitleTranslationFormat(
    val mimeType: String,
    val fileExtension: String,
) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
}

internal object ChapterTitleTranslationCodec {
    private const val CSV_BOM = '\uFEFF'
    private val csvHeader = listOf(
        // Historical name. This column holds the local database chapter id, which is only stable
        // on the device that exported it. Portable path/name matches take priority; this value is
        // retained as a guarded same-device fallback and for compatibility with older exports.
        "stable_key",
        "漫画原名",
        "漫画路径",
        "篇目原名",
        "篇目路径",
        "中文名",
        "中文排序名",
        "备注",
        "人工锁定",
        "仅供参考",
        "来源实例",
    )
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(
        manga: Manga,
        chapters: List<Chapter>,
        format: ChapterTitleTranslationFormat = ChapterTitleTranslationFormat.JSON,
        onlyUntranslated: Boolean = false,
        exportInstanceId: String? = null,
    ): String {
        val document = toDocument(manga, chapters, onlyUntranslated, exportInstanceId)
        return when (format) {
            ChapterTitleTranslationFormat.JSON -> json.encodeToString(document)
            ChapterTitleTranslationFormat.CSV -> encodeCsv(listOf(document))
        }
    }

    fun encodeLocalLibrary(
        mangas: List<Pair<Manga, List<Chapter>>>,
        format: ChapterTitleTranslationFormat = ChapterTitleTranslationFormat.JSON,
        onlyUntranslated: Boolean = false,
        exportInstanceId: String? = null,
    ): String {
        val document = LocalLibraryChapterTitleTranslationDocument(
            mangas = mangas
                .sortedBy { (manga, _) -> manga.title.lowercase() }
                .map { (manga, chapters) ->
                    toDocument(manga, chapters, onlyUntranslated, exportInstanceId)
                }
                // Fully translated manga are omitted from the untranslated checklist.
                .filter { it.chapters.isNotEmpty() },
        )
        return when (format) {
            ChapterTitleTranslationFormat.JSON -> json.encodeToString(document)
            ChapterTitleTranslationFormat.CSV -> encodeCsv(document.mangas)
        }
    }

    fun decode(value: String): ChapterTitleTranslationDocument {
        return if (value.isCsv()) {
            val mangas = decodeCsv(value).mangas
            require(mangas.size <= 1) { "Chapter title translation table contains multiple manga" }
            mangas.singleOrNull() ?: ChapterTitleTranslationDocument(
                mangaId = 0,
                mangaTitle = "",
                mangaUrl = "",
                chapters = emptyList(),
            )
        } else {
            json.decodeFromString(value)
        }
    }

    fun decodeLocalLibrary(value: String): LocalLibraryChapterTitleTranslationDocument {
        return if (value.isCsv()) {
            decodeCsv(value)
        } else {
            json.decodeFromString(value)
        }
    }

    fun planLocalLibraryImport(
        document: LocalLibraryChapterTitleTranslationDocument,
        currentMangas: List<Pair<Manga, List<Chapter>>>,
        currentInstanceId: String? = null,
    ): LocalLibraryChapterTitleImportPlan {
        require(document.formatVersion == 1) { "Unsupported local library title translation format" }

        val byId = currentMangas.associateBy { (manga, _) -> manga.id }
        val byUrl = currentMangas.groupBy { (manga, _) -> manga.url }
        val allChapters = currentMangas.flatMap { (_, chapters) -> chapters }
        val allChaptersById = allChapters.associateBy(Chapter::id)
        val allChaptersByUrl = allChapters.groupBy(Chapter::url)
        val allChaptersByName = allChapters.groupBy(Chapter::name)
        val claimedChapterIds = mutableSetOf<Long>()
        var ignoredCount = 0

        val updates = document.mangas.flatMap { mangaDocument ->
            val canUseDatabaseIds = mangaDocument.exportInstanceId != null &&
                mangaDocument.exportInstanceId == currentInstanceId
            val idMatch = byId[mangaDocument.mangaId]
                ?.takeIf { (manga, _) -> manga.url == mangaDocument.mangaUrl }
            val urlMatch = byUrl[mangaDocument.mangaUrl]?.singleOrNull()
            val current = idMatch ?: urlMatch
            val currentChaptersById = current?.second.orEmpty().associateBy(Chapter::id)
            val currentChaptersByUrl = current?.second.orEmpty().groupBy(Chapter::url)
            val currentChaptersByName = current?.second.orEmpty().groupBy(Chapter::name)

            mangaDocument.chapters.mapNotNull { entry ->
                if (entry.referenceOnly) return@mapNotNull null

                val translatedTitle = entry.translatedTitle.trim()
                if (translatedTitle.isEmpty()) {
                    ignoredCount++
                    return@mapNotNull null
                }

                val localUrlMatch = currentChaptersByUrl[entry.originalUrl]?.singleOrNull()
                val localNameMatch = currentChaptersByName[entry.originalTitle]?.singleOrNull()
                val globalUrlMatch = allChaptersByUrl[entry.originalUrl]?.singleOrNull()
                val globalNameMatch = allChaptersByName[entry.originalTitle]?.singleOrNull()
                // Ids are device-local, so use them only as a final same-device fallback after
                // portable path/name matches fail, and still require the exported identity to fit.
                val localIdMatch = if (canUseDatabaseIds) {
                    currentChaptersById[entry.chapterId]?.takeIf { it.matchesExportedIdentity(entry) }
                } else {
                    null
                }
                val movedIdMatch = if (canUseDatabaseIds) {
                    allChaptersById[entry.chapterId]?.takeIf { it.matchesExportedIdentity(entry) }
                } else {
                    null
                }
                val chapter = localUrlMatch ?: localNameMatch ?: globalUrlMatch ?: globalNameMatch ?: localIdMatch
                    ?: movedIdMatch

                if (chapter == null || !claimedChapterIds.add(chapter.id)) {
                    ignoredCount++
                    null
                } else {
                    ChapterUpdate(id = chapter.id, translatedName = translatedTitle)
                }
            }
        }

        return LocalLibraryChapterTitleImportPlan(updates, ignoredCount)
    }

    fun planImport(
        document: ChapterTitleTranslationDocument,
        currentChapters: List<Chapter>,
        currentInstanceId: String? = null,
    ): ChapterTitleImportPlan {
        require(document.formatVersion == 1) { "Unsupported chapter title translation format" }

        val byId = currentChapters.associateBy { it.id }
        val byUrl = currentChapters.groupBy { it.url }
        val byName = currentChapters.groupBy { it.name }
        val claimedIds = mutableSetOf<Long>()
        var ignoredCount = 0

        val updates = document.chapters.mapNotNull { entry ->
            if (entry.referenceOnly) return@mapNotNull null

            val translatedTitle = entry.translatedTitle.trim()
            if (translatedTitle.isEmpty()) {
                ignoredCount++
                return@mapNotNull null
            }

            val urlMatch = byUrl[entry.originalUrl]?.singleOrNull()
            val nameMatch = byName[entry.originalTitle]?.singleOrNull()
            val idMatch = if (
                document.exportInstanceId != null &&
                document.exportInstanceId == currentInstanceId
            ) {
                byId[entry.chapterId]?.takeIf { it.matchesExportedIdentity(entry) }
            } else {
                null
            }
            val chapter = urlMatch ?: nameMatch ?: idMatch

            if (chapter == null || !claimedIds.add(chapter.id)) {
                ignoredCount++
                null
            } else {
                ChapterUpdate(id = chapter.id, translatedName = translatedTitle)
            }
        }

        return ChapterTitleImportPlan(updates, ignoredCount)
    }

    private fun toDocument(
        manga: Manga,
        chapters: List<Chapter>,
        onlyUntranslated: Boolean,
        exportInstanceId: String?,
    ): ChapterTitleTranslationDocument {
        val exportedChapters = if (onlyUntranslated && chapters.none { it.isUntranslated() }) {
            emptyList()
        } else {
            chapters
        }
        return ChapterTitleTranslationDocument(
            exportInstanceId = exportInstanceId,
            mangaId = manga.id,
            mangaTitle = manga.title,
            mangaUrl = manga.url,
            chapters = exportedChapters.map { chapter ->
                ChapterTitleTranslationEntry(
                    chapterId = chapter.id,
                    originalTitle = chapter.name,
                    originalUrl = chapter.url,
                    translatedTitle = chapter.translatedNameOrNull.orEmpty(),
                    referenceOnly = onlyUntranslated && !chapter.isUntranslated(),
                )
            },
        )
    }

    private fun Chapter.matchesExportedIdentity(entry: ChapterTitleTranslationEntry): Boolean {
        if (url == entry.originalUrl) return true
        return name == entry.originalTitle &&
            url.substringAfterLast('/') == entry.originalUrl.substringAfterLast('/')
    }

    private fun encodeCsv(documents: List<ChapterTitleTranslationDocument>): String {
        val rows = buildList {
            add(csvHeader)
            documents.forEach { manga ->
                manga.chapters.forEach { chapter ->
                    add(
                        listOf(
                            chapter.chapterId.toString(),
                            manga.mangaTitle,
                            manga.mangaUrl,
                            chapter.originalTitle,
                            chapter.originalUrl,
                            chapter.translatedTitle,
                            "",
                            "",
                            "",
                            chapter.referenceOnly.toString(),
                            manga.exportInstanceId.orEmpty(),
                        ),
                    )
                }
            }
        }
        return buildString {
            append(CSV_BOM)
            rows.joinTo(this, separator = "\r\n") { row ->
                row.joinToString(",", transform = ::escapeCsvCell)
            }
        }
    }

    private fun decodeCsv(value: String): LocalLibraryChapterTitleTranslationDocument {
        val rows = parseCsv(value)
        require(rows.isNotEmpty()) { "Chapter title translation table is empty" }

        val headers = rows.first().map(::normalizeCsvHeader)
        val formatVersionIndex = headers.findColumn("formatversion", "版本")
        val mangaIdIndex = headers.findColumn("mangaid", "漫画id")
        val mangaTitleIndex = headers.requireColumn("mangatitle", "漫画原名", "漫画名")
        val mangaPathIndex = headers.requireColumn("mangapath", "mangaurl", "漫画路径")
        val chapterIdIndex = headers.requireColumn("stablekey", "chapterid", "篇目id", "章节id")
        val originalTitleIndex = headers.requireColumn(
            "originaltitle",
            "chaptertitle",
            "篇目原名",
            "章节原名",
            "原名",
        )
        val chapterPathIndex = headers.requireColumn(
            "chapterpath",
            "originalurl",
            "chapterurl",
            "篇目路径",
            "章节路径",
        )
        val translatedTitleIndex = headers.requireColumn("translatedtitle", "中文名", "译名")
        val referenceOnlyIndex = headers.findColumn("referenceonly", "仅供参考")
        val exportInstanceIdIndex = headers.findColumn("exportinstanceid", "exportinstance", "来源实例")

        data class MangaKey(val id: Long, val title: String, val path: String, val exportInstanceId: String?)

        val chaptersByManga = linkedMapOf<MangaKey, MutableList<ChapterTitleTranslationEntry>>()
        rows.drop(1).forEachIndexed { index, row ->
            if (row.all(String::isBlank)) return@forEachIndexed

            val rowNumber = index + 2
            val formatVersion = formatVersionIndex
                ?.let { row.cell(it).trim().ifEmpty { "1" }.toIntOrNull() }
                ?: 1
            require(formatVersion == 1) { "Unsupported table format on row $rowNumber" }

            val mangaTitle = row.cell(mangaTitleIndex)
            val mangaPath = row.cell(mangaPathIndex)
            val mangaId = mangaIdIndex?.let { row.cell(it).trim().toLongOrNull() } ?: 0L
            val exportInstanceId = exportInstanceIdIndex
                ?.let { row.cell(it).trim().ifEmpty { null } }
            // The id is only meaningful on the exporting device and is merely a final fallback,
            // so a missing or malformed value must not discard an otherwise usable row.
            val chapterId = row.cell(chapterIdIndex).trim().toLongOrNull() ?: 0L
            require(mangaPath.isNotBlank()) { "Missing manga_path on row $rowNumber" }

            chaptersByManga.getOrPut(
                MangaKey(mangaId, mangaTitle, mangaPath, exportInstanceId),
                ::mutableListOf,
            )
                .add(
                    ChapterTitleTranslationEntry(
                        chapterId = chapterId,
                        originalTitle = row.cell(originalTitleIndex),
                        originalUrl = row.cell(chapterPathIndex),
                        translatedTitle = row.cell(translatedTitleIndex),
                        referenceOnly = referenceOnlyIndex
                            ?.let { row.cell(it).trim().lowercase() in setOf("true", "1", "yes", "是") }
                            ?: false,
                    ),
                )
        }

        return LocalLibraryChapterTitleTranslationDocument(
            mangas = chaptersByManga.map { (manga, chapters) ->
                ChapterTitleTranslationDocument(
                    exportInstanceId = manga.exportInstanceId,
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    mangaUrl = manga.path,
                    chapters = chapters,
                )
            },
        )
    }

    private fun String.isCsv(): Boolean {
        return trimStart(CSV_BOM, ' ', '\t', '\r', '\n').firstOrNull() != '{'
    }

    private fun escapeCsvCell(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun parseCsv(value: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        val input = value.removePrefix(CSV_BOM.toString())

        fun finishField() {
            row.add(field.toString())
            field.clear()
        }

        fun finishRow() {
            finishField()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = mutableListOf()
        }

        while (index < input.length) {
            val character = input[index]
            if (inQuotes) {
                if (character == '"') {
                    if (input.getOrNull(index + 1) == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(character)
                }
            } else {
                when (character) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(character)
                    ',' -> finishField()
                    '\r' -> {
                        finishRow()
                        if (input.getOrNull(index + 1) == '\n') index++
                    }
                    '\n' -> finishRow()
                    else -> field.append(character)
                }
            }
            index++
        }

        require(!inQuotes) { "Unterminated quoted field in chapter title translation table" }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun normalizeCsvHeader(value: String): String {
        return value
            .trim()
            .lowercase()
            .filterNot { it == '_' || it == '-' || it.isWhitespace() }
    }

    private fun List<String>.findColumn(vararg names: String): Int? {
        val accepted = names.toSet()
        return indexOfFirst { it in accepted }.takeIf { it >= 0 }
    }

    private fun List<String>.requireColumn(vararg names: String): Int {
        return findColumn(*names) ?: error("Missing table column: ${names.first()}")
    }

    private fun List<String>.cell(index: Int): String = getOrElse(index) { "" }
}
