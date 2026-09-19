package eu.kanade.presentation.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Confirmation for taking the selected works off the shelf.
 *
 * The action erases nothing, but it is the one that removes works from the library, and the row
 * offers it next to a red erase that clears their files. Saying which one keeps what is what tells
 * the two apart before either is tapped, so the body names what survives rather than warning about
 * what does not.
 */
@Composable
fun RemoveFromLibraryDialog(
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
                Text(text = stringResource(MR.strings.action_remove_from_library))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.remove_from_library_title))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_from_library_confirmation))
        },
    )
}
