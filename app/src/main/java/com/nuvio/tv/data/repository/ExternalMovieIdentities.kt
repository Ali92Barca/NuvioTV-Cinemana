package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress

private val movieIdPattern = Regex("xt_(?:v|m|vod)_[A-Za-z0-9]+_[0-9]+")
internal fun externalMovieId(id: String) = movieIdPattern.matches(id)

/** Subscription scope contains a hash only. Native playback IDs are never rewritten in metadata. */
internal data class ExternalMovieIdentity(val scope: String, val native: String, val tmdb: String?, val imdb: String?) {
    val aliases: Set<String> get() = listOfNotNull(tmdb?.let { "tmdb:movie:$it" }, tmdb?.let { "tmdb:$it" }, imdb).toSet()
}

/** Immutable connected components use public IDs, never colliding subscription-local native IDs. */
internal class ExternalMovieIdentities(val rows: List<ExternalMovieIdentity>) {
    private class Group(val rows: List<ExternalMovieIdentity>) {
        val aliases = rows.flatMap { it.aliases }.toSet()
        val canonical = rows.mapNotNull { it.imdb }.distinct().singleOrNull()
            ?: rows.mapNotNull { it.tmdb }.distinct().singleOrNull()?.let { "tmdb:movie:$it" }
        val valid = rows.mapNotNull { it.imdb }.distinct().size <= 1 && rows.mapNotNull { it.tmdb }.distinct().size <= 1
    }
    private val groups: List<Group> = buildList {
        val remaining = rows.toMutableList()
        while (remaining.isNotEmpty()) {
            val connected = mutableListOf(remaining.removeAt(0))
            val aliases = connected.first().aliases.toMutableSet()
            do {
                val added = remaining.filter { row -> row.aliases.any { it in aliases } }
                remaining.removeAll(added.toSet())
                connected.addAll(added)
                added.forEach { aliases.addAll(it.aliases) }
            } while (added.isNotEmpty())
            add(Group(connected))
        }
    }
    private val public = groups.flatMap { g -> g.aliases.map { it to g } }.toMap()
    private val native = groups.flatMap { g -> g.rows.map { it.native to g } }.groupBy({ it.first }, { it.second })
    private fun select(id: String, scope: String? = null): Group? =
        (if (externalMovieId(id)) native[id].orEmpty().distinct().filter { g -> scope == null || g.rows.any { it.native == id && it.scope == scope } }.singleOrNull()
        else public[id])?.takeIf { it.valid }

    fun related(id: String): Set<String> {
        val group = select(id) ?: return setOf(id)
        return group.aliases + group.rows.map { it.native }.filter { select(it) === group }
    }
    fun canonical(id: String) = select(id)?.canonical
    fun nativeIds(scope: String, tmdb: String?, imdb: String?): List<String> {
        val matching = listOfNotNull(tmdb?.let { select("tmdb:movie:$it") }, imdb?.let { select(it) }).distinct()
        val group = matching.singleOrNull() ?: return emptyList()
        if (tmdb != null && group.rows.any { it.tmdb != null && it.tmdb != tmdb }) return emptyList()
        if (imdb != null && group.rows.any { it.imdb != null && it.imdb != imdb }) return emptyList()
        return group.rows.filter { it.scope == scope }.map { it.native }.distinct()
    }
    fun project(value: WatchProgress, target: String?, scope: String?): WatchProgress {
        val group = select(value.contentId, scope) ?: return value
        val destination = target ?: group.canonical ?: return value
        if (select(destination) !== group) return value
        return if (destination == value.contentId) value else value.copy(contentId = destination, videoId = destination)
    }
}
