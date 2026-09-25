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
import androidx.paging.LoadState
import androidx.paging.LoadStates
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
import eu.kanade.presentation.category.components.writableCategoryIds
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.local.LocalEntryDeletionService
import eu.kanade.tachiyomi.data.manga.GoodDoujinStore
import eu.kanade.tachiyomi.data.manga.MangaCoverUpdateStore
import eu.kanade.tachiyomi.data.manga.MangaMark
import eu.kanade.tachiyomi.data.manga.MangaMarkStore
import eu.kanade.tachiyomi.data.manga.RandomSelectionCooldown
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
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
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.Preference
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

        /**
         * The load states of a list that is already complete: nothing is loading, and there is
         * nothing further to fetch. Handing this to [PagingData.from] is what stops a finished list
         * from being reported as still loading.
         */
        private val CompleteLoadStates = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = false),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

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
    private val markFilterPreference = preferenceStore.getString(
        "browse_mark_filter_$sourceId",
        MarkFilter.NONE.name,
    )

    private val dateAxisPreference: Preference<String> = preferenceStore.getString(
        "browse_date_axis",
        LocalDateAxis.Imported.name,
    )

    /**
     * Remembered direction of each date, so switching dates does not carry the previous one's
     * direction over.
     *
     * The direction used to travel with the sort key, which meant two orders that are each natural
     * on their own could never be reached one from the other: arriving at 看完 from 全部 inherited
     * ascending, so the most recently finished work - the one the reader came to see - sat at the
     * bottom, and the one tap that fixed it also overwrote the direction of the list they came
     * from. Each date therefore keeps its own.
     *
     * Every date defaults to descending: a date list answers "what happened recently", so the
     * newest entry belongs at the top - the import date included, where the newest arrival is what
     * the reader most often wants.
     *
     * Every date lives here, the import date included. Its direction used to be the source's stored
     * one, because the source derives the listing in that order - but that slot is shared with 标题
     * and 篇数, so the import date could never be newest-first while the title list was A-to-Z, and
     * a choice made on one silently became the other's. The key now has its own slot and the
     * ordering is applied in the app layer like every other date.
     */
    private val dateAxisAscendingPreferences: Map<LocalDateAxis, Preference<Boolean>> =
        LocalDateAxis.entries.associateWith { axis ->
            preferenceStore.getBoolean("browse_date_axis_ascending_${axis.name}", false)
        }

    /**
     * Everything the reader changes as one action: the filters in force, the sort key, the date that
     * key means, and each date's remembered direction.
     *
     * One value rather than separate flows because these change together and are only meaningful
     * together. A filter tap can also move the date (see [followedDateAxis]), and with separate
     * flows the list rebuilt once per field: the first rebuild ordered it by the date the reader was
     * leaving, and only the second was the order they asked for - visible as the list flashing an
     * order that never existed. One emission cannot be torn, which is the same reason the filter and
     * its match sets are published as one value.
     *
     * The derived flows below are read-only projections of this; every write goes through
     * [updateListControls].
     */
    @Immutable
    private data class ListControls(
        val readingFilter: ReadingFilter,
        val markFilter: MarkFilter,
        val sort: SourceModelFilter.Sort.Selection?,
        val dateAxis: LocalDateAxis,
        val dateDirections: Map<LocalDateAxis, Boolean>,
    ) {
        /** The dates the filters in force make available, in menu order. */
        val availableDates: List<LocalDateAxis>
            get() = datesAvailableFor(readingFilter, markFilter)

        /** Direction in force for [axis], read from that date's own slot. */
        fun ascendingFor(axis: LocalDateAxis): Boolean = dateDirections[axis] ?: false

        /** The ordering the list is really in: the date key's direction comes from its own slot. */
        val effectiveSort: SourceModelFilter.Sort.Selection?
            get() = sort?.let { selection ->
                if (selection.index == LocalSource.ORDER_BY_DATE) {
                    selection.copy(ascending = ascendingFor(dateAxis))
                } else {
                    selection
                }
            }
    }

    private val listControls = MutableStateFlow(
        ListControls(
            readingFilter = runCatching { ReadingFilter.valueOf(readingFilterPreference.get()) }
                .getOrDefault(ReadingFilter.ALL),
            markFilter = if (source is LocalSource && state.value.listing == Listing.Latest) {
                MarkFilter.NONE
            } else {
                runCatching { MarkFilter.valueOf(markFilterPreference.get()) }
                    .getOrDefault(MarkFilter.NONE)
            },
            sort = (source as? LocalSource)?.orderBySelection,
            dateAxis = runCatching { LocalDateAxis.valueOf(dateAxisPreference.get()) }
                .getOrDefault(LocalDateAxis.Imported),
            dateDirections = dateAxisAscendingPreferences.mapValues { (_, preference) -> preference.get() },
        ),
    )

    /**
     * Applies [change] as one write.
     *
     * The single write path for the reader's list controls. Describing a change as one function over
     * the whole state is what keeps a tap that moves two fields from rebuilding the list twice.
     *
     * There is deliberately no per-field mirror: a second copy of any of these goes stale the moment
     * this is the only writer, and a decision that reads the stale copy acts on the state the reader
     * just left. Reading them off [listControls] is what makes the one write authoritative.
     */
    private inline fun updateListControls(change: (ListControls) -> ListControls) {
        val next = change(listControls.value)
        if (next == listControls.value) return
        listControls.value = next
    }

    val readingFilter: StateFlow<ReadingFilter> = listControls
        .map { it.readingFilter }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, listControls.value.readingFilter)
    val markFilter: StateFlow<MarkFilter> = listControls
        .map { it.markFilter }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, listControls.value.markFilter)

    /**
     * Direction in force for [axis].
     *
     * Read from the date's own slot, the import date included. It used to borrow the source's
     * stored direction, because the source does the sorting for that one - but sharing a slot with
     * 标题 and 篇数 meant the import date could never be newest-first while the title list was
     * A-to-Z, and the reader's choice on one silently became the other's.
     */
    private fun axisAscending(axis: LocalDateAxis): Boolean =
        listControls.value.ascendingFor(axis)

    /**
     * Records the direction chosen for [axis] (never the import date; see the map above).
     *
     * Advances the list generation like any other reorder: the rows are the same works in a new
     * sequence, which is a new list as far as the screen is concerned - it opens at the top and
     * re-anchors the scroller.
     */
    private fun setAxisAscending(axis: LocalDateAxis, ascending: Boolean) {
        if (listControls.value.ascendingFor(axis) == ascending) return
        dateAxisAscendingPreferences[axis]?.set(ascending)
        updateListControls { it.copy(dateDirections = it.dateDirections + (axis to ascending)) }
        listGeneration.update { it + 1 }
    }

    /**
     * Records [axis] as the date to order by, without touching the list generation.
     *
     * The caller owns the generation bump, because picking a date can also change the sort key and
     * the two must land as one list. Bumping here as well made a single tap rebuild the list twice
     * - once for the key and once for the date - and the first rebuild was visible as the list
     * briefly in an order the reader never asked for.
     */
    private fun setDateAxis(axis: LocalDateAxis) {
        if (listControls.value.dateAxis == axis) return
        updateListControls { it.copy(dateAxis = axis) }
        dateAxisPreference.set(axis.name)
    }

    /**
     * Everything the sort control and the list identity need, as one value.
     *
     * Published together because they describe one state: the key, the direction in force for it,
     * the date it orders by, and which dates are on offer. Read as separate flows they arrive in
     * separate frames, and the control briefly shows a combination that never existed - the new
     * name over the old menu, or the new date over a list still in the previous order. One emission
     * cannot be torn.
     */
    @Immutable
    data class SortUiState(
        val selection: SourceModelFilter.Sort.Selection?,
        val dateAxis: LocalDateAxis?,
        val availableDates: List<LocalDateAxis>,
    )

    private val sortUiStateInternal: Flow<SortUiState> = combine(
        listControls,
        state.map { servesWholeListing(it.listing) && it.listing !is Listing.Latest },
    ) { controls, applies ->
        SortUiState(
            // Under the date key the direction shown (and used) is the date's own slot, never the
            // source's - the source's slot belongs to 标题 and 篇数, and letting the date read it is
            // what made newest-first import order impossible while the title list was A-to-Z.
            selection = controls.effectiveSort,
            dateAxis = controls.dateAxis.takeIf {
                applies && controls.sort?.index == LocalSource.ORDER_BY_DATE
            },
            availableDates = controls.availableDates,
        )
    }

    /** The sort control's state, as one value - see [SortUiState]. */
    val sortUiState: StateFlow<SortUiState> = sortUiStateInternal
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SortUiState(
                selection = listControls.value.sort,
                dateAxis = null,
                availableDates = listControls.value.availableDates,
            ),
        )

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
        // The url -> id translation for selection and the random pool. Only the local source needs
        // it: its listing comes from the file system and is matched against the database by url,
        // while a remote listing is already made of database rows.
        val mangaIdsByUrl = if (source is LocalSource) {
            async { mangaRepository.getMangaIdsBySourceId(sourceId) }
        } else {
            null
        }
        val local = source as? LocalSource
        val directory = local?.let {
            async { refreshLocalDirectorySnapshot(it, forceDirectoryCheck) }
        }

        progressSnapshot.value = progress.await()
        mangaIdsByUrl?.await()?.let { mangaIdsByUrlInternal.value = it }
        val favoriteIds = favorites.await()
        val favoritesChanged = hasLoadedFavoriteSnapshot && favoriteIdsInternal.value != favoriteIds
        favoriteIdsInternal.value = favoriteIds
        hasLoadedFavoriteSnapshot = true
        favoriteUrls?.await()?.let { favoriteUrlsInternal.value = it }
        // A favorite that just changed also changes what the "not in library" filter matches, so
        // the loaded pages have to be rebuilt for it as well.
        if (favoritesChanged &&
            (hideInLibraryItems || listControls.value.markFilter == MarkFilter.NOT_IN_LIBRARY)
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
                // The rebuild is not requested here: invalidating the listing is what raises the
                // revision, and the collector in `init` is the single place that turns it into one.
                // Asking for it here as well derived the listing twice for one directory change.
                local.invalidateListing()
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
        // Keyed by url, valued by the newest mark time on that work: membership is what the mark
        // filters ask ("is this flagged?"), and the time is what the date ordering reads when the
        // axis is the mark date. Several chapters of one work can be marked, so the newest wins -
        // the list then tracks when the work was last marked, which is what a mark list is about.
        val flaggedUrls = newestMarkByUrl(duplicateMarks, resolveUrl)
        val goodDoujinUrls = newestMarkByUrl(goodDoujinMarks, resolveUrl)

        ProgressContext(
            progressByMangaId = progressList.associate { it.mangaId to it.progress },
            progressByUrl = progressList.associate { it.url to it.progress },
            fsChapterCounts = fsChapterCounts,
            lastReadMangaId = progressList
                .filter { it.progress.lastOpenedAt > 0L }
                .maxByOrNull { it.progress.lastOpenedAt }
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
     * url -> manga id for every work of this source that has a row, chapter-bearing or not.
     *
     * Read from the shelf rather than derived from [progressSnapshot]: that snapshot comes from a
     * query joining chapters, so a work whose chapters are not in the database yet - a folder
     * that has never been opened - has no row there at all and would translate to nothing. The
     * listing still shows it, so select-all, invert and the random pool each silently dropped it
     * while the toolbar went on counting it.
     *
     * Filled by [refreshVisibleSnapshots], the same moment the progress snapshot is, so the two
     * stay consistent with each other and with the listing they are matched against.
     */
    private val mangaIdsByUrlInternal = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val mangaIdByUrl: StateFlow<Map<String, Long>> = mangaIdsByUrlInternal

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
        listControls,
        favoriteUrlsInternal,
        mangaIdByUrl,
    ) { (listing, snapshot), context, controls, favoriteUrls, idByUrl ->
        val readingFilter = controls.readingFilter
        val markFilter = controls.markFilter
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
    data class FilterContext(
        /**
         * Urls carrying a mark, valued by the newest mark time on the work. The mark filters only
         * ask membership; the times ride along because the date ordering reads them when the filter
         * in force is a mark filter. They belong in this value rather than being read separately
         * where the ordering happens: the filter and the times it is matched against have to reach
         * the list as one value, or the list would briefly be ordered by times belonging to the
         * previous filter.
         *
         * Empty whenever the corresponding mark filter is not in force, so a chapter being read -
         * which changes progress but no mark - still cannot invalidate the paged list.
         */
        val flaggedUrls: Map<String, Long> = emptyMap(),
        val goodDoujinUrls: Map<String, Long> = emptyMap(),
        val favoriteUrls: Set<String> = emptySet(),
        val finishedUrls: Set<String> = emptySet(),
        val startedUrls: Set<String> = emptySet(),
    ) {
        /**
         * Newest mark time on the work at [url] of the kind [axis] tracks, 0 when it carries none.
         *
         * Keyed on the axis rather than on the mark filter: the two marks are separate stores, and
         * the date being sorted by is what decides which one is being asked about.
         */
        fun markedAt(url: String, axis: LocalDateAxis): Long = when (axis) {
            LocalDateAxis.Flagged -> flaggedUrls[url]
            LocalDateAxis.GoodDoujin -> goodDoujinUrls[url]
            else -> null
        } ?: 0L
    }

    /**
     * The filters in force, together with the sets they are matched against, as one value.
     *
     * They are only ever meaningful together: each filter's predicate reads its own set, so a new
     * filter paired with the previous sets is not a state the list may be filtered by. Deriving
     * them separately let exactly that reach the list - the filter changed first, the sets followed
     * once the progress they come from was recomputed, and the page in between was filtered by a
     * filter that had no data yet: "read" against a not-yet-built set of read urls matches nothing,
     * so the list emptied and the screen showed "no results" before filling in.
     *
     * Published as one value so the paged list always sees a matching pair.
     *
     * [dateOrder] rides here for the same reason: it is derived from the same filter and progress
     * as the sets, and the ordering it applies has to match the filter the list was narrowed by.
     */
    @Immutable
    data class AppliedFilter(
        val readingFilter: ReadingFilter,
        val markFilter: MarkFilter,
        val context: FilterContext,
        val dateOrder: DateOrdering? = null,
    )

    /**
     * A date ordering to apply to the filtered list, beyond the one the source already produced.
     *
     * The source's own date ordering is the import date; every other axis reads data the source
     * cannot see (progress, marks), so those orderings are applied to the list once it is filtered,
     * in the app layer. Null whenever the source's own order already is the right one - the import
     * axis, or a key that is not the date - which is what leaves the existing behaviour untouched.
     *
     * [progressByUrl] is carried rather than looked up later so the ordering reads the very
     * progress the filter was built from; a separate read could pair a new axis with old progress.
     */
    @Immutable
    data class DateOrdering(
        val axis: LocalDateAxis,
        val ascending: Boolean,
        val progressByUrl: Map<String, MangaProgress>,
    )

    /**
     * Counts the lists the reader has asked for: one step per filter, order or listing choice.
     *
     * Stamped onto every row, so the screen can tell a replacement from an update at the moment
     * the rows are on screen. Deliberately not advanced by anything that changes content without
     * changing what was asked for - shelving a work, a rescan - because those must leave the
     * reader's place alone.
     */
    private val listGeneration = MutableStateFlow(0L)

    /**
     * The ordering inputs, grouped so the filter value below can take them as one.
     *
     * The whole control state is one input, not one per field. A filter tap can also move the date,
     * and those now land in the same write - so taking them as separate inputs would emit once for
     * each field and the list would be derived twice, the first time in an order that never existed.
     */
    private val appliedFilter: StateFlow<AppliedFilter> = combine(
        progressContext,
        listControls,
        favoriteUrlsInternal,
    ) { context, controls, favoriteUrls ->
        val readingFilter = controls.readingFilter
        val markFilter = controls.markFilter
        val sort = controls.sort
        AppliedFilter(
            readingFilter = readingFilter,
            markFilter = markFilter,
            context = FilterContext(
                flaggedUrls = if (markFilter == MarkFilter.FLAGGED) context.flaggedUrls else emptyMap(),
                goodDoujinUrls = if (markFilter == MarkFilter.GOOD_DOUJIN) context.goodDoujinUrls else emptyMap(),
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
            ),
            dateOrder = sort
                ?.takeIf { it.index == LocalSource.ORDER_BY_DATE }
                ?.let { controls.dateAxis }
                ?.let { axis ->
                    // Every date is applied here, the import date included. Its direction has its
                    // own slot now (see [axisAscending]), and the source's own ordering is fixed to
                    // one direction - so the import date can no longer ride along with it, or a
                    // reader who chose newest-first import order would get whichever direction the
                    // source happened to hold.
                    DateOrdering(
                        axis = axis,
                        ascending = controls.ascendingFor(axis),
                        // Carried only for the dates that read it. The others would otherwise pin a
                        // fresh progress map into this value on every chapter read, and this value's
                        // whole point is that a read cannot invalidate the paged list.
                        progressByUrl = if (axis.readsProgress) context.progressByUrl else emptyMap(),
                    )
                },
        )
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            AppliedFilter(
                readingFilter = listControls.value.readingFilter,
                markFilter = listControls.value.markFilter,
                context = FilterContext(),
            ),
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
            appliedFilter,
            source.listingSnapshot,
        ) { applied, snapshot ->
            val context = applied.context
            val markFilter = applied.markFilter
            val readingFilter = applied.readingFilter
            val markUrls = when (markFilter) {
                MarkFilter.NONE -> null
                MarkFilter.FLAGGED -> context.flaggedUrls.keys
                MarkFilter.GOOD_DOUJIN -> context.goodDoujinUrls.keys
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
        combine(
            listingWithSnapshot,
            progressContext,
            listControls,
            favoriteUrlsInternal,
            screenVisible,
        ) { (listing, snapshot), context, controls, favoriteUrls, visible ->
            val filter = controls.readingFilter
            val markFilter = controls.markFilter
            if (visible) {
                CountFilterArgs(
                    listing = listing,
                    listingUrls = when (listing) {
                        Listing.Popular -> snapshot.allUrls
                        Listing.Latest -> snapshot.latestUrls
                        // The local library is browsed with a blank query, so its search state is
                        // the whole listing and belongs to the snapshot like the two above. Left
                        // to the search branch it carried no urls at all, which made every listing
                        // change produce identical args: distinctUntilChanged then dropped the
                        // re-emission and the number kept its old value until something unrelated
                        // moved - a filter tap, or the next visit. Reading the snapshot here is
                        // also what the list itself is built from, so the two agree by
                        // construction. A real search still defines its own result set.
                        is Listing.Search -> snapshot.allUrls.takeIf { listing.query.isNullOrBlank() }
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
     *
     * Also ticks [reloadGeneration], which is what tells a listing served whole to re-derive. That
     * listing has no paging source to invalidate, so without the tick every one of these reloads
     * would be silently dropped for it.
     */
    private fun invalidatePagingSources() {
        // Pager instances for cached listings are created once, so keep their sources registered
        // for later directory, favorite-filter, and manual-refresh invalidations.
        pagingSources.forEach { it.invalidate() }
        // The pages served from now on reflect the listing as it stands at this moment, so this
        // is what any later comparison has to measure against.
        currentListingUrls()?.let { servedListingUrls = it }
        reloadGeneration.update { it + 1 }
    }

    /** Ticks whenever the listing has to be derived again; see [invalidatePagingSources]. */
    private val reloadGeneration = MutableStateFlow(0)

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
     * Whether [listing] is served as one finished list rather than paged in.
     *
     * The local library, whose listing is derived in full anyway - see [wholeListingFor]. It is
     * browsed with a blank query; a search on the local source defines a result set of its own and
     * is paged like any other. Everything paging does around an unfinished list is unnecessary for
     * the library and is switched off with this.
     */
    private fun servesWholeListing(listing: Listing): Boolean {
        if (source !is LocalSource) return false
        return !(listing is Listing.Search && !listing.query.isNullOrBlank())
    }

    /**
     * Whether the list on screen is served whole, which decides how its rows are identified.
     *
     * A whole list is replaced outright, so a row must not be identified by what it shows: the
     * lazy layout remembers the key of the row it is showing and, when the content is replaced,
     * follows that key to wherever it now sits. For a list that grows a page at a time that is
     * exactly right - the reader keeps their place. For a list that is replaced it is wrong: the
     * work under the reader is still somewhere in the new list, just at another index, so the
     * reader is carried there. It is what made a filter change land at 99, and what made flipping
     * the sort throw the list to the other end.
     */
    val wholeListingShown: StateFlow<Boolean> = state
        .map { servesWholeListing(it.listing) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, servesWholeListing(state.value.listing))

    /**
     * Whether the pager fills unloaded positions with placeholders, which fixes the presented
     * list length at the full result count. Off while a client-side filter can drop items out
     * of loaded pages, because the slots are then sized by the unfiltered listing and never
     * fill - see [clientFilterNarrows].
     *
     * Always off for a local listing: it is served complete (see [wholeListingFor]), so there are
     * no unloaded positions to fill, and a placeholder count could only disagree with it.
     */
    private val pagingPlaceholdersEnabled: StateFlow<Boolean> = combine(
        listControls,
        wholeListingShown,
    ) { controls, wholeListing ->
        !wholeListing && !clientFilterNarrows(controls.readingFilter, controls.markFilter)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
     *
     * Nothing is kept for a local listing: it is served complete, so its end is the real end and
     * the scroller can already travel the whole of it.
     */
    val trailingSlotCount: StateFlow<Int> = combine(
        pagingPlaceholdersEnabled,
        wholeListingShown,
    ) { placeholders, wholeListing ->
        if (placeholders || wholeListing) 0 else pageSize
    }
        .distinctUntilChanged()
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

    /**
     * The local library as a single, finished list, kept per window.
     *
     * Its listing is derived in full before any page is sliced out of it, so paging here only
     * re-slices a list that already exists - while making the screen receive that list in
     * installments. A page arriving grows the list underneath the reader, and paging's own
     * bookkeeping (placeholders, the trailing skeleton slots, re-reading from the first page after
     * an invalidation) exists to paper over exactly that. Handing the whole list over removes the
     * cause instead: the list is complete before it is shown, so nothing about it changes while it
     * is being read.
     *
     * The flow is cached per window so the same instance is handed back for every change of filter
     * or order: the same flow re-emits, and the reader's list simply becomes the new one. That is
     * what keeps the screen from tearing its list down and building it again.
     *
     * Cached on the window rather than on the listing object: a listing carries a `FilterList`,
     * whose equals is always false, so keying on it would miss on every lookup.
     *
     * Two steps, deliberately separated. The derived listing depends only on the order and on the
     * directory - it is the same library whatever the reading or mark filter says. Those are
     * applied afterwards, to the list already in hand, so a filter tap is pure work on data that is
     * already here while a re-order or a rescan derives first. Doing both in one step is what made
     * a filter tap take as long as a reload.
     */
    private val wholeListingFlows = ConcurrentHashMap<Boolean, Flow<PagingData<Manga>>>()

    private var resolvedRowsFor: Set<String>? = null
    private var resolvedRowsGeneration = -1
    private var resolvedRows = emptyMap<String, Manga>()

    /**
     * The stored row behind each derived entry.
     *
     * Resolving a row means asking the repository to insert-or-update it and hand back what is
     * stored: a write transaction per entry, which measured 254ms for this library and accounted
     * for 84% of the time a re-order took. A re-order does not change any row - the same works are
     * there, in another sequence - so reusing what was already resolved is free of consequence and
     * takes that cost off every sort and every filter change.
     *
     * Keyed on the set of works and on the reload counter, not on the derived list: a re-order
     * produces a fresh list holding the very same works. The set is what can change a row's
     * identity; the counter covers the events that change a row in place - a work being shelved, a
     * rescan, an explicit reload.
     */
    private suspend fun resolveListingRows(smangas: List<SManga>): List<Manga> {
        val urls = smangas.mapTo(HashSet()) { it.url }
        val generation = reloadGeneration.value
        if (urls != resolvedRowsFor || generation != resolvedRowsGeneration) {
            val resolved = networkToLocalManga(smangas.map { it.toDomainManga(source.id) })
            resolvedRows = resolved.associateBy { it.url }
            resolvedRowsFor = urls
            resolvedRowsGeneration = generation
            return resolved
        }
        val cache = resolvedRows
        return smangas.mapNotNull { cache[it.url] }
    }

    private fun wholeListingFor(listing: Listing): Flow<PagingData<Manga>> {
        val latestWindow = listing is Listing.Latest
        return wholeListingFlows.getOrPut(latestWindow) {
            // One combine, not two chained ones. Chaining made a change that moves both the filter
            // and the ordering derive the listing twice - once per stage - and the first pass was
            // rendered before the second replaced it.
            combine(listControls, reloadGeneration, appliedFilter) { controls, _, applied ->
                val local = source as? LocalSource ?: return@combine emptyList()
                val smangas = local.getWholeListing(latestWindow)
                // This is the moment the pages really are built from the listing, so it is also the
                // moment the comparison in [rebuildIfListingChanged] has to measure later changes
                // against. Recording it only when the listing was invalidated recorded the listing
                // as it stood *before* the change: the rebuild that followed then read as another
                // change, and returning to the tab rebuilt the whole list a second time for the one
                // event - which is what made an import look like it stuttered and re-sorted late.
                local.listingSnapshot.value.allUrls.toSet()
                    .takeIf { it.isNotEmpty() }
                    ?.let { servedListingUrls = it }
                val filtered = resolveListingRows(smangas)
                    .filter { !hideInLibraryItems || !it.favorite }
                    .filter { it.matches(applied) }
                // The 最近更新 listing keeps its native order - the chapters' own recency - whatever
                // the sort key says, exactly as the source derived it.
                when (val order = applied.dateOrder.takeIf { !latestWindow }) {
                    null -> filtered
                    else -> reorderDateAxis(filtered, order, applied)
                }
            }
                .map { mangas ->
                    PagingData.from(
                        data = mangas,
                        sourceLoadStates = CompleteLoadStates,
                    )
                }
        }
    }

    /** Whether [manga] passes the reading and mark filters in force. */
    private fun Manga.matches(applied: AppliedFilter): Boolean {
        val url = this.url
        val readingMatch = when (applied.readingFilter) {
            ReadingFilter.ALL -> true
            ReadingFilter.UNREAD -> url !in applied.context.finishedUrls
            ReadingFilter.IN_PROGRESS ->
                url in applied.context.startedUrls && url !in applied.context.finishedUrls
            ReadingFilter.FINISHED -> url in applied.context.finishedUrls
        }
        val markMatch = when (applied.markFilter) {
            MarkFilter.NONE -> true
            MarkFilter.FLAGGED -> url in applied.context.flaggedUrls
            MarkFilter.GOOD_DOUJIN -> url in applied.context.goodDoujinUrls
            MarkFilter.NOT_IN_LIBRARY -> url !in applied.context.favoriteUrls
        }
        return readingMatch && markMatch
    }

    /**
     * The timestamp [manga] is dated by under [ordering]'s axis, for both the sequence and the
     * separators.
     *
     * One function for both on purpose: a list ordered by one date under headings naming another
     * would put every entry under a heading of its own, because consecutive entries would keep
     * falling into different buckets. The axis and the maps it reads come from the same applied
     * filter, so the order and the headings cannot be built from different filters' data either.
     *
     * A null [ordering] is the import date - the source already served that sequence, so there is
     * nothing to reorder by, but the headings still name it.
     */
    private fun dateAxisValueOf(manga: Manga, ordering: DateOrdering?, applied: AppliedFilter): Long {
        val axis = ordering?.axis ?: LocalDateAxis.Imported
        return axis.value(
            manga = manga,
            progress = ordering?.progressByUrl?.get(manga.url) ?: MangaProgress.EMPTY,
            markedAt = applied.context.markedAt(manga.url, axis),
        )
    }

    /**
     * Reorders an already-filtered list by [ordering]'s axis.
     *
     * Applied to the filtered list rather than to the source's own ordering: the axis reads data
     * the source cannot see, and filtering first means only the entries that survive are ordered.
     */
    private fun reorderDateAxis(
        mangas: List<Manga>,
        ordering: DateOrdering,
        applied: AppliedFilter,
    ): List<Manga> {
        return mangas.sortedWith(
            dateAxisComparator(ordering.ascending) { manga ->
                dateAxisValueOf(manga, ordering, applied)
            },
        )
    }

    private fun pagerFor(listing: Listing, placeholdersEnabled: Boolean): Flow<PagingData<Manga>> {
        // The local library has nothing to gain from paging and pays for it: see [wholeListingFor].
        if (servesWholeListing(listing)) {
            return wholeListingFor(listing)
        }
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
        //
        // Only the pager is invalidated here, never a whole listing. This collector is driven by
        // the same value the listing is derived from, so a whole listing already re-derives from
        // that emission; ticking the reload as well would derive it a second time and show the
        // list updating twice for one tap.
        viewModelScope.launchIO {
            var seeded = false
            listingUrlFilter.collect { urls ->
                val local = source as? LocalSource ?: return@collect
                local.setListingUrlFilter(urls)
                if (seeded) {
                    pagingSources.forEach { it.invalidate() }
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
        //
        // The listing being invalidated is the one signal that is a real change and has no other
        // way of reaching this screen. An import runs in a WorkManager worker, so it cannot rebuild
        // anything itself and only raises the source's invalidated flag; without this collector the
        // new work stayed invisible until the reader tapped a filter, which rebuilt the list for an
        // unrelated reason. It is also why deleting from the details screen now lands here instead
        // of waiting for the next visit: the delete path used to rely on the pager's own refresh,
        // which is a no-op for a listing served whole (PagingData.from carries no ui receiver).
        viewModelScope.launchIO {
            val local = source as? LocalSource ?: return@launchIO
            // The current value only seeds the collector: the flag may already be raised by a
            // change that happened while this screen did not exist, and that change is what the
            // first derivation is about to read anyway.
            local.listingRevision.drop(1).collect {
                invalidatePagingSources()
            }
        }
    }

    /**
     * The pager and the item mapping for the listing currently shown.
     *
     * The pager is chosen by the listing and by whether a narrowing filter is active - the two
     * things that decide which pager serves the pages, and the reason placeholders match the
     * filter. Nothing else is an input here. The filters in force and the sets they match against
     * are read while the pages are mapped instead: they change whenever something is read or
     * shelved, and as inputs every one of those changes produced a brand new flow.
     *
     * That mattered because the caller collects this with paging-compose, which keys the
     * `LazyPagingItems` on the flow instance. A new instance is a new pager presentation that
     * starts with no items: the list empties, re-reads its first pages, and the scroll position,
     * the fast scroller and its anchor are all rebuilt along with it. It is what sent the reader
     * to the top - or to the end - when a work was added to the library, which only flips one
     * entry's shelf membership.
     *
     * Mapping rather than rebuilding also means the pages already loaded stay loaded. The filter
     * still decides which of them are shown, so a swap narrows the same loaded window instead of
     * dropping it and walking the pages again.
     */
    val mangaPagerFlowFlow: StateFlow<Flow<PagingData<BrowseSourceUiModel>>> = combine(
        state.map { it.listing }.distinctUntilChanged(),
        appliedFilter.map { !clientFilterNarrows(it.readingFilter, it.markFilter) }
            .distinctUntilChanged(),
    ) { listing, placeholdersEnabled ->
        listing to pagerFor(listing, placeholdersEnabled)
    }
        // Compared by identity on the pager flow, not on the pair: the listing's own equals cannot
        // be relied on here (a listing carries a FilterList, whose equals is always false), so
        // comparing pairs would call every re-emission a change. What matters is whether the flow
        // underneath is the same one - if it is, the screen keeps the list it already has.
        .distinctUntilChanged { old, new -> old.second === new.second }
        .map { (listing, pagerFlow) ->
            pagerFlow.map { pagingData ->
                // Read per delivered page, not captured when the flow was built: the filters can
                // change without the pager being swapped, and the pages that follow have to be
                // mapped with the filters in force at that moment.
                val applied = appliedFilter.value
                val generation = listGeneration.value
                val mapped = pagingData.map { manga ->
                    BrowseSourceUiModel.Item(
                        manga = manga,
                        matchedChapter = manga.memo[LocalSource.MATCHED_CHAPTER_KEY]?.jsonPrimitive?.contentOrNull,
                        listGeneration = generation,
                    )
                }.filter { model ->
                    val url = model.manga.url
                    val readingMatch = when (applied.readingFilter) {
                        ReadingFilter.ALL -> true
                        ReadingFilter.UNREAD -> url !in applied.context.finishedUrls
                        ReadingFilter.IN_PROGRESS ->
                            url in applied.context.startedUrls && url !in applied.context.finishedUrls
                        ReadingFilter.FINISHED -> url in applied.context.finishedUrls
                    }
                    val markMatch = when (applied.markFilter) {
                        MarkFilter.NONE -> true
                        MarkFilter.FLAGGED -> url in applied.context.flaggedUrls
                        MarkFilter.GOOD_DOUJIN -> url in applied.context.goodDoujinUrls
                        MarkFilter.NOT_IN_LIBRARY -> url !in applied.context.favoriteUrls
                    }
                    readingMatch && markMatch
                }
                // Date headers group runs of consecutive entries, so they only read as sections
                // while the list really is ordered by date. Under any other sort the dates jump
                // around and every entry would open its own header. The "recently updated" listing
                // is excluded the same way: it orders by the chapters' recency, not by a date the
                // headings could name.
                //
                // The ordering is what decides this, not which listing is shown: the separators
                // used to be tied to the "recent" listing, which meant picking the date sort on
                // its own showed no dates at all. The buckets read the axis the ordering uses -
                // the import date, or whichever date the active filter gives the key - so a heading
                // names the event the list is actually ordered by. Recent days stay separate and
                // read relatively; older ones collapse into their month so a library spanning years
                // does not become all headings.
                //
                // Read off the applied value rather than a copy of the sort state: [applied] is the
                // one write's own result, so a filter change that moved the ordering off the date
                // key is already reflected here. A stale copy made the headings outlive that move
                // and put date separators back on a list ordered by title.
                val dateKeyActive = applied.dateOrder != null
                if (source is LocalSource &&
                    dateKeyActive &&
                    listing !is Listing.Latest
                ) {
                    // Only where the axis ordering was actually applied. A local search is paged
                    // rather than served whole, so it keeps the source's own import-date order -
                    // and its headings have to name that date, not the axis the filter would have
                    // given the library list.
                    val ordering = applied.dateOrder.takeIf { servesWholeListing(listing) }
                    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                    mapped.insertSeparators<BrowseSourceUiModel.Item, BrowseSourceUiModel> { before, after ->
                        val next = after ?: return@insertSeparators null
                        val nextBucket = dateHeaderBucket(
                            dateAxisValueOf(next.manga, ordering, applied),
                            today,
                        )
                        val beforeBucket = before?.let {
                            dateHeaderBucket(dateAxisValueOf(it.manga, ordering, applied), today)
                        }
                        if (nextBucket != beforeBucket) {
                            BrowseSourceUiModel.Header(nextBucket, generation)
                        } else {
                            null
                        }
                    }
                } else {
                    mapped.map<BrowseSourceUiModel.Item, BrowseSourceUiModel> { it }
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
        listGeneration.update { it + 1 }
        mutableState.update {
            it.copy(
                listing = listing,
                toolbarQuery = null,
            )
        }
    }

    fun setReadingFilter(filter: ReadingFilter) {
        // Only the reading half moves: the mark filter in force is whatever the reader last chose,
        // read off the one value rather than a copy that only ever holds the startup state.
        applyFilters(readingFilter = filter, markFilter = listControls.value.markFilter)
        readingFilterPreference.set(filter.name)
    }

    private fun setMarkFilter(filter: MarkFilter) {
        applyFilters(readingFilter = listControls.value.readingFilter, markFilter = filter)
        markFilterPreference.set(filter.name)
    }

    /**
     * Moves to a filter pair and applies whatever it implies for the ordering, as one write.
     *
     * Both the filters and the date they imply are computed here and stored together, so the list is
     * derived once from the finished state. Writing them one after the other rebuilt the list twice:
     * the first rebuild paired the new filter with the date the reader was leaving, which the new
     * filter does not offer - so it fell back to another date for one frame, and the second rebuild
     * showed the order they actually asked for. That intermediate frame is what flashed.
     */
    private fun applyFilters(readingFilter: ReadingFilter, markFilter: MarkFilter) {
        val local = source as? LocalSource
        val current = listControls.value
        val sort = current.sort
        val available = datesAvailableFor(readingFilter, markFilter)
        val dateKeyActive = sort?.index == LocalSource.ORDER_BY_DATE
        val follow = if (dateKeyActive) {
            followedDateAxis(current.dateAxis, available)
        } else {
            DateFollow.Keep
        }
        // Leaving the date key for the title key is the one move that also rewrites the source's
        // stored ordering, since that is what serves the title list.
        val sortingByTitle = follow == DateFollow.DropToTitle
        if (sortingByTitle) {
            local?.setOrderByIndex(LocalSource.ORDER_BY_TITLE)
        }
        val nextSort = when {
            !sortingByTitle -> sort
            else -> local?.orderBySelection ?: sort?.copy(index = LocalSource.ORDER_BY_TITLE)
        }
        val nextDate = (follow as? DateFollow.MoveTo)?.axis ?: current.dateAxis
        updateListControls {
            it.copy(
                readingFilter = readingFilter,
                markFilter = markFilter,
                sort = nextSort,
                dateAxis = nextDate,
            )
        }
        listGeneration.update { it + 1 }
    }

    fun setLocalListFilter(filter: MarkFilter) {
        if (source !is LocalSource) return
        setMarkFilter(filter)
        setListing(Listing.Popular)
    }

    /**
     * The ordering a sort action left the list in, for the screen to report back to the reader.
     *
     * Returned rather than read from [localSort] because the flow has not emitted at that moment:
     * a direction flip on a non-import date only moves that date's slot, and reading the flow here
     * would report the ordering the list is leaving behind. [dateAxis] rides along for the same
     * reason - the notice names the date the action just chose, which the flow does not know yet.
     */
    @Immutable
    data class SortChange(
        val index: Int,
        val ascending: Boolean,
        val dateAxis: LocalDateAxis?,
    )

    /**
     * Orders the local library by [index]. Re-picking the key already in use changes nothing, so it
     * does not reload the list either.
     *
     * The direction is not carried over from the previous key. It used to be, which meant the
     * direction a key was left in followed the reader into the next one - and a key whose natural
     * order is the opposite of the one they came from arrived the wrong way round. Each key now
     * resumes the direction it was last left in: a date its own remembered one, the others the
     * source's single stored direction.
     */
    fun setLocalSortKey(index: Int): SortChange? {
        val local = source as? LocalSource ?: return null
        val controls = listControls.value
        val current = controls.sort ?: return null
        if (current.index == index) return null
        return if (index == LocalSource.ORDER_BY_DATE) {
            // The date key's direction is the date's own slot, so the source's stored direction is
            // left untouched - it belongs to 标题 and 篇数, and writing the date's direction into it
            // would turn the title list around behind the reader's back.
            val ascending = axisAscending(controls.dateAxis)
            applyLocalSortIndexOnly(local, index, ascending)
            SortChange(index, ascending, controls.dateAxis)
        } else {
            // Leaving the date key: the source resumes the direction it holds. A date direction
            // must not leak onto 标题 or 篇数 either.
            val ascending = local.orderBySelection.ascending
            applyLocalSort(local, index, ascending)
            SortChange(index, ascending, null)
        }
    }

    /**
     * Picks the date the date key orders by. Re-picking the one already chosen changes nothing.
     *
     * Selecting a date also selects the date key. The date is only reachable through the menu, so a
     * reader on 标题 or 篇数 who opens the dates and picks one is asking to order by date - and
     * requiring the key to be set first left that tap doing nothing at all.
     *
     * This is the only way the choice changes deliberately. [applyFilters] is the only way it
     * changes otherwise, and only when a filter has made the chosen date wrong or out of place.
     */
    fun setLocalDateAxis(axis: LocalDateAxis): SortChange? {
        val local = source as? LocalSource ?: return null
        val controls = listControls.value
        val current = controls.sort ?: return null
        // The menu only offers the available dates, so this guards against a stale tap rather than
        // against the reader asking for something they were not shown.
        if (axis !in controls.availableDates) return null
        val changingKey = current.index != LocalSource.ORDER_BY_DATE
        if (!changingKey && controls.dateAxis == axis) return null
        setDateAxis(axis)
        if (changingKey) {
            // Selecting a date also selects the date key: the date is only reachable through the
            // menu, so a reader on 标题 or 篇数 picking one is asking to order by date - and
            // requiring the key to be set first left that tap doing nothing at all. One write
            // covers both changes, so the list is rebuilt once, in its final order.
            local.setOrderBy(LocalSource.ORDER_BY_DATE, current.ascending)
            updateListControls { it.copy(sort = local.orderBySelection) }
            listGeneration.update { it + 1 }
            pagingSources.forEach { it.invalidate() }
        } else {
            listGeneration.update { it + 1 }
        }
        return SortChange(
            index = LocalSource.ORDER_BY_DATE,
            ascending = axisAscending(axis),
            dateAxis = axis,
        )
    }

    /**
     * Flips the ordering direction of the local library.
     *
     * On the date key the flip belongs to the chosen date, not to the source's stored direction: the
     * source's date ordering is the import date, so writing the flip there while another date is in
     * use would turn the reader's import-date order upside down behind their back. Only the import
     * date's flip reaches the source.
     */
    fun toggleLocalSortDirection(): SortChange? {
        val local = source as? LocalSource ?: return null
        val controls = listControls.value
        val current = controls.sort ?: return null
        if (current.index != LocalSource.ORDER_BY_DATE) {
            applyLocalSort(local, current.index, !current.ascending)
            return SortChange(current.index, !current.ascending, null)
        }
        // Every date flips its own slot, the import date included: the source's direction is the
        // title list's and flipping the date must not reach it.
        val axis = controls.dateAxis
        val flipped = !axisAscending(axis)
        setAxisAscending(axis, flipped)
        return SortChange(current.index, flipped, axis)
    }

    /**
     * Persists the new ordering and reloads the pages that are already on screen. Writing the
     * preference before invalidating is what makes the reload pick the new order up; the derived
     * listing cache keys on both fields, so it recomputes instead of serving the old sequence.
     *
     * The order itself is enough to redraw the list, so it is published without a reload tick. The
     * tick would be a second, redundant reason for the list to be derived again, and the second
     * derivation lands one beat after the first - which is exactly the stutter this had.
     */
    private fun applyLocalSort(local: LocalSource, index: Int, ascending: Boolean) {
        local.setOrderBy(index, ascending)
        updateListControls { it.copy(sort = local.orderBySelection) }
        listGeneration.update { it + 1 }
        // The pager still needs invalidating: its cached pages were sliced from the old order.
        pagingSources.forEach { it.invalidate() }
    }

    /**
     * Switches to [index] and hands the ordering its direction, without writing the source's own.
     *
     * Used when moving to the date key: that key reads its direction from the date's slot, so the
     * source's stored direction has to keep meaning 标题 and 篇数. The internal selection still
     * carries [ascending] so the list ordering and the chip agree from the first frame.
     */
    private fun applyLocalSortIndexOnly(local: LocalSource, index: Int, ascending: Boolean) {
        local.setOrderByIndex(index)
        updateListControls { it.copy(sort = local.orderBySelection.copy(ascending = ascending)) }
        listGeneration.update { it + 1 }
        pagingSources.forEach { it.invalidate() }
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
            MarkFilter.FLAGGED -> flaggedUrls.keys
            MarkFilter.GOOD_DOUJIN -> goodDoujinUrls.keys
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

            val update = new.toMangaUpdate()
            // The local source's date sort reads date_added as the day the work last entered the
            // library (see LocalSource's ordering), so shelf membership must not rewrite it;
            // only other sources carry the upstream "date added to library" meaning here.
            val applied = if (source is LocalSource) update.copy(dateAdded = null) else update
            if (updateManga.await(applied)) {
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
                // The pages already served are a slice of the listing as it stood when they were
                // built, so a work whose shelf membership just changed can still be sitting in
                // them - or missing from them - exactly where the "not in library" filter looks.
                // Reloading is what makes the list agree with the filter again.
                if (hideInLibraryItems || listControls.value.markFilter == MarkFilter.NOT_IN_LIBRARY) {
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
        val writable = writableCategoryIds(include)
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
                    // Shelving must not touch date_added: the local date sort reads it as the
                    // day the work last entered the library (see LocalSource's ordering), and
                    // rewriting it dropped the work into the "today" section the moment it was
                    // shelved.
                    updateManga.await(
                        manga.copy(favorite = true).toMangaUpdate().copy(dateAdded = null),
                    )
                }
            }
            favoriteIdsInternal.update { it + selected }
            favoriteUrlsInternal.update { it + selectedUrls }
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
            // The reading filter matches against the progress snapshot, which is otherwise only
            // reread when the screen is shown again. Without this the marked works keep their old
            // read state - a work marked unread while the 看完 filter is on stays in the list, and
            // a work marked read under 剩余 stays out - until the reader leaves and comes back.
            refreshVisibleSnapshots()
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
            // Same reason as the batch mark: the reading filter reads the progress snapshot, so
            // clearing progress has to refresh it or the works stay in whatever list the filter
            // put them in until the screen is left and shown again.
            refreshVisibleSnapshots()
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
        val controls = listControls.value
        return urls.filter { url ->
            matchesListingFilters(
                url = url,
                context = context,
                readingFilter = controls.readingFilter,
                markFilter = controls.markFilter,
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
        /**
         * Urls of the works carrying at least one chapter mark, valued by the newest mark time on
         * that work. Membership answers the mark filters; the value feeds the date ordering when
         * the filter in force is a mark filter.
         */
        val flaggedUrls: Map<String, Long> = emptyMap(),
        /** As [flaggedUrls], for the good-doujin mark. */
        val goodDoujinUrls: Map<String, Long> = emptyMap(),
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

/**
 * Urls of the works carrying a mark, valued by the newest mark time on each.
 *
 * A work can carry several marks (one per chapter), and the listing is per work, so the times have
 * to be folded here rather than looked up later. The newest is the one that matters: a mark list
 * ordered by date should lead with the work marked most recently, not with one whose oldest mark
 * happens to be recent.
 *
 * Marks whose work no longer resolves to a url are dropped, exactly as the set-building it
 * replaces did - a mark outliving its work must not invent an entry.
 */
private fun newestMarkByUrl(
    marks: List<MangaMark>,
    resolveUrl: (Long) -> String?,
): Map<String, Long> {
    if (marks.isEmpty()) return emptyMap()
    val newest = HashMap<String, Long>(marks.size)
    marks.forEach { mark ->
        val url = resolveUrl(mark.mangaId) ?: return@forEach
        val current = newest[url]
        if (current == null || mark.markedAt > current) {
            newest[url] = mark.markedAt
        }
    }
    return newest
}
