package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.audio.AudioPlayerContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioAccountProgress
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.data.audio.LyricLine
import eu.kanade.tachiyomi.data.audio.SubtitleParser
import eu.kanade.tachiyomi.data.audio.toWorkSnapshot
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AudioPlayerScreen(
    private val items: List<AudioPlayItem>,
    private val startIndex: Int,
    private val startPositionMs: Long = 0L,
    private val resumeExisting: Boolean = false,
) : Screen() {

    companion object {
        fun current(): AudioPlayerScreen {
            return AudioPlayerScreen(
                items = emptyList(),
                startIndex = 0,
                resumeExisting = true,
            )
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val controller = remember { Injekt.get<AudioPlayerController>() }
        val api = remember { Injekt.get<KikoeruApi>() }
        val favoriteStore = remember { Injekt.get<AudioFavoriteStore>() }
        val accountSync = remember { Injekt.get<AudioAccountSync>() }
        val coroutineScope = rememberCoroutineScope()

        var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
        var subtitleState by remember { mutableStateOf(AudioSubtitleState.NOT_AVAILABLE) }
        var subtitleRetry by remember { mutableStateOf(0) }
        val currentItem = controller.state.item
        val subtitleUrl = currentItem?.subtitleUrl
        var isFavorite by remember { mutableStateOf(false) }

        LaunchedEffect(controller) {
            if (!resumeExisting) {
                controller.start(items, startIndex, startPositionMs)
            }
            // Keep playing (and show a notification) after the user leaves this screen.
            AudioPlaybackService.start(context)
        }

        LaunchedEffect(currentItem?.workId) {
            isFavorite = currentItem?.let { favoriteStore.contains(it.workId) } == true
        }

        LaunchedEffect(subtitleUrl, subtitleRetry) {
            if (subtitleUrl == null) {
                lyrics = emptyList()
                subtitleState = AudioSubtitleState.NOT_AVAILABLE
                return@LaunchedEffect
            }
            subtitleState = AudioSubtitleState.LOADING
            val result = runCatching {
                withIOContext {
                    val content = api.fetchSubtitle(subtitleUrl)
                    SubtitleParser.parse(content, subtitleUrl)
                }
            }
            result.fold(
                onSuccess = { parsed ->
                    lyrics = parsed
                    subtitleState = if (parsed.isEmpty()) AudioSubtitleState.EMPTY else AudioSubtitleState.READY
                },
                onFailure = {
                    lyrics = emptyList()
                    subtitleState = AudioSubtitleState.ERROR
                },
            )
        }

        val toggleFavorite = {
            currentItem?.let { item ->
                isFavorite = favoriteStore.toggle(item.toWorkSnapshot())
                coroutineScope.launch {
                    accountSync.updateProgress(
                        item.workId,
                        AudioAccountProgress.MARKED.takeIf { isFavorite },
                    )
                }
            }
            Unit
        }

        AudioPlayerContent(
            controller = controller,
            state = controller.state,
            lyrics = lyrics,
            subtitleState = subtitleState,
            isFavorite = isFavorite,
            navigateUp = navigator::pop,
            onToggleFavorite = toggleFavorite,
            onRetrySubtitle = { subtitleRetry++ },
            onTogglePlay = controller::togglePlay,
            onSeek = controller::seekTo,
            onSeekBy = controller::seekBy,
            onNext = controller::next,
            onPrevious = controller::previous,
            onRandom = controller::random,
            onToggleLoop = controller::toggleLoop,
            onCyclePlaybackSpeed = controller::cyclePlaybackSpeed,
            onCycleAudioQuality = controller::cycleAudioQuality,
            onSetSleepTimer = controller::setSleepTimer,
        )
    }
}

enum class AudioSubtitleState {
    NOT_AVAILABLE,
    LOADING,
    READY,
    EMPTY,
    ERROR,
}
