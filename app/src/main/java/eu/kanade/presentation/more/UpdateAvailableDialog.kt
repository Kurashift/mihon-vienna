package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The start-up prompt for a release the background check found.
 *
 * Three answers, and they mean three different things:
 *
 * - 忽略更新 writes the release's version to preferences, so this version never asks again while
 *   the next release still does;
 * - 知道了 closes the prompt for this launch only — the state behind it lives in memory, so the
 *   next launch asks again;
 * - 更新日志 opens the release notes, the same dialog the About screen's own check opens.
 *
 * The red dot on the More tab stays where it was: this prompt is the loud half of the same fact,
 * and it can be switched off without switching the indicator off.
 */
@Composable
fun UpdateAvailableDialog(
    versionName: String,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(MR.strings.update_available_title))
        },
        text = {
            Text(
                text = stringResource(MR.strings.update_available_message, versionName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenChangelog) {
                Text(stringResource(MR.strings.whats_new))
            }
        },
        dismissButton = {
            // Ignoring is the wider decision of the two, so it sits first and the everyday answer
            // sits next to it; the release notes keep the confirming side of the row.
            Row {
                TextButton(onClick = onIgnore) {
                    Text(stringResource(MR.strings.update_available_ignore))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.update_available_later))
                }
            }
        },
    )
}
