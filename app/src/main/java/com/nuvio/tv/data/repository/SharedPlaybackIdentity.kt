package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

internal fun nativePlaybackId(id: String): Boolean =
    Regex("(?:cinemana(?:-series)?|cee):[0-9]+").matches(id)

internal data class PlaybackIdentity(val native: String, val canonical: String, val series: Boolean,
    val aliases: Set<String>, val episodes: Map<Pair<Int, Int>, String>, val partial: Boolean = false)

/** Verified identity only, independent of profile-specific watch state. No media IDs are migrated. */
@Singleton
class SharedPlaybackIdentity @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("shared_playback_identity_v1", Context.MODE_PRIVATE)
    internal val entries = MutableStateFlow(read())
    private val artworkPrefs = context.getSharedPreferences("builtin_artwork_identity_v1", Context.MODE_PRIVATE)
    internal val artwork = MutableStateFlow(artworkPrefs.all.mapNotNull { (id, value) ->
        (value as? String)?.let { id to it }
    }.toMap())

    @Synchronized internal fun learnArtwork(id: String, imdb: String) {
        if (!nativePlaybackId(id) || !Regex("tt[0-9]+").matches(imdb) || artwork.value[id] == imdb) return
        artworkPrefs.edit().putString(id, imdb).apply()
        artwork.value = artwork.value + (id to imdb)
    }
    private data class Stored(val native: String, val canonical: String, val series: Boolean,
        val aliases: Set<String>, val episodes: Map<String, String>, val partial: Boolean = false)
    private fun read(): List<PlaybackIdentity> = runCatching {
        val rows: List<Stored> = Gson().fromJson(prefs.getString("identities", "[]"), object : TypeToken<List<Stored>>() {}.type)
        rows.filter { nativePlaybackId(it.native) && Regex("tt[0-9]+|tmdb:(?:tv:|movie:)?[0-9]+").matches(it.canonical) }
            .map { row -> PlaybackIdentity(row.native, row.canonical, row.series, row.aliases,
                row.episodes.mapNotNull { (key, value) ->
                    val pair = key.split(':').mapNotNull(String::toIntOrNull)
                    if (pair.size == 2) (pair[0] to pair[1]) to value else null
                }.toMap(), row.partial) }
    }.getOrDefault(emptyList())

    @Synchronized internal fun learn(meta: Meta, imdb: String?) {
        val tmdb = meta.tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return
        if (!nativePlaybackId(meta.id)) return
        val series = meta.apiType in setOf("series", "tv")
        if (series && !meta.sharedProgressCompatible) return
        val canonical = imdb?.takeIf { Regex("tt[0-9]+").matches(it) } ?: "tmdb:${if (series) "tv" else "movie"}:$tmdb"
        val aliases = setOf(meta.id, canonical, "tmdb:$tmdb", "tmdb:${if (series) "tv" else "movie"}:$tmdb") +
            if (series) setOf("tmdb:series:$tmdb") else emptySet()
        if (canonical.startsWith("tt") && entries.value.any { row ->
            row.series == series && row.canonical.startsWith("tt") && row.canonical != canonical &&
                row.aliases.any { it in aliases && it.startsWith("tmdb:") }
        }) return
        val episodes = meta.videos.mapNotNull { video ->
            if (meta.verifiedPlaybackVideoIds?.contains(video.id) == false) return@mapNotNull null
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            (season to episode) to video.id
        }.toMap()
        val record = PlaybackIdentity(meta.id, canonical, series, aliases, episodes,
            partial = series && meta.verifiedPlaybackVideoIds != null && episodes.size < meta.videos.size)
        val old = entries.value.firstOrNull { it.native == meta.id }
        // Conflicting provider rematches must never silently join two watch histories.
        if (old != null && old.canonical !in aliases && canonical !in old.aliases) return
        val next = entries.value.filterNot { it.native == meta.id } + record
        if (next == entries.value) return
        val stored = next.map { row -> Stored(row.native, row.canonical, row.series, row.aliases,
            row.episodes.mapKeys { (key, _) -> "${key.first}:${key.second}" }, row.partial) }
        check(prefs.edit().putString("identities", Gson().toJson(stored)).commit())
        entries.value = next
    }

    internal fun related(id: String, seriesHint: Boolean? = null): Set<String> {
        val direct = entries.value.filter { id in it.aliases && (seriesHint == null || it.series == seriesHint) }
        // Numeric TMDB IDs can refer to unrelated movie and TV records.
        if (direct.map { it.series }.distinct().size > 1) return setOf(id)
        val kind = direct.firstOrNull()?.series
        var group = setOf(id)
        do {
            val previous = group
            group = group + entries.value.filter { row -> (kind == null || row.series == kind) && row.aliases.any { it in group } }.flatMap { it.aliases }
        } while (previous != group)
        return group
    }
    internal fun canonical(id: String, seriesHint: Boolean? = null): String {
        val related = related(id, seriesHint)
        if (related == setOf(id) && entries.value.filter { id in it.aliases && (seriesHint == null || it.series == seriesHint) }.map { it.series }.distinct().size > 1) return id
        return related.firstOrNull { Regex("tt[0-9]+").matches(it) }
            ?: entries.value.firstOrNull { (it.native == id || id in it.aliases) && (seriesHint == null || it.series == seriesHint) }?.canonical ?: id
    }
    internal fun expand(ids: Set<String>): Set<String> = ids.flatMap { related(it) }.toSet()
    internal fun canProjectEpisode(sourceId: String, targetId: String, season: Int?, episode: Int?): Boolean {
        if (sourceId == targetId || season == null || episode == null) return true
        val source = entries.value.firstOrNull { it.native == sourceId }
        val target = entries.value.firstOrNull { it.native == targetId }
        return (source == null || (season to episode) in source.episodes) &&
            (target == null || (season to episode) in target.episodes)
    }
    internal fun progress(progress: WatchProgress, target: String = canonical(progress.contentId, progress.contentType in setOf("series", "tv"))): WatchProgress {
        if (target == progress.contentId) return progress
        val s = progress.season
        val e = progress.episode
        if (!canProjectEpisode(progress.contentId, target, s, e)) return progress
        val video = if (s != null && e != null) {
            entries.value.firstOrNull { it.native == target }?.episodes?.get(s to e)
                ?: if (!nativePlaybackId(target)) "$target:$s:$e" else progress.videoId
        } else target
        return progress.copy(contentId = target, videoId = video)
    }
    internal fun project(items: List<WatchProgress>): List<WatchProgress> = items.map { progress(it) }
        .groupBy { Triple(it.contentId, it.season, it.episode) }.values
        .mapNotNull { group -> group.maxByOrNull { it.lastWatched } }.sortedByDescending { it.lastWatched }
}
