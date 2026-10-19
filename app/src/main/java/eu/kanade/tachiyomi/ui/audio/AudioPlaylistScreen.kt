package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioListeningTab
import eu.kanade.presentation.audio.AudioListeningTabs
import eu.kanade.presentation.audio.AudioPlaylistContent
import eu.kanade.presentation.audio.buildQueueSections
import eu.kanade.presentation.audio.folderStateKey
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
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        var tracks by remember { mutableStateOf(playlistStore.load()) }
        val sections = remember(tracks) { buildQueueSections(tracks) }

        // Explicit sets rather than a single "expanded" set: a row nobody has touched has to follow
        // the current track, while a row the user closed must stay closed even while it is current.
        var expandedSections by remember {
            mutableStateOf(basePreferences.audioQueueExpandedSections.get())
        }
        var collapsedSections by remember {
            mutableStateOf(basePreferences.audioQueueCollapsedSections.get())
        }
        var expandedFolders by remember {
            mutableStateOf(basePreferences.audioQueueExpandedFolders.get())
        }
        var collapsedFolders by remember {
            mutableStateOf(basePreferences.audioQueueCollapsedFolders.get())
        }

        // Works and folders disappear when their tracks are removed, so their old keys would
        // otherwise accumulate in preferences forever.
        LaunchedEffect(sections) {
            val liveSections = sections.mapTo(hashSetOf()) { it.key }
            val liveFolders = sections.flatMapTo(hashSetOf()) { section ->
                section.folders.map { folder -> folderStateKey(section.key, folder.path) }
            }
            val nextExpanded = expandedSections.intersect(liveSections)
            val nextCollapsed = collapsedSections.intersect(liveSections)
            val nextExpandedFolders = expandedFolders.intersect(liveFolders)
            val nextCollapsedFolders = collapsedFolders.intersect(liveFolders)
            if (nextExpanded != expandedSections || nextCollapsed != collapsedSections) {
                expandedSections = nextExpanded
                collapsedSections = nextCollapsed
                basePreferences.audioQueueExpandedSections.set(nextExpanded)
                basePreferences.audioQueueCollapsedSections.set(nextCollapsed)
            }
            if (nextExpandedFolders != expandedFolders || nextCollapsedFolders != collapsedFolders) {
                expandedFolders = nextExpandedFolders
                collapsedFolders = nextCollapsedFolders
                basePreferences.audioQueueExpandedFolders.set(nextExpandedFolders)
                basePreferences.audioQueueCollapsedFolders.set(nextCollapsedFolders)
            }
        }

        fun refresh() {
            tracks = playlistStore.load()
        }

        fun setSectionExpanded(key: String, expanded: Boolean) {
            // One work at a time: opening a second one closes whatever was open before, the way
            // an accordion does. The other keys are collapsed explicitly, because a row nobody
            // has touched would otherwise fall back to following the current track.
            val nextExpanded = if (expanded) setOf(key) else expandedSections - key
            val nextCollapsed = if (expanded) {
                sections.mapNotNullTo(hashSetOf()) { section -> section.key.takeIf { it != key } }
            } else {
                collapsedSections + key
            }
            expandedSections = nextExpanded
            collapsedSections = nextCollapsed
            basePreferences.audioQueueExpandedSections.set(nextExpanded)
            basePreferences.audioQueueCollapsedSections.set(nextCollapsed)
        }

        fun setFolderExpanded(key: String, expanded: Boolean) {
            val nextExpanded = if (expanded) expandedFolders + key else expandedFolders - key
            val nextCollapsed = if (expanded) collapsedFolders - key else collapsedFolders + key
            expandedFolders = nextExpanded
            collapsedFolders = nextCollapsed
            basePreferences.audioQueueExpandedFolders.set(nextExpanded)
            basePreferences.audioQueueCollapsedFolders.set(nextCollapsed)
        }

        fun clearSectionState() {
            expandedSections = emptySet()
            collapsedSections = emptySet()
            expandedFolders = emptySet()
            collapsedFolders = emptySet()
            basePreferences.audioQueueExpandedSections.set(emptySet())
            basePreferences.audioQueueCollapsedSections.set(emptySet())
            basePreferences.audioQueueExpandedFolders.set(emptySet())
            basePreferences.audioQueueCollapsedFolders.set(emptySet())
        }

        fun openHistory() {
            val existing = navigator.items.filterIsInstance<AudioHistoryScreen>().lastOrNull()
            if (existing != null) {
                navigator.popUntil { it === existing }
            } else {
                navigator.replace(AudioHistoryScreen())
            }
        }

        AudioPlaylistContent(
            sections = sections,
            currentItem = controller.state.item,
            isPlaying = controller.state.isPlaying,
            expandedSections = expandedSections,
            collapsedSections = collapsedSections,
            expandedFolders = expandedFolders,
            collapsedFolders = collapsedFolders,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            tabRow = {
                AudioListeningTabs(
                    selected = AudioListeningTab.QUEUE,
                    onSelect = { tab ->
                        if (tab == AudioListeningTab.HISTORY) openHistory()
                    },
                )
            },
            navigateUp = navigator::pop,
            onOpenWork = { section ->
                section.tracks.firstOrNull()?.let { navigator.push(AudioDetailScreen(it.toWorkSnapshot())) }
            },
            onClickTrack = { index ->
                if (index in tracks.indices) {
                    controller.start(tracks, index, 0)
                    AudioPlaybackService.start(context)
                }
            },
            onPlaySection = { startIndex ->
                if (startIndex in tracks.indices) {
                    controller.start(tracks, startIndex, 0)
                    AudioPlaybackService.start(context)
                }
            },
            onToggleSection = ::setSectionExpanded,
            onToggleFolder = ::setFolderExpanded,
            onRemoveTrack = { url ->
                playlistStore.removeAll(setOf(url))
                refresh()
            },
            onRemoveSelected = { urls ->
                playlistStore.removeAll(urls)
                refresh()
            },
            onPlayAll = {
                if (tracks.isNotEmpty()) {
                    controller.start(tracks, 0, 0)
                    AudioPlaybackService.start(context)
                }
            },
            onClear = {
                playlistStore.clear()
                tracks = emptyList()
                clearSectionState()
            },
        )
    }
}
