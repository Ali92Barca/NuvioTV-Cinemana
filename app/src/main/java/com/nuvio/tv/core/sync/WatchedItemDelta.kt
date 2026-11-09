package com.nuvio.tv.core.sync

import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItemEvent
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey

internal data class WatchedItemDelta(
    val upserts: List<WatchedItem>,
    val deletes: List<Triple<String, Int?, Int?>>
)

/** A page may contain both mark and unmark for the same episode. Splitting it
 * into two lists before reducing loses event order and resurrects old marks. */
internal fun reduceWatchedItemDelta(events: List<SupabaseWatchedItemEvent>): WatchedItemDelta {
    val latest = linkedMapOf<WatchedMutationKey, SupabaseWatchedItemEvent>()
    events.forEach { event ->
        if (!event.operation.equals("upsert", true) && !event.operation.equals("delete", true)) return@forEach
        val key = WatchedMutationKey(event.contentId, event.season, event.episode)
        if (event.eventId >= (latest[key]?.eventId ?: Long.MIN_VALUE)) latest[key] = event
    }
    return WatchedItemDelta(
        upserts = latest.values.filter { it.operation.equals("upsert", true) }.map {
            WatchedItem(it.contentId, it.contentType, it.title, it.season, it.episode, it.watchedAt)
        },
        deletes = latest.values.filter { it.operation.equals("delete", true) }
            .map { Triple(it.contentId, it.season, it.episode) }
    )
}
