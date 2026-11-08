package com.nuvio.android.cinemana

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemanaEditionResolutionTest {
    @Test fun originalTurkishAbiSharesAllFiveAvailableSecondSeasonEpisodesWithMissingTmdbRuntime() = runBlocking {
        assertTrue(CinemanaEditions.originalTurkishAudio(JSONObject("""{"language_lighttigerNb":"25"}""")))
        assertTrue(CinemanaEditions.originalTurkishAudio(JSONObject("""{"videoLanguages":{"en_title":"Turkish"}}""")))
        assertFalse(CinemanaEditions.originalTurkishAudio(JSONObject("""{"videoLanguages":{"en_title":"Arabic"}}""")))
        val episodes = org.json.JSONArray((1..23).map { number -> JSONObject()
            .put("nb", (100 + number).toString()).put("rootSeries", if (number == 1) "0" else "101")
            .put("en_title", "A.B.I.").put("season", "1").put("episodeNummer", number.toString()) })
        val client = CinemanaClient(transport = { url -> when {
            "allVideoInfo" in url -> """{"nb":"101","en_title":"A.B.I.","kind":"2","videoLanguages":{"en_title":"Turkish","ar_title":"التركية"},"imdbUrlRef":"https://www.imdb.com/title/tt38282411/"}"""
            "videoSeason" in url -> episodes.toString()
            "AdvancedSearch" in url -> "[]"
            else -> error("Unexpected native request")
        } })
        val adapter = CinemanaResourceAdapter(client, { TmdbCredentials("fixture", "en-US") }, { url -> when {
            "/find/" in url -> """{"tv_results":[{"id":306529,"name":"A.B.I."}]}"""
            "/season/" in url -> """{"episodes":[]}"""
            else -> """{"id":306529,"name":"A.B.I.","original_language":"tr","external_ids":{"imdb_id":"tt38282411"},"seasons":[{"season_number":1,"episode_count":18},{"season_number":2,"episode_count":7}]}"""
        } })
        val meta = JSONObject(adapter.handle("https://cinemana.nuvio.invalid/meta/series/cinemana-series:101.json")!!).getJSONObject("meta")
        assertTrue(meta.getBoolean("_sharedProgressCompatible"))
        assertFalse(meta.has("_verifiedPlaybackVideoIds"))
        val videos = meta.getJSONArray("videos")
        assertEquals(23, videos.length())
        assertEquals(2, videos.getJSONObject(18).getInt("season"))
        assertEquals(1, videos.getJSONObject(18).getInt("episode"))
        assertEquals("cinemana:119", videos.getJSONObject(18).getString("id"))
        assertEquals(5, videos.getJSONObject(22).getInt("episode"))
    }
    @Test fun artworkLookupSkipsEpisodeAndFullMetadataRequests() = runBlocking {
        val client = CinemanaClient(transport = { url ->
            check("allVideoInfo" in url) { "Unexpected native request: $url" }
            """{"nb":"12","en_title":"Fixture","imdbUrlRef":"https://www.imdb.com/title/tt12345/"}"""
        })
        val adapter = CinemanaResourceAdapter(client, { TmdbCredentials("fixture", "en-US") }, { url ->
            check("/find/" in url) { "Unexpected TMDB request: $url" }
            """{"tv_results":[{"id":345,"name":"Fixture"}],"movie_results":[]}"""
        })
        val url = "https://cinemana.nuvio.invalid/meta/series/cinemana-series:12.json?artworkOnly=1"
        val meta = JSONObject(adapter.handle(url)!!).getJSONObject("meta")
        assertEquals("345", meta.getString("_artworkTmdbId"))
        assertFalse(meta.has("videos"))
        assertFalse(meta.has("moviedb_id"))
    }

    @Test fun laterPageEditionSurvivesAlongsideOrdinaryEditionAndLinksStayFresh() = runBlocking {
        var links = 0
        var metadata = 0
        val client = CinemanaClient(transport = { url ->
            when {
                "AdvancedSearch" in url -> when {
                    "page=0&" in url -> """[{"nb":"1","en_title":"Example","year":"2020"}]"""
                    "page=1&" in url -> """[{"nb":"2","en_title":"Example Dubbed","year":"2020"}]"""
                    else -> "[]"
                }
                "transcoddedFiles" in url -> {
                    links++
                    """[{"videoUrl":"https://video.test/${url.substringAfterLast('/')}-$links","resolution":"1080p"}]"""
                }
                else -> """{"en_title":"Example"}"""
            }
        })
        val adapter = CinemanaResourceAdapter(client, { TmdbCredentials("key", "en-US") }, { url ->
            metadata++
            when {
                "translations" in url -> """{"translations":[]}"""
                "alternative_titles" in url -> """{"titles":[]}"""
                else -> """{"title":"Example","original_title":"Example","release_date":"2020-01-01"}"""
            }
        })
        val url = "https://cinemana.nuvio.invalid/stream/movie/tmdb:movie:9.json"
        val first = JSONObject(adapter.handle(url)!!).getJSONArray("streams")
        assertEquals(2, first.length())
        val calls = metadata
        assertEquals(1, calls)
        val second = JSONObject(adapter.handle(url)!!).getJSONArray("streams")
        assertEquals(2, second.length())
        assertEquals(calls, metadata)
        assertEquals(4, links)
        assertNotEquals(first.getJSONObject(0).getString("url"), second.getJSONObject(0).getString("url"))
    }
}
