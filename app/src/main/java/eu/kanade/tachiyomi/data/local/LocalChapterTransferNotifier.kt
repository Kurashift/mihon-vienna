package eu.kanade.tachiyomi.data.local

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LocalChapterTransferNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
) {
    private val cancelIntent by lazy { NotificationReceiver.cancelLocalTransferPendingBroadcast(context) }

    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LOCAL_TRANSFER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.action_import_local_chapters))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                cancelIntent,
            )
        }
    }

    fun showProgress(progress: LocalChapterTransferService.Progress) {
        progressNotificationBuilder.setContentTitle(
            context.stringResource(MR.strings.action_import_local_chapters),
        )
        if (!securityPreferences.hideNotificationContent.get()) {
            progressNotificationBuilder.setContentText(progress.currentName)
            progressNotificationBuilder.setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${progress.completed}/${progress.total}  ${progress.currentName}",
                ),
            )
        } else {
            progressNotificationBuilder.setContentText(null)
            progressNotificationBuilder.setStyle(null)
        }
        context.notify(
            Notifications.ID_LOCAL_TRANSFER_PROGRESS,
            progressNotificationBuilder
                .setProgress(progress.total, progress.completed, false)
                .build(),
        )
    }

    fun cancel() {
        context.cancelNotification(Notifications.ID_LOCAL_TRANSFER_PROGRESS)
    }

    fun showResult(imported: Int, skipped: Int, failed: Int, isMove: Boolean) {
        context.notificationBuilder(Notifications.CHANNEL_LOCAL_TRANSFER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.action_import_local_chapters))
            setSmallIcon(R.drawable.ic_mihon)
            setAutoCancel(true)
            setContentText(
                context.stringResource(
                    if (isMove) {
                        MR.strings.notification_local_move_complete
                    } else {
                        MR.strings.notification_local_transfer_complete
                    },
                    imported,
                    skipped,
                    failed,
                ),
            )
        }.also { context.notify(Notifications.ID_LOCAL_TRANSFER_COMPLETE, it.build()) }
    }

    fun buildInitialNotification(): Notification = progressNotificationBuilder
        .setProgress(0, 0, true)
        .build()
}
