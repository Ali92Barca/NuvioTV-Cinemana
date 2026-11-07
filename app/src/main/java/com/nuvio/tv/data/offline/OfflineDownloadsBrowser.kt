@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.progressSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
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

private data class DownloadRow(val item: OfflineItem, val ready: Boolean, val transfer: OfflineTransfer?, val watchedFraction: Float = 0f, val locationUnavailable: Boolean = false)

@Composable
fun OfflineDownloadsScreen(
    onPlayNative: ((OfflineItem, Boolean) -> Unit)? = null,
    onDetails: ((OfflineItem) -> Unit)? = null,
    viewModel: OfflineDownloadsViewModel = hiltViewModel()
) {
    OfflineDownloadsBrowser(viewModel, embedded = false, onPlayNative = onPlayNative, onDetails = onDetails)
}

@Composable
fun OfflineDownloadsCard(viewModel: OfflineDownloadsViewModel = hiltViewModel()) {
    OfflineDownloadsBrowser(viewModel, embedded = true)
}

@Composable
private fun OfflineDownloadsBrowser(
    viewModel: OfflineDownloadsViewModel,
    embedded: Boolean,
    onPlayNative: ((OfflineItem, Boolean) -> Unit)? = null,
    onDetails: ((OfflineItem) -> Unit)? = null
) {
    var rows by remember { mutableStateOf(emptyList<DownloadRow>()) }
    var loaded by remember { mutableStateOf(false) }
    val selectedKey = viewModel.selectedDownloadFolder
    val focusKey = if (selectedKey != null) viewModel.lastFocusedEpisodeKey else viewModel.lastFocusedPosterKey
    val rootGridState = rememberLazyGridState()
    val episodeListState = rememberLazyListState()
    val targetFocusRequester = remember { FocusRequester() }
    val emptyFocusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumedSerial by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumedSerial++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var deleteItem by remember { mutableStateOf<OfflineItem?>(null) }
    var movieActions by remember { mutableStateOf<OfflineItem?>(null) }
    var seriesActions by remember { mutableStateOf<OfflineDownloadEntry.Series?>(null) }
    var deleteSeries by remember { mutableStateOf<OfflineDownloadEntry.Series?>(null) }
    LaunchedEffect(viewModel) {
        while (true) {
            rows = withContext(Dispatchers.IO) {
                viewModel.downloads.list().map { DownloadRow(it, viewModel.downloads.ready(it), viewModel.downloads.transfer(it.video),
                    locationUnavailable = viewModel.downloads.locationUnavailable(it)) }
            }
            loaded = true
            delay(2_000)
        }
    }
    val entries = remember(rows) { groupOfflineDownloads(rows.map { it.item }) }
    val artworkKeys = rows.map { it.item.key }
    LaunchedEffect(artworkKeys) {
        if (artworkKeys.isNotEmpty()) viewModel.refreshEpisodeArtwork(rows.map { it.item })
    }
    val folder = entries.filterIsInstance<OfflineDownloadEntry.Series>().find { it.key == selectedKey }
    val visible = folder?.episodes?.map { OfflineDownloadEntry.File(it) } ?: entries
    val watchState by viewModel.watchState.collectAsStateWithLifecycle()
    val rowByKey = remember(rows, watchState) {
        rows.associate { it.item.key to it.copy(watchedFraction = offlineWatchFraction(it.item.identity, watchState)) }
    }
    // Episode lists are ordered by season and episode.
    // Focus the first unfinished downloaded episode, not the last watched.
    val preferredEpisodeKey = folder?.episodes?.let { items ->
        val fractions = items.map { rowByKey[it.key]?.watchedFraction ?: 0f }
        firstUnfinishedDownloadedEpisodeIndex(fractions)
            ?.let { index -> "file:${items[index].key}" }
    }
    BackHandler(enabled = folder != null) { viewModel.selectDownloadFolder(null) }
    LaunchedEffect(loaded, selectedKey, entries.map { entry -> when (entry) {
        is OfflineDownloadEntry.Series -> entry.key
        is OfflineDownloadEntry.File -> entry.item.key
    } }) {
        if (loaded && selectedKey != null && folder == null) viewModel.selectDownloadFolder(null)
    }
    val rootKeys = entries.map { entry -> when (entry) {
        is OfflineDownloadEntry.Series -> "show:${entry.key}"
        is OfflineDownloadEntry.File -> "file:${entry.item.key}"
    } }
    val episodeKeys = folder?.episodes?.map { "file:${it.key}" }.orEmpty()
    // Navigation away to the player destroys/recreates the screen, but its nav-scoped
    // ViewModel retains the folder and focused episode. Do not re-focus every 2s refresh.
    LaunchedEffect(loaded, selectedKey, rootKeys, episodeKeys, preferredEpisodeKey, resumedSerial) {
        if (!loaded) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        if (selectedKey != null && folder != null && episodeKeys.isNotEmpty()) {
            val targetKey = preferredEpisodeKey
                ?: focusKey.takeIf { it in episodeKeys }
                ?: episodeKeys.first()
            val target = episodeKeys.indexOf(targetKey).coerceAtLeast(0)
            episodeListState.scrollToItem(target)
            repeat(2) { withFrameNanos { } }
            runCatching { targetFocusRequester.requestFocus() }
        } else if (selectedKey == null && rootKeys.isNotEmpty()) {
            val target = rootKeys.indexOf(focusKey).takeIf { it >= 0 } ?: 0
            rootGridState.scrollToItem(target)
            repeat(2) { withFrameNanos { } }
            runCatching { targetFocusRequester.requestFocus() }
        } else if (selectedKey == null && rootKeys.isEmpty()) {
            runCatching { emptyFocusRequester.requestFocus() }
        }
    }
    val context = LocalContext.current
    val play: (OfflineItem, Boolean) -> Unit = { item, fromBeginning ->
        if (onPlayNative != null && item.identity?.contentId?.isNotBlank() == true &&
            item.identity.keys().isNotEmpty()) {
            onPlayNative(item, fromBeginning)
        } else {
            context.startActivity(
                Intent(context, OfflinePlayerActivity::class.java)
                    .putExtra("item", item.key)
                    .putExtra("position", 0L)
            )
        }
    }
    val details: (OfflineItem) -> Unit = { item -> onDetails?.invoke(item) }
    val markWatched: (List<OfflineItem>) -> Unit = { items -> viewModel.markDownloadedAsWatched(items) }
    Column(
        modifier = (if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().background(NuvioTheme.colors.Background))
            .padding(if (embedded) 0.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
            if (folder != null) Button(onClick = { viewModel.selectDownloadFolder(null) }) { Text(stringResource(R.string.action_back)) }
            Text(folder?.name ?: stringResource(R.string.nav_downloads), style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loaded && visible.isEmpty()) {
            // A focusable empty placeholder allows DPAD navigation to the sidebar.
            Text(stringResource(R.string.offline_library_empty),
                modifier = Modifier.then(if (folder == null) Modifier.focusRequester(emptyFocusRequester) else Modifier)
                    .focusable(),
                style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
        }
        if (folder == null && !embedded) {
            // Root: posters ONLY. Movies present Play/Delete; series drill down to episodes.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                state = rootGridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                // Focus enlarges TV posters; keep a clear overscan-safe buffer on every edge.
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 38.dp)
            ) {
                gridItems(entries, key = { entry -> when (entry) {
                    is OfflineDownloadEntry.Series -> "show:${entry.key}"
                    is OfflineDownloadEntry.File -> "file:${entry.item.key}"
                } }) { entry ->
                    val key = when (entry) {
                        is OfflineDownloadEntry.Series -> "show:${entry.key}"
                        is OfflineDownloadEntry.File -> "file:${entry.item.key}"
                    }
                    DownloadPosterCard(entry, rowByKey,
                        focusRequester = if (key == (focusKey.takeIf { it in rootKeys } ?: rootKeys.firstOrNull())) targetFocusRequester else null,
                        onFocused = { viewModel.recordFocusedPoster(key) },
                        openFolder = {
                            viewModel.recordFocusedPoster(key)
                            viewModel.selectDownloadFolder(it)
                        },
                        openMovie = {
                            viewModel.recordFocusedPoster(key)
                            movieActions = it
                        },
                        openSeriesActions = {
                            viewModel.recordFocusedPoster(key)
                            seriesActions = it
                        })
                }
            }
        } else if (embedded) {
            visible.forEach { entry ->
                DownloadEntryCard(entry, rowByKey, { viewModel.selectDownloadFolder(it) }, play,
                    { deleteItem = it }, details, markWatched, onDetails != null)
            }
        } else {
            // Series detail: one continuous scroll, chronological episodes, no season tabs.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = episodeListState,
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                items(visible, key = { entry -> when (entry) {
                    is OfflineDownloadEntry.Series -> "show:${entry.key}"
                    is OfflineDownloadEntry.File -> "file:${entry.item.key}"
                } }) { entry ->
                    val key = when (entry) {
                        is OfflineDownloadEntry.Series -> "show:${entry.key}"
                        is OfflineDownloadEntry.File -> "file:${entry.item.key}"
                    }
                    DownloadEntryCard(entry, rowByKey, { viewModel.selectDownloadFolder(it) }, play,
                        { deleteItem = it }, details, markWatched, onDetails != null,
                        focusRequester = if (key == (preferredEpisodeKey
                            ?: focusKey.takeIf { it in episodeKeys }
                            ?: episodeKeys.firstOrNull())) targetFocusRequester else null,
                        onFocused = { viewModel.recordFocusedEpisode(key) })
                }
            }
        }
    }
    movieActions?.let { item ->
        val row = rowByKey[item.key]
        NuvioDialog(title = item.identity?.displayName ?: item.title, subtitle = "خيارات الفيلم المحمّل",
            onDismiss = { movieActions = null }) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row?.locationUnavailable == true) {
                    Text("أعد توصيل الذاكرة الخارجية لتشغيل هذا التنزيل. تقدم المشاهدة محفوظ.")
                    if (onDetails != null) {
                        OfflinePosterAction(onClick = { movieActions = null; details(item) }) { Text("التفاصيل") }
                    }
                } else if (row?.ready != true) {
                    OfflinePosterAction(onClick = { movieActions = null; deleteItem = item }) {
                        Text("إلغاء التنزيل")
                    }
                } else {
                    OfflinePosterAction(onClick = { movieActions = null; play(item, false) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(NuvioTheme.spacing.sm))
                        Text(if (row.watchedFraction > 0f && row.watchedFraction < 1f) "استئناف" else "تشغيل")
                    }
                    if (row.watchedFraction > 0f) {
                        OfflinePosterAction(onClick = { movieActions = null; play(item, true) }) {
                            Text("إعادة من البداية")
                        }
                    }
                    if (onDetails != null && item.identity?.contentId?.isNotBlank() == true) {
                        OfflinePosterAction(onClick = { movieActions = null; details(item) }) { Text("التفاصيل") }
                    }
                    if (row.watchedFraction < 1f && item.identity?.keys()?.isNotEmpty() == true) {
                        OfflinePosterAction(onClick = { movieActions = null; markWatched(listOf(item)) }) {
                            Text("حدد كمشاهد")
                        }
                    }
                    OfflinePosterAction(onClick = { movieActions = null; deleteItem = item }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(NuvioTheme.spacing.sm))
                        Text(stringResource(R.string.action_delete))
                    }
                }
            }
        }
    }
    seriesActions?.let { series ->
        val readyEpisodes = series.episodes.filter { rowByKey[it.key]?.ready == true }
        val fractions = readyEpisodes.map { rowByKey[it.key]?.watchedFraction ?: 0f }
        val firstUnfinished = firstUnfinishedDownloadedEpisodeIndex(fractions)
        val resumeItem = firstUnfinished?.let { readyEpisodes[it] }
        val resumeFraction = firstUnfinished?.let { fractions[it] } ?: 0f
        val detailItem = series.episodes.firstOrNull { it.identity?.contentId?.isNotBlank() == true }
        NuvioDialog(title = series.name, subtitle = "خيارات المسلسل المحمّل", onDismiss = { seriesActions = null }) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resumeItem != null) {
                    OfflinePosterAction(onClick = { seriesActions = null; play(resumeItem, false) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(NuvioTheme.spacing.sm))
                        Text(if (resumeFraction > 0f) "استئناف" else "تشغيل")
                    }
                } else if (readyEpisodes.isNotEmpty()) {
                    OfflinePosterAction(onClick = { seriesActions = null; play(readyEpisodes.first(), true) }) {
                        Text("تشغيل من البداية")
                    }
                }
                if (onDetails != null && detailItem != null) {
                    OfflinePosterAction(onClick = { seriesActions = null; details(detailItem.copy(identity = detailItem.identity?.copy(season = null, episode = null, videoId = null))) }) {
                        Text("تفاصيل المسلسل")
                    }
                }
                OfflinePosterAction(onClick = {
                    seriesActions = null
                    viewModel.selectDownloadFolder(series.key)
                }) { Text("عرض الحلقات المحمّلة") }
                val unWatched = readyEpisodes.filter {
                    (rowByKey[it.key]?.watchedFraction ?: 0f) < 1f && it.identity?.keys()?.isNotEmpty() == true
                }
                if (unWatched.isNotEmpty()) {
                    OfflinePosterAction(onClick = { seriesActions = null; markWatched(unWatched) }) {
                        Text("حدد جميع الحلقات المحمّلة كمشاهدة")
                    }
                }
                OfflinePosterAction(onClick = { seriesActions = null; deleteSeries = series }) {
                    Text("حذف جميع الحلقات")
                }
            }
        }
    }
    deleteSeries?.let { series ->
        NuvioDialog(title = "حذف جميع الحلقات", subtitle = series.name,
            onDismiss = { deleteSeries = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Button(onClick = { deleteSeries = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = {
                    viewModel.removeMany(series.episodes.map { it.key })
                    deleteSeries = null
                }) { Text("تأكيد الحذف") }
            }
        }
    }
    deleteItem?.let { item ->
        val cancelDownload = rowByKey[item.key]?.ready != true
        NuvioDialog(
            title = if (cancelDownload) "إلغاء التنزيل" else stringResource(R.string.offline_delete_title),
            subtitle = item.identity?.displayName ?: item.title,
            onDismiss = { deleteItem = null }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Button(onClick = { deleteItem = null }) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { viewModel.remove(item.key); deleteItem = null }) {
                    Text(if (cancelDownload) "تأكيد الإلغاء" else stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun DownloadPosterCard(entry: OfflineDownloadEntry, rows: Map<String, DownloadRow>,
    openFolder: (String) -> Unit, openMovie: (OfflineItem) -> Unit,
    openSeriesActions: (OfflineDownloadEntry.Series) -> Unit,
    focusRequester: FocusRequester? = null, onFocused: () -> Unit = {}) {
    val item = when (entry) {
        is OfflineDownloadEntry.Series -> entry.episodes.firstOrNull()
        is OfflineDownloadEntry.File -> entry.item
    }
    val title = when (entry) {
        is OfflineDownloadEntry.Series -> entry.name
        is OfflineDownloadEntry.File -> entry.item.identity?.displayName ?: entry.item.title
    }
    val poster = when (entry) {
        is OfflineDownloadEntry.Series -> entry.episodes.firstNotNullOfOrNull {
            it.posterPath?.takeIf { path -> File(path).isFile }
        }
        is OfflineDownloadEntry.File -> entry.item.posterPath?.takeIf { File(it).isFile }
    }
    val year = when (entry) {
        is OfflineDownloadEntry.Series -> entry.episodes.firstNotNullOfOrNull { it.releaseYear }
        is OfflineDownloadEntry.File -> entry.item.releaseYear
    }
    val incomplete = when (entry) {
        is OfflineDownloadEntry.Series -> entry.episodes.mapNotNull { rows[it.key] }.firstOrNull { !it.ready }
        is OfflineDownloadEntry.File -> rows[entry.item.key]?.takeUnless { it.ready }
    }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // Foundation clickable retains remote DPAD focus without TV Material's oval default button shape.
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .border(if (focused) 3.dp else 1.dp,
                if (focused) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border, shape)
            .padding(if (focused) 3.dp else 1.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .combinedClickable(
                onClick = {
                    when (entry) {
                        is OfflineDownloadEntry.Series -> openFolder(entry.key)
                        is OfflineDownloadEntry.File -> openMovie(entry.item)
                    }
                },
                onLongClick = {
                    when (entry) {
                        is OfflineDownloadEntry.Series -> openSeriesActions(entry)
                        is OfflineDownloadEntry.File -> openMovie(entry.item)
                    }
                }
            )) {
            if (poster != null) {
                AsyncImage(model = File(poster), contentDescription = title,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Default.Folder, contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(44.dp))
            }
            incomplete?.let { row -> DownloadProgressOverlay(row, Modifier.align(Alignment.Center)) }
            // Watch progress is an overlay INSIDE the poster, never below title/year.
            run {
                val watched = when (entry) {
                    is OfflineDownloadEntry.Series ->
                        downloadedSeriesWatchFraction(entry.episodes.map {
                            rows[it.key]?.watchedFraction ?: 0f
                        })
                    is OfflineDownloadEntry.File ->
                        rows[entry.item.key]?.watchedFraction ?: 0f
                }
                if (watched > 0f) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(4.dp).progressSemantics(watched.coerceIn(0f, 1f))
                        .background(Color.Black.copy(alpha = 0.65f))) {
                        Box(Modifier.fillMaxWidth(watched.coerceIn(0f, 1f))
                            .fillMaxHeight().background(NuvioTheme.colors.Secondary))
                    }
                }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = NuvioTheme.colors.TextPrimary)
        if (!year.isNullOrBlank()) {
            Text(year, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        }
    }
}

@Composable
private fun DownloadProgressOverlay(row: DownloadRow, modifier: Modifier = Modifier) {
    val percent = row.transfer?.takeIf { it.total > 0L }
        ?.let { ((it.downloaded.toDouble() / it.total.toDouble()) * 100.0).toInt().coerceIn(0, 100) }
    val failed = row.transfer?.status == DownloadManager.STATUS_FAILED
    Column(modifier.background(Color.Black.copy(alpha = 0.77f), RoundedCornerShape(14.dp))
        .padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (row.locationUnavailable) "USB غير متصل"
            else if (failed) stringResource(R.string.offline_failed)
            else stringResource(R.string.offline_downloading, row.transfer?.downloaded?.div(1024 * 1024) ?: 0L),
            style = MaterialTheme.typography.bodySmall, color = Color.White)
        if (!failed && !row.locationUnavailable) Text(percent?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DownloadEntryCard(entry: OfflineDownloadEntry, rows: Map<String, DownloadRow>,
    openFolder: (String) -> Unit, play: (OfflineItem, Boolean) -> Unit,
    delete: (OfflineItem) -> Unit, details: (OfflineItem) -> Unit,
    markWatched: (List<OfflineItem>) -> Unit, canOpenDetails: Boolean,
    focusRequester: FocusRequester? = null, onFocused: () -> Unit = {}) {
    when (entry) {
        is OfflineDownloadEntry.Series -> {
            val count = entry.episodes.map { it.identity?.season to it.identity?.episode }.distinct().size
            val poster = entry.episodes.firstNotNullOfOrNull { it.posterPath?.takeIf { path -> File(path).isFile } }
            Button(onClick = { openFolder(entry.key) }, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
                    if (poster != null) {
                        AsyncImage(model = File(poster), contentDescription = entry.name,
                            modifier = Modifier.width(64.dp).height(96.dp), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                    Column {
                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.offline_episode_count, count))
                    }
                }
            }
        }
        is OfflineDownloadEntry.File -> rows[entry.item.key]?.let { row ->
            DownloadFileCard(row, play, delete, details, markWatched, canOpenDetails, focusRequester, onFocused)
        }
    }
}

@Composable
private fun DownloadFileCard(
    row: DownloadRow, play: (OfflineItem, Boolean) -> Unit, delete: (OfflineItem) -> Unit,
    details: (OfflineItem) -> Unit, markWatched: (List<OfflineItem>) -> Unit,
    canOpenDetails: Boolean, focusRequester: FocusRequester? = null, onFocused: () -> Unit = {}
) {
    val item = row.item
    val identity = item.identity
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val status = when {
        row.locationUnavailable -> "الذاكرة الخارجية غير متصلة — تقدم المشاهدة محفوظ"
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
        val artwork = if (identity?.type in setOf("series", "tv")) item.thumbnailPath else item.posterPath
        Box(modifier = if (identity?.type in setOf("series", "tv"))
            Modifier.width(224.dp).height(126.dp) else Modifier.width(64.dp).height(96.dp)) {
            artwork?.takeIf { File(it).isFile }?.let { path ->
                AsyncImage(model = File(path), contentDescription = item.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop)
            }
            if (!row.ready) DownloadProgressOverlay(row, Modifier.align(Alignment.Center))
            if (row.watchedFraction > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                    .progressSemantics(row.watchedFraction.coerceIn(0f, 1f))
                    .background(Color.Black.copy(alpha = 0.65f))) {
                    Box(Modifier.fillMaxWidth(row.watchedFraction.coerceIn(0f, 1f))
                        .fillMaxHeight().background(NuvioTheme.colors.Secondary))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            Text(if (identity?.type in setOf("series", "tv")) item.title else (identity?.displayName ?: item.title), style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (identity?.type in setOf("series", "tv") && identity?.season != null && identity.episode != null) {
                identity.displayName?.takeIf { it != item.title }?.let { show ->
                    Text(show, style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary)
                }
                Text(stringResource(R.string.offline_season_episode, identity.season, identity.episode),
                    style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            item.downloadFolder?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        val colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard, contentColor = NuvioTheme.colors.TextPrimary)
        if (row.locationUnavailable) {
            Button(onClick = { if (canOpenDetails) details(item) }, colors = colors,
                modifier = Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused() }) { Text("الذاكرة غير متصلة") }
        } else if (!row.ready) {
            Button(onClick = { delete(item) }, colors = colors,
                modifier = Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused() }) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(NuvioTheme.spacing.xs))
                Text("إلغاء التنزيل")
            }
        } else {
            Button(onClick = { play(item, false) }, colors = colors,
                modifier = Modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused() }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(NuvioTheme.spacing.xs))
                Text(if (row.watchedFraction > 0f && row.watchedFraction < 1f) "استئناف" else "تشغيل")
            }
            if (row.watchedFraction > 0f) {
                Button(onClick = { play(item, true) }, colors = colors) { Text("إعادة من البداية") }
            }
            if (canOpenDetails && identity?.contentId?.isNotBlank() == true) {
                Button(onClick = { details(item) }, colors = colors) { Text("التفاصيل") }
            }
            if (row.watchedFraction < 1f && identity?.keys()?.isNotEmpty() == true) {
                Button(onClick = { markWatched(listOf(item)) }, colors = colors) { Text("حدد كمشاهد") }
            }
            Button(onClick = { delete(item) }, colors = colors) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(NuvioTheme.spacing.xs))
                Text(stringResource(R.string.action_delete))
            }
        }
    }
    }

}

@Composable
private fun OfflinePosterAction(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = Color.Black),
        content = content)
}
