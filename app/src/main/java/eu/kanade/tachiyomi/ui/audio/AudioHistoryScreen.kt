package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
        val viewModel = viewModel<AudioHistoryViewModel>()
        val groups by viewModel.groups.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.refresh()
        }

        AudioHistoryContent(
            groups = groups,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = navigator::pop,
            onClear = viewModel::clear,
            onRemoveWork = viewModel::removeWork,
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

    fun addToPlaylist(entry: AudioHistoryEntry) {
        playlistStore.addAll(listOf(entry.item))
    }
}
