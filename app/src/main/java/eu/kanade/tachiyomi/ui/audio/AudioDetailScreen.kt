package eu.kanade.tachiyomi.ui.audio

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import eu.kanade.tachiyomi.data.audio.AudioCategoryCache
import eu.kanade.tachiyomi.data.audio.AudioCategoryField
import eu.kanade.tachiyomi.data.audio.AudioCategoryRef
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.AudioQualityMode
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.TrackNode
import eu.kanade.tachiyomi.data.audio.Work
import eu.kanade.tachiyomi.data.audio.buildAudioTrackCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val scope = rememberCoroutineScope()

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

        // Resolve the tapped dictionary entry to a backend id (same route as the category screen,
        // cacheable id endpoint) and only fall back to the legacy $name$ parse when the on-disk
        // dictionaries cannot pin it down to a single entry.
        fun navigateCategory(field: AudioCategoryField, name: String) {
            scope.launch {
                val ref = withContext(Dispatchers.IO) { viewModel.resolveRef(field, name) }
                if (ref != null) {
                    navigator.push(AudioBrowseScreen(categoryTitle = name, initialCategory = ref))
                } else {
                    navigator.push(AudioBrowseScreen(categoryTitle = name, initialFilter = field.legacyKeyword(name)))
                }
            }
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
            onToggleFavorite = { viewModel.toggleFavorite(work) },
            onClickCircle = { name -> navigateCategory(AudioCategoryField.CIRCLE, name) },
            onClickVa = { name -> navigateCategory(AudioCategoryField.VA, name) },
            onClickTag = { name -> navigateCategory(AudioCategoryField.TAG, name) },
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
    private val categoryCache: AudioCategoryCache = Injekt.get(),
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
                it.copy(
                    loading = true,
                    error = false,
                    errorMessage = null,
                    rootNodes = emptyList(),
                    flatTracks = emptyList(),
                )
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

    /**
     * Ensures [items] are all present in the shared playlist, keeping any track already queued.
     * Silent on purpose: the folder rows turning into their checked state is the feedback.
     */
    fun enqueueFolder(items: List<AudioPlayItem>) {
        if (items.isEmpty()) return
        playlistStore.addAll(items)
        refreshPlaylistState()
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

    /**
     * Resolves a dictionary entry name to its backend id so the detail page navigates the same way
     * as the category screen: an id filter on the cacheable endpoint instead of re-parsing the
     * name server-side. Returns null when the name is missing from the on-disk dictionaries or
     * matches more than one entry, in which case the caller keeps the legacy `$name$` route — the
     * fallback never regresses, it just skips the optimisation.
     */
    suspend fun resolveRef(field: AudioCategoryField, name: String): AudioCategoryRef? {
        val snapshot = categoryCache.read() ?: return null
        return when (field) {
            AudioCategoryField.CIRCLE ->
                snapshot.circles
                    .filter { it.name == name }
                    .singleOrNull()
                    ?.let { AudioCategoryRef(field, it.id.toString(), name) }
            AudioCategoryField.VA ->
                snapshot.vas
                    .filter { it.name == name }
                    .singleOrNull()
                    ?.let { AudioCategoryRef(field, it.id, name) }
            AudioCategoryField.TAG ->
                snapshot.tags
                    .filter { it.name == name }
                    .singleOrNull()
                    ?.let { AudioCategoryRef(field, it.id.toString(), name) }
        }
    }
}
