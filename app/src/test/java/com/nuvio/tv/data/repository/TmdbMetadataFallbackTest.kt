package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TmdbMetadataFallbackTest {
    @Test fun explicitVioletIdentityBuildsBothSeasonsAndKeepsOriginalLookupId() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val api=mockk<TmdbApi>()
        coEvery { api.getTvDetails(313314,any(),any()) } returns Response.success(TmdbDetailsResponse(313314,name="ليل البنفسج",numberOfSeasons=2,originalLanguage="ar"))
        coEvery { api.getTvSeasonDetails(313314,any(),any(),any()) } answers {
            val s=secondArg<Int>()
            Response.success(TmdbSeasonResponse(s,(1..if(s==1)10 else 6).map { TmdbEpisode(episodeNumber=it,name="Episode $it") }))
        }
        val fallback=TmdbMetadataFallback(api)
        val meta=fallback.load("series","tmdb:313314")!!
        assertEquals("tmdb:313314",meta.id)
        assertEquals("313314",meta.tmdbId)
        assertEquals(16,meta.videos.size)
        assertEquals("tmdb:313314:2:1",meta.videos.first { it.season==2 }.id)
        assertNull(fallback.load("series","xt_s_src_42"))
        assertNull(fallback.load("movie","tmdb:313314"))
        coVerify(exactly=1) { api.getTvDetails(any(),any(),any()) }
    }
    @Test fun incompleteOrDuplicateSeasonDoesNotBecomeACompleteCachedLayout() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val api=mockk<TmdbApi>()
        coEvery { api.getTvDetails(42,any(),any()) } returns Response.success(TmdbDetailsResponse(42,name="Fixture",numberOfSeasons=1))
        coEvery { api.getTvSeasonDetails(42,1,any(),any()) } returns Response.success(TmdbSeasonResponse(1,listOf(TmdbEpisode(episodeNumber=1),TmdbEpisode(episodeNumber=1))))
        assertNull(TmdbMetadataFallback(api).load("series","tmdb:42"))
    }
}
