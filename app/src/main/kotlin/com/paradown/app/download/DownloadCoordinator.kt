package com.paradown.app.download

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.paradown.app.data.db.AppDatabase
import com.paradown.app.data.db.DownloadDao
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.DownloadStatus
import com.paradown.app.data.prefs.AppSettings
import com.paradown.app.data.prefs.SettingsRepository
import com.paradown.app.network.NetworkMonitor
import com.paradown.app.network.NetworkStatus
import com.paradown.app.service.DownloadService
import com.paradown.app.storage.DownloadStorage
import com.paradown.app.util.FileNames
import com.paradown.app.util.Formatting
import com.paradown.app.util.UrlTools
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Fila global de downloads. Mantém no máximo 3 downloads ativos, inicia o
 * foreground service quando há trabalho e reage a mudanças de rede e
 * preferências. Todo o estado persistente fica no Room.
 */
class DownloadCoordinator(
    context: Context,
    database: AppDatabase,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val storage: DownloadStorage,
    client: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = DownloadEngine(appContext, database, dao, settings, storage, client)
    private val networkMonitor = NetworkMonitor(appContext)
    private val controls = ConcurrentHashMap<Long, DownloadControl>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val runtimes = MutableStateFlow<Map<Long, DownloadRuntime>>(emptyMap())
    private val serviceRunning = MutableStateFlow(false)
    private val autoPaused = MutableStateFlow<Set<Long>>(emptySet())
    private val drainMutex = Mutex()
    private val started = AtomicBoolean(false)

    private val downloadSnapshot = combine(
        dao.observeDownloads(),
        runtimes,
    ) { downloads, runtimeMap ->
        downloads to runtimeMap
    }

    val state: StateFlow<CoordinatorState> = combine(
        downloadSnapshot,
        networkMonitor.status,
        settings.settings,
        serviceRunning,
        autoPaused,
    ) { (downloads, runtimeMap), network, appSettings, serviceUp, autoIds ->
        buildState(downloads, runtimeMap, network, appSettings, serviceUp, autoIds.size)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = CoordinatorState(
            network = networkMonitor.status.value,
            onlyWifi = settings.current.onlyWifi,
        ),
    )

    /**
     * Recupera estados interrompidos e passa a observar rede e preferências.
     * Não inicia serviço nem downloads automaticamente.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        networkMonitor.start()
        scope.launch {
            try {
                dao.markRunningAsInterrupted()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao recuperar downloads interrompidos", e)
            }
        }
        scope.launch {
            networkMonitor.status.collect { status ->
                if (isNetworkAllowed(status)) {
                    resumeAutoPaused()
                    drain()
                } else {
                    pauseForConnectivity(reasonFor(status))
                }
            }
        }
        scope.launch {
            settings.settings
                .map { it.onlyWifi }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    val status = networkMonitor.status.value
                    if (isNetworkAllowed(status)) {
                        resumeAutoPaused()
                        drain()
                    } else {
                        pauseForConnectivity(reasonFor(status))
                    }
                }
        }
    }

    /**
     * Insere um link na fila e garante que o serviço esteja ativo.
     * Retorna o id local criado.
     */
    suspend fun enqueue(
        url: String,
        fileName: String? = null,
        mimeType: String? = null,
        expectedSha256: String? = null,
    ): Long {
        val normalized = UrlTools.normalize(url)
            ?: throw IllegalArgumentException("Link inválido. Use um endereço http ou https.")
        val resolvedName = fileName
            ?.takeIf { it.isNotBlank() }
            ?.let { FileNames.sanitize(it) }
            ?: FileNames.defaultNameFor(UrlTools.fileNameFromUrl(normalized), mimeType)
        val id = dao.insertDownload(
            DownloadEntity(
                url = normalized,
                fileName = resolvedName,
                mimeType = mimeType,
                expectedSha256 = Formatting.normalizeSha256(expectedSha256),
                status = DownloadStatus.QUEUED,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        ensureService()
        return id
    }

    suspend fun pause(id: Long) {
        clearAutoPaused(id)
        val control = controls[id]
        val job = jobs[id]
        if (control != null && job != null && job.isActive) {
            control.pause(PAUSE_REASON)
            return
        }
        val entity = dao.getDownload(id) ?: return
        when (entity.status) {
            DownloadStatus.QUEUED ->
                dao.pauseQueued(id, PAUSE_REASON)

            DownloadStatus.PROBING,
            DownloadStatus.DOWNLOADING,
            ->
                dao.setStatus(
                    id = id,
                    status = DownloadStatus.PAUSED,
                    error = null,
                    pauseReason = PAUSE_REASON,
                )

            else -> return
        }
        requestStopIfIdle()
    }

    suspend fun resume(id: Long) {
        val entity = dao.getDownload(id) ?: return
        if (entity.status == DownloadStatus.COMPLETED || entity.status.isRunning) return
        clearAutoPaused(id)
        dao.requeue(id)
        ensureService()
        drain()
    }

    suspend fun cancel(id: Long) {
        clearAutoPaused(id)
        val control = controls[id]
        val job = jobs[id]
        if (control != null && job != null && job.isActive) {
            control.cancel()
            return
        }
        val entity = dao.getDownload(id) ?: return
        if (entity.status == DownloadStatus.COMPLETED) return
        entity.contentUri?.let { uri ->
            runCatching {
                storage.openExisting(uri, entity.fileName, entity.treeUri).delete()
            }.onFailure { Log.w(TAG, "Falha ao apagar parcial cancelado", it) }
        }
        dao.deleteSegments(id)
        dao.updateDownload(
            entity.copy(
                status = DownloadStatus.CANCELED,
                downloadedBytes = 0L,
                contentUri = null,
                connections = 0,
                error = null,
                pauseReason = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        requestStopIfIdle()
    }

    suspend fun pauseAll(reason: String = PAUSE_REASON) {
        autoPaused.value = emptySet()
        controls.values.forEach { it.pause(reason) }
        dao.getQueued(MAX_BULK_QUERY).forEach { dao.pauseQueued(it.id, reason) }
        requestStopIfIdle()
    }

    suspend fun resumeAll() {
        autoPaused.value = emptySet()
        dao.getPaused().forEach { dao.requeue(it.id) }
        ensureService()
        drain()
    }

    fun cancelAllActive() {
        val activeIds = jobs.keys.toList()
        activeIds.forEach { id -> controls[id]?.cancel() }
    }

    fun onServiceStarted() {
        serviceRunning.value = true
        scope.launch {
            resumeAutoPaused()
            drain()
        }
    }

    fun onServiceStopped(reason: String? = null) {
        serviceRunning.value = false
        val pauseReason = reason ?: "Serviço encerrado"
        controls.values.forEach { it.pause(pauseReason) }
    }

    fun shutdown() {
        runCatching { scope.cancel() }
        networkMonitor.stop()
    }

    private fun buildState(
        downloads: List<DownloadEntity>,
        runtimeMap: Map<Long, DownloadRuntime>,
        network: NetworkStatus,
        appSettings: AppSettings,
        serviceUp: Boolean,
        autoPausedCount: Int,
    ): CoordinatorState {
        val active = downloads
            .asSequence()
            .filter { it.status == DownloadStatus.PROBING || it.status == DownloadStatus.DOWNLOADING }
            .map { entity ->
                val runtime = runtimeMap[entity.id]
                ActiveDownloadState(
                    id = entity.id,
                    fileName = entity.fileName,
                    mimeType = entity.mimeType,
                    downloadedBytes = maxOf(
                        entity.downloadedBytes,
                        runtime?.downloadedBytes ?: 0L,
                    ),
                    totalBytes = when {
                        entity.totalBytes > 0L -> entity.totalBytes
                        runtime != null && runtime.totalBytes > 0L -> runtime.totalBytes
                        else -> -1L
                    },
                    bytesPerSecond = runtime?.bytesPerSecond ?: 0L,
                    etaSeconds = runtime?.etaSeconds,
                    activeConnections = runtime?.activeConnections ?: entity.connections,
                    maxConnections = runtime?.maxConnections ?: appSettings.maxConnections,
                    status = entity.status,
                )
            }
            .toList()
        val waitingReason = when {
            !network.connected -> "Sem conexão"
            appSettings.onlyWifi && !network.isWifiLike -> "Aguardando Wi-Fi"
            else -> null
        }
        return CoordinatorState(
            active = active,
            queued = downloads.count { it.status == DownloadStatus.QUEUED },
            paused = downloads.count { it.status == DownloadStatus.PAUSED },
            completed = downloads.count { it.status == DownloadStatus.COMPLETED },
            failed = downloads.count { it.status == DownloadStatus.FAILED },
            network = network,
            onlyWifi = appSettings.onlyWifi,
            waitingReason = waitingReason,
            autoPausedCount = autoPausedCount,
            serviceRunning = serviceUp,
        )
    }

    private suspend fun drain() {
        drainMutex.withLock {
            if (!serviceRunning.value) return
            if (waitReason() != null) return
            val freeSlots = AppSettings.MAX_ACTIVE_DOWNLOADS - jobs.size
            if (freeSlots <= 0) return
            val queued = dao.getQueued(freeSlots)
            for (entity in queued) {
                if (jobs.containsKey(entity.id)) continue
                startJob(entity)
            }
        }
    }

    private fun startJob(entity: DownloadEntity) {
        val id = entity.id
        val control = DownloadControl()
        controls[id] = control
        runtimes.update {
            it + (id to DownloadRuntime(
                maxConnections = settings.current.maxConnections,
                totalBytes = entity.totalBytes,
            ))
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: DownloadOutcome? = null
            try {
                outcome = engine.run(id, control) { runtime ->
                    runtimes.update { current -> current + (id to runtime) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Falha não tratada no download $id", e)
                runCatching {
                    dao.setStatus(
                        id = id,
                        status = DownloadStatus.FAILED,
                        error = (e.message ?: "Falha inesperada").take(400),
                        pauseReason = null,
                    )
                }
                outcome = DownloadOutcome.FAILED
            } finally {
                jobs.remove(id)
                controls.remove(id)
                runtimes.update { it - id }
                withContext(NonCancellable) {
                    handleJobFinished(id, outcome)
                }
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun handleJobFinished(id: Long, outcome: DownloadOutcome?) {
        if (outcome == DownloadOutcome.PAUSED &&
            autoPaused.value.contains(id) &&
            waitReason() == null
        ) {
            val entity = dao.getDownload(id)
            if (entity != null && entity.status == DownloadStatus.PAUSED) {
                clearAutoPaused(id)
                dao.requeue(id)
            }
        } else if (outcome != DownloadOutcome.PAUSED) {
            clearAutoPaused(id)
        }
        drain()
        requestStopIfIdle()
    }

    private suspend fun resumeAutoPaused() {
        if (waitReason() != null) return
        val ids = autoPaused.value
        for (id in ids) {
            if (jobs.containsKey(id)) continue
            clearAutoPaused(id)
            val entity = dao.getDownload(id) ?: continue
            if (entity.status == DownloadStatus.PAUSED) {
                dao.requeue(id)
            }
        }
    }

    private fun pauseForConnectivity(reason: String) {
        for (id in jobs.keys) {
            val control = controls[id] ?: continue
            if (autoPaused.value.contains(id)) continue
            autoPaused.update { it + id }
            control.pause(reason)
        }
    }

    private suspend fun requestStopIfIdle() {
        if (jobs.isNotEmpty()) return
        if (autoPaused.value.isNotEmpty()) return
        if (dao.countQueued() > 0) return
        if (serviceRunning.value) {
            appContext.stopService(Intent(appContext, DownloadService::class.java))
        }
    }

    private fun ensureService() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, DownloadService::class.java),
            )
        }.onFailure {
            Log.e(TAG, "Não foi possível iniciar o serviço de download", it)
        }
    }

    private fun isNetworkAllowed(status: NetworkStatus): Boolean {
        if (!status.connected) return false
        return !settings.current.onlyWifi || status.isWifiLike
    }

    private fun reasonFor(status: NetworkStatus): String = when {
        !status.connected -> "Sem conexão"
        settings.current.onlyWifi && !status.isWifiLike -> "Aguardando Wi-Fi"
        else -> "Aguardando rede"
    }

    private fun waitReason(): String? {
        val status = networkMonitor.status.value
        return when {
            !status.connected -> "Sem conexão"
            settings.current.onlyWifi && !status.isWifiLike -> "Aguardando Wi-Fi"
            else -> null
        }
    }

    private fun clearAutoPaused(id: Long) {
        autoPaused.update { it - id }
    }

    companion object {
        private const val TAG = "ParaDownCoordinator"
        private const val PAUSE_REASON = "Pausado"
        private const val MAX_BULK_QUERY = 10_000
    }
}
