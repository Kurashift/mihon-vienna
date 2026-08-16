package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.LibraryReturnAnchorStore
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdateStore
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.data.manga.RandomSelectionCooldown
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaProgress
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.model.MangaProgressByMangaId
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceViewModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getMangaProgress: GetMangaProgress = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    mangaMarkStore: MangaMarkStore = Injekt.get(),
    private val goodDoujinStore: GoodDoujinStore = Injekt.get(),
    getIncognitoState: GetIncognitoState = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val randomSelectionCooldown: RandomSelectionCooldown = Injekt.get(),
    private val libraryReturnAnchorStore: LibraryReturnAnchorStore = Injekt.get(),
    private val mangaCoverUpdateStore: MangaCoverUpdateStore = Injekt.get(),
) : StateViewModel<BrowseSourceViewModel.State>(State(Listing.valueOf(listingQuery))) {

    companion object {
        private const val CLEAR_HISTORY_BATCH_SIZE = 500
        private const val LOCAL_DIRECTORY_POLL_MILLIS = 30_000L
        private const val LOCAL_REFRESH_CONCURRENCY = 6

        val SOURCE_ID_KEY = CreationExtras.Key<Long>()
        val LISTING_QUERY_KEY = CreationExtras.Key<String?>()

        val Factory = viewModelFactory {
            initializer {
                BrowseSourceViewModel(
                    sourceId = get(SOURCE_ID_KEY)!!,
                    listingQuery = get(LISTING_QUERY_KEY),
                )
            }
        }
    }

    var displayMode by sourcePreferences.sourceDisplayMode.asState(viewModelScope)

    // Emits when a manga is added to the library without any visible dialog.
    val events = Channel<Unit>()

    val source = sourceManager.getOrStub(sourceId)

    private val favoriteIdsInternal = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Latest set of favorited manga ids for this source. The list UI subscribes to
     * this directly so adding/removing a favorite updates the cover and badges
     * immediately, without waiting for the pager to reload.
     */
    val favoriteIds: StateFlow<Set<Long>> = favoriteIdsInternal
    private var hasLoadedFavoriteSnapshot = false

    /** Manga that anchored the library viewport before navigating into a detail screen. */
    val returnAnchorMangaId: StateFlow<Long?> = libraryReturnAnchorStore.mangaIdToRestore

    fun rememberReturnAnchor(mangaId: Long) {
        libraryReturnAnchorStore.remember(mangaId)
    }

    fun consumeReturnAnchor() {
        libraryReturnAnchorStore.consume()
    }

    init {
        if (source is LocalSource) {
            when (mutableState.value.listing) {
                Listing.Popular -> source.resetOrderBy(popular = true)
                Listing.Latest -> source.resetOrderBy(popular = false)
                else -> {}
            }
        }

        mutableState.update {
            var query: String? = null
            var listing = it.listing

            if (listing is Listing.Search) {
                query = listing.query
                listing = Listing.Search(query, source.getFilterList())
            }

            it.copy(
                listing = listing,
                filters = source.getFilterList(),
                toolbarQuery = query,
            )
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedSource.set(source.id)
        }

    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInLibraryItems.get()

    private var observedLocalDirectorySignature: String? = null
    private var lastLocalDirectoryCheckAt = Long.MIN_VALUE
    private var visibleSnapshotRefreshJob: Job? = null
    private val screenVisible = MutableStateFlow(false)
    private val progressSnapshot = MutableStateFlow<List<MangaProgressByMangaId>>(emptyList())
    private val localChapterCounts = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val readingFilterPreference = preferenceStore.getString(
        "browse_reading_filter_$sourceId",
        ReadingFilter.ALL.name,
    )
    private val readingFilterInternal = MutableStateFlow(
        runCatching { ReadingFilter.valueOf(readingFilterPreference.get()) }
            .getOrDefault(ReadingFilter.ALL),
    )
    val readingFilter: StateFlow<ReadingFilter> = readingFilterInternal
    private val markFilterPreference = preferenceStore.getString(
        "browse_mark_filter_$sourceId",
        MarkFilter.NONE.name,
    )
    private val markFilterInternal = MutableStateFlow(
        if (source is LocalSource && state.value.listing == Listing.Latest) {
            MarkFilter.NONE
        } else {
            runCatching { MarkFilter.valueOf(markFilterPreference.get()) }
                .getOrDefault(MarkFilter.NONE)
        },
    )
    val markFilter: StateFlow<MarkFilter> = markFilterInternal

    /** Live phase of the local chapter refresh, null when no pass is running. */
    sealed interface ChapterRefreshProgress {
        data class Checking(val completed: Int, val total: Int) : ChapterRefreshProgress
        data class Updating(val completed: Int, val total: Int) : ChapterRefreshProgress
    }

    data class ChapterRefreshResult(
        val changedManga: Int,
        val newChapters: Int,
        val storageUnavailable: Boolean = false,
    )

    /**
     * Whether a manual "refresh all chapters" pass is currently running for the local source,
     * together with its live progress. Guards against double taps; also freezes progress-driven
     * list refreshes while it runs so the screen doesn't flicker for every manga that gets synced.
     */
    val isRefreshingChapters = MutableStateFlow<ChapterRefreshProgress?>(null)

    /** True when the local base directory changed since the last rescan, prompting a refresh. */
    val localSourceChanged = MutableStateFlow(false)

    /**
     * Refreshes expensive library-wide data once when this screen becomes visible. Cached values
     * remain available while a detail screen or reader is open, so returning never reconnects a
     * tree of database flows before the list can be drawn.
     */
    fun onScreenVisible() {
        screenVisible.value = true
        if (visibleSnapshotRefreshJob?.isActive == true) return
        visibleSnapshotRefreshJob = viewModelScope.launchIO {
            refreshVisibleSnapshots()
        }
    }

    fun onScreenHidden() {
        screenVisible.value = false
        visibleSnapshotRefreshJob?.cancel()
    }

    private suspend fun refreshVisibleSnapshots(forceDirectoryCheck: Boolean = false) = coroutineScope {
        val progress = async { getMangaProgress.awaitForSource(sourceId) }
        val favorites = async { mangaRepository.getFavoriteIdsBySourceId(sourceId).toHashSet() }
        val local = source as? LocalSource
        val directory = local?.let {
            async { refreshLocalDirectorySnapshot(it, forceDirectoryCheck) }
        }

        progressSnapshot.value = progress.await()
        val favoriteIds = favorites.await()
        val favoritesChanged = hasLoadedFavoriteSnapshot && favoriteIdsInternal.value != favoriteIds
        favoriteIdsInternal.value = favoriteIds
        hasLoadedFavoriteSnapshot = true
        if (hideInLibraryItems && favoritesChanged) {
            invalidatePagingSources()
        }
        directory?.await()
    }

    private suspend fun refreshLocalDirectorySnapshot(local: LocalSource, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && observedLocalDirectorySignature != null &&
            now - lastLocalDirectoryCheckAt < LOCAL_DIRECTORY_POLL_MILLIS
        ) {
            return
        }
        lastLocalDirectoryCheckAt = now

        val signature = local.getMangaDirectorySignature() ?: return
        val previous = observedLocalDirectorySignature
        if (previous == signature && localChapterCounts.value.isNotEmpty()) return

        observedLocalDirectorySignature = signature
        if (previous == null) {
            val committed = basePreferences.localSourceDirectorySignature.get()
            if (committed.isBlank()) {
                basePreferences.localSourceDirectorySignature.set(signature)
            } else if (signature != committed) {
                localSourceChanged.value = true
            }
        } else if (previous != signature) {
            invalidatePagingSources()
            localSourceChanged.value = true
        }
        localChapterCounts.value = local.getChapterCounts()
    }

    private val progressContext: StateFlow<ProgressContext> = combine(
        progressSnapshot,
        localChapterCounts,
        mangaMarkStore.marks,
        goodDoujinStore.marks,
    ) { progressList, fsChapterCounts, duplicateMarks, goodDoujinMarks ->
        val urlByMangaId = progressList.associate { it.mangaId to it.url }
        val flaggedUrls = duplicateMarks.mapNotNullTo(HashSet()) { urlByMangaId[it.mangaId] }
        val goodDoujinUrls = goodDoujinMarks.mapNotNullTo(HashSet()) { urlByMangaId[it.mangaId] }

        ProgressContext(
            progressByMangaId = progressList.associate { it.mangaId to it.progress },
            progressByUrl = progressList.associate { it.url to it.progress },
            fsChapterCounts = fsChapterCounts,
            lastReadMangaId = progressList
                .filter { it.lastOpenedAt > 0L }
                .maxByOrNull { it.lastOpenedAt }
                ?.mangaId,
            flaggedUrls = flaggedUrls,
            goodDoujinUrls = goodDoujinUrls,
        )
    }
        // While "refresh all chapters" is running, each synced manga writes to the database and
        // would re-emit the progress flow, causing the whole list to reload on every single
        // manga. Drop those intermediate updates and only let the final state through once the
        // pass finishes, so the screen refreshes exactly once instead of flickering constantly.
        .combine(isRefreshingChapters) { context, progress -> (progress != null) to context }
        .filter { (refreshing, _) -> !refreshing }
        .map { (_, context) -> context }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ProgressContext(emptyMap(), emptyMap(), emptyMap()),
        )

    // Exposed to the render layer so each visible card can look up progress/last-read state at
    // draw time instead of baking it into the paged items (which would rebuild the whole list
    // every time a chapter is read).
    val progressContextState: StateFlow<ProgressContext> = progressContext

    /**
     * Minimal projection of [progressContext] that only carries fields affecting the list
     * FILTERS. Reading a chapter changes progress, but when the reading filter is ALL (and no
     * mark filter is active) that must not invalidate the paged list; edges here only change
     * when an include/exclude set actually changes.
     */
    @Immutable
    private data class FilterContext(
        val flaggedUrls: Set<String> = emptySet(),
        val goodDoujinUrls: Set<String> = emptySet(),
        val finishedUrls: Set<String> = emptySet(),
        val startedUrls: Set<String> = emptySet(),
    )

    private val filterContext: StateFlow<FilterContext> = combine(
        progressContext,
        readingFilterInternal,
        markFilterInternal,
    ) { context, readingFilter, markFilter ->
        FilterContext(
            flaggedUrls = if (markFilter == MarkFilter.FLAGGED) context.flaggedUrls else emptySet(),
            goodDoujinUrls = if (markFilter == MarkFilter.GOOD_DOUJIN) context.goodDoujinUrls else emptySet(),
            finishedUrls = if (readingFilter != ReadingFilter.ALL) {
                context.progressByUrl.filterValues(MangaProgress::hasFinished).keys.toSet()
            } else {
                emptySet()
            },
            startedUrls = if (readingFilter == ReadingFilter.IN_PROGRESS) {
                context.progressByUrl.filterValues(MangaProgress::hasBeenRead).keys.toSet()
            } else {
                emptySet()
            },
        )
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FilterContext(),
        )

    /**
     * Number of manga entries in the currently displayed list, after applying the
     * active listing (popular/latest/search) and the reading filter. Shown next to
     * the source name in the toolbar so it stays in sync with the filter chips.
     */
    val currentViewMangaCount: StateFlow<Int> = if (source is LocalSource) {
        combine(
            state.map { it.listing }.distinctUntilChanged(),
            progressContext,
            readingFilterInternal,
            markFilterInternal,
            screenVisible,
        ) { listing, context, filter, markFilter, visible ->
            if (visible) {
                CountFilterArgs(
                    listing = listing,
                    readingFilter = filter,
                    context = context.toCountContext(filter, markFilter),
                )
            } else {
                null
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .flatMapLatest { (listing, filter, context) ->
                flow {
                    val local = source
                    val urls = withIOContext {
                        when (listing) {
                            is Listing.Popular -> local.getPopularMangaUrls()
                            is Listing.Latest -> local.getLatestMangaUrls()
                            is Listing.Search -> local.getSearchMangaUrls(listing.query.orEmpty(), listing.filters)
                        }
                    }
                    emit(
                        urls.count { url ->
                            context.matchesReadingFilter(filter, url) &&
                                context.matchesMarkFilter(url)
                        },
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    } else {
        MutableStateFlow(0)
    }

    private val pagerCache = ConcurrentHashMap<Listing, Flow<PagingData<Manga>>>()

    private val pagingSources = ConcurrentHashMap.newKeySet<SourcePagingSource>()

    /**
     * Invalidates every paging source created so far (current listing, cached Popular/Latest
     * and any open search) so they reload with fresh data, e.g. after the local directory
     * changed or a manga was added to/removed from the library.
     */
    private fun invalidatePagingSources() {
        // Pager instances for cached listings are created once, so keep their sources registered
        // for later directory, favorite-filter, and manual-refresh invalidations.
        pagingSources.forEach { it.invalidate() }
    }

    private fun buildPager(listing: Listing): Flow<PagingData<Manga>> {
        val pageSize = if (source is LocalSource) LocalSource.PAGE_SIZE else 25
        return Pager(
            PagingConfig(
                pageSize = pageSize,
                initialLoadSize = pageSize,
                enablePlaceholders = source is LocalSource,
            ),
        ) {
            getRemoteManga(sourceId, listing.query ?: "", listing.filters).also {
                pagingSources += it
            }
        }.flow
            .map { pagingData ->
                pagingData.filter { !hideInLibraryItems || !it.favorite }
            }
            .cachedIn(viewModelScope)
    }

    private fun pagerFor(listing: Listing): Flow<PagingData<Manga>> {
        return if (listing is Listing.Search) {
            buildPager(listing)
        } else {
            pagerCache.getOrPut(listing) {
                buildPager(listing)
            }
        }
    }

    val mangaPagerFlowFlow = combine(
        state.map { it.listing }
            .distinctUntilChanged()
            .map { listing -> listing to pagerFor(listing) },
        filterContext,
        readingFilterInternal,
        markFilterInternal,
        mangaCoverUpdateStore.covers,
    ) { (listing, pagerFlow), filterCtx, filter, markFilter, coverUpdates ->
        pagerFlow.map { pagingData ->
            val items = pagingData.map { manga ->
                val currentManga = coverUpdates[manga.id]?.let { cover ->
                    manga.copy(
                        thumbnailUrl = cover.url,
                        coverLastModified = cover.lastModified,
                    )
                } ?: manga
                BrowseSourceUiModel.Item(
                    manga = currentManga,
                    matchedChapter = currentManga.memo[LocalSource.MATCHED_CHAPTER_KEY]?.jsonPrimitive?.contentOrNull,
                    latestChapterAddedAt = currentManga.memo[LocalSource.LATEST_CHAPTER_TIME_KEY]
                        ?.jsonPrimitive
                        ?.longOrNull
                        ?: 0L,
                )
            }.filter { model ->
                val url = model.manga.url
                val readingMatch = when (filter) {
                    ReadingFilter.ALL -> true
                    ReadingFilter.UNREAD -> url !in filterCtx.finishedUrls
                    ReadingFilter.IN_PROGRESS -> url in filterCtx.startedUrls && url !in filterCtx.finishedUrls
                    ReadingFilter.FINISHED -> url in filterCtx.finishedUrls
                }
                val markMatch = when (markFilter) {
                    MarkFilter.NONE -> true
                    MarkFilter.FLAGGED -> url in filterCtx.flaggedUrls
                    MarkFilter.GOOD_DOUJIN -> url in filterCtx.goodDoujinUrls
                }
                readingMatch && markMatch
            }
            if (source is LocalSource && listing is Listing.Latest) {
                items.insertSeparators<BrowseSourceUiModel.Item, BrowseSourceUiModel> { before, after ->
                    val next = after ?: return@insertSeparators null
                    val startsNewDate = before == null ||
                        before.latestChapterAddedAt.toLocalDate() != next.latestChapterAddedAt.toLocalDate()
                    if (startsNewDate) BrowseSourceUiModel.Header(next.latestChapterAddedAt) else null
                }
            } else {
                items.map<BrowseSourceUiModel.Item, BrowseSourceUiModel> { it }
            }
        }
    }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyFlow(),
        )

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.landscapeColumns
        } else {
            libraryPreferences.portraitColumns
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    fun resetFilters() {
        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        if (source is LocalSource) {
            when (listing) {
                Listing.Popular -> source.resetOrderBy(popular = true)
                Listing.Latest -> source.resetOrderBy(popular = false)
                else -> {}
            }
        }
        mutableState.update {
            it.copy(
                listing = listing,
                toolbarQuery = null,
                filters = if (source is LocalSource) source.getFilterList() else it.filters,
            )
        }
    }

    fun setReadingFilter(filter: ReadingFilter) {
        readingFilterInternal.value = filter
        readingFilterPreference.set(filter.name)
    }

    private fun setMarkFilter(filter: MarkFilter) {
        markFilterInternal.value = filter
        markFilterPreference.set(filter.name)
    }

    fun setLocalListFilter(filter: MarkFilter) {
        if (source !is LocalSource) return
        setMarkFilter(filter)
        setListing(Listing.Popular)
    }

    fun showLocalLatest() {
        if (source !is LocalSource) return
        setMarkFilter(MarkFilter.NONE)
        setListing(Listing.Latest)
    }

    private fun matchesReadingFilter(filter: ReadingFilter, progress: MangaProgress): Boolean {
        return when (filter) {
            ReadingFilter.ALL -> true
            ReadingFilter.UNREAD -> !progress.hasFinished
            ReadingFilter.IN_PROGRESS -> progress.hasBeenRead && !progress.hasFinished
            ReadingFilter.FINISHED -> progress.hasFinished
        }
    }

    private fun matchesMarkFilter(filter: MarkFilter, url: String, context: ProgressContext): Boolean {
        return when (filter) {
            MarkFilter.NONE -> true
            MarkFilter.FLAGGED -> url in context.flaggedUrls
            MarkFilter.GOOD_DOUJIN -> url in context.goodDoujinUrls
        }
    }

    private fun ProgressContext.toCountContext(
        readingFilter: ReadingFilter,
        markFilter: MarkFilter,
    ): CountContext {
        val startedUrls = if (readingFilter == ReadingFilter.IN_PROGRESS) {
            progressByUrl.filterValues(MangaProgress::hasBeenRead).keys.toSet()
        } else {
            emptySet()
        }
        val finishedUrls = if (readingFilter == ReadingFilter.ALL) {
            emptySet()
        } else {
            progressByUrl.filterValues(MangaProgress::hasFinished).keys.toSet()
        }
        val markedUrls = when (markFilter) {
            MarkFilter.NONE -> null
            MarkFilter.FLAGGED -> flaggedUrls
            MarkFilter.GOOD_DOUJIN -> goodDoujinUrls
        }
        return CountContext(startedUrls, finishedUrls, markedUrls)
    }

    fun setFilters(filters: FilterList) {
        if (source is LocalSource) {
            source.persistOrderBySelection(filters)
        }
        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        val currentListing = state.value.listing
        val previousListing = if (currentListing is Listing.Search) {
            currentListing.previousListing
        } else {
            currentListing
        }
        val input = currentListing as? Listing.Search
            ?: Listing.Search(
                query = null,
                filters = source.getFilterList(),
                previousListing = previousListing,
            )

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun searchGenre(genreName: String) {
        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val previousListing = when (val current = it.listing) {
                is Listing.Search -> current.previousListing
                else -> current
            }
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters, previousListing = previousListing)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters, previousListing = previousListing)
            }
            it.copy(
                filters = defaultFilters,
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        viewModelScope.launch {
            // The paging item's `favorite` flag can be stale (the pager is not reloaded after a
            // toggle), so read the current state from the database before flipping it. Without
            // this, toggling twice in a row keeps writing the same value and the manga gets
            // stuck in (or out of) the library.
            val currentFavorite = runCatching { mangaRepository.getMangaById(manga.id).favorite }
                .getOrDefault(manga.favorite)
            var new = manga.copy(
                favorite = !currentFavorite,
                dateAdded = when (currentFavorite) {
                    true -> 0
                    false -> Clock.System.now().toEpochMilliseconds()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            if (updateManga.await(new.toMangaUpdate())) {
                favoriteIdsInternal.update { ids ->
                    if (new.favorite) ids + new.id else ids - new.id
                }
                if (hideInLibraryItems) {
                    invalidatePagingSources()
                }
            }
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launch {
            val categories = getCategories()
            val isLocal = source is LocalSource

            // For local sources, always show the category picker so long-press adding to the
            // library has visible feedback; other sources keep the original quick-add behavior.
            if (isLocal && categories.isNotEmpty()) {
                showChangeCategoryDialog(manga, categories)
                return@launch
            }

            val defaultCategoryId = libraryPreferences.defaultCategory.get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)

                    // Only the local source quick-add path has no visible dialog.
                    if (isLocal) {
                        events.send(Unit)
                    }
                }

                // Choose a category
                else -> showChangeCategoryDialog(manga, categories)
            }
        }
    }

    private suspend fun showChangeCategoryDialog(manga: Manga, categories: List<Category>) {
        val preselectedIds = getCategories.await(manga.id).map { it.id }
        setDialog(
            Dialog.ChangeMangaCategory(
                manga,
                categories.mapAsCheckboxState { it.id in preselectedIds },
            ),
        )
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): List<MangaWithChapterCount> {
        return getDuplicateLibraryManga.invoke(manga)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun clearReadingHistory() {
        viewModelScope.launchIO {
            val local = source as? LocalSource
            if (local == null) {
                historyRepository.resetHistoryBySourceId(sourceId)
                chapterRepository.setAllChaptersUnreadBySource(sourceId)
                return@launchIO
            }

            // Only clear the manga currently shown in this screen.
            val filteredUrls = currentFilteredMangaUrls(local)
            if (filteredUrls.isEmpty()) return@launchIO

            val mangaIds = filteredUrls.mapNotNull { url ->
                mangaRepository.getMangaByUrlAndSourceId(url, sourceId)?.id
            }
            mangaIds.chunked(CLEAR_HISTORY_BATCH_SIZE).forEach { ids ->
                historyRepository.resetHistoryByMangaIds(ids)
                chapterRepository.setAllChaptersUnreadByMangaIds(ids)
            }
        }
    }

    /** Emits a concise result after a local incremental refresh finishes. */
    val chapterRefreshEvents = Channel<ChapterRefreshResult>()

    /**
     * Incrementally syncs the chapter list of local manga that changed on disk into the database
     * (new folders, or folders whose mtime changed). Unchanged manga are skipped.
     */
    fun refreshAllChapters() {
        val local = source as? LocalSource ?: return
        if (isRefreshingChapters.value != null) return
        isRefreshingChapters.value = ChapterRefreshProgress.Checking(0, 0)
        viewModelScope.launchIO {
            try {
                val scan = local.scanChapterChanges(basePreferences.localSourceSyncMtime.get()) { completed, total ->
                    isRefreshingChapters.value = ChapterRefreshProgress.Checking(completed, total)
                }
                if (!scan.isReliable) {
                    localSourceChanged.value = true
                    chapterRefreshEvents.send(
                        ChapterRefreshResult(
                            changedManga = 0,
                            newChapters = 0,
                            storageUnavailable = true,
                        ),
                    )
                    return@launchIO
                }
                val unsynced = scan.changedMangaUrls
                val total = unsynced.size
                if (total > 0) {
                    isRefreshingChapters.value = ChapterRefreshProgress.Updating(0, total)
                }
                val semaphore = Semaphore(LOCAL_REFRESH_CONCURRENCY)
                val completed = AtomicInteger(0)
                val successful = ConcurrentHashMap.newKeySet<String>()
                val totalNew = if (total == 0) {
                    0
                } else {
                    coroutineScope {
                        unsynced.map { url ->
                            async {
                                try {
                                    semaphore.withPermit {
                                        val dbManga = mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
                                        if (dbManga == null) {
                                            successful += url
                                            0
                                        } else {
                                            val result = updateMangaFromRemote(
                                                source = local,
                                                manga = dbManga,
                                                fetchDetails = false,
                                                fetchChapters = true,
                                            )
                                            if (result.isSuccess) successful += url
                                            result.getOrNull()?.newChapters?.size ?: 0
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "Failed to refresh local manga: $url" }
                                    0
                                } finally {
                                    isRefreshingChapters.value = ChapterRefreshProgress.Updating(
                                        completed = completed.incrementAndGet(),
                                        total = total,
                                    )
                                }
                            }
                        }.awaitAll().sum()
                    }
                }
                local.markChaptersSynced(scan, successful)
                local.refreshListingCovers()
                invalidatePagingSources()
                val hasFailures = successful.size < total
                if (!hasFailures) {
                    basePreferences.localSourceSyncMtime.set(local.getBaseDirectoryLastModified())
                    local.getMangaDirectorySignature()?.let(basePreferences.localSourceDirectorySignature::set)
                }
                localSourceChanged.value = hasFailures
                chapterRefreshEvents.send(
                    ChapterRefreshResult(
                        changedManga = total,
                        newChapters = totalNew,
                    ),
                )
            } finally {
                isRefreshingChapters.value = null
                refreshVisibleSnapshots(forceDirectoryCheck = true)
            }
        }
    }

    /**
     * Returns the id of a random manga from the currently applied search/listing
     * and reading filter selection. Returns null for non-local sources or when
     * the filtered list is empty.
     */
    suspend fun getRandomLocalMangaId(): Long? {
        val local = source as? LocalSource ?: return null
        val filteredUrls = currentFilteredMangaUrls(local)
        if (filteredUrls.isEmpty()) return null

        // Resolve ids on the IO dispatcher: every lookup is a database query, so the
        // main thread must not be blocked while shuffling through the candidates.
        val ids = withIOContext {
            filteredUrls.mapNotNull { url ->
                mangaRepository.getMangaByUrlAndSourceId(url, sourceId)?.id
            }
        }
        return randomSelectionCooldown.pickManga(ids)
    }

    fun pickRandomMangaId(candidates: Collection<Long>): Long? {
        return randomSelectionCooldown.pickManga(candidates)
    }

    suspend fun getRandomGoodDoujinMangaId(): Long? {
        val markedMangaIds = goodDoujinStore.marks.value
            .map { it.mangaId }
            .distinct()
        return randomSelectionCooldown.pickManga(markedMangaIds)
    }

    /** Enumerates the manga URLs shown by the current listing and reading filter. */
    private suspend fun currentFilteredMangaUrls(local: LocalSource): List<String> {
        val listing = state.value.listing
        val urls = withIOContext {
            when (listing) {
                is Listing.Popular -> local.getPopularMangaUrls()
                is Listing.Latest -> local.getLatestMangaUrls()
                is Listing.Search -> local.getSearchMangaUrls(listing.query.orEmpty(), listing.filters)
            }
        }
        if (urls.isEmpty()) return emptyList()

        val context = progressContext.value
        return urls.filter { url ->
            val progress = context.progressByUrl[url]
                ?: context.fsChapterCounts[url]
                    ?.takeIf { it > 0 }
                    ?.let { MangaProgress(it, 0, 0, 0) }
                ?: MangaProgress.EMPTY
            matchesReadingFilter(readingFilterInternal.value, progress) &&
                matchesMarkFilter(markFilterInternal.value, url, context)
        }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    fun exitSearch() {
        mutableState.update {
            val listing = it.listing
            if (listing is Listing.Search) {
                it.copy(listing = listing.previousListing, toolbarQuery = null)
            } else {
                it.copy(toolbarQuery = null)
            }
        }
    }

    enum class ReadingFilter {
        ALL,
        IN_PROGRESS,
        UNREAD,
        FINISHED,
    }

    enum class MarkFilter {
        NONE,
        FLAGGED,
        GOOD_DOUJIN,
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(
            override val query: String?,
            override val filters: FilterList,
            val previousListing: Listing = Popular,
        ) : Listing(query = query, filters = filters)

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data object ClearHistory : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    @Immutable
    private data class FilterArgs(
        val listing: Listing,
        val context: ProgressContext,
        val readingFilter: ReadingFilter,
        val markFilter: MarkFilter,
    )

    @Immutable
    private data class CountFilterArgs(
        val listing: Listing,
        val readingFilter: ReadingFilter,
        val context: CountContext,
    )

    @Immutable
    private data class CountContext(
        val startedUrls: Set<String>,
        val finishedUrls: Set<String>,
        val markedUrls: Set<String>?,
    ) {
        fun matchesReadingFilter(filter: ReadingFilter, url: String): Boolean = when (filter) {
            ReadingFilter.ALL -> true
            ReadingFilter.UNREAD -> url !in finishedUrls
            ReadingFilter.IN_PROGRESS -> url in startedUrls && url !in finishedUrls
            ReadingFilter.FINISHED -> url in finishedUrls
        }

        fun matchesMarkFilter(url: String): Boolean = markedUrls?.contains(url) ?: true
    }

    @Immutable
    data class ProgressContext(
        val progressByMangaId: Map<Long, MangaProgress>,
        val progressByUrl: Map<String, MangaProgress>,
        val fsChapterCounts: Map<String, Long>,
        val lastReadMangaId: Long? = null,
        val flaggedUrls: Set<String> = emptySet(),
        val goodDoujinUrls: Set<String> = emptySet(),
    )

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}
