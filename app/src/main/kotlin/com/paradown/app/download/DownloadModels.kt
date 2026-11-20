package com.paradown.app.download

import java.io.IOException

data class DownloadRuntime(
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val activeConnections: Int = 0,
    val maxConnections: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
)

enum class DownloadOutcome {
    COMPLETED,
    PAUSED,
    CANCELED,
    FAILED,
}

open class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DownloadPausedException(val reason: String?) : Exception("Download pausado")

class DownloadCancelledException : Exception("Download cancelado")

class RangeNotSupportedException(message: String = "O servidor não aceitou requisições por faixa.") :
    DownloadException(message)

class RemoteChangedException(message: String = "O arquivo remoto mudou. O download será reiniciado.") :
    DownloadException(message)

class HttpStatusException(
    val code: Int,
    val retryAfterMs: Long?,
    message: String,
    cause: Throwable? = null,
) : DownloadException(message, cause)

class ChecksumMismatchException(expected: String, actual: String) :
    DownloadException("SHA-256 não confere. Esperado: $expected. Obtido: $actual.")
