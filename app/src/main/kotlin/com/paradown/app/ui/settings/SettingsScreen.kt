package com.paradown.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paradown.app.R
import com.paradown.app.data.prefs.AppSettings
import com.paradown.app.data.prefs.ThemeMode
import com.paradown.app.ui.AppIntents
import com.paradown.app.util.Formatting
import kotlin.math.roundToInt

private val speedOptions = listOf(
    0L,
    512L * 1024L,
    1L * 1024L * 1024L,
    2L * 1024L * 1024L,
    5L * 1024L * 1024L,
    10L * 1024L * 1024L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onConnectionsChanged: (Int) -> Unit,
    onOnlyWifiChanged: (Boolean) -> Unit,
    onSpeedLimitChanged: (Long) -> Unit,
    onFolderChanged: (String?) -> Unit,
    onClipboardChanged: (Boolean) -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    var sliderValue by remember(settings.maxConnections) {
        mutableFloatStateOf(settings.maxConnections.toFloat())
    }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            folderError = null
            onFolderChanged(uri.toString())
            Toast.makeText(
                context,
                context.getString(R.string.settings_folder_changed),
                Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            val message = e.message ?: context.getString(R.string.error_unknown)
            folderError = context.getString(R.string.settings_folder_error, message)
        }
    }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_connections_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_connections_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.settings_connections_value,
                        sliderValue.roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    onValueChangeFinished = {
                        onConnectionsChanged(sliderValue.roundToInt())
                    },
                )
            }

            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_folder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = folderLabel(context, settings.folderTreeUri),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_folder_choose))
                    }
                    OutlinedButton(
                        onClick = {
                            folderError = null
                            onFolderChanged(null)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = settings.folderTreeUri != null,
                    ) {
                        Text(stringResource(R.string.settings_folder_reset))
                    }
                }
                folderError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_folder_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_wifi_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.settings_wifi_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.onlyWifi,
                        onCheckedChange = onOnlyWifiChanged,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_speed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box {
                    OutlinedButton(
                        onClick = { speedMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (settings.speedLimitBytesPerSecond <= 0L) {
                                stringResource(R.string.settings_speed_unlimited)
                            } else {
                                Formatting.speed(settings.speedLimitBytesPerSecond)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text("▾")
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false },
                    ) {
                        speedOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (option <= 0L) {
                                            stringResource(R.string.settings_speed_unlimited)
                                        } else {
                                            Formatting.speed(option)
                                        },
                                    )
                                },
                                onClick = {
                                    speedMenuExpanded = false
                                    onSpeedLimitChanged(option)
                                },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.settings_speed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_clipboard_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.settings_clipboard_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.clipboardMonitorEnabled,
                        onCheckedChange = onClipboardChanged,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_theme_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.SYSTEM,
                        onClick = { onThemeChanged(ThemeMode.SYSTEM) },
                        label = { Text(stringResource(R.string.settings_theme_system)) },
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.LIGHT,
                        onClick = { onThemeChanged(ThemeMode.LIGHT) },
                        label = { Text(stringResource(R.string.settings_theme_light)) },
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.DARK,
                        onClick = { onThemeChanged(ThemeMode.DARK) },
                        label = { Text(stringResource(R.string.settings_theme_dark)) },
                    )
                }
            }

            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_miui_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_miui_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!AppIntents.openBatterySettings(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_unavailable),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_miui_battery))
                    }
                    OutlinedButton(
                        onClick = {
                            if (!AppIntents.openAutostartSettings(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_unavailable),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_miui_autostart))
                    }
                }
            }

            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_about_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_version, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

private fun folderLabel(context: Context, uriString: String?): String {
    if (uriString.isNullOrBlank()) return context.getString(R.string.settings_folder_system)
    return runCatching {
        val uri = Uri.parse(uriString)
        val segment = uri.lastPathSegment ?: uriString
        Uri.decode(segment)
    }.getOrDefault(uriString)
}
