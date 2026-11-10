package com.nuvio.tv.data.offline

import android.app.DownloadManager

internal fun hasActiveOfflineTransfers(statuses: Collection<Int>): Boolean = statuses.any {
    it == DownloadManager.STATUS_PENDING || it == DownloadManager.STATUS_RUNNING
}

/** A missing transfer, removed file, or unavailable storage must never display a ready badge. */
internal fun readyOfflineKeys(
    items: List<OfflineItem>, statuses: Map<Long, Int>,
    canOpen: (Long) -> Boolean, locationUnavailable: (OfflineItem) -> Boolean
): Set<String> = items.asSequence().filter { item ->
    !locationUnavailable(item) && (listOf(item.video) + item.subtitles.map { it.first }).all { id ->
        statuses[id] == DownloadManager.STATUS_SUCCESSFUL && canOpen(id)
    }
}.flatMap { it.identity?.keys().orEmpty().asSequence() }.toSet()
