package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.onboarding.PermissionList
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.launchNotificationSettings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Standalone permission screen reached from More. Same list as the onboarding's permission step,
 * but framed as a settings page: no wizard, no "Next", just back.
 */
class PermissionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_permissions),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(Modifier.padding(contentPadding).verticalScroll(rememberScrollState())) {
                PermissionList()
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_manage_notifications),
                    onPreferenceClick = { context.launchNotificationSettings() },
                )
                TextPreferenceWidget(
                    title = stringResource(MR.strings.background_help),
                    onPreferenceClick = { uriHandler.openUri("https://dontkillmyapp.com/") },
                )
            }
        }
    }
}
