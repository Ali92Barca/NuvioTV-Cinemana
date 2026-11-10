package com.nuvio.tv.data.builtincache

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.data.remote.api.AddonApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private val prefetchVideoIdPattern = Regex("(?:imdb:)?tt[0-9]+(?::[0-9]+:[0-9]+)?|tmdb:(?:(?:movie|tv|series):)?[0-9]+(?::[0-9]+:[0-9]+)?")

/** Only built-in link metadata is warmed. No playback, scrobbling, or media downloads. */
@Singleton
class BuiltinStreamPrefetcher @Inject constructor(
    private val api: AddonApi,
    private val addons: AddonRepository,
    private val profiles: ProfileManager,
) {
    data class Target(val type: String, val videoId: String)
    private val permits = Semaphore(1)
    private val recent = LinkedHashMap<String, Pair<Long, Long>>(64, .75f, true)
    private fun isRecent(key: String): Boolean = synchronized(recent) {
        recent[key]?.let { (at, ttl) -> System.nanoTime() - at < ttl } == true
    }
    private fun remember(key: String, success: Boolean) = synchronized(recent) {
        recent[key] = System.nanoTime() to if (success) 300_000_000_000L else 30_000_000_000L
        while (recent.size > 256) recent.remove(recent.keys.first())
    }

    suspend fun warm(targets: List<Target>, expectedProfile: Int = profiles.activeProfileId.value) {
        if (profiles.activeProfileId.value != expectedProfile) return
        val enabled = addons.getInstalledAddons().first().filter { it.enabled }
            .associate { it.baseUrl.trimEnd('/') to it.version }
        coroutineScope {
            targets.distinct().flatMap { target ->
                listOf("cinemana", "cee").mapNotNull { provider ->
                    val base = "https://$provider.nuvio.invalid"
                    val key = "$expectedProfile|$base|${enabled[base]}|${target.type}|${target.videoId}"
                    if (base !in enabled || !supports(provider, target) || isRecent(key)) null else async {
                        permits.withPermit {
                            if (profiles.activeProfileId.value != expectedProfile || isRecent(key)) return@withPermit
                            try {
                                val url = "$base/stream/${encode(target.type)}/${encode(target.videoId)}.json"
                                val response = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                                    withContext(BuiltinPrefetchMode) { api.getStreams(url) }
                                }
                                remember(key, response?.isSuccessful == true && !response.body()?.streams.isNullOrEmpty())
                                Log.d("BuiltinPrefetch", "ready provider=$provider count=${response?.body()?.streams?.size ?: 0}")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { remember(key, false) }
                        }
                    }
                }
            }.forEach { it.await() }
        }
    }

    companion object {
        fun supports(provider: String, target: Target): Boolean {
            if (target.type !in listOf("movie", "series") || target.videoId.isBlank()) return false
            val id = target.videoId
            if (target.type == "series" && !id.startsWith("cinemana:")) {
                val tokens = id.split(':')
                if (tokens.size < 3 || tokens[tokens.size - 2].toIntOrNull()?.takeIf { it > 0 } == null ||
                    tokens.last().toIntOrNull()?.takeIf { it > 0 } == null) return false
            }
            if (id.startsWith("$provider:")) return true
            if (provider == "cinemana" && id.startsWith("cinemana-series:")) return true
            return id.matches(prefetchVideoIdPattern)
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
