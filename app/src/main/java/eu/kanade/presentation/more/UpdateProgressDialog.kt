package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.updater.AppUpdateProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The download's own dialog, shown over the release notes.
 *
 * The notes can be several screens long, so anything drawn at the end of them is invisible exactly
 * when the reader wants it — a progress bar under a long changelog means scrolling to find out
 * whether the download is even running. A dialog stays where the eye is.
 */
@Composable
fun UpdateProgressDialog(
    versionName: String,
    progress: AppUpdateProgress,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = progress.isBusy()
    val primaryLabel = updatePrimaryLabel(progress)

    AlertDialog(
        // Same rule as the start-up prompt: a stray tap outside must not throw away a download in
        // flight. 停止下载 is the way out, and it closes the dialog.
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(text = stringResource(MR.strings.update_install_title, versionName))
        },
        text = {
            UpdateProgressBlock(progress)
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!busy) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(MR.strings.update_available_later))
                    }
                }
                when {
                    busy -> TextButton(onClick = onCancel) {
                        Text(stringResource(MR.strings.update_download_stop))
                    }
                    primaryLabel != null -> TextButton(onClick = onPrimary) {
                        Text(primaryLabel)
                    }
                }
            }
        },
    )
}
