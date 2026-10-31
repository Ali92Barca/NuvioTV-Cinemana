@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class DownloadRow(val item: OfflineItem, val ready: Boolean, val transfer: OfflineTransfer?, val watchedFraction: Float = 0f)

@Composable
fun OfflineDownloadsScreen(onPlayNative: ((OfflineItem) -> Unit)? = null, viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    OfflineDownloadsBrowser(viewModel, embedded = false, onPlayNative = onPlayNative)
}

@Composable
fun OfflineDownloadsCard(viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    OfflineDownloadsBrowser(viewModel, embedded = true)
}

@Composable
private fun OfflineDownloadsBrowser(viewModel: OfflineDownloadsViewModel, embedded: Boolean, onPlayNative: ((OfflineItem) -> Unit)? = null) {
    var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
    var loaded by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var deleteItem by remember { mutableStateOf<OfflineItem?>(null) }
    LaunchedEffect(viewModel) {
        while (true) {
            rows = withContext(Dispatchers.IO) {
                viewModel.downloads.list().map { DownloadRow(it, viewModel.downloads.ready(it), viewModel.downloads.transfer(it.video)) }
            }
            loaded = true
            delay(2_000)
        }
    }
    val entries = remember(rows) { groupOfflineDownloads(rows.map { it.item }) }
    val folder = entries.filterIsInstance<OfflineDownloadEntry.Series>().find { it.key == selectedKey }
    val visible = folder?.episodes?.map { OfflineDownloadEntry.File(it) } ?: entries
    val watchState by viewModel.watchState.collectAsStateWithLifecycle()
    val rowByKey = remember(rows, watchState) {
        rows.associate { it.item.key to it.copy(watchedFraction = offlineWatchFraction(it.item.identity, watchState)) }
    }
    BackHandler(enabled = folder != null) { selectedKey = null }
    LaunchedEffect(loaded, entries, selectedKey) {
        if (loaded && selectedKey != null && folder == null) selectedKey = null
    }
    val context = LocalContext.current
    val play: (OfflineItem) -> Unit = { item ->
        if (onPlayNative != null && item.identity?.contentId?.isNotBlank() == true &&
            item.identity.keys().isNotEmpty()) {
            onPlayNative(item)
        } else {
            context.startActivity(
                Intent(context, OfflinePlayerActivity::class.java)
                    .putExtra("item", item.key)
            )
        }
    }
    Column(
        modifier = (if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().background(NuvioTheme.colors.Background))
            .padding(if (embedded) 0.dp else NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
            if (folder != null) Button(onClick = { selectedKey = null }) { Text(stringResource(R.string.action_back)) }
            Text(folder?.name ?: stringResource(R.string.nav_downloads), style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loaded && visible.isEmpty()) Text(stringResource(R.string.offline_library_empty),
            style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
        if (embedded) {
            visible.forEach { entry ->
                DownloadEntryCard(entry, rowByKey, { selectedKey = it }, play, { deleteItem = it })
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                items(visible, key = { when (it) {
                    is OfflineDownloadEntry.Series -> "show:${it.key}"
                    is OfflineDownloadEntry.File -> "file:${it.item.key}"
                } }) { entry ->
                    DownloadEntryCard(entry, rowByKey, { selectedKey = it }, play, { deleteItem = it })
                }
            }
        }
    }
    deleteItem?.let { item ->
        NuvioDialog(title = stringResource(R.string.offline_delete_title), subtitle = item.identity?.displayName ?: item.title,
            onDismiss = { deleteItem = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Button(onClick = { deleteItem = null }) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { viewModel.remove(item.key); deleteItem = null }) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun DownloadEntryCard(entry: OfflineDownloadEntry, rows: Map<String, DownloadRow>,
    openFolder: (String) -> Unit, play: (OfflineItem) -> Unit, delete: (OfflineItem) -> Unit) {
    when (entry) {
        is OfflineDownloadEntry.Series -> {
            val count = entry.episodes.map { it.identity?.season to it.identity?.episode }.distinct().size
            SettingsActionRow(title = entry.name, subtitle = stringResource(R.string.offline_episode_count, count),
                leadingIcon = Icons.Default.Folder, leadingArtworkSize = 48.dp, onClick = { openFolder(entry.key) })
        }
        is OfflineDownloadEntry.File -> rows[entry.item.key]?.let { row -> DownloadFileCard(row, play, delete) }
    }
}

@Composable
private fun DownloadFileCard(row: DownloadRow, play: (OfflineItem) -> Unit, delete: (OfflineItem) -> Unit) {
    val item = row.item
    val identity = item.identity
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val status = when {
        row.ready -> stringResource(R.string.offline_ready)
        row.transfer == null -> stringResource(R.string.offline_missing)
        row.transfer.status == DownloadManager.STATUS_FAILED -> stringResource(R.string.offline_failed)
        row.transfer.status == DownloadManager.STATUS_SUCCESSFUL -> stringResource(R.string.offline_subtitles_pending)
        else -> stringResource(R.string.offline_downloading, row.transfer.downloaded / (1024 * 1024))
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(NuvioTheme.colors.BackgroundElevated, shape)
        .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, shape)) {
    Row(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            Text(identity?.displayName ?: item.title, style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (identity?.type in setOf("series", "tv") && identity?.season != null && identity.episode != null) {
                Text(stringResource(R.string.offline_season_episode, identity.season, identity.episode),
                    style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            Text(stringResource(R.string.offline_watched_percent, (row.watchedFraction * 100).toInt()),
                style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
        val colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, contentColor = NuvioTheme.colors.TextPrimary)
        Button(onClick = { play(item) }, enabled = row.ready, colors = colors) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NuvioTheme.spacing.xs))
            Text(stringResource(R.string.offline_play))
        }
        Button(onClick = { delete(item) }, colors = colors) {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NuvioTheme.spacing.xs))
            Text(stringResource(R.string.action_delete))
        }
    }
        Box(Modifier.fillMaxWidth().height(3.dp).progressSemantics(row.watchedFraction)
            .background(NuvioTheme.colors.TextPrimary.copy(alpha = 0.12f))) {
            if (row.watchedFraction > 0f) Box(Modifier.fillMaxWidth(row.watchedFraction).fillMaxHeight()
                .background(NuvioTheme.colors.Secondary))
        }
    }

}
