package com.nuvio.tv.data.cee

import kotlinx.coroutines.async

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.*
import okhttp3.OkHttpClient
import retrofit2.Response
import java.net.URI

/**
 * Routes built-in Cee catalog requests in-process.
 * All other requests remain handled by the existing AddonApi.
 */
class CeeAddonApi(
    private val delegate: AddonApi,
    http: OkHttpClient,
    private val tmdbCredentials: () -> com.nuvio.android.cinemana.TmdbCredentials,
    private val streamCache: com.nuvio.tv.data.builtincache.BuiltinStreamCache? = null,
    detailsStore: com.nuvio.tv.data.builtincache.BuiltinDetailsStore? = null,

) : AddonApi {

    companion object {
        const val BASE_URL = CeeResourceAdapter.BASE_URL
    }


    private val ceeDiscoverGenres = listOf("Action", "Adventure", "Animation", "Arabic dubbed", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Game-Show", "History", "Horror", "Iraqi", "Music", "Musical", "Mystery", "Program", "Reality-TV", "Religious", "Romance", "Sci-Fi", "Short", "Slice of Life", "Sport", "Talk-Show", "Thriller", "uncategorized")

    private val ceeDiscoverYears = listOf("2027", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011", "2010", "2009", "2008", "2007", "2006", "2005", "2004", "2003", "2002", "2001", "2000", "1999", "1998", "1997", "1996", "1995", "1994", "1993", "1992", "1991", "1990", "1989", "1988", "1987", "1986", "1985", "1984", "1983", "1982", "1981", "1980", "1979", "1978", "1977", "1976", "1975", "1974", "1973", "1972", "1971", "1970", "1969", "1968", "1967", "1966", "1965", "1964", "1963", "1962", "1961", "1960", "1959", "1958", "1957", "1956", "1955", "1954", "1953", "1952", "1951", "1950")

    private val ceeDiscoverExtras: List<Any> = listOf(
        mapOf("name" to "skip"),
        mapOf("name" to "genre", "options" to ceeDiscoverGenres),
        mapOf("name" to "year", "options" to ceeDiscoverYears)
    )
    private val client = CeeClient(http)
    private val cinemanaClient = com.nuvio.tv.core.network.providerCinemanaClient(http)
    private val tmdbLookup = CeeTmdbLookup(http, tmdbCredentials)
    private val tmdbFetch = com.nuvio.tv.core.network.ProviderMetadataFetch(http)
    private val adapter = CeeResourceAdapter(client = client, tmdbCredentials = tmdbCredentials,
        tmdbFetch = tmdbFetch::fetch,
        detailsStore = detailsStore,
        matchEpisodeLayout = { id, episodes -> tmdbLookup.matchEpisodeLayout(id, episodes) },
        verifyEpisodes = { id, episodes, unchecked -> tmdbLookup.compatibleEpisodes(id, episodes, client, unchecked) })
    private val streamJson = com.squareup.moshi.Moshi.Builder().build().adapter(StreamResponseDto::class.java)

    private fun isCee(url: String): Boolean =
        runCatching {
            URI(url).host.equals("cee.nuvio.invalid", ignoreCase = true)
        }.getOrDefault(false)

    override suspend fun getManifest(
        manifestUrl: String,
    ): Response<AddonManifestDto> {
        if (!isCee(manifestUrl)) {
            return delegate.getManifest(manifestUrl)
        }

        // Propagate failures so AddonRepository retains the last valid cached manifest.
        val dynamicHomeCatalogs = adapter.homeCatalogs("ar").map { group ->
            CatalogDescriptorDto(
                type = group.type,
                id = group.id,
                name = "Cee • ${group.name}",
                pageSize = 12,
                showInHome = true,
                extraSupported = listOf("skip")
            )
        }
        return Response.success(
            AddonManifestDto(
                id = "org.nuvio.cee",
                name = "Cee",
                version = "0.2.0",
                description = "Built-in Cee catalog provider",
                types = listOf("movie", "series"),
                resources = listOf(
                    "catalog",
                    mapOf("name" to "meta", "types" to listOf("movie", "series"), "idPrefixes" to listOf("cee:")),
                    mapOf("name" to "stream", "types" to listOf("movie", "series"), "idPrefixes" to listOf("cee:", "tt", "imdb:", "tmdb:", "cinemana-series:")),
                    mapOf("name" to "subtitles", "types" to listOf("movie", "series"), "idPrefixes" to listOf("cee:", "tt", "imdb:", "tmdb:")),
                ),
                catalogs = dynamicHomeCatalogs + listOf(
                    CatalogDescriptorDto(
                        type = "movie", id = CeeResourceAdapter.SEARCH_MOVIES_CATALOG,
                        name = "Cee - Search Movies", showInHome = false,
                        pageSize = 24, extraSupported = listOf("search")
                    ),
                    CatalogDescriptorDto(
                        type = "series", id = CeeResourceAdapter.SEARCH_SERIES_CATALOG,
                        name = "Cee - Search Series", showInHome = false,
                        pageSize = 24, extraSupported = listOf("search")
                    ),
                    CatalogDescriptorDto(
                        type = "movie",
                        id = CeeResourceAdapter.MOVIES_CATALOG,
                        name = "Cee - Latest Movies", showInHome = false,
                        pageSize = 24,
                        extraSupported = listOf("skip")
                    ),
                    CatalogDescriptorDto(
                        type = "series",
                        id = CeeResourceAdapter.SERIES_CATALOG,
                        name = "Cee - Latest Series", showInHome = false,
                        pageSize = 24,
                        extraSupported = listOf("skip")
                    ),
                    CatalogDescriptorDto(
                        type = "movie",
                        id = CeeResourceAdapter.DISCOVER_MOVIES_CATALOG,
                        name = "Cee - Movies",
                        pageSize = 12,
                        showInHome = false,
                        extraSupported = listOf("skip", "genre", "year"), extra = ceeDiscoverExtras,
                    ),
                    CatalogDescriptorDto(
                        type = "series",
                        id = CeeResourceAdapter.DISCOVER_SERIES_CATALOG,
                        name = "Cee - Series",
                        pageSize = 12,
                        showInHome = false,
                        extraSupported = listOf("skip", "genre", "year"), extra = ceeDiscoverExtras
                    )
                )
            )
        )
    }

    override suspend fun getCatalog(
        catalogUrl: String,
    ): Response<CatalogResponseDto> {
        if (!isCee(catalogUrl)) {
            return delegate.getCatalog(catalogUrl)
        }

        val uri = URI(catalogUrl)
        val segments = uri.path.trim('/').split('/')

        require(segments.size >= 3 && segments[0] == "catalog") {
            "Invalid Cee catalog URL"
        }

        val catalogId = segments[2].removeSuffix(".json")

        val args = mutableMapOf<String, String>()
        val extraPath = segments.drop(3).joinToString("/")
            .removeSuffix(".json")

        for (part in extraPath.split("&", "/")) {
            val pair = part.split("=", limit = 2)
            if (pair.size == 2) {
                args[java.net.URLDecoder.decode(pair[0], "UTF-8")] =
                    java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
        }

        uri.rawQuery?.split("&")?.forEach { part ->
            val pair = part.split("=", limit = 2)
            if (pair.size == 2) {
                args[java.net.URLDecoder.decode(pair[0], "UTF-8")] =
                    java.net.URLDecoder.decode(pair[1], "UTF-8")
            }
        }

        if (catalogId == CeeResourceAdapter.SEARCH_MOVIES_CATALOG ||
            catalogId == CeeResourceAdapter.SEARCH_SERIES_CATALOG) {
            val term = args["search"]?.trim().orEmpty()
            val skip = args["skip"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            return Response.success(adapter.searchCatalog(
                keyword = term,
                series = catalogId == CeeResourceAdapter.SEARCH_SERIES_CATALOG,
                skip = skip
            ))
        }

        if (catalogId.startsWith("cee-home-")) {
            val skip = args["skip"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            return Response.success(
                adapter.homeCatalog(
                    catalogId = catalogId,
                    page = skip / 12
                )
            )
        }
        val discover = catalogId == CeeResourceAdapter.DISCOVER_MOVIES_CATALOG ||
            catalogId == CeeResourceAdapter.DISCOVER_SERIES_CATALOG

        val pageSize = if (discover) 12 else 24
        val skip = args["skip"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val currentYear = java.time.Year.now().value + 1
        val yearValue = args["year"]?.trim().orEmpty()
        val years = yearValue.split(",", "-")
            .mapNotNull { it.trim().toIntOrNull() }

        val startYear = years.firstOrNull() ?: 1900
        val endYear = years.getOrNull(1) ?: if (years.isNotEmpty()) startYear else currentYear

        val category = (args["genre"] ?: args["category"])
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return Response.success(
            adapter.catalog(
                catalogId = catalogId,
                page = skip / pageSize,
                startYear = startYear,
                endYear = endYear,
                category = category
            )
        )
    }

    override suspend fun getMeta(
        metaUrl: String,
    ): Response<MetaResponseDto> {
        if (!isCee(metaUrl)) return delegate.getMeta(metaUrl)
        val uri = URI(metaUrl)
        return Response.success(adapter.meta(
            ceeVideoId(metaUrl, "meta"), uri.path.trim('/').split('/')[1], uri.rawQuery == "artworkOnly=1"
        ))
    }

    override suspend fun getStreams(
        streamUrl: String,
    ): Response<StreamResponseDto> {
        if (!isCee(streamUrl)) return delegate.getStreams(streamUrl)
        val load: suspend () -> String = {
            val state = com.nuvio.android.cinemana.ProviderRequestState()
            val payload = kotlinx.coroutines.withContext(state) {

                val ids = resolveVideoIds(streamUrl, "stream")
                val allStreams = ids.flatMap { videoId ->
                    adapter.streams(videoId).streams.orEmpty().map { stream ->
                        if (ids.size > 1) {
                            stream.copy(title = "${stream.title.orEmpty()} | CEE #$videoId")
                        } else stream
                    }
                }
                val sortedStreams = allStreams.sortedByDescending { stream ->
                    Regex("""(\d{3,4})p?""", RegexOption.IGNORE_CASE)
                        .find(stream.title.orEmpty())
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                }
                streamJson.toJson(StreamResponseDto(streams = sortedStreams))
            }
            org.json.JSONObject(payload).put("_cacheComplete", state.complete.get()).toString()
        }
        val json = streamCache?.get(streamUrl, load) ?: load()
        return Response.success(requireNotNull(streamJson.fromJson(json)))
    }

    override suspend fun getSubtitles(
        subtitleUrl: String,
    ): Response<SubtitleResponseDto> {
        if (!isCee(subtitleUrl)) return delegate.getSubtitles(subtitleUrl)

        val ids = resolveVideoIds(subtitleUrl, "subtitles")
        val subtitles = ids.flatMap { id ->
            adapter.subtitles(id).subtitles.orEmpty()
        }.distinctBy { it.url }
        return Response.success(SubtitleResponseDto(subtitles = subtitles))
    }

    private suspend fun resolveVideoIds(url: String, resource: String): List<String> {
        val parts = URI(url).path.trim('/').split('/')
        require(parts.size == 3 && parts[0] == resource)
        val series = parts[1] == "series"
        require(series || parts[1] == "movie")
        val raw = java.net.URLDecoder.decode(parts[2].removeSuffix(".json").replace("+", "%2B"), "UTF-8")
        val request = CeeVideoRequest.parse(raw, series) ?: return emptyList()
        var officialSeasonCounts = emptyMap<Int, Int>()
        var originalLanguage: String? = null
        val root = if (request.nativeId != null) request.nativeId else {
            val externalId = request.externalId ?: return emptyList()
            val query = if (externalId.startsWith("cinemana-series:")) {
                val id = externalId.removePrefix("cinemana-series:")
                val info = cinemanaClient.info(id)
                CeeResolver.Query(
                    imdbId = Regex("tt[0-9]+").find(info.optString("imdbUrlRef"))?.value,
                    arabicTitle = info.optString("ar_title").takeIf { it.isNotBlank() },
                    englishTitle = info.optString("en_title").takeIf { it.isNotBlank() },
                    year = info.optString("year").take(4).toIntOrNull(),
                    type = "series"
                )
            } else tmdbLookup.query(externalId, series)
            if (query == null) {
                android.util.Log.i("CeeResolver", "No metadata query for $externalId series=$series")
                return emptyList()
            }
            officialSeasonCounts = query.seasonCounts
            originalLanguage = query.originalLanguage
            val resolved = CeeResolver.resolve(client, query)
            if (resolved == null) {
                android.util.Log.i("CeeResolver", "No Cee match for $externalId series=$series")
                return emptyList()
            }
            resolved
        }
        if (!series) return listOf(root)
        val nativeEpisodes = client.episodes(root)
        val projectedEpisodes = if (request.externalId != null)
            mapSharedEpisodes(nativeEpisodes, officialSeasonCounts, originalLanguage) ?: nativeEpisodes else nativeEpisodes
        val episodeIds = projectedEpisodes
            .filter { it.season == request.season && it.episode == request.episode }
            .map { it.videoId }
            .distinct()

        if (request.externalId?.startsWith("cinemana-series:") == true) {
            val sourceRoot = request.externalId.removePrefix("cinemana-series:")
            val sourceEpisode = cinemanaClient.episodes(sourceRoot)
                .singleOrNull { it.season == request.season && it.episode == request.episode }
                ?: return emptyList()
            val sourceSeconds = sourceEpisode.durationSeconds ?: run {
                com.nuvio.android.cinemana.CinemanaEditions.episodeSeconds(cinemanaClient.info(sourceEpisode.videoId))
            } ?: return emptyList()
            return episodeIds.filter { id ->
                val duration = try {
                    client.videoInfo(id).optString("duration").toDoubleOrNull()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    kotlin.coroutines.coroutineContext[com.nuvio.android.cinemana.ProviderRequestState]
                        ?.complete?.set(false)
                    null
                }
                duration != null && duration.isFinite() && duration in 600.0..18000.0 &&
                    duration / sourceSeconds.toDouble() in 0.70..1.30
            }
        }
        if (episodeIds.size <= 1) return episodeIds

        // A dubbing release delay is not an episode-cut mismatch.
        // Only compare durations of actual candidates for this S/E.
        val measured = kotlinx.coroutines.coroutineScope {
            episodeIds.map { id ->
                async {
                    val duration = try {
                        client.videoInfo(id).optString("duration")
                            .toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it in 60.0..30000.0 }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        kotlin.coroutines.coroutineContext[
                            com.nuvio.android.cinemana.ProviderRequestState
                        ]?.complete?.set(false)
                        null
                    }
                    id to duration
                }
            }.map { it.await() }
        }

        val reference = measured.mapNotNull { it.second }.maxOrNull()
        if (reference == null) {
            // Keep one exact native edition rather than mixing unknown cuts.
            return episodeIds.take(1)
        }
        return measured.filter { (_, duration) ->
            duration != null && duration >= reference * 0.65
        }.map { it.first }
    }

    private fun ceeVideoId(url: String, resource: String): String {
        val parts = URI(url).path.trim('/').split('/')
        require(parts.size == 3 && parts[0] == resource) {
            "Invalid Cee resource URL"
        }
        val encodedId = parts[2].removeSuffix(".json")
        val decodedId = java.net.URLDecoder.decode(
            encodedId.replace("+", "%2B"),
            "UTF-8"
        )
        val id = decodedId.removePrefix("cee:")
        require(id.matches(Regex("[0-9]+"))) {
            "Invalid Cee video ID"
        }
        return id
    }
}
