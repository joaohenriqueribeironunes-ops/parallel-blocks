package com.paradown.app.util

import java.io.InputStream
import java.security.MessageDigest

object Checksum {

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") {
            "%02x".format(java.util.Locale.US, it)
        }
    }
}
