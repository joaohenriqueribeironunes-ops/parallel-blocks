package com.paradown.app.download

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordena pausa/cancelamento cooperativo e mantém as chamadas HTTP ativas
 * para que pausar não dependa do timeout de leitura do OkHttp.
 */
class DownloadControl {

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    var pauseReason: String? = null
        private set

    private val activeCalls = ConcurrentHashMap<Int, Call>()

    val isPauseRequested: Boolean get() = pauseRequested
    val isCancelRequested: Boolean get() = cancelRequested
    val isStopRequested: Boolean get() = pauseRequested || cancelRequested

    fun pause(reason: String?) {
        if (cancelRequested) return
        pauseReason = reason
        pauseRequested = true
        cancelCalls()
    }

    fun cancel() {
        cancelRequested = true
        pauseRequested = false
        cancelCalls()
    }

    fun attachCall(ownerId: Int, call: Call) {
        activeCalls[ownerId] = call
        if (isStopRequested) {
            call.cancel()
        }
    }

    fun detachCall(ownerId: Int, call: Call) {
        activeCalls.remove(ownerId, call)
    }

    fun cancelWorkerCall(ownerId: Int) {
        activeCalls[ownerId]?.cancel()
    }

    fun cancelCalls() {
        activeCalls.values.forEach { call ->
            runCatching { call.cancel() }
        }
    }

    fun ensureRunning() {
        if (cancelRequested) throw DownloadCancelledException()
        if (pauseRequested) throw DownloadPausedException(pauseReason)
    }
}
