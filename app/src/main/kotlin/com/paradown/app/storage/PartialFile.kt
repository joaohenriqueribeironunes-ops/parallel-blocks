package com.paradown.app.storage

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

class StorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface PartialFile {
    val finalName: String
    val treeUri: String?
    val uri: android.net.Uri

    suspend fun openWriter(totalBytes: Long, reset: Boolean): PartialWriter
    suspend fun openInputStream(): InputStream
    suspend fun publish(finalName: String): android.net.Uri
    suspend fun delete()
    suspend fun exists(): Boolean
}

interface PartialWriter : Closeable {
    val currentLength: Long
    fun write(buffer: ByteArray, offset: Int, count: Int, position: Long)
    fun truncate(length: Long)
}
