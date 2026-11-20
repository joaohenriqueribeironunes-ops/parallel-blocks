package com.paradown.app.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlTools {

    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val trailingJunk = charArrayOf('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')

    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
            else -> null
        } ?: return null
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        return url.toString()
    }

    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = urlRegex.find(text)?.value ?: return normalize(text)
        var cleaned = match.trimEnd(*trailingJunk)
        while (cleaned.endsWith(").") || cleaned.endsWith("].)") || cleaned.endsWith(".(")) {
            cleaned = cleaned.dropLast(1).trimEnd(*trailingJunk)
        }
        return normalize(cleaned)
    }

    fun fileNameFromUrl(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val segment = httpUrl.pathSegments.lastOrNull { it.isNotBlank() } ?: return null
        return segment.ifBlank { null }
    }

    fun extensionFromUrl(url: String): String? {
        val name = fileNameFromUrl(url) ?: return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase()
    }
}
