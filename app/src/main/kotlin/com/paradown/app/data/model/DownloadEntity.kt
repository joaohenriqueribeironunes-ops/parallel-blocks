package com.paradown.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val finalUrl: String? = null,
    val fileName: String,
    val mimeType: String? = null,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val pauseReason: String? = null,
    val supportsRanges: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val connections: Int = 0,
    val expectedSha256: String? = null,
    val contentUri: String? = null,
    val treeUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progressFraction: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes > 0 -> (downloadedBytes.toDouble() / totalBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()

            else -> 0f
        }

    val hasKnownSize: Boolean
        get() = totalBytes > 0L
}

@Entity(
    tableName = "segments",
    primaryKeys = ["downloadId", "segmentIndex"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("downloadId")],
)
data class SegmentEntity(
    val downloadId: Long,
    val segmentIndex: Int,
    val startOffset: Long,
    val endOffset: Long,
    val position: Long,
) {
    val totalBytes: Long
        get() = (endOffset - startOffset + 1).coerceAtLeast(0)

    val downloadedBytes: Long
        get() = (position - startOffset).coerceIn(0, totalBytes)

    val progressFraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()

    val isComplete: Boolean
        get() = position > endOffset
}
