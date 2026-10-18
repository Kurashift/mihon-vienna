package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.updater.AppUpdateProgress
import eu.kanade.tachiyomi.data.updater.AppUpdateSession
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
        val scope = rememberCoroutineScope()
        val session = remember { AppUpdateSession(context, scope) }

        // Once the system installer has the file this dialog has nothing left to say, and staying
        // on top of the install sheet would hide it.
        LaunchedEffect(session.progress) {
            if (session.progress == AppUpdateProgress.Installing) {
                navigator.pop()
            }
        }

        NewUpdateScreen(
            versionName = release.version,
            changelogInfo = release.info,
            progress = session.progress,
            onDownload = { session.start(release) },
            onCancelDownload = session::cancel,
            onDismissDownload = session::discard,
            onOpenInBrowser = { context.openInBrowser(release.releaseLink) },
            onRejectUpdate = {
                // Closing the notes drops whatever the download left in the cache: nobody is going
                // to install a package they walked away from.
                session.discard()
                navigator.pop()
            },
        )
    }
}
