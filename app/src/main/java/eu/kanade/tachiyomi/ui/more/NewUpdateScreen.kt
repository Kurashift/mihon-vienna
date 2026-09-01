package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.openInBrowser

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = changelogInfo,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onAcceptUpdate = { context.openInBrowser(downloadLink) },
            onRejectUpdate = navigator::pop,
        )
    }
}
