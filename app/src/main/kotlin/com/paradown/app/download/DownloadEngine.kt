package com.paradown.app.download

import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.paradown.app.data.db.AppDatabase
import com.paradown.app.data.db.DownloadDao
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.DownloadStatus
import com.paradown.app.data.model.SegmentEntity
import com.paradown.app.data.prefs.AppSettings
import com.paradown.app.data.prefs.SettingsRepository
import com.paradown.app.storage.DownloadStorage
import com.paradown.app.storage.PartialFile
import com.paradown.app.storage.PartialWriter
import com.paradown.app.storage.StorageException
import com.paradown.app.util.Checksum
import com.paradown.app.util.FileNames
import com.paradown.app.util.Formatting
import com.paradown.app.util.UrlTools
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Motor principal. Faz probe, decide entre download segmentado e fluxo único,
 * retoma com If-Range, salva checkpoints no Room e finaliza via MediaStore/SAF.
 */
class DownloadEngine(
    context: Context,
    private val database: AppDatabase,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val storage: DownloadStorage,
    private val client: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val probe = ServerProbe(client)
    private val rangedDownloader = RangedDownloader(
        client = client,
        powerManager = appContext.getSystemService(PowerManager::class.java),
    )

    suspend fun run(
        downloadId: Long,
        control: DownloadControl,
        onRuntime: (DownloadRuntime) -> Unit,
    ): DownloadOutcome {
        val appSettings = settings.current
        var retries = 0
        var forcePlain = false

        while (true) {
            val entity = dao.getDownload(downloadId) ?: return DownloadOutcome.FAILED
            control.ensureRunning()
            try {
                return attemptDownload(
                    initial = entity,
                    appSettings = appSettings,
                    control = control,
                    onRuntime = onRuntime,
                    allowResume = retries == 0,
                    forcePlain = forcePlain,
                )
            } catch (e: RemoteChangedException) {
                if (retries >= 1) {
                    return failPermanently(entity.id, e.message ?: "O arquivo remoto mudou.")
                }
                resetForRetry(downloadId, entity.id)
                retries++
            } catch (e: RangeNotSupportedException) {
                if (retries >= 1) {
                    return failPermanently(entity.id, e.message ?: "O servidor não aceita faixas.")
                }
                resetForRetry(downloadId, entity.id)
                retries++
                forcePlain = true
            }
        }
    }

    private suspend fun attemptDownload(
        initial: DownloadEntity,
        appSettings: AppSettings,
        control: DownloadControl,
        onRuntime: (DownloadRuntime) -> Unit,
        allowResume: Boolean,
        forcePlain: Boolean,
    ): DownloadOutcome {
        var entity = initial.copy(
            status = DownloadStatus.PROBING,
            error = null,
            pauseReason = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.updateDownload(entity)

        var partial: PartialFile? = null
        var writer: PartialWriter? = null
        var scheduler: SegmentScheduler? = null
        var singleDownloaded = 0L
        val singleProgress = AtomicLong(0L)
        var remoteInfo: RemoteFileInfo? = null

        suspend fun closeWriter() {
            try {
                writer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao fechar arquivo parcial", e)
            }
            writer = null
        }

        suspend fun persistCurrentProgress() {
            try {
                val currentScheduler = scheduler
                if (currentScheduler != null) {
                    checkpointRanged(
                        downloadId = entity.id,
                        downloaded = currentScheduler.downloadedBytes(),
                        connections = currentScheduler.activeConnections(),
                        segments = currentScheduler.snapshot(),
                    )
                } else {
                    checkpointSingle(
                        downloadId = entity.id,
                        totalBytes = entity.totalBytes,
                        downloaded = singleProgress.get(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao salvar progresso final", e)
            }
        }

        suspend fun handlePause(reason: String?): DownloadOutcome {
            return withContext(NonCancellable) {
                closeWriter()
                persistCurrentProgress()
                try {
                    dao.setStatus(
                        id = entity.id,
                        status = DownloadStatus.PAUSED,
                        error = null,
                        pauseReason = reason ?: "Pausado",
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao registrar pausa", e)
                }
                val downloaded = scheduler?.downloadedBytes() ?: singleProgress.get()
                onRuntime(
                    DownloadRuntime(
                        downloadedBytes = downloaded,
                        totalBytes = entity.totalBytes,
                        activeConnections = 0,
                        maxConnections = appSettings.maxConnections,
                    ),
                )
                DownloadOutcome.PAUSED
            }
        }

        suspend fun handleCancel(): DownloadOutcome {
            return withContext(NonCancellable) {
                closeWriter()
                try {
                    val currentPartial = partial
                    if (currentPartial != null) {
                        currentPartial.delete()
                    } else {
                        entity.contentUri?.let { uri ->
                            runCatching {
                                storage.openExisting(uri, entity.fileName, entity.treeUri).delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao apagar arquivo parcial cancelado", e)
                }
                try {
                    dao.deleteSegments(entity.id)
                    dao.updateDownload(
                        entity.copy(
                            status = DownloadStatus.CANCELED,
                            contentUri = null,
                            error = null,
                            pauseReason = null,
                            connections = 0,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao registrar cancelamento", e)
                }
                onRuntime(DownloadRuntime(totalBytes = entity.totalBytes, activeConnections = 0))
                DownloadOutcome.CANCELED
            }
        }

        suspend fun handleFailure(error: Throwable): DownloadOutcome {
            return withContext(NonCancellable) {
                closeWriter()
                persistCurrentProgress()
                val message = friendlyError(error)
                try {
                    dao.setStatus(
                        id = entity.id,
                        status = DownloadStatus.FAILED,
                        error = message,
                        pauseReason = null,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao registrar erro", e)
                }
                onRuntime(DownloadRuntime(totalBytes = entity.totalBytes, activeConnections = 0))
                DownloadOutcome.FAILED
            }
        }

        try {
            control.ensureRunning()
            val existingSegments = if (allowResume) dao.getSegments(entity.id) else emptyList()
            val validator = strongValidator(entity)
            var resumed = false

            if (allowResume && canAttemptResume(entity, existingSegments, validator)) {
                val resumeInfo = probe.probe(entity.url, control, validator)
                if (isResumeValid(entity, resumeInfo)) {
                    val existingFile = storage.openExisting(
                        uriString = entity.contentUri!!,
                        finalName = entity.fileName,
                        treeUri = entity.treeUri,
                    )
                    if (existingFile.exists()) {
                        partial = existingFile
                        try {
                            writer = existingFile.openWriter(entity.totalBytes, reset = false)
                            scheduler = SegmentScheduler(
                                totalBytes = entity.totalBytes,
                                initialConnections = planConnections(
                                    entity.totalBytes,
                                    appSettings.maxConnections,
                                ),
                                existing = existingSegments.map { it.toSnapshot() },
                            )
                            singleDownloaded = scheduler.downloadedBytes()
                            singleProgress.set(singleDownloaded)
                            remoteInfo = resumeInfo
                            resumed = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Retomada inválida; reiniciando", e)
                            closeWriter()
                            scheduler = null
                        }
                    }
                }

                if (!resumed) {
                    closeWriter()
                    try {
                        partial?.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao apagar parcial inválido", e)
                    }
                    partial = null
                    dao.deleteSegments(entity.id)
                    entity = entity.copy(
                        contentUri = null,
                        totalBytes = -1L,
                        downloadedBytes = 0L,
                        supportsRanges = false,
                        etag = null,
                        lastModified = null,
                        finalUrl = null,
                        treeUri = null,
                        connections = 0,
                        updatedAt = System.currentTimeMillis(),
                    )
                    dao.updateDownload(entity)
                }
            } else if (entity.contentUri != null || existingSegments.isNotEmpty()) {
                closeWriter()
                entity.contentUri?.let { uri ->
                    runCatching {
                        storage.openExisting(uri, entity.fileName, entity.treeUri).delete()
                    }.onFailure { Log.w(TAG, "Falha ao limpar parcial antigo", it) }
                }
                dao.deleteSegments(entity.id)
                entity = entity.copy(
                    contentUri = null,
                    totalBytes = -1L,
                    downloadedBytes = 0L,
                    supportsRanges = false,
                    etag = null,
                    lastModified = null,
                    finalUrl = null,
                    treeUri = null,
                    connections = 0,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.updateDownload(entity)
            }

            if (!resumed) {
                val freshInfo = probe.probe(entity.url, control)
                remoteInfo = freshInfo
                val mime = freshInfo.mimeType
                    ?: mimeFromName(UrlTools.fileNameFromUrl(freshInfo.finalUrl))
                val desiredName = FileNames.fromContentDisposition(freshInfo.contentDisposition)
                    ?: FileNames.defaultNameFor(
                        UrlTools.fileNameFromUrl(freshInfo.finalUrl),
                        mime,
                    )
                val finalName = storage.resolveUniqueName(
                    desired = desiredName,
                    mimeType = mime,
                    treeUri = appSettings.folderTreeUri,
                )
                partial = storage.createPartial(
                    finalName = finalName,
                    mimeType = mime,
                    treeUri = appSettings.folderTreeUri,
                )
                if (freshInfo.totalBytes != 0L) {
                    writer = partial.openWriter(freshInfo.totalBytes, reset = true)
                }
                entity = entity.copy(
                    finalUrl = freshInfo.finalUrl,
                    fileName = finalName,
                    mimeType = mime,
                    totalBytes = freshInfo.totalBytes,
                    downloadedBytes = 0L,
                    supportsRanges = freshInfo.supportsRanges && !forcePlain,
                    etag = freshInfo.etag,
                    lastModified = freshInfo.lastModified,
                    status = DownloadStatus.DOWNLOADING,
                    error = null,
                    pauseReason = null,
                    contentUri = partial.uri.toString(),
                    treeUri = appSettings.folderTreeUri,
                    connections = 0,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.deleteSegments(entity.id)
                if (freshInfo.supportsRanges && freshInfo.totalBytes > 0L && !forcePlain) {
                    val plannedScheduler = SegmentScheduler(
                        totalBytes = freshInfo.totalBytes,
                        initialConnections = planConnections(
                            freshInfo.totalBytes,
                            appSettings.maxConnections,
                        ),
                    )
                    scheduler = plannedScheduler
                    persistSegments(entity.id, plannedScheduler.snapshot())
                } else if (freshInfo.totalBytes > 0L) {
                    persistSegments(
                        entity.id,
                        listOf(
                            SegmentSnapshot(
                                index = 0,
                                start = 0L,
                                end = freshInfo.totalBytes - 1L,
                                position = 0L,
                            ),
                        ),
                    )
                }
                dao.updateDownload(entity)
            } else {
                val resolved = remoteInfo ?: throw DownloadException("Metadata remota ausente.")
                entity = entity.copy(
                    finalUrl = resolved.finalUrl,
                    supportsRanges = true,
                    etag = resolved.etag ?: entity.etag,
                    lastModified = resolved.lastModified ?: entity.lastModified,
                    mimeType = entity.mimeType ?: resolved.mimeType,
                    downloadedBytes = scheduler?.downloadedBytes() ?: entity.downloadedBytes,
                    status = DownloadStatus.DOWNLOADING,
                    error = null,
                    pauseReason = null,
                    contentUri = partial!!.uri.toString(),
                    connections = 0,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.updateDownload(entity)
            }

            control.ensureRunning()
            val currentScheduler = scheduler
            if (currentScheduler != null && !forcePlain) {
                val resolvedInfo = remoteInfo
                    ?: throw DownloadException("Metadata remota ausente para download segmentado.")
                rangedDownloader.run(
                    finalUrl = entity.finalUrl ?: resolvedInfo.finalUrl,
                    rangeValidator = resolvedInfo.rangeValidator ?: strongValidator(entity),
                    totalBytes = entity.totalBytes,
                    scheduler = currentScheduler,
                    writer = writer
                        ?: throw DownloadException("Arquivo parcial não está aberto."),
                    maxConnections = appSettings.maxConnections,
                    speedLimitBytesPerSecond = appSettings.speedLimitBytesPerSecond,
                    control = control,
                    onRuntime = onRuntime,
                    persist = { downloaded, connections, segments ->
                        checkpointRanged(
                            downloadId = entity.id,
                            downloaded = downloaded,
                            connections = connections,
                            segments = segments,
                        )
                    },
                )
                singleDownloaded = currentScheduler.downloadedBytes()
                if (!currentScheduler.isComplete()) {
                    throw DownloadException("Download segmentado terminou incompleto.")
                }
                if (singleDownloaded != entity.totalBytes) {
                    throw DownloadException(
                        "Tamanho baixado (${Formatting.bytes(singleDownloaded)}) " +
                            "difere do tamanho remoto (${Formatting.bytes(entity.totalBytes)}).",
                    )
                }
            } else if (entity.totalBytes != 0L) {
                val currentWriter = writer
                    ?: throw DownloadException("Arquivo parcial não está aberto.")
                singleDownloaded = runSingleStream(
                    entity = entity,
                    writer = currentWriter,
                    control = control,
                    appSettings = appSettings,
                    progress = singleProgress,
                    onRuntime = onRuntime,
                    persist = { downloaded ->
                        checkpointSingle(
                            downloadId = entity.id,
                            totalBytes = entity.totalBytes,
                            downloaded = downloaded,
                        )
                    },
                )
                if (entity.totalBytes > 0L && singleDownloaded != entity.totalBytes) {
                    throw DownloadException(
                        "Tamanho recebido (${Formatting.bytes(singleDownloaded)}) " +
                            "difere do esperado (${Formatting.bytes(entity.totalBytes)}).",
                    )
                }
            } else {
                singleDownloaded = 0L
                singleProgress.set(0L)
                checkpointSingle(entity.id, 0L, 0L)
            }

            val actualPartial = partial
                ?: throw DownloadException("Arquivo parcial ausente na finalização.")
            val actualTotal = if (entity.totalBytes > 0L) {
                entity.totalBytes
            } else {
                singleDownloaded
            }

            val currentWriter = writer
            if (currentWriter != null) {
                val length = currentWriter.currentLength
                val expectedLength = if (currentScheduler != null && !forcePlain) {
                    entity.totalBytes
                } else {
                    singleDownloaded
                }
                if (length < expectedLength) {
                    throw DownloadException(
                        "O arquivo no disco está menor que o esperado. Download incompleto.",
                    )
                }
                if (length != expectedLength) {
                    currentWriter.truncate(expectedLength)
                }
                closeWriter()
            }

            val expectedHash = entity.expectedSha256
            if (!expectedHash.isNullOrBlank()) {
                val actualHash = withContext(Dispatchers.IO) {
                    Checksum.sha256(actualPartial.openInputStream())
                }
                if (!Formatting.sha256Matches(expectedHash, actualHash)) {
                    withContext(NonCancellable) {
                        runCatching { actualPartial.delete() }
                        dao.deleteSegments(entity.id)
                        dao.updateDownload(
                            entity.copy(
                                status = DownloadStatus.FAILED,
                                error = "SHA-256 não confere. O arquivo foi descartado.\n" +
                                    "Esperado: ${expectedHash.lowercase()}\n" +
                                    "Obtido: $actualHash",
                                contentUri = null,
                                downloadedBytes = 0L,
                                connections = 0,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    return DownloadOutcome.FAILED
                }
            }

            control.ensureRunning()
            val publishedUri = withContext(Dispatchers.IO) {
                actualPartial.publish(entity.fileName)
            }
            val completed = entity.copy(
                status = DownloadStatus.COMPLETED,
                totalBytes = actualTotal,
                downloadedBytes = actualTotal,
                contentUri = publishedUri.toString(),
                error = null,
                pauseReason = null,
                connections = 0,
                updatedAt = System.currentTimeMillis(),
            )
            checkpointRanged(
                downloadId = entity.id,
                downloaded = actualTotal,
                connections = 0,
                segments = currentScheduler?.snapshot() ?: singleSegment(actualTotal),
            )
            dao.updateDownload(completed)
            onRuntime(
                DownloadRuntime(
                    bytesPerSecond = 0L,
                    etaSeconds = null,
                    activeConnections = 0,
                    maxConnections = appSettings.maxConnections,
                    downloadedBytes = actualTotal,
                    totalBytes = actualTotal,
                ),
            )
            return DownloadOutcome.COMPLETED
        } catch (e: CancellationException) {
            closeWriter()
            withContext(NonCancellable) {
                persistCurrentProgress()
                runCatching {
                    dao.setStatus(
                        id = entity.id,
                        status = DownloadStatus.PAUSED,
                        error = null,
                        pauseReason = "Interrompido pelo sistema",
                    )
                }
            }
            throw e
        } catch (e: DownloadPausedException) {
            return handlePause(e.reason)
        } catch (e: DownloadCancelledException) {
            return handleCancel()
        } catch (e: RemoteChangedException) {
            closeWriter()
            if (control.isCancelRequested) return handleCancel()
            if (control.isPauseRequested) return handlePause(control.pauseReason)
            throw e
        } catch (e: RangeNotSupportedException) {
            closeWriter()
            if (control.isCancelRequested) return handleCancel()
            if (control.isPauseRequested) return handlePause(control.pauseReason)
            throw e
        } catch (e: Throwable) {
            when {
                control.isCancelRequested -> return handleCancel()
                control.isPauseRequested -> return handlePause(control.pauseReason)
                else -> return handleFailure(e)
            }
        } finally {
            try {
                writer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao fechar writer no finally", e)
            }
            writer = null
        }
    }

    private suspend fun runSingleStream(
        entity: DownloadEntity,
        writer: PartialWriter,
        control: DownloadControl,
        appSettings: AppSettings,
        progress: AtomicLong,
        onRuntime: (DownloadRuntime) -> Unit,
        persist: suspend (Long) -> Unit,
    ): Long = coroutineScope {
        val downloaded = progress
        val limiter = BandwidthLimiter(appSettings.speedLimitBytesPerSecond)
        val reporter = ProgressReporter(
            scope = this,
            totalBytes = entity.totalBytes,
            bytesProvider = { downloaded.get() },
            connectionsProvider = { 1 },
            maxConnections = 1,
            onRuntime = onRuntime,
        )
        reporter.start()
        var failures = 0
        try {
            while (true) {
                control.ensureRunning()
                if (downloaded.get() == 0L) {
                    try {
                        persist(0L)
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao zerar checkpoint", e)
                    }
                }
                val request = Request.Builder()
                    .url(entity.finalUrl ?: entity.url)
                    .get()
                    .header("Accept", "*/*")
                    .header("Accept-Encoding", "identity")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", ServerProbe.USER_AGENT)
                    .build()
                val call = client.newCall(request)
                control.attachCall(SINGLE_CALL_ID, call)
                try {
                    val result = call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw httpStatusException(response)
                        }
                        val encoding = response.header("Content-Encoding")
                        if (encoding != null && !encoding.equals("identity", true)) {
                            throw DownloadException(
                                "O servidor enviou conteúdo comprimido; não é possível salvar o arquivo.",
                            )
                        }
                        val body = response.body ?: throw IOException("Resposta sem corpo.")
                        val contentLength = response.header("Content-Length")?.toLongOrNull()
                            ?: body.contentLength().takeIf { it >= 0L }
                            ?: -1L
                        if (entity.totalBytes > 0L && contentLength > 0L &&
                            contentLength != entity.totalBytes
                        ) {
                            throw DownloadException(
                                "O tamanho do arquivo mudou no servidor. Tente novamente.",
                            )
                        }
                        val buffer = ByteArray(limiter.recommendedChunkSize)
                        var position = 0L
                        var lastCheckpointTime = System.nanoTime()
                        var lastCheckpointBytes = 0L
                        body.byteStream().use { stream ->
                            while (true) {
                                control.ensureRunning()
                                val maxRead = buffer.size
                                limiter.acquire(maxRead)
                                val read = stream.read(buffer, 0, maxRead)
                                if (read < 0) break
                                if (read == 0) {
                                    delay(1L)
                                    continue
                                }
                                writer.write(buffer, 0, read, position)
                                position += read
                                downloaded.set(position)
                                val now = System.nanoTime()
                                if (now - lastCheckpointTime >= CHECKPOINT_INTERVAL_NANOS ||
                                    position - lastCheckpointBytes >= CHECKPOINT_BYTES
                                ) {
                                    persist(position)
                                    lastCheckpointTime = now
                                    lastCheckpointBytes = position
                                }
                            }
                        }
                        if (contentLength >= 0L && position < contentLength) {
                            throw IOException(
                                "A conexão terminou antes do fim do arquivo " +
                                    "(${Formatting.bytes(position)} de " +
                                    "${Formatting.bytes(contentLength)}).",
                            )
                        }
                        if (entity.totalBytes > 0L && position != entity.totalBytes) {
                            throw IOException(
                                "Tamanho recebido (${Formatting.bytes(position)}) diferente " +
                                    "do esperado (${Formatting.bytes(entity.totalBytes)}).",
                            )
                        }
                        position
                    }
                    writer.truncate(result)
                    downloaded.set(result)
                    persist(result)
                    return@coroutineScope result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DownloadPausedException) {
                    throw e
                } catch (e: DownloadCancelledException) {
                    throw e
                } catch (e: Throwable) {
                    control.ensureRunning()
                    if (!isRetryableSingle(e)) throw e
                    failures++
                    if (failures >= MAX_SINGLE_ATTEMPTS) {
                        throw DownloadException(
                            "Falha após $MAX_SINGLE_ATTEMPTS tentativas: " +
                                (e.message ?: "erro de rede"),
                            e,
                        )
                    }
                    writer.truncate(0L)
                    downloaded.set(0L)
                    persist(0L)
                    delay(singleRetryDelayMs(failures, e))
                } finally {
                    control.detachCall(SINGLE_CALL_ID, call)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            0L
        } finally {
            reporter.stop()
        }
    }

    private suspend fun persistSegments(downloadId: Long, segments: List<SegmentSnapshot>) {
        database.withTransaction {
            dao.deleteSegments(downloadId)
            if (segments.isNotEmpty()) {
                dao.insertSegments(segments.map { it.toEntity(downloadId) })
            }
        }
    }

    private suspend fun checkpointRanged(
        downloadId: Long,
        downloaded: Long,
        connections: Int,
        segments: List<SegmentSnapshot>,
    ) {
        database.withTransaction {
            dao.updateProgress(
                id = downloadId,
                downloaded = downloaded,
                connections = connections,
                now = System.currentTimeMillis(),
            )
            dao.deleteSegments(downloadId)
            if (segments.isNotEmpty()) {
                dao.insertSegments(segments.map { it.toEntity(downloadId) })
            }
        }
    }

    private suspend fun checkpointSingle(downloadId: Long, totalBytes: Long, downloaded: Long) {
        database.withTransaction {
            dao.updateProgress(
                id = downloadId,
                downloaded = downloaded,
                connections = if (downloaded in 0..totalBytes && totalBytes > 0L) 1 else 0,
                now = System.currentTimeMillis(),
            )
            if (totalBytes > 0L) {
                dao.deleteSegments(downloadId)
                dao.insertSegments(
                    listOf(
                        SegmentEntity(
                            downloadId = downloadId,
                            segmentIndex = 0,
                            startOffset = 0L,
                            endOffset = totalBytes - 1L,
                            position = downloaded.coerceIn(0L, totalBytes),
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun resetForRetry(downloadId: Long, fallbackEntityId: Long) {
        withContext(NonCancellable) {
            val entity = dao.getDownload(fallbackEntityId) ?: return@withContext
            entity.contentUri?.let { uri ->
                runCatching {
                    storage.openExisting(uri, entity.fileName, entity.treeUri).delete()
                }.onFailure { Log.w(TAG, "Falha ao apagar parcial para reinício", it) }
            }
            dao.deleteSegments(fallbackEntityId)
            dao.updateDownload(
                entity.copy(
                    status = DownloadStatus.QUEUED,
                    finalUrl = null,
                    totalBytes = -1L,
                    downloadedBytes = 0L,
                    supportsRanges = false,
                    etag = null,
                    lastModified = null,
                    contentUri = null,
                    treeUri = null,
                    connections = 0,
                    error = null,
                    pauseReason = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            onReset(downloadId)
        }
    }

    private var resetListener: ((Long) -> Unit)? = null

    fun setResetListener(listener: (Long) -> Unit) {
        resetListener = listener
    }

    private fun onReset(downloadId: Long) {
        resetListener?.invoke(downloadId)
    }

    private suspend fun failPermanently(downloadId: Long, message: String): DownloadOutcome {
        withContext(NonCancellable) {
            dao.setStatus(
                id = downloadId,
                status = DownloadStatus.FAILED,
                error = message,
                pauseReason = null,
            )
        }
        return DownloadOutcome.FAILED
    }

    private fun canAttemptResume(
        entity: DownloadEntity,
        segments: List<SegmentEntity>,
        validator: String?,
    ): Boolean = entity.contentUri != null &&
        segments.isNotEmpty() &&
        entity.totalBytes > 0L &&
        entity.supportsRanges &&
        validator != null

    private fun isResumeValid(entity: DownloadEntity, info: RemoteFileInfo): Boolean {
        if (!info.supportsRanges) return false
        if (info.validationFailed) return false
        if (info.rangeValidation != RangeValidation.MATCHED) return false
        if (info.totalBytes != entity.totalBytes) return false
        if (entity.etag != null && info.etag != null && entity.etag != info.etag) return false
        if (entity.lastModified != null && info.lastModified != null &&
            entity.lastModified != info.lastModified
        ) {
            return false
        }
        return true
    }

    private fun strongValidator(entity: DownloadEntity): String? {
        val etag = entity.etag
            ?.takeUnless { it.startsWith("W/", ignoreCase = true) }
            ?.takeIf { it.isNotBlank() }
        return etag ?: entity.lastModified?.takeIf { it.isNotBlank() }
    }

    private fun planConnections(totalBytes: Long, maxConnections: Int): Int {
        if (totalBytes <= SMALL_FILE_BYTES) return 1
        return minOf(AppSettings.INITIAL_CONNECTIONS, maxConnections).coerceAtLeast(1)
    }

    private fun mimeFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val extension = name.substring(dot + 1).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun friendlyError(error: Throwable): String {
        val raw = when (error) {
            is HttpStatusException -> error.message
            is DownloadException -> error.message
            is StorageException ->
                "Falha no armazenamento: ${error.message ?: "erro desconhecido"}"
            is IOException ->
                "Falha de rede: ${error.message ?: "conexão interrompida"}"
            else ->
                "Falha inesperada: ${error.message ?: error.javaClass.simpleName}"
        } ?: "Falha desconhecida."
        return raw.take(400)
    }

    private fun isRetryableSingle(error: Throwable): Boolean = when (error) {
        is StorageException -> false
        is HttpStatusException -> isRetryableHttpCode(error.code)
        is DownloadException -> false
        is IOException -> true
        else -> false
    }

    private fun singleRetryDelayMs(failures: Int, error: Throwable): Long {
        val serverDelay = (error as? HttpStatusException)?.retryAfterMs
        val exponential = 1_000L shl (failures - 1).coerceIn(0, 5)
        return (serverDelay ?: exponential).coerceIn(0L, 30_000L) +
            Random.nextLong(0L, 300L)
    }

    private fun singleSegment(total: Long): List<SegmentSnapshot> =
        if (total > 0L) {
            listOf(
                SegmentSnapshot(
                    index = 0,
                    start = 0L,
                    end = total - 1L,
                    position = total,
                ),
            )
        } else {
            emptyList()
        }

    private fun SegmentEntity.toSnapshot(): SegmentSnapshot = SegmentSnapshot(
        index = segmentIndex,
        start = startOffset,
        end = endOffset,
        position = position,
    )

    private fun SegmentSnapshot.toEntity(downloadId: Long): SegmentEntity = SegmentEntity(
        downloadId = downloadId,
        segmentIndex = index,
        startOffset = start,
        endOffset = end,
        position = position,
    )

    companion object {
        private const val TAG = "ParaDownEngine"
        private const val SINGLE_CALL_ID = -2
        private const val SMALL_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_SINGLE_ATTEMPTS = 5
        private const val CHECKPOINT_INTERVAL_NANOS = 500_000_000L
        private const val CHECKPOINT_BYTES = 1L * 1024L * 1024L
    }
}
