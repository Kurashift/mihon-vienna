package tachiyomi.presentation.core.components.material

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.unit.dp

@Composable
fun AutoDismissSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val currentData = hostState.currentSnackbarData
    val scope = rememberCoroutineScope()
    val accessibilityManager = LocalAccessibilityManager.current
    val timer = remember(currentData, accessibilityManager) {
        val visuals = currentData?.visuals
        val duration = when {
            visuals?.duration == SnackbarDuration.Indefinite -> Long.MAX_VALUE
            visuals?.actionLabel != null -> SNACKBAR_ACTION_TIMEOUT_MILLIS
            visuals?.duration == SnackbarDuration.Long -> 10_000L
            else -> 3_000L
        }
        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = duration,
            containsIcons = false,
            containsText = true,
            containsControls = visuals?.actionLabel != null,
        ) ?: duration
        SnackbarTimeout(scope, timeout) { currentData?.dismiss() }
    }
    DisposableEffect(currentData, timer) {
        if (currentData != null && currentData.visuals.duration != SnackbarDuration.Indefinite) timer.start()
        onDispose { timer.cancel() }
    }

    // Own the timeout here: Material's host has a second timer that cannot pause on touch.
    AnimatedContent(
        targetState = currentData,
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 })
                .togetherWith(fadeOut(tween(100)) + slideOutVertically(tween(140)) { it / 3 })
        },
        label = "Snackbar",
    ) { data ->
        if (data != null) {
            PillSnackbar(
                message = data.visuals.message,
                actionLabel = data.visuals.actionLabel,
                onAction = data::performAction,
                onDismiss = data::dismiss,
                onPressedChange = { pressed ->
                    if (hostState.currentSnackbarData === data) timer.setPressed(pressed)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
