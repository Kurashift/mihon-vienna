package eu.kanade.presentation.more.onboarding

import androidx.compose.runtime.Composable

internal class PermissionStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        PermissionList()
    }
}
