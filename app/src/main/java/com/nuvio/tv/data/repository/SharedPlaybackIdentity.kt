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
    internal data class ExternalEpisode(val scope: String, val native: String, val nativeVideo: String,
        val nativeSeason: Int, val nativeEpisode: Int, val canonical: String,
        val canonicalSeason: Int, val canonicalEpisode: Int)
    private val externalRows = runCatching {
        Gson().fromJson<List<ExternalEpisode>>(prefs.getString("external_episodes_v1", "[]"),
            object : TypeToken<List<ExternalEpisode>>() {}.type).orEmpty()
    }.getOrDefault(emptyList()).toMutableList()
    internal val externalRevision = MutableStateFlow(0L)
    private val externalCanonicalLinks = runCatching {
        Gson().fromJson<Map<String, String>>(prefs.getString("external_canonical_links_v1", "{}"),
            object : TypeToken<Map<String, String>>() {}.type).orEmpty()
    }.getOrDefault(emptyMap()).toMutableMap()
    private fun externalCanonical(id: String): String {
        var current = id
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            val number = Regex("tmdb:(?:(?:tv|series):)?([0-9]+)").matchEntire(current)?.groupValues?.get(1)
            val key = number?.let { "tmdb:tv:$it" } ?: current
            val builtin = if (nativePlaybackId(current)) canonical(current, true) else key
            val next = externalCanonicalLinks[current] ?: externalCanonicalLinks[key] ?: builtin
            if (next == current) return current
            current = next
        }
        return id
    }
    @Synchronized internal fun learnExternalCanonical(tmdbId: String, imdbId: String?) {
        if (tmdbId.toIntOrNull()?.let { it > 0 } != true || imdbId == null || !Regex("tt[0-9]+").matches(imdbId)) return
        val key = "tmdb:tv:$tmdbId"
        if (externalCanonicalLinks.containsKey(key)) return
        externalCanonicalLinks[key] = imdbId
        check(prefs.edit().putString("external_canonical_links_v1", Gson().toJson(externalCanonicalLinks)).commit())
        externalRevision.value += 1
    }
    private fun sourceScope(url: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.trimEnd('/').toByteArray()).joinToString("") { "%02x".format(it) }

    @Synchronized internal fun learnExternalEpisode(baseUrl: String, native: String, nativeVideo: String,
        nativeSeason: Int, nativeEpisode: Int, canonical: String, canonicalSeason: Int, canonicalEpisode: Int) {
        learnExternalSeason(baseUrl, native, canonical, nativeSeason, canonicalSeason,
            listOf(Triple(nativeEpisode, nativeVideo, canonicalEpisode)))
    }
    @Synchronized internal fun learnExternalSeason(baseUrl: String, native: String, canonical: String,
        nativeSeason: Int, canonicalSeason: Int, videos: List<Triple<Int, String, Int>>) {
        if (!Regex("xt_s_[A-Za-z0-9]+_[0-9]+").matches(native) || baseUrl.isBlank() ||
            !Regex("tt[0-9]+|tmdb:(?:tv:|series:)?[0-9]+|cinemana-series:[0-9]+|cee:[0-9]+").matches(canonical)) return
        val scope = sourceScope(baseUrl)
        var changed = false
        for ((nativeEpisode, nativeVideo, canonicalEpisode) in videos) {
        if (!Regex("xt_[A-Za-z0-9_:.-]+").matches(nativeVideo)) continue
        val row = ExternalEpisode(scope, native, nativeVideo, nativeSeason, nativeEpisode,
            canonical, canonicalSeason, canonicalEpisode)
        val existing = externalRows.filter { it.scope == row.scope && it.native == native &&
            it.nativeSeason == nativeSeason && it.nativeEpisode == nativeEpisode }
        // Never silently replace a previously verified episode with a different work.
        if (existing.isNotEmpty()) {
            val previous = externalCanonical(existing.first().canonical)
            val next = externalCanonical(canonical)
            // A verified native episode can connect a built-in source anchor to its
            // public identity; two conflicting public identities never overwrite each other.
            val anchor = canonical.takeIf(::nativePlaybackId)?.takeIf { this.canonical(it, true) == it }
                ?: existing.first().canonical.takeIf(::nativePlaybackId)?.takeIf { this.canonical(it, true) == it }
            val destination = if (anchor == canonical) previous else next
            if (anchor != null && anchor != destination && !externalCanonicalLinks.containsKey(anchor)) {
                externalCanonicalLinks[anchor] = destination
                changed = true
            }
            continue
        }
        externalRows += row
        changed = true
        }
        if (!changed) return
        check(prefs.edit().putString("external_episodes_v1", Gson().toJson(externalRows))
            .putString("external_canonical_links_v1", Gson().toJson(externalCanonicalLinks)).commit())
        externalRevision.value += 1
    }
    @Synchronized internal fun externalRelated(id: String): Set<String> = setOf(id) + externalRows
        .filter { it.native == id || externalCanonical(it.canonical) == externalCanonical(id) }
        .flatMap { listOf(it.native, it.canonical, externalCanonical(it.canonical)) }

    @Synchronized internal fun externalQuery(from: String, to: String, season: Int, episode: Int): Pair<Int, Int>? {
        if (from == to) return season to episode
        val rows = externalRows.filter { (it.native == from && externalCanonical(it.canonical) == externalCanonical(canonical(to, true)) && it.nativeSeason == season && it.nativeEpisode == episode) ||
            (externalCanonical(it.canonical) == externalCanonical(canonical(from, true)) && it.native == to && it.canonicalSeason == season && it.canonicalEpisode == episode) }
        return rows.map { if (it.native == to) it.nativeSeason to it.nativeEpisode else it.canonicalSeason to it.canonicalEpisode }.distinct().singleOrNull()
    }
    private fun ambiguousExternalCoordinate(native: String, season: Int?, episode: Int?) = externalRows
        .filter { it.native == native && it.nativeSeason == season && it.nativeEpisode == episode }
        .map { Triple(externalCanonical(it.canonical), it.canonicalSeason, it.canonicalEpisode) }.distinct().size > 1
    @Synchronized private fun externalProgress(value: WatchProgress, target: String? = null): WatchProgress {
        val scope = value.addonBaseUrl?.let(::sourceScope)
        val rows = externalRows.filter { row ->
            (row.native == value.contentId && row.nativeSeason == value.season && row.nativeEpisode == value.episode &&
                !(scope == null && ambiguousExternalCoordinate(row.native, value.season, value.episode)) &&
                (scope == null || scope == row.scope) && (target == null || externalCanonical(canonical(target, true)) == externalCanonical(row.canonical))) ||
            (target == row.native && row.canonicalSeason == value.season && row.canonicalEpisode == value.episode &&
                externalCanonical(row.canonical) == externalCanonical(value.contentId) &&
                !ambiguousExternalCoordinate(row.native, row.nativeSeason, row.nativeEpisode))
        }
        val projected = rows.map { row ->
            if (target == row.native) value.copy(contentId = row.native, videoId = row.nativeVideo,
                season = row.nativeSeason, episode = row.nativeEpisode)
            else value.copy(contentId = externalCanonical(row.canonical), videoId = "${externalCanonical(row.canonical)}:${row.canonicalSeason}:${row.canonicalEpisode}",
                season = row.canonicalSeason, episode = row.canonicalEpisode)
        }.distinctBy { listOf(it.contentId, it.season, it.episode, it.videoId) }
        return projected.singleOrNull() ?: value
    }
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
    @Synchronized internal fun progress(value: WatchProgress, requestedTarget: String? = null): WatchProgress {
        if (requestedTarget?.startsWith("xt_") == true) {
            val projected = externalProgress(progress(value), requestedTarget)
            return if (projected.contentId == requestedTarget) projected else value
        }
        val input = if (value.contentType in setOf("series", "tv") && !value.contentId.startsWith("xt_") &&
            externalRows.any { externalCanonical(it.canonical) == externalCanonical(value.contentId) }) {
            val id = externalCanonical(value.contentId)
            if (id != value.contentId && value.season != null && value.episode != null)
                value.copy(contentId = id, videoId = "$id:${value.season}:${value.episode}") else value
        } else value
        val progress = externalProgress(input, requestedTarget)
        if (value.contentId.startsWith("xt_") && progress.contentId.startsWith("xt_") &&
            requestedTarget != null && requestedTarget != value.contentId) return value
        val target = requestedTarget ?: canonical(progress.contentId, progress.contentType in setOf("series", "tv"))
        if (target != progress.contentId && externalCanonical(target) == externalCanonical(progress.contentId) &&
            entries.value.none { it.native == target || it.native == progress.contentId } &&
            progress.season != null && progress.episode != null) return progress.copy(contentId = target,
            videoId = "$target:${progress.season}:${progress.episode}")
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
