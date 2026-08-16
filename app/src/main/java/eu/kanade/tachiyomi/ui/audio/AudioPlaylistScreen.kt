package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.audio.AudioPlaylistContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioPlaylistStore
import eu.kanade.tachiyomi.data.audio.toWorkSnapshot
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AudioPlaylistScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val playlistStore = remember { Injekt.get<AudioPlaylistStore>() }
        val controller = remember { Injekt.get<AudioPlayerController>() }
        var groups by remember { mutableStateOf(playlistStore.loadGrouped()) }

        fun refresh() {
            groups = playlistStore.loadGrouped()
        }

        AudioPlaylistContent(
            groups = groups,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = navigator::pop,
            onOpenWork = { group ->
                group.tracks.firstOrNull()?.let { navigator.push(AudioDetailScreen(it.toWorkSnapshot())) }
            },
            onClickTrack = { group, index ->
                if (index in group.tracks.indices) {
                    controller.start(group.tracks, index, 0)
                    AudioPlaybackService.start(context)
                }
            },
            onPlayWork = { group ->
                if (group.tracks.isNotEmpty()) {
                    controller.start(group.tracks, 0, 0)
                    AudioPlaybackService.start(context)
                }
            },
            onRemoveTrack = { item ->
                playlistStore.remove(item.mediaStreamUrl)
                refresh()
            },
            onRemoveWork = { group ->
                playlistStore.removeWork(group.workId, group.workTitle)
                refresh()
            },
            onPlayAll = {
                val tracks = playlistStore.load()
                if (tracks.isNotEmpty()) {
                    controller.start(tracks, 0, 0)
                    AudioPlaybackService.start(context)
                }
            },
            onClear = {
                playlistStore.clear()
                groups = emptyList()
            },
        )
    }
}
