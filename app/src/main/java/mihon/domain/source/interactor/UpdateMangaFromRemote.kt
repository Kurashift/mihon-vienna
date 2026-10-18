package mihon.domain.source.interactor

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.local.withLocalChapterMutationLock
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import logcat.LogPriority
import mihon.domain.source.models.RemoteMangaUpdate
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import kotlin.time.Clock

class UpdateMangaFromRemote(
    private val sourceManager: SourceManager,
    private val chapterRepository: ChapterRepository,
    private val mangaRepository: MangaRepository,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val coverCache: CoverCache,
    private val libraryPreferences: LibraryPreferences,
    private val downloadManager: DownloadManager,
) {
    suspend operator fun invoke(
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteMangaUpdate> {
        val source = sourceManager.getOrStub(manga.source)
        return invoke(
            source = source,
            manga = manga,
            fetchDetails = fetchDetails,
            fetchChapters = fetchChapters,
            manualFetch = manualFetch,
        )
    }

    suspend operator fun invoke(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean = false,
        fetchChapters: Boolean = false,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
    ): Result<RemoteMangaUpdate> {
        return try {
            val performUpdate: suspend () -> RemoteMangaUpdate = {
                updateFromSource(
                    source = source,
                    manga = manga,
                    fetchDetails = fetchDetails,
                    fetchChapters = fetchChapters,
                    manualFetch = manualFetch,
                    fetchWindow = fetchWindow,
                )
            }
            val update = if (source.isLocal()) {
                // Keep one manga's disk snapshot and database sync atomic with its imports.
                withLocalChapterMutationLock(manga.url, performUpdate)
            } else {
                performUpdate()
            }
            Result.success(update)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            Result.failure(e)
        }
    }

    private suspend fun updateFromSource(
        source: Source,
        manga: Manga,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        manualFetch: Boolean,
        fetchWindow: Pair<Long, Long>,
    ): RemoteMangaUpdate {
        val chapters = chapterRepository.getChapterByMangaId(manga.id)
            .sortedBy { it.sourceOrder }
        val update = withIOContext {
            source.getMangaUpdate(
                manga = manga.toSManga(),
                chapters = chapters.map(Chapter::toSChapter),
                fetchDetails = fetchDetails,
                fetchChapters = fetchChapters,
            )
        }
        awaitUpdateFromSource(manga, update.manga, manualFetch)
        val newChapters = syncChaptersWithSource.await(
            rawSourceChapters = update.chapters,
            manga = manga,
            source = source,
            manualFetch = manualFetch,
            fetchWindow = fetchWindow,
            allowEmptyLocalSource = source is tachiyomi.source.local.LocalSource && fetchChapters,
        )
        // A chapter that did not exist in this work before is the work entering the library again,
        // and the local source's date sort reads date_added as exactly that moment (see LocalSource's
        // ordering). The import screen already re-dates a work it adds chapters to; a chapter file
        // copied into an existing work's folder and picked up by a directory scan - the manual
        // refresh or the details screen's own change check - came through here instead, so the work
        // kept the date it first appeared at and a freshly added chapter never moved it to the top
        // of the import-date list.
        //
        // Only an addition counts. A chapter file replaced under its own name adds nothing, and a
        // deletion is not an import either, so neither re-dates the work.
        if (source.isLocal() && newChapters.isNotEmpty()) {
            mangaRepository.update(
                MangaUpdate(
                    id = manga.id,
                    dateAdded = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            // The browse list memoizes each row it has already resolved, so a date written here
            // would not reach the screen until some unrelated control rebuilt it. The date is what
            // the import-date ordering reads, so the listing has to be derived again for the work
            // to actually move.
            (source as? tachiyomi.source.local.LocalSource)?.invalidateListing()
        }
        // A local cleanup can drop the row while this update is running; the source work already
        // succeeded, so report it against the entry we started from instead of failing it.
        val updatedManga = mangaRepository.getMangaByIdOrNull(manga.id) ?: manga

        return RemoteMangaUpdate(manga = updatedManga, newChapters = newChapters)
    }

    private suspend fun awaitUpdateFromSource(
        localManga: Manga,
        remoteManga: SManga,
        manualFetch: Boolean,
    ): Boolean {
        val remoteTitle = try {
            remoteManga.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }

        // if the manga isn't a favorite (or 'update titles' preference is enabled), set its title from source and update in db
        val title =
            if (remoteTitle.isNotEmpty() && (!localManga.favorite || libraryPreferences.updateMangaTitles.get())) {
                remoteTitle
            } else {
                null
            }

        val coverLastModified = when {
            // Never refresh covers if the url is empty to avoid "losing" existing covers
            remoteManga.thumbnail_url.isNullOrEmpty() -> null
            !manualFetch && localManga.thumbnailUrl == remoteManga.thumbnail_url -> null
            localManga.isLocal() -> Clock.System.now().toEpochMilliseconds()
            localManga.hasCustomCover(coverCache) -> {
                coverCache.deleteFromCache(localManga, false)
                null
            }
            else -> {
                coverCache.deleteFromCache(localManga, false)
                Clock.System.now().toEpochMilliseconds()
            }
        }

        val thumbnailUrl = remoteManga.thumbnail_url?.takeIf { it.isNotEmpty() }

        val success = mangaRepository.update(
            MangaUpdate(
                id = localManga.id,
                title = title,
                coverLastModified = coverLastModified,
                author = remoteManga.author,
                artist = remoteManga.artist,
                description = remoteManga.description,
                genre = remoteManga.getGenres(),
                thumbnailUrl = thumbnailUrl,
                status = remoteManga.status.toLong(),
                updateStrategy = remoteManga.update_strategy,
                initialized = true,
                memo = remoteManga.memo,
            ),
        )
        if (success && title != null) {
            downloadManager.renameManga(localManga, title)
        }
        return success
    }
}
