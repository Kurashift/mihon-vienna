package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import uy.kohesive.injekt.injectLazy

class AppUpdateChecker {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        return withIOContext {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isPreviewBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )

            when (result) {
                is GetApplicationRelease.Result.NewUpdate -> AppUpdateState.setAvailable(result.release)
                is GetApplicationRelease.Result.NoNewUpdate -> AppUpdateState.clear()
                is GetApplicationRelease.Result.OsTooOld -> Unit
            }
            result
        }
    }
}

const val GITHUB_REPO = "Kurashift/mihon-vienna"
const val PROJECT_GITHUB_URL = "https://github.com/$GITHUB_REPO"
const val PROJECT_ISSUES_URL = "$PROJECT_GITHUB_URL/issues"
const val PROJECT_RELEASES_URL = "$PROJECT_GITHUB_URL/releases"

val RELEASE_TAG: String by lazy {
    if (isPreviewBuildType) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
