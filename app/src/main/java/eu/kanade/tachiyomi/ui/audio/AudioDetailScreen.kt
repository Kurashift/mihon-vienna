package eu.kanade.tachiyomi.ui.audio

import android.app.Activity
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
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
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.TrackNode
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.audio.buildAudioTrackCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

        fun navigateBack() {
            navigator.pop()
            if (finishActivityOnBack) activity?.finish()
        }

        if (finishActivityOnBack) {
            BackHandler(onBack = ::navigateBack)
        }

        AudioDetailContent(
            work = work,
            state = state,
            playlistUrls = playlistUrls,
            isFavorite = isFavorite,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = ::navigateBack,
            onRetry = { viewModel.load(work) },
            onPlayAll = {
                if (state.flatTracks.isNotEmpty()) navigator.push(AudioPlayerScreen(state.flatTracks, 0))
            },
            onClickTrack = { index -> navigator.push(AudioPlayerScreen(state.flatTracks, index)) },
            onTogglePlaylist = { item ->
                toastPlaylistChange(viewModel.toggleInPlaylist(item))
            },
            onToggleWorkPlaylist = {
                toastPlaylistChange(viewModel.toggleWorkPlaylist(state.flatTracks))
            },
            onToggleFolderPlaylist = { folderPath ->
                toastPlaylistChange(viewModel.toggleFolderPlaylist(folderPath))
            },
            onToggleFavorite = { viewModel.toggleFavorite(work) },
            onClickCircle = { name ->
                navigator.push(AudioBrowseScreen(categoryTitle = name, initialFilter = "\$circle:$name\$"))
            },
            onClickVa = { name ->
                navigator.push(AudioBrowseScreen(categoryTitle = name, initialFilter = "\$va:$name\$"))
            },
            onClickTag = { name ->
                navigator.push(AudioBrowseScreen(categoryTitle = name, initialFilter = "\$tag:$name\$"))
            },
        )
    }
}

data class AudioDetailState(
    val loading: Boolean = false,
    val error: Boolean = false,
    val errorMessage: String? = null,
    val rootNodes: List<TrackNode> = emptyList(),
    val flatTracks: List<AudioPlayItem> = emptyList(),
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
        _isFavorite.value = favoriteStore.contains(work.id)
        loadGeneration++
        val generation = loadGeneration
        viewModelScope.launchIO {
            _state.update {
                it.copy(loading = true, error = false, errorMessage = null, rootNodes = emptyList(), flatTracks = emptyList())
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
                _state.update { current ->
                    if (generation != loadGeneration) return@update current
                    current.copy(loading = false, rootNodes = catalog.rootNodes, flatTracks = flatTracks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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

    fun toggleFavorite(work: Work) {
        val isFavorite = favoriteStore.toggle(work)
        _isFavorite.value = isFavorite
        viewModelScope.launchIO {
            accountSync.updateProgress(work.id, AudioAccountProgress.MARKED.takeIf { isFavorite })
        }
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
