package com.nuvio.tv.ui.screens.home

/** A fully loaded history is authoritative, including removal of a show's last
 * watched episode. Before a tracker finishes loading, retain cached badges. */
internal fun reconcileWatchedSeriesBadges(
    current: Set<String>, complete: Set<String>, incomplete: Set<String>,
    watchedHistoryIds: Set<String>, historyLoaded: Boolean
): Set<String> = ((if (historyLoaded) current.intersect(watchedHistoryIds) else current) - incomplete) + complete
