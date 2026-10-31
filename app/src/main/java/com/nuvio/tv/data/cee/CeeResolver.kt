package com.nuvio.tv.data.cee

import org.json.JSONObject
import java.util.Locale

/**
 * Conservative identity matching for Cee search results.
 * Does not confuse TMDB/IMDb IDs with Cee's internal video IDs.
 */
internal object CeeResolver {

    data class Query(
        val imdbId: String?,
        val arabicTitle: String?,
        val englishTitle: String?,
        val year: Int?,
        val type: String
    )

    private val imdbPattern = Regex("tt[0-9]+", RegexOption.IGNORE_CASE)

    private fun normalized(value: String?): String =
        value.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{M}]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun imdbFrom(value: String?): String? =
        imdbPattern.find(value.orEmpty())?.value?.lowercase(Locale.ROOT)

    suspend fun resolve(client: CeeClient, query: Query): String? {
        val keywords = listOfNotNull(
            query.arabicTitle?.trim()?.takeIf { it.isNotBlank() },
            query.englishTitle?.trim()?.takeIf { it.isNotBlank() }
        ).distinct()

        if (keywords.isEmpty()) return null

        val candidates = mutableListOf<JSONObject>()

        for (keyword in keywords) {
            try {
                val results = client.search(keyword, query.type == "series")
                for (index in 0 until results.length()) {
                    results.optJSONObject(index)?.let(candidates::add)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the remaining title if this search fails.
            }
        }

        match(query, candidates.distinctBy { it.optString("nb") })?.let { return it }
        // The remote search index can omit titles present in the latest-series feed.
        // Do not accept fuzzy hits; verify the same identity against a bounded fallback.
        if (query.type == "series") {
            for (page in 0 until 3) {
                val rows = try { client.latestSeries(page) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { continue }
                val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                match(query, items)?.let { return it }
                if (items.isEmpty()) break
            }
        }
        return null
    }

    fun match(query: Query, candidates: List<JSONObject>): String? {
        val expectedImdb = imdbFrom(query.imdbId)
        val expectedYear = query.year
        val expectedArabic = normalized(query.arabicTitle)
        val expectedEnglish = normalized(query.englishTitle)

        val matches = candidates.mapNotNull { item ->
            val rawId = item.optString("nb")
                .takeIf { it.matches(Regex("[0-9]+")) }
                ?: return@mapNotNull null

            val id = if (query.type == "series") item.optString("rootSeries")
                .takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: rawId else rawId
            val kind = item.optString("kind")
            val expectedKind = if (query.type == "series") "2" else "1"
            if (kind != expectedKind) return@mapNotNull null

            val itemImdb = imdbFrom(item.optString("imdbUrlRef"))
            val itemYear = item.optString("year").toIntOrNull()

            if (expectedYear != null &&
                itemYear != null &&
                expectedYear != itemYear && query.type != "series"
            ) return@mapNotNull null

            if (expectedImdb != null && itemImdb != null) {
                if (expectedImdb != itemImdb) return@mapNotNull null
                return@mapNotNull id to 100
            }

            val ar = normalized(item.optString("ar_title"))
            val en = normalized(item.optString("en_title"))

            val titleMatch =
                (expectedArabic.isNotBlank() &&
                    (expectedArabic == ar || expectedArabic == en)) ||
                (expectedEnglish.isNotBlank() &&
                    (expectedEnglish == en || expectedEnglish == ar))

            if (!titleMatch) return@mapNotNull null

            if (expectedYear == null || itemYear == null ||
                expectedYear != itemYear
            ) return@mapNotNull null

            id to 80
        }

        val bestScore = matches.maxOfOrNull { it.second } ?: return null
        val best = matches.filter { it.second == bestScore }.distinctBy { it.first }

        return best.singleOrNull()?.first
    }
}