package com.paradown.app.download

import android.os.PowerManager
import android.util.Log
import com.paradown.app.data.prefs.AppSettings
import com.paradown.app.storage.PartialWriter
import com.paradown.app.storage.StorageException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Executa um download segmentado com pool adaptativo de conexões e work stealing.
 */
internal class RangedDownloader(
    private val client: OkHttpClient,
    private val powerManager: PowerManager,
) {

    suspend fun run(
        finalUrl: String,
        rangeValidator: String?,
        totalBytes: Long,
        scheduler: SegmentScheduler,
        writer: PartialWriter,
        maxConnections: Int,
        speedLimitBytesPerSecond: Long,
        control: DownloadControl,
        onRuntime: (DownloadRuntime) -> Unit,
        persist: suspend (downloaded: Long, connections: Int, segments: List<SegmentSnapshot>) -> Unit,
    ) = coroutineScope {
        val workers = ConcurrentHashMap<Int, Job>()
        val nextWorkerId = AtomicInteger(0)
        val fatal = AtomicReference<Throwable?>(null)
        val errorCounter = AtomicInteger(0)
        val limiter = BandwidthLimiter(speedLimitBytesPerSecond)
        var monitorJob: Job? = null
        var checkpointJob: Job? = null
        var reporter: ProgressReporter? = null

        fun activeWorkers(): Int = workers.values.count { it.isActive }

        fun spawnWorker() {
            if (control.isStopRequested || scheduler.isComplete()) return
            val id = nextWorkerId.getAndIncrement()
            val job = launch(
                Dispatchers.IO + CoroutineName("ParaDown-segment-$id"),
                start = CoroutineStart.LAZY,
            ) {
                try {
                    workerLoop(
                        workerId = id,
                        writer = writer,
                        scheduler = scheduler,
                        control = control,
                        limiter = limiter,
                        fatal = fatal,
                        errorCounter = errorCounter,
                        finalUrl = finalUrl,
                        rangeValidator = rangeValidator,
                        expectedTotal = totalBytes,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DownloadPausedException) {
                    // Pausa cooperativa: o loop principal encerra o pool.
                } catch (e: DownloadCancelledException) {
                    // Cancelamento cooperativo.
                } catch (e: Throwable) {
                    if (!control.isStopRequested) {
                        if (fatal.compareAndSet(null, e)) {
                            Log.w(TAG, "Falha em segmento; encerrando pool", e)
                        }
                        control.cancelCalls()
                    }
                } finally {
                    workers.remove(id)
                }
            }
            workers[id] = job
            job.start()
        }

        fun cancelOneWorker() {
            val candidates = workers.entries.filter { it.value.isActive }
            if (candidates.size <= 1) return
            val victim = candidates.maxByOrNull { it.key } ?: return
            workers.remove(victim.key)
            control.cancelWorkerCall(victim.key)
            victim.value.cancel()
        }

        try {
            val deviceLimit = deviceConnectionLimit(maxConnections)
            val initial = minOf(
                deviceLimit,
                AppSettings.INITIAL_CONNECTIONS,
                scheduler.segmentCount(),
            ).coerceAtLeast(1)

            reporter = ProgressReporter(
                scope = this,
                totalBytes = totalBytes,
                bytesProvider = { scheduler.downloadedBytes() },
                connectionsProvider = { activeWorkers() },
                maxConnections = maxConnections,
                onRuntime = onRuntime,
            ).also { it.start() }

            checkpointJob = launch(Dispatchers.IO) {
                var lastTime = System.nanoTime()
                var lastBytes = scheduler.downloadedBytes()
                while (isActive) {
                    delay(100L)
                    val now = System.nanoTime()
                    val downloaded = scheduler.downloadedBytes()
                    val elapsedMs = (now - lastTime) / 1_000_000L
                    if (elapsedMs >= CHECKPOINT_INTERVAL_MS ||
                        downloaded - lastBytes >= CHECKPOINT_BYTES
                    ) {
                        try {
                            persist(
                                downloaded,
                                scheduler.activeConnections(),
                                scheduler.snapshot(),
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Falha ao salvar checkpoint; continuando download", e)
                        }
                        lastTime = now
                        lastBytes = downloaded
                    }
                }
            }

            repeat(initial) { spawnWorker() }

            monitorJob = launch(Dispatchers.Default) {
                var lastTime = System.nanoTime()
                var lastBytes = scheduler.downloadedBytes()
                var lastSpeed = 0L
                var warmupWindows = 1
                var cooldownUntil = 0L
                while (isActive) {
                    delay(ADAPTIVE_WINDOW_MS)
                    if (control.isStopRequested || fatal.get() != null) break
                    val now = System.nanoTime()
                    val downloaded = scheduler.downloadedBytes()
                    val elapsed = (now - lastTime).coerceAtLeast(1L)
                    val speed = ((downloaded - lastBytes).coerceAtLeast(0L) * 1_000_000_000.0 /
                        elapsed.toDouble()).toLong()
                    val errors = errorCounter.getAndSet(0)
                    val target = deviceConnectionLimit(maxConnections)
                    val active = activeWorkers()
                    val canCreateWork = scheduler.hasUnclaimedWork() ||
                        scheduler.totalRemainingBytes() >
                        SegmentScheduler.DEFAULT_MIN_SPLIT_BYTES * 2L

                    if (active > target) {
                        cancelOneWorker()
                        cooldownUntil = now + SCALE_COOLDOWN_NANOS
                        warmupWindows = 1
                    } else if (errors > 0 && active > 2) {
                        cancelOneWorker()
                        cooldownUntil = now + SCALE_COOLDOWN_NANOS
                        warmupWindows = 2
                    } else if (warmupWindows > 0) {
                        warmupWindows--
                    } else if (now >= cooldownUntil &&
                        active < target &&
                        canCreateWork &&
                        speed >= MIN_SPEED_TO_SCALE &&
                        speed > (lastSpeed * 1.08).toLong()
                    ) {
                        spawnWorker()
                        warmupWindows = 1
                    } else if (active > 2 &&
                        lastSpeed >= THROTTLE_REFERENCE_SPEED &&
                        speed < (lastSpeed * 0.60).toLong()
                    ) {
                        cancelOneWorker()
                        cooldownUntil = now + SCALE_COOLDOWN_NANOS
                        warmupWindows = 2
                    }

                    lastSpeed = speed
                    lastBytes = downloaded
                    lastTime = now
                }
            }

            while (isActive) {
                delay(150L)
                if (scheduler.isComplete() || control.isStopRequested || fatal.get() != null) {
                    break
                }
                if (workers.values.none { it.isActive }) {
                    if (scheduler.hasUnclaimedWork()) {
                        spawnWorker()
                    } else if (!scheduler.isComplete()) {
                        fatal.compareAndSet(
                            null,
                            DownloadException("O download parou inesperadamente sem workers ativos."),
                        )
                        break
                    } else {
                        break
                    }
                }
            }
        } finally {
            monitorJob?.cancel()
            checkpointJob?.cancelAndJoin()
            reporter?.stop()
            control.cancelCalls()
            workers.values.forEach { it.cancel() }
            workers.values.forEach { it.join() }
        }

        fatal.get()?.let { throw it }
        control.ensureRunning()
        if (!scheduler.isComplete()) {
            throw DownloadException("O download terminou com segmentos incompletos.")
        }
    }

    private suspend fun workerLoop(
        workerId: Int,
        writer: PartialWriter,
        scheduler: SegmentScheduler,
        control: DownloadControl,
        limiter: BandwidthLimiter,
        fatal: AtomicReference<Throwable?>,
        errorCounter: AtomicInteger,
        finalUrl: String,
        rangeValidator: String?,
        expectedTotal: Long,
    ) {
        while (true) {
            control.ensureRunning()
            if (fatal.get() != null) throw DownloadCancelledException()
            val work = scheduler.acquireWork(workerId) ?: return
            var completed = false
            try {
                downloadSegment(
                    workerId = workerId,
                    segmentIndex = work.index,
                    writer = writer,
                    scheduler = scheduler,
                    control = control,
                    limiter = limiter,
                    fatal = fatal,
                    errorCounter = errorCounter,
                    finalUrl = finalUrl,
                    rangeValidator = rangeValidator,
                    expectedTotal = expectedTotal,
                )
                scheduler.complete(workerId, work.index)
                completed = true
            } finally {
                if (!completed) {
                    scheduler.release(workerId, work.index)
                }
            }
            if (control.isStopRequested || scheduler.isComplete()) return
        }
    }

    private suspend fun downloadSegment(
        workerId: Int,
        segmentIndex: Int,
        writer: PartialWriter,
        scheduler: SegmentScheduler,
        control: DownloadControl,
        limiter: BandwidthLimiter,
        fatal: AtomicReference<Throwable?>,
        errorCounter: AtomicInteger,
        finalUrl: String,
        rangeValidator: String?,
        expectedTotal: Long,
    ) {
        var failures = 0
        while (true) {
            control.ensureRunning()
            if (fatal.get() != null) throw DownloadCancelledException()
            val snapshot = scheduler.snapshotForIndex(segmentIndex) ?: return
            val end = scheduler.currentEnd(segmentIndex) ?: return
            if (snapshot.position > end) return

            val before = snapshot.position
            try {
                downloadRangeOnce(
                    workerId = workerId,
                    segmentIndex = segmentIndex,
                    position = before,
                    end = end,
                    writer = writer,
                    scheduler = scheduler,
                    control = control,
                    limiter = limiter,
                    fatal = fatal,
                    finalUrl = finalUrl,
                    rangeValidator = rangeValidator,
                    expectedTotal = expectedTotal,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadPausedException) {
                throw e
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: Throwable) {
                control.ensureRunning()
                if (fatal.get() != null) throw DownloadCancelledException()
                if (!isRetryable(e)) throw e
                failures++
                errorCounter.incrementAndGet()
                if (failures >= MAX_ATTEMPTS) {
                    throw DownloadException(
                        "Falha no segmento ${segmentIndex + 1} após $MAX_ATTEMPTS tentativas: " +
                            (e.message ?: "erro de rede"),
                        e,
                    )
                }
                delay(retryDelayMs(failures, e))
                continue
            }

            val after = scheduler.snapshotForIndex(segmentIndex)?.position ?: return
            if (after > before) {
                failures = 0
            }
            val liveEnd = scheduler.currentEnd(segmentIndex) ?: return
            if (after > liveEnd) return
            if (after <= before) {
                failures++
                errorCounter.incrementAndGet()
                if (failures >= MAX_ATTEMPTS) {
                    throw DownloadException(
                        "O servidor não enviou dados para o segmento ${segmentIndex + 1}.",
                    )
                }
                delay(retryDelayMs(failures, null))
            }
        }
    }

    private suspend fun downloadRangeOnce(
        workerId: Int,
        segmentIndex: Int,
        position: Long,
        end: Long,
        writer: PartialWriter,
        scheduler: SegmentScheduler,
        control: DownloadControl,
        limiter: BandwidthLimiter,
        fatal: AtomicReference<Throwable?>,
        finalUrl: String,
        rangeValidator: String?,
        expectedTotal: Long,
    ) {
        val requestBuilder = Request.Builder()
            .url(finalUrl)
            .get()
            .header("Range", "bytes=$position-$end")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", ServerProbe.USER_AGENT)
        if (rangeValidator != null) {
            requestBuilder.header("If-Range", rangeValidator)
        }
        control.ensureRunning()
        val call = client.newCall(requestBuilder.build())
        control.attachCall(workerId, call)
        try {
            call.execute().use { response ->
                when {
                    response.code == 206 -> {
                        val range = parseContentRange(response.header("Content-Range"))
                            ?: throw RangeNotSupportedException(
                                "Resposta 206 sem cabeçalho Content-Range válido.",
                            )
                        val rangeStart = range.start
                            ?: throw DownloadException("Content-Range sem início.")
                        val rangeTotal = range.total
                            ?: throw RangeNotSupportedException(
                                "O servidor não informou o tamanho total.",
                            )
                        if (rangeTotal != expectedTotal) {
                            throw RemoteChangedException(
                                "O tamanho do arquivo mudou no servidor.",
                            )
                        }
                        if (rangeStart != position) {
                            throw DownloadException(
                                "O servidor devolveu uma faixa diferente da solicitada.",
                            )
                        }
                        val encoding = response.header("Content-Encoding")
                        if (encoding != null && !encoding.equals("identity", true)) {
                            throw DownloadException(
                                "O servidor enviou conteúdo comprimido; " +
                                    "não é possível gravar as faixas corretamente.",
                            )
                        }
                        val body = response.body
                            ?: throw IOException("Resposta sem corpo.")
                        val buffer = ByteArray(limiter.recommendedChunkSize)
                        var current = position
                        body.byteStream().use { stream ->
                            while (true) {
                                control.ensureRunning()
                                if (fatal.get() != null) throw DownloadCancelledException()
                                val liveEnd = scheduler.currentEnd(segmentIndex) ?: break
                                if (current > liveEnd) break
                                val maxRead = min(
                                    buffer.size.toLong(),
                                    liveEnd - current + 1L,
                                ).toInt()
                                if (maxRead <= 0) break
                                limiter.acquire(maxRead)
                                val read = stream.read(buffer, 0, maxRead)
                                if (read < 0) break
                                if (read == 0) continue
                                val endAfterRead = scheduler.currentEnd(segmentIndex) ?: break
                                val allowed = min(
                                    read.toLong(),
                                    endAfterRead - current + 1L,
                                ).coerceAtLeast(0L).toInt()
                                if (allowed <= 0) break
                                writer.write(buffer, 0, allowed, current)
                                current += allowed
                                if (!scheduler.updatePosition(segmentIndex, workerId, current)) {
                                    break
                                }
                                if (allowed < read) break
                            }
                        }
                    }

                    response.code == 200 -> {
                        if (rangeValidator != null) {
                            throw RemoteChangedException(
                                "O arquivo mudou (If-Range não correspondido).",
                            )
                        }
                        throw RangeNotSupportedException()
                    }

                    response.code == 416 -> {
                        throw RemoteChangedException(
                            "O servidor recusou a faixa (HTTP 416). O arquivo pode ter mudado.",
                        )
                    }

                    else -> throw httpStatusException(response)
                }
            }
        } catch (e: IOException) {
            control.ensureRunning()
            throw e
        } finally {
            control.detachCall(workerId, call)
        }
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is RemoteChangedException,
        is RangeNotSupportedException,
        is DownloadPausedException,
        is DownloadCancelledException,
        is StorageException,
        -> false

        is HttpStatusException -> isRetryableHttpCode(error.code)
        is DownloadException -> false
        is IOException -> true
        else -> false
    }

    private fun retryDelayMs(failures: Int, error: Throwable?): Long {
        val serverDelay = (error as? HttpStatusException)?.retryAfterMs
        val exponential = 1_000L shl (failures - 1).coerceIn(0, 5)
        val chosen = serverDelay ?: exponential
        return chosen.coerceIn(0L, MAX_RETRY_DELAY_MS) + Random.nextLong(0L, 300L)
    }

    private fun deviceConnectionLimit(maxConnections: Int): Int {
        val thermal = runCatching {
            powerManager.currentThermalStatus
        }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val batterySaver = runCatching {
            powerManager.isPowerSaveMode
        }.getOrDefault(false)
        val limit = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 1
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            batterySaver -> 2
            else -> maxConnections
        }
        return limit.coerceIn(1, maxConnections.coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "ParaDownRanged"
        private const val MAX_ATTEMPTS = 5
        private const val CHECKPOINT_INTERVAL_MS = 500L
        private const val CHECKPOINT_BYTES = 1L * 1024L * 1024L
        private const val ADAPTIVE_WINDOW_MS = 4_000L
        private const val SCALE_COOLDOWN_NANOS = 12_000_000_000L
        private const val MIN_SPEED_TO_SCALE = 256L * 1024L
        private const val THROTTLE_REFERENCE_SPEED = 512L * 1024L
        private const val MAX_RETRY_DELAY_MS = 30_000L
    }
}
