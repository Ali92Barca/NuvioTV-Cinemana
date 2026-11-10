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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.nuvio.tv.data.repository.sharedRequest
import java.util.concurrent.ConcurrentHashMap
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private val translatedTurkishPrefixPattern = Regex("(?:^| )ar tr s(?: |$)")
private val imdbIdentityPattern = Regex("tt[0-9]+")
private val builtinSeriesPattern = Regex("cinemana-series:[0-9]+|cee:[0-9]+")
private val editionEpisodeLabelPattern = Regex("(S[0-9]+\\s*E[0-9]+\\s*[-–:]?\\s*)\\((?:Original|Light\\s+Modified)\\)\\s*", RegexOption.IGNORE_CASE)
private val tmdbIdentityPattern = Regex("tmdb:(?:(?:tv|series|movie):)?([0-9]+)")
private val releaseYearPattern = Regex("(?:19|20)[0-9]{2}")

private val typedMovieIdentityPattern = Regex("tmdb:movie:([1-9][0-9]*)")
private val movieIdentityPattern = Regex("tmdb:(?:movie:)?([0-9]+)")
private val seriesEpisodeIdentityPattern = Regex("(tt[0-9]+|tmdb:(?:(?:tv|series):)?[0-9]+)(?::([0-9]+):([0-9]+))?")
private val numberedEpisodePattern = Regex("(?:^| )s([0-9]+) ?e([0-9]+)(?: |$)")
private val episodeOnlyTitlePattern = Regex("(?:^|.* )(?:episode|ep|الحلقه) 0*[0-9]+$")
private val coordinateOnlyTitlePattern = Regex("(?:^|.* )s[0-9]+ ?e[0-9]+$")
private val numberedPartPattern = Regex("^(.+?)\\s+([2-9]|[1-9][0-9])$")

