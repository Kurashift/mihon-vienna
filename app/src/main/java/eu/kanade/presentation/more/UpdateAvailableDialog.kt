package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.updater.AppUpdateProgress
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
 * The download happens here rather than behind a browser: [progress] is the whole story of it, and
 * the confirm button follows it — 下载并安装, 取消 while it runs, 去允许安装 once the APK is on disk
 * but Android still blocks installs, 安装 once it is allowed, 重试 after a failure. A package older
 * than the installed build has no button at all: Android cannot install it, so the dialog only
 * says why. [UpdateProgressBlock] draws the state and [updatePrimaryLabel] names the action, so
 * this prompt and the release-notes dialog cannot drift apart.
 *
 * All three buttons live in one row on purpose. Two of them used to sit in their own nested row,
 * which put one gap between 忽略更新 and 知道了 and a different one before the confirm button.
 */
@Composable
fun UpdateAvailableDialog(
    versionName: String,
    progress: AppUpdateProgress?,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    val busy = progress.isBusy()
    val primaryLabel = updatePrimaryLabel(progress)

    AlertDialog(
        // Tapping outside or pressing back must not end a download: losing 27 MB to a stray tap is
        // exactly what the reader would not forgive. While bytes are moving, 停止下载 is the only
        // way out — and it closes the dialog outright.
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(text = stringResource(MR.strings.update_available_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(MR.strings.update_available_message, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                progress?.let { UpdateProgressBlock(it) }
                // The release notes used to be the confirm button. The download took that slot, and
                // a link inside the text keeps the notes one tap away without a fourth button in
                // the row. Hidden while bytes are moving: the download is the task at that point.
                if (!busy) {
                    TextButton(
                        onClick = onOpenChangelog,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(stringResource(MR.strings.whats_new))
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!busy) {
                    TextButton(onClick = onIgnore) {
                        Text(stringResource(MR.strings.update_available_ignore))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(MR.strings.update_available_later))
                    }
                }
                when {
                    // While bytes are moving the only way out is an explicit stop.
                    busy -> TextButton(onClick = onCancel) {
                        Text(stringResource(MR.strings.update_download_stop))
                    }
                    // A package older than the installed build cannot be installed at all, so there
                    // is no primary action to offer — only the way out.
                    primaryLabel != null -> TextButton(onClick = onPrimary) {
                        Text(primaryLabel)
                    }
                }
            }
        },
    )
}
