package eu.kanade.tachiyomi.util

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdate
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdateStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.time.Clock

fun Manga.removeCovers(coverCache: CoverCache = Injekt.get()): Manga {
    if (isLocal()) return this
    return if (coverCache.deleteFromCache(this, true) > 0) {
        copy(coverLastModified = Clock.System.now().toEpochMilliseconds())
    } else {
        this
    }
}

suspend fun Manga.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateManga: UpdateManga = Injekt.get(),
    coverCache: CoverCache = Injekt.get(),
    coverUpdateStore: MangaCoverUpdateStore = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
) {
    if (isLocal()) {
        val coverUri = coverManager.update(toSManga(), stream)?.uri?.toString()
            ?: error("Local manga directory is unavailable")

        // A migrated manga can still have an app-private custom cover, which the image loader
        // prefers over the local source file. Remove it so list and detail use the same cover.
        coverCache.deleteCustomCover(id)

        val revision = Clock.System.now().toEpochMilliseconds()
        val updated = updateManga.await(
            MangaUpdate(
                id = id,
                thumbnailUrl = coverUri,
                coverLastModified = revision,
            ),
        )
        if (!updated) error("Failed to persist local manga cover")
        runCatching {
            (sourceManager.get(LocalSource.ID) as? LocalSource)?.refreshMangaCover(url, coverUri)
        }
        coverUpdateStore.publish(id, MangaCoverUpdate(coverUri, revision))
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateManga.awaitUpdateCoverLastModified(id)
    }
}
