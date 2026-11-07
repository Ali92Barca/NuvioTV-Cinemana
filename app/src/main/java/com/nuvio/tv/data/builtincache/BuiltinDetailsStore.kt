package com.nuvio.tv.data.builtincache

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.nuvio.android.cinemana.CinemanaTmdbMatcher
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.repository.SharedPlaybackIdentity
import com.squareup.moshi.Moshi
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class BuiltinDetailsStore @Inject constructor(
    @ApplicationContext context: Context,
    moshi: Moshi,
    private val settings: Lazy<TmdbSettingsDataStore>,
    private val identities: SharedPlaybackIdentity
) {
    data class EpisodeKey(val id: String, val season: Int, val episode: Int)

    private val bindings = File(context.filesDir, "builtin-tmdb-bindings-v1")
    private val snapshots = File(context.cacheDir, "builtin-details-v1")
    private val cachePolicy = NuvioCachePolicy(context)
    private val gson = Gson()
    private val metaJson = moshi.adapter(MetaDto::class.java)
    private val locks = Array(32) { Mutex() }
    private val cacheLock = Mutex()

    private fun file(directory: File, key: String) =
        File(directory, DiskStreamCache.digest(key) + ".json")

    private suspend fun <T> binding(key: String, action: suspend (File) -> T): T =
        withContext(Dispatchers.IO) {
            locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                action(file(bindings, key))
            }
        }

    suspend fun resolve(
        key: String,
        nativeId: String,
        series: Boolean,
        nativeTitle: String,
        find: suspend () -> CinemanaTmdbMatcher.Resolution
    ): CinemanaTmdbMatcher.Match? = binding("title|$key") { target ->
            val saved = read(target) { gson.fromJson(it, CinemanaTmdbMatcher.Resolution::class.java) }
            if (saved != null && (saved.confirmedMissing || saved.match != null)) return@binding saved.match
            val prefix = if (series) "tmdb:tv:" else "tmdb:movie:"
            val knownId = identities.entries.value.firstOrNull { it.native == nativeId && it.series == series }
                ?.aliases?.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
            val result = if (knownId != null && knownId > 0) {
                CinemanaTmdbMatcher.Resolution(
                    CinemanaTmdbMatcher.Match(knownId, nativeTitle, null, null, null, 0.0)
                )
            } else find()
            if (result.match != null || result.confirmedMissing) write(target, gson.toJson(result))
            result.match
        }

    // Existing verified entries are retained across temporary provider failures.
    // Replaced or renumbered IDs require a fresh edition check.
    suspend fun verify(
        key: String,
        episodes: List<EpisodeKey>,
        check: suspend (Set<EpisodeKey>, Boolean) -> Boolean?
    ): Boolean? = binding("episodes|$key") { target ->
        if (episodes.isEmpty()) return@binding null
        val saved = read(target) { gson.fromJson(it, Array<EpisodeKey>::class.java) }?.toSet().orEmpty()
        val current = episodes.toSet()
        val extendsSaved = saved.isNotEmpty() && current.containsAll(saved)
        val unchecked = if (extendsSaved) current - saved else current
        if (extendsSaved && unchecked.isEmpty()) return@binding true
        val result = check(unchecked, extendsSaved)
        if (result == true) write(target, gson.toJson(episodes))
        result
    }

    internal suspend fun verifyEpisodesIndividually(
        key: String,
        current: Set<EpisodeKey>,
        check: suspend (Set<EpisodeKey>) -> Map<EpisodeKey, Boolean?>
    ): Set<EpisodeKey>? = binding("episode-runtimes|$key") { target ->
        val saved = read(target) { gson.fromJson(it, Array<EpisodeKey>::class.java) }?.toSet().orEmpty()
        val retained = if (current.containsAll(saved)) saved else emptySet()
        val checks = check(current - retained)
        if (checks.values.any { it == false }) return@binding null
        val verified = retained + checks.filterValues { it == true }.keys
        if (verified.isNotEmpty() && verified != saved) write(target, gson.toJson(verified))
        verified
    }

    internal fun rememberVerifiedArtwork(nativeId: String, imdb: String) = identities.learnArtwork(nativeId, imdb)

    fun snapshotKey(type: String, id: String): String? {
        if (!id.startsWith("cinemana:") && !id.startsWith("cinemana-series:") && !id.startsWith("cee:")) return null
        val kind = if (type == "series" || type == "tv" || id.startsWith("cinemana-series:")) "series" else "movie"
        return "${settings.get().settings.value.language}|$kind|$id"
    }

    suspend fun readSnapshot(key: String): MetaDto? = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            val target = file(snapshots, key)
            read(target, metaJson::fromJson)?.also { target.setLastModified(System.currentTimeMillis()) }
        }
    }

    suspend fun saveSnapshot(key: String, meta: MetaDto) = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            val target = file(snapshots, key)
            val raw = metaJson.toJson(meta)
            val limit = cachePolicy.limitBytes(NuvioCacheKind.DETAILS)
            val size = raw.toByteArray().size
            if (size > 2 * 1024 * 1024 || size > limit || !cachePolicy.hasWriteHeadroom() || !write(target, raw)) return@withLock
            val files = snapshots.listFiles { entry -> entry.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
            var bytes = files.sumOf { it.length() }
            for (entry in files) {
                if (bytes <= limit) break
                val size = entry.length()
                if (entry.delete()) {
                    bytes -= size
                }
            }
        }
    }

    private fun <T> read(target: File, parse: (String) -> T): T? = try {
        parse(AtomicFile(target).readFully().toString(Charsets.UTF_8))
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun write(target: File, raw: String): Boolean {
        target.parentFile?.mkdirs()
        val file = AtomicFile(target)
        val stream = try { file.startWrite() } catch (_: IOException) { return false }
        return try {
            stream.write(raw.toByteArray())
            file.finishWrite(stream)
            true
        } catch (_: IOException) {
            file.failWrite(stream)
            false
        }
    }
}
