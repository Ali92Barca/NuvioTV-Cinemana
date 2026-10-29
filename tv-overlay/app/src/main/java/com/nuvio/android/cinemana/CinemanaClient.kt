package com.nuvio.android.cinemana

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val aliases: List<String> = listOf(title)
    )
    data class Episode(val videoId: String, val season: Int, val episode: Int, val title: String)
    data class Stream(val url: String, val quality: String, val container: String)
    data class Subtitle(val url: String, val language: String, val label: String)

    suspend fun search(query: String, series: Boolean, maxPages: Int = 5): List<Entry> {
        if (query.isBlank()) return emptyList()
        val results = linkedMapOf<String, Entry>()
        var previous = ""
        val seenVideoIds = mutableSetOf<String>()
        for (page in 0 until maxPages.coerceIn(1, 15)) {
            val path = "/api/android/AdvancedSearch?level=0&videoTitle=${enc(query)}&staffTitle=&page=$page&year=1900%2C${Year.now().value + 1}&type=${if (series) "series" else "movies"}"
            val records = items(get(path))
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
                results[key] = if (old == null) entry else old.copy(aliases = (old.aliases + entry.aliases).distinct())
            }
            if (newVideoIds == 0) break
        }
        return results.values.toList()
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
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
            val current = records.firstOrNull { it.optString("nb", it.optString("id")) == id }
            val baseSeason = (current ?: records.firstOrNull())?.optInt("season", 1)?.coerceAtLeast(1) ?: 1
            seasons.add(baseSeason)
            for (record in records) {
                val videoId = numericId(record.optString("nb", record.optString("id"))) ?: continue
                val season = record.optInt("season", 1).coerceAtLeast(1)
                val episode = record.optInt("episodeNummer", 1).coerceAtLeast(1)
                found.putIfAbsent(videoId, Episode(videoId, season, episode,
                    title(record).ifBlank { "Episode $episode" }))
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
    suspend fun catalogPage(categoryId: String, series: Boolean, offset: Int): List<Entry> {
        val skip = offset.coerceAtLeast(0)
        if (skip > 10_000) return emptyList()
        val entries = linkedMapOf<String, Entry>()
        var cursor = 0
        repeat(30) {
            val kind = if (series) "2" else "1"
            val response = get("/api/android/videosByCategory?categoryID=${enc(categoryId)}&orderby=desc&videoKind=$kind&offset=$cursor&level=0")
            val before = entries.size
            for (record in items(response)) {
                val entry = toEntry(record, series) ?: continue
                val key = if (series && numericId(record.optString("rootSeries"))?.takeIf { it != "0" } == null)
                    "rootless:" + CinemanaEpisodeMapping.rootlessSeriesKey(entry.title, entry.year) + "|" + CinemanaEditions.label(entry.aliases) else entry.id
                entries.putIfAbsent(key, entry)
            }
            val next = response.optInt("offset", -1)
            if (entries.size >= skip + 30 || entries.size == before || next <= cursor)
                return entries.values.drop(skip).take(30)
            cursor = next
        }
        return entries.values.drop(skip).take(30)
    }

    suspend fun completeEpisodes(seriesVideoId: String, record: JSONObject? = null): List<Episode> {
        val base = episodes(seriesVideoId)
        val info = record ?: info(seriesVideoId)
        val names = listOf("en_title", "ar_title", "other_title", "title")
            .map { info.optString(it) }.filter { it.isNotBlank() }.distinct()
        val found = base.associateByTo(linkedMapOf()) { it.videoId }
        for (query in names.take(2)) {
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
                    if (root != null && root != seriesVideoId) continue
                    if (root == null && id != seriesVideoId && CinemanaEpisodeMapping.normalizeTitle(title(item)) !in names.map(CinemanaEpisodeMapping::normalizeTitle)) continue
                    val year = info.optString("year")
                    if (root == null && year.isNotBlank() && item.optString("year") != year) continue
                    val season = item.optInt("season", 1).coerceAtLeast(1)
                    val number = item.optInt("episodeNummer", 1).coerceAtLeast(1)
                    if (found.putIfAbsent(id, Episode(id, season, number, "Episode $number")) == null) added++
                }
                if (records.isEmpty() || newIds == 0) break
            }
            if (added > 0) break
        }
        return found.values.sortedWith(compareBy(Episode::season, Episode::episode))
    }

    suspend fun info(videoId: String): JSONObject {
        requireId(videoId)
        val data = get("/api/android/allVideoInfo/id/$videoId")
        return items(data).firstOrNull() ?: data.optJSONObject("info") ?: data
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
        val id = numericId(item.optString("nb", item.optString("id"))) ?: return null
        val root = numericId(item.optString("rootSeries"))
        val finalId = if (series && root != null && root != "0") root else id
        val image = listOf("imgObjUrl", "imgMediumThumbObjUrl", "imgThumbObjUrl")
            .firstNotNullOfOrNull { item.optString(it).takeIf(String::isNotBlank) }
        val poster = image?.let { if (isHttp(it)) it else baseUrl.trimEnd('/') + "/" + it.trimStart('/') }
        return Entry(finalId, title(item), poster, item.optString("year").ifBlank { null }, series,
            listOf("en_title", "ar_title", "other_title", "title").map { item.optString(it) }.filter { it.isNotBlank() }.distinct())
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
