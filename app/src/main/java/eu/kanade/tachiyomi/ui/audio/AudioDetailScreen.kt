package eu.kanade.tachiyomi.ui.audio

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioDetailContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioAccountProgress
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioCategoryField
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.TrackNode
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.audio.buildAudioTrackCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AudioDetailScreen(
    internal val work: Work,
    private val finishActivityOnBack: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val activity = LocalContext.current as? Activity
        val viewModel = viewModel<AudioDetailViewModel>()
        val state by viewModel.state.collectAsState()
        val playlistUrls by viewModel.playlistUrls.collectAsState()
        val isFavorite by viewModel.isFavorite.collectAsState()
        val addedToPlaylistText = stringResource(MR.strings.audio_added_to_playlist)
        val removedFromPlaylistText = stringResource(MR.strings.audio_removed_from_playlist)

        fun toastPlaylistChange(added: Boolean) {
            Toast.makeText(
                activity,
                if (added) addedToPlaylistText else removedFromPlaylistText,
                Toast.LENGTH_SHORT,
            ).show()
        }

        LaunchedEffect(work.id) {
            viewModel.load(work)
        }

        // Playback paths enter with a bare snapshot; this is what the header shows until the
        // metadata fetch fills the tags in, and what gets collected so favourites keep theirs.
        val headerWork = state.work ?: work

        fun navigateBack() {
            navigator.pop()
            if (finishActivityOnBack) activity?.finish()
        }

        val onOpenCategory = rememberCategoryNavigator(navigator)

        if (finishActivityOnBack) {
            BackHandler(onBack = ::navigateBack)
        }

        AudioDetailContent(
            work = headerWork,
            state = state,
            playlistUrls = playlistUrls,
            isFavorite = isFavorite,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = ::navigateBack,
            onClickHome = {
                // Reuse the home screen already in the stack when there is one, otherwise the
                // reader hand-off would strand the user on a page with no way upstream.
                val home = navigator.items.filterIsInstance<AudioBrowseScreen>()
                    .lastOrNull { it.categoryTitle == null }
                if (home != null) {
                    navigator.popUntil { it === home }
                } else {
                    navigator.push(AudioBrowseScreen())
                }
            },
            onRetry = { viewModel.load(work) },
            onClickTrack = { index ->
                // Tapping one track plays its folder, not the whole work: tracks are grouped by
                // disc/chapter, so the neighbour tracks are the ones that belong to this listen.
                // The folder also lands in the shared playlist, which is what keeps playing once
                // the player is closed. Tracks sitting at the work root have an empty folderPath
                // and simply group with the other root tracks.
                val target = state.flatTracks[index]
                val folderTracks = state.flatTracks.filter { it.folderPath == target.folderPath }
                val startIndex = folderTracks
                    .indexOfFirst { it.mediaStreamUrl == target.mediaStreamUrl }
                    .coerceAtLeast(0)
                viewModel.enqueueFolder(folderTracks)
                navigator.push(AudioPlayerScreen(folderTracks, startIndex))
            },
            onTogglePlaylist = { item ->
                toastPlaylistChange(viewModel.toggleInPlaylist(item))
            },
            onToggleWorkPlaylist = {
                toastPlaylistChange(viewModel.toggleWorkPlaylist(state.flatTracks))
            },
            onToggleFolderPlaylist = { folderPath ->
                toastPlaylistChange(viewModel.toggleFolderPlaylist(folderPath))
            },
            onToggleFavorite = { viewModel.toggleFavorite(headerWork) },
            onTitleSearch = { query ->
                // The query's own result page, the same shape a tag chip lands on: one list
                // narrowed by keyword. Comes in as the whole title on tap, or as the
                // hand-picked text from the title's selection menu.
                if (query.isNotBlank()) {
                    navigator.push(AudioBrowseScreen(categoryTitle = query, initialFilter = query))
                }
            },
            onClickCircle = { name -> onOpenCategory(AudioCategoryField.CIRCLE, name) },
            onClickVa = { name -> onOpenCategory(AudioCategoryField.VA, name) },
            onClickTag = { name -> onOpenCategory(AudioCategoryField.TAG, name) },
            onClickNeighbor = { neighbor -> navigator.push(AudioDetailScreen(neighbor)) },
        )
    }
}

