package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.onboarding.PermissionList
import eu.kanade.presentation.util.Screen
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

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_permissions),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            PermissionList(
                modifier = Modifier
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
