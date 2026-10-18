package eu.kanade.tachiyomi.data.updater

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

    /** [message] is the transport's own text, shown as-is; null for a failure with no detail. */
    data class Failed(val message: String?) : AppUpdateProgress
}
