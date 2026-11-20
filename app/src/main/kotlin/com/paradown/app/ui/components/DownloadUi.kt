package com.paradown.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paradown.app.R
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.DownloadStatus
import com.paradown.app.data.model.SegmentEntity
import com.paradown.app.download.ActiveDownloadState
import com.paradown.app.util.Formatting
import androidx.compose.ui.res.stringResource
import kotlin.math.max

enum class DownloadFilter {
    ALL,
    ACTIVE,
    PAUSED,
    COMPLETED,
}

fun List<DownloadEntity>.filteredBy(filter: DownloadFilter): List<DownloadEntity> = when (filter) {
    DownloadFilter.ALL -> this
    DownloadFilter.ACTIVE -> filter {
        it.status.isRunning || it.status == DownloadStatus.QUEUED
    }

    DownloadFilter.PAUSED -> filter { it.status == DownloadStatus.PAUSED }
    DownloadFilter.COMPLETED -> filter { it.status == DownloadStatus.COMPLETED }
}

fun extensionLabel(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.lastIndex) return "FILE"
    return fileName.substring(dot + 1).uppercase().take(4)
}

private fun progressOf(download: DownloadEntity, runtime: ActiveDownloadState?): Float {
    val total = if (download.totalBytes > 0L) download.totalBytes else runtime?.totalBytes ?: -1L
    if (total <= 0L) return 0f
    val downloaded = max(download.downloadedBytes, runtime?.downloadedBytes ?: 0L)
    return (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

@Composable
fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.DOWNLOADING,
    DownloadStatus.PROBING,
    DownloadStatus.QUEUED,
    -> MaterialTheme.colorScheme.primary

    DownloadStatus.PAUSED -> MaterialTheme.colorScheme.secondary
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun statusLabel(download: DownloadEntity, runtime: ActiveDownloadState?): String {
    val total = if (download.totalBytes > 0L) download.totalBytes else runtime?.totalBytes ?: -1L
    val downloaded = max(download.downloadedBytes, runtime?.downloadedBytes ?: 0L)
    return when (download.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
        DownloadStatus.PROBING -> stringResource(R.string.status_probing)
        DownloadStatus.DOWNLOADING -> if (total > 0L) {
            stringResource(
                R.string.status_downloading_progress,
                Formatting.percent(progressOf(download, runtime)),
                Formatting.bytes(downloaded),
                Formatting.bytes(total),
            )
        } else {
            stringResource(R.string.status_downloading_unknown, Formatting.bytes(downloaded))
        }

        DownloadStatus.PAUSED -> stringResource(R.string.status_paused)
        DownloadStatus.COMPLETED ->
            stringResource(R.string.status_completed, Formatting.bytes(total))

        DownloadStatus.FAILED -> stringResource(R.string.status_failed)
        DownloadStatus.CANCELED -> stringResource(R.string.status_canceled)
    }
}

@Composable
fun FileBadge(fileName: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = extensionLabel(fileName),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
fun DownloadCard(
    download: DownloadEntity,
    runtime: ActiveDownloadState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FileBadge(download.fileName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLabel(download, runtime),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(download.status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (download.status) {
                    DownloadStatus.COMPLETED -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    DownloadStatus.FAILED -> Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }
            }

            val active = download.status == DownloadStatus.DOWNLOADING ||
                download.status == DownloadStatus.PROBING
            val showProgress = active ||
                download.status == DownloadStatus.PAUSED ||
                download.status == DownloadStatus.QUEUED
            if (showProgress) {
                val progress = progressOf(download, runtime)
                if (progress > 0f || download.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                } else if (active) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                }
            }

            if (active) {
                val current = runtime
                if (current != null && current.bytesPerSecond > 0L) {
                    val connections = current.activeConnections.coerceAtLeast(1)
                    val eta = current.etaSeconds
                    Text(
                        text = if (eta != null) {
                            stringResource(
                                R.string.status_speed_eta,
                                Formatting.speed(current.bytesPerSecond),
                                Formatting.eta(eta),
                                connections,
                            )
                        } else {
                            stringResource(
                                R.string.status_speed,
                                Formatting.speed(current.bytesPerSecond),
                                connections,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (download.status == DownloadStatus.PAUSED) {
                download.pauseReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (download.status == DownloadStatus.FAILED) {
                download.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SegmentProgressBar(
    segments: List<SegmentEntity>,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        segments.forEach { segment ->
            val weight = segment.totalBytes.coerceAtLeast(1L).toFloat()
            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val fraction = segment.progressFraction
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(
                                if (segment.isComplete) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    monospace: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = valueColor,
            modifier = Modifier.weight(1.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