data class AudioDetailState(
    val loading: Boolean = false,
    val error: Boolean = false,
    val errorMessage: String? = null,
    val rootNodes: List<TrackNode> = emptyList(),
    val flatTracks: List<AudioPlayItem> = emptyList(),
    /**
     * The work the header renders. Entries from the playback paths only carry a bare snapshot,
     * so [AudioDetailViewModel.load] fills this in from the backend when its tags are missing.
     */
    val work: Work? = null,
    /**
     * What the backend considers similar to [work], shown as one horizontal line under the track
     * list. Fetched separately from the tracks so a slow recommender cannot hold the page back,
     * and left empty when it fails — the page is complete without it.
     */
    val neighbors: List<Work> = emptyList(),
)

class AudioDetailViewModel(
    private val api: KikoeruApi = Injekt.get(),
    private val playlistStore: AudioPlaylistStore = Injekt.get(),
    private val favoriteStore: AudioFavoriteStore = Injekt.get(),
    private val accountSync: AudioAccountSync = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(AudioDetailState())
    val state: StateFlow<AudioDetailState> = _state.asStateFlow()

    private val _playlistUrls = MutableStateFlow<Set<String>>(emptySet())
    val playlistUrls: StateFlow<Set<String>> = _playlistUrls.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private var loadGeneration = 0

    fun load(work: Work) {
        _isFavorite.value = isFavorite(work)
        loadGeneration++
        val generation = loadGeneration
        // Synchronous, ahead of every coroutine: the header must know which work is on screen
        // before the metadata fetch below can possibly answer.
        _state.update { it.copy(work = work) }
        // The work feeds attach tags and VAs to every row, but entries from the player, the mini
        // bar, history, playlists or the reader hand-off only carry a bare snapshot — those pages
        // used to show up without their tags. One call fills them in; failing is silent because
        // the page is fully usable without the chips.
        if (work.tags.isEmpty()) {
            viewModelScope.launchIO {
                try {
                    val full = api.fetchWork(work.id)
                    _state.update { current ->
                        if (current.work?.id != work.id) return@update current
                        current.copy(work = full)
                    }
                    // The fetched work carries the account state the feed row could not, so the
                    // heart settles on it rather than on the bare snapshot it opened with.
                    if (_state.value.work?.id == work.id) _isFavorite.value = isFavorite(full)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Audio work metadata fetch failed" }
                }
            }
        }
        viewModelScope.launchIO {
            _state.update {
                it.copy(
                    loading = true,
                    error = false,
                    errorMessage = null,
                    rootNodes = emptyList(),
                    flatTracks = emptyList(),
                    neighbors = emptyList(),
                )
            }
            // Started first and awaited next to the tracks: the live backend answers this in about
            // the same second the track list takes, so waiting costs a fraction of a second and the
            // row is already there when the page paints instead of popping in once it has settled.
            // The budget exists only so a stalled recommender cannot hold the whole page.
            val neighbors = async {
                withTimeoutOrNull(NEIGHBOR_BUDGET_MS) {
                    try {
                        api.fetchNeighbors(work.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "Audio neighbour fetch failed" }
                        emptyList()
                    }
                }
            }
            try {
                val nodes = api.fetchTracks(work.id)
                val quality = AudioQualityMode.fromPreference(basePreferences.audioQuality.get())
                val catalog = nodes.buildAudioTrackCatalog(work, quality)
                val flatTracks = catalog.tracks
                val storedUrls = playlistStore.load().mapTo(hashSetOf()) { it.mediaStreamUrl }
                _playlistUrls.value = flatTracks
                    .filter { it.mediaStreamUrl in storedUrls }
                    .map { it.mediaStreamUrl }
                    .toSet()
                val similar = neighbors.await().orEmpty()
                _state.update { current ->
                    if (generation != loadGeneration) return@update current
                    current.copy(
                        loading = false,
                        rootNodes = catalog.rootNodes,
                        flatTracks = flatTracks,
                        neighbors = similar,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                neighbors.cancel()
                logcat(LogPriority.ERROR, e) { "Audio detail load failed" }
                _state.update { current ->
                    if (generation != loadGeneration) return@update current
                    current.copy(loading = false, error = true, errorMessage = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun toggleInPlaylist(item: AudioPlayItem): Boolean {
        val added = playlistStore.toggle(item)
        refreshPlaylistState()
        return added
    }

    fun toggleWorkPlaylist(items: List<AudioPlayItem>): Boolean {
        if (items.isEmpty()) return false
        val queuedUrls = playlistStore.load().mapTo(hashSetOf()) { it.mediaStreamUrl }
        return if (items.all { it.mediaStreamUrl in queuedUrls }) {
            playlistStore.removeWork(items.first().workId, items.first().workTitle)
            refreshPlaylistState()
            false
        } else {
            val added = playlistStore.addAll(items) > 0
            refreshPlaylistState()
            added
        }
    }

    fun toggleFolderPlaylist(folderPath: String): Boolean {
        val items = _state.value.flatTracks.filter { it.folderPath == folderPath }
        if (items.isEmpty()) return false
        val queuedUrls = playlistStore.load().mapTo(hashSetOf()) { it.mediaStreamUrl }
        return if (items.all { it.mediaStreamUrl in queuedUrls }) {
            playlistStore.removeAll(items.map { it.mediaStreamUrl })
            refreshPlaylistState()
            false
        } else {
            val added = playlistStore.addAll(items) > 0
            refreshPlaylistState()
            added
        }
    }

    /**
     * Ensures [items] are all present in the shared playlist, keeping any track already queued.
     * Silent on purpose: the folder rows turning into their checked state is the feedback.
     */
    fun enqueueFolder(items: List<AudioPlayItem>) {
        if (items.isEmpty()) return
        playlistStore.addAll(items)
        refreshPlaylistState()
    }

    /**
     * Collects or drops [work] on the account, keeping the local copy as the offline answer.
     *
     * The button moves first and is put back when the write does not land: a mark that silently
     * never reached the account is what made the site and the app disagree in the first place.
     */
    fun toggleFavorite(work: Work) {
        val mark = !_isFavorite.value
        _isFavorite.value = mark
        if (mark) favoriteStore.add(work) else favoriteStore.remove(work.id)
        if (!accountSync.isSignedIn()) return
        viewModelScope.launchIO {
            if (accountSync.setMarked(work, mark)) return@launchIO
            _isFavorite.value = !mark
            if (mark) favoriteStore.remove(work.id) else favoriteStore.add(work)
        }
    }

    /**
     * Whether the account holds [work] as marked.
     *
     * Once the account has answered, its answer is the only one that counts. The state carried by
     * the work itself is a snapshot from whenever the list was fetched, so falling back to it after
     * an uncollect would read the state that was just deleted and put the heart straight back —
     * and the next tap would then re-create the row on the account.
     */
    private fun isFavorite(work: Work): Boolean {
        if (!accountSync.isSignedIn()) return favoriteStore.contains(work.id)
        if (accountSync.hasLoaded() || accountSync.progressOf(work.id) != null) {
            return accountSync.isMarked(work.id)
        }
        return AudioAccountProgress.fromWire(work.progress) == AudioAccountProgress.MARKED
    }

    private fun refreshPlaylistState() {
        val tracks = _state.value.flatTracks
        val storedUrls = playlistStore.load().mapTo(hashSetOf()) { it.mediaStreamUrl }
        _playlistUrls.value = tracks
            .filter { it.mediaStreamUrl in storedUrls }
            .map { it.mediaStreamUrl }
            .toSet()
    }
}

/**
 * How long the details page waits for the similar row before painting without it.
 *
 * Measured against the live backend: the request answers in about 1.1 s, the same second the track
 * list takes, so this is never reached in normal use — it only bounds a recommender that stalls.
 */
private const val NEIGHBOR_BUDGET_MS = 2_500L
