package com.nuvio.tv.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.SharedPlaybackIdentity
import com.nuvio.tv.data.repository.SharedPlaybackResolver
import com.nuvio.tv.data.repository.nativePlaybackId
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal fun betterPosterUrl(imdb: String, arabic: Boolean): String? {
    if (!Regex("tt[0-9]+").matches(imdb)) return null
    val suffix = if (arabic) "?lang=ar" else ""
    return "https://btttr.cc/poster/imdb/poster-default/$imdb.jpg$suffix"
}

@Singleton
class BuiltinCatalogPosters @Inject constructor(
    @ApplicationContext context: Context,
    private val identities: SharedPlaybackIdentity,
    private val resolver: SharedPlaybackResolver,
    watchProgress: WatchProgressRepository,
    watchedSeries: WatchedSeriesStateHolder
) {
    private val prefs = context.getSharedPreferences("builtin_catalog_posters", Context.MODE_PRIVATE)
    internal val arabic = MutableStateFlow(prefs.getBoolean("arabic", true))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchedEpisodes = watchProgress.watchedItems.map { rows ->
        val byTitle = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        for (row in rows) {
            val season = row.season ?: continue
            val episode = row.episode ?: continue
            byTitle.getOrPut(row.contentId) { mutableSetOf() }.add(season to episode)
        }
        byTitle
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), mutableMapOf())
    private val seriesWatchState = combine(watchedEpisodes, watchedSeries.fullyWatchedSeriesIds) { episodes, completed ->
        SeriesWatchState(episodes, completed, (episodes.keys + completed).mapNotNull { parseContentIds(it).tmdb }.toSet())
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), SeriesWatchState())
    private val watchedMovies = watchProgress.observeWatchedMovieIds().map<Set<String>, Set<String>?> { it }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    internal fun setArabic(value: Boolean) {
        prefs.edit().putBoolean("arabic", value).apply()
        arabic.value = value
    }

    internal fun request(id: String, type: String) = resolver.prioritizePoster(id, type)
    internal fun verifySeries(id: String) = resolver.verifySeriesPoster(id)

    internal fun observe(id: String, movie: Boolean, sourceBaseUrl: String? = null): kotlinx.coroutines.flow.Flow<BuiltinPosterState> {
        if (id.startsWith("xt_")) {
            // Keep native IPTV posters: no identity reads, history subscriptions or replacement loads.
            return flowOf(BuiltinPosterState())
        }
        return combine(
            identities.entries, identities.artwork, arabic, watchedMovies,
            combine(seriesWatchState, resolver.seriesPosterCandidates, identities.externalRevision) { series, candidates, _ -> series to candidates }
        ) { entries, artwork, ar, watched, (series, candidates) ->
            val verified = entries.firstOrNull { it.native == id && it.series == !movie }
            val imdb = artwork[id] ?: verified?.canonical
            val completed = if (movie) watched?.contains(id) else verified
                ?.takeUnless { it.partial }
                ?.episodes?.keys?.filter { it.first != 0 }?.takeIf { it.isNotEmpty() }
                ?.let { required -> series.episodes[id]?.containsAll(required) == true }
            val tmdb = parseContentIds(candidates[id]).tmdb
            val needsEpisodeVerification = !movie && verified == null && (
                (imdb != null && (imdb in series.episodes || imdb in series.completed)) ||
                    (tmdb != null && tmdb in series.tmdbIds)
            )
            BuiltinPosterState(imdb?.let { betterPosterUrl(it, ar) }, completed, needsEpisodeVerification)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }
}

private data class SeriesWatchState(
    val episodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    val completed: Set<String> = emptySet(),
    val tmdbIds: Set<Int> = emptySet()
)

internal data class BuiltinPosterState(
    val url: String? = null,
    val watched: Boolean? = null,
    val needsEpisodeVerification: Boolean = false
)
internal val LocalBuiltinCatalogPosters = staticCompositionLocalOf<BuiltinCatalogPosters?> { null }

@Composable
internal fun rememberBuiltinPoster(item: MetaPreview): BuiltinPosterState {
    // IPTV matching is retained for playback/progress only, not catalog artwork.
    if (item.id.startsWith("xt_")) return BuiltinPosterState()
    val posters = LocalBuiltinCatalogPosters.current ?: return BuiltinPosterState()
    val external = item.id.startsWith("xt_")
    if (!external && !nativePlaybackId(item.id)) return BuiltinPosterState()
    val movie = item.apiType == "movie"
    val updates = remember(posters, item.id, movie, item.sourceAddonBaseUrl) { posters.observe(item.id, movie, item.sourceAddonBaseUrl) }
    return key(item.id, movie) {
        val state by updates.collectAsState(BuiltinPosterState())
        LaunchedEffect(posters, item.id, item.apiType) {
            if (external) return@LaunchedEffect // Cached matches only: no metadata or TMDB request for a poster.
            delay(450)
            posters.request(item.id, item.apiType)
        }
        LaunchedEffect(posters, item.id, state.needsEpisodeVerification) {
            if (external) return@LaunchedEffect
            if (state.needsEpisodeVerification) posters.verifySeries(item.id)
        }
        state
    }
}
