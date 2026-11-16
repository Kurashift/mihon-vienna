package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ExtensionInstallTasks(
        scope,
        { Installer.cancelInstallQueue(context, it) },
        { logcat(LogPriority.ERROR, it) },
    )
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller

    private val httpClient: OkHttpClient = Injekt.get<NetworkHelper>().client

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param extension The extension to install.
     * @param isUpdateForPrivatelyInstalled If this is an update for a privately installed extension
     */
    fun downloadAndInstall(
        extension: Extension.Available,
        isUpdateForPrivatelyInstalled: Boolean = false,
    ): Flow<InstallStep> {
        return tasks.start(extension.pkgName) { task ->
            val tmpFile = File.createTempFile("extension_", ".apk", context.cacheDir)
            var handedOff = false
            try {
                task.step.value = InstallStep.Downloading
                downloadExtensionApk(httpClient, extension.apkUrl, tmpFile)
                task.job.ensureActive()
                val packageInfo = ExtensionLoader.getArchivePackageInfo(context, tmpFile)
                    ?: error("Downloaded file is not an extension")
                validateExtensionPackage(
                    extension,
                    packageInfo.packageName,
                    PackageInfoCompat.getLongVersionCode(packageInfo),
                    ExtensionLoader.getSignatures(packageInfo).orEmpty(),
                )
                task.job.ensureActive()
                task.step.value = InstallStep.Installing
                installApk(task.id, tmpFile, isUpdateForPrivatelyInstalled) { task.job.ensureActive() }
                handedOff = true
            } finally {
                if (!handedOff) tmpFile.delete()
            }
        }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param tempFile The file of the extension to install. Delete after use.
     * @param isUpdateForPrivatelyInstalled If this install is an update for a privately installed extension
     */
    private fun installApk(
        downloadId: Long,
        tempFile: File,
        isUpdateForPrivatelyInstalled: Boolean,
        ensureActive: () -> Unit,
    ) {
        if (isUpdateForPrivatelyInstalled) {
            installApkPrivately(downloadId, tempFile, ensureActive)
            return
        }

        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }

            BasePreferences.ExtensionInstaller.PRIVATE -> {
                installApkPrivately(downloadId, tempFile, ensureActive)
            }

            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private fun installApkPrivately(downloadId: Long, tempFile: File, ensureActive: () -> Unit) {
        try {
            if (ExtensionLoader.installPrivateExtensionFile(context, tempFile, ensureActive)) {
                updateInstallStep(downloadId, InstallStep.Installed)
            } else {
                updateInstallStep(downloadId, InstallStep.Error)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
            updateInstallStep(downloadId, InstallStep.Error)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        tasks.cancel(pkgName)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        tasks.update(downloadId, step)
    }

    fun isActive(downloadId: Long): Boolean = tasks.isActive(downloadId)

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
    }
}
