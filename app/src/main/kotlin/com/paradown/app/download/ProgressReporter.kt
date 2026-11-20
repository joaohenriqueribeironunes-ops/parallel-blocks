package com.paradown.app.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

internal class ProgressReporter(
    private val scope: CoroutineScope,
    private val totalBytes: Long,
    private val bytesProvider: () -> Long,
    private val connectionsProvider: () -> Int,
    private val maxConnections: Int,
    private val onRuntime: (DownloadRuntime) -> Unit,
) {
    private var job: Job? = null
    private var smoothedSpeed = 0.0

    fun start() {
        emit(0L)
        job = scope.launch(Dispatchers.Default) {
            var lastBytes = bytesProvider()
            var lastNanos = System.nanoTime()
            while (isActive) {
                delay(1_000L)
                val now = System.nanoTime()
                val current = bytesProvider()
                val elapsed = (now - lastNanos).coerceAtLeast(1L)
                val instant = ((current - lastBytes).coerceAtLeast(0L) * 1_000_000_000.0 /
                    elapsed.toDouble())
                smoothedSpeed = if (smoothedSpeed <= 0.0) {
                    instant
                } else {
                    smoothedSpeed * 0.6 + instant * 0.4
                }
                if (instant <= 0.0 && smoothedSpeed < 16.0) {
                    smoothedSpeed = 0.0
                }
                emit(smoothedSpeed.toLong())
                lastBytes = current
                lastNanos = now
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        emit(0L)
    }

    private fun emit(speed: Long) {
        val downloaded = bytesProvider()
        val eta = if (totalBytes > 0L && speed > 1024L) {
            ((totalBytes - downloaded).coerceAtLeast(0L) / speed)
                .takeIf { it > 0L }
        } else {
            null
        }
        runCatching {
            onRuntime(
                DownloadRuntime(
                    bytesPerSecond = speed,
                    etaSeconds = eta,
                    activeConnections = connectionsProvider(),
                    maxConnections = maxConnections,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                ),
            )
        }
    }
}
