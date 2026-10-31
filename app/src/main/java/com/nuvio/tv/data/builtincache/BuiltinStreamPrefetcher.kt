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

/** Only built-in link metadata is warmed. No playback, scrobbling, or media downloads. */
@Singleton
class BuiltinStreamPrefetcher @Inject constructor(
    private val api: AddonApi,
    private val addons: AddonRepository,
    private val profiles: ProfileManager,
) {
    data class Target(val type: String, val videoId: String)
    private val permits = Semaphore(2)

    suspend fun warm(targets: List<Target>, expectedProfile: Int = profiles.activeProfileId.value) {
        if (profiles.activeProfileId.value != expectedProfile) return
        val enabled = addons.getInstalledAddons().first().filter { it.enabled }.map { it.baseUrl.trimEnd('/') }.toSet()
        coroutineScope {
            targets.distinct().flatMap { target ->
                listOf("cinemana", "cee").mapNotNull { provider ->
                    val base = "https://$provider.nuvio.invalid"
                    if (base !in enabled || !supports(provider, target)) null else async {
                        permits.withPermit {
                            if (profiles.activeProfileId.value != expectedProfile) return@withPermit
                            try {
                                val url = "$base/stream/${encode(target.type)}/${encode(target.videoId)}.json"
                                val response = withContext(BuiltinPrefetchMode) { api.getStreams(url) }
                                Log.d("BuiltinPrefetch", "ready provider=$provider count=${response.body()?.streams?.size ?: 0}")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Normal foreground requests remain available. */ }
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
            return id.matches(Regex("(?:imdb:)?tt[0-9]+(?::[0-9]+:[0-9]+)?|tmdb:(?:(?:movie|tv|series):)?[0-9]+(?::[0-9]+:[0-9]+)?"))
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
