package com.paradown.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object AppIntents {

    fun openFile(context: Context, uriString: String, mimeType: String?): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val resolvedMime = mimeType
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, resolvedMime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return startFirst(context, listOf(intent))
    }

    fun openBatterySettings(context: Context): Boolean {
        val battery = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val miuiPower = Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        ).putExtra("package_name", context.packageName)
            .putExtra(
                "package_label",
                context.applicationInfo.loadLabel(context.packageManager).toString(),
            )
        return startFirst(context, listOf(battery, miuiPower, appDetails(context)))
    }

    fun openAutostartSettings(context: Context): Boolean {
        val miuiAutostart = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        val miuiAutostartAlt = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartActivity",
            ),
        )
        return startFirst(context, listOf(miuiAutostart, miuiAutostartAlt, appDetails(context)))
    }

    fun openAppDetails(context: Context): Boolean = startFirst(context, listOf(appDetails(context)))

    private fun appDetails(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    private fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val available = runCatching {
                intent.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
            if (!available) continue
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }
}
