package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioBrowseContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioHistoryStore
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.Work
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AudioBrowseScreen(
    private val categoryTitle: String? = null,
    private val initialFilter: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<AudioBrowseViewModel>()
        val audioController = remember { Injekt.get<AudioPlayerController>() }
        val state by viewModel.state.collectAsState()
        val sort by viewModel.sort.collectAsState()
        val auth by viewModel.auth.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.initialize(initialFilter)
        }

        AudioBrowseContent(
            state = state,
            title = categoryTitle ?: stringResource(MR.strings.audio_title),
            sort = sort,
            auth = auth,
            audioQuality = audioController.state.audioQuality,
            showTabs = categoryTitle == null,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            onClickWork = { work -> navigator.push(AudioDetailScreen(work)) },
            onClickHistory = { navigator.push(AudioHistoryScreen()) },
            onClickCategories = { navigator.push(AudioCategoryScreen()) },
            onClickPlaylist = { navigator.push(AudioPlaylistScreen()) },
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

enum class AudioSort(
    val order: String,
    val sort: String,
    val label: StringResource,
) {
    RELEASE_DESC("release", "desc", MR.strings.audio_sort_release_desc),
    RATING_DESC("rating", "desc", MR.strings.audio_sort_rating_desc),
    DL_DESC("dl_count", "desc", MR.strings.audio_sort_dl_desc),
    CREATE_DESC("create_date", "desc", MR.strings.audio_sort_create_desc),
    RANDOM("random", "desc", MR.strings.audio_sort_random),
}

enum class AudioBrowseTab(
    val label: StringResource,
) {
    RECOMMENDED(MR.strings.audio_tab_recommended),
    POPULAR(MR.strings.audio_tab_popular),
    LATEST(MR.strings.audio_tab_latest),
    FAVORITES(MR.strings.audio_tab_favorites),
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
    val tab: AudioBrowseTab = AudioBrowseTab.LATEST,
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
) : ViewModel() {

    private val _state = MutableStateFlow(AudioBrowseState())
    val state: StateFlow<AudioBrowseState> = _state.asStateFlow()

    private val _sort = MutableStateFlow(AudioSort.RELEASE_DESC)
    val sort: StateFlow<AudioSort> = _sort.asStateFlow()

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

    fun initialize(initialFilter: String?) {
        if (initialized) return
        initialized = true
        if (initialFilter != null) {
            search(initialFilter)
        } else {
            refresh()
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
        refresh()
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
    }

    fun setSort(sort: AudioSort) {
        _sort.value = sort
        if (_state.value.tab != AudioBrowseTab.LATEST && _state.value.tab != AudioBrowseTab.FAVORITES) {
            _state.update { it.copy(tab = AudioBrowseTab.LATEST) }
        }
        refresh()
    }

    fun refresh() {
        loadGeneration++
        val generation = loadGeneration
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
            loadPage(1, generation)
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.tab == AudioBrowseTab.FAVORITES) return
        if (current.loading || current.refreshing || current.loadingMore || current.error) return
        if (current.works.size >= current.totalCount) return
        _state.update { it.copy(loadingMore = true, loadMoreError = false) }
        val generation = loadGeneration
        viewModelScope.launchIO { loadPage(current.page + 1, generation) }
    }

    fun search(query: String) {
        if (_state.value.tab == AudioBrowseTab.FAVORITES) {
            _state.update { it.copy(query = query.ifBlank { null }) }
            loadLocalFavorites()
            return
        }
        loadGeneration++
        val generation = loadGeneration
        _state.update {
            it.copy(
                query = query.ifBlank { null },
                loading = true,
                refreshing = false,
                error = false,
                errorMessage = null,
                loadMoreError = false,
                page = 1,
                works = emptyList(),
            )
        }
        viewModelScope.launchIO { loadPage(1, generation) }
    }

    fun exitSearch() {
        if (_state.value.query != null) {
            _state.update { it.copy(query = null) }
            refresh()
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

    private suspend fun loadPage(page: Int, generation: Int) {
        val query = _state.value.query
        val currentSort = _sort.value
        val currentTab = _state.value.tab
        val effectiveKeyword = query.orEmpty().trim()
        if (currentTab == AudioBrowseTab.FAVORITES) {
            loadLocalFavorites()
            return
        }
        try {
            val response = if (effectiveKeyword.isNotBlank()) {
                api.search(effectiveKeyword, page, PAGE_SIZE, currentSort.order, currentSort.sort)
            } else when (currentTab) {
                AudioBrowseTab.LATEST -> {
                    api.fetchWorks(page, PAGE_SIZE, currentSort.order, currentSort.sort)
                }
                AudioBrowseTab.POPULAR -> api.fetchPopular(page, PAGE_SIZE)
                AudioBrowseTab.FAVORITES -> return
                AudioBrowseTab.RECOMMENDED -> {
                    if (basePreferences.audioAuthToken.get().isNotBlank()) {
                        // Logged-in users keep the backend's personalized recommender.
                        api.fetchRecommended(recommenderUuid, page, PAGE_SIZE)
                    } else {
                        val personalKeyword = personalCircleKeyword()
                        if (personalKeyword != null) {
                            api.search(personalKeyword, page, PAGE_SIZE, "rating", "desc")
                        } else {
                            // No taste signal yet: fall back to top-rated works so this tab differs
                            // from "popular" (which is ranked by popularity) instead of duplicating it.
                            api.fetchWorks(page, PAGE_SIZE, "rating", "desc")
                        }
                    }
                }
            }
            _state.update { current ->
                if (generation != loadGeneration) return@update current
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
        val sorted = when (_sort.value) {
            AudioSort.RELEASE_DESC, AudioSort.CREATE_DESC -> filtered.sortedByDescending { it.release.orEmpty() }
            AudioSort.RATING_DESC -> filtered.sortedByDescending { it.rateAverage2dp ?: 0.0 }
            AudioSort.DL_DESC -> filtered
            AudioSort.RANDOM -> filtered.shuffled()
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

    private companion object {
        const val PAGE_SIZE = 30
    }
}
