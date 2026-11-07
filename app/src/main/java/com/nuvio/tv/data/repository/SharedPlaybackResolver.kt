package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPlaybackResolver @Inject constructor(
    private val registry: SharedPlaybackIdentity,
    private val metaRepository: MetaRepository,
    private val tmdb: TmdbService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2)
    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val artworkRetryAfter = createArtworkRetryCache()
    internal val seriesPosterCandidates = MutableStateFlow<Map<String, String>>(emptyMap())
    private val visible = Channel<Pair<String, String>>(16, BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for ((id, type) in visible) {
                val series = type in setOf("series", "tv")
                if (series) {
                    if (registry.canonical(id, seriesHint = true) != id) continue
                } else if (registry.artwork.value.containsKey(id) || registry.canonical(id).startsWith("tt")) {
                    continue
                }
                if (System.currentTimeMillis() < (artworkRetryAfter[id] ?: 0L)) continue
                val loaded = try {
                    withTimeoutOrNull(12_000) {
                        val cached = if (series) metaRepository.getCachedBuiltinDetails(type, id) else null
                        if (cached?.tmdbId != null) {
                            learn(cached)
                            true
                        } else {
                            metaRepository.getBuiltinArtwork(type, id)?.also { learn(it) } != null
                        }
                    } == true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                artworkRetryAfter[id] = System.currentTimeMillis() + if (loaded) 600_000L else 15_000L
            }
        }
    }

    private fun createArtworkRetryCache() = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 256
    }

    internal suspend fun learn(meta: Meta) {
        if (!nativePlaybackId(meta.id)) return
        val tmdbId = (meta.artworkTmdbId ?: meta.tmdbId)?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val kind = if (meta.apiType in setOf("series", "tv")) "tv" else "movie"
        val nativeImdb = meta.imdbId?.takeIf { Regex("tt[0-9]+").matches(it) }
        val officialImdb = tmdb.tmdbToImdb(tmdbId, kind)
        if (nativeImdb != null && officialImdb != null && nativeImdb != officialImdb) return
        val imdb = officialImdb ?: nativeImdb
        if (imdb != null) registry.learnArtwork(meta.id, imdb)
        if (meta.tmdbId?.toIntOrNull() == tmdbId) registry.learn(meta, imdb)
        if (kind == "tv" && registry.canonical(meta.id, seriesHint = true) == meta.id) {
            seriesPosterCandidates.update { candidates ->
                if (meta.id in candidates) candidates
                else (candidates + (meta.id to "tmdb:tv:$tmdbId")).toList().takeLast(256).toMap()
            }
        }
    }

    internal suspend fun resolve(id: String): String {
        lookup(id)?.let { withTimeoutOrNull(8_000) { it.await() } }
        return registry.canonical(id)
    }

    internal fun verifySeriesPoster(id: String) {
        lookup(id, requestedType = "series")
    }

    private fun lookup(id: String, requestedType: String? = null): Deferred<Unit>? {
        if (!nativePlaybackId(id) || registry.canonical(id) != id) return null
        if (System.currentTimeMillis() < (retryAfter[id] ?: 0L)) return null
        val job = synchronized(inFlight) {
            inFlight[id] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val loaded = semaphore.withPermit {
                        withTimeoutOrNull(45_000) {
                            // Cee resolves its native type from metadata; Cinemana IDs encode it.
                            val type = requestedType ?: if (id.startsWith("cinemana-series:")) "series" else "movie"
                            val base = if (id.startsWith("cee:")) "https://cee.nuvio.invalid" else "https://cinemana.nuvio.invalid"
                            val result = metaRepository.getMeta(base, type, id).first { it !is NetworkResult.Loading }
                            if (result is NetworkResult.Success) learn(result.data)
                            result is NetworkResult.Success
                        }
                    }
                    retryAfter[id] = System.currentTimeMillis() + if (loaded == true) 600_000L else 15_000L
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { retryAfter[id] = System.currentTimeMillis() + 15_000L }
                finally { inFlight.remove(id) }
            }.also { inFlight[id] = it }
        }
        job.start()
        return job
    }

    internal fun prioritizePoster(id: String, type: String) { visible.trySend(id to type) }
}
