package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioBrowseContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioCategoryCache
import eu.kanade.tachiyomi.data.audio.AudioCategoryField
import eu.kanade.tachiyomi.data.audio.AudioCategoryRef
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioHistoryStore
import eu.kanade.tachiyomi.data.audio.AudioPageCache
import eu.kanade.tachiyomi.data.audio.AudioPageSnapshot
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.audio.WorksResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.CacheControl
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

class AudioBrowseScreen(
    internal val categoryTitle: String? = null,
    private val initialFilter: String? = null,
    /**
     * Opens on one dictionary entry's works, filtered by id. Mutually exclusive with
     * [initialFilter]; whichever is set wins.
     */
    private val initialCategory: AudioCategoryRef? = null,
) : Screen() {

    /** True for the whole library, false for a result page narrowed to one keyword or entry. */
    private val isLibraryScreen: Boolean
        get() = categoryTitle == null && initialFilter == null && initialCategory == null

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<AudioBrowseViewModel>()
        val audioController = remember { Injekt.get<AudioPlayerController>() }
        val state by viewModel.state.collectAsState()
        val sort by viewModel.sort.collectAsState()
        val workListSort by viewModel.workListSort.collectAsState()
        val auth by viewModel.auth.collectAsState()
        val onOpenCategory = rememberCategoryNavigator(navigator)

        LaunchedEffect(Unit) {
            viewModel.initialize(
                initialFilter,
                initialCategory,
                // The library keeps the random draw it starts on. A keyword or dictionary result
                // is one specific set the user just asked for, which reads better in release
                // order than in a fresh shuffle of it.
                sort = AudioSort.RELEASE_DESC.takeUnless { isLibraryScreen },
            )
        }

        AudioBrowseContent(
            state = state,
            title = categoryTitle ?: stringResource(MR.strings.audio_title),
            sort = sort,
            workListSort = workListSort,
            auth = auth,
            audioQuality = audioController.state.audioQuality,
            showTabs = categoryTitle == null,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            onClickWork = { work -> navigator.push(AudioDetailScreen(work)) },
            onClickHistory = { navigator.push(AudioHistoryScreen()) },
            onClickCategories = { navigator.push(AudioCategoryScreen()) },
            // The tag strip is the fastest way from a row that looks promising to more of the
            // same, so it opens the tag's own results page rather than the work it sits on.
            onClickTag = { name -> onOpenCategory(AudioCategoryField.TAG, name) },
            navigateUp = navigator::pop,
            onSearch = viewModel::search,
            onExitSearch = viewModel::exitSearch,
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onSortChange = viewModel::setSort,
            onSelectTab = viewModel::setTab,
            onLogin = viewModel::login,
            onLogout = viewModel::logout,
            onCycleAudioQuality = audioController::cycleAudioQuality,
        )
    }
}

/**
 * Opens one dictionary entry's works — a circle, VA or tag — from anywhere in the audio section.
 *
 * Shared by the browse list and the work details page so a tap on the same name always lands on
 * the same page, whichever list it was tapped in. Resolves the name to a backend id first, which
 * keeps the filter off the URL path and so makes it immune to names containing `$`, spaces or
 * punctuation; the legacy `$name$` keyword is only used when the on-disk dictionaries cannot pin
 * the name to a single entry.
 *
 * The lookup reads a multi-megabyte snapshot, so it runs on the IO dispatcher and the page is
 * pushed when it answers.
 */
@Composable
internal fun rememberCategoryNavigator(navigator: Navigator): (AudioCategoryField, String) -> Unit {
    val scope = rememberCoroutineScope()
    val cache: AudioCategoryCache = remember { Injekt.get() }
    return remember(navigator, scope) {
        { field: AudioCategoryField, name: String ->
            scope.launch {
                val ref = withContext(Dispatchers.IO) { cache.resolveRef(field, name) }
                navigator.push(
                    if (ref != null) {
                        AudioBrowseScreen(categoryTitle = name, initialCategory = ref)
                    } else {
                        AudioBrowseScreen(categoryTitle = name, initialFilter = field.legacyKeyword(name))
                    },
                )
            }
        }
    }
}

