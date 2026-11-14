package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.undo.UndoPillService
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PillSnackbar
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Hosts the app's undo pill above every screen, so the offer survives switching tabs or opening a
 * work and keeps its own countdown.
 */
@Composable
fun UndoPillHost(modifier: Modifier = Modifier) {
    val service = remember { Injekt.get<UndoPillService>() }
    val pill by service.pill.collectAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // One height everywhere: clear of the bottom navigation bar on the tabs, and the same
            // spot on every other screen so the pill never moves between them.
            .padding(bottom = BottomNavFabLift)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // AnimatedContent keeps the outgoing pill composed while it fades, so the message stays
        // readable for the whole exit instead of collapsing to an empty one first.
        AnimatedContent(
            targetState = pill,
            transitionSpec = {
                (fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 })
                    .togetherWith(fadeOut(tween(100)) + slideOutVertically(tween(140)) { it / 3 })
            },
            label = "UndoPill",
        ) { current ->
            if (current != null) {
                PillSnackbar(
                    message = current.message,
                    actionLabel = stringResource(MR.strings.action_undo),
                    onAction = service::undo,
                    onDismiss = service::dismiss,
                    onPressedChange = service::setPressed,
                )
            }
        }
    }
}
