package com.nuvio.tv.data.builtincache

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal object BuiltinPrefetchMode : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BuiltinPrefetchMode>
}

@Singleton
class BuiltinStreamCache @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileManager,
    private val settings: dagger.Lazy<TmdbSettingsDataStore>,
    http: OkHttpClient,
) {
    private val disk = DiskStreamCache(File(context.filesDir, "builtin-stream-links-v1"))
    private val probe = http.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
    }.callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun key(url: String, profile: Int): String {
        val tmdb = settings.get().settings.value
        val credentials = DiskStreamCache.digest(tmdb.apiKey.ifBlank { BuildConfig.TMDB_API_KEY })
        val version = if (URI(url).host.equals("cee.nuvio.invalid", ignoreCase = true)) "scenes-cee-v2" else "scenes-v1"
        return "$version|$profile|$credentials|${tmdb.language}|$url"
    }

    suspend fun get(url: String, load: suspend () -> String): String {
        val background = coroutineContext[BuiltinPrefetchMode.Key] != null
        val cacheKey = key(url, profiles.activeProfileId.value)
        var hit = true
        val result = disk.get(cacheKey, validate = { payload ->
            if (background) true else validateLink(payload)
        }) { hit = false; load() }
        Log.d("BuiltinStreamCache", "${if (hit) "hit" else "loaded"} provider=${URI(url).host}")
        return result
    }

    private suspend fun validateLink(payload: String): Boolean = withContext(Dispatchers.IO) {
        validateCachedMediaLink(payload) { request ->
            val call = probe.newCall(request)
            call.execute().use { response ->
                val code = response.code
                // Do not consume a video body if a server ignores Range.
                call.cancel()
                code
            }
        }
    }

    suspend fun invalidateRequest(type: String, videoId: String, profileId: Int) {
        for (host in listOf("cinemana", "cee")) {
            val url = "https://$host.nuvio.invalid/stream/${encode(type)}/${encode(videoId)}.json"
            disk.invalidate(key(url, profileId))
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Some media servers reject HEAD while a ranged GET works; do not discard good cached URLs. */
internal suspend fun validateCachedMediaLink(payload: String, execute: suspend (Request) -> Int): Boolean {
    val url = JSONObject(payload).getJSONArray("streams").getJSONObject(0).getString("url")
    return try {
        val request = Request.Builder().url(url).get().header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity").build()
        execute(request) !in listOf(401, 403, 404, 410)
    } catch (_: java.io.IOException) { true }
      catch (_: IllegalArgumentException) { false }
}
