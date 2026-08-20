package eu.kanade.tachiyomi.ui.manga

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga

@Serializable
internal data class ChapterTitleTranslationDocument(
    val formatVersion: Int = 1,
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
)

internal data class ChapterTitleImportPlan(
    val updates: List<ChapterUpdate>,
    val ignoredCount: Int,
)

internal data class LocalLibraryChapterTitleImportPlan(
    val updates: List<ChapterUpdate>,
    val ignoredCount: Int,
)

internal object ChapterTitleTranslationCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(manga: Manga, chapters: List<Chapter>): String {
        return json.encodeToString(toDocument(manga, chapters))
    }

    fun encodeLocalLibrary(mangas: List<Pair<Manga, List<Chapter>>>): String {
        return json.encodeToString(
            LocalLibraryChapterTitleTranslationDocument(
                mangas = mangas
                    .sortedBy { (manga, _) -> manga.title.lowercase() }
                    .map { (manga, chapters) -> toDocument(manga, chapters) },
            ),
        )
    }

    fun decode(value: String): ChapterTitleTranslationDocument {
        return json.decodeFromString(value)
    }

    fun decodeLocalLibrary(value: String): LocalLibraryChapterTitleTranslationDocument {
        return json.decodeFromString(value)
    }

    fun planLocalLibraryImport(
        document: LocalLibraryChapterTitleTranslationDocument,
        currentMangas: List<Pair<Manga, List<Chapter>>>,
    ): LocalLibraryChapterTitleImportPlan {
        require(document.formatVersion == 1) { "Unsupported local library title translation format" }

        val byId = currentMangas.associateBy { (manga, _) -> manga.id }
        val byUrl = currentMangas.groupBy { (manga, _) -> manga.url }
        val allChapters = currentMangas.flatMap { (_, chapters) -> chapters }
        val allChaptersById = allChapters.associateBy(Chapter::id)
        val allChaptersByUrl = allChapters.groupBy(Chapter::url)
        val claimedChapterIds = mutableSetOf<Long>()
        var ignoredCount = 0

        val updates = document.mangas.flatMap { mangaDocument ->
            val idMatch = byId[mangaDocument.mangaId]
                ?.takeIf { (manga, _) ->
                    manga.url == mangaDocument.mangaUrl || manga.title == mangaDocument.mangaTitle
                }
            val urlMatch = byUrl[mangaDocument.mangaUrl]?.singleOrNull()
            val current = idMatch ?: urlMatch
            val currentChaptersById = current?.second.orEmpty().associateBy(Chapter::id)
            val currentChaptersByUrl = current?.second.orEmpty().groupBy(Chapter::url)

            mangaDocument.chapters.mapNotNull { entry ->
                val translatedTitle = entry.translatedTitle.trim()
                if (translatedTitle.isEmpty()) {
                    ignoredCount++
                    return@mapNotNull null
                }

                val localIdMatch = currentChaptersById[entry.chapterId]
                    ?.takeIf { it.name == entry.originalTitle || it.url == entry.originalUrl }
                val localUrlMatch = currentChaptersByUrl[entry.originalUrl]?.singleOrNull()
                // A moved local chapter keeps its database id even when its parent manga changes.
                val movedIdMatch = allChaptersById[entry.chapterId]
                    ?.takeIf { it.name == entry.originalTitle || it.url == entry.originalUrl }
                val globalUrlMatch = allChaptersByUrl[entry.originalUrl]?.singleOrNull()
                val chapter = localIdMatch ?: localUrlMatch ?: movedIdMatch ?: globalUrlMatch

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
    ): ChapterTitleImportPlan {
        require(document.formatVersion == 1) { "Unsupported chapter title translation format" }

        val byId = currentChapters.associateBy { it.id }
        val byUrl = currentChapters.groupBy { it.url }
        val claimedIds = mutableSetOf<Long>()
        var ignoredCount = 0

        val updates = document.chapters.mapNotNull { entry ->
            val translatedTitle = entry.translatedTitle.trim()
            if (translatedTitle.isEmpty()) {
                ignoredCount++
                return@mapNotNull null
            }

            val idMatch = byId[entry.chapterId]
                ?.takeIf { it.name == entry.originalTitle || it.url == entry.originalUrl }
            val urlMatch = byUrl[entry.originalUrl]?.singleOrNull()
            val chapter = idMatch ?: urlMatch

            if (chapter == null || !claimedIds.add(chapter.id)) {
                ignoredCount++
                null
            } else {
                ChapterUpdate(id = chapter.id, translatedName = translatedTitle)
            }
        }

        return ChapterTitleImportPlan(updates, ignoredCount)
    }

    private fun toDocument(manga: Manga, chapters: List<Chapter>): ChapterTitleTranslationDocument {
        return ChapterTitleTranslationDocument(
            mangaId = manga.id,
            mangaTitle = manga.title,
            mangaUrl = manga.url,
            chapters = chapters.map { chapter ->
                ChapterTitleTranslationEntry(
                    chapterId = chapter.id,
                    originalTitle = chapter.name,
                    originalUrl = chapter.url,
                    translatedTitle = chapter.translatedNameOrNull.orEmpty(),
                )
            },
        )
    }
}
