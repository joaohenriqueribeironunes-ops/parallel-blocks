package com.paradown.app.download

import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Limitador de banda compartilhado por todos os segmentos de um download.
 * Agenda "slots" de tempo para cada bloco, mantendo a taxa agregada próxima
 * do limite configurado. Limite 0 significa ilimitado.
 */
class BandwidthLimiter(private val bytesPerSecond: Long) {

    private val lock = Any()
    private var nextFreeNanos = System.nanoTime()

    val enabled: Boolean
        get() = bytesPerSecond > 0L

    val recommendedChunkSize: Int
        get() {
            if (bytesPerSecond <= 0L) return DEFAULT_CHUNK
            return (bytesPerSecond / 8L).coerceIn(MIN_CHUNK.toLong(), DEFAULT_CHUNK.toLong()).toInt()
        }

    suspend fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0L || bytes <= 0) return
        val waitNanos: Long
        synchronized(lock) {
            val now = System.nanoTime()
            val start = max(now, nextFreeNanos)
            val duration = bytes.toDouble() / bytesPerSecond.toDouble() * 1_000_000_000.0
            nextFreeNanos = start + duration.toLong().coerceAtLeast(1L)
            waitNanos = (start - now).coerceAtLeast(0L)
        }
        if (waitNanos > 0L) {
            delay(waitNanos / 1_000_000L + if (waitNanos % 1_000_000L > 0L) 1L else 0L)
        }
    }

    companion object {
        private const val MIN_CHUNK = 4 * 1024
        const val DEFAULT_CHUNK = 64 * 1024
    }
}
