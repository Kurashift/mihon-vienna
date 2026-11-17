package eu.kanade.tachiyomi.ui.stats

import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatsViewModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
) : StateViewModel<StatsScreenState>(StatsScreenState.Success()) {

    private val refreshRevision = MutableStateFlow(0L)

    init {
        val libraryChanges = refreshRevision.flatMapLatest { getLibraryManga.subscribe() }
            .flowOn(Dispatchers.IO)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)
        viewModelScope.launchIO {
            historyRepository.subscribeReadingOverview().observeSection { overview ->
                update {
                    copy(
                        overview = StatsData.Overview(
                            overview.readWorkCount,
                            overview.completedWorkCount,
                            overview.readChapterCount,
                            overview.totalReadDuration,
                        ),
                    )
                }
            }
        }
        viewModelScope.launchIO {
            libraryChanges.observeSection { library ->
                val distinct = library.distinctBy { it.id }
                val localCount = distinct.count { it.manga.isLocal() }.toLong()
                update {
                    copy(library = StatsData.Library(distinct.size.toLong(), localCount, distinct.size - localCount))
                }
            }
        }
        viewModelScope.launchIO {
            val local = sourceManager.get(LocalSource.ID) as? LocalSource ?: return@launchIO
            combine(
                local.observeCachedLibrarySnapshot(),
                mangaRepository.getMangaProgressBySourceAsFlow(LocalSource.ID)
                    .map { entries -> entries.map { it.mangaId to it.url }.toSet() }
                    .distinctUntilChanged(),
                refreshRevision,
            ) { _, _, _ ->
                local.getCachedLibrarySnapshot() to mangaRepository.getMangaIdsBySourceId(LocalSource.ID)
            }.conflate().flatMapLatest { (snapshot, idsByUrl) ->
                val ids = snapshot.chapterCounts.orEmpty().keys.mapNotNull(idsByUrl::get)
                chapterRepository.observeChaptersByMangaIds(ids, applyScanlatorFilter = false)
                    .map { chapters -> localStatsProjection(snapshot, idsByUrl, chapters) }
            }.observeSection { localStats -> update { copy(localLibrary = localStats) } }
        }
        viewModelScope.launchIO {
            downloadCache.changes.onEach { downloadCache.awaitReady() }
                .map { downloadManager.getDownloadCount().toLong() }
                .observeSection { count -> update { copy(downloads = StatsData.Downloads(count)) } }
        }
        viewModelScope.launchIO {
            combine(
                libraryChanges,
                getTracks.subscribeAll(),
                refreshRevision,
            ) { library, tracks, _ ->
                val ids = library.mapTo(hashSetOf()) { it.id }
                val services = trackerManager.loggedInTrackers()
                val serviceIds = services.mapTo(hashSetOf()) { it.id }
                val tracksByManga = tracks.filter { it.mangaId in ids && it.trackerId in serviceIds }
                    .groupBy { it.mangaId }
                StatsData.Trackers(
                    trackedTitleCount = tracksByManga.size.toLong(),
                    meanScore = meanScore(tracksByManga),
                    trackerCount = services.size.toLong(),
                )
            }.observeSection { trackers -> update { copy(trackers = trackers) } }
        }
    }

    /** Settings and tracker login can change while this screen remains on the navigator stack. */
    fun onResume() {
        refreshRevision.update { it + 1 }
    }

    private fun meanScore(tracksByManga: Map<Long, List<Track>>): Double {
        return tracksByManga.values.mapNotNull { tracks ->
            tracks.filter { it.score > 0.0 }
                .mapNotNull { track -> trackerManager.get(track.trackerId)?.get10PointScore(track) }
                .filter(Double::isFinite)
                .takeIf { it.isNotEmpty() }
                ?.average()
        }.average()
    }

    private fun update(transform: StatsScreenState.Success.() -> StatsScreenState.Success) {
        mutableState.update { (it as? StatsScreenState.Success ?: StatsScreenState.Success()).transform() }
    }

    private suspend fun <T> Flow<T>.observeSection(onValue: suspend (T) -> Unit) {
        refreshRevision.flatMapLatest {
            this.onEach(onValue).catch { error ->
                if (error is CancellationException) throw error
                logcat(LogPriority.ERROR, error) { "Failed to update statistics section" }
            }
        }.collectLatest { }
    }
}
