package com.mrsep.musicrecognizer.feature.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.mrsep.musicrecognizer.core.common.DeeplinkRouter
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupError
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.mrsep.musicrecognizer.core.strings.R as StringsR
import com.mrsep.musicrecognizer.core.ui.R as UiR

class BackupNotificationHelper @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val deeplinkRouter: DeeplinkRouter,
) {

    private val notificationManager = appContext.getSystemService<NotificationManager>()

    fun notifyFailure(failure: AutoBackupResult.Failure, featureDisabled: Boolean) {
        if (isPostNotificationPermissionDenied()) return
        val manager = notificationManager ?: return
        val reason = localizedError(appContext, failure)
        val body = if (featureDisabled) {
            appContext.getString(StringsR.string.auto_backup_notification_auto_backup_was_turned_off) + "\n$reason"
        } else {
            reason
        }
        val contentIntent = createPendingIntentForDeeplink(deeplinkRouter.getDeepLinkIntentToBackupRestore())
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID_BACKUP)
            .setSmallIcon(UiR.drawable.ic_notification_ready)
            .setContentTitle(appContext.getString(StringsR.string.auto_backup_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIFICATION_TAG_BACKUP, NOTIFICATION_ID_BACKUP, notification)
    }

    fun cancelFailure() {
        notificationManager?.cancel(NOTIFICATION_TAG_BACKUP, NOTIFICATION_ID_BACKUP)
    }

    private fun isPostNotificationPermissionDenied(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_DENIED
        } else {
            false
        }
    }

    private fun createPendingIntentForDeeplink(intent: Intent): PendingIntent {
        return TaskStackBuilder.create(appContext).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    companion object {
        const val CHANNEL_ID_BACKUP = "com.mrsep.musicrecognizer.backup"
        private const val NOTIFICATION_TAG_BACKUP = "backup"
        private const val NOTIFICATION_ID_BACKUP = 5

        fun getChannelForBackup(context: Context): NotificationChannel {
            val name = context.getString(StringsR.string.notification_channel_name_backup)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            return NotificationChannel(CHANNEL_ID_BACKUP, name, importance).apply {
                description = context.getString(StringsR.string.notification_channel_desc_backup)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
        }

        fun localizedError(context: Context, failure: AutoBackupResult.Failure?): String {
            val reason = when (failure?.reason) {
                AutoBackupError.AccessDenied -> {
                    context.getString(StringsR.string.auto_backup_error_access_denied)
                }
                AutoBackupError.FileNotFound -> {
                    context.getString(StringsR.string.auto_backup_error_file_not_found)
                }
                AutoBackupError.Unhandled, null -> {
                    context.getString(StringsR.string.auto_backup_error_unhandled)
                }
            }
            val detail = failure?.message
            return if (
                failure?.reason == AutoBackupError.Unhandled &&
                !detail.isNullOrBlank() &&
                !detail.contains('\n')
            ) {
                "$reason\n$detail"
            } else {
                reason
            }
        }
    }
}
