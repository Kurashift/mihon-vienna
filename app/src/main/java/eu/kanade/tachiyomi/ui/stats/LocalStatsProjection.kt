package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.data.StatsData
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.source.local.LocalLibrarySnapshot

internal fun localStatsProjection(
    snapshot: LocalLibrarySnapshot,
    mangaIdsByUrl: Map<String, Long>,
    chapters: List<Chapter>,
): StatsData.LocalLibrary {
    val counts = snapshot.chapterCounts ?: return StatsData.LocalLibrary()
    val completeReadCoverage = counts.keys.all { url ->
        url !in mangaIdsByUrl || url in snapshot.chapterUrlsByMangaUrl
    }
    val readCount = if (completeReadCoverage) {
        val validUrlsById = snapshot.chapterUrlsByMangaUrl.mapNotNull { (url, chapterUrls) ->
            mangaIdsByUrl[url]?.let { it to chapterUrls }
        }.toMap()
        chapters.asSequence()
            .filter { it.read && it.url in validUrlsById[it.mangaId].orEmpty() }
            .distinctBy { it.mangaId to it.url }
            .count().toLong()
    } else {
        null
    }
    return StatsData.LocalLibrary(snapshot.workCount, snapshot.chapterCount, readCount)
}
