package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object HelpScreen : SearchableSettings {
    @Composable
    override fun getTitleRes() = MR.strings.label_help

    @Composable
    override fun getPreferences(): List<Preference> {
        val uriHandler = LocalUriHandler.current
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.online_help),
                onClick = { uriHandler.openUri(Constants.URL_HELP) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_onboarding_guide),
                onClick = { navigator.push(OnboardingScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_show_coach_marks),
                onClick = {
                    Injekt.get<BasePreferences>().coachLocalFabShown.set(false)
                    Injekt.get<ReaderPreferences>().showNavigationOverlayNewUser.set(true)
                    context.toast(MR.strings.coach_reset_done)
                },
            ),
        )
    }
}
