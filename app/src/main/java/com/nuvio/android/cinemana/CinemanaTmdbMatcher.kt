package com.nuvio.android.cinemana

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

/**
 * Optional TMDB metadata enrichment. Never replace a Cinemana playback ID with a TMDB ID.
 * Supply the app's existing TMDB API key at runtime, not a committed secret.
 */
class CinemanaTmdbMatcher(
    private val apiKey: String,
    private val language: String = "ar-SA",
    private val fetch: suspend (String) -> String = ::tmdbHttpGet
) {
    data class Match(
        val tmdbId: Int,
        val title: String,
        val overview: String?,
        val poster: String?,
        val year: Int?,
        val score: Double
    )

    suspend fun match(title: String, year: Int?, series: Boolean): Match? =
        match(listOf(title), year, series)

    suspend fun match(titles: List<String>, year: Int?, series: Boolean, imdbId: String? = null): Match? =
        resolve(titles, year, series, imdbId).match

    data class Resolution(val match: Match? = null, val confirmedMissing: Boolean = false)

    suspend fun resolve(titles: List<String>, year: Int?, series: Boolean, imdbId: String?): Resolution {
        val aliases = titles.map(CinemanaEditions::identityTitle).filter { it.isNotBlank() && it != "null" }.distinctBy(::normalize).take(4)
        if (apiKey.isBlank() || aliases.isEmpty()) return Resolution()
        val kind = if (series) "tv" else "movie"
        val verifiedImdb = imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }
        var imdbLookupEmpty = false
        if (verifiedImdb != null) {
            val direct = optional {
                val response = JSONObject(fetch("https://api.themoviedb.org/3/find/$verifiedImdb?api_key=${encode(apiKey)}&external_source=imdb_id&language=${encode(language)}"))
                imdbLookupEmpty = response.optJSONArray("tv_results")?.length() == 0 &&
                    response.optJSONArray("movie_results")?.length() == 0
                val results = response.optJSONArray(if (series) "tv_results" else "movie_results")
                val items = if (results == null) emptyList() else (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                items.filter { it.optInt("id") > 0 }.distinctBy { it.optInt("id") }.singleOrNull()
            }
            if (direct != null) {
                val poster = direct.optString("poster_path").takeIf { it.startsWith('/') }
                return Resolution(Match(direct.optInt("id"), direct.optString(if (series) "name" else "title").ifBlank { aliases.first() },
                    direct.optString("overview").takeIf { it.isNotBlank() && it != "null" },
                    poster?.let { "https://image.tmdb.org/t/p/w500$it" },
                    direct.optString(if (series) "first_air_date" else "release_date").take(4).toIntOrNull(), 200.0))
            }
        }
        val names = aliases.map(::normalize).toSet()
        // TMDB localizes search results; always include English as well as the
        // configured locale, otherwise an English Cinemana title cannot match
        // the localized Arabic name or Turkish original_name.
        val locales = listOf(language, "en-US", "ar-SA", "tr-TR").distinctBy { it.substringBefore('-') }
        val responses = coroutineScope {
            aliases.flatMap { alias -> locales.map { locale ->
                async {
                    optional {
                        JSONObject(fetch("https://api.themoviedb.org/3/search/$kind?api_key=${encode(apiKey)}&language=${encode(locale)}&query=${encode(alias)}"))
                            .optJSONArray("results")
                    }
                }
            } }.map { it.await() }
        }
        val candidates = linkedMapOf<Int, JSONObject>()
        val matchedNames = mutableMapOf<Int, MutableSet<String>>()
        for (results in responses.filterNotNull()) for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            if (id <= 0) continue
            val labels = listOf(item.optString(if (series) "name" else "title"), item.optString(if (series) "original_name" else "original_title"))
            val exactNames = labels.map(::normalize).filter { it in names }.toSet()
            if (exactNames.isEmpty()) continue
            matchedNames.getOrPut(id) { mutableSetOf() }.addAll(exactNames)
            candidates.putIfAbsent(id, item)
        }
        val matches = coroutineScope {
            candidates.values.take(8).map { item -> async {
                val id = item.optInt("id")
                val firstYear = item.optString(if (series) "first_air_date" else "release_date").take(4).toIntOrNull()
                val details = if (series && year != null && firstYear != null && year != firstYear)
                    optional { JSONObject(fetch("https://api.themoviedb.org/3/tv/$id?api_key=${encode(apiKey)}&language=${encode(language)}")) }
                    else null
                if (!CinemanaIdentity.yearMatches(year, firstYear, details?.optString("last_air_date")?.take(4)?.toIntOrNull(), series)) return@async null
                val posterPath = item.optString("poster_path")
                Match(id,
                    item.optString(if (series) "name" else "title").ifBlank { aliases.first() },
                    item.optString("overview").takeIf { it.isNotBlank() && it != "null" },
                    posterPath.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/w500$it" },
                    firstYear,
                    100.0 + (matchedNames[id].orEmpty().size - 1).coerceAtLeast(0) * 10.0 +
                        (if (year != null && firstYear != null) (25 - abs(year - firstYear) * 5).coerceAtLeast(0) else 0),
                )
            } }.mapNotNull { it.await() }
        }.sortedByDescending { it.score }
        val best = matches.firstOrNull()
        // A permanent miss requires an explicit external ID and complete empty searches.
        // Ambiguous names, missing IDs and failed requests must remain retryable.
        if (best == null) return Resolution(confirmedMissing = imdbLookupEmpty &&
            responses.isNotEmpty() && responses.all { it?.length() == 0 })
        return Resolution(best.takeIf { matches.size < 2 || it.score - matches[1].score >= 5.0 })
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun normalize(value: String) = CinemanaEpisodeMapping.normalizeTitle(CinemanaEditions.identityTitle(value))

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}

private suspend fun tmdbHttpGet(url: String): String = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode !in 200..299) error("TMDB HTTP ${connection.responseCode}")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}
