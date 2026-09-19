package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A short-lived notice shown over the screen, sized to its text.
 *
 * Unlike a platform toast this never queues: [show] replaces whatever is on screen and restarts
 * the timer, so tapping through a row of controls reads as one message changing rather than as a
 * backlog played out one after another. It carries no action and is deliberately brief, which
 * makes it fit feedback on a tap the user just made and nothing else.
 */
@Stable
class TransientNoticeState {
    internal var current by mutableStateOf<Notice?>(null)
        private set

    private var nextId = 0L

    /** Show [text], replacing any notice still on screen. */
    fun show(text: String) {
        current = Notice(++nextId, text)
    }

    /** Take the notice down, if one is up. */
    internal fun dismiss() {
        current = null
    }

    internal data class Notice(val id: Long, val text: String)
}

@Composable
fun rememberTransientNoticeState(): TransientNoticeState = remember { TransientNoticeState() }

/**
 * Hosts the notice of [state], or nothing when there is none. Give it a slot that sits over the
 * content but does not take a share of it, such as a scaffold's snackbar slot.
 */
@Composable
fun TransientNoticeHost(
    state: TransientNoticeState,
    modifier: Modifier = Modifier,
    duration: Duration = 1.5.seconds,
) {
    val notice = state.current
    // Kept past the state being cleared so the exit animation still has text to draw.
    var text by remember { mutableStateOf("") }
    LaunchedEffect(notice?.id) {
        if (notice == null) return@LaunchedEffect
        text = notice.text
        delay(duration)
        state.dismiss()
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