enum class AudioSort(
    val order: String,
    val sort: String,
    val label: StringResource,
    /** Shown on the browse tab while this sort drives the work list. */
    val tabLabel: StringResource,
    /**
     * Whether this sort is a one-off selection the user asked for rather than an order maintained
     * over a catalogue that keeps growing. A draw is pinned once fetched: the same request would
     * answer differently, so repeating it silently would destroy what the user is looking at
     * instead of adding to it.
     */
    val isDraw: Boolean = false,
) {
    // Listed first because it is what the work-list tab is opened for most often; [AudioSort.entries]
    // is also the order of the sort sheet, so it sits at the top there too.
    RANDOM("random", "desc", MR.strings.audio_sort_random, MR.strings.audio_sort_random, isDraw = true),
    RELEASE_DESC("release", "desc", MR.strings.audio_sort_release_desc, MR.strings.audio_tab_latest),

    // Backend rejects "rating" and answers with an empty list; the ranking column is this one.
    RATING_DESC(
        "rate_average_2dp",
        "desc",
        MR.strings.audio_sort_rating_desc,
        MR.strings.audio_tab_sort_rating,
    ),

    DL_DESC("dl_count", "desc", MR.strings.audio_sort_dl_desc, MR.strings.audio_tab_sort_dl),
    CREATE_DESC(
        "create_date",
        "desc",
        MR.strings.audio_sort_create_desc,
        MR.strings.audio_tab_sort_create,
    ),
}

enum class AudioBrowseTab(
    val label: StringResource,
    /** Whether this tab's content is ordered by the selected sort. */
    val sortable: Boolean,
) {
    // Backend feeds without an order parameter: picking a sort here would switch tabs anyway.
    RECOMMENDED(MR.strings.audio_tab_recommended, false),
    POPULAR(MR.strings.audio_tab_popular, false),

    LATEST(MR.strings.audio_tab_latest, true),

    // Favorites are held locally, so the sort reorders them in place.
    FAVORITES(MR.strings.audio_tab_favorites, true),
}

data class AudioBrowseState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: Boolean = false,
    val errorMessage: String? = null,
    val works: List<Work> = emptyList(),
    val query: String? = null,
    val page: Int = 1,
    val totalCount: Int = 0,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    // Opens on recommendations. Signed out that resolves to the popular list, which the backend
    // serves anonymously, rather than a personalized one the visitor cannot get.
    val tab: AudioBrowseTab = AudioBrowseTab.RECOMMENDED,
)

data class AudioAuthState(
    val loading: Boolean = false,
    val username: String? = null,
    val error: String? = null,
)

