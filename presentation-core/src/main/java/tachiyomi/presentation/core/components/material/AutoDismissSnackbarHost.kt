package tachiyomi.presentation.core.components.material

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * A [SnackbarHost] that auto-dismisses messages after a short time so they don't
 * linger on screen. Plain info messages disappear quickly; messages with an action
 * stay a little longer so the user can still tap the action.
 */
@Composable
fun AutoDismissSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { data -> Snackbar(data) },
) {
    val currentData = hostState.currentSnackbarData
    LaunchedEffect(currentData) {
        if (currentData != null) {
            val hasAction = currentData.visuals.actionLabel != null || currentData.visuals.withDismissAction
            delay(if (hasAction) ACTION_TIMEOUT_MILLIS else PLAIN_TIMEOUT_MILLIS)
            currentData.dismiss()
        }
    }
    SnackbarHost(hostState = hostState, modifier = modifier, snackbar = snackbar)
}

private const val PLAIN_TIMEOUT_MILLIS = 1_200L
private const val ACTION_TIMEOUT_MILLIS = 4_000L
