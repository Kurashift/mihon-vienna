package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadService
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadState
import eu.kanade.tachiyomi.data.updater.AppUpdateProgress
import eu.kanade.tachiyomi.util.system.openInBrowser
import tachiyomi.domain.release.model.Release

/**
 * The release notes, with the same in-app download the start-up prompt offers.
 *
 * The whole [Release] is passed rather than its fields because the download needs the SHA-256 and
 * the size the release API reports, not just a URL to open in a browser.
 */
class NewUpdateScreen(
    private val release: Release,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val progress by AppUpdateDownloadState.progress.collectAsState()

        // A finished download goes straight to the installer, so the reader does not have to find
        // the second button after waiting.
        LaunchedEffect(progress) {
            if (progress == AppUpdateProgress.Ready) {
                AppUpdateDownloadState.readyApk?.let { AppUpdateDownloadState.install(context, it) }
            }
        }

        NewUpdateScreen(
            versionName = release.version,
            changelogInfo = release.info,
            progress = progress,
            onDownload = { AppUpdateDownloadState.startOrInstall(context, release) },
            onCancelDownload = {
                AppUpdateDownloadService.stop(context)
                AppUpdateDownloadState.clear()
            },
            onDismissDownload = AppUpdateDownloadState::clear,
            onOpenInBrowser = { context.openInBrowser(release.releaseLink) },
            onRejectUpdate = {
                AppUpdateDownloadState.clear()
                navigator.pop()
            },
        )
    }
}
