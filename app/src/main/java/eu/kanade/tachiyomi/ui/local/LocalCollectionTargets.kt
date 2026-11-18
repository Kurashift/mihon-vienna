package eu.kanade.tachiyomi.ui.local

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal suspend fun MangaRepository.getLocalCollectionTargets(
    localMangas: List<Manga>,
    excludedId: Long? = null,
): List<Manga> {
    val source = Injekt.get<SourceManager>().get(LocalSource.ID) as? LocalSource
    val indexedUrls = source?.getCachedCollectionUrls()
    // Without an index, retain the previous database-backed choices without scanning storage.
    val idsWithChapters = if (indexedUrls == null) {
        getMangaProgressBySource(LocalSource.ID).mapTo(hashSetOf()) { it.mangaId }
    } else {
        emptySet()
    }
    return localCollectionTargets(localMangas, indexedUrls, idsWithChapters, excludedId)
}

internal fun localCollectionTargets(
    localMangas: List<Manga>,
    indexedUrls: Set<String>?,
    idsWithChapters: Set<Long>,
    excludedId: Long? = null,
): List<Manga> = localMangas
    .filter { manga ->
        manga.id != excludedId &&
            (indexedUrls?.contains(manga.url) ?: (manga.id in idsWithChapters))
    }
    .sortedBy { it.title.lowercase() }