/** Native stream IDs stay native; verified episode identities are stored separately. */
@Singleton
class ExternalAddonBridge @Inject constructor(
    private val api: AddonApi,
    private val tmdb: TmdbApi? = null,
    private val identities: SharedPlaybackIdentity? = null,
    private val matchCache: IptvIdentityCache? = null,
    http: okhttp3.OkHttpClient = okhttp3.OkHttpClient()
) {
    private val tmdbFetch = com.nuvio.tv.core.network.ProviderMetadataFetch(http)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = ConcurrentHashMap<String, Deferred<Meta>>()
    private val refreshLimit = Semaphore(1)
    @Volatile internal var cacheGeneration = 0L
        private set
    private data class SearchEntry(val created: Long, val rows: List<MetaPreviewDto>)
    fun forgetAddon(baseUrl: String) {
        refreshes.values.forEach { it.cancel() }
        refreshes.clear()
        identities?.forgetExternalAddon(baseUrl)
        matchCache?.remove(baseUrl)
        synchronized(searches) { searches.clear() }
        synchronized(nativeEnrichments) { nativeEnrichments.clear() }
        synchronized(editionProofs) { editionProofs.clear() }
        cacheGeneration += 1
    }
    private val searches = object : LinkedHashMap<String, SearchEntry>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchEntry>?) = size > 128
    }
    private data class Info(val titles: Set<String>, val tmdbId: String?, val imdbId: String?, val arabic: Boolean, val turkish: Boolean)
    private data class InfoEntry(val created: Long, val info: Info)
    private val infoCache = object : LinkedHashMap<String, InfoEntry>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InfoEntry>?) = size > 128
    }
    private data class NativeEnrichment(val created: Long, val source: Meta, val result: Meta, val layoutRevision: Long)
    private val nativeEnrichments = object : LinkedHashMap<String, NativeEnrichment>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NativeEnrichment>?) = size > 32
    }
    private data class EpisodeNames(val created: Long, val names: Map<Int, String>)
    private val englishEpisodeNames = object : LinkedHashMap<Pair<Int, Int>, EpisodeNames>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, EpisodeNames>?) = size > 128
    }
    fun isXtream(addon: Addon) = addon.resources.any { resource ->
        resource.idPrefixes.orEmpty().any { it.startsWith("xt_") }
    }
    /** Better Stream advertises tmdb: but does not resolve the typed movie route. */
    fun streamRequestId(addon: Addon, type: String, id: String): String {
        if (type != "movie" || !addon.id.startsWith("org.betterstream.") || !isXtream(addon)) return id
        val number = typedMovieIdentityPattern.matchEntire(id)?.groupValues?.get(1) ?: return id
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
    internal fun sharedOriginalMap(tmdbId: String?, canonical: Map<Int, Set<Int>>, native: Map<Int, List<Int>>,
        season: Int, nativeSeason: Int): Map<Int, Int>? {
        val layout = canonical.toMutableMap()
        if (tmdbId != null) identities?.verifiedSeriesSeasonAvailable(tmdbId, season)?.let { available ->
            val current = layout[season].orEmpty()
            if (available.size > current.size && available == (1..available.size).toSet()) layout[season] = available
        }
        return ExternalEpisodeNumbering.translatedMap(layout, native, season, nativeSeason)
    }
    private data class EditionProof(val created: Long, val translated: Boolean)
    private val editionProofs = object : LinkedHashMap<String, EditionProof>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EditionProof>?) = size > 128
    }
    internal fun translatedLabel(label: String): Boolean {
        val value = ExternalAddonTitleMatcher.normalize(label)
        return translatedTurkishPrefixPattern.containsMatchIn(value) ||
            IptvIdentityCache.translatedCatalog(value)
    }
    /** One bounded metadata-only stream lookup, only when relaxed mapping is needed.
     * No media is played and no subscription URL is stored in the proof cache. */
    internal suspend fun translatedEdition(addon: Addon, native: Meta): Boolean {
        if (!com.nuvio.tv.data.repository.privateAddonId(native.id)) return false
        if (matchCache?.translated(addon.baseUrl, native.id) == true) return true
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
        if (result == true) matchCache?.rememberCatalog(addon.baseUrl, "مترجم", listOf(native.id))
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
        val tmdb = movieIdentityPattern.matchEntire(id)?.groupValues?.get(1)
        val imdb = id.takeIf { imdbIdentityPattern.matches(it) }
        if (tmdb == null && imdb == null) return emptyList()
        return identities?.externalMovieSources(addon.baseUrl, tmdb, imdb).orEmpty()
    }

    fun cachedSeriesIds(addon: Addon, id: String, season: Int?, episode: Int?): List<String> {
        if (season == null || episode == null) return emptyList()
        val match = seriesEpisodeIdentityPattern
            .matchEntire(id) ?: return emptyList()
        if (match.groupValues[2].isNotEmpty() &&
            (match.groupValues[2].toIntOrNull() != season || match.groupValues[3].toIntOrNull() != episode)) return emptyList()
        return identities?.externalEpisodeSources(addon.baseUrl, match.groupValues[1], season, episode).orEmpty()
    }

    suspend fun resolveAll(addon: Addon, type: String, canonical: Meta, season: Int?, episode: Int?,
        onResolved: (suspend (String) -> Unit)? = null): List<String> {
        withContext(Dispatchers.IO) { identities?.migrateLegacyCache() }
        val accepted = linkedSetOf<String>()
        suspend fun discover(): List<String> {
            if (type == "movie") {
                val known = (cachedMovieIds(addon, canonical.id) + identities?.externalMovieSources(addon.baseUrl, canonical.tmdbId, canonical.imdbId).orEmpty()).distinct()
                if (known.isNotEmpty()) {
                    known.forEach { accepted.add(it); onResolved?.invoke(it) }
                    return accepted.toList()
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
                return ExternalAddonTitleMatcher.choose(titles.toList(), if (parent || type == "series") null else expectedYear, listOf(value),
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
                val sourceVideos = native.videos.orEmpty().distinctBy { Triple(it.id, it.season, it.episode) }
                val nativeSeasonVideos = sourceVideos.filter { it.season == nativeSeason }
                val airedNumbers = completeSeason.filter { it.available != false && isEpisodeReleaseAired(it.released) != false }
                    .mapNotNull { it.episode }.toSet()
                val canonicalLayout = canonical.videos.filter { it.season != null }.groupBy { it.season!! }
                    .mapValues { (_, vs) -> vs.mapNotNull { it.episode }.toSet() }
                val nativeLayout = sourceVideos.filter { it.season != null }.groupBy { it.season!! }
                    .mapValues { (_, vs) -> vs.mapNotNull { it.episode } }
                val strictMapping = ExternalEpisodeNumbering.episodeMap(canonicalLayout, nativeLayout,
                    season, nativeSeason, airedNumbers, info.arabic, info.turkish)
                val strictVideo = nativeSeasonVideos.singleOrNull { it.episode == strictMapping?.get(episode) }
                val needsRelaxation = strictMapping == null || strictVideo == null ||
                    !compatible(expected, strictVideo, info.arabic, strictMapping.size, info.turkish) ||
                    (!partParent && ExternalAddonTitleMatcher.choose(titles.toList(), expectedYear, listOf(value),
                        info.imdbId, info.tmdbId) !is ExternalAddonTitleMatcher.Decision.Matched)
                val translatedProof = matchCache?.translated(addon.baseUrl, native.id) == true ||
                    (needsRelaxation && translatedEdition(addon, native.toDomain("Episode")))
                val relaxed = info.arabic || translatedProof
                if (!relaxed && !partParent && ExternalAddonTitleMatcher.choose(titles.toList(), expectedYear,
                    listOf(value), info.imdbId, info.tmdbId) !is ExternalAddonTitleMatcher.Decision.Matched) return null
                val mapping = (if (relaxed && !partParent) sharedOriginalMap(info.tmdbId,
                    canonical.videos.filter { it.season != null }.groupBy { it.season!! }.mapValues { (_, vs) -> vs.mapNotNull { it.episode }.toSet() },
                    sourceVideos.filter { it.season != null }.groupBy { it.season!! }.mapValues { (_, vs) -> vs.mapNotNull { it.episode } }, season, nativeSeason) else null)
                    ?: ExternalEpisodeNumbering.episodeMap(
                    canonical.videos.filter { it.season != null }.groupBy { it.season!! }
                        .mapValues { (_, videos) -> videos.mapNotNull { it.episode }.toSet() },
                    sourceVideos.filter { it.season != null }.groupBy { it.season!! }
                        .mapValues { (_, videos) -> videos.mapNotNull { it.episode } },
                    season, nativeSeason, airedNumbers, info.arabic, info.turkish)
                    ?: if (!partParent) verifiedSiblingMap(info.tmdbId,
                        canonical.videos.filter { it.season != null }.groupBy { it.season!! }.mapValues { (_, vs) -> vs.mapNotNull { it.episode }.toSet() },
                        sourceVideos.filter { it.season != null }.groupBy { it.season!! }.mapValues { (_, vs) -> vs.mapNotNull { it.episode } },
                        season, nativeSeason, info.turkish, info.turkish && translatedEdition(addon, native.toDomain("Episode"))) else null
                if (BuildConfig.DEBUG) android.util.Log.d("ExternalAddonBridge",
                    "Episode mapping turkish=${info.turkish} season=$season episode=$episode canonicalCount=${completeSeason.size} nativeCount=${nativeSeasonVideos.size} mapped=${mapping?.size ?: 0}")
                val episodeMap = mapping ?: run {
                    val publicId = info.imdbId ?: info.tmdbId?.let { "tmdb:tv:$it" }
                    if (info.turkish && !partParent && publicId != null && matchCache?.work(addon.baseUrl, native.id) == null)
                        identities?.invalidateExternalSeason(addon.baseUrl, native.id, nativeSeason, publicId)
                    return null
                }
                val expectedSeason = completeSeason.filter { it.episode in episodeMap }
                val video = nativeSeasonVideos.singleOrNull { it.episode == episodeMap[episode] } ?: return null
                if (!compatibleEpisode(expected, video, relaxed, expectedSeason.size, info.turkish, info.tmdbId, season)) return null
                val canonicalId = (if (partParent) canonical.id.takeIf { builtinSeriesPattern.matches(it) } else null)
                    ?: info.tmdbId?.takeIf { partParent }?.let { "tmdb:tv:$it" }
                    ?: info.imdbId?.takeIf { imdbIdentityPattern.matches(it) }
                    ?: info.tmdbId?.let { "tmdb:tv:$it" }
                    ?: canonical.id.takeIf { builtinSeriesPattern.matches(it) }
                if (canonicalId != null) identities?.learnExternalSeason(addon.baseUrl, native.id, canonicalId, nativeSeason, season,
                    expectedSeason.mapNotNull { item ->
                        val number = item.episode ?: return@mapNotNull null
                        val nativeNumber = episodeMap[number] ?: return@mapNotNull null
                        val source = nativeSeasonVideos.singleOrNull { it.episode == nativeNumber } ?: return@mapNotNull null
                        if (compatibleEpisode(item, source, relaxed, expectedSeason.size, info.turkish, info.tmdbId, season)) Triple(nativeNumber, source.id, number) else null
                    }, allowPartAlias = partParent, canonicalVideos = if (canonicalId == canonical.id)
                        expectedSeason.mapNotNull { item -> item.episode?.let { it to item.id } }.toMap() else emptyMap())
                info.tmdbId?.let { matchCache?.rememberWork(addon.baseUrl, native.id, it, info.imdbId, translatedProof) }
                if (video.id.isBlank()) return null
                val label = ExternalAddonTitleMatcher.normalize(native.name + " " + video.title.orEmpty())
                val translated = label.contains("مترجم") || translatedTurkishPrefixPattern.containsMatchIn(label)
                return video.id to translated
            }
            val rows = mutableListOf<MetaPreviewDto>()
            val seen = mutableSetOf<String>()
            for (title in titles.take(4)) for (catalog in catalogs(addon, type)) {
                val key = hash(listOf(addon.baseUrl, addon.version, catalog.id, type, title).joinToString("\u0000"))
                val cached = synchronized(searches) { searches[key]?.takeIf { System.nanoTime() - it.created < 300_000_000_000L } }
                val found = if (cached != null) cached.rows else {
                    val response = try {
                        withTimeoutOrNull(10_000) {
                            api.getCatalog(url(addon, "catalog/${encode(type)}/${encode(catalog.id)}/search=${encode(title)}.json"))
                        } ?: continue
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
                for (value in candidates) {
                    if (!seen.add(value.originalId)) continue
                    val resolved = resolveCandidate(value) ?: continue
                    if (accepted.add(resolved.first)) onResolved?.invoke(resolved.first)
                    // A verified translated edition satisfies the requested Turkish episode.
                    // Do not keep searching foreign/dubbed editions after finding it.
                    if (onResolved == null && info.turkish && resolved.second) return accepted.toList()
                }
            }
            return if (type == "movie") {
                val match = ExternalAddonTitleMatcher.choose(titles.toList(), expectedYear,
                    rows.mapNotNull(::candidate).distinct(), info.imdbId, info.tmdbId)
                    as? ExternalAddonTitleMatcher.Decision.Matched
                if (match == null) emptyList() else {
                    if (com.nuvio.tv.data.repository.privateAddonId(match.candidate.originalId)) identities?.learnExternalMovie(addon.baseUrl, match.candidate.originalId, info.tmdbId, info.imdbId)
                    if (accepted.add(match.candidate.originalId)) onResolved?.invoke(match.candidate.originalId)
                    accepted.toList()
                }
            } else accepted.toList()
        }
        // Interactive discovery publishes every ready edition and is cancelled by its caller.
        // Bound individual requests, rather than truncating the entire result set.
        return if (onResolved != null) discover()
            else withTimeoutOrNull(25_000) { discover() } ?: accepted.toList()
    }

    internal fun acceptsLayout(originalLanguage: String?, expected: Set<Int>, actual: Set<Int>, aired: Set<Int> = expected) =
        ExternalEpisodeNumbering.acceptsLayout(originalLanguage == "ar", expected, actual, aired)

    internal fun compatible(expected: Video, actual: VideoDto, arabic: Boolean, count: Int, turkish: Boolean = false): Boolean {
        if (actual.id.isBlank()) return false
        if (arabic || turkish) return true
        val label = (actual.title ?: actual.name.orEmpty()).replace(
            editionEpisodeLabelPattern, "\$1")
        val name = ExternalAddonTitleMatcher.normalize(label)
        val coordinates = numberedEpisodePattern.findAll(name).toList()
        if (coordinates.isNotEmpty() && (coordinates.size != 1 ||
                coordinates.single().groupValues[1].toIntOrNull() != actual.season ||
                coordinates.single().groupValues[2].toIntOrNull() != actual.episode)) return false
        val episodeName = coordinates.singleOrNull()?.let { name.substring(it.range.last + 1).trim() }
            ?.takeIf(String::isNotBlank) ?: name
        val expectedName = ExternalAddonTitleMatcher.normalize(expected.title)
        val generic = episodeOnlyTitlePattern
        val numbered = coordinateOnlyTitlePattern
        if (expectedName.isBlank() || (episodeName != expectedName && name != expectedName &&
                !((generic.matches(episodeName) || numbered.matches(name)) && count >= 2))) return false
        return expected.released == null || actual.released == null || expected.released.take(10) == actual.released.take(10)
    }

    /** Localized episode names are aliases, not different cuts. Only consult the English
     * alias after the work, season layout and native episode coordinate were validated. */
    private suspend fun compatibleEpisode(expected: Video, actual: VideoDto, relaxed: Boolean, count: Int,
        turkish: Boolean, tmdbId: String?, season: Int): Boolean {
        if (compatible(expected, actual, relaxed, count, turkish)) return true
        val id = tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return false
        val api = tmdb ?: return false
        val key = id to season
        val cached = synchronized(englishEpisodeNames) { englishEpisodeNames[key] }
        val names = if (cached != null && System.nanoTime() - cached.created <
            (if (cached.names.isEmpty()) 30_000_000_000L else 300_000_000_000L)) cached.names else {
            val episodes = try {
                withTimeoutOrNull(5_000) {
                    api.getTvSeasonDetails(id, season, BuildConfig.TMDB_API_KEY, "en-US")
                        .takeIf { it.isSuccessful }?.body()?.episodes
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val numbers = episodes.orEmpty().mapNotNull { it.episodeNumber }
            val found = if (numbers.size == episodes?.size && numbers.all { it > 0 } && numbers.distinct().size == numbers.size)
                episodes.orEmpty().mapNotNull { e -> e.name?.takeIf(String::isNotBlank)?.let { e.episodeNumber!! to it } }.toMap()
            else emptyMap()
            synchronized(englishEpisodeNames) { englishEpisodeNames[key] = EpisodeNames(System.nanoTime(), found) }
            found
        }
        val alternate = names[expected.episode] ?: return false
        return compatible(expected.copy(title = alternate), actual, relaxed, count, turkish)
    }

    /** Learn the inverse direction when a user opens an Xtream catalog title directly. */
    suspend fun enrichNative(addon: Addon, native: Meta): Meta = withContext(Dispatchers.IO) {
        identities?.migrateLegacyCache()
        val publicEvidence = native.tmdbId?.toIntOrNull()?.let { it > 0 } == true || native.imdbId?.matches(imdbIdentityPattern) == true
        if (!com.nuvio.tv.data.repository.privateAddonId(native.id) ||
            (!(isXtream(addon) && native.id.startsWith("xt_")) && !publicEvidence))
            return@withContext identities?.withWatchIdentity(native, addon.baseUrl) ?: native
        val key = hash(addon.baseUrl + "\u0000" + addon.version + "\u0000" + native.id)
        val cached = synchronized(nativeEnrichments) { nativeEnrichments[key] }
        if (cached != null && cached.source == native && cached.layoutRevision == (identities?.revision ?: 0L) && System.nanoTime() - cached.created < 300_000_000_000L)
            return@withContext cached.result
        val stored = if (native.apiType == "series") matchCache?.work(addon.baseUrl, native.id) else null
        val savedId = stored?.get("tmdb")?.asString
        val verified = stored?.getAsJsonArray("verifiedVideos")?.map { it.asString }?.toSet().orEmpty()
        if (savedId != null && verified.isNotEmpty() && identities?.hasExternalSeries(addon.baseUrl, native.id) == true) {
            val route = identities.withWatchIdentity(native.copy(tmdbId = savedId, imdbId = stored.get("imdb")?.asString,
                sharedProgressCompatible = true, verifiedPlaybackVideoIds = verified), addon.baseUrl)
            val unchanged = stored.get("nativeLayout")?.asString == nativeFingerprint(native) &&
                stored.get("providerLayout")?.asString == providerFingerprint(savedId)
            if (!unchanged && refreshes.size < 16) {
                val generation = cacheGeneration
                val request = refreshes.sharedRequest(key) {
                    refreshScope.async(start = CoroutineStart.LAZY) {
                        withTimeoutOrNull(25_000) {
                            refreshLimit.withPermit {
                                val updated = enrichNativeSeries(addon, native)
                                currentCoroutineContext().ensureActive()
                                if (generation == cacheGeneration && updated.sharedProgressCompatible) {
                                    val result = identities.withWatchIdentity(updated, addon.baseUrl)
                                    synchronized(nativeEnrichments) { nativeEnrichments[key] = NativeEnrichment(
                                        System.nanoTime(), native, result, identities.revision) }
                                    result
                                } else route
                            }
                        } ?: route
                    }
                }
                request.invokeOnCompletion { refreshes.remove(key, request) }
            }
            return@withContext route
        }
        val enriched = if (native.apiType == "movie") enrichNativeMovie(addon, native) else enrichNativeSeries(addon, native)
        val result = if (enriched.videos.isNotEmpty()) identities?.withWatchIdentity(enriched, addon.baseUrl) ?: enriched else enriched
        if (result.sharedProgressCompatible) synchronized(nativeEnrichments) {
            nativeEnrichments[key] = NativeEnrichment(System.nanoTime(), native, result, (identities?.revision ?: 0L))
        }
        result
    }

    private fun nativeFingerprint(native: Meta) = hash(native.videos.map { listOf(it.id, it.season, it.episode) }.toString())
    private data class ProviderLayout(val revision: Long, val digest: String)
    private val providerLayouts = object : LinkedHashMap<String, ProviderLayout>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProviderLayout>?) = size > 128
    }
    private fun providerFingerprint(id: String): String {
        val revision = identities?.revision ?: 0L
        synchronized(providerLayouts) { providerLayouts[id]?.takeIf { it.revision == revision }?.let { return it.digest } }
        // Preserve the exact legacy row ordering/string representation in durable cache evidence.
        val digest = hash(identities?.providerRows("tmdb:tv:$id").orEmpty().toString())
        synchronized(providerLayouts) { providerLayouts[id] = ProviderLayout(revision, digest) }
        return digest
    }

    private suspend fun enrichNativeMovie(addon: Addon, native: Meta): Meta {
        val api = tmdb ?: return native
        if (identities == null || !com.nuvio.tv.data.repository.privateAddonId(native.id) || BuildConfig.TMDB_API_KEY.isBlank()) return native
        return withTimeoutOrNull(12_000) {
            try {
                val sourceYear = year(native.releaseInfo ?: native.released)
                val match = native.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    com.nuvio.android.cinemana.CinemanaTmdbMatcher.Match(it, native.name, null, null, null, 200.0)
                } ?: com.nuvio.android.cinemana.CinemanaTmdbMatcher(BuildConfig.TMDB_API_KEY, fetch = tmdbFetch::fetch)
                    .match(ExternalAddonTitleMatcher.titleVariants(native.name).toList(), sourceYear, false, native.imdbId)
                    ?: return@withTimeoutOrNull native
                val response = api.getMovieDetails(match.tmdbId, BuildConfig.TMDB_API_KEY, "ar-SA")
                val details = response.body()?.takeIf { response.isSuccessful && it.id == match.tmdbId } ?: return@withTimeoutOrNull native
                val officialYear = year(details.releaseDate)
                if (details.originalLanguage != "ar" && sourceYear != null && officialYear != null && kotlin.math.abs(sourceYear - officialYear) > 1) return@withTimeoutOrNull native
                val imdb = api.getMovieExternalIds(match.tmdbId, BuildConfig.TMDB_API_KEY).body()?.imdbId
                    ?.takeIf { imdbIdentityPattern.matches(it) }
                if (conflicts(native.imdbId, imdb) || conflicts(native.tmdbId, match.tmdbId.toString())) return@withTimeoutOrNull native
                identities.learnExternalMovie(addon.baseUrl, native.id, match.tmdbId.toString(), imdb ?: native.imdbId)
                native.copy(tmdbId = match.tmdbId.toString(), imdbId = imdb ?: native.imdbId, sharedProgressCompatible = true)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { native }
        } ?: native
    }

    private suspend fun enrichNativeSeries(addon: Addon, native: Meta): Meta {
        val api = tmdb ?: return native
        if (identities == null || native.apiType != "series" ||
            !com.nuvio.tv.data.repository.privateAddonId(native.id) || BuildConfig.TMDB_API_KEY.isBlank()) return native
        return withTimeoutOrNull(25_000) {
            try {
                val labels = ExternalAddonTitleMatcher.searchTitles(native.name)
                val matcher = com.nuvio.android.cinemana.CinemanaTmdbMatcher(BuildConfig.TMDB_API_KEY, fetch = tmdbFetch::fetch)
                val stored = matchCache?.work(addon.baseUrl, native.id)
                val nativeLayout = nativeFingerprint(native)
                val savedId = stored?.get("tmdb")?.asString
                if (savedId != null && identities.hasExternalSeries(addon.baseUrl, native.id) && stored.get("nativeLayout")?.asString == nativeLayout &&
                    stored.get("providerLayout")?.asString == providerFingerprint(savedId)) {
                    val verified = stored.getAsJsonArray("verifiedVideos")?.map { it.asString }?.toSet().orEmpty()
                    if (verified.isNotEmpty()) return@withTimeoutOrNull native.copy(tmdbId = savedId,
                        imdbId = stored.get("imdb")?.asString, sharedProgressCompatible = true,
                        verifiedPlaybackVideoIds = verified)
                }
                val matchedId = stored?.get("tmdb")?.asString ?: native.tmdbId
                val match = matchedId?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    com.nuvio.android.cinemana.CinemanaTmdbMatcher.Match(it, native.name, null, null, null, 200.0)
                } ?: matcher.match(labels, null, true, native.imdbId)
                    ?: matcher.match(labels, year(native.releaseInfo ?: native.released), true, native.imdbId)
                    ?: return@withTimeoutOrNull native
                val details = api.getTvDetails(match.tmdbId, BuildConfig.TMDB_API_KEY, "ar-SA")
                    .body() ?: return@withTimeoutOrNull native
                if (details.id != match.tmdbId) return@withTimeoutOrNull native
                val arabic = details.originalLanguage == "ar"
                val turkish = details.originalLanguage == "tr"
                val translated = stored?.get("translated")?.asBoolean == true || translatedEdition(addon, native)
                val relaxed = arabic || translated
                val sourceYear = year(native.releaseInfo ?: native.released)
                if (!relaxed && !turkish && sourceYear != null && match.year != null && kotlin.math.abs(sourceYear - match.year) > 1)
                    return@withTimeoutOrNull native
                val imdb = api.getTvExternalIds(match.tmdbId, BuildConfig.TMDB_API_KEY).body()?.imdbId
                    ?.takeIf { imdbIdentityPattern.matches(it) }
                if (conflicts(native.imdbId, imdb) || conflicts(native.tmdbId, match.tmdbId.toString())) return@withTimeoutOrNull native
                currentCoroutineContext().ensureActive()
                matchCache?.rememberWork(addon.baseUrl, native.id, match.tmdbId.toString(), imdb, translated)
                val numberedPart = labels.any { splitPart(it) != null } &&
                    !canUseSeriesImdb(match.tmdbId.toString(), imdb, labels.toSet())
                val canonical = if (numberedPart) "tmdb:tv:${match.tmdbId}" else imdb ?: "tmdb:tv:${match.tmdbId}"
                if (!numberedPart) identities.learnExternalCanonical(match.tmdbId.toString(), imdb)
                val verified = mutableSetOf<String>()
                val sourceSeasons = native.videos.distinctBy { Triple(it.id, it.season, it.episode) }
                    .filter { (it.season ?: 0) > 0 }.groupBy { it.season!! }
                val canonicalSeasons = mutableMapOf<Int, Set<Int>>()
                for ((season, videos) in sourceSeasons.entries.sortedBy { it.key }.take(30)) {
                    val complete = api.getTvSeasonDetails(match.tmdbId, season, BuildConfig.TMDB_API_KEY, "ar-SA")
                        .body()?.episodes ?: continue
                    val completeNumbers = complete.mapNotNull { it.episodeNumber }.toSet()
                    canonicalSeasons[season] = completeNumbers
                    val aired = complete.filter { isEpisodeReleaseAired(it.airDate) != false }
                    val airedNumbers = aired.mapNotNull { it.episodeNumber }.toSet()
                    val episodeMap = (if (relaxed) sharedOriginalMap(match.tmdbId.toString(), canonicalSeasons,
                        sourceSeasons.mapValues { (_, items) -> items.mapNotNull { it.episode } }, season, season) else null)
                        ?: ExternalEpisodeNumbering.episodeMap(canonicalSeasons,
                        sourceSeasons.mapValues { (_, items) -> items.mapNotNull { it.episode } },
                        season, season, airedNumbers, arabic, turkish)
                        ?: if (!numberedPart) verifiedSiblingMap(match.tmdbId.toString(), canonicalSeasons,
                            sourceSeasons.mapValues { (_, items) -> items.mapNotNull { it.episode } }, season, season, turkish, turkish && translatedEdition(addon, native)) else null
                    val acceptedMap = episodeMap ?: run {
                            if (turkish && stored == null) identities.invalidateExternalSeason(addon.baseUrl, native.id, season, canonical)
                            null
                        } ?: continue
                    val expectedNumbers = acceptedMap.keys
                    val verifiedSeason = mutableListOf<Triple<Int, String, Int>>()
                    for ((number, nativeNumber) in acceptedMap) {
                        val item = complete.singleOrNull { it.episodeNumber == number }
                        if (item == null && !relaxed) continue
                        val video = videos.singleOrNull { it.episode == nativeNumber } ?: continue
                        val canonicalVideo = video.copy(title = item?.name.orEmpty(), released = item?.airDate)
                        val sourceVideo = VideoDto(video.id, title = video.title, released = video.released, season = season, episode = video.episode)
                    if (!compatibleEpisode(canonicalVideo, sourceVideo, relaxed, expectedNumbers.size, turkish,
                        match.tmdbId.toString(), season)) continue
                        verifiedSeason += Triple(nativeNumber, video.id, number)
                        verified += video.id
                    }
                    currentCoroutineContext().ensureActive()
                    identities.learnExternalSeason(addon.baseUrl, native.id, canonical, season, season, verifiedSeason)
                }
                if (verified.isNotEmpty()) matchCache?.rememberLayout(addon.baseUrl, native.id, nativeLayout,
                    providerFingerprint(match.tmdbId.toString()), verified)
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
        if (tmdb != null && id == null) id = tmdbIdentityPattern.matchEntire(meta.id)?.groupValues?.get(1)
        var imdb = meta.imdbId
        if (tmdb != null && !BuildConfig.TMDB_API_KEY.isBlank() && id == null) try {
            val external = imdb ?: meta.id.takeIf { imdbIdentityPattern.matches(it) }
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
        val match = numberedPartPattern.matchEntire(title.trim()) ?: return null
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
    private fun year(value: String?) = value?.let { releaseYearPattern.find(it)?.value?.toIntOrNull() }
    private fun hash(value: String) = com.nuvio.tv.core.util.identityDigest(value)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun url(addon: Addon, path: String): String {
        val base = addon.baseUrl.trimEnd('/')
        val query = base.indexOf('?')
        return if (query < 0) "$base/$path" else "${base.substring(0, query).trimEnd('/')}/$path${base.substring(query)}"
    }
}
