package com.nuvio.tv.data.cee

import kotlinx.coroutines.sync.withLock
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
        val type: String,
        val seasonCounts: Map<Int, Int> = emptyMap(),
        val originalLanguage: String? = null
    )

    // Small shared snapshot of public CEE Discover results.
    private data class DiscoverSnapshot(
        val createdAt: Long,
        val records: List<JSONObject>
    )

    private val discoverLock = Any()
    private val discoverMutex = kotlinx.coroutines.sync.Mutex()
    private val discoverSnapshots = mutableMapOf<Boolean, DiscoverSnapshot>()
    private const val DISCOVER_TTL_MS = 15 * 60 * 1000L

    private suspend fun cachedDiscoverCandidates(
        client: CeeClient,
        series: Boolean
    ): List<JSONObject> {
        fun fresh(): List<JSONObject>? {
            val now = android.os.SystemClock.elapsedRealtime()
            return synchronized(discoverLock) {
                discoverSnapshots[series]
                    ?.takeIf { now - it.createdAt in 0 until DISCOVER_TTL_MS }
                    ?.records
            }
        }

        fresh()?.let { return it }

        return discoverMutex.withLock {
            fresh()?.let { return@withLock it }

            val candidates = linkedMapOf<String, JSONObject>()
            var completed = true

            for (page in 0 until 12) {
                val rows = try {
                    client.discover(series = series, page = page)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    completed = false
                    break
                }

                if (rows.length() == 0) break

                for (i in 0 until rows.length()) {
                    val item = rows.optJSONObject(i) ?: continue
                    val id = item.optString("nb")
                    if (id.matches(Regex("[0-9]+"))) {
                        candidates.putIfAbsent(id, item)
                    }
                }
            }

            val result = candidates.values.toList()
            if (completed) {
                synchronized(discoverLock) {
                    discoverSnapshots[series] = DiscoverSnapshot(
                        android.os.SystemClock.elapsedRealtime(), result
                    )
                }
            }
            result
        }
    }

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
        val searchTerms = keywords.flatMap { title ->
            listOf(title, title.replace('ة', 'ه'))
        }.distinct()

        for (keyword in searchTerms) {
            try {
                // Search all of the first four result pages. Cee often puts
                // the exact title after similarly named films on page zero.
                for (page in 0 until 4) {
                    val results = client.search(keyword, query.type == "series", page)
                    for (index in 0 until results.length()) {
                        results.optJSONObject(index)?.let(candidates::add)
                    }
                    if (results.length() == 0) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the remaining title if this search fails.
            }
        }

        match(query, candidates.distinctBy { it.optString("nb") })?.let { return it }
        // Search may omit a newly indexed title. Verify latest-feed candidates
        // using the same strict title / IMDb / year rules for both media types.
        for (page in 0 until 4) {
            val rows = try {
                if (query.type == "series") client.latestSeries(page) else client.latestMovies(page)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { continue }
            val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            match(query, items)?.let { return it }
            if (items.isEmpty()) break
        }

        // Some CEE movies appear in year catalogs but not in title search
        // or the latest feed. Use a bounded, exact-identity fallback.
        if (query.type == "movie" && query.year != null &&
            query.year in 1900..2100) {
            val yearCandidates = mutableListOf<JSONObject>()
            for (page in 0 until 10) {
                val rows = try {
                    client.discover(
                        series = false,
                        page = page,
                        startYear = query.year,
                        endYear = query.year
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    break
                }

                if (rows.length() == 0) break
                for (index in 0 until rows.length()) {
                    rows.optJSONObject(index)?.let(yearCandidates::add)
                }
            }
            match(query, yearCandidates.distinctBy {
                it.optString("nb")
            })?.let { return it }
        }

        // Home groups contain titles which may not be indexed by title search.
        // Bound the number of page requests to protect low-memory TV devices.
        val homeCandidates = mutableListOf<JSONObject>()
        val homeGroups = try {
            client.homeGroups("ar")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            org.json.JSONArray()
        }

        var homeRequests = 0
        for (index in 0 until homeGroups.length()) {
            if (homeRequests >= 24) break
            val group = homeGroups.optJSONObject(index) ?: continue
            val groupId = group.optString("groupsID")
            if (!groupId.matches(Regex("[0-9]+"))) continue

            // Group previews can be used without another network request.
            val previews = group.optJSONArray("content")
            if (previews != null) {
                for (itemIndex in 0 until previews.length()) {
                    previews.optJSONObject(itemIndex)?.let(homeCandidates::add)
                }
            }

            homeRequests++
            val page = try {
                client.homeGroupPage(groupId, 0)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }

            for (itemIndex in 0 until page.length()) {
                page.optJSONObject(itemIndex)?.let(homeCandidates::add)
            }
        }

        match(query, homeCandidates.distinctBy {
            it.optString("nb")
        })?.let { return it }

        // Final bounded Discover fallback; reuse its public index across titles.
        val discoverCandidates = cachedDiscoverCandidates(
            client,
            series = query.type == "series"
        )
        match(query, discoverCandidates)?.let { return it }

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
                // Prefer the exact title when several editions share an IMDb ID.
                // Keep ambiguous matches rejected by the existing singleOrNull().
                val candidateTitles = listOf(
                    normalized(item.optString("ar_title")),
                    normalized(item.optString("en_title"))
                ).filter { it.isNotBlank() }
                val expectedTitles = listOf(expectedArabic, expectedEnglish)
                    .filter { it.isNotBlank() }
                val exactTitle = expectedTitles.any { it in candidateTitles }
                return@mapNotNull id to if (exactTitle) 120 else 100
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