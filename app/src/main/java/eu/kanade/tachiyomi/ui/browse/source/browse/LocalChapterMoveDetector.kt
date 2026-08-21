package eu.kanade.tachiyomi.ui.browse.source.browse

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate

internal data class StoredLocalChapter(
    val chapterId: Long,
    val mangaId: Long,
    val mangaUrl: String,
    val fileName: String,
)

internal data class LocalChapterMoveCandidate(
    val chapterId: Long,
    val duplicateChapterId: Long?,
    val oldMangaId: Long,
    val oldMangaUrl: String,
    val newMangaUrl: String,
    val fileName: String,
)

internal fun detectLocalChapterMoves(
    storedChapters: List<StoredLocalChapter>,
    previousFileNamesByMangaUrl: Map<String, Set<String>>,
    currentFileNamesByMangaUrl: Map<String, Set<String>>,
): List<LocalChapterMoveCandidate> {
    val missingByName = storedChapters
        .filter { chapter ->
            previousFileNamesByMangaUrl[chapter.mangaUrl].orEmpty().containsChapterFile(chapter.fileName) &&
                chapter.fileName !in currentFileNamesByMangaUrl[chapter.mangaUrl].orEmpty()
        }
        .groupBy(StoredLocalChapter::fileName)

    val addedByName = currentFileNamesByMangaUrl
        .flatMap { (mangaUrl, fileNames) ->
            val previousNames = previousFileNamesByMangaUrl[mangaUrl].orEmpty()
            fileNames
                .filter { !previousNames.containsChapterFile(it) }
                .map { fileName -> mangaUrl to fileName }
        }
        .groupBy({ it.second }, { it.first })

    return missingByName.mapNotNull { (fileName, missing) ->
        val destinations = addedByName[fileName].orEmpty()
        val old = missing.singleOrNull() ?: return@mapNotNull null
        val newMangaUrl = destinations.singleOrNull() ?: return@mapNotNull null
        if (old.mangaUrl == newMangaUrl) return@mapNotNull null

        LocalChapterMoveCandidate(
            chapterId = old.chapterId,
            duplicateChapterId = storedChapters
                .singleOrNull { chapter ->
                    chapter.mangaUrl == newMangaUrl && chapter.fileName == fileName
                }
                ?.chapterId,
            oldMangaId = old.mangaId,
            oldMangaUrl = old.mangaUrl,
            newMangaUrl = newMangaUrl,
            fileName = fileName,
        )
    }
}

/**
 * Recovers a move after a previous refresh already committed the new disk layout. Only an exact
 * file name that exists in one current manga folder can revive a chapter from a now-missing manga
 * folder; ambiguous names are deliberately ignored.
 */
internal fun detectStaleLocalChapterMoves(
    storedChapters: List<StoredLocalChapter>,
    currentFileNamesByMangaUrl: Map<String, Set<String>>,
): List<LocalChapterMoveCandidate> {
    val currentLocationsByName = currentFileNamesByMangaUrl
        .flatMap { (mangaUrl, fileNames) -> fileNames.map { fileName -> fileName to mangaUrl } }
        .groupBy({ it.first }, { it.second })
    val currentMangaUrls = currentFileNamesByMangaUrl.keys

    return storedChapters.mapNotNull { old ->
        if (old.mangaUrl in currentMangaUrls) return@mapNotNull null
        val newMangaUrl = currentLocationsByName[old.fileName]
            ?.distinct()
            ?.singleOrNull()
            ?: return@mapNotNull null
        val duplicate = storedChapters.singleOrNull { chapter ->
            chapter.mangaUrl == newMangaUrl && chapter.fileName == old.fileName
        }
        LocalChapterMoveCandidate(
            chapterId = old.chapterId,
            duplicateChapterId = duplicate?.chapterId,
            oldMangaId = old.mangaId,
            oldMangaUrl = old.mangaUrl,
            newMangaUrl = newMangaUrl,
            fileName = old.fileName,
        )
    }
}

internal fun mergeMovedLocalChapter(
    chapter: Chapter,
    duplicate: Chapter,
    targetMangaId: Long,
    targetUrl: String,
    preferDuplicateProgress: Boolean = false,
): ChapterUpdate {
    return ChapterUpdate(
        id = chapter.id,
        mangaId = targetMangaId,
        read = chapter.read || duplicate.read,
        bookmark = chapter.bookmark || duplicate.bookmark,
        lastPageRead = if (preferDuplicateProgress) duplicate.lastPageRead else chapter.lastPageRead,
        totalPages = maxOf(chapter.totalPages, duplicate.totalPages),
        customOrder = 0,
        dateFetch = maxOf(chapter.dateFetch, duplicate.dateFetch),
        sourceOrder = duplicate.sourceOrder,
        url = targetUrl,
        name = duplicate.name,
        dateUpload = duplicate.dateUpload,
        chapterNumber = duplicate.chapterNumber,
        scanlator = duplicate.scanlator,
        version = maxOf(chapter.version, duplicate.version),
        memo = JsonObject(chapter.memo + duplicate.memo),
        translatedName = chapter.translatedNameOrNull ?: duplicate.translatedNameOrNull,
    )
}

private fun Set<String>.containsChapterFile(fileName: String): Boolean {
    return fileName in this || fileName.substringBeforeLast('.', fileName) in this
}
