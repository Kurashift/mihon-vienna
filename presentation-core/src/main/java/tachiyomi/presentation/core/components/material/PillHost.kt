package tachiyomi.presentation.core.components.material

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Hosts one pill at a time.
 *
 * A pill that takes over from another one is a message change, not a new appearance: cross-fading
 * the two draws both of them at once, which reads as a duplicated popup and leaves the outgoing
 * pill tappable for the length of the transition. Only appearing and leaving animate; a
 * replacement swaps the message in place.
 */
@Composable
fun <T> PillHost(
    target: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    label: String = "Pill",
    content: @Composable (T) -> Unit,
) {
    // The message on screen, kept apart from [target] so the pill still has something to draw
    // while it leaves instead of collapsing to an empty one first.
    val shown = remember { mutableStateOf(target) }
    if (target != null) shown.value = target

    Box(modifier = modifier, contentAlignment = contentAlignment) {
        AnimatedVisibility(
            visible = target != null,
            enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(140)) { it / 3 },
            label = label,
        ) {
            shown.value?.let { content(it) }
        }
    }
}
