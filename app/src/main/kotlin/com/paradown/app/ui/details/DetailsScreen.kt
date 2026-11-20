package com.paradown.app.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paradown.app.R
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.data.model.DownloadStatus
import com.paradown.app.data.model.SegmentEntity
import com.paradown.app.download.ActiveDownloadState
import com.paradown.app.ui.components.FileBadge
import com.paradown.app.ui.components.InfoRow
import com.paradown.app.ui.components.SegmentProgressBar
import com.paradown.app.ui.components.statusColor
import com.paradown.app.ui.components.statusLabel
import com.paradown.app.util.Formatting
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    download: DownloadEntity?,
    segments: List<SegmentEntity>,
    runtime: ActiveDownloadState?,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    var showCancelDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.capture_cancel_button),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (download == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.details_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FileBadge(download.fileName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.fileName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLabel(download, runtime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor(download.status),
                    )
                }
            }

            SegmentProgressBar(segments = segments)

            val total = if (download.totalBytes > 0L) {
                download.totalBytes
            } else {
                runtime?.totalBytes ?: -1L
            }
            val downloaded = max(download.downloadedBytes, runtime?.downloadedBytes ?: 0L)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = stringResource(R.string.details_downloaded),
                    value = Formatting.bytes(downloaded),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = stringResource(R.string.details_size),
                    value = if (total > 0L) Formatting.bytes(total) else stringResource(
                        R.string.details_unknown,
                    ),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = stringResource(R.string.details_connections),
                    value = (runtime?.activeConnections ?: download.connections).toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            if (download.status == DownloadStatus.DOWNLOADING && runtime != null &&
                runtime.bytesPerSecond > 0L
            ) {
                Text(
                    text = if (runtime.etaSeconds != null) {
                        stringResource(
                            R.string.status_speed_eta,
                            Formatting.speed(runtime.bytesPerSecond),
                            Formatting.eta(runtime.etaSeconds),
                            runtime.activeConnections.coerceAtLeast(1),
                        )
                    } else {
                        stringResource(
                            R.string.status_speed,
                            Formatting.speed(runtime.bytesPerSecond),
                            runtime.activeConnections.coerceAtLeast(1),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.details_segments),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.details_segments_count, segments.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SegmentProgressBar(segments = segments)
                    InfoRow(
                        label = stringResource(R.string.details_status),
                        value = statusLabel(download, runtime),
                    )
                    InfoRow(
                        label = stringResource(R.string.details_sha),
                        value = download.expectedSha256
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.details_sha_unset),
                        monospace = download.expectedSha256 != null,
                    )
                    download.pauseReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        InfoRow(
                            label = stringResource(R.string.details_pause_reason),
                            value = reason,
                            valueColor = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    download.error?.takeIf { it.isNotBlank() }?.let { error ->
                        InfoRow(
                            label = stringResource(R.string.details_error),
                            value = error,
                            valueColor = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            when (download.status) {
                DownloadStatus.COMPLETED -> {
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = download.contentUri != null,
                    ) {
                        Text(stringResource(R.string.details_open))
                    }
                }

                DownloadStatus.PAUSED -> {
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.details_resume))
                    }
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.details_cancel),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                DownloadStatus.FAILED -> {
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.details_retry))
                    }
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.details_cancel),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                DownloadStatus.CANCELED -> {
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.details_restart))
                    }
                }

                DownloadStatus.QUEUED,
                DownloadStatus.PROBING,
                DownloadStatus.DOWNLOADING,
                -> {
                    Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.details_pause))
                    }
                    OutlinedButton(
                        onClick = { showCancelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.details_cancel),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCancelDialog && download != null) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.details_cancel_title)) },
            text = { Text(stringResource(R.string.details_cancel_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        onCancel()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.details_cancel_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.details_cancel_dismiss))
                }
            },
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
