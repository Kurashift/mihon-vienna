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
     * Whether Android will let this app start an install at all.
     *
     * False until the reader allows "install unknown apps" for this app; the setting is per-app and
     * only has to be given once.
     */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the per-app switch that [canInstall] reads. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Opens the system install sheet for [apk]; false when nothing took the intent. */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * The version code inside [apk] and the one installed, or null when the package cannot be read.
     *
     * Read before installing so a locally built test package — which can carry a higher code than
     * the published release — is reported as such, instead of failing at the system level with
     * "应用未安装" and no reason.
     */
    fun versionCodes(context: Context, apk: File): Pair<Long, Long>? {
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
