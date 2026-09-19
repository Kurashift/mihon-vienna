package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.Context
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
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.local.LocalEntryDeletionService
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdateStore
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.data.manga.RandomSelectionCooldown
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.manga.RandomGoodDoujinResult
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaProgress
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaProgress
import tachiyomi.domain.manga.model.MangaProgressByMangaId
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalListingSnapshot
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalChapterCoverManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseSourceViewModel(
    private val sourceId: Long,
    listingQuery: String?,
    private val context: Context = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val deletionService: LocalEntryDeletionService = Injekt.get(),
    private val getMangaProgress: GetMangaProgress = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddTracks = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val mangaMarkStore: MangaMarkStore = Injekt.get(),
    private val goodDoujinStore: GoodDoujinStore = Injekt.get(),
    getIncognitoState: GetIncognitoState = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val randomSelectionCooldown: RandomSelectionCooldown = Injekt.get(),
    val mangaCoverUpdateStore: MangaCoverUpdateStore = Injekt.get(),
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
     * Ids picked in the local library's selection mode.
     *
     * Selection exists so several works can be put on a shelf in one pass; the long press that
     * used to add a single work to the library now opens this instead.
     */
    private val selectionInternal = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = selectionInternal
    val selectionMode: StateFlow<Boolean> = selectionInternal
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Latest set of favorited manga ids for this source. The list UI subscribes to
     * this directly so adding/removing a favorite updates the cover and badges
     * immediately, without waiting for the pager to reload.
     */
    val favoriteIds: StateFlow<Set<Long>> = favoriteIdsInternal
    private var hasLoadedFavoriteSnapshot = false

    /**
     * Urls of the works of this source that are in the library.
     *
     * Only read when [hideInLibraryItems] is on: that is the only case where the toolbar count
     * has to exclude them, and the shelf addresses entries by url, so the count needs the same
     * key the pager filters on.
     */
    private val favoriteUrlsInternal = MutableStateFlow<Set<String>>(emptySet())

    init {
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

    /**
     * Urls of the local listing as it was when the pages currently on screen were built, null
     * before the first build.
     *
     * Compared against the live snapshot on return to decide whether the listing really lost or
     * gained an entry. A plain "something changed" flag is not enough: the listing re-publishes
     * an equivalent snapshot after every rebuild, and rebuilding then invalidates every loaded
     * page for a listing that did not actually change - the grid is served one page again and a
     * list scrolled to the middle visibly collapses and re-expands.
     */
    @Volatile
    private var servedListingUrls: Set<String>? = null
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

    /**
     * Ordering of the local library, null for any other source.
     *
     * The paged list reads it while mapping pages, so a sort change reloads the current listing
     * exactly once with the new order rather than emitting a stale page first. It is deliberately
     * not part of the pager's [combine]: those flows produce a brand new paging flow, which would
     * replay the cached page in the old order before the reload lands.
     */
    private val localSortInternal = MutableStateFlow((source as? LocalSource)?.orderBySelection)
    val localSort: StateFlow<SourceModelFilter.Sort.Selection?> = localSortInternal

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
            // Rebuild once on return rather than while this screen is covered, so the list is
            // never reloaded behind an open detail page.
            rebuildIfListingChanged()
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
        // Needed both to exclude in-library entries from the count when that setting is on, and
        // to drive the "not in library" filter, so the local source always reads it.
        val favoriteUrls = if (hideInLibraryItems || source is LocalSource) {
            async { mangaRepository.getFavoriteUrlsBySourceId(sourceId).toHashSet() }
        } else {
            null
        }
        val local = source as? LocalSource
        val directory = local?.let {
            async { refreshLocalDirectorySnapshot(it, forceDirectoryCheck) }
        }

        progressSnapshot.value = progress.await()
        val favoriteIds = favorites.await()
        val favoritesChanged = hasLoadedFavoriteSnapshot && favoriteIdsInternal.value != favoriteIds
        favoriteIdsInternal.value = favoriteIds
        hasLoadedFavoriteSnapshot = true
        favoriteUrls?.await()?.let { favoriteUrlsInternal.value = it }
        // A favorite that just changed also changes what the "not in library" filter matches, so
        // the loaded pages have to be rebuilt for it as well.
        if (favoritesChanged &&
            (hideInLibraryItems || markFilterInternal.value == MarkFilter.NOT_IN_LIBRARY)
        ) {
            invalidatePagingSources()
        }
        directory?.await()
    }

    private suspend fun refreshLocalDirectorySnapshot(local: LocalSource, force: Boolean) {
        // Ordinary tab entry only needs the last confirmed listing. Walking the tree here
        // (find -maxdepth 2) stalls the shelf and can invalidate pages on a partial SAF
        // read. Confirmed scans stay on pull-to-refresh / refresh-all-chapters.
        if (!force) {
            if (localChapterCounts.value.isEmpty()) {
                localChapterCounts.value = local.getChapterCounts()
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (observedLocalDirectorySignature != null &&
            now - lastLocalDirectoryCheckAt < LOCAL_DIRECTORY_POLL_MILLIS
        ) {
            return
        }
        lastLocalDirectoryCheckAt = now

        var directorySnapshot = local.getMangaDirectorySnapshot() ?: return
        var signature = directorySnapshot.signature
        val previous = observedLocalDirectorySignature
        if (previous == signature && localChapterCounts.value.isNotEmpty()) return

        val committed = basePreferences.localSourceDirectorySignature.get()
        val listingUrls = local.listingSnapshot.value.allUrls.toSet()
        val matchesListing = listingUrls.isNotEmpty() && directorySnapshot.urls == listingUrls
        val differsFromBaseline = when {
            matchesListing || directorySnapshot.fromListingFallback -> false
            previous != null -> signature != previous
            committed.isNotBlank() -> signature != committed
            else -> false
        }

        if (differsFromBaseline) {
            if (!localDirectoryChangeCanApplyImmediately(directorySnapshot.urls, listingUrls)) {
                // A partial provider result can repeat identically. Confirm every missing folder
                // before letting it invalidate the last known-good shelf snapshot.
                directorySnapshot = local.getConfirmedMangaDirectorySnapshot() ?: return
                signature = directorySnapshot.signature
            }
            val currentListingUrls = local.listingSnapshot.value.allUrls.toSet()
            if (currentListingUrls.isEmpty() || directorySnapshot.urls != currentListingUrls) {
                local.invalidateListing()
                invalidatePagingSources()
                localSourceChanged.value = true
            } else {
                basePreferences.localSourceDirectorySignature.set(signature)
            }
        } else if (committed.isBlank() || matchesListing) {
            basePreferences.localSourceDirectorySignature.set(signature)
        }

        if (!differsFromBaseline || localSourceChanged.value) {
            observedLocalDirectorySignature = signature
        }
        localChapterCounts.value = local.getChapterCounts()
    }

    /**
     * Database id -> url for every work that currently carries a mark, read back from the
     * database.
     *
     * Marks are stored against the database id while the shelf is addressed by url, so a mark
     * only reaches an entry through a translation. Taking that translation from
     * [progressSnapshot] makes every mark depend on when that snapshot happened to be read: a
     * work whose row moved, appeared or was renamed after the read translates either to nothing
     * or to the url it used to have, and then quietly drops out of the mark filters while still
     * being listed with no filter at all. Reading the ids back removes that dependency on
     * timing — the answer is whatever the database says right now, which is exactly what the
     * listing is built from.
     *
     * [progressSnapshot] is only a trigger here: it is the moment a visit refreshes its data,
     * which is also the moment a moved work would otherwise keep resolving to its old url.
     */
    private val markUrlById: StateFlow<Map<Long, String>> = combine(
        mangaMarkStore.marks,
        goodDoujinStore.marks,
        progressSnapshot,
    ) { marks, doujins, _ ->
        val ids = HashSet<Long>(marks.size + doujins.size)
        marks.mapTo(ids) { it.mangaId }
        doujins.mapTo(ids) { it.mangaId }
        ids
    }
        .mapLatest { ids ->
            if (ids.isEmpty()) emptyMap() else mangaRepository.getMangaUrlsByIds(ids)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val progressContext: StateFlow<ProgressContext> = combine(
        progressSnapshot,
        localChapterCounts,
        mangaMarkStore.marks,
        goodDoujinStore.marks,
        markUrlById,
    ) { progressList, fsChapterCounts, duplicateMarks, goodDoujinMarks, markUrls ->
        // The database answers first; the snapshot built here only covers the window before that
        // read lands, so a mark never resolves to nothing while it is still in flight.
        val snapshotUrlByMangaId = progressList.associate { it.mangaId to it.url }
        val resolveUrl = { mangaId: Long -> markUrls[mangaId] ?: snapshotUrlByMangaId[mangaId] }
        val flaggedUrls = duplicateMarks.mapNotNullTo(HashSet()) { resolveUrl(it.mangaId) }
        val goodDoujinUrls = goodDoujinMarks.mapNotNullTo(HashSet()) { resolveUrl(it.mangaId) }

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
        .distinctUntilChanged()
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
     * url -> manga id for every manga that still has chapters.
     *
     * [distinctUntilChanged] is what keeps this cheap: reading a chapter rewrites the progress
     * rows without changing this mapping, and without it every single read would re-filter the
     * whole random pool for nothing.
     */
    private val mangaIdByUrl: StateFlow<Map<String, Long>> = progressSnapshot
        .map { list -> list.associate { it.url to it.mangaId } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val listingAndSnapshot: Flow<Pair<Listing, LocalListingSnapshot?>> = combine(
        state.map { it.listing }.distinctUntilChanged(),
        (source as? LocalSource)?.listingSnapshot ?: flowOf(null),
    ) { listing, snapshot -> listing to snapshot }

    @Immutable
    private data class RandomPoolArgs(
        val listing: Listing,
        val snapshotUrls: List<String>?,
        val context: ProgressContext,
        val readingFilter: ReadingFilter,
        val markFilter: MarkFilter,
        val favoriteUrls: Set<String>,
        val idByUrl: Map<String, Long>,
    )

    /**
     * Ids of every manga the current listing shows after the reading and mark filters: the whole
     * result set, not just the pages the pager has already loaded.
     *
     * The details screen's random button walks this. It used to be handed only the loaded page,
     * so the button kept offering the same first PAGE_SIZE entries however far the user had
     * scrolled and however many entries the filter actually matched.
     */
    val filteredMangaIds: StateFlow<List<Long>> = combine(
        listingAndSnapshot,
        progressContext,
        // Merged so the combine below keeps the typed overload: its arity limit is five, and
        // going one over leaves every lambda parameter inferred as Any.
        combine(readingFilterInternal, markFilterInternal) { readingFilter, markFilter ->
            readingFilter to markFilter
        },
        favoriteUrlsInternal,
        mangaIdByUrl,
    ) { (listing, snapshot), context, (readingFilter, markFilter), favoriteUrls, idByUrl ->
        RandomPoolArgs(
            listing = listing,
            snapshotUrls = when (listing) {
                is Listing.Popular -> snapshot?.allUrls
                is Listing.Latest -> snapshot?.latestUrls
                is Listing.Search -> null
            },
            context = context,
            readingFilter = readingFilter,
            markFilter = markFilter,
            favoriteUrls = favoriteUrls,
            idByUrl = idByUrl,
        )
    }
        .distinctUntilChanged()
        .mapLatest { args ->
            // Resolving and filtering a library of thousands of urls must not block the main
            // thread, so the whole pass runs on IO.
            withIOContext<List<Long>> {
                val local = source as? LocalSource ?: return@withIOContext emptyList()
                if (args.idByUrl.isEmpty()) return@withIOContext emptyList()
                val urls = args.snapshotUrls ?: run {
                    val listing = args.listing as Listing.Search
                    local.getSearchMangaUrls(listing.query.orEmpty())
                }
                urls.mapNotNull { url ->
                    if (!matchesListingFilters(
                            url = url,
                            context = args.context,
                            readingFilter = args.readingFilter,
                            markFilter = args.markFilter,
                            favoriteUrls = args.favoriteUrls,
                        )
                    ) {
                        return@mapNotNull null
                    }
                    args.idByUrl[url]
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        val favoriteUrls: Set<String> = emptySet(),
        val finishedUrls: Set<String> = emptySet(),
        val startedUrls: Set<String> = emptySet(),
    )

    private val filterContext: StateFlow<FilterContext> = combine(
        progressContext,
        readingFilterInternal,
        markFilterInternal,
        favoriteUrlsInternal,
    ) { context, readingFilter, markFilter, favoriteUrls ->
        FilterContext(
            flaggedUrls = if (markFilter == MarkFilter.FLAGGED) context.flaggedUrls else emptySet(),
            goodDoujinUrls = if (markFilter == MarkFilter.GOOD_DOUJIN) context.goodDoujinUrls else emptySet(),
            favoriteUrls = if (markFilter == MarkFilter.NOT_IN_LIBRARY) favoriteUrls else emptySet(),
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
     * URLs the local listing is narrowed to for the active reading and mark filters, null when
     * neither filter narrows it.
     *
     * Pushed into [LocalSource] so the pager walks the filtered sequence rather than the whole
     * library. Without it, a selective filter (two marked entries among hundreds) can only be
     * rediscovered by walking every page, and every reload — most visibly a sort change — drops
     * the loaded window back to the first page, so the grid goes empty and spins until the walk
     * reaches the matches.
     *
     * An empty set means the marks have not been read yet or there are none; paging then stays
     * on the full listing, exactly as before, and the list-side filter still hides non-matches.
     */
    private val listingUrlFilter: StateFlow<Set<String>?> = if (source is LocalSource) {
        combine(
            filterContext,
            readingFilterInternal,
            markFilterInternal,
            source.listingSnapshot,
        ) { context, readingFilter, markFilter, snapshot ->
            val markUrls = when (markFilter) {
                MarkFilter.NONE -> null
                MarkFilter.FLAGGED -> context.flaggedUrls
                MarkFilter.GOOD_DOUJIN -> context.goodDoujinUrls
                // Every url the library does not hold. Built from the snapshot rather than left
                // null so the pager walks only the matches instead of the whole library.
                MarkFilter.NOT_IN_LIBRARY -> snapshot.allUrls.filterNotTo(HashSet()) { it in context.favoriteUrls }
            }
            val readingUrls = when (readingFilter) {
                ReadingFilter.ALL -> null
                ReadingFilter.UNREAD -> snapshot.allUrls.filterNotTo(HashSet()) { it in context.finishedUrls }
                ReadingFilter.IN_PROGRESS -> context.startedUrls - context.finishedUrls
                ReadingFilter.FINISHED -> context.finishedUrls
            }
            when {
                markUrls == null && readingUrls == null -> null
                markUrls == null -> readingUrls?.takeIf { it.isNotEmpty() }
                readingUrls == null -> markUrls.takeIf { it.isNotEmpty() }
                else -> markUrls.intersect(readingUrls).takeIf { it.isNotEmpty() }
            }
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    } else {
        MutableStateFlow(null)
    }

    /**
     * Number of manga entries in the currently displayed list, after applying the
     * active listing (popular/latest/search) and the reading filter. Shown next to
     * the source name in the toolbar so it stays in sync with the filter chips.
     */
    val currentViewMangaCount: StateFlow<Int> = if (source is LocalSource) {
        val listingWithSnapshot = combine(
            state.map { it.listing }.distinctUntilChanged(),
            source.listingSnapshot,
        ) { listing, snapshot -> listing to snapshot }
        // The two filters are merged so the combine below keeps the typed overload: its arity
        // limit is five, and going one over leaves every lambda parameter inferred as Any.
        val filters = combine(
            readingFilterInternal,
            markFilterInternal,
        ) { readingFilter, markFilter -> readingFilter to markFilter }
        combine(
            listingWithSnapshot,
            progressContext,
            filters,
            favoriteUrlsInternal,
            screenVisible,
        ) { (listing, snapshot), context, (filter, markFilter), favoriteUrls, visible ->
            if (visible) {
                CountFilterArgs(
                    listing = listing,
                    listingUrls = when (listing) {
                        Listing.Popular -> snapshot.allUrls
                        Listing.Latest -> snapshot.latestUrls
                        is Listing.Search -> null
                    },
                    readingFilter = filter,
                    markFilter = markFilter,
                    context = context.toCountContext(filter, markFilter, favoriteUrls),
                    favoriteUrls = favoriteUrls,
                )
            } else {
                null
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .mapLatest { args ->
                val urls = args.listingUrls ?: withIOContext {
                    val listing = args.listing as Listing.Search
                    source.getSearchMangaUrls(listing.query.orEmpty())
                }
                urls.count { url ->
                    // Same rule the pager applies to every loaded item, so the number next to
                    // the source name stays the count of what is actually on the shelf.
                    if (hideInLibraryItems && url in args.favoriteUrls) {
                        false
                    } else {
                        args.context.matchesReadingFilter(args.readingFilter, url) &&
                            args.context.matchesMarkFilter(args.markFilter, url)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    } else {
        MutableStateFlow(0)
    }

    private val pagerCache = ConcurrentHashMap<PagerCacheKey, Flow<PagingData<Manga>>>()

    private val pagingSources = ConcurrentHashMap.newKeySet<SourcePagingSource>()

    /**
     * Identity of a non-search pager: the listing plus the placeholders decision it was built
     * with. That decision is read at build time, so it is part of the cache key: toggling a
     * narrowing filter has to swap in a pager built with the matching configuration.
     */
    private data class PagerCacheKey(val listing: Listing, val placeholdersEnabled: Boolean)

    /**
     * Whether the paging pipeline applies a client-side filter that can drop items from loaded
     * pages: the reading filter, the mark filter, or "hide in-library items". Placeholder slots
     * are sized by the raw listing total, so while such a filter is active they can never be
     * filled with matching items: after every listing reload (most visibly a sort change) the
     * grid would flash gray placeholder cards sized by the unfiltered library, which then only
     * disappear as the pager crawls through every remaining page. While a filter narrows the
     * list, placeholders are therefore turned off and the presented list is exactly the
     * filtered result.
     */
    private fun clientFilterNarrows(readingFilter: ReadingFilter, markFilter: MarkFilter): Boolean {
        return hideInLibraryItems ||
            readingFilter != ReadingFilter.ALL ||
            markFilter != MarkFilter.NONE
    }

    /**
     * Invalidates every paging source created so far (current listing, cached Popular/Latest
     * and any open search) so they reload with fresh data, e.g. after the local directory
     * changed or a manga was added to/removed from the library.
     */
    private fun invalidatePagingSources() {
        // Pager instances for cached listings are created once, so keep their sources registered
        // for later directory, favorite-filter, and manual-refresh invalidations.
        pagingSources.forEach { it.invalidate() }
        // The pages served from now on reflect the listing as it stands at this moment, so this
        // is what any later comparison has to measure against.
        currentListingUrls()?.let { servedListingUrls = it }
    }

    /** Urls of the local listing right now, null for any other source or before the first scan. */
    private fun currentListingUrls(): Set<String>? {
        val local = source as? LocalSource ?: return null
        return local.listingSnapshot.value.allUrls.toSet().takeIf { it.isNotEmpty() }
    }

    /**
     * Rebuilds the loaded pages only when the local listing really gained or lost an entry since
     * the pages currently on screen were built.
     *
     * Deleting every chapter of a work takes its directory and its database row with it and the
     * listing drops the entry immediately, while the pages already served still hold that card -
     * without a rebuild it lingers as a blank entry until the next rescan. The rebuild is deferred
     * to the moment this screen is shown again so the list is never reloaded behind an open detail
     * page.
     */
    private fun rebuildIfListingChanged() {
        val currentUrls = currentListingUrls() ?: return
        val served = servedListingUrls
        servedListingUrls = currentUrls
        // Nothing has been served yet, so there is nothing to rebuild - the first pages are being
        // built from this very listing.
        if (served != null && served != currentUrls) {
            invalidatePagingSources()
        }
    }

    private val pageSize = if (source is LocalSource) LocalSource.PAGE_SIZE else 25

    /**
     * Whether the pager fills unloaded positions with placeholders, which fixes the presented
     * list length at the full result count. Off while a client-side filter can drop items out
     * of loaded pages, because the slots are then sized by the unfiltered listing and never
     * fill - see [clientFilterNarrows].
     */
    private val pagingPlaceholdersEnabled: StateFlow<Boolean> = combine(
        readingFilterInternal,
        markFilterInternal,
    ) { readingFilter, markFilter -> !clientFilterNarrows(readingFilter, markFilter) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            !clientFilterNarrows(readingFilterInternal.value, markFilterInternal.value),
        )

    /**
     * Skeleton slots the listing keeps past the last loaded page so the scroller can be
     * dragged from one end of the list to the other in a single gesture.
     *
     * With placeholders on, the pager already reports the full count and the list is that long
     * from the start, so nothing has to be kept. With them off, the presented list is only
     * ever as long as the pages loaded so far, and the thumb stops dead at the last loaded
     * entry until the finger lets go and the next page arrives.
     *
     * Deliberately one page, not the whole remaining listing: reading one of these slots is
     * what pulls the next page in, and a page-sized run is exactly the range Paging treats as
     * "the next page". Sizing it by the unfiltered total instead is the behaviour that used to
     * flash a screenful of grey cards after every listing reload.
     */
    val trailingSlotCount: StateFlow<Int> = pagingPlaceholdersEnabled
        .map { enabled -> if (enabled) 0 else pageSize }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private fun buildPager(listing: Listing, placeholdersEnabled: Boolean): Flow<PagingData<Manga>> {
        return Pager(
            PagingConfig(
                pageSize = pageSize,
                initialLoadSize = pageSize,
                enablePlaceholders = source is LocalSource && placeholdersEnabled,
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

    private fun pagerFor(listing: Listing, placeholdersEnabled: Boolean): Flow<PagingData<Manga>> {
        return if (listing is Listing.Search) {
            buildPager(listing, placeholdersEnabled)
        } else {
            pagerCache.getOrPut(PagerCacheKey(listing, placeholdersEnabled)) {
                buildPager(listing, placeholdersEnabled)
            }
        }
    }

    init {
        // A page is a slice of the narrowed listing once the mark filter is pushed down, so
        // changing it invalidates the pages already on screen: an entry marked after the first
        // load would otherwise stay invisible until some unrelated reload rebuilt them. The
        // first value only seeds the source, which has not served a page yet.
        viewModelScope.launchIO {
            var seeded = false
            listingUrlFilter.collect { urls ->
                val local = source as? LocalSource ?: return@collect
                local.setListingUrlFilter(urls)
                if (seeded) {
                    invalidatePagingSources()
                }
                seeded = true
            }
        }

        // Deleting every chapter of a work takes its directory and its database row with it, and
        // the listing drops the entry immediately. The pages already served still hold that card,
        // so the rebuild is deferred to the moment this screen is shown again: a deleted work
        // then disappears on return instead of lingering as a blank card until the next rescan.
        // That comparison lives in [rebuildIfListingChanged], not in a collector here - watching
        // the snapshot continuously is what made an unchanged listing rebuild itself.
    }

    val mangaPagerFlowFlow = combine(
        // The pager is selected by listing AND by whether a narrowing filter is active: a filter
        // toggle swaps in a pager built with matching placeholders, so the presented list never
        // carries phantom slots sized by the unfiltered listing. Sort is deliberately absent
        // (see [localSortInternal]). Cover overlays stay off this combine: publishing one cover
        // used to allocate a new inner Flow and reconnect LazyPagingItems.
        combine(
            state.map { it.listing }.distinctUntilChanged(),
            readingFilterInternal,
            markFilterInternal,
        ) { listing, readingFilter, markFilter ->
            PagerCacheKey(listing, !clientFilterNarrows(readingFilter, markFilter))
        }
            .map { key -> key.listing to pagerFor(key.listing, key.placeholdersEnabled) },
        filterContext,
        readingFilterInternal,
        markFilterInternal,
    ) { (listing, pagerFlow), filterCtx, filter, markFilter ->
        pagerFlow.map { pagingData ->
            val items = pagingData.map { manga ->
                BrowseSourceUiModel.Item(
                    manga = manga,
                    matchedChapter = manga.memo[LocalSource.MATCHED_CHAPTER_KEY]?.jsonPrimitive?.contentOrNull,
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
                    MarkFilter.NOT_IN_LIBRARY -> url !in filterCtx.favoriteUrls
                }
                readingMatch && markMatch
            }
            // Date headers group runs of consecutive entries, so they only read as sections while
            // the list really is ordered by date. Under any other sort the dates jump around and
            // every entry would open its own header. The "recently updated" listing is excluded
            // the same way: it orders by the chapters' recency, not by the import date.
            //
            // The ordering is what decides this, not which listing is shown: the separators used
            // to be tied to the "recent" listing, which meant picking the date sort on its own
            // showed no dates at all. The buckets follow the sort's own notion of the date - the
            // import date - so a heading names when the works under it entered the library.
            // Recent days stay separate and read relatively; older ones collapse into their month
            // so a library spanning years does not become all headings.
            val orderByIndex = localSortInternal.value?.index
            if (source is LocalSource &&
                orderByIndex == LocalSource.ORDER_BY_DATE &&
                listing !is Listing.Latest
            ) {
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                items.insertSeparators<BrowseSourceUiModel.Item, BrowseSourceUiModel> { before, after ->
                    val next = after ?: return@insertSeparators null
                    val nextBucket = dateHeaderBucket(next.manga.dateAdded, today)
                    val beforeBucket = before?.let { dateHeaderBucket(it.manga.dateAdded, today) }
                    if (nextBucket != beforeBucket) BrowseSourceUiModel.Header(nextBucket) else null
                }
            } else {
                items.map<BrowseSourceUiModel.Item, BrowseSourceUiModel> { it }
            }
        }
    }
        .distinctUntilChanged { old, new -> old === new }
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
        mutableState.update {
            it.copy(
                listing = listing,
                toolbarQuery = null,
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

    /**
     * Orders the local library by [index], keeping the current direction. Re-picking the key
     * already in use changes nothing, so it does not reload the list either.
     */
    fun setLocalSortKey(index: Int) {
        val local = source as? LocalSource ?: return
        val current = localSortInternal.value ?: return
        if (current.index == index) return
        applyLocalSort(local, index, current.ascending)
    }

    /** Flips the local library's ordering between ascending and descending. */
    fun toggleLocalSortDirection() {
        val local = source as? LocalSource ?: return
        val current = localSortInternal.value ?: return
        applyLocalSort(local, current.index, !current.ascending)
    }

    /**
     * Persists the new ordering and reloads the pages that are already on screen. Writing the
     * preference before invalidating is what makes the reload pick the new order up; the derived
     * listing cache keys on both fields, so it recomputes instead of serving the old sequence.
     */
    private fun applyLocalSort(local: LocalSource, index: Int, ascending: Boolean) {
        local.setOrderBy(index, ascending)
        localSortInternal.value = local.orderBySelection
        invalidatePagingSources()
    }

    private fun matchesReadingFilter(filter: ReadingFilter, progress: MangaProgress): Boolean {
        return LocalReadingFilter.matches(filter, progress)
    }

    private fun matchesMarkFilter(filter: MarkFilter, url: String, context: ProgressContext): Boolean {
        return when (filter) {
            MarkFilter.NONE -> true
            MarkFilter.FLAGGED -> url in context.flaggedUrls
            MarkFilter.GOOD_DOUJIN -> url in context.goodDoujinUrls
            MarkFilter.NOT_IN_LIBRARY -> true
        }
    }

    private fun ProgressContext.toCountContext(
        readingFilter: ReadingFilter,
        markFilter: MarkFilter,
        favoriteUrls: Set<String>,
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
            MarkFilter.NOT_IN_LIBRARY -> null
        }
        return CountContext(startedUrls, finishedUrls, markedUrls, favoriteUrls)
    }

    fun setFilters(filters: FilterList) {
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
                favoriteUrlsInternal.update { urls ->
                    if (new.favorite) urls + new.url else urls - new.url
                }
                // Shelf membership belongs to being in the library: rows left behind would
                // resurface as pre-checked shelves the next time the work is added again.
                if (!new.favorite) {
                    setMangaCategories.await(new.id, emptyList())
                }
                if (hideInLibraryItems || markFilterInternal.value == MarkFilter.NOT_IN_LIBRARY) {
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
        // The pager item's favorite flag can be stale (see [changeMangaFavorite]), so read the
        // state from the database: a work being added holds no shelves, whatever an older
        // build's unfavorite left behind in the shelf rows.
        val isFavorited = runCatching { mangaRepository.getMangaByIdOrNull(manga.id)?.favorite }
            .getOrNull() ?: manga.favorite
        val preselectedIds = if (isFavorited) getCategories.await(manga.id).map { it.id } else emptyList()
        setDialog(
            Dialog.ChangeMangaCategory(
                manga,
                categories.mapAsCheckboxState { it.id in preselectedIds },
            ),
        )
    }

    fun toggleSelection(mangaId: Long) {
        selectionInternal.update { ids ->
            if (mangaId in ids) ids - mangaId else ids + mangaId
        }
    }

    fun toggleRangeSelection(mangaId: Long, mangaList: List<Manga>) {
        viewModelScope.launchIO {
            val ids = fullListingMangaIds() ?: mangaList.map { it.id }
            val lastSelected = selectionInternal.value.lastOrNull { it in ids } ?: return@launchIO
            val lastIndex = ids.indexOf(lastSelected)
            val currentIndex = ids.indexOf(mangaId)
            if (lastIndex == -1 || currentIndex == -1) return@launchIO
            val range = if (lastIndex < currentIndex) {
                ids.subList(lastIndex, currentIndex + 1)
            } else {
                ids.subList(currentIndex, lastIndex + 1)
            }
            selectionInternal.update { it + range }
        }
    }

    fun selectAll(mangaList: List<Manga>) {
        viewModelScope.launchIO {
            val ids = fullListingMangaIds() ?: mangaList.map { it.id }
            selectionInternal.value = ids.toHashSet()
        }
    }

    fun invertSelection(mangaList: List<Manga>) {
        viewModelScope.launchIO {
            val ids = (fullListingMangaIds() ?: mangaList.map { it.id }).toHashSet()
            selectionInternal.update { current -> (ids - current) + (current - ids) }
        }
    }

    /**
     * Ids of every manga the current listing shows once the reading and mark filters are applied,
     * in the order the list presents them — the whole result set, not just the pages the pager has
     * loaded so far.
     *
     * Selection built on the loaded pages alone caps select-all at a couple of pages' worth and
     * only reaches the real total after the user has scrolled the whole list once. The local
     * source can enumerate its full listing from the directory index, so selection runs on that;
     * a remote source has no such set short of walking every network page, so it returns null and
     * the callers keep the loaded pages as the selection universe.
     */
    private suspend fun fullListingMangaIds(): List<Long>? {
        val local = source as? LocalSource ?: return null
        val idByUrl = mangaIdByUrl.value
        if (idByUrl.isEmpty()) return emptyList()
        val favoriteUrls = favoriteUrlsInternal.value
        return currentFilteredMangaUrls(local).mapNotNull { url ->
            // Same rule the pager applies to every loaded item, so what gets picked matches what
            // the list and the toolbar count show.
            if (hideInLibraryItems && url in favoriteUrls) null else idByUrl[url]
        }
    }

    fun clearSelection() {
        selectionInternal.value = emptySet()
    }

    /**
     * Categories offered when setting the shelf of the picked works, plus the default shelf.
     *
     * The default shelf is only offered here: it is the absence of a category row, so the caller
     * that knows how to express that (the local library) asks for it explicitly, while the
     * upstream screens keep their existing behaviour.
     */
    suspend fun getSelectableCategories(includeDefault: Boolean): List<Category> {
        val categories = getCategories.await()
        return if (includeDefault) categories else categories.filterNot { it.isSystemCategory }
    }

    /** Opens the shelf picker for everything currently selected. */
    fun openChangeCategoryDialogForSelection() {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        viewModelScope.launchIO {
            // A single work keeps the duplicate guard the long press used to run: shelving a
            // second copy of something already in the library is worth asking about. Asking once
            // per work in a batch would just be noise.
            if (selected.size == 1) {
                val manga = runCatching { mangaRepository.getMangaByIdOrNull(selected.first()) }
                    .getOrNull()
                if (manga != null) {
                    val duplicates = getDuplicateLibraryManga(manga)
                    if (duplicates.isNotEmpty()) {
                        setDialog(Dialog.AddDuplicateManga(manga, duplicates))
                        return@launchIO
                    }
                }
            }

            val categories = getSelectableCategories(includeDefault = source is LocalSource)
            val perManga = selected.associateWith { mangaId ->
                val manga = runCatching { mangaRepository.getMangaByIdOrNull(mangaId) }.getOrNull()
                // A work that is not in the library holds no shelves. Rows left behind by an
                // older build's unfavorite must not come back here as pre-checked picks.
                if (manga?.favorite == true) {
                    getCategories.await(mangaId).map { it.id }.toSet()
                } else {
                    emptySet()
                }
            }
            // Every category the selection already shares is checked; the ones only some of them
            // hold show as mixed, exactly like the upstream library's batch picker.
            val initialSelection = categories.map { category ->
                val holders = if (category.isSystemCategory) {
                    // The default shelf holds exactly the works with no category row at all, so
                    // it is not "the works holding category 0" - that id is never in the table.
                    perManga.values.count { it.isEmpty() }
                } else {
                    perManga.values.count { category.id in it }
                }
                when (holders) {
                    0 -> CheckboxState.State.None(category)
                    perManga.size -> CheckboxState.State.Checked(category)
                    else -> CheckboxState.TriState.Exclude(category)
                }
            }
            setDialog(Dialog.ChangeSelectionCategory(initialSelection))
        }
    }

    /** Asks for confirmation before the selected directories are erased. */
    fun requestDeleteSelectedManga() {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        viewModelScope.launchIO {
            val titles = selected.mapNotNull { mangaId ->
                runCatching { mangaRepository.getMangaByIdOrNull(mangaId)?.title }.getOrNull()
            }
            setDialog(Dialog.DeleteSelection(titles))
        }
    }

    /**
     * Applies the picked shelf to every selected work at once.
     *
     * [include] are the shelves that should hold them and [exclude] the ones that should not, the
     * same pair the upstream library's picker produces. Picking a shelf also puts the work on the
     * shelf itself: the entries that were not in the library yet have to become favorites, or the
     * choice would have no effect.
     */
    fun setSelectedMangaCategories(include: List<Long>, exclude: List<Long>) {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        // The default shelf is the absence of a row, so its id is a signal rather than something
        // to write; storing it would name a category that does not exist.
        val writable = include.filter { it != Category.UNCATEGORIZED_ID }
        viewModelScope.launchNonCancellable {
            val selectedUrls = mutableSetOf<String>()
            selected.forEach { mangaId ->
                val manga = runCatching { mangaRepository.getMangaByIdOrNull(mangaId) }.getOrNull()
                    ?: return@forEach
                selectedUrls += manga.url
                val categoryIds = if (manga.favorite) {
                    getCategories.await(mangaId)
                        .map { it.id }
                        .subtract(exclude.toSet())
                        .plus(writable)
                        .toList()
                } else {
                    // The work is being added fresh: what the user picked is all it holds, so
                    // rows left by an older build's unfavorite are dropped instead of merged in.
                    writable
                }
                setMangaCategories.await(mangaId, categoryIds)
                if (!manga.favorite) {
                    updateManga.await(
                        manga.copy(
                            favorite = true,
                            dateAdded = Clock.System.now().toEpochMilliseconds(),
                        ).toMangaUpdate(),
                    )
                }
            }
            favoriteIdsInternal.update { it + selected }
            favoriteUrlsInternal.update { it + selectedUrls }
            invalidatePagingSources()
            clearSelection()
        }
    }

    /** Takes the selected works off the shelf without touching their files. */
    fun removeSelectedFromLibrary() {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        viewModelScope.launchNonCancellable {
            val removedUrls = mutableSetOf<String>()
            selected.forEach { mangaId ->
                val manga = runCatching { mangaRepository.getMangaByIdOrNull(mangaId) }.getOrNull()
                    ?: return@forEach
                removedUrls += manga.url
                updateManga.await(
                    manga.copy(favorite = false, dateAdded = 0).removeCovers(coverCache).toMangaUpdate(),
                )
                // Same rule as the single toggle: leaving the library drops the shelf rows, so
                // re-adding later does not resurrect the old shelves as pre-checked picks.
                setMangaCategories.await(mangaId, emptyList())
            }
            favoriteIdsInternal.update { it - selected }
            favoriteUrlsInternal.update { it - removedUrls }
            invalidatePagingSources()
            clearSelection()
        }
    }

    /**
     * Asks before flipping the read status of a selection.
     *
     * A batch marks every chapter of every picked work, so a stray tap can move thousands of
     * chapters at once. The count is what makes the size of that visible before it happens.
     */
    fun requestMarkSelectedRead(read: Boolean) {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        setDialog(Dialog.MarkSelectionRead(read = read, count = selected.size))
    }

    fun markSelectedRead(read: Boolean) {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        viewModelScope.launchNonCancellable {
            selected.forEach { mangaId ->
                val manga = runCatching { mangaRepository.getMangaByIdOrNull(mangaId) }.getOrNull()
                    ?: return@forEach
                setReadStatus.await(manga = manga, read = read)
            }
            clearSelection()
        }
    }

    /** Deletes the selected works' directories one by one, reporting whatever could not go. */
    fun deleteSelectedLocalManga() {
        val selected = selectionInternal.value
        if (selected.isEmpty()) return
        viewModelScope.launchNonCancellable {
            val failed = mutableListOf<String>()
            var deleted = 0
            selected.forEach { mangaId ->
                val manga = runCatching { mangaRepository.getMangaByIdOrNull(mangaId) }.getOrNull()
                    ?: return@forEach
                val result = deletionService.deleteManga(
                    LocalEntryDeletionService.MangaEntry(
                        id = manga.id,
                        url = manga.url,
                        title = manga.title,
                        manga = manga,
                    ),
                )
                deleted += result.deleted
                failed += result.failed
            }
            favoriteIdsInternal.update { it - selected }
            clearSelection()
            _deleteCompleted.emit(LocalDeleteCompleted(deleted = deleted, failed = failed))
        }
    }

    data class LocalDeleteCompleted(val deleted: Int, val failed: List<String>)

    private val _deleteCompleted = MutableSharedFlow<LocalDeleteCompleted>(extraBufferCapacity = 1)
    val deleteCompleted: Flow<LocalDeleteCompleted> = _deleteCompleted.asSharedFlow()

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
                relocateMovedLocalChapters(scan)
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
                val listingRefreshed = local.refreshListing(scan)
                if (listingRefreshed) {
                    invalidatePagingSources()
                }
                val hasFailures = successful.size < total || !listingRefreshed
                if (!hasFailures) {
                    basePreferences.localSourceSyncMtime.set(local.getBaseDirectoryLastModified())
                    local.getMangaDirectorySignature()?.let(basePreferences.localSourceDirectorySignature::set)
                }
                localSourceChanged.value = hasFailures
                val changedDirectoryCount = (scan.changedMangaUrls + scan.removedMangaUrls).size
                chapterRefreshEvents.send(
                    ChapterRefreshResult(
                        changedManga = changedDirectoryCount,
                        newChapters = totalNew,
                        storageUnavailable = !listingRefreshed,
                    ),
                )
            } finally {
                isRefreshingChapters.value = null
                refreshVisibleSnapshots(forceDirectoryCheck = true)
            }
        }
    }

    private suspend fun relocateMovedLocalChapters(scan: tachiyomi.source.local.LocalChapterSyncScan) {
        val storedMangas = mangaRepository.getMangaProgressBySource(sourceId)
        val mangaUrlById = storedMangas.associate { it.mangaId to it.url }
        if (mangaUrlById.isEmpty()) return
        val mangaByUrl = mutableMapOf<String, Manga>()
        val allStoredChapters = chapterRepository
            .getChaptersByMangaIds(mangaUrlById.keys.toList())
        val coverManager = Injekt.get<LocalChapterCoverManager>()
        val historyByMangaId = mutableMapOf<Long, Map<Long, java.util.Date?>>()
        suspend fun readAt(mangaId: Long, chapterId: Long): java.util.Date? {
            val histories = historyByMangaId[mangaId] ?: historyRepository
                .getHistoryByMangaId(mangaId)
                .associate { it.chapterId to it.readAt }
                .also { historyByMangaId[mangaId] = it }
            return histories[chapterId]
        }

        val duplicateGroups = findExactLocalChapterDuplicateGroups(allStoredChapters)
        duplicateGroups.forEach { duplicates ->
            val keeper = duplicates.minBy(Chapter::id)
            val preferredProgressId = duplicates
                .mapNotNull { chapter -> readAt(chapter.mangaId, chapter.id)?.let { it to chapter.id } }
                .maxByOrNull { it.first }
                ?.second
            val merged = mergeExactLocalChapterDuplicates(duplicates, preferredProgressId)
            // 漫画可能已被删除（比如本地文件刚被清理掉），拿不到就跳过这组，
            // 否则 awaitAsOne 会抛 "ResultSet returned null"。
            val mangaTitle = runCatching { mangaRepository.getMangaById(keeper.mangaId) }
                .getOrNull()?.title ?: return@forEach
            duplicates.filterNot { it.id == keeper.id }.forEach { duplicate ->
                coverManager.copyCustomCover(keeper.id, duplicate.id)
                chapterRepository.mergeRelocatedChapter(merged, duplicate.id)
                coverManager.deleteCustomCover(duplicate.id)
                mangaMarkStore.merge(
                    chapterId = keeper.id,
                    duplicateChapterId = duplicate.id,
                    mangaId = keeper.mangaId,
                    mangaTitle = mangaTitle,
                )
            }
        }

        if (scan.chapterFileNamesByMangaUrl.isEmpty()) return
        val duplicateIds = duplicateGroups.flatMap { group ->
            val keeperId = group.minOf(Chapter::id)
            group.map(Chapter::id).filterNot { it == keeperId }
        }.toHashSet()
        val storedChapters = allStoredChapters
            .filterNot { it.id in duplicateIds }
            .mapNotNull { chapter ->
                val mangaUrl = mangaUrlById[chapter.mangaId] ?: return@mapNotNull null
                StoredLocalChapter(
                    chapterId = chapter.id,
                    mangaId = chapter.mangaId,
                    mangaUrl = mangaUrl,
                    fileName = chapter.url.substringAfter('/', chapter.url),
                )
            }
        val currentChangedFiles = scan.chapterFileNamesByMangaUrl
            .filterKeys(scan.changedMangaUrls::contains)
        val candidates = (
            detectLocalChapterMoves(
                storedChapters = storedChapters,
                previousFileNamesByMangaUrl = scan.previousChapterFileNamesByMangaUrl,
                currentFileNamesByMangaUrl = currentChangedFiles,
            ) + detectStaleLocalChapterMoves(
                storedChapters = storedChapters,
                currentFileNamesByMangaUrl = scan.chapterFileNamesByMangaUrl,
            )
            ).distinctBy(LocalChapterMoveCandidate::chapterId)
        if (candidates.isEmpty()) return

        candidates.forEach { candidate ->
            val targetManga = mangaByUrl[candidate.newMangaUrl] ?: (
                mangaRepository.getMangaByUrlAndSourceId(candidate.newMangaUrl, sourceId)
                    ?: networkToLocalManga(
                        Manga.create().copy(
                            source = sourceId,
                            url = candidate.newMangaUrl,
                            title = candidate.newMangaUrl,
                        ),
                    )
                ).also { mangaByUrl[candidate.newMangaUrl] = it }
            val oldChapterUrl = "${candidate.oldMangaUrl}/${candidate.fileName}"
            val newChapterUrl = "${candidate.newMangaUrl}/${candidate.fileName}"
            val chapter = chapterRepository.getChapterById(candidate.chapterId) ?: return@forEach
            val duplicate = candidate.duplicateChapterId
                ?.let { chapterRepository.getChapterById(it) }
                ?.takeIf { it.mangaId == targetManga.id && it.url == newChapterUrl }
            if (duplicate != null) {
                // Keep both files until the database transaction succeeds. A failed merge must
                // not delete the duplicate chapter's only custom cover.
                coverManager.copyCustomCover(chapter.id, duplicate.id)
            }
            coverManager.migrateLegacyCover(
                chapterId = candidate.chapterId,
                oldChapterUrl = oldChapterUrl,
                newChapterUrl = newChapterUrl,
            )
            if (duplicate == null) {
                chapterRepository.relocateAll(
                    listOf(
                        ChapterUpdate(
                            id = candidate.chapterId,
                            mangaId = targetManga.id,
                            url = newChapterUrl,
                        ),
                    ),
                )
                mangaMarkStore.relocate(
                    chapterId = candidate.chapterId,
                    mangaId = targetManga.id,
                    mangaTitle = targetManga.title,
                )
            } else {
                val oldReadAt = readAt(chapter.mangaId, chapter.id)
                val duplicateReadAt = readAt(duplicate.mangaId, duplicate.id)
                chapterRepository.mergeRelocatedChapter(
                    chapterUpdate = mergeMovedLocalChapter(
                        chapter = chapter,
                        duplicate = duplicate,
                        targetMangaId = targetManga.id,
                        targetUrl = newChapterUrl,
                        preferDuplicateProgress = duplicateReadAt != null &&
                            (oldReadAt == null || duplicateReadAt.after(oldReadAt)),
                    ),
                    duplicateChapterId = duplicate.id,
                )
                coverManager.deleteCustomCover(duplicate.id)
                mangaMarkStore.merge(
                    chapterId = candidate.chapterId,
                    duplicateChapterId = duplicate.id,
                    mangaId = targetManga.id,
                    mangaTitle = targetManga.title,
                )
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

    /**
     * Picks a random manga from the good doujin list. [RandomGoodDoujinResult.hasEntries]
     * tells an empty list apart from a pick that simply had nothing left to choose, so
     * the caller can show the right message.
     */
    internal suspend fun getRandomGoodDoujinManga(): RandomGoodDoujinResult {
        val markedMangaIds = goodDoujinStore.marks.value
            .map { it.mangaId }
            .distinct()
        return RandomGoodDoujinResult(
            hasEntries = markedMangaIds.isNotEmpty(),
            mangaId = randomSelectionCooldown.pickManga(markedMangaIds),
        )
    }

    /** Enumerates the manga URLs shown by the current listing and reading filter. */
    private suspend fun currentFilteredMangaUrls(local: LocalSource): List<String> {
        val listing = state.value.listing
        val urls = withIOContext {
            when (listing) {
                is Listing.Popular -> local.getPopularMangaUrls()
                is Listing.Latest -> local.getLatestMangaUrls()
                is Listing.Search -> local.getSearchMangaUrls(listing.query.orEmpty())
            }
        }
        if (urls.isEmpty()) return emptyList()

        val context = progressContext.value
        return urls.filter { url ->
            matchesListingFilters(
                url = url,
                context = context,
                readingFilter = readingFilterInternal.value,
                markFilter = markFilterInternal.value,
                favoriteUrls = favoriteUrlsInternal.value,
            )
        }
    }

    /**
     * The one definition of "this URL survives the reading and mark filters".
     *
     * The browse list, the toolbar's random pick and the details screen's random pool all go
     * through here: with two copies the button eventually offers a manga the list would not
     * show, which is how a fully read manga keeps sneaking into an "Unread" selection.
     */
    private fun matchesListingFilters(
        url: String,
        context: ProgressContext,
        readingFilter: ReadingFilter,
        markFilter: MarkFilter,
        favoriteUrls: Set<String>,
    ): Boolean {
        val progress = context.progressByUrl[url]
            ?: context.fsChapterCounts[url]
                ?.takeIf { it > 0 }
                ?.let { MangaProgress(it, 0, 0, 0) }
            ?: MangaProgress.EMPTY
        val markMatch = if (markFilter == MarkFilter.NOT_IN_LIBRARY) {
            url !in favoriteUrls
        } else {
            matchesMarkFilter(markFilter, url, context)
        }
        return matchesReadingFilter(readingFilter, progress) && markMatch
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
        UNREAD,
        IN_PROGRESS,
        FINISHED,
    }

    enum class MarkFilter {
        NONE,
        FLAGGED,
        GOOD_DOUJIN,
        NOT_IN_LIBRARY,
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
        data class ChangeSelectionCategory(
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteSelection(val titles: List<String>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog

        /** Confirms a read-status change across a selection, which can touch every chapter at once. */
        data class MarkSelectionRead(val read: Boolean, val count: Int) : Dialog
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
        val listingUrls: List<String>?,
        val readingFilter: ReadingFilter,
        val markFilter: MarkFilter,
        val context: CountContext,
        val favoriteUrls: Set<String>,
    )

    @Immutable
    private data class CountContext(
        val startedUrls: Set<String>,
        val finishedUrls: Set<String>,
        val markedUrls: Set<String>?,
        val favoriteUrls: Set<String>,
    ) {
        fun matchesReadingFilter(filter: ReadingFilter, url: String): Boolean = when (filter) {
            ReadingFilter.ALL -> true
            ReadingFilter.UNREAD -> url !in finishedUrls
            ReadingFilter.IN_PROGRESS -> url in startedUrls && url !in finishedUrls
            ReadingFilter.FINISHED -> url in finishedUrls
        }

        fun matchesMarkFilter(filter: MarkFilter, url: String): Boolean = when (filter) {
            MarkFilter.NOT_IN_LIBRARY -> url !in favoriteUrls
            else -> markedUrls?.contains(url) ?: true
        }
    }

    @Immutable
    data class ProgressContext(
        val progressByMangaId: Map<Long, MangaProgress>,
        val progressByUrl: Map<String, MangaProgress>,
        val fsChapterCounts: Map<String, Long>,
        val lastReadMangaId: Long? = null,
        val flaggedUrls: Set<String> = emptySet(),
        val goodDoujinUrls: Set<String> = emptySet(),
    ) {
        fun progressFor(mangaId: Long, url: String): MangaProgress {
            return progressByMangaId[mangaId] ?: fsChapterCounts[url]?.takeIf { it > 0 }?.let {
                MangaProgress(it, 0, 0, 0)
            } ?: MangaProgress.EMPTY
        }
    }

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

internal fun localDirectoryChangeCanApplyImmediately(
    observedUrls: Set<String>,
    listingUrls: Set<String>,
): Boolean = listingUrls.isNotEmpty() && observedUrls.containsAll(listingUrls)
