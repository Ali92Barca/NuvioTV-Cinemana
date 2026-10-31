@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OfflineDownloadsViewModel @Inject constructor(val downloads: OfflineDownloads,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val savedState: androidx.lifecycle.SavedStateHandle,
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val localProgress: com.nuvio.tv.data.local.WatchProgressPreferences,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository) : ViewModel() {
    val message = MutableStateFlow("")
    internal val watchState = profileManager.activeProfileId.flatMapLatest { profileId ->
        combine(
            localProgress.observeAllProgress(profileId),
            watchProgressRepository.allProgress.onStart { emit(emptyList()) },
            watchProgressRepository.watchedItems.onStart { emit(emptyList()) }
        ) { local, remote, watched -> OfflineWatchState(local + remote, watched) }
            .onStart { emit(OfflineWatchState()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineWatchState())
    private fun requestIdentity(): OfflineMediaIdentity {
        fun value(key: String) = savedState.get<String>(key)?.takeIf { it.isNotBlank() && it != "null" }
        val type = value("contentType").orEmpty()
        val videoId = value("videoId")
        val contentId = value("contentId")
        val meta = metaRepository.getCachedMeta(type, contentId ?: videoId.orEmpty())
        val aliases = (listOfNotNull(meta?.id, meta?.imdbId) + meta?.links.orEmpty()
            .filter { it.category.equals("imdb", true) }.mapNotNull { Regex("tt[0-9]+").find(it.url)?.value }).toSet()
        return OfflineMediaIdentity(type, contentId, videoId, value("season")?.toIntOrNull(), value("episode")?.toIntOrNull(), aliases,
            meta?.name ?: value("contentName") ?: value("title"))
    }
    fun enqueue(stream: Stream, requestedIdentity: OfflineMediaIdentity? = null) {
      val identity = requestedIdentity ?: requestIdentity()
      viewModelScope.launch(Dispatchers.IO) {
        message.value = try {
            val canonical = (listOfNotNull(identity?.contentId) + identity?.aliases.orEmpty())
                .mapNotNull { OfflineMediaIdentity.canonicalId(it) }.firstOrNull { it.startsWith("tt") || it.startsWith("tmdb:") }
            val aliases = identity?.aliases.orEmpty().toMutableSet()
            runCatching {
                if (canonical?.startsWith("tt") == true) tmdbService.imdbToTmdb(canonical, identity?.type.orEmpty())?.let { aliases.add("tmdb:$it") }
                else if (canonical?.startsWith("tmdb:") == true) tmdbService.tmdbToImdb(canonical.removePrefix("tmdb:").toInt(), identity?.type.orEmpty())?.let { aliases.add(it) }
            }
            downloads.enqueue(stream, identity?.copy(aliases = aliases)); "أُضيف الفيديو والترجمات إلى التنزيلات"
        }
            catch (error: Exception) { error.message ?: "تعذر بدء التنزيل" }
    } }
    fun remove(key: String) { viewModelScope.launch(Dispatchers.IO) { downloads.remove(key) } }
}

@Composable
fun OfflineDownloadButton(stream: Stream, viewModel: OfflineDownloadsViewModel = hiltViewModel(), identity: OfflineMediaIdentity? = null) {
    if (!supportsBuiltinDownload(stream)) return
    val message by viewModel.message.collectAsState()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.enqueue(stream, identity) }) { Text("تنزيل الفيديو والترجمات") }
        if (message.isNotBlank()) Text(message)
    }
}

@Composable
fun OfflineDownloadDialog(stream: Stream, onDismiss: () -> Unit,
    viewModel: OfflineDownloadsViewModel = hiltViewModel(), identity: OfflineMediaIdentity? = null) {
    LaunchedEffect(stream) { viewModel.message.value = "" }
    NuvioDialog(title = stringResource(R.string.nav_downloads), onDismiss = onDismiss) {
        OfflineDownloadButton(stream, viewModel, identity)
        Button(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    }
}