class AudioBrowseViewModel(
    private val api: KikoeruApi = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val favoriteStore: AudioFavoriteStore = Injekt.get(),
    private val accountSync: AudioAccountSync = Injekt.get(),
    private val playlistStore: AudioPlaylistStore = Injekt.get(),
    private val historyStore: AudioHistoryStore = Injekt.get(),
    private val pageCache: AudioPageCache = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(AudioBrowseState())
    val state: StateFlow<AudioBrowseState> = _state.asStateFlow()

    // One sort per sortable tab rather than a single shared one. The work-list tab is a discovery
    // surface, opened most often for a fresh random draw; the favorites tab is a lookup list over
    // works the user already picked, which is useless if it lands in a new order on every visit.
    // Sharing one value forced whichever was picked last onto both, so a random draw on the work
    // list also scrambled the favorites. The tab row names the work-list tab after its own sort,
    // which is how it reads 推荐 / 热门 / 随机 / 收藏.
    private val workSort = MutableStateFlow(AudioSort.RANDOM)
    private val favoriteSort = MutableStateFlow(AudioSort.RELEASE_DESC)

    /** Sort of the work-list tab, which is what that tab is named after. */
    val workListSort: StateFlow<AudioSort> = workSort.asStateFlow()

    /** Sort of the tab on screen, which is what the sort sheet edits. */
    val sort: StateFlow<AudioSort> = combine(_state, workSort, favoriteSort) { state, work, favorite ->
        if (state.tab == AudioBrowseTab.FAVORITES) favorite else work
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AudioSort.RANDOM)

    /** The flow holding [tab]'s sort, so a pick on one tab never reorders the other. */
    private fun sortOf(tab: AudioBrowseTab): MutableStateFlow<AudioSort> =
        if (tab == AudioBrowseTab.FAVORITES) favoriteSort else workSort

    private val _auth = MutableStateFlow(
        AudioAuthState(
            username = basePreferences.audioUsername.get()
                .takeIf { basePreferences.audioAuthToken.get().isNotBlank() }
                ?.ifBlank { null },
        ),
    )
    val auth: StateFlow<AudioAuthState> = _auth.asStateFlow()

    /** Bumped on every refresh/search so stale in-flight pages never overwrite newer ones. */
    private var loadGeneration = 0

    /** True once the first page has been requested, so returning to this screen never resets it. */
    private var initialized = false

    /**
     * When set, the list is narrowed to one dictionary entry by id rather than by keyword.
     *
     * Deliberately outside [AudioBrowseState]: unlike [AudioBrowseState.query] the user cannot
     * edit or clear it, the results page is for a single entry for its whole lifetime.
     */
    private var categoryRef: AudioCategoryRef? = null

    /** Warmed up second page, so the first pull up never waits on the network. */
    private var prefetchJob: Job? = null

    /**
     * The rows currently on screen when they came from a cache hit. Compared by reference to tell
     * whether the user has since scrolled into the list, because a background refresh is only
     * allowed to replace rows that were never appended to.
     */
    private var displayedFromCache: List<Work>? = null

    /**
     * The pinned random order of the local favorites, and the only thing that decides their
     * position while the draw sort is active. Null until a draw is taken.
     */
    private var shuffledFavoriteIds: List<Long>? = null

    /**
     * Seed of the random draw the backend was asked for, sent with every page of it.
     *
     * The backend reshuffles on a new seed and on nothing else: `order=random` without one answers
     * the same page every time, so a pull to refresh used to come back with the very rows on
     * screen. One value has to cover the whole draw — page two has to continue the shuffle page
     * one started, not open an unrelated one — so it is only rolled when the user asks for another
     * draw, which is [refresh] and picking the sort itself.
     */
    private var drawSeed: Long = Random.nextLong()

    init {
        if (_auth.value.username != null) {
            viewModelScope.launchIO {
                accountSync.synchronize()
                if (_state.value.tab == AudioBrowseTab.FAVORITES) loadLocalFavorites()
            }
        }
        viewModelScope.launch {
            basePreferences.audioFavorites.changes().collect {
                if (_state.value.tab == AudioBrowseTab.FAVORITES) loadLocalFavorites()
            }
        }
        viewModelScope.launch {
            combine(
                basePreferences.audioAuthToken.changes(),
                basePreferences.audioUsername.changes(),
            ) { token, username ->
                username.takeIf { token.isNotBlank() }?.ifBlank { null }
            }.collect { username ->
                _auth.update { it.copy(username = username, loading = false) }
                if (username != null) {
                    viewModelScope.launchIO {
                        accountSync.synchronize()
                        if (_state.value.tab == AudioBrowseTab.FAVORITES) loadLocalFavorites()
                    }
                }
            }
        }
    }

    fun initialize(initialFilter: String?, category: AudioCategoryRef? = null, sort: AudioSort? = null) {
        if (initialized) return
        initialized = true
        // Always the work list's sort: a keyword or dictionary result is one specific set, shown
        // on the list side of the model even when no tab row is on screen to name it.
        if (sort != null) workSort.value = sort
        categoryRef = category
        if (categoryRef != null) {
            // Not search(): the filter is an id, not a keyword, so there is nothing to put in the
            // search field. The list simply starts narrowed to one dictionary entry.
            switchTo(_state.value.tab, sortOf(_state.value.tab).value, null)
        } else if (initialFilter != null) {
            search(initialFilter)
        } else {
            // Not refresh(): this runs again whenever the screen is re-entered with a fresh
            // ViewModel, and the point of the page cache is that re-entering inside the TTL
            // repaints the previous rows instead of starting over with a spinner and a request.
            switchTo(_state.value.tab, sortOf(_state.value.tab).value, null)
        }
    }

    private val recommenderUuid: String
        get() {
            val existing = basePreferences.audioRecommenderUuid.get()
            if (existing.isNotBlank()) return existing
            return java.util.UUID.randomUUID().toString().also { basePreferences.audioRecommenderUuid.set(it) }
        }

    fun setTab(tab: AudioBrowseTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        switchTo(tab, sortOf(tab).value, _state.value.query)
    }

    fun login(name: String, password: String) {
        _auth.update { it.copy(loading = true, error = null) }
        viewModelScope.launchIO {
            try {
                val response = api.login(name, password)
                basePreferences.audioAuthToken.set(response.token)
                basePreferences.audioUsername.set(response.user.name)
                response.user.recommenderUuid?.let { basePreferences.audioRecommenderUuid.set(it) }
                _auth.update { it.copy(loading = false, username = response.user.name, error = null) }
                accountSync.synchronize(force = true)
                // Recommendations are keyed by account, so everything fetched anonymously is wrong
                // the moment we are signed in.
                pageCache.clear()
                if (_state.value.tab == AudioBrowseTab.FAVORITES) loadLocalFavorites()
                if (_state.value.tab == AudioBrowseTab.RECOMMENDED) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Audio login failed" }
                _auth.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun logout() {
        accountSync.resetSession()
        basePreferences.audioAuthToken.set("")
        basePreferences.audioUsername.set("")
        _auth.update { it.copy(username = null, error = null) }
        pageCache.clear()
        // Recommendations are account specific, so what is on screen is stale the instant we
        // sign out. Other tabs do not depend on the account and would just flash for nothing.
        if (_state.value.tab == AudioBrowseTab.RECOMMENDED) refresh()
    }

    fun setSort(sort: AudioSort) {
        // Only the work-list tab and favorites honor a sort: recommendations and popular are
        // backend feeds without an order parameter, so sorting anything else means moving to the
        // work list. Favorites are sorted locally, so they keep their own tab and content.
        val tab = if (_state.value.tab != AudioBrowseTab.LATEST && _state.value.tab != AudioBrowseTab.FAVORITES) {
            AudioBrowseTab.LATEST.also { newTab -> _state.update { it.copy(tab = newTab) } }
        } else {
            _state.value.tab
        }
        // Re-picking the sort already on screen changes nothing. For a draw that is the whole
        // point: the list is a handful the user is working through, and a second tap on the
        // entry — easy to land while flipping through the sheet — would silently replace those
        // works with a different handful and they would be gone. Moving to a draw from another
        // sort is asking for one, so it rolls a seed; pulling the list is how it is rolled again.
        if (sort == sortOf(tab).value) return
        sortOf(tab).value = sort
        if (sort.isDraw) drawSeed = Random.nextLong()
        switchTo(tab, sort, _state.value.query)
    }

    fun refresh() {
        loadGeneration++
        val generation = loadGeneration
        prefetchJob?.cancel()
        // A pull to refresh is an explicit request for the newest rows, so it may replace the
        // list wherever the user happens to be.
        displayedFromCache = null
        // The one deliberate request for another draw. Dropping the pinned selection is what
        // separates it from every other reload: without this the pull would fetch a shuffled
        // page, pin it over the old one and the list would look untouched. A new seed is the other
        // half — the backend repeats the same page for a URL it has already answered, so without
        // one the fresh request below returns exactly the rows the user is trying to get away from.
        if (sortOf(_state.value.tab).value.isDraw) {
            shuffledFavoriteIds = null
            drawSeed = Random.nextLong()
        }
        viewModelScope.launchIO {
            _state.update {
                it.copy(
                    loading = it.works.isEmpty(),
                    refreshing = it.works.isNotEmpty(),
                    error = false,
                    errorMessage = null,
                    loadMoreError = false,
                    page = 1,
                )
            }
            // A pull to refresh asks for what the backend has right now, so neither cache may
            // answer it. The work feed is stored on disk for five minutes
            // ([AudioCacheInterceptor.MAX_AGE_SECONDS]), and a random draw is repeated by the
            // backend itself unless it is handed a new seed, so either one winning the request
            // used to come back with the very rows on screen — invisible on a release-ordered
            // list, and fatal on a random one, whose whole point is a new draw.
            loadPage(1, generation, forceNetwork = true)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.tab == AudioBrowseTab.FAVORITES) return
        if (current.loading || current.refreshing || current.loadingMore || current.error) return
        // Measured against what has been requested rather than the deduplicated row count, which
        // lags behind whenever the backend repeats a work across two pages.
        if (current.page * PAGE_SIZE >= current.totalCount) return
        val baseKey = cacheKeyFor(current.tab, sortOf(current.tab).value, current.query)
        val nextPage = current.page + 1
        // A page warmed up half an hour ago is not worth showing: by the time the user reaches
        // the bottom they expect newer rows than that.
        val cached = pageCache.get(pageKey(baseKey, nextPage))?.takeIf(::isFresh)
        if (cached != null) {
            _state.update {
                it.copy(
                    works = (it.works + cached.works).distinctBy { work -> work.id },
                    page = nextPage,
                    totalCount = cached.totalCount,
                    loadMoreError = false,
                )
            }
            return
        }
        _state.update { it.copy(loadingMore = true, loadMoreError = false) }
        val generation = loadGeneration
        viewModelScope.launchIO { loadPage(nextPage, generation) }
    }

    fun search(query: String) {
        if (_state.value.tab == AudioBrowseTab.FAVORITES) {
            _state.update { it.copy(query = query.ifBlank { null }) }
            loadLocalFavorites()
            return
        }
        val normalized = query.ifBlank { null }
        _state.update { it.copy(query = normalized) }
        switchTo(_state.value.tab, sortOf(_state.value.tab).value, normalized)
    }

    fun exitSearch() {
        if (_state.value.query != null) {
            _state.update { it.copy(query = null) }
            switchTo(_state.value.tab, sortOf(_state.value.tab).value, null)
        }
    }

    /**
     * Moves to another (tab, sort, keyword) combination, showing a cached page when there is one.
     *
     * A hit paints immediately; whether it is then revalidated depends on [isFresh]. This is not a
     * social feed — a handful of works are released per day — so going back to the backend on
     * every single visit would spend a round trip on data that has almost certainly not moved.
     * [AudioPageCache.MAX_AGE] is the compromise: short enough that anything released during a session
     * still shows up, long enough that flipping between tabs stops costing requests.
     *
     * A miss has to clear the rows first: leaving the previous tab's works on screen under the new
     * tab's header looks like the sort silently failed.
     */
    private fun switchTo(tab: AudioBrowseTab, sort: AudioSort, query: String?) {
        loadGeneration++
        val generation = loadGeneration
        prefetchJob?.cancel()
        viewModelScope.launchIO {
            val cached = pageCache.get(pageKey(cacheKeyFor(tab, sort, query), 1))
            if (cached != null) {
                displayedFromCache = cached.works
                val stale = !isFresh(cached)
                _state.update {
                    it.copy(
                        works = cached.works,
                        totalCount = cached.totalCount,
                        page = 1,
                        loading = false,
                        refreshing = stale,
                        error = false,
                        errorMessage = null,
                        loadMoreError = false,
                    )
                }
                // A fresh hit needs no request at all, so the page is left exactly as painted.
                if (!stale) return@launchIO
            } else {
                displayedFromCache = null
                _state.update {
                    it.copy(
                        works = emptyList(),
                        page = 1,
                        loading = true,
                        refreshing = false,
                        error = false,
                        errorMessage = null,
                        loadMoreError = false,
                    )
                }
            }
            loadPage(1, generation)
        }
    }

    /**
     * Builds a `$circle:xxx$` search keyword from the user's most recent listen/playlist entry,
     * so the "recommended" tab is personalized by taste instead of returning the same list as
     * "popular". Returns null when there is no local taste signal yet.
     */
    private fun personalCircleKeyword(): String? {
        val circle = favoriteStore.load().firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: historyStore.load().firstOrNull()?.item?.circleName?.takeIf { it.isNotBlank() }
            ?: playlistStore.load().firstOrNull()?.circleName?.takeIf { it.isNotBlank() }
            ?: return null
        return "\$circle:$circle\$"
    }

    /**
     * Identifies what is on screen. The account is part of it because the recommended feed is
     * personalized, and the taste keyword too: without it a newly collected circle would keep
     * serving the list fetched before the user had any history at all.
     *
     * A draw carries its seed: two draws are different pages that must not overwrite each other,
     * and going back to the tab has to repaint the draw the user took rather than an older one.
     */
    private fun cacheKeyFor(tab: AudioBrowseTab, sort: AudioSort, query: String?): String {
        val account = if (basePreferences.audioAuthToken.get().isBlank()) "anon" else "user"
        // A category page is identified by its id, so it must not collide with the same tab left
        // unfiltered — and it has no keyword, so the taste fallback below would only add noise.
        val category = categoryRef?.let { "${it.field.pathSegment}:${it.id}" }.orEmpty()
        val keyword = query.orEmpty().trim().ifBlank {
            if (categoryRef != null) {
                ""
            } else if (tab == AudioBrowseTab.RECOMMENDED && account == "anon") {
                personalCircleKeyword().orEmpty()
            } else {
                ""
            }
        }
        val draw = if (sort.isDraw) "seed=$drawSeed" else ""
        return "$account|${tab.name}|${sort.name}|$category|$keyword|$draw"
    }

    private fun pageKey(baseKey: String, page: Int) = "$baseKey#$page"

    private fun isFresh(snapshot: AudioPageSnapshot): Boolean = pageCache.isFresh(snapshot)

    private suspend fun loadPage(page: Int, generation: Int, forceNetwork: Boolean = false) {
        val query = _state.value.query
        val currentTab = _state.value.tab
        val currentSort = sortOf(currentTab).value
        if (currentTab == AudioBrowseTab.FAVORITES) {
            loadLocalFavorites()
            return
        }
        val baseKey = cacheKeyFor(currentTab, currentSort, query)
        try {
            val response = requestPage(currentTab, currentSort, query, page, forceNetwork)
            _state.update { current ->
                if (generation != loadGeneration) return@update current
                // A background refresh of the first page must not yank the list back to the top
                // when the user has already scrolled into it. Appending a page replaces `works`
                // with a new instance, so an unchanged reference means the rows are untouched:
                // keep them and let the fresh page take effect on the next visit instead.
                if (page == 1 && displayedFromCache != null && current.works !== displayedFromCache) {
                    return@update current.copy(refreshing = false)
                }
                val merged = if (page == 1) {
                    response.works
                } else {
                    (current.works + response.works).distinctBy { it.id }
                }
                current.copy(
                    loading = false,
                    refreshing = false,
                    error = false,
                    loadingMore = false,
                    loadMoreError = false,
                    works = merged,
                    page = page,
                    totalCount = response.pagination.totalCount,
                )
            }
            if (generation == loadGeneration) {
                cachePage(baseKey, page, response, currentSort)
                if (page == 1) prefetchNextPage(baseKey, currentTab, currentSort, query, generation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Audio browse load failed" }
            _state.update { current ->
                if (generation != loadGeneration) return@update current
                current.copy(
                    loading = false,
                    refreshing = false,
                    loadingMore = false,
                    error = current.works.isEmpty(),
                    loadMoreError = page > 1 && current.works.isNotEmpty(),
                    errorMessage = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /** Picks the request that backs the given (tab, sort, keyword, page). */
    private suspend fun requestPage(
        tab: AudioBrowseTab,
        sort: AudioSort,
        query: String?,
        page: Int,
        forceNetwork: Boolean = false,
    ): WorksResponse {
        // Only the two work feeds are ever served from the disk cache; searches already ask for
        // the network and the recommender endpoints are POSTs, which OkHttp never stores.
        val cache = if (forceNetwork) CacheControl.FORCE_NETWORK else null
        // Only a draw carries a seed, and every page of the same draw carries the same one.
        val seed = drawSeed.takeIf { sort.isDraw }
        val effectiveKeyword = query.orEmpty().trim()
        // A keyword typed on a category results page narrows that category instead of escaping it:
        // the entry's filter is combined with the keyword, which the backend parses as an AND.
        // Measured against the live API: `$circle:072LABO$ 舔耳 淫语` answers 45 of that circle's
        // 108 works, all 45 inside it, versus 12164 / 9462 for the bare words catalogue-wide.
        categoryRef?.let { ref ->
            if (effectiveKeyword.isBlank()) {
                return api.fetchCategoryWorks(ref.field, ref.id, page, PAGE_SIZE, sort.order, sort.sort, cache, seed)
            }
            // The id endpoints accept no keyword, so an intersection can only be expressed in the
            // legacy syntax. A name containing `$` would break out of the filter — the price of
            // combining the two, and the reason the unfiltered case still goes by id.
            return api.search(
                "${ref.field.legacyKeyword(ref.title)} $effectiveKeyword",
                page,
                PAGE_SIZE,
                sort.order,
                sort.sort,
                seed,
            )
        }
        if (effectiveKeyword.isNotBlank()) {
            return api.search(effectiveKeyword, page, PAGE_SIZE, sort.order, sort.sort, seed)
        }
        return when (tab) {
            AudioBrowseTab.LATEST -> api.fetchWorks(page, PAGE_SIZE, sort.order, sort.sort, cache, seed)
            AudioBrowseTab.POPULAR -> api.fetchPopular(page, PAGE_SIZE)
            AudioBrowseTab.FAVORITES -> WorksResponse()
            AudioBrowseTab.RECOMMENDED -> requestRecommended(page)
        }
    }

    private suspend fun requestRecommended(page: Int): WorksResponse {
        if (basePreferences.audioAuthToken.get().isNotBlank()) {
            // Logged-in users keep the backend's personalized recommender.
            return api.fetchRecommended(recommenderUuid, page, PAGE_SIZE)
        }
        val personalKeyword = personalCircleKeyword()
        return if (personalKeyword != null) {
            api.search(
                personalKeyword,
                page,
                PAGE_SIZE,
                AudioSort.RATING_DESC.order,
                AudioSort.RATING_DESC.sort,
            )
        } else {
            // No taste signal yet: this tab opens by default when signed out,
            // so it needs a real list. The endpoint is open anonymously.
            api.fetchPopular(page, PAGE_SIZE)
        }
    }

    /**
     * @param sort The sort the page was fetched under, which is what decides whether it is a
     * draw worth pinning. Read from the request rather than from the current state: a page that
     * arrives after the user has already moved on to another sort must not be pinned as theirs.
     */
    private fun cachePage(baseKey: String, page: Int, response: WorksResponse, sort: AudioSort) {
        pageCache.put(
            pageKey(baseKey, page),
            response.works,
            response.pagination.totalCount,
            // A draw is pinned so it is never revalidated behind the user's back, and so it is
            // the last thing evicted when the cache fills. Every other page is a view onto a
            // growing catalogue, where a newer copy is always an improvement.
            pinned = sort.isDraw,
        )
    }

    /**
     * Pulls the page after the one on screen while the user is still reading it, so the first
     * scroll to the bottom has nothing left to wait for.
     */
    private fun prefetchNextPage(
        baseKey: String,
        tab: AudioBrowseTab,
        sort: AudioSort,
        query: String?,
        generation: Int,
    ) {
        val current = _state.value
        if (current.page * PAGE_SIZE >= current.totalCount) return
        val nextPage = current.page + 1
        if (pageCache.get(pageKey(baseKey, nextPage)) != null) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launchIO {
            try {
                val response = requestPage(tab, sort, query, nextPage)
                if (generation == loadGeneration && response.works.isNotEmpty()) {
                    cachePage(baseKey, nextPage, response, sort)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed warm-up is invisible by design: the page just loads on demand when the
                // user actually scrolls to it.
            }
        }
    }

    private fun loadLocalFavorites() {
        val query = _state.value.query.orEmpty().trim()
        val filtered = favoriteStore.load()
            .asSequence()
            .filter { work ->
                query.isBlank() || listOf(
                    work.title,
                    work.name,
                    work.tags.joinToString { it.name },
                    work.vas.joinToString { it.name },
                ).any { it.contains(query, ignoreCase = true) }
            }
            .toList()
        val sorted = when (favoriteSort.value) {
            AudioSort.RELEASE_DESC, AudioSort.CREATE_DESC -> filtered.sortedByDescending { it.release.orEmpty() }
            AudioSort.RATING_DESC -> filtered.sortedByDescending { it.rateAverage2dp ?: 0.0 }
            AudioSort.DL_DESC -> filtered
            AudioSort.RANDOM -> shuffledFavorites(filtered)
        }
        _state.update {
            it.copy(
                loading = false,
                refreshing = false,
                error = false,
                errorMessage = null,
                works = sorted,
                page = 1,
                totalCount = sorted.size,
                loadingMore = false,
                loadMoreError = false,
            )
        }
    }

    /**
     * Applies the pinned random order to the favorites that are still in the list.
     *
     * This list is recomputed on every change to the store and to the search field, so shuffling
     * inline would reorder the works under the user's cursor each time they uncollected one or
     * typed a letter. Remembering the draw and walking it means the order survives all of that:
     * works added since the draw go to the front, where they are visible rather than hidden in a
     * list that has just been rearranged.
     */
    private fun shuffledFavorites(works: List<Work>): List<Work> {
        val order = shuffledFavoriteIds ?: works.shuffled().map { it.id }.also { shuffledFavoriteIds = it }
        val byId = works.associateBy { it.id }
        val drawn = order.mapNotNull { byId[it] }
        // Anything collected after the draw is not in the remembered order yet; appending would
        // bury it at the bottom, and prepending would repeat this same reordering on every
        // recompute, so it is merged into the draw from here on.
        val added = works.filter { it.id !in order }
        val merged = added + drawn
        shuffledFavoriteIds = merged.map { it.id }
        return merged
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
