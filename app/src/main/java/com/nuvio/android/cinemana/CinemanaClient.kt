package com.nuvio.android.cinemana

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Year
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Android-native Cinemana API client. No Node, localhost or addon server.
 * This is a transport/data layer; UI and Nuvio provider wiring are separate.
 */
class CinemanaClient(
    private val baseUrl: String = "https://cinemana.shabakaty.com",
    private val transport: suspend (String) -> String = ::httpGet
) {
    data class Entry(
        val id: String, val title: String, val poster: String?,
        val year: String?, val isSeries: Boolean,
        val aliases: List<String> = listOf(title), val imdbId: String? = null
    )
    data class Episode(
        val videoId: String, val season: Int, val episode: Int, val title: String, val durationSeconds: Long? = null
    )
    data class Stream(val url: String, val quality: String, val container: String)
    data class Subtitle(val url: String, val language: String, val label: String)

    data class CategoryLanguage(
        val id: String,
        val name: String,
        val movieCount: Int,
        val seriesCount: Int
    )

    suspend fun homeGroups(language: String = "ar"): org.json.JSONArray {
        val lang = if (language == "en") "en" else "ar"
        return get("/api/android/videoGroups/lang/$lang/level/0")
            .optJSONArray("groups") ?: org.json.JSONArray()
    }

    suspend fun homeGroupPage(groupId: String, page: Int = 0): org.json.JSONArray {
        require(groupId.matches(Regex("[0-9]+"))) { "Invalid Cinemana Home group ID" }
        require(page in 0..500) { "Invalid Cinemana Home page" }

        val url = baseUrl.trimEnd('/') +
            "/api/android/videoListPagination/groupID/$groupId/level/0/itemsPerPage/12/page/$page"

        return org.json.JSONArray(transport(url))
    }
    suspend fun categoryLanguages(categoryIds: String): List<CategoryLanguage> {
        val requestedIds = categoryIds.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        if (requestedIds.isEmpty()) return emptyList()

        val raw = transport(baseUrl.trimEnd('/') + "/api/android/categoryLangFreqVideo")
        val categories = if (raw.trimStart().startsWith("[")) {
            JSONArray(raw)
        } else {
            JSONObject(raw).optJSONArray("info") ?: JSONArray()
        }
        val combined = linkedMapOf<String, CategoryLanguage>()

        for (i in 0 until categories.length()) {
            val category = categories.optJSONObject(i) ?: continue
            if (category.optString("nb") !in requestedIds) continue

            val languages = category.optJSONArray("langArray") ?: continue

            for (j in 0 until languages.length()) {
                val language = languages.optJSONObject(j) ?: continue
                val id = language.optString("langNb")
                if (id.isBlank()) continue

                val name = language.optString("lang_ar_title")
                    .ifBlank { language.optString("lang_en_title") }
                    .ifBlank { id }

                val previous = combined[id]

                combined[id] = CategoryLanguage(
                    id = id,
                    name = name,
                    movieCount = (previous?.movieCount ?: 0) +
                        language.optInt("langFreq", 0),
                    seriesCount = (previous?.seriesCount ?: 0) +
                        language.optInt("langFreqSeries", 0)
                )
            }
        }

        return combined.values.toList()
    }
    private val searchRequests = Semaphore(12)

    private val infoCacheLock = Any()
    private val infoCache = object : LinkedHashMap<String, String>(300, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 300
    }

    // A catalog may expose the skippingDurations field as an empty object.
    // Do not treat that as a complete record: fetch the exact edition detail instead.
    private fun hasUsableSceneData(record: JSONObject): Boolean {
        val cuts = record.optJSONObject("skippingDurations")
            ?: record.optString("skippingDurations").takeIf { it.trim().startsWith("{") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (cuts == null) return false
        val starts = cuts.optJSONArray("start")
        val ends = cuts.optJSONArray("end")
        return starts != null && ends != null && starts.length() > 0 && starts.length() == ends.length()
    }

    private fun rememberInfo(record: JSONObject) {
        val id = record.optString("nb", record.optString("id"))
        if (id.isBlank() || !id.all(Char::isDigit)) return

        val rootId = record.optString("rootSeries")
            .takeIf { it.isNotBlank() && it != "0" && it.all(Char::isDigit) }

        val serialized = record.toString()
        synchronized(infoCacheLock) {
            val previous = infoCache[id]?.let { runCatching { JSONObject(it) }.getOrNull() }
            val keepPrevious = previous?.let(::hasUsableSceneData) == true &&
                !hasUsableSceneData(record)
            if (!keepPrevious) infoCache[id] = serialized
            if (rootId != null && rootId != id && !infoCache.containsKey(rootId)) {
                infoCache[rootId] = serialized
            }
        }
    }

    private fun cachedInfo(videoId: String): JSONObject? =
        synchronized(infoCacheLock) {
            infoCache[videoId]?.let(::JSONObject)
        }

    suspend fun search(query: String, series: Boolean, maxPages: Int = 5): List<Entry> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val results = linkedMapOf<String, Entry>()
        var previous = ""
        val seenVideoIds = mutableSetOf<String>()
        // Fetch bounded pages concurrently; consume in order so an empty or
        // repeated page still terminates the search exactly as before.
        val pages = (0 until maxPages.coerceIn(1, 15)).map { page -> async {
            try {
                Result.success(searchRequests.withPermit {
                    val path = "/api/android/AdvancedSearch?level=0&videoTitle=${enc(query)}&staffTitle=&page=$page&year=1900%2C${Year.now().value + 1}&type=${if (series) "series" else "movies"}"
                    items(get(path))
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        } }
        try {
        for (task in pages) {
            val records = task.await().getOrThrow()
            records.forEach(::rememberInfo)
            val signature = records.joinToString(",") { it.optString("nb", it.optString("id")) }
            if (records.isEmpty() || signature == previous) break
            previous = signature
            val newVideoIds = records.count { seenVideoIds.add(it.optString("nb", it.optString("id"))) }
            records.forEach { record ->
                val entry = toEntry(record, series) ?: return@forEach
                val root = numericId(record.optString("rootSeries"))?.takeIf { it != "0" }
                val key = if (series && root == null)
                    "rootless:" + CinemanaEpisodeMapping.rootlessSeriesKey(entry.title, entry.year) + "|" + CinemanaEditions.label(entry.aliases) else entry.id
                val old = results[key]
                results[key] = if (old == null) entry else old.copy(aliases = (old.aliases + entry.aliases).distinct(), imdbId = old.imdbId ?: entry.imdbId)
            }
            if (newVideoIds == 0) break
        }
        results.values.toList()
        } finally { pages.forEach { it.cancel() } }
    }

    /**
     * Fetch one native Cinemana All page. The API uses page numbers,
     * unlike category catalogs which use opaque offsets.
     */
    suspend fun allCatalogPage(series: Boolean, page: Int, yearRange: String? = null, categoryId: String? = null): List<Entry> {
        if (page !in 0..500) return emptyList()

        val type = if (series) "series" else "movies"
        val years = yearRange
            ?.split(',', ':', '-')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        val startYear = years.firstOrNull()?.takeIf { it in 1900..2100 } ?: 1900
        val endYear = years.getOrNull(1)?.takeIf { it in startYear..2100 }
            ?: if (years.isNotEmpty()) startYear else Year.now().value + 1
        val path = "/api/android/AdvancedSearch?level=0&category_id=${enc(categoryId.orEmpty())}&type=$type&page=$page&year=$startYear%2C$endYear"

        val records = items(get(path))
        records.forEach(::rememberInfo)
        val entries = linkedMapOf<String, Entry>()

        for (record in records) {
            val entry = toEntry(record, series) ?: continue
            val root = numericId(record.optString("rootSeries"))?.takeIf { it != "0" }
            val key = if (series && root == null)
                "rootless:" + CinemanaEpisodeMapping.rootlessSeriesKey(entry.title, entry.year) + "|" + CinemanaEditions.label(entry.aliases)
            else entry.id

            entries.putIfAbsent(key, entry)
        }

        return entries.values.toList()
    }

    suspend fun catalog(categoryId: String, series: Boolean, maxPages: Int = 3): List<Entry> {
        val results = linkedMapOf<String, Entry>()
        var offset = 0
        repeat(maxPages.coerceIn(1, 15)) {
            val kind = if (series) "2" else "1"
            val response = get("/api/android/videosByCategory?categoryID=${enc(categoryId)}&orderby=desc&videoKind=$kind&offset=$offset&level=0")
            val before = results.size
            items(response).mapNotNull { toEntry(it, series) }.forEach { results.putIfAbsent(it.id, it) }
            val next = response.optInt("offset", -1)
            if (results.size == before || next <= offset) return results.values.toList()
            offset = next
        }
        return results.values.toList()
    }

    suspend fun episodes(seriesVideoId: String): List<Episode> {
        requireId(seriesVideoId)
        val found = linkedMapOf<String, Episode>()
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val seasons = mutableSetOf<Int>()
        pending.add(seriesVideoId)
        while (pending.isNotEmpty() && visited.size < 12) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val records = try {
                items(get("/api/android/videoSeason/id/$id"))
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { kotlin.coroutines.coroutineContext[ProviderRequestState]?.complete?.set(false); continue }
            val current = records.firstOrNull { it.optString("nb", it.optString("id")) == id }
            val baseSeason = (current ?: records.firstOrNull())?.optInt("season", 1)?.coerceAtLeast(1) ?: 1
            seasons.add(baseSeason)
            for (record in records) {
                val videoId = numericId(record.optString("nb", record.optString("id"))) ?: continue
                val season = record.optInt("season", 1).coerceAtLeast(1)
                val episode = record.optInt("episodeNummer", 1).coerceAtLeast(1)
                found.putIfAbsent(videoId, Episode(videoId, season, episode,
                    title(record).ifBlank { "Episode $episode" }, CinemanaEditions.episodeSeconds(record)))
                val root = numericId(record.optString("rootSeries"))
                if (root != null && root != "0" && season != baseSeason && seasons.add(season) && !visited.contains(videoId)) {
                    pending.add(videoId)
                }
            }
        }
        return found.values.sortedWith(compareBy(Episode::season, Episode::episode))
    }

    /** The API returns an opaque next offset. Nuvio skips count visible series,
     * so walk API cursors and deduplicate before slicing the requested page. */
    suspend fun catalogPage(categoryId: String, series: Boolean, offset: Int, sort: String = "desc", languageId: String? = null): List<Entry> {
        if (!languageId.isNullOrBlank() && categoryId.contains(",")) {
            return mergeLanguageCategories(categoryId, series, offset, sort, languageId)
        }
        val skip = offset.coerceAtLeast(0)
        if (skip > 10_000) return emptyList()
        val allowedSorts = setOf(
            "desc", "asc", "r_desc", "r_asc",
            "stars_desc", "stars_asc",
            "views_desc", "views_asc",
            "rating_desc", "rating_asc",
            "title_asc", "title_desc"
        )
        val orderBy = sort.takeIf { it in allowedSorts } ?: "desc"
        val entries = linkedMapOf<String, Entry>()
        val sourceRecords = linkedMapOf<String, JSONObject>()
        fun orderedPage(): List<Entry> {
            val comparator: Comparator<JSONObject>? = when (orderBy) {
                "r_desc" -> compareByDescending { it.optInt("year", 0) }
                "r_asc" -> compareBy { it.optInt("year", 0) }
                "stars_desc" -> compareByDescending { it.optDouble("stars", 0.0) }
                "stars_asc" -> compareBy { it.optDouble("stars", 0.0) }
                "views_desc" -> compareByDescending { it.optInt("views", 0) }
                "views_asc" -> compareBy { it.optInt("views", 0) }
                "title_asc" -> compareBy { it.optString("en_title").ifBlank { it.optString("ar_title") }.lowercase() }
                "title_desc" -> compareByDescending { it.optString("en_title").ifBlank { it.optString("ar_title") }.lowercase() }
                else -> null
            }
            val keys = if (comparator == null) entries.keys.toList()
                else sourceRecords.entries.sortedWith(compareBy(comparator) { it.value }).map { it.key }
            return keys.drop(skip).take(30).mapNotNull { entries[it] }
        }
        var cursor = 0
        repeat(30) {
            val kind = if (series) "2" else "1"
                        val path = if (languageId.isNullOrBlank()) {
                "/api/android/videosByCategory?categoryID=${enc(categoryId)}&orderby=$orderBy&videoKind=$kind&offset=$cursor&level=0"
            } else {
                "/api/android/videosByCategoryAndLanguage?category_id=${enc(categoryId)}&language_id=${enc(languageId)}&videoKind=$kind&orderby=$orderBy&level=0&offset=$cursor"
            }
            val response = get(path)
            items(response).forEach(::rememberInfo)
            val before = entries.size
            for (record in items(response)) {
                val entry = toEntry(record, series) ?: continue
                val key = if (series && numericId(record.optString("rootSeries"))?.takeIf { it != "0" } == null)
                    "rootless:" + CinemanaEpisodeMapping.rootlessSeriesKey(entry.title, entry.year) + "|" + CinemanaEditions.label(entry.aliases) else entry.id
                entries.putIfAbsent(key, entry)
                sourceRecords.putIfAbsent(key, record)
            }
            val next = response.optInt("offset", -1)
            if (entries.size >= skip + 30 || entries.size == before || next <= cursor)
                return orderedPage()
            cursor = next
        }
        return orderedPage()
    }

    private suspend fun mergeLanguageCategories(
        categoryIds: String,
        series: Boolean,
        offset: Int,
        sort: String,
        languageId: String
    ): List<Entry> {
        val skip = offset.coerceAtLeast(0)
        if (skip > 10_000) return emptyList()

        val categories = categoryIds.split(",").map { it.trim() }
            .filter { it.isNotEmpty() }.distinct()

        val allowedSorts = setOf(
            "desc", "asc", "r_desc", "r_asc",
            "stars_desc", "stars_asc",
            "views_desc", "views_asc",
            "rating_desc", "rating_asc",
            "title_asc", "title_desc"
        )
        val orderBy = sort.takeIf { it in allowedSorts } ?: "desc"

        val records = linkedMapOf<String, JSONObject>()
        val categoryOrders = mutableListOf<List<String>>()

        val target = skip + 30
        val kind = if (series) "2" else "1"

        for (category in categories) {
            var cursor = 0
            var requests = 0
            val seenCursors = mutableSetOf<Int>()
            val ranks = linkedMapOf<String, Int>()


            while (requests < 30 && seenCursors.add(cursor)) {
                requests++

                val path = "/api/android/videosByCategoryAndLanguage" +
                    "?category_id=${enc(category)}&language_id=${enc(languageId)}" +
                    "&videoKind=$kind&orderby=$orderBy&level=0&offset=$cursor"

                val response = get(path)
                val batch = items(response)
                batch.forEach(::rememberInfo)

                val before = ranks.size

                for (record in batch) {
                    val entry = toEntry(record, series) ?: continue
                    val key = if (series &&
                        numericId(record.optString("rootSeries"))?.takeIf { it != "0" } == null
                    ) {
                        "rootless:" + CinemanaEpisodeMapping.rootlessSeriesKey(
                            entry.title, entry.year
                        ) + "|" + CinemanaEditions.label(entry.aliases)
                    } else entry.id

                    if (key !in ranks) ranks[key] = ranks.size
                    records.putIfAbsent(key, record)
                }

                val next = response.optInt("offset", -1)
                if (batch.isEmpty() || next <= cursor) break
                if (ranks.size >= target + 60) break
                cursor = next
            }
            categoryOrders.add(ranks.keys.toList())
        }

        val sortComparator: Comparator<JSONObject>? = when (orderBy) {
            "desc" -> compareByDescending { it.optString("publishDate") }
            "asc" -> compareBy { it.optString("publishDate") }
            "stars_desc" -> compareByDescending { it.optDouble("stars", 0.0) }
            "stars_asc" -> compareBy { it.optDouble("stars", 0.0) }
            "title_desc" -> compareByDescending {
                it.optString("en_title").ifBlank { it.optString("ar_title") }
                    .lowercase()
            }
            "title_asc" -> compareBy {
                it.optString("en_title").ifBlank { it.optString("ar_title") }
                    .lowercase()
            }
            "rating_desc" -> compareByDescending {
                if (series) it.optInt("seriesRating") else it.optInt("filmRating")
            }
            "rating_asc" -> compareBy {
                if (series) it.optInt("seriesRating") else it.optInt("filmRating")
            }
            else -> null
        }

        val ordered = if (sortComparator != null) {
            records.values.sortedWith(sortComparator.thenBy { it.optString("nb") })
        } else {
            val mergedKeys = linkedSetOf<String>()
            val maxSize = categoryOrders.maxOfOrNull { it.size } ?: 0
            for (index in 0 until maxSize) {
                for (categoryOrder in categoryOrders) {
                    categoryOrder.getOrNull(index)?.let { mergedKeys.add(it) }
                }
            }
            mergedKeys.mapNotNull { records[it] }
        }

        return ordered.drop(skip).take(30).mapNotNull { toEntry(it, series) }
    }

    suspend fun completeEpisodes(
        seriesVideoId: String,
        record: JSONObject? = null,
        expectedSeasons: Map<Int, Int> = emptyMap()
    ): List<Episode> {
        val base = try {
            episodes(seriesVideoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Broken videoSeason endpoints must not prevent exact-edition fallback.
            kotlin.coroutines.coroutineContext[ProviderRequestState]?.complete?.set(false)
            emptyList()
        }
        if (expectedSeasons.isNotEmpty() && base.size == expectedSeasons.values.sum() &&
            base.map { it.season to it.episode }.toSet().size == base.size &&
            base.all { it.episode in 1..(expectedSeasons[it.season] ?: 0) }
        ) return base
        val info = record ?: try {
            info(seriesVideoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            kotlin.coroutines.coroutineContext[ProviderRequestState]?.complete?.set(false)
            cachedInfo(seriesVideoId)
                ?.takeIf { it.optString("nb", it.optString("id")) == seriesVideoId }
                ?: return base
        }
        val names = listOf("en_title", "ar_title", "other_title", "title")
            .map { info.optString(it) }.filter { it.isNotBlank() }.distinct()
        val found = base.associateByTo(linkedMapOf()) { it.videoId }
        // A catalog entry may be a child episode, not the series root.
        // Accept search results from its verified native root.
        val ownRoot = numericId(info.optString("rootSeries"))
            ?.takeIf { it != "0" } ?: seriesVideoId
        for (query in names.take(4)) {
            val seen = mutableSetOf<String>()
            var added = 0
            for (page in 0 until 10) {
                val records = items(get("/api/android/AdvancedSearch?level=0&videoTitle=${enc(query)}&staffTitle=&page=$page&year=1900%2C${Year.now().value + 1}&type=series"))
                var newIds = 0
                for (item in records) {
                    val id = numericId(item.optString("nb", item.optString("id"))) ?: continue
                    if (!seen.add(id)) continue
                    newIds++
                    val root = numericId(item.optString("rootSeries"))?.takeIf { it != "0" }
                    if (root != null && root != ownRoot && root != seriesVideoId) continue
                    // Never merge another rootless edition by title/year alone.
                    if (root == null && id != seriesVideoId) continue
                    val year = info.optString("year")
                    if (root == null && year.isNotBlank() && item.optString("year") != year) continue
                    val season = item.optInt("season", 1).coerceAtLeast(1)
                    val number = item.optInt("episodeNummer", 1).coerceAtLeast(1)
                    if (found.putIfAbsent(id, Episode(id, season, number, "Episode $number", CinemanaEditions.episodeSeconds(item))) == null) added++
                }
                // A page can consist entirely of previously seen episodes;
                // don't treat that as proof that later pages are empty.
                if (records.isEmpty()) break
            }
            // Check remaining title aliases too; the first match can have incomplete episodes.
        }
        // The videoSeason endpoint can expose later seasons only when queried
        // using a child episode ID. Follow a bounded number of verified children;
        // never merge an episode from another series root.
        val seed = found.values.take(16).map { it.videoId }.filter { it != seriesVideoId }
        for (childId in seed) {
            val siblingRows = try { items(get("/api/android/videoSeason/id/$childId")) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
            for (item in siblingRows) {
                val episodeId = numericId(item.optString("nb", item.optString("id"))) ?: continue
                val candidateRoot = numericId(item.optString("rootSeries"))?.takeIf { it != "0" }
                if (candidateRoot != null && candidateRoot != ownRoot && candidateRoot != seriesVideoId) continue
                if (candidateRoot == null && episodeId !in found) continue
                val season = item.optInt("season", 1).coerceAtLeast(1)
                val episode = item.optInt("episodeNummer", 1).coerceAtLeast(1)
                found.putIfAbsent(episodeId, Episode(episodeId, season, episode,
                    title(item).ifBlank { "Episode $episode" }, CinemanaEditions.episodeSeconds(item)))
            }
        }
        return found.values.sortedWith(compareBy(Episode::season, Episode::episode))
    }

    suspend fun info(videoId: String): JSONObject {
        requireId(videoId)
        cachedInfo(videoId)?.takeIf {
            it.optString("nb", it.optString("id")) == videoId && hasUsableSceneData(it)
        }?.let { return it }
        try {
            val data = get("/api/android/allVideoInfo/id/$videoId")
            val record = items(data).firstOrNull()
                ?: data.optJSONObject("info")
                ?: data
            val recordId = record.optString("nb").takeIf { it.isNotBlank() && it != "null" }
                ?: record.optString("id")
            if (recordId == videoId) {
                rememberInfo(record)
                return record
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The legacy details endpoint can reject otherwise valid catalog IDs.
        }

        // Use the previously observed exact-ID catalog/search record on HTTP failure.
        // Never substitute another edition with a similar title.
        cachedInfo(videoId)
            ?.takeIf { it.optString("nb", it.optString("id")) == videoId }
            ?.let { return it }

        // Fall back to the native search endpoint, matching the exact video ID.
        // Never substitute a similarly named film for the requested ID.
        for (series in listOf(false, true)) {
            val type = if (series) "series" else "movies"
            for (page in 0 until 5) {
                val path = "/api/android/AdvancedSearch?level=0&videoTitle=&staffTitle=&page=$page&year=1900%2C${Year.now().value + 1}&type=$type"
                val records = items(get(path))
        records.forEach(::rememberInfo)
                records.firstOrNull {
                    it.optString("nb", it.optString("id")) == videoId
                }?.let {
                    rememberInfo(it)
                    return it
                }
                if (records.isEmpty()) break
            }
        }

        error("Cinemana metadata unavailable for video $videoId")
    }

    fun entry(record: JSONObject, series: Boolean): Entry? = toEntry(record, series)

    suspend fun streams(videoId: String): List<Stream> {
        requireId(videoId)
        return items(get("/api/android/transcoddedFiles/id/$videoId")).mapNotNull {
            val url = it.optString("videoUrl")
            if (!isHttp(url)) null else Stream(
                url, it.optString("resolution", it.optString("transcoddedFileName", "Video")),
                it.optString("container", "MP4"))
        }.sortedByDescending { CinemanaQuality.height(it.quality) }
    }

    suspend fun subtitles(videoId: String, infoRecord: JSONObject? = null): List<Subtitle> {
        requireId(videoId)
        val record = infoRecord ?: info(videoId)
        val translations = record.optJSONArray("translations") ?: JSONArray()
        val languages = listOf(
            Triple("ar", "ara", "Arabic"), Triple("en", "eng", "English"),
            Triple("sp", "spa", "Spanish")
        )
        return languages.mapNotNull { (code, lang, label) ->
            val matches = (0 until translations.length()).mapNotNull { translations.optJSONObject(it) }
                .filter { it.optString("type").equals(code, true) || it.optString("name").equals(label, true) }
            val preferred = matches.firstOrNull {
                it.optString("extention", it.optString("extension")).equals("vtt", true) && isHttp(it.optString("file"))
            } ?: matches.firstOrNull { isHttp(it.optString("file")) }
            val fallback = when (code) {
                "ar" -> record.optString("arTranslationFilePath")
                "en" -> record.optString("enTranslationFilePath")
                else -> record.optString("spTranslationFilePath")
            }
            val url = preferred?.optString("file") ?: fallback
            if (isHttp(url)) Subtitle(url, lang, label) else null
        }
    }

    private suspend fun get(path: String): JSONObject {
        val raw = transport(baseUrl.trimEnd('/') + path)
        return if (raw.trimStart().startsWith("[")) JSONObject().put("info", JSONArray(raw))
        else JSONObject(raw)
    }

    private fun items(data: JSONObject): List<JSONObject> {
        val array = listOf("info", "results", "videos", "data", "files")
            .firstNotNullOfOrNull { data.optJSONArray(it) } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun toEntry(item: JSONObject, series: Boolean): Entry? {
        val kind = item.optString("kind")
        if ((kind == "1" && series) || (kind == "2" && !series)) return null
        val id = numericId(item.optString("nb", item.optString("id"))) ?: return null
        val root = numericId(item.optString("rootSeries"))
        val finalId = if (series && root != null && root != "0") root else id
        val image = listOf("imgObjUrl", "imgMediumThumbObjUrl", "imgThumbObjUrl")
            .firstNotNullOfOrNull { item.optString(it).takeIf(String::isNotBlank) }
        val poster = image?.let { if (isHttp(it)) it else baseUrl.trimEnd('/') + "/" + it.trimStart('/') }
        return Entry(finalId, title(item), poster, item.optString("year").ifBlank { null }, series,
            listOf("en_title", "ar_title", "other_title", "title").map { item.optString(it) }.filter { it.isNotBlank() }.distinct(),
            CinemanaIdentity.imdbId(item.optString("imdbUrlRef").ifBlank { item.optString("imdb_id") }))
    }

    private fun title(item: JSONObject): String = listOf("en_title", "ar_title", "other_title", "title")
        .firstNotNullOfOrNull { item.optString(it).takeIf(String::isNotBlank) } ?: ""

    private fun numericId(s: String): String? = s.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    private fun requireId(s: String) { require(numericId(s) != null) { "Invalid Cinemana video ID" } }
    private fun isHttp(s: String) = s.startsWith("https://", true) || s.startsWith("http://", true)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "CinemanaNuvioAndroid/0.1")
    }
    try {
        if (connection.responseCode !in 200..299) error("Cinemana HTTP ${connection.responseCode}")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}
