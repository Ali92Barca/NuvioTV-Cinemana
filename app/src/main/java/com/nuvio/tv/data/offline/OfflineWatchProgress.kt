package com.nuvio.tv.data.offline

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal data class OfflineWatchState(
    val progress: List<WatchProgress> = emptyList(),
    val watched: List<WatchedItem> = emptyList()
)

/** The caller supplies only the current profile's entries. Never match by title. */
internal fun offlineWatchFraction(identity: OfflineMediaIdentity?, state: OfflineWatchState): Float {
    val keys = identity?.keys().orEmpty()
    if (keys.isEmpty()) return 0f
    fun matches(type: String, id: String, video: String?, season: Int?, episode: Int?) =
        OfflineMediaIdentity(type, id, video, season, episode).keys().any(keys::contains)
    val progress = state.progress.filter { matches(it.contentType, it.contentId, it.videoId, it.season, it.episode) }
        .maxByOrNull { it.lastWatched }
    val watched = state.watched.filter { matches(it.contentType, it.contentId, null, it.season, it.episode) }
        .maxByOrNull { it.watchedAt }
    if (watched != null && (progress == null || watched.watchedAt >= progress.lastWatched)) return 1f
    return progress?.progressPercentage?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}
