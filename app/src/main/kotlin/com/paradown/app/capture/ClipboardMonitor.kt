package com.paradown.app.capture

import android.content.ClipboardManager
import android.content.Context
import com.paradown.app.data.prefs.SettingsRepository
import com.paradown.app.util.FileNames
import com.paradown.app.util.UrlTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ClipboardSuggestion(
    val url: String,
    val suggestedFileName: String,
)

/**
 * Monitora o clipboard apenas enquanto uma tela do app está em primeiro plano.
 * A partir do Android 10 o sistema bloqueia leituras em segundo plano.
 */
class ClipboardMonitor(
    context: Context,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    private val _suggestion = MutableStateFlow<ClipboardSuggestion?>(null)
    val suggestion: StateFlow<ClipboardSuggestion?> = _suggestion.asStateFlow()

    private var listening = false
    private var lastSeenUrl: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        inspect()
    }

    fun onForeground() {
        if (!listening) {
            clipboard.addPrimaryClipChangedListener(listener)
            listening = true
        }
        inspect()
    }

    fun onBackground() {
        if (listening) {
            clipboard.removePrimaryClipChangedListener(listener)
            listening = false
        }
    }

    fun refresh() {
        inspect()
    }

    fun dismiss() {
        _suggestion.value = null
    }

    fun consume(): ClipboardSuggestion? {
        val current = _suggestion.value
        _suggestion.value = null
        return current
    }

    private fun inspect() {
        if (!settings.current.clipboardMonitorEnabled) {
            _suggestion.value = null
            return
        }
        val text = runCatching {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            clip.getItemAt(0).coerceToText(appContext)?.toString()
        }.getOrNull() ?: return
        val url = UrlTools.extractFirstUrl(text) ?: return
        if (url == lastSeenUrl) return
        lastSeenUrl = url
        _suggestion.value = ClipboardSuggestion(
            url = url,
            suggestedFileName = FileNames.defaultNameFor(
                UrlTools.fileNameFromUrl(url),
                null,
            ),
        )
    }
}
