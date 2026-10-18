package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.File

/**
 * Hands a downloaded APK to the system installer.
 *
 * Deliberately the `ACTION_VIEW` route rather than a `PackageInstaller` session: a session needs a
 * broadcast receiver and a pending-intent dance to surface the system's confirmation dialog, and
 * this app has no queue to feed. The reader sees the same install sheet either way.
 *
 * The file is shared through the app's existing [FileProvider], whose `cache-path` already covers
 * everything under `cacheDir` — including the `updates` directory the downloader writes to.
 */
object AppUpdateInstaller {

    /**
     * What still stands between [apk] and an install, or null when it can go straight to the
     * installer.
     *
     * Checked before the sheet opens: "allow installs" is a per-app system switch the reader has to
     * flip once, and a downgrade is refused by Android with nothing but "应用未安装". Both are
     * better said in the app than discovered at the system dialog.
     */
    fun checkInstallable(context: Context, apk: File): AppUpdateProgress? {
        if (!apk.exists()) return AppUpdateProgress.Failed(null)
        if (!context.packageManager.canRequestPackageInstalls()) {
            return AppUpdateProgress.NeedsPermission
        }
        val versions = versionCodes(context, apk) ?: return null
        return if (versions.first < versions.second) {
            AppUpdateProgress.Downgrade(incoming = versions.first, installed = versions.second)
        } else {
            null
        }
    }

    /** Opens the per-app switch behind [checkInstallable]'s permission test. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** The intent that shows the system install sheet for [apk]. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens the system install sheet for [apk]; false when nothing took the intent. */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists()) return false
        return runCatching { context.startActivity(installIntent(context, apk)) }.isSuccess
    }

    /**
     * The version code inside [apk] and the one installed, or null when the package cannot be read.
     */
    private fun versionCodes(context: Context, apk: File): Pair<Long, Long>? {
        val packageManager = context.packageManager
        val incoming = packageManager.getPackageArchiveInfo(apk.path, 0)?.let {
            PackageInfoCompat.getLongVersionCode(it)
        } ?: return null
        val installed = runCatching {
            PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(context.packageName, 0))
        }.getOrNull() ?: return null
        return incoming to installed
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
