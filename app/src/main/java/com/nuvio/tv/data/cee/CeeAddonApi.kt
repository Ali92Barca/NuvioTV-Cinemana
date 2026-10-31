package com.nuvio.tv.data.cee

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
) : AddonApi {

    companion object {
        const val BASE_URL = CeeResourceAdapter.BASE_URL
    }

    private val client = CeeClient(http)
    private val adapter = CeeResourceAdapter(client)
    private val tmdbLookup = CeeTmdbLookup(http, tmdbCredentials)
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
                    mapOf("name" to "stream", "types" to listOf("movie", "series"), "idPrefixes" to listOf("cee:", "tt", "imdb:", "tmdb:")),
                    mapOf("name" to "subtitles", "types" to listOf("movie", "series"), "idPrefixes" to listOf("cee:", "tt", "imdb:", "tmdb:")),
                ),
                catalogs = listOf(
                    CatalogDescriptorDto(
                        type = "movie",
                        id = CeeResourceAdapter.MOVIES_CATALOG,
                        name = "Cee - Latest Movies",
                        pageSize = 24,
                        extraSupported = listOf("skip")
                    ),
                    CatalogDescriptorDto(
                        type = "series",
                        id = CeeResourceAdapter.SERIES_CATALOG,
                        name = "Cee - Latest Series",
                        pageSize = 24,
                        extraSupported = listOf("skip")
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

        val skip = Regex("(?:^|/)skip=(\\d+)")
            .find(uri.rawPath ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        return Response.success(
            adapter.catalog(catalogId, skip / 24)
        )
    }

    override suspend fun getMeta(
        metaUrl: String,
    ): Response<MetaResponseDto> {
        if (!isCee(metaUrl)) return delegate.getMeta(metaUrl)
        return Response.success(adapter.meta(ceeVideoId(metaUrl, "meta"), URI(metaUrl).path.trim('/').split('/')[1]))
    }

    override suspend fun getStreams(
        streamUrl: String,
    ): Response<StreamResponseDto> {
        if (!isCee(streamUrl)) return delegate.getStreams(streamUrl)
        val load: suspend () -> String = {
            val state = com.nuvio.android.cinemana.ProviderRequestState()
            val payload = kotlinx.coroutines.withContext(state) {
                val id = resolveVideoId(streamUrl, "stream")
                streamJson.toJson(if (id != null) adapter.streams(id) else StreamResponseDto())
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
        val id = resolveVideoId(subtitleUrl, "subtitles")
        return Response.success(if (id != null) adapter.subtitles(id) else SubtitleResponseDto())
    }

    private suspend fun resolveVideoId(url: String, resource: String): String? {
        val parts = URI(url).path.trim('/').split('/')
        require(parts.size == 3 && parts[0] == resource)
        val series = parts[1] == "series"
        require(series || parts[1] == "movie")
        val raw = java.net.URLDecoder.decode(parts[2].removeSuffix(".json").replace("+", "%2B"), "UTF-8")
        val request = CeeVideoRequest.parse(raw, series) ?: return null
        val root = if (request.nativeId != null) request.nativeId else {
            val query = tmdbLookup.query(request.externalId ?: return null, series) ?: return null
            CeeResolver.resolve(client, query) ?: return null
        }
        if (!series) return root
        return client.episodes(root).filter { it.season == request.season && it.episode == request.episode }
            .map { it.videoId }.distinct().singleOrNull()
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
