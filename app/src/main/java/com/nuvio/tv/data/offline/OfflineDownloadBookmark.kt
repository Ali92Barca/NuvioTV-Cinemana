package com.nuvio.tv.data.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class OfflineDownloadStatusViewModel @Inject constructor(downloads: OfflineDownloads) : ViewModel() {
    val keys = observeOfflineReadyKeys(downloads.readinessChanges(), downloads::readiness)
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}

internal fun observeOfflineReadyKeys(changesFlow: Flow<Unit>, read: () -> OfflineDownloads.Readiness): Flow<Set<String>> = flow {
        kotlinx.coroutines.coroutineScope {
            val changes = changesFlow.produceIn(this)
            try {
                while (true) {
                    val state = runCatching { read() }.getOrNull()
                    if (state != null) emit(state.keys)
                    // Completion/index events refresh immediately. Stable files only need
                    // a slow reconciliation for external deletions or a removed USB drive.
                    kotlinx.coroutines.withTimeoutOrNull(if (state?.active == true) 2_000L else 30_000L) { changes.receive() }
                }
            } finally { changes.cancel() }
        }
    }.distinctUntilChanged()

@Composable
fun OfflineDownloadStatusProvider(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = generateSequence(context as? android.content.Context) { current ->
        (current as? android.content.ContextWrapper)?.baseContext
    }.filterIsInstance<androidx.activity.ComponentActivity>().firstOrNull()
        ?: error("OfflineDownloadStatusProvider requires ComponentActivity")
    val viewModel: OfflineDownloadStatusViewModel = hiltViewModel(viewModelStoreOwner = activity)
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalOfflineDownloadKeys provides keys, content = content)
}

private val LocalOfflineDownloadKeys = compositionLocalOf<Set<String>> { emptySet() }

@Composable
fun ContinueWatchingDownloadBookmark(item: com.nuvio.tv.ui.screens.home.ContinueWatchingItem, modifier: Modifier = Modifier) {
    val identity = when (item) {
        is com.nuvio.tv.ui.screens.home.ContinueWatchingItem.InProgress -> item.progress.let {
            OfflineMediaIdentity(it.contentType, it.contentId, it.videoId, it.season, it.episode)
        }
        is com.nuvio.tv.ui.screens.home.ContinueWatchingItem.NextUp -> item.info.let {
            OfflineMediaIdentity(it.contentType, it.contentId, it.videoId, it.season, it.episode)
        }
    }
    OfflineDownloadBookmark(identity, modifier)
}

@Composable
fun OfflineDownloadBookmark(identity: OfflineMediaIdentity, modifier: Modifier = Modifier) {
    val keys = LocalOfflineDownloadKeys.current
    if (identity.keys().none { it in keys }) return
    Icon(Icons.Default.Bookmark, contentDescription = "محملة على الجهاز",
        tint = Color(0xFFB9F6CA), modifier = modifier.background(Color.Black.copy(alpha = .8f), RoundedCornerShape(6.dp)).padding(5.dp).size(23.dp))
}
