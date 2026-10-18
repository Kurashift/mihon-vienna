package eu.kanade.tachiyomi.data.updater

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.release.model.Release
import java.io.File

/**
 * Where an in-app update stands, for the dialog that reports it.
 *
 * The two terminal states are separate on purpose: [NeedsPermission] means the APK is already on
 * disk and verified, so the reader only has to allow installs once and tap again — nothing is
 * downloaded twice. [Installing] means the system installer was handed the file and has taken over.
 */
sealed interface AppUpdateProgress {
    data class Downloading(val downloaded: Long, val total: Long) : AppUpdateProgress

    data object Verifying : AppUpdateProgress

    /** Downloaded and verified, waiting for the reader to install it. */
    data object Ready : AppUpdateProgress

    /** Downloaded and verified, but Android does not allow this app to install packages yet. */
    data object NeedsPermission : AppUpdateProgress

    /** Downloaded and verified, and the system installer was opened with it. */
    data object Installing : AppUpdateProgress

    /**
     * The package on disk is older than what is installed — a locally built test package can carry
     * a higher version code than the published release. Android refuses that install, and the only
     * thing the reader would see is "应用未安装", so the reason is named here instead.
     */
    data class Downgrade(val incoming: Long, val installed: Long) : AppUpdateProgress

    /** [message] is the transport's own text, logged rather than shown. */
    data class Failed(val message: String?) : AppUpdateProgress
}

/**
 * The download's state, shared by the foreground service that runs it and every surface that
 * reports it.
 *
 * It lives outside the screens because the download outlives them: the reader can dismiss the
 * dialog, leave the app, or turn the screen off, and the service keeps going. A surface that comes
 * back reads the same state it left.
 */
object AppUpdateDownloadState {
    private val _progress = MutableStateFlow<AppUpdateProgress?>(null)
    val progress: StateFlow<AppUpdateProgress?> = _progress.asStateFlow()

    /** The verified APK waiting to be installed, if the last download finished. */
    var readyApk: File? = null
        private set

    /** The release the running (or finished) download belongs to. */
    var release: Release? = null
        private set

    fun start(release: Release) {
        this.release = release
        readyApk = null
        _progress.value = AppUpdateProgress.Downloading(0, release.sizeBytes)
    }

    fun setProgress(progress: AppUpdateProgress) {
        _progress.value = progress
    }

    fun finish(apk: File) {
        readyApk = apk
        _progress.value = AppUpdateProgress.Ready
    }

    /** Drops the state and the APK: the reader stopped the download or walked away from it. */
    fun clear() {
        _progress.value = null
        readyApk?.delete()
        readyApk = null
        release = null
    }

    /** Starts the download, or installs the package a finished download already left behind. */
    fun startOrInstall(context: Context, release: Release) {
        val apk = readyApk
        if (apk != null && apk.exists()) {
            install(context, apk)
        } else {
            AppUpdateDownloadService.start(context, release)
        }
    }

    /** Runs the checks that stand between [apk] and the installer, then opens it. */
    fun install(context: Context, apk: File) {
        val blocked = AppUpdateInstaller.checkInstallable(context, apk)
        if (blocked != null) {
            _progress.value = blocked
            // The package stays on disk: flipping the system switch and tapping again is a tap,
            // not another 27 MB.
            if (blocked == AppUpdateProgress.NeedsPermission) {
                AppUpdateInstaller.openInstallPermission(context)
            }
            return
        }

        _progress.value = if (AppUpdateInstaller.install(context, apk)) {
            AppUpdateProgress.Installing
        } else {
            AppUpdateProgress.Failed(null)
        }
    }
}
