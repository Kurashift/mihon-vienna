package eu.kanade.domain.manga.interactor

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource
import kotlin.time.Clock

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        dateTime: LocalDateTime = Clock.System.now().toLocalDateTime(timeZone),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime.date, timeZone),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, timeZone, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Clock.System.now().toEpochMilliseconds()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                coverLastModified = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val manga = mangaRepository.getMangaByIdOrNull(mangaId)
            ?: return mangaRepository.update(MangaUpdate(id = mangaId, favorite = favorite))
        // Already in the state the caller is asking for: writing it again would still refresh the
        // row's last_modified_at through a trigger, and for a non-local work it would push
        // date_added forward - re-dating the entry, and under "date added" moving it to the top of
        // the library for a toggle that changed nothing.
        if (manga.favorite == favorite) return true

        // The local source's date sort reads date_added as the day the work last entered the
        // library (see LocalSource's ordering), so shelf membership must not rewrite it. Only a
        // real addition of content moves it - importing new chapters into the work. Other sources
        // keep the upstream semantics: the date the work was added to the library.
        val dateAdded = when {
            manga.source == LocalSource.ID -> null
            favorite -> Clock.System.now().toEpochMilliseconds()
            else -> 0
        }
        return mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
    }
}
