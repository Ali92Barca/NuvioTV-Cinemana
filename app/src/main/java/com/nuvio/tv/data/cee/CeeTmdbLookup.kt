package com.nuvio.tv.data.cee

import com.nuvio.android.cinemana.TmdbCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class CeeTmdbLookup(
    private val http: OkHttpClient,
    private val credentials: () -> TmdbCredentials
) {
    internal suspend fun matchEpisodeLayout(
        id: Int,
        episodes: List<CeeClient.Episode>
    ): List<CeeClient.Episode>? = withContext(Dispatchers.IO) {
        try {
            val key = credentials().apiKey
            if (key.isBlank()) return@withContext null
            val details = fetchJson("https://api.themoviedb.org/3/tv/$id?api_key=$key")
            mapSharedEpisodes(episodes, seasonCounts(details), details.optString("original_language"))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    /** Verified available coordinates plus duration samples; unknown/recut editions stay independent. */
    suspend fun compatibleEpisodes(
        id: Int, episodes: List<CeeClient.Episode>, client: CeeClient,
        unchecked: Set<com.nuvio.tv.data.builtincache.BuiltinDetailsStore.EpisodeKey>?
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val key = credentials().apiKey
                if (key.isBlank() || episodes.isEmpty()) return@withContext false
                val details = fetchJson("https://api.themoviedb.org/3/tv/$id?api_key=$key")
                val seasons = details.optJSONArray("seasons") ?: return@withContext false
                val expected = mutableMapOf<Pair<Int, Int>, Int?>()
                for (index in 0 until seasons.length()) {
                    val season = seasons.getJSONObject(index).optInt("season_number")
                    if (season <= 0) continue
                    val official = fetchJson("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$key")
                        .optJSONArray("episodes") ?: return@withContext false
                    for (i in 0 until official.length()) {
                        val episode = official.getJSONObject(i)
                        val number = episode.optInt("episode_number")
                        if (number > 0) expected[season to number] = episode.optInt("runtime").takeIf { it > 0 }
                    }
                }
                val coordinates = episodes.map { it.season to it.episode }
                if (!com.nuvio.tv.data.repository.verifiedEpisodeCoordinates(coordinates, expected.keys)) return@withContext false
                val samples = if (unchecked != null) episodes.filter {
                    com.nuvio.tv.data.builtincache.BuiltinDetailsStore.EpisodeKey(it.videoId, it.season, it.episode) in unchecked
                } else episodes.groupBy { it.season }.values.flatMap { group ->
                    val sorted = group.sortedBy { it.episode }
                    listOf(sorted.first(), sorted[sorted.size / 2], sorted.last()).distinctBy { it.videoId }
                }
                samples.all { episode ->
                    val seconds = client.videoInfo(episode.videoId).optString("duration").toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it in 60.0..30000.0 }?.toLong()
                    com.nuvio.android.cinemana.CinemanaEpisodeMapping.durationCompatible(seconds,
                        expected[episode.season to episode.episode]) == true
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }

    suspend fun movieQuery(videoId: String): CeeResolver.Query? = query(videoId, false)

    suspend fun query(videoId: String, series: Boolean): CeeResolver.Query? =
        withContext(Dispatchers.IO) {
            val kind = if (series) "tv" else "movie"
            val imdbId = Regex("(?:imdb:)?(tt[0-9]+)").matchEntire(videoId)?.groupValues?.get(1)
            val tmdbId = Regex("(?:tmdb:$kind:|" + (if (series) "tmdb:series:|" else "") + "tmdb:)([0-9]+)")
                .matchEntire(videoId)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val key = credentials().apiKey
            if (key.isBlank()) return@withContext null

            try {
                val id = tmdbId ?: imdbId?.let { resolveTmdbId(it, key, series) }
                    ?: return@withContext null

                val details = fetchJson(
                    "https://api.themoviedb.org/3/$kind/$id" +
                        "?api_key=$key&language=ar"
                )

                val arabicTitle = details.optString(if (series) "name" else "title")
                    .takeIf { it.isNotBlank() }

                val englishTitle = try {
                    fetchJson(
                        "https://api.themoviedb.org/3/$kind/$id" +
                            "?api_key=$key&language=en-US"
                    ).optString(if (series) "name" else "title").takeIf { it.isNotBlank() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    details.optString(if (series) "original_name" else "original_title")
                        .takeIf { it.isNotBlank() }
                }

                if (arabicTitle == null && englishTitle == null) {
                    return@withContext null
                }

                val resolvedImdb = details.optString("imdb_id")
                    .takeIf { it.matches(Regex("tt[0-9]+")) }
                    ?: imdbId

                val year = details.optString(if (series) "first_air_date" else "release_date")
                    .take(4).toIntOrNull()

                CeeResolver.Query(
                    imdbId = resolvedImdb,
                    arabicTitle = arabicTitle,
                    englishTitle = englishTitle,
                    year = year,
                    type = if (series) "series" else "movie",
                    seasonCounts = if (series) seasonCounts(details) else emptyMap(),
                    originalLanguage = details.optString("original_language").takeIf { it.isNotBlank() }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private fun seasonCounts(details: JSONObject): Map<Int, Int> {
        val seasons = details.optJSONArray("seasons") ?: return emptyMap()
        return (0 until seasons.length()).mapNotNull { index ->
            val row = seasons.optJSONObject(index) ?: return@mapNotNull null
            val season = row.optInt("season_number")
            val count = row.optInt("episode_count")
            if (season > 0) season to count else null
        }.toMap()
    }

    private fun resolveTmdbId(imdbId: String, key: String, series: Boolean): Int? {
        val json = fetchJson(
            "https://api.themoviedb.org/3/find/$imdbId" +
                "?api_key=$key&external_source=imdb_id"
        )
        return json.optJSONArray(if (series) "tv_results" else "movie_results")
            ?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "TMDB HTTP ${response.code}"
            }
            return JSONObject(requireNotNull(response.body).string())
        }
    }
}
