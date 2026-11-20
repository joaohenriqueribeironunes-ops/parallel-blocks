package com.paradown.app.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paradown.app.R
import com.paradown.app.capture.ClipboardSuggestion
import com.paradown.app.data.model.DownloadEntity
import com.paradown.app.download.CoordinatorState
import com.paradown.app.ui.components.DownloadCard
import com.paradown.app.ui.components.DownloadFilter
import com.paradown.app.ui.components.filteredBy
import com.paradown.app.util.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    downloads: List<DownloadEntity>,
    state: CoordinatorState,
    clipboardSuggestion: ClipboardSuggestion?,
    onOpenDownload: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onAddDownload: () -> Unit,
    onClipboardDownload: (ClipboardSuggestion) -> Unit,
    onClipboardDismiss: () -> Unit,
    onPauseAll: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL.name) }
    val selectedFilter = DownloadFilter.valueOf(filter)
    val visibleDownloads = downloads.filteredBy(selectedFilter)
    val activeById = state.active.associateBy { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                        )
                        if (state.queued > 0) {
                            Text(
                                text = stringResource(R.string.home_queued_detail, state.queued),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDownload,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_add_download)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (clipboardSuggestion != null) {
                item(key = "clipboard") {
                    ClipboardBanner(
                        suggestion = clipboardSuggestion,
                        onDownload = { onClipboardDownload(clipboardSuggestion) },
                        onDismiss = onClipboardDismiss,
                    )
                }
            }

            if (state.active.isNotEmpty()) {
                item(key = "active-summary") {
                    ActiveSummaryCard(state = state, onPauseAll = onPauseAll)
                }
            } else if (state.isWaitingForNetwork) {
                item(key = "waiting") {
                    WaitingCard(state = state)
                }
            }

            item(key = "filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedFilter == DownloadFilter.ALL,
                        onClick = { filter = DownloadFilter.ALL.name },
                        label = { Text(stringResource(R.string.home_filter_all)) },
                    )
                    FilterChip(
                        selected = selectedFilter == DownloadFilter.ACTIVE,
                        onClick = { filter = DownloadFilter.ACTIVE.name },
                        label = { Text(stringResource(R.string.home_filter_active)) },
                    )
                    FilterChip(
                        selected = selectedFilter == DownloadFilter.PAUSED,
                        onClick = { filter = DownloadFilter.PAUSED.name },
                        label = { Text(stringResource(R.string.home_filter_paused)) },
                    )
                    FilterChip(
                        selected = selectedFilter == DownloadFilter.COMPLETED,
                        onClick = { filter = DownloadFilter.COMPLETED.name },
                        label = { Text(stringResource(R.string.home_filter_completed)) },
                    )
                }
            }

            if (visibleDownloads.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        filtered = downloads.isNotEmpty(),
                        onAddDownload = onAddDownload,
                    )
                }
            } else {
                items(items = visibleDownloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        runtime = activeById[download.id],
                        onClick = { onOpenDownload(download.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardBanner(
    suggestion: ClipboardSuggestion,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.clipboard_suggestion_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.clipboard_suggestion_detail,
                    suggestion.suggestedFileName,
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.clipboard_suggestion_dismiss))
                }
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.clipboard_suggestion_download))
                }
            }
        }
    }
}

@Composable
private fun ActiveSummaryCard(state: CoordinatorState, onPauseAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.activeCount == 1) {
                        state.active.first().fileName
                    } else {
                        stringResource(
                            R.string.notification_downloading_many,
                            state.activeCount,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = Formatting.percent(state.overallProgress),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (state.knownTotalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { state.overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.home_active_summary,
                        state.activeCount,
                        Formatting.speed(state.totalSpeed),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPauseAll) {
                    Text(stringResource(R.string.home_active_pause))
                }
            }
        }
    }
}

@Composable
private fun WaitingCard(state: CoordinatorState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.home_waiting_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = state.waitingReason ?: stringResource(R.string.status_paused),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (state.queued > 0) {
                Text(
                    text = stringResource(R.string.home_waiting_detail, state.queued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean, onAddDownload: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "↓",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = if (filtered) {
                    stringResource(R.string.home_filter_empty)
                } else {
                    stringResource(R.string.home_empty_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (!filtered) {
                Text(
                    text = stringResource(R.string.home_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onAddDownload) {
                    Text(stringResource(R.string.home_empty_action))
                }
            }
        }
    }
}
