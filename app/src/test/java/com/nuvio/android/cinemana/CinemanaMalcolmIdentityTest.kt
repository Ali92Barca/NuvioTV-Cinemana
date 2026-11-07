package com.nuvio.android.cinemana

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemanaMalcolmIdentityTest {
    @Test fun verifiedImdbOutranksIncorrectProviderYear() {
        assertTrue(CinemanaIdentity.matches("tt0212671", "https://www.imdb.com/title/tt0212671/", true, 1920, 2000, 2006, true))
    }
    @Test fun conflictingImdbNeverFallsBackToMatchingTitleAndYear() {
        assertFalse(CinemanaIdentity.matches("tt0212671", "tt35466672", true, 2000, 2000, 2006, true))
    }
    @Test fun missingIdentityStillRequiresConservativeYearMatch() {
        assertFalse(CinemanaIdentity.matches("tt0212671", null, true, 1920, 2000, 2006, true))
    }
    @Test fun resolvesNuvioEpisodeToCorrectMalcolmVideoDespite1920Year() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = CinemanaClient(transport = { url ->
            calls.add(url)
            when {
                "AdvancedSearch" in url -> if ("page=0&" in url)
                    """[{"nb":"134165","kind":"2","rootSeries":"0","en_title":"Malcolm in the Middle","year":"1920","imdbUrlRef":"http://www.imdb.com/title/tt0212671/"}]""" else "[]"
                "videoSeason" in url -> """[
                  {"nb":"134165","rootSeries":"0","season":"1","episodeNummer":"1","en_title":"Malcolm in the Middle"},
                  {"nb":"143838","rootSeries":"134165","season":"3","episodeNummer":"19","en_title":"Malcolm in the Middle"}]
                """
                "allVideoInfo" in url -> """{"nb":"143838","en_title":"Malcolm in the Middle","rootSeries":"134165","translations":[]}"""
                "transcoddedFiles/id/143838" in url -> """[{"videoUrl":"https://video.test/143838.mp4","resolution":"1080p"}]"""
                else -> error("Unexpected provider request")
            }
        })
        val adapter = CinemanaResourceAdapter(client, { TmdbCredentials("fixture", "en") }, { url ->
            if ("/find/" in url) """{"tv_results":[{"id":2004}]}"""
            else """{"name":"Malcolm in the Middle","original_name":"Malcolm in the Middle","first_air_date":"2000-01-09","last_air_date":"2006-05-14","seasons":[{"season_number":3,"episode_count":22}],"external_ids":{"imdb_id":"tt0212671"}}"""
        })
        val streams = JSONObject(adapter.handle("https://cinemana.nuvio.invalid/stream/series/tt0212671:3:19.json")!!).getJSONArray("streams")
        assertEquals(1, streams.length())
        assertEquals("https://video.test/143838.mp4", streams.getJSONObject(0).getString("url"))
        assertFalse(calls.any { "transcoddedFiles/id/134165" in it })
    }
    @Test fun nativeMetadataUsesOneVerifiedFindInsteadOfMultiLocaleSearches() = runBlocking {
        var calls = 0
        val matcher = CinemanaTmdbMatcher("fixture", "en", fetch = { url ->
            calls++; assertTrue(url.contains("/find/tt0212671"))
            """{"tv_results":[{"id":2004,"name":"Malcolm in the Middle","first_air_date":"2000-01-09","poster_path":"/poster.jpg"}]}"""
        })
        val match = matcher.match(listOf("Malcolm in the Middle"), 1920, true, "tt0212671")!!
        assertEquals(2004, match.tmdbId); assertEquals(2000, match.year); assertEquals(1, calls)
    }
}
