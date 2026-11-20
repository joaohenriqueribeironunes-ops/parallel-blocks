package com.paradown.app

import android.app.Application
import com.paradown.app.capture.ClipboardMonitor
import com.paradown.app.data.db.AppDatabase
import com.paradown.app.data.prefs.SettingsRepository
import com.paradown.app.download.DownloadCoordinator
import com.paradown.app.download.HttpClients
import com.paradown.app.storage.DownloadStorage
import okhttp3.OkHttpClient

class ParaDownApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settings: SettingsRepository
        private set

    lateinit var httpClient: OkHttpClient
        private set

    lateinit var storage: DownloadStorage
        private set

    lateinit var coordinator: DownloadCoordinator
        private set

    lateinit var clipboardMonitor: ClipboardMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.build(this)
        settings = SettingsRepository(this)
        clipboardMonitor = ClipboardMonitor(this, settings)
        httpClient = HttpClients.build()
        storage = DownloadStorage(this)
        coordinator = DownloadCoordinator(
            context = this,
            database = database,
            dao = database.downloadDao(),
            settings = settings,
            storage = storage,
            client = httpClient,
        )
        coordinator.start()
    }
}
