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

/**
 * Input fractions must follow native season/episode order.
 * Pick the FIRST unfinished downloaded episode, not the most recently watched.
 * 1f means completed or explicitly marked watched.
 */
internal fun firstUnfinishedDownloadedEpisodeIndex(fractions: List<Float>): Int? =
    fractions.indexOfFirst { !it.isFinite() || it < 1f }.takeIf { it >= 0 }

/**
 * Equal-weight progress across the DOWNLOADED episodes only.
 * Example: 1.0 + 1.0 + 0.0 divided by 3.
 */
internal fun downloadedSeriesWatchFraction(fractions: List<Float>): Float {
    if (fractions.isEmpty()) return 0f
    return fractions.sumOf {
        if (it.isFinite()) it.coerceIn(0f, 1f).toDouble() else 0.0
    }.div(fractions.size).toFloat().coerceIn(0f, 1f)
}
