package eu.kanade.presentation.more

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.updater.AppUpdateProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The one place an update's state is turned into words.
 *
 * Two surfaces offer the download — the start-up prompt and the release-notes dialog — and they
 * have to agree on what "downloading", "needs permission" and "this package is older than what you
 * have" look like. Keeping the rendering here is what stops the two from drifting apart.
 */
@Composable
internal fun UpdateProgressBlock(progress: AppUpdateProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        when (progress) {
            is AppUpdateProgress.Downloading -> {
                Spacer(Modifier.height(12.dp))
                // An unknown total (no Content-Length) falls back to an indeterminate bar rather
                // than a bar that sits at 0 and looks stuck.
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.downloaded.toFloat() / progress.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (progress.total > 0) {
                        stringResource(
                            MR.strings.update_download_progress,
                            Formatter.formatShortFileSize(context, progress.downloaded),
                            Formatter.formatShortFileSize(context, progress.total),
                        )
                    } else {
                        Formatter.formatShortFileSize(context, progress.downloaded)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppUpdateProgress.Verifying -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(MR.strings.update_download_verifying),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppUpdateProgress.NeedsPermission -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(MR.strings.update_install_permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AppUpdateProgress.Downgrade -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        MR.strings.update_install_downgrade,
                        progress.incoming,
                        progress.installed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AppUpdateProgress.Failed -> {
                Spacer(Modifier.height(12.dp))
                // The transport's own text stays in the log: "HTTP 503" or a checksum mismatch is
                // not something a reader can act on, and the recovery is the same either way.
                Text(
                    text = stringResource(MR.strings.update_download_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AppUpdateProgress.Installing, AppUpdateProgress.Ready -> Unit
        }
    }
}

/** True while bytes are still moving, which is the only time the primary action is "cancel". */
internal fun AppUpdateProgress?.isBusy(): Boolean =
    this is AppUpdateProgress.Downloading || this is AppUpdateProgress.Verifying

/**
 * Label for the button that starts, retries or finishes the install, or null when there is no
 * action to offer: a download in flight (the button is 取消 then) and a package older than the
 * installed build (Android cannot install it).
 */
@Composable
internal fun updatePrimaryLabel(progress: AppUpdateProgress?): String? = when (progress) {
    null -> stringResource(MR.strings.update_download_and_install)
    is AppUpdateProgress.Failed -> stringResource(MR.strings.action_retry)
    AppUpdateProgress.Ready -> stringResource(MR.strings.action_install)
    AppUpdateProgress.NeedsPermission -> stringResource(MR.strings.update_install_grant)
    AppUpdateProgress.Installing -> stringResource(MR.strings.action_install)
    is AppUpdateProgress.Downloading,
    AppUpdateProgress.Verifying,
    is AppUpdateProgress.Downgrade,
    -> null
}
