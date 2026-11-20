package com.paradown.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.DownloadStatus
import com.paradown.app.data.model.SegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeDownload(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM segments WHERE downloadId = :id ORDER BY segmentIndex ASC")
    fun observeSegments(id: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownload(id: Long): DownloadEntity?

    @Query("SELECT * FROM segments WHERE downloadId = :id ORDER BY segmentIndex ASC")
    suspend fun getSegments(id: Long): List<SegmentEntity>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getQueued(limit: Int): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'PAUSED' ORDER BY createdAt ASC")
    suspend fun getPaused(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'QUEUED'")
    suspend fun countQueued(): Int

    @Insert
    suspend fun insertDownload(download: DownloadEntity): Long

    @Update
    suspend fun updateDownload(download: DownloadEntity)

    @Query(
        "UPDATE downloads SET downloadedBytes = :downloaded, connections = :connections, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun updateProgress(id: Long, downloaded: Long, connections: Int, now: Long)

    @Query(
        "UPDATE downloads SET status = :status, error = :error, pauseReason = :pauseReason, " +
            "updatedAt = :now WHERE id = :id",
    )
    suspend fun setStatus(
        id: Long,
        status: DownloadStatus,
        error: String?,
        pauseReason: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        "UPDATE downloads SET status = 'PAUSED', pauseReason = 'Interrompido pelo sistema', " +
            "updatedAt = :now WHERE status IN ('PROBING', 'DOWNLOADING')",
    )
    suspend fun markRunningAsInterrupted(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: Long)

    @Query("SELECT * FROM segments WHERE downloadId = :id")
    suspend fun getSegmentsRaw(id: Long): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE downloadId = :id")
    suspend fun deleteSegments(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("UPDATE downloads SET status = 'QUEUED', error = NULL, pauseReason = NULL, updatedAt = :now WHERE id = :id")
    suspend fun requeue(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'PAUSED', error = NULL, pauseReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun pauseQueued(id: Long, reason: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'CANCELED', error = NULL, pauseReason = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markCanceled(id: Long, now: Long = System.currentTimeMillis())
}
