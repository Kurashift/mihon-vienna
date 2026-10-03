package eu.kanade.presentation.more.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import soup.compose.material.motion.animation.materialSharedAxisX
import soup.compose.material.motion.animation.rememberSlideDistance
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

/**
 * Where the onboarding opens. [Permissions] is used by the in-app entry that sends the user back
 * to the permission step to grant a permission the ROM withheld, without walking the whole flow.
 */
enum class OnboardingEntryPoint {
    Start,
    Permissions,
}

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onRestoreBackup: () -> Unit,
    entryPoint: OnboardingEntryPoint = OnboardingEntryPoint.Start,
) {
    val slideDistance = rememberSlideDistance()

    val steps = remember {
        listOf(
            ThemeStep(),
            StorageStep(),
            PermissionStep(),
            GuidesStep(onRestoreBackup = onRestoreBackup),
        )
    }

    var currentStep by rememberSaveable {
        mutableIntStateOf(
            when (entryPoint) {
                OnboardingEntryPoint.Start -> 0
                OnboardingEntryPoint.Permissions -> steps.indexOfFirst { it is PermissionStep }
            }.coerceIn(0, steps.lastIndex),
        )
    }
    val isLastStep = currentStep == steps.lastIndex

    // Only the normal flow steps back through the pages. Entered straight at a step (e.g. from the
    // settings entry), back leaves the flow instead of walking back through pages never seen.
    BackHandler(enabled = entryPoint == OnboardingEntryPoint.Start && currentStep != 0) {
        currentStep--
    }

    InfoScreen(
        icon = Icons.Outlined.RocketLaunch,
        headingText = stringResource(MR.strings.onboarding_heading),
        subtitleText = stringResource(MR.strings.onboarding_description),
        acceptText = stringResource(
            if (isLastStep) {
                MR.strings.onboarding_action_finish
            } else {
                MR.strings.onboarding_action_next
            },
        ),
        canAccept = steps[currentStep].isComplete,
        onAcceptClick = {
            if (isLastStep) {
                onComplete()
            } else {
                currentStep++
            }
        },
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = MaterialTheme.padding.small)
                .clip(MaterialTheme.shapes.small)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    materialSharedAxisX(
                        forward = targetState > initialState,
                        slideDistance = slideDistance,
                    )
                },
                label = "stepContent",
            ) {
                steps[it].Content()
            }
        }
    }
}
