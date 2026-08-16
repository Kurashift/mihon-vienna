package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.audio.AudioMiniPlayerBar
import eu.kanade.tachiyomi.data.audio.toWorkSnapshot
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun AudioMiniPlayerNavigationBar() {
    val navigator = LocalNavigator.currentOrThrow
    val controller = remember { Injekt.get<AudioPlayerController>() }

    AudioMiniPlayerBar(
        controller = controller,
        onOpenPlayer = {
            if (navigator.lastItem !is AudioPlayerScreen) {
                navigator.push(AudioPlayerScreen.current())
            }
        },
        onOpenWork = {
            controller.state.item?.let { item ->
                val currentWorkId = (navigator.lastItem as? AudioDetailScreen)?.work?.id
                if (currentWorkId != item.workId) {
                    navigator.push(AudioDetailScreen(item.toWorkSnapshot()))
                }
            }
        },
    )
}
