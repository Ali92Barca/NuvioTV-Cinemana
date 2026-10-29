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

    suspend fun match(titles: List<String>, year: Int?, series: Boolean): Match? {
        val aliases = titles.map(CinemanaEditions::identityTitle).filter { it.isNotBlank() && it != "null" }.distinctBy(::normalize).take(4)
        if (apiKey.isBlank() || aliases.isEmpty()) return null
        val kind = if (series) "tv" else "movie"
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
            } }.mapNotNull { it.await() }
        }
        val candidates = linkedMapOf<Int, JSONObject>()
        for (results in responses) for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            if (id <= 0) continue
            val labels = listOf(item.optString(if (series) "name" else "title"), item.optString(if (series) "original_name" else "original_title"))
            if (labels.none { normalize(it) in names }) continue
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
                    100.0 + if (year != null && firstYear != null) (25 - abs(year - firstYear) * 5).coerceAtLeast(0) else 0,
                )
            } }.mapNotNull { it.await() }
        }.sortedByDescending { it.score }
        val best = matches.firstOrNull() ?: return null
        return best.takeIf { matches.size < 2 || it.score - matches[1].score >= 5.0 }
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
