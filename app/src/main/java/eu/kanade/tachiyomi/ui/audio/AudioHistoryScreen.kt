package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.audio.AudioHistoryContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioHistoryEntry
import eu.kanade.tachiyomi.data.audio.AudioHistoryGroup
import eu.kanade.tachiyomi.data.audio.AudioHistoryStore
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.toWorkSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AudioHistoryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = viewModel<AudioHistoryViewModel>()
        val controller = remember { Injekt.get<AudioPlayerController>() }
        val groups by viewModel.groups.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.refresh()
        }

        AudioHistoryContent(
            groups = groups,
            currentWorkId = controller.state.item?.workId,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = navigator::pop,
            onClear = viewModel::clear,
            onRemoveWork = viewModel::removeWork,
            onRemoveSelected = viewModel::removeSelected,
            onPlayAll = {
                val entries = viewModel.playAllOrder()
                if (entries.isNotEmpty()) {
                    controller.start(entries.map { it.item }, 0, entries.first().positionMs)
                    AudioPlaybackService.start(context)
                }
            },
            onAddToPlaylist = viewModel::addToPlaylist,
            onOpenWork = { group ->
                navigator.push(AudioDetailScreen(group.latest.item.toWorkSnapshot()))
            },
            onClickEntry = { entry ->
                navigator.push(AudioPlayerScreen(listOf(entry.item), 0, entry.positionMs))
            },
        )
    }
}

class AudioHistoryViewModel(
    private val historyStore: AudioHistoryStore = Injekt.get(),
    private val playlistStore: AudioPlaylistStore = Injekt.get(),
) : ViewModel() {

    private val _groups = MutableStateFlow<List<AudioHistoryGroup>>(emptyList())
    val groups: StateFlow<List<AudioHistoryGroup>> = _groups.asStateFlow()

    fun refresh() {
        _groups.value = historyStore.loadGrouped()
    }

    fun clear() {
        viewModelScope.launch {
            historyStore.clear()
            _groups.value = emptyList()
        }
    }

    fun removeWork(group: AudioHistoryGroup) {
        historyStore.removeWork(group.workId, group.workTitle)
        refresh()
    }

    /** Removes every selected row, matched by the same key the list uses for them. */
    fun removeSelected(keys: Set<String>) {
        if (keys.isEmpty()) return
        _groups.value
            .filter { it.key in keys }
            .forEach { historyStore.removeWork(it.workId, it.workTitle) }
        refresh()
    }

    /**
     * The works in the order "play all" queues them: most recently played first, one entry per
     * work — the same entry each row continues from.
     */
    fun playAllOrder(): List<AudioHistoryEntry> =
        _groups.value.sortedByDescending { it.latest.lastPlayedAt }.map { it.latest }

    fun addToPlaylist(entry: AudioHistoryEntry) {
        playlistStore.addAll(listOf(entry.item))
    }
}
