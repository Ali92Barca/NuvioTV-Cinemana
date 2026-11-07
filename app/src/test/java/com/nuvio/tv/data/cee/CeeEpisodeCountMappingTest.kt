package com.nuvio.tv.data.cee

import com.nuvio.android.cinemana.TmdbCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CeeEpisodeCountMappingTest {
    @Test
    fun equalCountsMatchWithoutEpisodeRuntimeRequests() = runTest {
        for (count in listOf(15, 30)) {
            val requests = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                requests.add(chain.request().url.encodedPath)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body("""{"seasons":[{"season_number":1,"episode_count":$count}]}""".toResponseBody())
                    .build()
            }.build()
            val source = (1..count).map { CeeClient.Episode((1000 + it).toString(), 1, it) }
            val mapped = CeeTmdbLookup(http) { TmdbCredentials("test", "ar") }
                .matchEpisodeLayout(88, source)
            assertEquals(source, mapped)
            assertEquals(listOf("/3/tv/88"), requests)
        }
    }

    @Test
    fun equalTotalsMapSeasonCoordinatesAndPreserveSourceVideoIds() {
        val source = (1..15).map { CeeClient.Episode((1000 + it).toString(), 1, it) }
        val mapped = mapSharedEpisodes(source, mapOf(1 to 5, 2 to 10))!!
        assertEquals(source.map { it.videoId }, mapped.map { it.videoId })
        assertEquals(2 to 1, mapped[5].let { it.season to it.episode })
        assertEquals(2 to 10, mapped.last().let { it.season to it.episode })
    }

    @Test
    fun differentTotalsDoNotUseTheCountOverride() {
        val source = (1..53).map { CeeClient.Episode(it.toString(), 1, it) }
        assertNull(mapSharedEpisodes(source, mapOf(1 to 12, 2 to 26, 3 to 31)))
    }

    @Test
    fun originalArabicBypassesCountAndMissingRuntimeRequirements() = runTest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"original_language":"ar","seasons":[]}""".toResponseBody())
                .build()
        }.build()
        val source = (1..30).map { CeeClient.Episode(it.toString(), 1, it) }
        val mapped = CeeTmdbLookup(http) { TmdbCredentials("test", "ar") }
            .matchEpisodeLayout(222888, source)
        assertEquals(source, mapped)
    }

    @Test
    fun arabicDisplayTitleCannotBypassTheOriginalLanguagePolicy() {
        val source = (1..53).map { CeeClient.Episode(it.toString(), 1, it) }
        val counts = mapOf(1 to 12, 2 to 26, 3 to 31)
        assertNull(mapSharedEpisodes(source, counts, "tr"))
        assertNull(mapSharedEpisodes(source, counts, null))
        assertNotNull(mapSharedEpisodes(source, counts, "ar"))
    }

    @Test
    fun duplicateOrMissingEpisodeDefinitionsCannotCreateABijection() {
        val source = listOf(CeeClient.Episode("10", 1, 1), CeeClient.Episode("11", 1, 1))
        assertNull(mapSharedEpisodes(source, mapOf(1 to 2)))
        assertNull(mapSharedEpisodes(emptyList(), emptyMap()))
        assertNull(mapSharedEpisodes(listOf(CeeClient.Episode("10", 1, 1)), mapOf(1 to 0)))
    }
}
