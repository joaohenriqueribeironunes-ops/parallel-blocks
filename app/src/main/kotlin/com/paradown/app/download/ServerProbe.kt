package com.paradown.app.download

import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class RemoteFileInfo(
    val originalUrl: String,
    val finalUrl: String,
    val totalBytes: Long,
    val supportsRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val rangeValidation: RangeValidation = RangeValidation.NOT_REQUESTED,
) {
    val rangeValidator: String?
        get() {
            val strongEtag = etag?.takeUnless { it.startsWith("W/", ignoreCase = true) }
            return strongEtag ?: lastModified
        }

    val validationFailed: Boolean
        get() = rangeValidation == RangeValidation.FAILED
}

enum class RangeValidation {
    NOT_REQUESTED,
    MATCHED,
    FAILED,
}

class ServerProbe(
    private val client: OkHttpClient,
) {

    suspend fun probe(
        url: String,
        control: DownloadControl,
        ifRange: String? = null,
    ): RemoteFileInfo = withContext(Dispatchers.IO) {
        control.ensureRunning()
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .headers(commonHeaders())
            .build()

        var headInfo: HeadInfo? = null
        var headFailure: HttpStatusException? = null
        try {
            execute(headRequest, control).use { response ->
                if (response.isSuccessful) {
                    headInfo = HeadInfo(
                        finalUrl = response.request.url.toString(),
                        totalBytes = itLength(response),
                        supportsRanges = response.header("Accept-Ranges")
                            ?.contains("bytes", ignoreCase = true) == true,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        contentDisposition = response.header("Content-Disposition"),
                        mimeType = mimeOf(response),
                    )
                } else {
                    headFailure = httpStatusException(response)
                }
            }
        } catch (e: HttpStatusException) {
            headFailure = e
        } catch (e: IOException) {
            control.ensureRunning()
        }

        val info = headInfo
        val needGetProbe = ifRange != null ||
            info == null ||
            info.totalBytes <= 0L ||
            !info.supportsRanges

        if (!needGetProbe && info != null) {
            return@withContext RemoteFileInfo(
                originalUrl = url,
                finalUrl = info.finalUrl,
                totalBytes = info.totalBytes,
                supportsRanges = info.supportsRanges,
                etag = info.etag,
                lastModified = info.lastModified,
                contentDisposition = info.contentDisposition,
                mimeType = info.mimeType,
                rangeValidation = RangeValidation.NOT_REQUESTED,
            )
        }

        val getRequestBuilder = Request.Builder()
            .url(info?.finalUrl ?: url)
            .get()
            .header("Range", "bytes=0-0")
            .headers(commonHeaders())
        if (ifRange != null) {
            getRequestBuilder.header("If-Range", ifRange)
        }

        val getResponse = try {
            execute(getRequestBuilder.build(), control)
        } catch (e: IOException) {
            control.ensureRunning()
            headFailure?.let { throw it }
            throw e
        }

        return@withContext getResponse.use { response ->
            if (response.code == 416) {
                val range = parseContentRange(response.header("Content-Range"))
                if (range?.total == 0L) {
                    return@use RemoteFileInfo(
                        originalUrl = url,
                        finalUrl = response.request.url.toString(),
                        totalBytes = 0L,
                        supportsRanges = true,
                        etag = response.header("ETag") ?: info?.etag,
                        lastModified = response.header("Last-Modified") ?: info?.lastModified,
                        contentDisposition = response.header("Content-Disposition")
                            ?: info?.contentDisposition,
                        mimeType = mimeOf(response) ?: info?.mimeType,
                        rangeValidation = if (ifRange == null) {
                            RangeValidation.NOT_REQUESTED
                        } else {
                            RangeValidation.FAILED
                        },
                    )
                }
                throw httpStatusException(response)
            }
            if (!response.isSuccessful) {
                throw httpStatusException(response)
            }

            val validation = when {
                ifRange == null -> RangeValidation.NOT_REQUESTED
                response.code == 206 -> RangeValidation.MATCHED
                else -> RangeValidation.FAILED
            }
            val range = parseContentRange(response.header("Content-Range"))
            val supports = response.code == 206 && range?.total != null
            val total = when {
                supports -> range?.total ?: -1L
                else -> itLength(response)
            }
            RemoteFileInfo(
                originalUrl = url,
                finalUrl = response.request.url.toString(),
                totalBytes = total,
                supportsRanges = supports,
                etag = response.header("ETag") ?: info?.etag,
                lastModified = response.header("Last-Modified") ?: info?.lastModified,
                contentDisposition = response.header("Content-Disposition")
                    ?: info?.contentDisposition,
                mimeType = mimeOf(response) ?: info?.mimeType,
                rangeValidation = validation,
            )
        }
    }

    private fun execute(request: Request, control: DownloadControl): Response {
        control.ensureRunning()
        val call = client.newCall(request)
        control.attachCall(ServerProbe.PROBE_CALL_ID, call)
        try {
            return call.execute()
        } catch (e: IOException) {
            control.ensureRunning()
            throw e
        } finally {
            control.detachCall(ServerProbe.PROBE_CALL_ID, call)
        }
    }

    private fun itLength(response: Response): Long =
        response.header("Content-Length")?.toLongOrNull()
            ?: response.body?.contentLength()?.takeIf { it >= 0L }
            ?: -1L

    private fun mimeOf(response: Response): String? =
        response.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    private fun commonHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Accept", "*/*")
        .add("Accept-Encoding", "identity")
        .add("Cache-Control", "no-cache")
        .build()

    private data class HeadInfo(
        val finalUrl: String,
        val totalBytes: Long,
        val supportsRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    companion object {
        const val USER_AGENT = "ParaDown/1.0 (Android)"
        const val PROBE_CALL_ID = -1
    }
}

data class ContentRange(
    val start: Long?,
    val end: Long?,
    val total: Long?,
)

fun parseContentRange(value: String?): ContentRange? {
    if (value.isNullOrBlank()) return null
    val match = Regex("""bytes\s+(\d+|\*)-(\d+|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        .find(value.trim()) ?: return null
    val start = match.groupValues[1].takeUnless { it == "*" }?.toLongOrNull()
    val end = match.groupValues[2].takeUnless { it == "*" }?.toLongOrNull()
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    return ContentRange(start, end, total)
}

fun parseRetryAfterMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { seconds ->
        return TimeUnit.SECONDS.toMillis(seconds.coerceIn(0L, 3600L))
    }
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )
    for (pattern in formats) {
        try {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = true
            }
            val date: Date = format.parse(value.trim()) ?: continue
            return (date.time - System.currentTimeMillis()).coerceIn(0L, 3600_000L)
        } catch (_: ParseException) {
            // Tenta o próximo formato.
        }
    }
    return null
}

fun httpStatusException(response: Response): HttpStatusException {
    val code = response.code
    val retryAfter = parseRetryAfterMs(response.header("Retry-After"))
    val message = when (code) {
        401, 403 -> "Acesso negado pelo servidor (HTTP $code)."
        404 -> "Arquivo não encontrado no servidor (HTTP 404)."
        408 -> "O servidor demorou demais para responder (HTTP 408)."
        416 -> "Faixa inválida solicitada ao servidor (HTTP 416)."
        429 -> "O servidor limitou as requisições (HTTP 429). Tente mais tarde."
        500, 502, 503, 504 -> "Servidor temporariamente indisponível (HTTP $code)."
        else -> "O servidor respondeu HTTP $code."
    }
    return HttpStatusException(code, retryAfter, message)
}

fun isRetryableHttpCode(code: Int): Boolean =
    code == 408 || code == 425 || code == 429 || code == 500 || code == 502 ||
        code == 503 || code == 504
