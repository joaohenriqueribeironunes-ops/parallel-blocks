package com.paradown.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import com.paradown.app.util.FileNames
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Grava arquivos na pasta Downloads via MediaStore (padrão) ou em uma árvore
 * SAF escolhida pelo usuário. Nunca usa MANAGE_EXTERNAL_STORAGE.
 */
class DownloadStorage(private val context: Context) {

    private val resolver = context.applicationContext.contentResolver

    suspend fun resolveUniqueName(
        desired: String,
        mimeType: String?,
        treeUri: String?,
    ): String = withContext(Dispatchers.IO) {
        val names = existingNames(treeUri)
        FileNames.uniqueName(FileNames.withMimeExtension(desired, mimeType), names)
    }

    suspend fun createPartial(
        finalName: String,
        mimeType: String?,
        treeUri: String?,
    ): PartialFile = withContext(Dispatchers.IO) {
        if (treeUri.isNullOrBlank()) {
            createMediaStorePartial(finalName, mimeType)
        } else {
            createSafPartial(finalName, mimeType, treeUri)
        }
    }

    fun openExisting(
        uriString: String,
        finalName: String,
        treeUri: String?,
    ): PartialFile {
        val uri = Uri.parse(uriString)
        return if (treeUri.isNullOrBlank()) {
            MediaStorePartialFile(resolver, uri, finalName, null)
        } else {
            SafPartialFile(context, resolver, uri, finalName, treeUri)
        }
    }

    private fun ensureExternalStorageMounted() {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED, true)) {
            throw StorageException(
                "O armazenamento externo não está disponível. Verifique o dispositivo.",
            )
        }
    }

    private fun existingNames(treeUri: String?): Set<String> = try {
        if (treeUri.isNullOrBlank()) {
            ensureExternalStorageMounted()
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%${DEFAULT_RELATIVE_PATH}%")
            val names = HashSet<String>()
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(nameColumn)?.let { names.add(it) }
                }
            }
            names
        } else {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: throw StorageException("Pasta de destino inválida ou sem permissão.")
            if (!tree.exists() || !tree.isDirectory) {
                throw StorageException("A pasta escolhida não existe mais. Escolha outra nas configurações.")
            }
            if (!tree.canRead()) {
                throw StorageException("Sem permissão de leitura na pasta escolhida.")
            }
            tree.listFiles().mapNotNullTo(HashSet()) { it.name }
        }
    } catch (e: SecurityException) {
        throw StorageException("Sem permissão para acessar a pasta de destino.", e)
    } catch (e: StorageException) {
        throw e
    } catch (e: Exception) {
        throw StorageException("Não foi possível listar a pasta de destino.", e)
    }

    private fun createMediaStorePartial(finalName: String, mimeType: String?): PartialFile {
        ensureExternalStorageMounted()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FileNames.partName(finalName))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: OCTET_STREAM)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: SecurityException) {
            throw StorageException("Sem permissão para gravar em Downloads.", e)
        } catch (e: Exception) {
            throw StorageException("Não foi possível criar o arquivo em Downloads.", e)
        } ?: throw StorageException("O sistema não criou o arquivo em Downloads.")

        return MediaStorePartialFile(resolver, uri, finalName, null)
    }

    private fun createSafPartial(
        finalName: String,
        mimeType: String?,
        treeUri: String,
    ): PartialFile {
        val tree = try {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        } catch (e: Exception) {
            throw StorageException("Não foi possível abrir a pasta escolhida.", e)
        } ?: throw StorageException("Pasta de destino inválida.")
        if (!tree.exists() || !tree.isDirectory) {
            throw StorageException("A pasta escolhida não existe mais. Escolha outra nas configurações.")
        }
        val partName = FileNames.partName(finalName)
        val document = try {
            tree.createFile(mimeType ?: OCTET_STREAM, partName)
        } catch (e: SecurityException) {
            throw StorageException("Sem permissão de escrita na pasta escolhida.", e)
        } catch (e: Exception) {
            throw StorageException("Não foi possível criar o arquivo na pasta escolhida.", e)
        } ?: throw StorageException("A pasta escolhida não aceitou a criação do arquivo.")

        if (document.name != null && document.name != partName) {
            runCatching {
                DocumentsContract.renameDocument(resolver, document.uri, partName)
            }
        }
        return SafPartialFile(context, resolver, document.uri, finalName, treeUri)
    }

    companion object {
        const val RELATIVE_PATH = "Download/ParaDown/"
        const val DEFAULT_RELATIVE_PATH = "Download/ParaDown"
        private const val OCTET_STREAM = "application/octet-stream"
    }
}

