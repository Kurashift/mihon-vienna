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

internal object ChapterTitleTranslationCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(manga: Manga, chapters: List<Chapter>): String {
        return json.encodeToString(
            ChapterTitleTranslationDocument(
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
            ),
        )
    }

    fun decode(value: String): ChapterTitleTranslationDocument {
        return json.decodeFromString(value)
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
}
