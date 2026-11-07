package com.nuvio.tv.data.iptv

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.util.isEpisodeReleaseAired
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
    private data class Info(val titles: Set<String>, val tmdbId: String?, val imdbId: String?, val arabic: Boolean)
    fun isXtream(addon: Addon) = addon.resources.any { resource ->
        resource.idPrefixes.orEmpty().any { it.startsWith("xt_") }
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

    suspend fun resolveAll(addon: Addon, type: String, canonical: Meta, season: Int?, episode: Int?): List<String> =
        withTimeoutOrNull(25_000) {
            val info = info(canonical, type)
            val part = if (type == "series" && season == 1) splitPart(canonical.name) else null
            fun isPartParent(candidate: ExternalAddonTitleMatcher.Candidate) = part != null &&
                ExternalAddonTitleMatcher.titleVariants(candidate.name).intersect(ExternalAddonTitleMatcher.titleVariants(part.first)).isNotEmpty() &&
                ExternalAddonTitleMatcher.titleVariants(candidate.name).intersect(ExternalAddonTitleMatcher.titleVariants(canonical.name)).isEmpty()
            val titles = info.titles + listOfNotNull(part?.first)
            val rows = mutableListOf<MetaPreviewDto>()
            for (title in titles.take(4)) for (catalog in catalogs(addon, type)) {
                val key = hash(listOf(addon.baseUrl, addon.version, catalog.id, type, title).joinToString("\u0000"))
                val cached = synchronized(searches) { searches[key]?.takeIf { System.nanoTime() - it.created < 300_000_000_000L } }
                if (cached != null) { rows += cached.rows; continue }
                val response = api.getCatalog(url(addon, "catalog/${encode(type)}/${encode(catalog.id)}/search=${encode(title)}.json"))
                if (!response.isSuccessful) return@withTimeoutOrNull emptyList()
                val found = response.body()?.metas?.filterNotNull() ?: return@withTimeoutOrNull emptyList()
                synchronized(searches) { searches[key] = SearchEntry(System.nanoTime(), found) }
                rows += found
            }
            val candidates = rows.filter { it.type == type }.mapNotNull { row ->
                val id = row.id ?: return@mapNotNull null
                val name = row.name ?: return@mapNotNull null
                ExternalAddonTitleMatcher.Candidate(id, name, year(row.releaseInfo ?: row.released), row.imdbId, row.tmdbId)
            }.distinct()
            val expectedYear = if (info.arabic) null else year(canonical.releaseInfo ?: canonical.released)
            if (type == "movie") {
                val match = ExternalAddonTitleMatcher.choose(titles.toList(), expectedYear, candidates, info.imdbId, info.tmdbId)
                    as? ExternalAddonTitleMatcher.Decision.Matched ?: return@withTimeoutOrNull emptyList()
                return@withTimeoutOrNull listOf(match.candidate.originalId)
            }
            if (type != "series" || season == null || episode == null) return@withTimeoutOrNull emptyList()
            val expected = canonical.videos.singleOrNull { it.season == season && it.episode == episode }
                ?: return@withTimeoutOrNull emptyList()
            val matches = candidates.filter {
                val parent = isPartParent(it)
                ExternalAddonTitleMatcher.choose(titles.toList(), if (parent) null else expectedYear, listOf(it),
                    if (parent) null else info.imdbId, if (parent) null else info.tmdbId) is
                    ExternalAddonTitleMatcher.Decision.Matched
            }
            matches.take(12).mapNotNull { candidate ->
                val response = api.getMeta(url(addon, "meta/${encode(type)}/${encode(candidate.originalId)}.json"))
                if (!response.isSuccessful) return@mapNotNull null
                val native = response.body()?.meta ?: return@mapNotNull null
                if (native.id != candidate.originalId || native.type != type) return@mapNotNull null
                val partParent = isPartParent(candidate)
                if (!partParent && (conflicts(info.imdbId, native.imdbId) || conflicts(info.tmdbId, native.tmdbId))) return@mapNotNull null
                val nativeSeason = if (partParent) part!!.second else season
                val completeSeason = canonical.videos.filter { it.season == season }
                val nativeSeasonVideos = native.videos.orEmpty().filter { it.season == nativeSeason }
                val completeNumbers = completeSeason.mapNotNull { it.episode }.toSet()
                val nativeNumbers = nativeSeasonVideos.mapNotNull { it.episode }.toSet()
                val airedSeason = completeSeason.filter { it.available != false && isEpisodeReleaseAired(it.released) != false }
                val airedNumbers = airedSeason.mapNotNull { it.episode }.toSet()
                val expectedSeason = if (!info.arabic && completeNumbers != nativeNumbers && airedNumbers == nativeNumbers) airedSeason else completeSeason
                val expectedNumbers = expectedSeason.mapNotNull { it.episode }.toSet()
                // The owner permits ar works to bypass edition/count checks. Identity and
                // an existing, unique native episode are still required. Part mapping is separate.
                if (!acceptsLayout(if (info.arabic) "ar" else null, completeNumbers, nativeNumbers, airedNumbers)) return@mapNotNull null
                val video = nativeSeasonVideos.singleOrNull { it.episode == episode } ?: return@mapNotNull null
                if (!compatible(expected, video, info.arabic, expectedNumbers.size)) return@mapNotNull null
                val canonicalId = info.imdbId?.takeIf { Regex("tt[0-9]+").matches(it) }
                    ?: info.tmdbId?.let { "tmdb:tv:$it" }
                    ?: canonical.id.takeIf { Regex("cinemana-series:[0-9]+|cee:[0-9]+").matches(it) }
                if (canonicalId != null) identities?.learnExternalSeason(addon.baseUrl, native.id, canonicalId, nativeSeason, season,
                    expectedSeason.mapNotNull { item ->
                        val number = item.episode ?: return@mapNotNull null
                        val source = nativeSeasonVideos.singleOrNull { it.episode == number } ?: return@mapNotNull null
                        if (compatible(item, source, info.arabic, expectedNumbers.size)) Triple(number, source.id, number) else null
                    })
                video.id.takeIf { it.isNotBlank() }
            }.distinct()
        } ?: emptyList()

    internal fun acceptsLayout(originalLanguage: String?, expected: Set<Int>, actual: Set<Int>, aired: Set<Int> = expected) =
        originalLanguage == "ar" || expected == actual || (aired.isNotEmpty() && aired == actual && expected.containsAll(aired))

    internal fun compatible(expected: Video, actual: VideoDto, arabic: Boolean, count: Int): Boolean {
        if (actual.id.isBlank()) return false
        if (arabic) return true
        val name = ExternalAddonTitleMatcher.normalize(actual.title ?: actual.name.orEmpty())
        val expectedName = ExternalAddonTitleMatcher.normalize(expected.title)
        val generic = Regex("(?:^|.* )(?:episode|ep|الحلقه) 0*[0-9]+$")
        val numbered = Regex("(?:^|.* )s[0-9]+ ?e[0-9]+$")
        if (expectedName.isBlank() || (name != expectedName && !((generic.matches(name) || numbered.matches(name)) && count >= 2))) return false
        return expected.released == null || actual.released == null || expected.released.take(10) == actual.released.take(10)
    }

    /** Learn the inverse direction when a user opens an Xtream catalog title directly. */
    suspend fun enrichNative(addon: Addon, native: Meta): Meta {
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
                val sourceYear = year(native.releaseInfo ?: native.released)
                if (!arabic && sourceYear != null && match.year != null && kotlin.math.abs(sourceYear - match.year) > 1)
                    return@withTimeoutOrNull native
                val imdb = api.getTvExternalIds(match.tmdbId, BuildConfig.TMDB_API_KEY).body()?.imdbId
                    ?.takeIf { Regex("tt[0-9]+").matches(it) }
                if (conflicts(native.imdbId, imdb) || conflicts(native.tmdbId, match.tmdbId.toString())) return@withTimeoutOrNull native
                val canonical = imdb ?: "tmdb:tv:${match.tmdbId}"
                identities.learnExternalCanonical(match.tmdbId.toString(), imdb)
                val verified = mutableSetOf<String>()
                for ((season, videos) in native.videos.filter { (it.season ?: 0) > 0 }.groupBy { it.season!! }.entries.take(30)) {
                    val complete = api.getTvSeasonDetails(match.tmdbId, season, BuildConfig.TMDB_API_KEY, "ar-SA")
                        .body()?.episodes ?: continue
                    val sourceNumbers = videos.mapNotNull { it.episode }.toSet()
                    val completeNumbers = complete.mapNotNull { it.episodeNumber }.toSet()
                    val aired = complete.filter { isEpisodeReleaseAired(it.airDate) != false }
                    val airedNumbers = aired.mapNotNull { it.episodeNumber }.toSet()
                    val expected = if (!arabic && completeNumbers != sourceNumbers && airedNumbers == sourceNumbers) aired else complete
                    val expectedNumbers = expected.mapNotNull { it.episodeNumber }.toSet()
                    if (!acceptsLayout(details.originalLanguage, completeNumbers, sourceNumbers, airedNumbers)) continue
                    val verifiedSeason = mutableListOf<Triple<Int, String, Int>>()
                    for (item in expected) {
                        val number = item.episodeNumber ?: continue
                        val video = videos.singleOrNull { it.episode == number } ?: continue
                        val canonicalVideo = video.copy(title = item.name.orEmpty(), released = item.airDate)
                        val sourceVideo = VideoDto(video.id, title = video.title, released = video.released, season = season, episode = number)
                        if (!compatible(canonicalVideo, sourceVideo, arabic, expectedNumbers.size)) continue
                        verifiedSeason += Triple(number, video.id, number)
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
        if (tmdb != null && !BuildConfig.TMDB_API_KEY.isBlank() && id?.toIntOrNull() != null) try {
            val details = if (type == "series") tmdb.getTvDetails(id.toInt(), BuildConfig.TMDB_API_KEY, "ar-SA")
                else tmdb.getMovieDetails(id.toInt(), BuildConfig.TMDB_API_KEY, "ar-SA")
            details.body()?.takeIf { details.isSuccessful && it.id.toString() == id }?.let {
                listOfNotNull(it.name, it.title, it.originalName, it.originalTitle).filter(String::isNotBlank).forEach(titles::add)
                arabic = it.originalLanguage == "ar"
            }
            if (type == "series" && imdb == null) imdb = tmdb.getTvExternalIds(id.toInt(), BuildConfig.TMDB_API_KEY).body()?.imdbId
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* optional aliases */ }
        if (type == "series" && id != null) identities?.learnExternalCanonical(id, imdb)
        return Info(titles, id, imdb, arabic)
    }
    internal fun splitPart(title: String): Pair<String, Int>? {
        val match = Regex("^(.+?)\\s+([2-9]|[1-9][0-9])$").matchEntire(title.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2].toInt()
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
