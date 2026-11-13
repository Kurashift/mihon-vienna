package eu.kanade.tachiyomi.util.system

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Shows a snackbar and immediately dismisses any currently visible one, so
 * consecutive messages replace each other instead of queuing up behind the
 * previous one.
 */
suspend fun SnackbarHostState.showSnackbarReplacing(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = duration,
    )
}

/**
 * Says what was just done and offers to take it back.
 *
 * The timeout comes from the host, not from here: AutoDismissSnackbarHost gives any snackbar
 * carrying an action label five seconds and pauses that count while it is being pressed. A
 * replacement snackbar dismisses this one without performing its action, which is the point -
 * only an explicit tap on the label runs [onUndo].
 *
 * @return whether the change was undone.
 */
suspend fun SnackbarHostState.showUndoSnackbar(
    message: String,
    undoLabel: String,
    onUndo: suspend () -> Unit,
): Boolean {
    val result = showSnackbarReplacing(message = message, actionLabel = undoLabel)
    if (result == SnackbarResult.ActionPerformed) {
        onUndo()
        return true
    }
    return false
}
