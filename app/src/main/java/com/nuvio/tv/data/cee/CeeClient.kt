package com.nuvio.tv.data.cee

import kotlinx.coroutines.Dispatchers
import com.nuvio.tv.core.network.awaitProviderBody
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct Cee API client.
 *
 * Does not require Node.js or an external local server.
 * Playback URLs are requested fresh because they may expire.
 */
class CeeClient(
    http: OkHttpClient,
    private val fetchOverride: (suspend (String) -> String)? = null,

) {
    // Level is read from the active profile for each API request.
    companion object {
        private const val API = "https://cee.buzz/api/android"
    }

    private val transport = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.build()

    private suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        fetchOverride?.let { return@withContext it(path) }
        val request = Request.Builder()
            .url("$API/$path")
            .header("Accept", "application/json")
            .get()
            .build()

        transport.newCall(request).awaitProviderBody()
    }

    suspend fun homeGroups(language: String = "ar"): JSONArray {
        val lang = if (language == "en") "en" else "ar"
        val response = JSONObject(fetch("videoGroups/lang/$lang/level/0"))
        return response.optJSONArray("groups") ?: JSONArray()
    }

    suspend fun homeGroupPage(groupId: String, page: Int = 0): JSONArray {
        require(groupId.matches(Regex("[0-9]+"))) { "Invalid Cee group ID" }
        return JSONArray(
            fetch(
                "videoListPagination/groupID/$groupId/level/0/itemsPerPage/12/page/${page.coerceAtLeast(0)}"
            )
        )
    }
    suspend fun latestMovies(page: Int = 0): JSONArray =
        JSONArray(fetch("latestMovies/level/0/itemsPerPage/24/page/${page.coerceAtLeast(0)}/"))

    suspend fun latestSeries(page: Int = 0): JSONArray =
        JSONArray(fetch("latestSeries/level/0/itemsPerPage/24/page/${page.coerceAtLeast(0)}/"))

    suspend fun videoInfo(id: String): JSONObject =
        JSONObject(fetch("allVideoInfo/id/${requireVideoId(id)}"))

    suspend fun streams(id: String): JSONArray =
        JSONArray(fetch("transcoddedFiles/id/${requireVideoId(id)}"))

    suspend fun subtitles(id: String): JSONObject {
        val raw = fetch("translationFiles/id/${requireVideoId(id)}")
        return if (raw.trimStart().startsWith("[")) JSONArray(raw).optJSONObject(0) ?: JSONObject()
            else JSONObject(raw)
    }

    data class Episode(val videoId: String, val season: Int, val episode: Int)

    suspend fun episodes(rootId: String): List<Episode> {
        val rows = JSONArray(fetch("videoSeason/id/${requireVideoId(rootId)}"))
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("nb").takeIf { it.matches(Regex("[0-9]+")) } ?: return@mapNotNull null
            val root = item.optString("rootSeries")
            if (id != rootId && root != rootId) return@mapNotNull null
            val season = item.optString("season").toIntOrNull() ?: return@mapNotNull null
            val episode = item.optString("episodeNummer").toIntOrNull() ?: return@mapNotNull null
            if (season < 1 || episode < 1) return@mapNotNull null
            Episode(id, season, episode)
        }.distinctBy { it.videoId }.sortedWith(compareBy(Episode::season, Episode::episode))
    }

    suspend fun search(keyword: String, series: Boolean = false, page: Int = 0): JSONArray {
        val encoded = java.net.URLEncoder.encode(keyword.trim(), "UTF-8")
        val maxYear = java.time.Year.now().value + 1
        return JSONArray(
            fetch(
                "AdvancedSearch?level=0&videoTitle=$encoded" +
                    "&staffTitle=&page=${page.coerceAtLeast(0)}&year=1900%2C$maxYear&type=${if (series) "series" else "movies"}"
            )
        )
    }

    suspend fun discover(
        series: Boolean = false,
        page: Int = 0,
        startYear: Int = 1900,
        endYear: Int = java.time.Year.now().value + 1,
        category: String? = null,

    ): JSONArray {
        require(startYear in 1900..2100 && endYear in startYear..2100) {
            "Invalid Cee year range"
        }
        val genre = category?.trim()?.takeIf { it.isNotEmpty() }
        val categoryArg = genre?.let {
            "&category=${java.net.URLEncoder.encode(it, "UTF-8")}"
        } ?: ""
        return JSONArray(
            fetch(
                "AdvancedSearch?level=0&videoTitle=&staffTitle=" +
                    "&page=${page.coerceAtLeast(0)}" +
                    "&year=$startYear%2C$endYear" +
                    "&type=${if (series) "series" else "movies"}" +
                    categoryArg
            )
        )
    }
    private fun requireVideoId(id: String): String {
        require(id.matches(Regex("[0-9]+"))) {
            "Invalid Cee video ID"
        }
        return id
    }
}
