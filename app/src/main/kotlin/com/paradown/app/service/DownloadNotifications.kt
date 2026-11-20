package com.paradown.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.paradown.app.R
import com.paradown.app.download.CoordinatorState
import com.paradown.app.util.Formatting

/**
 * Notificação agregada do foreground service. Mostra o progresso total dos
 * downloads ativos e ações de pausar/retomar/cancelar.
 */
class DownloadNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildInitial(): Notification = baseBuilder()
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_preparing))
        .setProgress(0, 0, true)
        .build()

    fun build(state: CoordinatorState): Notification {
        val builder = baseBuilder()
        when {
            state.active.isNotEmpty() -> buildActive(builder, state)
            state.isWaitingForNetwork -> buildWaiting(builder, state)
            state.paused > 0 -> buildPaused(builder, state)
            state.queued > 0 -> buildWaiting(builder, state)
            else -> buildIdle(builder)
        }
        return builder.build()
    }

    private fun buildActive(builder: NotificationCompat.Builder, state: CoordinatorState) {
        val primary = state.active.first()
        val activeCount = state.active.size
        val total = state.knownTotalBytes
        if (total > 0L) {
            builder.setProgress(1000, (state.overallProgress * 1000).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (activeCount == 1) {
            builder.setContentTitle(primary.fileName)
            val detail = if (total > 0L) {
                if (state.etaSeconds != null) {
                    context.getString(
                        R.string.notification_progress_detail_eta,
                        Formatting.bytes(state.totalDownloaded),
                        Formatting.bytes(total),
                        Formatting.speed(state.totalSpeed),
                        Formatting.eta(state.etaSeconds),
                    )
                } else {
                    context.getString(
                        R.string.notification_progress_detail,
                        Formatting.bytes(state.totalDownloaded),
                        Formatting.bytes(total),
                        Formatting.speed(state.totalSpeed),
                        state.totalConnections,
                    )
                }
            } else {
                context.getString(
                    R.string.notification_progress_unknown,
                    Formatting.speed(state.totalSpeed),
                    state.totalConnections,
                )
            }
            builder.setContentText(detail)
            if (total > 0L) {
                builder.setSubText(Formatting.percent(state.overallProgress))
            }
        } else {
            builder.setContentTitle(
                context.getString(R.string.notification_downloading_many, activeCount),
            )
            val detail = context.getString(
                R.string.notification_multi_detail,
                primary.fileName,
                Formatting.speed(state.totalSpeed),
                activeCount,
                state.totalConnections,
            )
            builder.setContentText(detail)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            if (total > 0L) {
                builder.setSubText(Formatting.percent(state.overallProgress))
            }
        }
        builder.addAction(
            0,
            context.getString(R.string.notification_pause_all),
            serviceAction(DownloadService.ACTION_PAUSE_ALL, REQUEST_PAUSE_ALL),
        )
        builder.addAction(
            0,
            context.getString(R.string.notification_cancel_active),
            serviceAction(DownloadService.ACTION_CANCEL_ACTIVE, REQUEST_CANCEL_ACTIVE),
        )
    }

    private fun buildWaiting(builder: NotificationCompat.Builder, state: CoordinatorState) {
        val reason = state.waitingReason
            ?: context.getString(R.string.notification_preparing)
        builder.setContentTitle(reason)
        val queued = state.queued.coerceAtLeast(state.autoPausedCount)
        builder.setContentText(
            context.getString(R.string.notification_waiting_detail, queued),
        )
        builder.setProgress(0, 0, true)
        if (state.active.isEmpty() && state.queued > 0) {
            builder.addAction(
                0,
                context.getString(R.string.notification_pause_all),
                serviceAction(DownloadService.ACTION_PAUSE_ALL, REQUEST_PAUSE_QUEUE),
            )
        }
        if (state.paused > 0) {
            addResumeAction(builder)
        }
    }

    private fun buildPaused(builder: NotificationCompat.Builder, state: CoordinatorState) {
        builder.setContentTitle(context.getString(R.string.notification_paused_title))
        builder.setContentText(
            context.getString(R.string.notification_paused_detail, state.paused),
        )
        builder.setProgress(0, 0, false)
        addResumeAction(builder)
    }

    private fun buildIdle(builder: NotificationCompat.Builder) {
        builder.setContentTitle(context.getString(R.string.notification_finishing))
        builder.setContentText(context.getString(R.string.notification_idle_detail))
        builder.setProgress(0, 0, false)
    }

    private fun addResumeAction(builder: NotificationCompat.Builder) {
        builder.addAction(
            0,
            context.getString(R.string.notification_resume),
            serviceAction(DownloadService.ACTION_RESUME_ALL, REQUEST_RESUME_ALL),
        )
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        contentIntent()?.let(builder::setContentIntent)
        return builder
    }

    private fun contentIntent(): PendingIntent? {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "downloads_progress"
        const val NOTIFICATION_ID = 1001

        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_PAUSE_ALL = 11
        private const val REQUEST_CANCEL_ACTIVE = 12
        private const val REQUEST_RESUME_ALL = 13
        private const val REQUEST_PAUSE_QUEUE = 14
    }
}
