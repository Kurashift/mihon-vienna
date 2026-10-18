package eu.kanade.tachiyomi.data.updater

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.model.Release
import java.io.File

/**
 * One update, from download to the system install sheet.
 *
 * The start-up prompt and the release-notes dialog both offer the same button, so the sequence
 * lives here rather than in either of them: download, verify against the release's SHA-256, then
 * install — and if Android has not been allowed to install yet, stop at [AppUpdateProgress
 * .NeedsPermission] with the verified file kept on disk, so coming back from the system setting is
 * a tap rather than a second 27 MB.
 *
 * Progress is Compose state, the way the audio player's controller keeps its own: both callers
 * draw straight from it.
 */
class AppUpdateSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var progress by mutableStateOf<AppUpdateProgress?>(null)
        private set

    private var verifiedApk: File? = null
    private var job: Job? = null

    /** Download and install [release], or install what an earlier attempt already verified. */
    fun start(release: Release) {
        val ready = verifiedApk
        if (ready != null && ready.exists()) {
            install(ready)
            return
        }

        job?.cancel()
        job = scope.launch {
            try {
                val file = AppUpdateDownloader(context).download(release) { update ->
                    withContext(Dispatchers.Main.immediate) { progress = update }
                }
                verifiedApk = file
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Update download failed" }
                withContext(Dispatchers.Main.immediate) {
                    progress = AppUpdateProgress.Failed(e.message)
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        progress = null
    }

    /**
     * Drops the whole attempt, including the APK in the cache.
     *
     * Called when the reader closes the prompt for good: a verified package kept for the
     * "allow installs" detour is worth holding on to, one nobody is going to install is not.
     */
    fun discard() {
        cancel()
        verifiedApk?.delete()
        verifiedApk = null
    }

    private fun install(apk: File) {
        if (!AppUpdateInstaller.canInstall(context)) {
            // The file stays: the reader only has to flip the switch and tap again.
            progress = AppUpdateProgress.NeedsPermission
            AppUpdateInstaller.openInstallPermission(context)
            return
        }

        val versions = AppUpdateInstaller.versionCodes(context, apk)
        if (versions != null && versions.first < versions.second) {
            progress = AppUpdateProgress.Downgrade(incoming = versions.first, installed = versions.second)
            return
        }

        progress = if (AppUpdateInstaller.install(context, apk)) {
            AppUpdateProgress.Installing
        } else {
            AppUpdateProgress.Failed(null)
        }
    }
}
