package com.nuvio.tv.ui.screens.detail

internal fun resolveEpisodeWatchedState(
    currentlyWatched: Boolean,
    completedByProgress: Boolean,
    optimisticallyMarked: Boolean,
    optimisticallyUnmarked: Boolean,
    watchedByVideoId: Boolean?,
    projectedWatched: Boolean = false
): Boolean {
    if (optimisticallyUnmarked) return false
    if (projectedWatched) return true
    if (watchedByVideoId == true && !optimisticallyUnmarked) return true
    if (
        watchedByVideoId == false &&
        currentlyWatched &&
        !completedByProgress &&
        !optimisticallyMarked
    ) {
        return false
    }
    return currentlyWatched
}
