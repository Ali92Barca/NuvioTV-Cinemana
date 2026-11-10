package com.nuvio.tv.core.network

import com.nuvio.android.cinemana.TmdbResponseCache
import okhttp3.OkHttpClient
import okhttp3.Request

/** TMDB JSON only. Never used for playable URLs; do not log credential-bearing requests. */
internal class ProviderMetadataFetch(http: OkHttpClient) {
    private val transport = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val cache = TmdbResponseCache()
    suspend fun fetch(url: String): String = cache.get(url) {
        transport.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
            .awaitProviderBody()
    }
}

/** Same Cinemana headers/timeouts as the original client, with real coroutine cancellation. */
internal fun providerCinemanaClient(http: OkHttpClient): com.nuvio.android.cinemana.CinemanaClient {
    val transport = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    return com.nuvio.android.cinemana.CinemanaClient(transport = { url ->
        transport.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "CinemanaNuvioAndroid/0.1").build()).awaitProviderBody()
    })
}
