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
    private val metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val identities: com.nuvio.tv.data.repository.SharedPlaybackIdentity) : ViewModel() {
    // Keep download navigation state with the nav-scoped ViewModel so returning
    // from the player restores the same series and episode, not the root grid.
    var selectedDownloadFolder by mutableStateOf(savedState.get<String>("offline_selected_folder"))
        private set
    var lastFocusedPosterKey by mutableStateOf(savedState.get<String>("offline_focused_poster"))
        private set
    var lastFocusedEpisodeKey by mutableStateOf(savedState.get<String>("offline_focused_episode"))
        private set

    fun selectDownloadFolder(key: String?) {
        if (key != selectedDownloadFolder && key != null) recordFocusedEpisode("")
        selectedDownloadFolder = key
        savedState["offline_selected_folder"] = key
    }

    fun recordFocusedPoster(key: String) {
        if (lastFocusedPosterKey == key) return
        lastFocusedPosterKey = key
        savedState["offline_focused_poster"] = key
    }

    fun recordFocusedEpisode(key: String) {
        if (lastFocusedEpisodeKey == key) return
        lastFocusedEpisodeKey = key
        savedState["offline_focused_episode"] = key
    }

    val message = MutableStateFlow("")
    internal val watchState = profileManager.activeProfileId.flatMapLatest { profileId ->
        combine(
            localProgress.observeAllRawProgress(profileId),
            watchProgressRepository.allProgress.onStart { emit(emptyList()) },
            watchProgressRepository.watchedItems.onStart { emit(emptyList()) }
        ) { local, remote, watched ->
            val progress = (local + remote).flatMap { item ->
                identities.related(item.contentId, item.contentType in setOf("series", "tv"))
                    .map { identities.progress(item, it) }
            }
            OfflineWatchState(progress, watched)
        }
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

    // Get the exact episode still, not a generic series backdrop.
    private suspend fun exactEpisodeStill(identity: OfflineMediaIdentity?): String? {
        val item = identity ?: return null
        if (item.type.lowercase() !in setOf("series", "tv")) return null
        val season = item.season?.takeIf { it >= 0 } ?: return null
        val episode = item.episode?.takeIf { it > 0 } ?: return null

        val candidates = (
            listOfNotNull(item.contentId, item.videoId) + item.aliases
        ).distinct()

        val tmdbId = candidates.firstNotNullOfOrNull { id ->
            Regex("^tmdb:(?:(?:tv|series):)?([1-9][0-9]*)$", RegexOption.IGNORE_CASE)
                .matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()
        } ?: candidates.firstNotNullOfOrNull { id ->
            Regex("^tt[0-9]+$", RegexOption.IGNORE_CASE)
                .matchEntire(id)?.value
                ?.let { tmdbService.imdbToTmdb(it, "series") }
        } ?: return null

        val key = tmdbService.apiKey().takeIf { it.isNotBlank() } ?: return null

        return try {
            val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
            val address = java.net.URL(
                "https://api.themoviedb.org/3/tv/$tmdbId/season/$season/episode/$episode?api_key=$encodedKey"
            )
            val connection = address.openConnection() as java.net.HttpURLConnection

            try {
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                if (connection.responseCode != 200) return null

                val json = connection.inputStream.bufferedReader().use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        text.append(buffer, 0, n)
                        if (text.length > 262144) return null
                    }
                    text.toString()
                }

                org.json.JSONObject(json).optString("still_path")
                    .takeIf { it.startsWith("/") }
                    ?.let { "https://image.tmdb.org/t/p/w780$it" }
            } finally {
                connection.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
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
            val resolvedIdentity = identity?.copy(aliases = aliases)
            val meta = metaRepository.getCachedMeta(
                resolvedIdentity?.type.orEmpty(),
                resolvedIdentity?.contentId ?: resolvedIdentity?.videoId.orEmpty()
            )
            val episode = meta?.videos?.firstOrNull {
                it.season == resolvedIdentity?.season && it.episode == resolvedIdentity?.episode
            }
            val posterUrl = meta?.poster ?: savedState.get<String>("poster")
            val thumbnailUrl = exactEpisodeStill(resolvedIdentity) ?: episode?.thumbnail
            val releaseYear = savedState.get<String>("year")
                ?.let { Regex("(?:19|20)[0-9]{2}").find(it)?.value }
                ?: Regex("(?:19|20)[0-9]{2}").find(resolvedIdentity?.displayName.orEmpty())?.value
            downloads.enqueue(stream, resolvedIdentity, posterUrl, thumbnailUrl, releaseYear)
            "أُضيف الفيديو والترجمات إلى التنزيلات"
        }
            catch (error: Exception) { error.message ?: "تعذر بدء التنزيل" }
    } }
    fun refreshEpisodeArtwork(items: List<OfflineItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            items.forEach { item ->
                val identity = item.identity ?: return@forEach
                if (identity.type.lowercase() !in setOf("series", "tv") ||
                    identity.season == null || identity.episode == null) return@forEach
                val ids = (listOfNotNull(identity.contentId, identity.videoId) + identity.aliases).distinct()
                val url = exactEpisodeStill(identity) ?: ids.asSequence().mapNotNull { id ->
                    metaRepository.getCachedMeta(identity.type, id)?.videos
                        ?.firstOrNull { it.season == identity.season && it.episode == identity.episode }
                        ?.thumbnail?.takeIf(String::isNotBlank)
                }.firstOrNull() ?: return@forEach
                downloads.upgradeEpisodeThumbnail(item, url)
            }
        }
    }

    fun remove(key: String) { viewModelScope.launch(Dispatchers.IO) { downloads.remove(key) } }

    fun removeMany(keys: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            keys.distinct().forEach(downloads::remove)
        }
    }

    /** Use the same repository as native playback for profile history and tracker sync. */
    fun markDownloadedAsWatched(items: List<OfflineItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = profileManager.activeProfileId.value
            val unique = items.distinctBy { it.identity?.keys()?.sorted()?.firstOrNull() ?: it.key }
            val progress = unique.filter { downloads.ready(it) }.mapNotNull { item ->
                val identity = item.identity ?: return@mapNotNull null
                val contentId = identity.contentId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (identity.keys().isEmpty()) return@mapNotNull null
                com.nuvio.tv.domain.model.WatchProgress(
                    contentId = contentId,
                    contentType = if (identity.type.lowercase() in setOf("series", "tv")) "series" else "movie",
                    name = identity.displayName ?: item.title,
                    poster = null, backdrop = null, logo = null,
                    videoId = identity.videoId ?: contentId,
                    season = identity.season,
                    episode = identity.episode,
                    episodeTitle = if (identity.type.lowercase() in setOf("series", "tv")) item.title else null,
                    position = 1L, duration = 1L,
                    lastWatched = System.currentTimeMillis(),
                    progressPercent = 100f
                )
            }
            if (progress.isEmpty()) return@launch
            try {
                // Native repository persists watched flags, progress and triggers sync.
                // No manual provider or network-scrobbler mutation is performed here.
                if (progress.size == 1) {
                    watchProgressRepository.markAsCompleted(progress.single(), profileId = profileId)
                } else {
                    // Batch API uses current active profile; avoid cross-profile writes if switched.
                    if (profileManager.activeProfileId.value == profileId) {
                        watchProgressRepository.markAsCompletedBatch(progress)
                    }
                }
                message.value = "تم تحديد المحتوى كمشاهد"
            } catch (error: Exception) {
                message.value = error.message ?: "تعذر تحديث حالة المشاهدة"
            }
        }
    }
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
