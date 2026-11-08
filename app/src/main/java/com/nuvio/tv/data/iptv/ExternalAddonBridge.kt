package com.nuvio.tv.data.iptv

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.data.remote.dto.VideoDto
import com.nuvio.tv.data.repository.SharedPlaybackIdentity
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Native stream IDs stay native; verified episode identities are stored separately. */
@Singleton
class ExternalAddonBridge @Inject constructor(
    private val api: AddonApi,
    private val tmdb: TmdbApi? = null,
    private val identities: SharedPlaybackIdentity? = null
) {
    private data class SearchEntry(val created: Long, val rows: List<MetaPreviewDto>)
    private val searches = object : LinkedHashMap<String, SearchEntry>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchEntry>?) = size > 128
    }
    private data class Info(val titles: Set<String>, val tmdbId: String?, val imdbId: String?, val arabic: Boolean, val turkish: Boolean)
    private data class InfoEntry(val created: Long, val info: Info)
    private val infoCache = object : LinkedHashMap<String, InfoEntry>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InfoEntry>?) = size > 128
    }
    private data class NativeEnrichment(val created: Long, val source: Meta, val result: Meta, val layoutRevision: Int)
    private val nativeEnrichments = object : LinkedHashMap<String, NativeEnrichment>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NativeEnrichment>?) = size > 32
    }
    fun isXtream(addon: Addon) = addon.resources.any { resource ->
        resource.idPrefixes.orEmpty().any { it.startsWith("xt_") }
    }
    /** Better Stream advertises tmdb: but does not resolve the typed movie route. */
    fun streamRequestId(addon: Addon, type: String, id: String): String {
        if (type != "movie" || !addon.id.startsWith("org.betterstream.") || !isXtream(addon)) return id
        val number = Regex("tmdb:movie:([1-9][0-9]*)").matchEntire(id)?.groupValues?.get(1) ?: return id
        return "tmdb:$number"
    }
    internal fun verifiedSiblingMap(tmdbId: String?, canonical: Map<Int, Set<Int>>, native: Map<Int, List<Int>>,
        season: Int, nativeSeason: Int, turkish: Boolean, translated: Boolean = false): Map<Int, Int>? {
        if (!turkish || tmdbId == null || season != nativeSeason) return null
        // Prefer actual same-work provider availability over TMDB's planned season counts,
        // including earlier seasons used to verify a cumulative offset.
        val layout = canonical.toMutableMap()
        for (number in 1..season) {
            val verified = identities?.verifiedSeriesSeasonForCount(tmdbId, number, native[number]?.size)
            if (verified != null && verified.size == native[number]?.size) layout[number] = verified
            else if (translated) identities?.verifiedSeriesSeasonAvailable(tmdbId, number)?.let { available ->
                val current = layout[number].orEmpty()
                if (current.isEmpty() || (current == (1..current.size).toSet() && available.size > current.size))
                    layout[number] = available
            }
        }
        val exact = layout[season]?.takeIf { it.size == native[nativeSeason]?.size }
        if (exact != null) ExternalEpisodeNumbering.episodeMap(layout, native, season, nativeSeason,
            exact, false, true)?.let { return it }
        if (!translated) return null
        return ExternalEpisodeNumbering.translatedMap(layout, native, season, nativeSeason)
    }
    private data class EditionProof(val created: Long, val translated: Boolean)
    private val editionProofs = object : LinkedHashMap<String, EditionProof>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EditionProof>?) = size > 128
    }
    internal fun translatedLabel(label: String): Boolean {
        val value = ExternalAddonTitleMatcher.normalize(label)
        return Regex("(?:^| )ar tr s(?: |$)").containsMatchIn(value) ||
            (value.contains("\u0645\u062a\u0631\u062c\u0645") && (value.contains("\u062a\u0631\u0643\u064a") || value.contains("\u062a\u0631\u0643\u064a\u0647")))
    }
    /** One bounded metadata-only stream lookup, only when relaxed mapping is needed.
     * No media is played and no subscription URL is stored in the proof cache. */
    internal suspend fun translatedEdition(addon: Addon, native: Meta): Boolean {
        if (!isXtream(addon)) return false
        if (translatedLabel((listOf(native.name) + native.genres).joinToString(" "))) return true
        val sample = native.videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            .minWithOrNull(compareBy<Video> { it.season }.thenBy { it.episode }) ?: return false
        val key = hash(addon.baseUrl + "\u0000" + addon.version + "\u0000" + sample.id)
        synchronized(editionProofs) { editionProofs[key]?.takeIf { System.nanoTime() - it.created < 300_000_000_000L } }
            ?.let { return it.translated }
        val result = try {
            withTimeoutOrNull(3_000) {
                val response = api.getStreams(url(addon, "stream/series/${encode(sample.id)}.json"))
                if (!response.isSuccessful) return@withTimeoutOrNull null
                response.body()?.streams?.any {
                    translatedLabel(listOfNotNull(it.name, it.title, it.description).joinToString(" "))
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (result != null) synchronized(editionProofs) { editionProofs[key] = EditionProof(System.nanoTime(), result) }
        return result == true
    }
    fun supports(addon: Addon, type: String) = addon.resources.any {
        it.name == "stream" && (it.types.isEmpty() || type in it.types)
    } && catalogs(addon, type).isNotEmpty()
    private fun catalogs(addon: Addon, type: String) = addon.catalogs.filter { c ->
        c.apiType == type && (c.extra.any { it.name == "search" } || "search" in c.extraSupported) &&
            c.extra.none { it.isRequired && it.name != "search" } && c.extraRequired.none { it != "search" }
    }
    suspend fun resolve(addon: Addon, type: String, canonical: Meta, season: Int?, episode: Int?) =
        resolveAll(addon, type, canonical, season, episode).singleOrNull()

    fun cachedMovieIds(addon: Addon, id: String): List<String> {
        val tmdb = Regex("tmdb:(?:movie:)?([0-9]+)").matchEntire(id)?.groupValues?.get(1)
        val imdb = id.takeIf { Regex("tt[0-9]+").matches(it) }
        if (tmdb == null && imdb == null) return emptyList()
        return identities?.externalMovieSources(addon.baseUrl, tmdb, imdb).orEmpty()
    }

    suspend fun resolveAll(addon: Addon, type: String, canonical: Meta, season: Int?, episode: Int?,
        onResolved: (suspend (String) -> Unit)? = null): List<String> {
        val accepted = linkedSetOf<String>()
        return withTimeoutOrNull(25_000) {
            if (type == "movie") {
                val known = (cachedMovieIds(addon, canonical.id) + identities?.externalMovieSources(addon.baseUrl, canonical.tmdbId, canonical.imdbId).orEmpty()).distinct()
                if (known.isNotEmpty()) {
                    known.forEach { accepted.add(it); onResolved?.invoke(it) }
                    return@withTimeoutOrNull accepted.toList()
                }
            }
            val info = info(canonical, type)
            val parts = if (type == "series" && season == 1) info.titles.mapNotNull { label -> splitPart(label)?.let { Triple(label, it.first, it.second) } } else emptyList()
            val partNumber = parts.map { it.third }.distinct().singleOrNull()
            val bases = parts.filter { it.third == partNumber }.flatMap { ExternalAddonTitleMatcher.titleVariants(it.second) }.toSet()
            val numberedTitles = parts.flatMap { ExternalAddonTitleMatcher.titleVariants(it.first) }.toSet()
            fun isPartParent(candidate: ExternalAddonTitleMatcher.Candidate) = partNumber != null &&
                ExternalAddonTitleMatcher.titleVariants(candidate.name).intersect(bases).isNotEmpty() &&
                ExternalAddonTitleMatcher.titleVariants(candidate.name).intersect(numberedTitles).isEmpty()
            val titles = (parts.map { it.second } + info.titles).toSet()
            val expectedVariants = titles.flatMap(ExternalAddonTitleMatcher::titleVariants).toSet()
            val expectedYear = if (info.arabic || (type == "series" && info.turkish)) null else year(canonical.releaseInfo ?: canonical.released)
            fun candidate(row: MetaPreviewDto): ExternalAddonTitleMatcher.Candidate? {
                if (row.type != type) return null
                return ExternalAddonTitleMatcher.Candidate(row.id ?: return null, row.name ?: return null,
                    year(row.releaseInfo ?: row.released), row.imdbId, row.tmdbId)
            }
            fun matches(value: ExternalAddonTitleMatcher.Candidate): Boolean {
                val parent = isPartParent(value)
                return ExternalAddonTitleMatcher.choose(titles.toList(), if (parent) null else expectedYear, listOf(value),
                    if (parent) null else info.imdbId, if (parent) null else info.tmdbId) is ExternalAddonTitleMatcher.Decision.Matched
            }
            suspend fun resolveCandidate(value: ExternalAddonTitleMatcher.Candidate): Pair<String, Boolean>? {
                if (type != "series" || season == null || episode == null) return null
                val expected = canonical.videos.singleOrNull { it.season == season && it.episode == episode } ?: return null
                val response = try {
                    withTimeoutOrNull(5_000) {
                        api.getMeta(url(addon, "meta/${encode(type)}/${encode(value.originalId)}.json"))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } ?: return null
                if (!response.isSuccessful) return null
                val native = response.body()?.meta ?: return null
                if (native.id != value.originalId || native.type != type) return null
                val partParent = isPartParent(value)
                if (!partParent && (conflicts(info.imdbId, native.imdbId) || conflicts(info.tmdbId, native.tmdbId))) return null
                val nativeSeason = if (partParent) partNumber!! else season
                if (partParent) enrichNative(addon, native.toDomain("Episode"))
                val completeSeason = canonical.videos.filter { it.season == season }
                val nativeSeasonVideos = native.videos.orEmpty().filter { it.season == nativeSeason }
                val airedNumbers = completeSeason.filter { it.available != false && isEpisodeReleaseAired(it.released) != false }
                    .mapNotNull { it.episode }.toSet()
                val mapping = ExternalEpisodeNumbering.episodeMap(
                    canonical.videos.filter { it.season != null }.groupBy { it.season!! }
                        .mapValues { (_, videos) -> videos.mapNotNull { it.episode }.toSet() },
                    native.videos.orEmpty().filter { it.season != null }.groupBy { it.season!! }
                        .mapValues { (_, videos) -> videos.mapNotNull { it.episode } },
                    season, nativeSeason, airedNumbers, info.arabic, info.turkish)
                    ?: if (!partParent) verifiedSiblingMap(info.tmdbId,
                        canonical.videos.filter { it.season != null }.groupBy { it.season!! }.mapValues { (_, vs) -> vs.mapNotNull { it.episode }.toSet() },
                        native.videos.orEmpty().filter { it.season != null }.groupBy { it.season!! }.mapValues { (_, vs) -> vs.mapNotNull { it.episode } },
                        season, nativeSeason, info.turkish, info.turkish && translatedEdition(addon, native.toDomain("Episode"))) else null
                if (BuildConfig.DEBUG) android.util.Log.d("ExternalAddonBridge",
                    "Episode mapping turkish=${info.turkish} season=$season episode=$episode canonicalCount=${completeSeason.size} nativeCount=${nativeSeasonVideos.size} mapped=${mapping?.size ?: 0}")
                val episodeMap = mapping ?: run {
                    val publicId = info.imdbId ?: info.tmdbId?.let { "tmdb:tv:$it" }
                    if (info.turkish && !partParent && publicId != null)
                        identities?.invalidateExternalSeason(addon.baseUrl, native.id, nativeSeason, publicId)
                    return null
                }
                val expectedSeason = completeSeason.filter { it.episode in episodeMap }
                val video = nativeSeasonVideos.singleOrNull { it.episode == episodeMap[episode] } ?: return null
                if (!compatible(expected, video, info.arabic, expectedSeason.size, info.turkish)) return null
                val canonicalId = (if (partParent) canonical.id.takeIf { Regex("cinemana-series:[0-9]+|cee:[0-9]+").matches(it) } else null)
                    ?: info.tmdbId?.takeIf { partParent }?.let { "tmdb:tv:$it" }
                    ?: info.imdbId?.takeIf { Regex("tt[0-9]+").matches(it) }
                    ?: info.tmdbId?.let { "tmdb:tv:$it" }
                    ?: canonical.id.takeIf { Regex("cinemana-series:[0-9]+|cee:[0-9]+").matches(it) }
                if (canonicalId != null) identities?.learnExternalSeason(addon.baseUrl, native.id, canonicalId, nativeSeason, season,
                    expectedSeason.mapNotNull { item ->
                        val number = item.episode ?: return@mapNotNull null
                        val nativeNumber = episodeMap[number] ?: return@mapNotNull null
                        val source = nativeSeasonVideos.singleOrNull { it.episode == nativeNumber } ?: return@mapNotNull null
                        if (compatible(item, source, info.arabic, expectedSeason.size, info.turkish)) Triple(nativeNumber, source.id, number) else null
                    }, allowPartAlias = partParent, canonicalVideos = if (canonicalId == canonical.id)
                        expectedSeason.mapNotNull { item -> item.episode?.let { it to item.id } }.toMap() else emptyMap())
                if (video.id.isBlank()) return null
                val label = ExternalAddonTitleMatcher.normalize(native.name + " " + video.title.orEmpty())
                val translated = label.contains("مترجم") || Regex("(?:^| )ar tr s(?: |$)").containsMatchIn(label)
                return video.id to translated
            }
            val rows = mutableListOf<MetaPreviewDto>()
            val seen = mutableSetOf<String>()
            for (title in titles.take(4)) for (catalog in catalogs(addon, type)) {
                val key = hash(listOf(addon.baseUrl, addon.version, catalog.id, type, title).joinToString("\u0000"))
                val cached = synchronized(searches) { searches[key]?.takeIf { System.nanoTime() - it.created < 300_000_000_000L } }
                val found = if (cached != null) cached.rows else {
                    val response = try {
                        api.getCatalog(url(addon, "catalog/${encode(type)}/${encode(catalog.id)}/search=${encode(title)}.json"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { continue }
                    if (!response.isSuccessful) continue
                    val result = response.body()?.metas?.filterNotNull() ?: continue
                    synchronized(searches) { searches[key] = SearchEntry(System.nanoTime(), result) }
                    result
                }
                rows += found
                if (type != "series") continue
                val candidates = found.mapNotNull(::candidate).distinct().filter(::matches)
                    .sortedByDescending { ExternalAddonTitleMatcher.titleVariants(it.name).intersect(expectedVariants).size }
                for (value in candidates.take(12)) {
                    if (!seen.add(value.originalId)) continue
                    val resolved = resolveCandidate(value) ?: continue
                    if (accepted.add(resolved.first)) onResolved?.invoke(resolved.first)
                    // A verified translated edition satisfies the requested Turkish episode.
                    // Do not keep searching foreign/dubbed editions after finding it.
                    if (onResolved == null && info.turkish && resolved.second) return@withTimeoutOrNull accepted.toList()
                }
            }
            if (type == "movie") {
                val match = ExternalAddonTitleMatcher.choose(titles.toList(), expectedYear,
                    rows.mapNotNull(::candidate).distinct(), info.imdbId, info.tmdbId)
                    as? ExternalAddonTitleMatcher.Decision.Matched
                if (match == null) emptyList() else {
                    if (isXtream(addon)) identities?.learnExternalMovie(addon.baseUrl, match.candidate.originalId, info.tmdbId, info.imdbId)
                    if (accepted.add(match.candidate.originalId)) onResolved?.invoke(match.candidate.originalId)
                    accepted.toList()
                }
            } else accepted.toList()
        } ?: accepted.toList() // A slow later candidate must not discard an already verified episode.
    }

    internal fun acceptsLayout(originalLanguage: String?, expected: Set<Int>, actual: Set<Int>, aired: Set<Int> = expected) =
        ExternalEpisodeNumbering.acceptsLayout(originalLanguage == "ar", expected, actual, aired)

    internal fun compatible(expected: Video, actual: VideoDto, arabic: Boolean, count: Int, turkish: Boolean = false): Boolean {
        if (actual.id.isBlank()) return false
        if (arabic || turkish) return true
        val name = ExternalAddonTitleMatcher.normalize(actual.title ?: actual.name.orEmpty())
        val expectedName = ExternalAddonTitleMatcher.normalize(expected.title)
        val generic = Regex("(?:^|.* )(?:episode|ep|الحلقه) 0*[0-9]+$")
        val numbered = Regex("(?:^|.* )s[0-9]+ ?e[0-9]+$")
        if (expectedName.isBlank() || (name != expectedName && !((generic.matches(name) || numbered.matches(name)) && count >= 2))) return false
        return expected.released == null || actual.released == null || expected.released.take(10) == actual.released.take(10)
    }

    /** Learn the inverse direction when a user opens an Xtream catalog title directly. */
    suspend fun enrichNative(addon: Addon, native: Meta): Meta = withContext(Dispatchers.IO) {
        if (!isXtream(addon) || !native.id.startsWith("xt_")) return@withContext native
        val key = hash(addon.baseUrl + "\u0000" + addon.version + "\u0000" + native.id)
        val cached = synchronized(nativeEnrichments) { nativeEnrichments[key] }
        if (cached != null && cached.source == native && cached.layoutRevision == identities?.entries?.value.hashCode() && System.nanoTime() - cached.created < 300_000_000_000L)
            return@withContext cached.result
        val result = if (native.apiType == "movie") enrichNativeMovie(addon, native) else enrichNativeSeries(addon, native)
        if (result.sharedProgressCompatible) synchronized(nativeEnrichments) {
            nativeEnrichments[key] = NativeEnrichment(System.nanoTime(), native, result, identities?.entries?.value.hashCode())
        }
        result
    }

    private suspend fun enrichNativeMovie(addon: Addon, native: Meta): Meta {
        val api = tmdb ?: return native
        if (identities == null || !com.nuvio.tv.data.repository.externalMovieId(native.id) || BuildConfig.TMDB_API_KEY.isBlank()) return native
        return withTimeoutOrNull(12_000) {
            try {
                val sourceYear = year(native.releaseInfo ?: native.released)
                val match = native.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    com.nuvio.android.cinemana.CinemanaTmdbMatcher.Match(it, native.name, null, null, null, 200.0)
                } ?: com.nuvio.android.cinemana.CinemanaTmdbMatcher(BuildConfig.TMDB_API_KEY)
                    .match(ExternalAddonTitleMatcher.titleVariants(native.name).toList(), sourceYear, false, native.imdbId)
                    ?: return@withTimeoutOrNull native
                val response = api.getMovieDetails(match.tmdbId, BuildConfig.TMDB_API_KEY, "ar-SA")
                val details = response.body()?.takeIf { response.isSuccessful && it.id == match.tmdbId } ?: return@withTimeoutOrNull native
                val officialYear = year(details.releaseDate)
                if (details.originalLanguage != "ar" && sourceYear != null && officialYear != null && kotlin.math.abs(sourceYear - officialYear) > 1) return@withTimeoutOrNull native
                val imdb = api.getMovieExternalIds(match.tmdbId, BuildConfig.TMDB_API_KEY).body()?.imdbId
                    ?.takeIf { Regex("tt[0-9]+").matches(it) }
                if (conflicts(native.imdbId, imdb) || conflicts(native.tmdbId, match.tmdbId.toString())) return@withTimeoutOrNull native
                identities.learnExternalMovie(addon.baseUrl, native.id, match.tmdbId.toString(), imdb ?: native.imdbId)
                native.copy(tmdbId = match.tmdbId.toString(), imdbId = imdb ?: native.imdbId, sharedProgressCompatible = true)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { native }
        } ?: native
    }

    private suspend fun enrichNativeSeries(addon: Addon, native: Meta): Meta {
        val api = tmdb ?: return native
        if (identities == null || !isXtream(addon) || native.apiType != "series" ||
            !Regex("xt_s_[A-Za-z0-9]+_[0-9]+").matches(native.id) || BuildConfig.TMDB_API_KEY.isBlank()) return native
        return withTimeoutOrNull(25_000) {
            try {
                val labels = ExternalAddonTitleMatcher.titleVariants(native.name).toList()
                val matcher = com.nuvio.android.cinemana.CinemanaTmdbMatcher(BuildConfig.TMDB_API_KEY)
                val match = native.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    com.nuvio.android.cinemana.CinemanaTmdbMatcher.Match(it, native.name, null, null, null, 200.0)
                } ?: matcher.match(labels, null, true, native.imdbId)
                    ?: matcher.match(labels, year(native.releaseInfo ?: native.released), true, native.imdbId)
                    ?: return@withTimeoutOrNull native
                val details = api.getTvDetails(match.tmdbId, BuildConfig.TMDB_API_KEY, "ar-SA")
                    .body() ?: return@withTimeoutOrNull native
                if (details.id != match.tmdbId) return@withTimeoutOrNull native
                val arabic = details.originalLanguage == "ar"
                val turkish = details.originalLanguage == "tr"
                val sourceYear = year(native.releaseInfo ?: native.released)
                if (!arabic && !turkish && sourceYear != null && match.year != null && kotlin.math.abs(sourceYear - match.year) > 1)
                    return@withTimeoutOrNull native
                val imdb = api.getTvExternalIds(match.tmdbId, BuildConfig.TMDB_API_KEY).body()?.imdbId
                    ?.takeIf { Regex("tt[0-9]+").matches(it) }
                if (conflicts(native.imdbId, imdb) || conflicts(native.tmdbId, match.tmdbId.toString())) return@withTimeoutOrNull native
                val numberedPart = labels.any { splitPart(it) != null } &&
                    !canUseSeriesImdb(match.tmdbId.toString(), imdb, labels.toSet())
                val canonical = if (numberedPart) "tmdb:tv:${match.tmdbId}" else imdb ?: "tmdb:tv:${match.tmdbId}"
                if (!numberedPart) identities.learnExternalCanonical(match.tmdbId.toString(), imdb)
                val verified = mutableSetOf<String>()
                val sourceSeasons = native.videos.filter { (it.season ?: 0) > 0 }.groupBy { it.season!! }
                val canonicalSeasons = mutableMapOf<Int, Set<Int>>()
                for ((season, videos) in sourceSeasons.entries.sortedBy { it.key }.take(30)) {
                    val complete = api.getTvSeasonDetails(match.tmdbId, season, BuildConfig.TMDB_API_KEY, "ar-SA")
                        .body()?.episodes ?: continue
                    val completeNumbers = complete.mapNotNull { it.episodeNumber }.toSet()
                    canonicalSeasons[season] = completeNumbers
                    val aired = complete.filter { isEpisodeReleaseAired(it.airDate) != false }
                    val airedNumbers = aired.mapNotNull { it.episodeNumber }.toSet()
                    val episodeMap = ExternalEpisodeNumbering.episodeMap(canonicalSeasons,
                        sourceSeasons.mapValues { (_, items) -> items.mapNotNull { it.episode } },
                        season, season, airedNumbers, arabic, turkish)
                        ?: if (!numberedPart) verifiedSiblingMap(match.tmdbId.toString(), canonicalSeasons,
                            sourceSeasons.mapValues { (_, items) -> items.mapNotNull { it.episode } }, season, season, turkish, turkish && translatedEdition(addon, native)) else null
                    val acceptedMap = episodeMap ?: run {
                            if (turkish) identities.invalidateExternalSeason(addon.baseUrl, native.id, season, canonical)
                            null
                        } ?: continue
                    val expected = complete.filter { it.episodeNumber in acceptedMap }
                    val expectedNumbers = expected.mapNotNull { it.episodeNumber }.toSet()
                    val verifiedSeason = mutableListOf<Triple<Int, String, Int>>()
                    for (item in expected) {
                        val number = item.episodeNumber ?: continue
                        val nativeNumber = acceptedMap[number] ?: continue
                        val video = videos.singleOrNull { it.episode == nativeNumber } ?: continue
                        val canonicalVideo = video.copy(title = item.name.orEmpty(), released = item.airDate)
                        val sourceVideo = VideoDto(video.id, title = video.title, released = video.released, season = season, episode = number)
                        if (!compatible(canonicalVideo, sourceVideo, arabic, expectedNumbers.size, turkish)) continue
                        verifiedSeason += Triple(nativeNumber, video.id, number)
                        verified += video.id
                    }
                    identities.learnExternalSeason(addon.baseUrl, native.id, canonical, season, season, verifiedSeason)
                }
                native.copy(tmdbId = match.tmdbId.toString(), imdbId = imdb,
                    sharedProgressCompatible = verified.isNotEmpty(), verifiedPlaybackVideoIds = verified)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { native }
        } ?: native
    }
    private suspend fun info(meta: Meta, type: String): Info {
        val cacheKey = hash(listOf(type, meta.id, meta.name, meta.tmdbId, meta.imdbId, meta.releaseInfo, meta.released).joinToString("\u0000"))
        synchronized(infoCache) { infoCache[cacheKey]?.takeIf { System.nanoTime() - it.created < 300_000_000_000L } }?.let { return it.info }
        val titles = linkedSetOf(meta.name)
        var id = meta.tmdbId
        if (tmdb != null && id == null) id = Regex("tmdb:(?:(?:tv|series|movie):)?([0-9]+)").matchEntire(meta.id)?.groupValues?.get(1)
        var imdb = meta.imdbId
        if (tmdb != null && !BuildConfig.TMDB_API_KEY.isBlank() && id == null) try {
            val external = imdb ?: meta.id.takeIf { Regex("tt[0-9]+").matches(it) }
            if (external != null) {
                imdb = external
                val result = tmdb.findByExternalId(external, BuildConfig.TMDB_API_KEY).body()
                id = (if (type == "series") result?.tvResults else result?.movieResults)?.singleOrNull()?.id?.toString()
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* optional ID lookup */ }
        var arabic = false
        var turkish = false
        var detailsLoaded = false
        if (tmdb != null && !BuildConfig.TMDB_API_KEY.isBlank() && id?.toIntOrNull() != null) try {
            val details = if (type == "series") tmdb.getTvDetails(id.toInt(), BuildConfig.TMDB_API_KEY, "ar-SA")
                else tmdb.getMovieDetails(id.toInt(), BuildConfig.TMDB_API_KEY, "ar-SA")
            details.body()?.takeIf { details.isSuccessful && it.id.toString() == id }?.let {
                detailsLoaded = true
                listOfNotNull(it.name, it.title, it.originalName, it.originalTitle).filter(String::isNotBlank).forEach(titles::add)
                arabic = it.originalLanguage == "ar"
                turkish = it.originalLanguage == "tr"
                if (type == "series" && turkish) {
                    val existing = titles.toList()
                    titles.clear()
                    listOfNotNull(it.originalName, it.name).filter(String::isNotBlank).forEach(titles::add)
                    titles.addAll(existing)
                }
            }
            if (imdb == null) imdb = (if (type == "series") tmdb.getTvExternalIds(id.toInt(), BuildConfig.TMDB_API_KEY)
                else tmdb.getMovieExternalIds(id.toInt(), BuildConfig.TMDB_API_KEY)).body()?.imdbId
            if (type == "series") {
                val aliases = if (turkish) withTimeoutOrNull(2_000) {
                    tmdb.getTvAlternativeTitles(id.toInt(), BuildConfig.TMDB_API_KEY).body()?.tvTitles
                } else tmdb.getTvAlternativeTitles(id.toInt(), BuildConfig.TMDB_API_KEY).body()?.tvTitles
                aliases.orEmpty().mapNotNull { it.title }.filter(String::isNotBlank).forEach(titles::add)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* optional aliases */ }
        if (type == "series" && id != null && canUseSeriesImdb(id, imdb, titles)) identities?.learnExternalCanonical(id, imdb)
        return Info(titles, id, imdb, arabic, turkish).also { result ->
            if (detailsLoaded) synchronized(infoCache) { infoCache[cacheKey] = InfoEntry(System.nanoTime(), result) }
        }
    }
    internal fun splitPart(title: String): Pair<String, Int>? {
        val match = Regex("^(.+?)\\s+([2-9]|[1-9][0-9])$").matchEntire(title.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2].toInt()
    }
    /** A numeric title (e.g. an age) is not automatically a numbered sequel. */
    internal suspend fun canUseSeriesImdb(id: String, imdb: String?, titles: Set<String>): Boolean {
        if (imdb == null) return false
        if (titles.none { splitPart(it) != null }) return true
        if (tmdb == null || BuildConfig.TMDB_API_KEY.isBlank()) return false
        return try {
            withTimeoutOrNull(2_000) {
                tmdb.findByExternalId(imdb, BuildConfig.TMDB_API_KEY).body()?.tvResults
                    ?.filter { it.id > 0 }?.distinctBy { it.id }?.singleOrNull()?.id?.toString() == id
            } == true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }
    private fun conflicts(expected: String?, actual: String?) = !expected.isNullOrBlank() && !actual.isNullOrBlank() && !expected.equals(actual, true)
    private fun year(value: String?) = value?.let { Regex("(?:19|20)[0-9]{2}").find(it)?.value?.toIntOrNull() }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun url(addon: Addon, path: String): String {
        val base = addon.baseUrl.trimEnd('/')
        val query = base.indexOf('?')
        return if (query < 0) "$base/$path" else "${base.substring(0, query).trimEnd('/')}/$path${base.substring(query)}"
    }
}
