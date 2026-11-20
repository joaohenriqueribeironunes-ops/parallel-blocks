package com.paradown.app.download

import com.paradown.app.data.model.DownloadStatus
import com.paradown.app.network.NetworkStatus

/**
 * Linha ativa exibida na notificação e, futuramente, na tela principal.
 */
data class ActiveDownloadState(
    val id: Long,
    val fileName: String,
    val mimeType: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val activeConnections: Int,
    val maxConnections: Int,
    val status: DownloadStatus,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

/**
 * Fotografia do estado do coordenador para notificação e interface.
 */
data class CoordinatorState(
    val active: List<ActiveDownloadState> = emptyList(),
    val queued: Int = 0,
    val paused: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val network: NetworkStatus = NetworkStatus(),
    val onlyWifi: Boolean = false,
    val waitingReason: String? = null,
    val autoPausedCount: Int = 0,
    val serviceRunning: Boolean = false,
) {
    val activeCount: Int get() = active.size

    val totalSpeed: Long get() = active.sumOf { it.bytesPerSecond }

    val totalDownloaded: Long get() = active.sumOf { it.downloadedBytes }

    val knownTotalBytes: Long
        get() = if (active.isNotEmpty() && active.all { it.totalBytes > 0L }) {
            active.sumOf { it.totalBytes }
        } else {
            -1L
        }

    val totalConnections: Int get() = active.sumOf { it.activeConnections }

    val overallProgress: Float
        get() {
            val total = knownTotalBytes
            if (total <= 0L) return 0f
            return (totalDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    val etaSeconds: Long?
        get() {
            val total = knownTotalBytes
            val speed = totalSpeed
            if (total <= 0L || speed <= 0L) return null
            return ((total - totalDownloaded).coerceAtLeast(0L) / speed).takeIf { it > 0L }
        }

    val isWaitingForNetwork: Boolean
        get() = waitingReason != null && (queued > 0 || autoPausedCount > 0)
}
