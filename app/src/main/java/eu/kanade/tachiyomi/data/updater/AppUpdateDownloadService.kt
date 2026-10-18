package eu.kanade.tachiyomi.data.updater

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import java.io.File

/**
 * Downloads the update APK in a foreground service, so leaving the app does not end it.
 *
 * The dialog is only a view over [AppUpdateDownloadState]: dismiss it, switch apps, lock the screen
 * — the service keeps streaming, the notification carries the progress and a 停止 action, and the
 * dialog that comes back shows the same numbers. That is the whole reason this is a service rather
 * than a coroutine tied to a screen.
 *
 * A finished download posts a notification whose tap opens the system installer, so a reader who
 * left the app does not have to find their way back to a dialog to finish.
 */
class AppUpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopDownload()
            ACTION_START -> {
                val release = intent.toRelease()
                if (release != null) startDownload(release)
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(release: Release) {
        if (job?.isActive == true) return
        AppUpdateDownloadState.start(release)
        startForegroundCompat(buildProgressNotification())

        job = scope.launch {
            try {
                val apk = AppUpdateDownloader(this@AppUpdateDownloadService).download(release) { progress ->
                    AppUpdateDownloadState.setProgress(progress)
                    notify(buildProgressNotification())
                }
                AppUpdateDownloadState.finish(apk)
                notify(buildReadyNotification(apk))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Update download failed" }
                AppUpdateDownloadState.setProgress(AppUpdateProgress.Failed(e.message))
                toast(MR.strings.update_download_failed)
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    /**
     * Stops the transfer and drops everything it produced.
     *
     * The reader pressed 停止下载: a half-downloaded package is worth nothing, so it goes with the
     * notification and the state.
     */
    private fun stopDownload() {
        job?.cancel()
        job = null
        AppUpdateDownloadState.clear()
        NotificationManagerCompat.from(this).cancel(Notifications.ID_APP_UPDATE)
        stopForegroundCompat()
        stopSelf()
    }

    private fun buildProgressNotification(): Notification {
        val release = AppUpdateDownloadState.release
        val progress = AppUpdateDownloadState.progress.value as? AppUpdateProgress.Downloading
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_APP_UPDATE)
            .setSmallIcon(R.drawable.ic_mihon)
            .setContentTitle(this.stringResource(MR.strings.update_install_title, release?.version.orEmpty()))
            .setContentText(this.stringResource(MR.strings.update_notification_downloading))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, progress?.total?.let { it <= 0 } ?: true)
            .addAction(
                0,
                this.stringResource(MR.strings.update_download_stop),
                PendingIntent.getService(
                    this,
                    REQUEST_STOP,
                    Intent(this, AppUpdateDownloadService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        if (progress != null && progress.total > 0) {
            builder.setProgress(1000, (progress.downloaded * 1000 / progress.total).toInt().coerceIn(0, 1000), false)
        }
        return builder.build()
    }

    private fun buildReadyNotification(apk: File): Notification = NotificationCompat.Builder(
        this,
        Notifications.CHANNEL_APP_UPDATE,
    )
        .setSmallIcon(R.drawable.ic_mihon)
        .setContentTitle(
            this.stringResource(MR.strings.update_install_title, AppUpdateDownloadState.release?.version.orEmpty()),
        )
        .setContentText(this.stringResource(MR.strings.update_notification_ready))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                REQUEST_INSTALL,
                AppUpdateInstaller.installIntent(this, apk),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setAutoCancel(true)
        .build()

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(Notifications.ID_APP_UPDATE, notification)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_APP_UPDATE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_APP_UPDATE, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun Intent.toRelease(): Release? {
        val version = getStringExtra(EXTRA_VERSION) ?: return null
        val url = getStringExtra(EXTRA_URL) ?: return null
        return Release(
            version = version,
            info = "",
            releaseLink = "",
            downloadLink = url,
            sha256 = getStringExtra(EXTRA_SHA256),
            sizeBytes = getLongExtra(EXTRA_SIZE, 0),
        )
    }

    companion object {
        private const val ACTION_START = "AppUpdateDownloadService.START"
        private const val ACTION_STOP = "AppUpdateDownloadService.STOP"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "size"
        private const val REQUEST_STOP = 1
        private const val REQUEST_INSTALL = 2

        fun start(context: Context, release: Release) {
            val intent = Intent(context, AppUpdateDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VERSION, release.version)
                .putExtra(EXTRA_URL, release.downloadLink)
                .putExtra(EXTRA_SHA256, release.sha256)
                .putExtra(EXTRA_SIZE, release.sizeBytes)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AppUpdateDownloadService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
