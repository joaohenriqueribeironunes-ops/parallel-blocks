package com.paradown.app.util

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

object Formatting {

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = -1
        while (amount >= 1024.0 && unit < units.lastIndex) {
            amount /= 1024.0
            unit++
        }
        val decimals = if (amount >= 100) 0 else 1
        return String.format(Locale.getDefault(), "%.${decimals}f %s", amount, units[unit])
    }

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        return "${bytes(bytesPerSecond)}/s"
    }

    fun eta(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "—"
        if (seconds < 60) return "$seconds s"
        val minutes = seconds / 60
        val restSeconds = seconds % 60
        if (minutes < 60) return "$minutes min ${restSeconds.toString().padStart(2, '0')} s"
        val hours = minutes / 60
        val restMinutes = minutes % 60
        return "$hours h ${restMinutes.toString().padStart(2, '0')} min"
    }

    fun percent(fraction: Float): String =
        "${(fraction.coerceIn(0f, 1f) * 100f).roundToLong()}%"

    fun checksumDigest(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(Locale.US, it) }

    fun normalizeSha256(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.filter { it.isLetterOrDigit() }.lowercase(Locale.US)
        return cleaned.takeIf { it.length == 64 } ?: value.trim().lowercase(Locale.US)
    }

    fun sha256Matches(expected: String, actual: String): Boolean {
        val normalizedExpected = normalizeSha256(expected) ?: return false
        val normalizedActual = actual.trim().lowercase(Locale.US)
        return MessageDigest.isEqual(
            normalizedExpected.toByteArray(Charsets.US_ASCII),
            normalizedActual.toByteArray(Charsets.US_ASCII),
        )
    }
}
