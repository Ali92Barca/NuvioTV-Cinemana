package com.nuvio.tv.data.cee

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CeeSeriesTest {
    @Test fun seriesMetadataContainsSeasonAndEpisodeIdsEvenWhenInfoKindIsWrong() = runBlocking {
        val client = CeeClient(OkHttpClient()) { path ->
            when {
                path.startsWith("allVideoInfo") -> """{"nb":"10","kind":"1","en_title":"Show"}"""
                path.startsWith("videoSeason") -> """[
                  {"nb":"10","rootSeries":"0","season":"1","episodeNummer":"1"},
                  {"nb":"21","rootSeries":"10","season":"2","episodeNummer":"1"},
                  {"nb":"22","rootSeries":"10","season":"2","episodeNummer":"2"},
                  {"nb":"99","rootSeries":"90","season":"2","episodeNummer":"2"}]
                """
                else -> error(path)
            }
        }
        val meta = CeeResourceAdapter(client).meta("10", "series").meta!!
        assertEquals("series", meta.type)
        assertEquals(listOf("cee:10:1:1", "cee:10:2:1", "cee:10:2:2"), meta.videos!!.map { it.id })
        assertEquals(2, meta.videos!!.last().season)
        assertEquals(2, meta.videos!!.last().episode)
    }
    @Test fun requestParserRequiresExactEpisodeAndCorrectMediaKind() {
        assertNull(CeeVideoRequest.parse("cee:10", true))
        assertNull(CeeVideoRequest.parse("tt123", true))
        assertNull(CeeVideoRequest.parse("tmdb:movie:10:1:2", true))
        assertNull(CeeVideoRequest.parse("tt123:0:2", true))
        assertNull(CeeVideoRequest.parse("tt123:1:2", false))
        assertEquals("tt123", CeeVideoRequest.parse("imdb:tt123:2:3", true)!!.externalId!!.removePrefix("imdb:"))
        assertEquals("tmdb:tv:10", CeeVideoRequest.parse("tmdb:tv:10:2:3", true)!!.externalId)
        assertEquals(3, CeeVideoRequest.parse("cee:10:2:3", true)!!.episode)
    }
    @Test fun seriesSearchUsesSeriesEndpointAndGroupsEpisodeCandidatesByRoot() = runBlocking {
        var request = ""
        val client = CeeClient(OkHttpClient()) { path -> request = path; "[]" }
        client.search("Show", series = true)
        assertTrue(request.endsWith("type=series"))
        val candidates = org.json.JSONArray("""[
          {"nb":"10","rootSeries":"0","kind":"2","year":"2020","en_title":"Show","imdbUrlRef":"tt123"},
          {"nb":"21","rootSeries":"10","kind":"2","year":"2024","en_title":"Show","imdbUrlRef":"tt123"}]
        """)
        val query = CeeResolver.Query("tt123", null, "Show", 2020, "series")
        assertEquals("10", CeeResolver.match(query, (0 until candidates.length()).map { candidates.getJSONObject(it) }))
    }
    @Test fun latestFeedResolvesVerifiedSeriesOmittedFromSearchIndex() = runBlocking {
        val client = CeeClient(OkHttpClient()) { path ->
            if (path.startsWith("AdvancedSearch")) "[]"
            else if (path.startsWith("latestSeries"))
                """[{"nb":"10","kind":"2","year":"2025","en_title":"MobLand","imdbUrlRef":"tt31510819"}]"""
            else error(path)
        }
        assertEquals("10", CeeResolver.resolve(client,
            CeeResolver.Query("tt31510819", null, "MobLand", 2025, "series")))
    }
    @Test fun externalSeriesLookupUsesTvMetadataInsteadOfMovieMetadata() = runBlocking {
        val paths = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths.add(path)
            val body = if ("/find/" in path) """{"tv_results":[{"id":88}],"movie_results":[{"id":99}]}"""
                else """{"name":"Show","original_name":"Show","first_air_date":"2020-01-01"}"""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val query = CeeTmdbLookup(http) { com.nuvio.android.cinemana.TmdbCredentials("test", "ar") }
            .query("tt123", true)!!
        assertEquals("series", query.type)
        assertEquals(2020, query.year)
        assertEquals(listOf("/3/find/tt123", "/3/tv/88", "/3/tv/88"), paths)
    }
}
