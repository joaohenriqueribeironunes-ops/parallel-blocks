package com.paradown.app.util

import android.webkit.MimeTypeMap
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object FileNames {

    private val invalidChars = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val whitespace = Regex("""\s+""")

    fun sanitize(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace(invalidChars, "_")
        name = name.replace(whitespace, " ").trim().trim('.')
        if (name.length > 180) {
            val dot = name.lastIndexOf('.')
            name = if (dot in 1..170) {
                name.substring(0, 170) + name.substring(dot)
            } else {
                name.take(180)
            }
        }
        return name.ifBlank { "download" }
    }

    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(';')
        var plainName: String? = null
        var extendedName: String? = null
        for (part in parts.drop(1)) {
            val trimmed = part.trim()
            when {
                trimmed.startsWith("filename*=", ignoreCase = true) -> {
                    extendedName = decodeExtended(trimmed.substringAfter('=').trim())
                }

                trimmed.startsWith("filename=", ignoreCase = true) -> {
                    plainName = trimmed.substringAfter('=').trim().trim('"')
                }
            }
        }
        val chosen = extendedName?.takeIf { it.isNotBlank() }
            ?: plainName?.takeIf { it.isNotBlank() }
            ?: return null
        return sanitize(chosen)
    }

    private fun decodeExtended(value: String): String? {
        val withoutQuotes = value.trim().trim('"')
        val firstQuote = withoutQuotes.indexOf('\'')
        if (firstQuote < 0) return null
        val secondQuote = withoutQuotes.indexOf('\'', firstQuote + 1)
        if (secondQuote < 0) return null
        val charsetName = withoutQuotes.substring(0, firstQuote).ifBlank { "UTF-8" }
        val encoded = withoutQuotes.substring(secondQuote + 1)
        return runCatching {
            val charset = charset(charsetName)
            URLDecoder.decode(encoded.replace("+", "%2B"), charset.name())
        }.getOrNull()
    }

    private fun charset(name: String): java.nio.charset.Charset = runCatching {
        java.nio.charset.Charset.forName(name)
    }.getOrDefault(StandardCharsets.UTF_8)

    fun withMimeExtension(name: String, mimeType: String?): String {
        if (mimeType.isNullOrBlank()) return name
        val base = name.substringBefore('?').substringBefore('#')
        val lastDot = base.lastIndexOf('.')
        val hasExtension = lastDot > 0 && lastDot < base.length - 1
        if (hasExtension) return name
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: return name
        return "$name.$extension"
    }

    fun defaultNameFor(urlFileName: String?, mimeType: String?): String {
        val fromUrl = urlFileName?.let { sanitize(URLDecoder.decode(it, StandardCharsets.UTF_8.name())) }
        val base = fromUrl?.takeIf { it.isNotBlank() && it != "/" } ?: "download"
        return withMimeExtension(base, mimeType)
    }

    /**
     * Gera um nome que não colide com os já existentes, preservando a extensão.
     */
    fun uniqueName(desired: String, existingNames: Set<String>): String {
        val sanitized = sanitize(desired)
        val existingLower = existingNames.mapTo(HashSet()) { it.lowercase() }
        if (sanitized.lowercase() !in existingLower) return sanitized

        val dot = sanitized.lastIndexOf('.')
        val stem = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val extension = if (dot > 0) sanitized.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (candidate.lowercase() !in existingLower) return candidate
            index++
            if (index > 10_000) {
                return "$stem-${System.currentTimeMillis()}$extension"
            }
        }
    }

    fun partName(finalName: String): String = "$finalName.part"
}
