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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.updater.AppUpdateProgress
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** How long a freshly shown prompt refuses to close on a tap outside it or on back. */
private const val OUTSIDE_DISMISS_ARM_DELAY_MILLIS = 400L

/**
 * The start-up prompt for a release the background check found.
 *
 * Three answers, and they mean three different things:
 *
 * - 不再提示 turns the start-up prompt off for good (`updatePromptEnabled`), and the about screen's
 *   switch reads that same preference;
 * - 跳过这版 writes the release's version to preferences, so this version never asks again while
 *   the next release still does;
 * - 更新 downloads and installs, and the confirm button follows [progress] from there.
 *
 * A tap outside the dialog, or back, is the fourth way out: it closes the prompt for this visit
 * only. That is also why the dialog ignores those two for its first moments — the check can finish
 * while the reader's finger is already on the screen, and the tail of that gesture used to land on
 * the scrim and close the prompt before it could be read.
 *
 * The download happens here rather than behind a browser: [progress] is the whole story of it, and
 * the confirm button follows it — 更新, 停止下载 while bytes move, 去允许 once the APK is on disk but
 * Android still blocks installs, 安装 once it is allowed, 重试 after a failure. A package older than
 * the installed build has no button at all: Android cannot install it, so the dialog only says why.
 * [UpdateProgressBlock] draws the state and [updatePrimaryLabel] names the action, so this prompt
 * and the release-notes dialog cannot drift apart.
 */
@Composable
fun UpdateAvailableDialog(
    versionName: String,
    progress: AppUpdateProgress?,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onSkipVersion: () -> Unit,
    onDisablePrompt: () -> Unit,
    onDismiss: () -> Unit,
    onOpenChangelog: () -> Unit,
) {
    val busy = progress.isBusy()
    val primaryLabel = updatePrimaryLabel(progress)

    // The check can land while the reader's finger is already on the screen; the release of that
    // touch then counts as a tap outside. Refusing to dismiss for the first moments keeps a prompt
    // that nobody asked for at that instant from vanishing before it can be read.
    var outsideDismissArmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(OUTSIDE_DISMISS_ARM_DELAY_MILLIS)
        outsideDismissArmed = true
    }

    AlertDialog(
        // Tapping outside or pressing back must not end a download: losing 27 MB to a stray tap is
        // exactly what the reader would not forgive. While bytes are moving, 停止下载 is the only
        // way out — and it closes the dialog outright.
        onDismissRequest = { if (!busy && outsideDismissArmed) onDismiss() },
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
                    // Two ways to say no, and the labels have to carry the difference: this one
                    // silences the prompt for good, the next one only for this release.
                    TextButton(onClick = onDisablePrompt) {
                        Text(stringResource(MR.strings.update_available_disable))
                    }
                    TextButton(onClick = onSkipVersion) {
                        Text(stringResource(MR.strings.update_available_skip_version))
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
