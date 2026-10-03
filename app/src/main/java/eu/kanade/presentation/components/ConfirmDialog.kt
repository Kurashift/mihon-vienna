package eu.kanade.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Plain confirmation for an action that needs one.
 *
 * The title names the action being confirmed and the body states what it does, so the two never
 * ask the same question twice. A generic "Are you sure?" title told the reader nothing they could
 * not already see in the body.
 *
 * Deliberately not used for local file deletion: that path needs its own dialog, because the
 * scope of what disappears from disk has to be spelled out before the user agrees to it.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