internal class MediaStorePartialFile(
    private val resolver: android.content.ContentResolver,
    override val uri: Uri,
    override val finalName: String,
    override val treeUri: String?,
) : PartialFile {

    override suspend fun openWriter(totalBytes: Long, reset: Boolean): PartialWriter =
        withContext(Dispatchers.IO) {
            val pfd = try {
                resolver.openFileDescriptor(uri, "rw")
            } catch (e: FileNotFoundException) {
                throw StorageException("O arquivo parcial desapareceu.", e)
            } catch (e: SecurityException) {
                throw StorageException("Sem permissão para escrever no arquivo parcial.", e)
            } ?: throw StorageException("Não foi possível abrir o arquivo parcial para escrita.")
            FdPartialWriter.open(pfd, totalBytes, reset)
        }

    override suspend fun openInputStream(): InputStream = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            throw StorageException("Não foi possível ler o arquivo para verificar o checksum.", e)
        } ?: throw StorageException("Não foi possível abrir o arquivo para leitura.")
    }

    override suspend fun publish(finalName: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val updated = try {
            resolver.update(uri, values, null, null)
        } catch (e: SecurityException) {
            throw StorageException("Sem permissão para finalizar o arquivo em Downloads.", e)
        }
        if (updated <= 0) {
            throw StorageException("O sistema não confirmou a finalização do arquivo.")
        }
        uri
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        try {
            resolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            throw StorageException("Sem permissão para apagar o arquivo parcial.", e)
        }
        Unit
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

internal class SafPartialFile(
    private val context: Context,
    private val resolver: android.content.ContentResolver,
    override val uri: Uri,
    override val finalName: String,
    override val treeUri: String?,
) : PartialFile {

    override suspend fun openWriter(totalBytes: Long, reset: Boolean): PartialWriter =
        withContext(Dispatchers.IO) {
            val pfd = try {
                resolver.openFileDescriptor(uri, "rw")
            } catch (e: FileNotFoundException) {
                throw StorageException("O arquivo parcial desapareceu.", e)
            } catch (e: SecurityException) {
                throw StorageException("Sem permissão para escrever no arquivo parcial.", e)
            } ?: throw StorageException("Não foi possível abrir o arquivo parcial para escrita.")
            FdPartialWriter.open(pfd, totalBytes, reset)
        }

    override suspend fun openInputStream(): InputStream = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            throw StorageException("Não foi possível ler o arquivo para verificar o checksum.", e)
        } ?: throw StorageException("Não foi possível abrir o arquivo para leitura.")
    }

    override suspend fun publish(finalName: String): Uri = withContext(Dispatchers.IO) {
        val renamed = try {
            DocumentsContract.renameDocument(resolver, uri, finalName)
        } catch (e: Exception) {
            throw StorageException("Não foi possível renomear o arquivo final.", e)
        }
        if (renamed == null) {
            throw StorageException("O provedor não permitiu renomear o arquivo final.")
        }
        renamed
    }

    override suspend fun delete() = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Arquivo já não existe.
            }
        }
        Unit
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        try {
            DocumentFile.fromSingleUri(context, uri)?.exists() ?: false
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Escrita posicional direta no descritor de arquivo usando Os.pwrite.
 * Funciona com MediaStore e provedores SAF que entregam arquivos regulares.
 */
internal class FdPartialWriter private constructor(
    private val fd: FileDescriptor,
    private val pfd: ParcelFileDescriptor?,
) : PartialWriter {

    override val currentLength: Long
        get() = try {
            Os.fstat(fd).st_size
        } catch (e: ErrnoException) {
            throw StorageException("Não foi possível obter o tamanho do arquivo parcial.", e)
        }

    override fun write(buffer: ByteArray, offset: Int, count: Int, position: Long) {
        if (count <= 0) return
        var written = 0
        while (written < count) {
            try {
                val result = Os.pwrite(
                    fd,
                    buffer,
                    offset + written,
                    count - written,
                    position + written,
                )
                if (result <= 0) {
                    throw IOException("O armazenamento não aceitou a escrita dos dados.")
                }
                written += result
            } catch (e: ErrnoException) {
                throw StorageException(
                    if (e.errno == OsConstants.ENOSPC) {
                        "Sem espaço livre no dispositivo."
                    } else {
                        "Falha ao gravar no armazenamento (errno ${e.errno})."
                    },
                    e,
                )
            }
        }
    }

    override fun truncate(length: Long) {
        try {
            Os.ftruncate(fd, length)
        } catch (e: ErrnoException) {
            throw StorageException("Não foi possível ajustar o tamanho do arquivo parcial.", e)
        }
    }

    override fun close() {
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
        } finally {
            try {
                pfd?.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        fun open(pfd: ParcelFileDescriptor, totalBytes: Long, reset: Boolean): PartialWriter {
            val duplicate = try {
                Os.dup(pfd.fileDescriptor)
            } catch (e: ErrnoException) {
                try {
                    pfd.close()
                } catch (_: IOException) {
                }
                throw StorageException("Não foi possível abrir o descritor do arquivo.", e)
            }
            try {
                pfd.close()
            } catch (_: IOException) {
            }
            try {
                val stat = Os.fstat(duplicate)
                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw StorageException(
                        "A pasta escolhida não oferece escrita direta. Escolha uma pasta local.",
                    )
                }
                val length = stat.st_size
                if (totalBytes > 0L) {
                    if (reset) {
                        Os.ftruncate(duplicate, 0L)
                        Os.ftruncate(duplicate, totalBytes)
                    } else {
                        if (length < totalBytes) {
                            throw StorageException(
                                "O arquivo parcial está incompleto e não pode ser retomado.",
                            )
                        }
                        if (length > totalBytes) {
                            Os.ftruncate(duplicate, totalBytes)
                        }
                    }
                } else if (reset) {
                    Os.ftruncate(duplicate, 0L)
                }
                return FdPartialWriter(duplicate, null)
            } catch (e: Throwable) {
                try {
                    Os.close(duplicate)
                } catch (_: ErrnoException) {
                }
                if (e is StorageException) throw e
                if (e is ErrnoException) {
                    throw StorageException("Não foi possível preparar o arquivo parcial.", e)
                }
                throw e
            }
        }
    }
}
