package eu.kanade.tachiyomi.ui.browse

import androidx.compose.runtime.Composable
import eu.kanade.presentation.util.Screen

data object OnlineSourceCenterScreen : Screen() {

    @Composable
    override fun Content() {
        BrowseTab.Content()
    }
}
