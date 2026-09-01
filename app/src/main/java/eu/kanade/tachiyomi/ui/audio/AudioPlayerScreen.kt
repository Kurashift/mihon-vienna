package eu.kanade.tachiyomi.ui.audio

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioPlayerContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioAccountProgress
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioFavoriteStore
import eu.kanade.tachiyomi.data.audio.AudioPlayItem
import eu.kanade.tachiyomi.data.audio.AudioSubtitleDisplayMode
import eu.kanade.tachiyomi.data.audio.toWorkSnapshot
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

class AudioPlayerScreen(
    private val items: List<AudioPlayItem>,
    private val startIndex: Int,
    private val startPositionMs: Long = 0L,
    private val resumeExisting: Boolean = false,
    private val finishActivityOnBack: Boolean = false,
) : Screen() {

    companion object {
        fun current(finishActivityOnBack: Boolean = false): AudioPlayerScreen {
            return AudioPlayerScreen(
                items = emptyList(),
                startIndex = 0,
                resumeExisting = true,
                finishActivityOnBack = finishActivityOnBack,
            )
        }

        /** Matches the Material 3 expanded breakpoint, above which landscape is left free. */
        private const val TABLET_MIN_WIDTH_DP = 600
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val activity = context as? android.app.Activity
        val controller = remember { Injekt.get<AudioPlayerController>() }
        val favoriteStore = remember { Injekt.get<AudioFavoriteStore>() }
        val accountSync = remember { Injekt.get<AudioAccountSync>() }
        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val coroutineScope = rememberCoroutineScope()

        val subtitleDisplayMode by basePreferences.audioSubtitleDisplayMode.collectPreferenceAsState()
        val displayMode = AudioSubtitleDisplayMode.fromPreference(subtitleDisplayMode)
        val floatingSubtitleEnabled by basePreferences.audioFloatingSubtitle.collectPreferenceAsState()
        val floatingSubtitleLocked by basePreferences.audioFloatingSubtitleLocked.collectPreferenceAsState()

        val currentItem = controller.state.item
        var isFavorite by remember { mutableStateOf(false) }

        // When the reader handed playback over, going up has to tear down this Activity so the
        // reader underneath is revealed again.
        fun navigateBack() {
            navigator.pop()
            if (finishActivityOnBack) activity?.finish()
        }

        if (finishActivityOnBack) {
            BackHandler(onBack = ::navigateBack)
        }

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

        // Subtitles are owned by the controller so they survive leaving this screen, but a track
        // restored from history only reaches the controller without ever being played. Asking for
        // it here is free when it is already loaded, and keeps the restored track from looking
        // like it has no transcript until the first play.
        LaunchedEffect(currentItem?.mediaStreamUrl) {
            controller.ensureSubtitlesLoaded()
        }

        val lifecycleOwner = LocalLifecycleOwner.current

        // Floating subtitles hide only while this page is the one being read, and staying
        // composed is not the same thing: pressing Home keeps the composition alive while the
        // user is looking at the launcher, where the subtitles are wanted again. The activity's
        // own visibility is what settles it, and started is exactly the point where Android
        // calls a screen visible, which also covers the unfocused half of a split screen.
        DisposableEffect(lifecycleOwner) {
            val lifecycle = lifecycleOwner.lifecycle
            fun sync() {
                controller.notifyPlayerScreenVisibility(
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                )
            }
            val observer = LifecycleEventObserver { _, _ -> sync() }
            lifecycle.addObserver(observer)
            // The observer only reacts from here on, so the state this page starts in is read once.
            sync()
            onDispose {
                lifecycle.removeObserver(observer)
                controller.notifyPlayerScreenVisibility(false)
            }
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

        // The player is laid out as a single column: on a phone in landscape the 300dp or so of
        // height cannot fit the cover, the lyrics and the controls at once, so the lower half is
        // simply pushed off screen. A side by side layout does not rescue it either, because the
        // controls alone need about 244dp and leave nothing for the lyrics — so phones are held
        // portrait instead, the way every phone music player does it.
        //
        // USER_PORTRAIT rather than PORTRAIT so a device level rotation lock is respected: the page
        // asks for portrait, it does not force the screen around. Tablets are left alone since
        // there is no landscape layout to fall back on and they have the room anyway.
        val configuration = LocalConfiguration.current
        DisposableEffect(Unit) {
            val previousOrientation = activity?.requestedOrientation
            if (configuration.smallestScreenWidthDp < TABLET_MIN_WIDTH_DP) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
            onDispose {
                activity?.requestedOrientation = previousOrientation
                    ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        AudioPlayerContent(
            controller = controller,
            state = controller.state,
            lyrics = controller.state.lyrics,
            subtitleState = controller.state.subtitleState,
            displayMode = displayMode,
            isFavorite = isFavorite,
            floatingSubtitleEnabled = floatingSubtitleEnabled,
            navigateUp = ::navigateBack,
            onToggleFavorite = toggleFavorite,
            onRetrySubtitle = controller::retrySubtitles,
            onCycleSubtitleDisplayMode = {
                basePreferences.audioSubtitleDisplayMode.set(displayMode.next().preferenceValue)
            },
            // A locked window is touch-through, so the only way back into it from here is this
            // button. The first tap therefore unlocks instead of closing: closing a window the
            // user cannot reach into would look like the tap did nothing.
            onToggleFloatingSubtitle = {
                when {
                    !floatingSubtitleEnabled -> basePreferences.audioFloatingSubtitle.set(true)
                    floatingSubtitleLocked -> basePreferences.audioFloatingSubtitleLocked.set(false)
                    else -> basePreferences.audioFloatingSubtitle.set(false)
                }
            },
            onOpenWorkDetail = {
                currentItem?.toWorkSnapshot()?.let { snapshot ->
                    // Stepping back to an existing details screen keeps the real upstream history
                    // (including the reader hand-off). Only build a fresh one when the player was
                    // opened from somewhere that never had the details in its stack.
                    val existing = navigator.items.filterIsInstance<AudioDetailScreen>()
                        .lastOrNull { it.work.id == snapshot.id }
                    if (existing != null) {
                        navigator.popUntil { it === existing }
                    } else {
                        navigator.replace(AudioDetailScreen(snapshot))
                    }
                }
            },
            onTogglePlay = controller::togglePlay,
            onSeek = controller::seekTo,
            onSeekBy = controller::seekBy,
            onNext = controller::next,
            onPrevious = controller::previous,
            onToggleLoop = controller::toggleLoop,
            onCyclePlaybackSpeed = controller::cyclePlaybackSpeed,
            onCycleAudioQuality = controller::cycleAudioQuality,
            onSetSleepTimer = controller::setSleepTimer,
        )
    }
}
