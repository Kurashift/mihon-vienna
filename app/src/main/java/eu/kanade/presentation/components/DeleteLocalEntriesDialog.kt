package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirmation shown before local files are erased from disk.
 *
 * Lists what is about to go so the destructive nature of the action is explicit, unlike the
 * "remove from list" confirmations which only drop a list entry.
 *
 * The body is a set of distinct slots rather than free-form text on purpose: the erasure warning,
 * the affected scope, and the reassurances each carry a different tone, and folding them into one
 * string forced them to share a single colour — which is what made a reassurance read as a second
 * warning.
 */
@Composable
fun DeleteLocalEntriesDialog(
    title: String,
    entryNames: List<String>,
    subtitle: String? = null,
    showEntryList: Boolean = true,
    notices: List<String> = emptyList(),
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
        // 漫画名不受调用方控制，可能非常长；M3 弹窗标题是 24sp，在内容宽度里一行只
        // 装得下十来个汉字，不限行数会把正文挤到屏幕外。截断即可：用户就在那一页上
        // 点的删除，名字认得出来。
        title = {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                // Spacing is what separates the warning from the list; without it the blocks run
                // together into one paragraph.
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.local_delete_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Callers that already name the single item in the title pass showEntryList =
                // false: repeating it as a one-line list adds no information.
                if (showEntryList && entryNames.isNotEmpty()) {
                    Text(
                        text = condensedBulletList(entryNames),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Muted, not red. These say what will *not* happen or what survives; painting them
                // with the error colour made them look like further danger.
                notices.filterNot { it.isBlank() }.forEach { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
