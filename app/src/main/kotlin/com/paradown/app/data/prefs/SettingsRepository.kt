package com.paradown.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val onlyWifi: Boolean = false,
    val speedLimitBytesPerSecond: Long = 0L,
    val folderTreeUri: String? = null,
    val clipboardMonitorEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_CONNECTIONS = 8
        const val MIN_CONNECTIONS = 1
        const val MAX_CONNECTIONS = 8
        const val INITIAL_CONNECTIONS = 4
        const val MAX_ACTIVE_DOWNLOADS = 3
    }
}

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings
        get() = _settings.value

    @Synchronized
    private fun read(): AppSettings {
        val maxConnections = prefs.getInt(
            KEY_MAX_CONNECTIONS,
            AppSettings.DEFAULT_MAX_CONNECTIONS,
        ).coerceIn(AppSettings.MIN_CONNECTIONS, AppSettings.MAX_CONNECTIONS)
        val theme = runCatching {
            ThemeMode.valueOf(
                prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name,
            )
        }.getOrDefault(ThemeMode.SYSTEM)
        return AppSettings(
            maxConnections = maxConnections,
            onlyWifi = prefs.getBoolean(KEY_ONLY_WIFI, false),
            speedLimitBytesPerSecond = prefs.getLong(KEY_SPEED_LIMIT, 0L).coerceAtLeast(0L),
            folderTreeUri = prefs.getString(KEY_FOLDER_URI, null),
            clipboardMonitorEnabled = prefs.getBoolean(KEY_CLIPBOARD_MONITOR, true),
            themeMode = theme,
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
        )
    }

    @Synchronized
    private fun mutate(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putInt(KEY_MAX_CONNECTIONS, updated.maxConnections)
            .putBoolean(KEY_ONLY_WIFI, updated.onlyWifi)
            .putLong(KEY_SPEED_LIMIT, updated.speedLimitBytesPerSecond)
            .putString(KEY_FOLDER_URI, updated.folderTreeUri)
            .putBoolean(KEY_CLIPBOARD_MONITOR, updated.clipboardMonitorEnabled)
            .putString(KEY_THEME, updated.themeMode.name)
            .putBoolean(KEY_ONBOARDING_DONE, updated.onboardingDone)
            .apply()
        _settings.value = updated
    }

    fun setMaxConnections(value: Int) = mutate {
        it.copy(
            maxConnections = value.coerceIn(
                AppSettings.MIN_CONNECTIONS,
                AppSettings.MAX_CONNECTIONS,
            ),
        )
    }

    fun setOnlyWifi(enabled: Boolean) = mutate { it.copy(onlyWifi = enabled) }

    fun setSpeedLimitBytesPerSecond(value: Long) = mutate {
        it.copy(speedLimitBytesPerSecond = value.coerceAtLeast(0L))
    }

    fun setFolderTreeUri(uri: String?) = mutate { it.copy(folderTreeUri = uri) }

    fun setClipboardMonitorEnabled(enabled: Boolean) = mutate {
        it.copy(clipboardMonitorEnabled = enabled)
    }

    fun setThemeMode(mode: ThemeMode) = mutate { it.copy(themeMode = mode) }

    fun setOnboardingDone(done: Boolean) = mutate { it.copy(onboardingDone = done) }

    companion object {
        private const val PREFS_NAME = "paradown_settings"
        private const val KEY_MAX_CONNECTIONS = "max_connections"
        private const val KEY_ONLY_WIFI = "only_wifi"
        private const val KEY_SPEED_LIMIT = "speed_limit"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_CLIPBOARD_MONITOR = "clipboard_monitor"
        private const val KEY_THEME = "theme"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
