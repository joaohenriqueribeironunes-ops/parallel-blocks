package com.paradown.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paradown.app.ParaDownApplication
import com.paradown.app.data.prefs.ThemeMode
import com.paradown.app.ui.theme.ParaDownTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ParaDownApplication
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            ParaDownTheme(darkTheme = darkTheme) {
                ParaDownApp(app)
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ParaDownApplication).clipboardMonitor.onForeground()
    }

    override fun onStop() {
        (application as ParaDownApplication).clipboardMonitor.onBackground()
        super.onStop()
    }
}
