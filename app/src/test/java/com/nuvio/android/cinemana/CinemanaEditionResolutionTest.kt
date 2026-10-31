package com.nuvio.android.cinemana

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemanaEditionResolutionTest {
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
