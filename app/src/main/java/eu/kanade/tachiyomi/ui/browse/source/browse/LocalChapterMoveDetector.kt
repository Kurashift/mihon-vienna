package eu.kanade.tachiyomi.ui.browse.source.browse

internal data class StoredLocalChapter(
    val chapterId: Long,
    val mangaId: Long,
    val mangaUrl: String,
    val fileName: String,
)

internal data class LocalChapterMoveCandidate(
    val chapterId: Long,
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
    val storedNamesByMangaUrl = storedChapters
        .groupBy(StoredLocalChapter::mangaUrl)
        .mapValues { (_, chapters) -> chapters.mapTo(hashSetOf(), StoredLocalChapter::fileName) }
    val missingByName = storedChapters
        .filter { chapter ->
            chapter.fileName in previousFileNamesByMangaUrl[chapter.mangaUrl].orEmpty() &&
                chapter.fileName !in currentFileNamesByMangaUrl[chapter.mangaUrl].orEmpty()
        }
        .groupBy(StoredLocalChapter::fileName)

    val addedByName = currentFileNamesByMangaUrl
        .flatMap { (mangaUrl, fileNames) ->
            val previousNames = previousFileNamesByMangaUrl[mangaUrl].orEmpty()
            val storedNames = storedNamesByMangaUrl[mangaUrl].orEmpty()
            fileNames
                .filter { it !in previousNames && it !in storedNames }
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
            oldMangaId = old.mangaId,
            oldMangaUrl = old.mangaUrl,
            newMangaUrl = newMangaUrl,
            fileName = fileName,
        )
    }
}
