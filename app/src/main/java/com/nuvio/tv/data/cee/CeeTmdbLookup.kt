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
                    type = if (series) "series" else "movie"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
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