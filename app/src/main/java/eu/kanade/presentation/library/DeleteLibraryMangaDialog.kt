package eu.kanade.presentation.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirmation for clearing the downloaded chapters of the selected works.
 *
 * Taking works off the shelf is not offered here: the shelf picker does that when every shelf is
 * left unchecked, so a second route to the same result would only be a place to disagree with it.
 * This dialog is left with the filesystem action, which has no other home in this row.
 */
@Composable
fun DeleteLibraryMangaDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_delete))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.delete_downloaded_chapters_title))
        },
        text = {
            Text(text = stringResource(MR.strings.delete_downloaded_chapters_confirmation))
        },
    )
}
