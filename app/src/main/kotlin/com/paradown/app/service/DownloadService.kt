package com.paradown.app.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.paradown.app.ParaDownApplication
import com.paradown.app.download.CoordinatorState
import com.paradown.app.download.DownloadCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service do tipo dataSync. Mantém o processo vivo durante os
 * downloads, publica a notificação de progresso e delega a fila ao coordenador.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: DownloadNotifications
    private var notificationJob: Job? = null
    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val coordinator: DownloadCoordinator
        get() = (application as ParaDownApplication).coordinator

    override fun onCreate() {
        super.onCreate()
        notifications = DownloadNotifications(this)
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            try {
                ServiceCompat.startForeground(
                    this,
                    DownloadNotifications.NOTIFICATION_ID,
                    notifications.buildInitial(),
                    type,
                )
                foregroundStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível entrar em foreground", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_PAUSE_ALL -> scope.launch { coordinator.pauseAll() }
            ACTION_RESUME_ALL -> scope.launch { coordinator.resumeAll() }
            ACTION_CANCEL_ACTIVE -> coordinator.cancelAllActive()
        }

        coordinator.onServiceStarted()
        if (notificationJob == null) {
            notificationJob = scope.launch {
                coordinator.state.collect { state ->
                    publishNotification(state)
                    updateWakeLock(state.active.isNotEmpty())
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        coordinator.onServiceStopped("Serviço encerrado")
        notificationJob?.cancel()
        notificationJob = null
        scope.cancel()
        releaseWakeLock()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        NotificationManagerCompat.from(this).cancel(DownloadNotifications.NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishNotification(state: CoordinatorState) {
        if (!foregroundStarted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                DownloadNotifications.NOTIFICATION_ID,
                notifications.build(state),
            )
        }.onFailure { Log.w(TAG, "Falha ao atualizar notificação", it) }
    }

    private fun notificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateWakeLock(active: Boolean) {
        if (active) {
            if (wakeLock == null) {
                val powerManager = getSystemService(PowerManager::class.java)
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ParaDown:downloads",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching {
                if (lock.isHeld) lock.release()
            }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ParaDownService"

        const val ACTION_PAUSE_ALL = "com.paradown.app.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.paradown.app.action.RESUME_ALL"
        const val ACTION_CANCEL_ACTIVE = "com.paradown.app.action.CANCEL_ACTIVE"
    }
}
