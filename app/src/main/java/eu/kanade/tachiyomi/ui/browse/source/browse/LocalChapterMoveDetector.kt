package eu.kanade.tachiyomi.ui.browse.source.browse

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.source.local.io.isSingleChapterEntryName

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
    // The virtual chapter of a folder-level single work carries a name every flat folder shares,
    // so it would pair two unrelated works: one that just grew a chapter folder (its virtual
    // chapter disappeared) with one that just appeared (its virtual chapter is new). That would
    // move the first work's reading progress onto the second one, so it is never a move.
    val movableChapters = storedChapters.filterNot { isSingleChapterEntryName(it.fileName) }
    val previousNames = previousFileNamesByMangaUrl.mapValues { (_, names) ->
        names.filterNotTo(hashSetOf(), ::isSingleChapterEntryName)
    }
    val currentNames = currentFileNamesByMangaUrl.mapValues { (_, names) ->
        names.filterNotTo(hashSetOf(), ::isSingleChapterEntryName)
    }

    val missingByName = movableChapters
        .filter { chapter ->
            previousNames[chapter.mangaUrl].orEmpty().containsChapterFile(chapter.fileName) &&
                chapter.fileName !in currentNames[chapter.mangaUrl].orEmpty()
        }
        .groupBy(StoredLocalChapter::fileName)

    val addedByName = currentNames
        .flatMap { (mangaUrl, fileNames) ->
            val previousForManga = previousNames[mangaUrl].orEmpty()
            fileNames
                .filter { !previousForManga.containsChapterFile(it) }
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
            duplicateChapterId = movableChapters
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
 *
 * The virtual chapter of a folder-level single work is never revived: every flat folder is called
 * the same, so a work whose folder was deleted would hand its reading progress to whichever flat
 * work happened to be the only other one. Losing the relocation when such a work is renamed is the
 * price of never moving progress onto the wrong work.
 */
internal fun detectStaleLocalChapterMoves(
    storedChapters: List<StoredLocalChapter>,
    currentFileNamesByMangaUrl: Map<String, Set<String>>,
): List<LocalChapterMoveCandidate> {
    val currentLocationsByName = currentFileNamesByMangaUrl
        .flatMap { (mangaUrl, fileNames) -> fileNames.map { fileName -> fileName to mangaUrl } }
        .groupBy({ it.first }, { it.second })
    val currentMangaUrls = currentFileNamesByMangaUrl.keys

    return storedChapters
        .filterNot { isSingleChapterEntryName(it.fileName) }
        .mapNotNull { old ->
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
        // The duplicate row is about to be removed, so its finish timestamp has to travel with the
        // merge; keeping the earlier of the two preserves the "first finished" meaning.
        markedReadAt = earliestMarkedReadAt(listOf(chapter, duplicate)),
    )
}

/**
 * Picks the finish timestamp to carry across a merge: the earliest one any of the merged rows has.
 *
 * The column means "first finished", so a row that was re-stamped later must not move the date
 * forward. Rows without a stamp carry 0, which is absence rather than a very old date, and null is
 * returned when none of them has one so the caller leaves the stored value untouched.
 */
private fun earliestMarkedReadAt(chapters: List<Chapter>): Long? {
    return chapters.map(Chapter::markedReadAt).filter { it > 0 }.minOrNull()
}

internal fun findExactLocalChapterDuplicateGroups(chapters: List<Chapter>): List<List<Chapter>> {
    return chapters
        .groupBy { it.mangaId to it.url }
        .values
        .filter { it.size > 1 }
}

internal fun mergeExactLocalChapterDuplicates(
    chapters: List<Chapter>,
    preferredProgressChapterId: Long? = null,
): ChapterUpdate {
    require(chapters.size > 1)
    require(chapters.map { it.mangaId to it.url }.distinct().size == 1)

    val ordered = chapters.sortedBy(Chapter::id)
    val keeper = ordered.first()
    val metadata = ordered.maxWithOrNull(
        compareBy<Chapter> { it.totalPages }
            .thenBy { it.memo.size }
            .thenBy(Chapter::id),
    ) ?: keeper
    val progress = ordered.firstOrNull { it.id == preferredProgressChapterId }
        ?: ordered.maxBy { it.lastPageRead }

    return ChapterUpdate(
        id = keeper.id,
        mangaId = keeper.mangaId,
        read = ordered.any(Chapter::read),
        bookmark = ordered.any(Chapter::bookmark),
        lastPageRead = progress.lastPageRead,
        totalPages = ordered.maxOf(Chapter::totalPages),
        customOrder = keeper.customOrder,
        dateFetch = ordered.maxOf(Chapter::dateFetch),
        sourceOrder = metadata.sourceOrder,
        url = keeper.url,
        name = metadata.name,
        dateUpload = ordered.maxOf(Chapter::dateUpload),
        chapterNumber = metadata.chapterNumber,
        scanlator = metadata.scanlator ?: ordered.firstNotNullOfOrNull(Chapter::scanlator),
        version = ordered.maxOf(Chapter::version),
        memo = JsonObject(
            buildMap {
                ordered.forEach { putAll(it.memo) }
            },
        ),
        translatedName = ordered.firstNotNullOfOrNull(Chapter::translatedNameOrNull),
        // Same reason as the move merge: the rows folded in here disappear, so the keeper has to
        // take over the earliest finish timestamp among them.
        markedReadAt = earliestMarkedReadAt(ordered),
    )
}

private fun Set<String>.containsChapterFile(fileName: String): Boolean {
    return fileName in this || fileName.substringBeforeLast('.', fileName) in this
}
