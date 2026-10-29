package com.nuvio.tv.data.cinemana

import com.nuvio.android.cinemana.CinemanaClient
import com.nuvio.android.cinemana.CinemanaResourceAdapter
import com.nuvio.android.cinemana.TmdbCredentials
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response

/** Handles the built-in provider in-process. External addon requests retain Retrofit behavior. */
class CinemanaAddonApi(
    private val delegate: AddonApi,
    private val moshi: Moshi,
    private val http: OkHttpClient,
) : AddonApi {
    companion object { const val BASE_URL = "https://cinemana.nuvio.invalid" }
    // BuildConfig.DEBUG enables the upstream BASIC logger, which includes TMDB query keys.
    // Provider traffic deliberately uses no logger or diagnostics interceptors.
    private val transport = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.build()
    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        transport.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Provider HTTP ${response.code}" }
            requireNotNull(response.body).string()
        }
    }
    private val adapter = CinemanaResourceAdapter(
        CinemanaClient(transport = ::fetch),
        { TmdbCredentials(BuildConfig.TMDB_API_KEY, "ar") },
        ::fetch,
    )
    private suspend fun <T : Any> local(url: String, type: Class<T>): Response<T>? {
        val json = adapter.handle(url) ?: return null
        return Response.success(requireNotNull(moshi.adapter(type).fromJson(json)))
    }
    override suspend fun getManifest(manifestUrl: String): Response<AddonManifestDto> =
        local(manifestUrl, AddonManifestDto::class.java) ?: delegate.getManifest(manifestUrl)
    override suspend fun getCatalog(catalogUrl: String): Response<CatalogResponseDto> =
        local(catalogUrl, CatalogResponseDto::class.java) ?: delegate.getCatalog(catalogUrl)
    override suspend fun getMeta(metaUrl: String): Response<MetaResponseDto> =
        local(metaUrl, MetaResponseDto::class.java) ?: delegate.getMeta(metaUrl)
    override suspend fun getStreams(streamUrl: String): Response<StreamResponseDto> =
        local(streamUrl, StreamResponseDto::class.java) ?: delegate.getStreams(streamUrl)
    override suspend fun getSubtitles(subtitleUrl: String): Response<SubtitleResponseDto> =
        local(subtitleUrl, SubtitleResponseDto::class.java) ?: delegate.getSubtitles(subtitleUrl)
}
