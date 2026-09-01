package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirmation shown before local files are erased from disk.
 *
 * Lists what is about to go so the destructive nature of the action is explicit, unlike the
 * "remove from list" confirmations which only drop a list entry.
 */
@Composable
fun DeleteLocalEntriesDialog(
    title: String,
    entryNames: List<String>,
    extraWarning: String? = null,
    inProgress: Boolean = false,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismissRequest() },
        dismissButton = {
            TextButton(enabled = !inProgress, onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inProgress && entryNames.isNotEmpty(),
                onClick = onConfirm,
            ) {
                Text(
                    text = stringResource(
                        if (inProgress) MR.strings.local_delete_progress else MR.strings.action_delete,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(MR.strings.local_delete_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entryNames.isNotEmpty()) {
                    Text(
                        text = bulletList(entryNames),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!extraWarning.isNullOrBlank()) {
                    Text(
                        text = extraWarning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

/**
 * Renders the entries as a short bullet list, collapsing the tail so a large deletion does not
 * push the confirm button off screen.
 */
@Composable
private fun bulletList(names: List<String>, maxVisible: Int = 6): String = buildString {
    val visible = names.take(maxVisible)
    visible.forEach { name ->
        appendLine("\u2022 ${name.trim().ifBlank { "-" }}")
    }
    val remaining = names.size - visible.size
    if (remaining > 0) {
        append("\u2022 ")
        append(stringResource(MR.strings.local_delete_and_more, remaining))
    }
}.trimEnd()
