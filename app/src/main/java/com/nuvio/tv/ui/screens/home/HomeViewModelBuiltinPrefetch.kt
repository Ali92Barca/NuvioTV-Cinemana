package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.builtincache.BuiltinStreamPrefetcher.Target
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun continueWatchingPrefetchTargets(items: List<ContinueWatchingItem>): List<Target> = buildList {
    fun addItem(type: String, video: String, content: String, season: Int?, episode: Int?) {
        val normalized = if (type.equals("tv", true)) "series" else type.lowercase()
        add(Target(normalized, video))
        if (content.matches(Regex("(?:imdb:)?tt[0-9]+|tmdb:(?:(?:movie|tv|series):)?[0-9]+"))) {
            if (normalized == "series" && season != null && episode != null && season > 0 && episode > 0)
                add(Target(normalized, "$content:$season:$episode"))
            else if (normalized == "movie") add(Target(normalized, content))
        }
    }
    for (item in items) when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.let { addItem(it.contentType, it.videoId, it.contentId, it.season, it.episode) }
        is ContinueWatchingItem.NextUp -> item.info.takeIf { it.hasAired }?.let { addItem(it.contentType, it.videoId, it.contentId, it.season, it.episode) }
    }
}.distinct()

/** Collected only while the home destination is resumed; profile/list changes cancel old work. */
internal suspend fun HomeViewModel.prefetchContinueWatchingStreams() {
    combine(uiState, profileManager.activeProfileId, addonRepository.getInstalledAddons()) { state, profile, addons ->
        Triple(profile, if (state.continueWatchingEnabled) continueWatchingPrefetchTargets(state.continueWatchingItems) else emptyList(),
            addons.filter { it.enabled }.map { it.baseUrl })
    }.distinctUntilChanged().collectLatest { (profile, targets, _) ->
        delay(2_000)
        builtinStreamPrefetcher.warm(targets, profile)
    }
}
