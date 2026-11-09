package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
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
    /** Reuse a provider's verified layout when TMDB already lists a newer episode. */
    internal fun verifiedSeriesSeason(tmdbId: String, season: Int): Set<Int>? = verifiedSeriesSeasonForCount(tmdbId, season, null)
    internal fun verifiedSeriesSeasonForCount(tmdbId: String, season: Int, count: Int?): Set<Int>? {
        val id = "tmdb:tv:$tmdbId"
        return entries.value.filter { it.series && !it.partial && id in it.aliases }
            .map { row -> row.episodes.keys.filter { it.first == season }.map { it.second }.toSet() }
            .filter { it.isNotEmpty() && (count == null || it.size == count) }.distinct().singleOrNull()
    }
    /** Providers may refresh at different times. Only growing contiguous prefixes agree. */
    internal fun verifiedSeriesSeasonAvailable(tmdbId: String, season: Int): Set<Int>? {
        val layouts = entries.value.filter { it.series && !it.partial && "tmdb:tv:$tmdbId" in it.aliases }
            .map { row -> row.episodes.keys.filter { it.first == season }.map { it.second }.toSet() }
            .filter { it.isNotEmpty() }
        if (layouts.isEmpty() || layouts.any { it != (1..it.size).toSet() }) return null
        return layouts.maxByOrNull { it.size }
    }
    internal data class ExternalEpisode(val scope: String, val native: String, val nativeVideo: String,
        val nativeSeason: Int, val nativeEpisode: Int, val canonical: String,
        val canonicalSeason: Int, val canonicalEpisode: Int, val alias: Boolean = false,
        val canonicalVideo: String? = null)
    private val externalRows = runCatching {
        Gson().fromJson<List<ExternalEpisode>>(prefs.getString("external_episodes_v1", "[]"),
            object : TypeToken<List<ExternalEpisode>>() {}.type).orEmpty()
    }.getOrDefault(emptyList()).toMutableList()
    internal val externalRevision = MutableStateFlow(0L)
    @Volatile private var externalMovies = ExternalMovieIdentities(runCatching {
        Gson().fromJson<List<ExternalMovieIdentity>>(prefs.getString("external_movies_v1", "[]"),
            object : TypeToken<List<ExternalMovieIdentity>>() {}.type).orEmpty()
    }.getOrDefault(emptyList()))

    @Synchronized internal fun learnExternalMovie(baseUrl: String, native: String, tmdb: String?, imdb: String?) {
        if (baseUrl.isBlank() || !privateAddonId(native)) return
        val tmdbId = tmdb?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true }
        val imdbId = imdb?.takeIf { Regex("tt[0-9]+").matches(it) }
        if (tmdbId == null && imdbId == null) return
        val scope = sourceScope(baseUrl)
        val old = externalMovies.rows.firstOrNull { it.scope == scope && it.native == native }
        if (old != null && ((old.tmdb != null && tmdbId != null && old.tmdb != tmdbId) ||
            (old.imdb != null && imdbId != null && old.imdb != imdbId))) return
        val row = ExternalMovieIdentity(scope, native, tmdbId ?: old?.tmdb, imdbId ?: old?.imdb)
        if (row == old) return
        val next = externalMovies.rows.filterNot { it.scope == scope && it.native == native } + row
        check(prefs.edit().putString("external_movies_v1", Gson().toJson(next)).commit())
        externalMovies = ExternalMovieIdentities(next)
        externalRevision.value += 1
    }
    internal fun externalMovieSources(baseUrl: String, tmdb: String?, imdb: String?) =
        externalMovies.nativeIds(sourceScope(baseUrl), tmdb, imdb)

    /** Reuse only verified episode edges for this subscription. Never synthesize
     * a native video ID from a displayed/canonical episode number. */
    @Synchronized internal fun externalEpisodeSources(baseUrl: String, id: String, season: Int, episode: Int): List<String> {
        indexExternal()
        val scope = sourceScope(baseUrl)
        val source = if (nativePlaybackId(id) && !hasPartBinding(id) &&
            canProjectEpisode(id, canonical(id, true), season, episode)) canonical(id, true) else id
        val starts = (rowsByNative[Triple(id, season, episode)].orEmpty().filter { it.scope == scope } +
            rowsByRef[Triple(externalCanonical(source), season, episode)].orEmpty()).map(::edge).toSet()
        return episodeClosure(starts).filter { it.scope == scope }.map { it.nativeVideo }.distinct()
    }
    private val externalCanonicalLinks = runCatching {
        Gson().fromJson<Map<String, String>>(prefs.getString("external_canonical_links_v1", "{}"),
            object : TypeToken<Map<String, String>>() {}.type).orEmpty()
    }.getOrDefault(emptyMap()).toMutableMap()
    private val externalTmdbPattern = Regex("tmdb:(?:(?:tv|series):)?([0-9]+)")
    private var indexedRevision = -1L
    private var rowsByEdge = emptyMap<List<String>, List<ExternalEpisode>>()
    private var rowsByRef = emptyMap<Triple<String, Int, Int>, List<ExternalEpisode>>()
    private var rowsByNative = emptyMap<Triple<String, Int, Int>, List<ExternalEpisode>>()
    private var rowsByTitle = emptyMap<String, List<ExternalEpisode>>()
    private var partIds = emptySet<String>()
    private val externalRelatedCache = object : LinkedHashMap<String, Set<String>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?) = size > 256
    }
    private fun indexExternal() {
        if (indexedRevision == externalRevision.value) return
        externalRelatedCache.clear()
        rowsByEdge = externalRows.groupBy(::edge)
        rowsByRef = externalRows.groupBy(::reference)
        rowsByNative = externalRows.groupBy { Triple(it.native, it.nativeSeason, it.nativeEpisode) }
        rowsByTitle = externalRows.flatMap { listOf(it.native to it, externalCanonical(it.canonical) to it) }.groupBy({ it.first }, { it.second })
        partIds = externalRows.filter { it.nativeSeason != it.canonicalSeason || it.nativeEpisode != it.canonicalEpisode }
            .flatMap { listOf(it.native, it.canonical, externalCanonical(it.canonical)) }.toSet()
        indexedRevision = externalRevision.value
    }
    private fun externalCanonical(id: String): String {
        var current = id
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            val number = externalTmdbPattern.matchEntire(current)?.groupValues?.get(1)
            val key = number?.let { "tmdb:tv:$it" } ?: current
            val next = externalCanonicalLinks[current] ?: externalCanonicalLinks[key] ?: key
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

    /** Revoke a disproven edition match without deleting any profile's actual watch records. */
    @Synchronized internal fun invalidateExternalSeason(baseUrl: String, native: String, season: Int, canonical: String) {
        val scope = sourceScope(baseUrl)
        if (!externalRows.removeAll { it.scope == scope && it.native == native && it.nativeSeason == season &&
                externalCanonical(it.canonical) == externalCanonical(canonical) }) return
        check(prefs.edit().putString("external_episodes_v1", Gson().toJson(externalRows)).commit())
        externalRevision.value += 1
    }

    @Synchronized internal fun learnExternalEpisode(baseUrl: String, native: String, nativeVideo: String,
        nativeSeason: Int, nativeEpisode: Int, canonical: String, canonicalSeason: Int, canonicalEpisode: Int) {
        learnExternalSeason(baseUrl, native, canonical, nativeSeason, canonicalSeason,
            listOf(Triple(nativeEpisode, nativeVideo, canonicalEpisode)))
    }
    @Synchronized internal fun learnExternalSeason(baseUrl: String, native: String, canonical: String,
        nativeSeason: Int, canonicalSeason: Int, videos: List<Triple<Int, String, Int>>,
        allowPartAlias: Boolean = false, canonicalVideos: Map<Int, String> = emptyMap()) {
        if (!privateAddonId(native) || baseUrl.isBlank() ||
            !Regex("tt[0-9]+|tmdb:(?:tv:|series:)?[0-9]+|cinemana-series:[0-9]+|cee:[0-9]+").matches(canonical)) return
        val scope = sourceScope(baseUrl)
        var changed = false
        for ((nativeEpisode, nativeVideo, canonicalEpisode) in videos) {
        if (!Regex("[A-Za-z0-9_:.-]{1,250}").matches(nativeVideo)) continue
        val row = ExternalEpisode(scope, native, nativeVideo, nativeSeason, nativeEpisode,
            canonical, canonicalSeason, canonicalEpisode, allowPartAlias || nativePlaybackId(canonical),
            canonicalVideos[canonicalEpisode]?.takeIf { Regex("[A-Za-z0-9_:.-]+").matches(it) })
        val existing = externalRows.filter { it.scope == row.scope && it.native == native &&
            it.nativeSeason == nativeSeason && it.nativeEpisode == nativeEpisode }
        if (existing.any { externalCanonical(it.canonical) == externalCanonical(canonical) &&
            it.canonicalSeason == canonicalSeason && it.canonicalEpisode == canonicalEpisode }) continue
        // A numbered-part proof may add an episode alias, never a whole-work alias.
        // Competing primary identities still cannot silently rematch a source episode.
        if (existing.any { !it.alias } && !row.alias) continue
        externalRows += row
        changed = true
        }
        if (!changed) return
        check(prefs.edit().putString("external_episodes_v1", Gson().toJson(externalRows))
            .putString("external_canonical_links_v1", Gson().toJson(externalCanonicalLinks)).commit())
        externalRevision.value += 1
    }
    private fun edge(row: ExternalEpisode) = listOf(row.scope, row.native, row.nativeSeason.toString(), row.nativeEpisode.toString())
    private fun reference(row: ExternalEpisode) = Triple(externalCanonical(row.canonical), row.canonicalSeason, row.canonicalEpisode)
    private fun episodeClosure(start: Set<List<String>>): List<ExternalEpisode> {
        indexExternal()
        var edges = start
        while (true) {
            val rows = edges.flatMap { rowsByEdge[it].orEmpty() }
            val refs = rows.map(::reference).toSet()
            val next = edges + refs.flatMap { rowsByRef[it].orEmpty() }.map(::edge)
            if (next == edges) return rows
            edges = next
        }
    }
    private fun hasExternalCoordinate(id: String, season: Int?, episode: Int?): Boolean {
        indexExternal()
        if (season == null || episode == null) return false
        return rowsByNative.containsKey(Triple(id, season, episode)) || rowsByRef.containsKey(Triple(externalCanonical(id), season, episode))
    }
    @Synchronized internal fun externalRelated(id: String, seriesHint: Boolean? = null): Set<String> {
        indexExternal()
        val movieRelated = if (seriesHint == true) emptySet() else externalMovies.related(id)
        if (seriesHint == null && externalMovies.isNative(id) && isExternalSeries(id)) return setOf(id)
        // An untyped TMDB number can identify both a film and a series. Do not use
        // that weak alias to bridge the two graphs when expanding watched history.
        if (seriesHint == null && id.startsWith("tmdb:") && id.removePrefix("tmdb:").toIntOrNull() != null && movieRelated.size > 1 &&
            (rowsByTitle[externalCanonical(id)].orEmpty().isNotEmpty() || entries.value.any { it.series && id in it.aliases })) return setOf(id)
        val key = "$seriesHint\u0000$id"
        externalRelatedCache[key]?.let { return it }
        val touched = if (seriesHint == false) emptySet() else
            (rowsByTitle[id].orEmpty() + rowsByTitle[externalCanonical(id)].orEmpty()).map(::edge).toSet()
        return (setOf(id) + movieRelated + episodeClosure(touched).flatMap { listOf(it.native, it.canonical, externalCanonical(it.canonical)) })
            .also { externalRelatedCache[key] = it }
    }

    /** Artwork reuses verified primary work identities; numbered-part aliases cannot replace it. */
    @Synchronized internal fun externalPosterImdb(id: String, baseUrl: String?): String? {
        indexExternal()
        val scope = baseUrl?.let(::sourceScope)
        return rowsByTitle[id].orEmpty().filter {
            it.native == id && !it.alias && (scope == null || it.scope == scope)
        }.map { externalCanonical(it.canonical) }.distinct().singleOrNull()
            ?.takeIf { Regex("tt[0-9]+").matches(it) }
    }
    @Synchronized internal fun hasPartBinding(id: String): Boolean {
        indexExternal()
        return id in partIds || externalCanonical(id) in partIds
    }
    @Synchronized internal fun isExternalSeries(id: String): Boolean {
        indexExternal()
        return rowsByTitle[id].orEmpty().any { it.native == id }
    }
    internal fun isExternalSource(id: String) = isExternalSeries(id) || externalMovies.isNative(id)

    @Synchronized internal fun externalQuery(from: String, to: String, season: Int, episode: Int): Pair<Int, Int>? {
        if (from == to) return season to episode
        indexExternal()
        val source = if (nativePlaybackId(from) && !hasPartBinding(from) &&
            canProjectEpisode(from, canonical(from, true), season, episode)) canonical(from, true) else from
        val destination = if (nativePlaybackId(to) && !hasPartBinding(to)) canonical(to, true) else to
        val starts = (rowsByNative[Triple(from, season, episode)].orEmpty() + rowsByRef[Triple(externalCanonical(source), season, episode)].orEmpty()).map(::edge).toSet()
        return episodeClosure(starts).mapNotNull {
            when {
                it.native == to -> it.nativeSeason to it.nativeEpisode
                externalCanonical(it.canonical) == externalCanonical(destination) -> it.canonicalSeason to it.canonicalEpisode
                else -> null
            }
        }.distinct().singleOrNull()
    }
    private fun ambiguousExternalCoordinate(native: String, season: Int?, episode: Int?): Boolean {
        indexExternal()
        if (season == null || episode == null) return false
        val groups = rowsByNative[Triple(native, season, episode)].orEmpty()
            .groupBy { it.scope }.values.map { rows -> episodeClosure(rows.map(::edge).toSet()).map(::reference).toMutableSet() }.toMutableList()
        if (groups.size < 2) return false
        var joined = true
        while (joined) {
            joined = false
            loop@ for (a in groups.indices) for (b in a + 1 until groups.size) if (groups[a].intersect(groups[b]).isNotEmpty()) {
                groups[a].addAll(groups[b]); groups.removeAt(b); joined = true; break@loop
            }
        }
        return groups.size > 1
    }
    @Synchronized private fun externalProgress(value: WatchProgress, target: String? = null): WatchProgress {
        if (value.contentType == "movie" && value.season == null && value.episode == null) {
            val publicTarget = target?.takeIf(::nativePlaybackId)?.let { canonical(it, false) } ?: target
            return externalMovies.project(value, publicTarget, value.addonBaseUrl?.let(::sourceScope))
        }
        indexExternal()
        val season = value.season ?: return value
        val episode = value.episode ?: return value
        val scope = value.addonBaseUrl?.let(::sourceScope)
        val sourceRows = rowsByNative[Triple(value.contentId, season, episode)].orEmpty().filter {
            (scope == null || scope == it.scope) && (scope != null || !ambiguousExternalCoordinate(it.native, season, episode))
        } + rowsByRef[Triple(externalCanonical(value.contentId), season, episode)].orEmpty()
        val starts = sourceRows.map(::edge).toSet()
        val linked = episodeClosure(starts)
        if (linked.isEmpty()) return value
        val builtinTarget = entries.value.firstOrNull { it.native == target && it.series }
        val candidates = if (target == null) linked else linked.filter {
            it.native == target || externalCanonical(it.canonical) == externalCanonical(target) ||
                (builtinTarget != null && externalCanonical(it.canonical) == externalCanonical(builtinTarget.canonical))
        }
        val selected = candidates.sortedWith(compareBy<ExternalEpisode> { if (target != null && it.canonical == target) 0 else 1 }
            .thenBy { it.alias }.thenBy { if (nativePlaybackId(it.canonical)) 1 else 0 }
            .thenBy { if (externalCanonical(it.canonical).startsWith("tt")) 0 else 1 }
            .thenBy { externalCanonical(it.canonical) }.thenBy { it.canonicalSeason }.thenBy { it.canonicalEpisode }).firstOrNull() ?: return value
        if (target == selected.native) {
            if (ambiguousExternalCoordinate(target, selected.nativeSeason, selected.nativeEpisode)) return value
            return value.copy(contentId = target, videoId = selected.nativeVideo, season = selected.nativeSeason, episode = selected.nativeEpisode)
        }
        val id = target ?: externalCanonical(selected.canonical)
        val builtin = entries.value.firstOrNull { it.native == id }
        if (builtin != null && (selected.canonicalSeason to selected.canonicalEpisode) !in builtin.episodes) return value
        val video = builtin?.episodes?.get(selected.canonicalSeason to selected.canonicalEpisode)
            ?: selected.canonicalVideo?.takeIf { id == selected.canonical }
            ?: "$id:${selected.canonicalSeason}:${selected.canonicalEpisode}"
        if (id == value.contentId && selected.canonicalSeason == value.season && selected.canonicalEpisode == value.episode) return value
        return value.copy(contentId = id, videoId = video, season = selected.canonicalSeason, episode = selected.canonicalEpisode)
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
        val movieAliases = if (seriesHint == true) setOf(id) else externalMovies.related(id)
        val untypedTmdb = id.startsWith("tmdb:") && id.removePrefix("tmdb:").toIntOrNull() != null
        val matchingAliases = if (untypedTmdb) movieAliases else movieAliases.filterNot {
            it.startsWith("tmdb:") && it.removePrefix("tmdb:").toIntOrNull() != null
        }.toSet()
        val direct = entries.value.filter { row -> row.aliases.any { it in matchingAliases } && (seriesHint == null || row.series == seriesHint) }
        if (seriesHint == null && movieAliases.size > 1 && direct.any { it.series }) return setOf(id)
        // Numeric TMDB IDs can refer to unrelated movie and TV records.
        if (direct.map { it.series }.distinct().size > 1) return setOf(id)
        val kind = direct.firstOrNull()?.series ?: false.takeIf { movieAliases.size > 1 }
        var group = movieAliases
        do {
            val previous = group
            val providerAliases = entries.value.filter { row -> (kind == null || row.series == kind) && row.aliases.any { it in group } }.flatMap { it.aliases }
            val movieAliases = if (kind == true) emptySet() else (group + providerAliases).flatMap { externalMovies.related(it) }.toSet()
            group = group + providerAliases + movieAliases
        } while (previous != group)
        return group
    }
    internal fun canonical(id: String, seriesHint: Boolean? = null): String {
        val related = related(id, seriesHint)
        if (related == setOf(id) && entries.value.filter { id in it.aliases && (seriesHint == null || it.series == seriesHint) }.map { it.series }.distinct().size > 1) return id
        return related.firstOrNull { Regex("tt[0-9]+").matches(it) }
            ?: entries.value.firstOrNull { (it.native == id || id in it.aliases) && (seriesHint == null || it.series == seriesHint) }?.canonical
            ?: externalMovies.canonical(id).takeUnless { seriesHint == true } ?: id
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
        if (entries.value.any { it.native == value.contentId } &&
            !canProjectEpisode(value.contentId, canonical(value.contentId, true), value.season, value.episode)) return value
        if (requestedTarget != null && (requestedTarget.startsWith("xt_") || isExternalSource(requestedTarget))) {
            val normalized = progress(value)
            val projected = externalProgress(normalized, requestedTarget)
            return if (projected.contentId == requestedTarget) projected else value
        }
        var progress = externalProgress(value, requestedTarget)
        if (progress == value && hasExternalCoordinate(value.contentId, value.season, value.episode) &&
            requestedTarget == null && hasPartBinding(value.contentId)) return value
        if (progress == value && !hasExternalCoordinate(value.contentId, value.season, value.episode) &&
            entries.value.any { it.native == value.contentId }) {
            val parent = canonical(value.contentId, true)
            if (parent != value.contentId && value.season != null && value.episode != null) {
                val mapped = externalProgress(value.copy(contentId = parent, videoId = "$parent:${value.season}:${value.episode}"), requestedTarget)
                if (requestedTarget == null || !hasPartBinding(requestedTarget) || mapped.contentId == requestedTarget) progress = mapped
            }
        }
        if (requestedTarget != null && progress == value && !hasPartBinding(requestedTarget)) {
            val mapped = externalProgress(value)
            if (mapped != value && requestedTarget in related(mapped.contentId, value.contentType in setOf("series", "tv")) &&
                canProjectEpisode(mapped.contentId, requestedTarget, mapped.season, mapped.episode)) progress = mapped
        }
        if (requestedTarget != null && progress.contentId != requestedTarget && progress == value &&
            (hasPartBinding(requestedTarget) || hasPartBinding(value.contentId))) return value
        if ((value.contentId.startsWith("xt_") || isExternalSource(value.contentId)) &&
            (progress.contentId.startsWith("xt_") || isExternalSource(progress.contentId)) &&
            requestedTarget != null && requestedTarget != value.contentId) return value
        if (requestedTarget == null && hasPartBinding(progress.contentId) &&
            hasExternalCoordinate(progress.contentId, progress.season, progress.episode)) return progress
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
    /** Attach display/watch identity without rewriting any source playback coordinate. */
    internal fun withWatchIdentity(meta: Meta, baseUrl: String): Meta {
        if (meta.apiType != "series" || Regex("tt[0-9]+|tmdb:.*").matches(meta.id)) return meta
        return meta.copy(videos = meta.videos.map { video ->
        val season = video.season ?: return@map video
        val episode = video.episode ?: return@map video
        val mapped = progress(WatchProgress(meta.id, meta.apiType, meta.name, null, null, null,
            video.id, season, episode, video.title, 0, 0, 0, addonBaseUrl = baseUrl))
        val publicId = canonical(mapped.contentId, true)
        if (!Regex("tt[0-9]+|tmdb:(?:tv:|series:)?[0-9]+").matches(publicId)) video
        else video.copy(watchIdentity = com.nuvio.tv.domain.model.EpisodeWatchIdentity(
            publicId, mapped.season ?: season, mapped.episode ?: episode))
        })
    }
    internal fun watched(item: WatchedItem, target: String? = null): WatchedItem {
        val mapped = progress(WatchProgress(item.contentId, item.contentType, item.title, item.poster, null, null,
            item.contentId, item.season, item.episode, null, 0, 0, item.watchedAt), target)
        return item.copy(contentId = mapped.contentId, season = mapped.season, episode = mapped.episode)
    }
}
