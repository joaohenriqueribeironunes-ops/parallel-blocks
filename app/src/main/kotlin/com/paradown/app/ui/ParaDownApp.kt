package com.paradown.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paradown.app.ParaDownApplication
import com.paradown.app.R
import com.paradown.app.data.db.DownloadDao
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.SegmentEntity
import com.paradown.app.download.CoordinatorState
import com.paradown.app.ui.details.DetailsScreen
import com.paradown.app.ui.home.HomeScreen
import com.paradown.app.ui.onboarding.OnboardingScreen
import com.paradown.app.ui.settings.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private object Route {
    const val HOME = "home"
    const val DETAILS = "details"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
}

@Composable
fun ParaDownApp(app: ParaDownApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { app.database.downloadDao() }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val downloads by dao.observeDownloads().collectAsStateWithLifecycle(emptyList())
    val coordinatorState by app.coordinator.state.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val clipboardSuggestion by app.clipboardMonitor.suggestion
        .collectAsStateWithLifecycle(null)

    var route by rememberSaveable {
        mutableStateOf(
            if (app.settings.current.onboardingDone) Route.HOME else Route.ONBOARDING,
        )
    }
    var detailsId by rememberSaveable { mutableStateOf(-1L) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(
                    context.getString(
                        R.string.action_error,
                        e.message ?: context.getString(R.string.error_unknown),
                    ),
                )
            }
        }
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun enqueue(url: String, sha256: String?) {
        requestNotificationPermissionIfNeeded()
        launchAction {
            app.coordinator.enqueue(url = url, expectedSha256 = sha256)
            toast(context.getString(R.string.add_started))
        }
    }

    fun openFile(download: DownloadEntity) {
        val uri = download.contentUri
        val opened = uri != null && AppIntents.openFile(context, uri, download.mimeType)
        if (!opened) {
            toast(context.getString(R.string.file_open_error))
        }
    }

    when (route) {
        Route.ONBOARDING -> {
            OnboardingScreen(
                onFinish = {
                    app.settings.setOnboardingDone(true)
                    route = Route.HOME
                },
            )
        }

        Route.DETAILS -> {
            BackHandler { route = Route.HOME }
            DetailsRoute(
                id = detailsId,
                dao = dao,
                coordinatorState = coordinatorState,
                onBack = { route = Route.HOME },
                onPause = { launchAction { app.coordinator.pause(detailsId) } },
                onResume = { launchAction { app.coordinator.resume(detailsId) } },
                onCancel = { launchAction { app.coordinator.cancel(detailsId) } },
                onOpen = { download -> openFile(download) },
            )
        }

        Route.SETTINGS -> {
            BackHandler { route = Route.HOME }
            SettingsScreen(
                settings = settings,
                onBack = { route = Route.HOME },
                onConnectionsChanged = app.settings::setMaxConnections,
                onOnlyWifiChanged = app.settings::setOnlyWifi,
                onSpeedLimitChanged = app.settings::setSpeedLimitBytesPerSecond,
                onFolderChanged = app.settings::setFolderTreeUri,
                onClipboardChanged = app.settings::setClipboardMonitorEnabled,
                onThemeChanged = app.settings::setThemeMode,
            )
        }

        else -> {
            HomeScreen(
                downloads = downloads,
                state = coordinatorState,
                clipboardSuggestion = clipboardSuggestion,
                onOpenDownload = { id ->
                    detailsId = id
                    route = Route.DETAILS
                },
                onOpenSettings = { route = Route.SETTINGS },
                onAddDownload = { showAddDialog = true },
                onClipboardDownload = { suggestion ->
                    app.clipboardMonitor.consume()
                    enqueue(suggestion.url, null)
                },
                onClipboardDismiss = { app.clipboardMonitor.dismiss() },
                onPauseAll = {
                    launchAction { app.coordinator.pauseAll() }
                },
            )
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, sha256 ->
                showAddDialog = false
                enqueue(url, sha256)
            },
        )
    }
}

@Composable
private fun DetailsRoute(
    id: Long,
    dao: DownloadDao,
    coordinatorState: CoordinatorState,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (DownloadEntity) -> Unit,
) {
    val download by remember(id) { dao.observeDownload(id) }
        .collectAsStateWithLifecycle(initialValue = null)
    val segments by remember(id) { dao.observeSegments(id) }
        .collectAsStateWithLifecycle(initialValue = emptyList<SegmentEntity>())
    val runtime = coordinatorState.active.firstOrNull { it.id == id }

    DetailsScreen(
        download = download,
        segments = segments,
        runtime = runtime,
        onBack = onBack,
        onPause = onPause,
        onResume = onResume,
        onCancel = onCancel,
        onOpen = { download?.let(onOpen) },
    )
}
