package com.nuvio.tv.data.iptv

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.*
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ExternalAddonBridgeTest {
    @Test fun verifiedEpisodeSurvivesTimeoutInALaterSearchCatalog() = retainedEpisode(true)
    @Test fun verifiedEpisodeSurvivesNetworkFailureInALaterSearchCatalog() = retainedEpisode(false)
    private fun retainedEpisode(timeout: Boolean) = kotlinx.coroutines.test.runTest {
        val canonical = canonical()
        every { canonical.videos } returns episodes()
        val addon = seriesAddon().copy(catalogs = seriesAddon().catalogs + CatalogDescriptor(
            ContentType.SERIES, id = "slow", name = "Slow", extra = listOf(CatalogExtra("search"))))
        coEvery { api.getCatalog(match { it.contains("/all/") }) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_42", "series", "Example", releaseInfo = "2020"))))
        coEvery { api.getCatalog(match { it.contains("/slow/") }) } coAnswers {
            if (!timeout) throw java.io.IOException("Catalog unavailable")
            kotlinx.coroutines.delay(30_000)
            Response.success(CatalogResponseDto(emptyList()))
        }
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xt_s_src_42", "series", "Example", videos = (1..2).map {
                VideoDto("xt_s_src_42:1:$it", title = "Episode $it", season = 1, episode = it)
            })))
        assertEquals(listOf("xt_s_src_42:1:1"), bridge.resolveAll(addon, "series", canonical, 1, 1))
        coVerify(exactly = 1) { api.getMeta(any()) }
    }
    @Test fun tmdbTurkishIdentityEnablesCountBasedNativeResolution() = runBlocking {
        // CI supplies the registered key; all requests in this test use mocks.
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val tmdb = mockk<com.nuvio.tv.data.remote.api.TmdbApi>()
        coEvery { tmdb.getTvDetails(42, any(), any()) } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbDetailsResponse(42, name = "المدينة البعيدة", originalLanguage = "tr"))
        coEvery { tmdb.getTvAlternativeTitles(42, any()) } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbAlternativeTitlesResponse())
        val canonical = canonical()
        every { canonical.name } returns "المدينة البعيدة"
        every { canonical.videos } returns episodes(season = 2, count = 35).map {
            it.copy(title = "", released = "2025-11-03")
        }
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", releaseInfo = "2018"))))
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", videos = (29..63).map {
                VideoDto("xt_s_src_1:2:$it", title = "AR-TR-S - Descriptive native label", released = "2026-01-05", season = 2, episode = it)
            })))
        val addon = seriesAddon().copy(catalogs = seriesAddon().catalogs + CatalogDescriptor(
            ContentType.SERIES, id = "slow", name = "Slow", extra = listOf(CatalogExtra("search"))))
        assertEquals("xt_s_src_1:2:36", ExternalAddonBridge(api, tmdb).resolve(addon, "series", canonical, 2, 8))
        coVerify(exactly = 1) { tmdb.getTvDetails(42, any(), any()) }
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }
    @Test fun verifiedTurkishCountMappingDoesNotRequireMatchingEpisodeTitlesOrDates() {
        val expected = episodes().first().copy(released = "2026-01-01")
        val actual = VideoDto("xt_s_src_42:2:36", title = "Descriptive Turkish title",
            released = "2026-01-05", season = 2, episode = 36)
        assertTrue(bridge.compatible(expected, actual, arabic = false, count = 35, turkish = true))
        assertFalse(bridge.compatible(expected, actual.copy(id = ""), arabic = false, count = 35, turkish = true))
        assertFalse(bridge.compatible(expected, actual, arabic = false, count = 35))
    }
    @Test fun arabicLanguageBypassesCountTitleAndDateConditionsButDoesNotChangePartMapping() {
        assertTrue(bridge.acceptsLayout("ar", setOf(1, 2), setOf(1, 2, 3, 4)))
        assertFalse(bridge.acceptsLayout("tr", setOf(1, 2), setOf(1, 2, 3, 4)))
        assertTrue(bridge.acceptsLayout("tr", setOf(1, 2), setOf(1, 2)))
        assertTrue(bridge.acceptsLayout("tr", (1..35).toSet(), (1..8).toSet(), (1..8).toSet()))
        assertFalse(bridge.acceptsLayout("tr", (1..35).toSet(), (1..7).toSet(), (1..8).toSet()))
        val expected = episodes().first().copy(released = "2026-01-01")
        val actual = VideoDto("xt_s_src_42:1:1", title = "Different title", released = "2025-01-01", season = 1, episode = 1)
        assertTrue(bridge.compatible(expected, actual, arabic = true, count = 2))
        assertFalse(bridge.compatible(expected, actual, arabic = false, count = 2))
        assertTrue(bridge.compatible(expected, actual.copy(title = "AR - Some Show مترجم - S01E01", released = null), arabic = false, count = 2))
        assertEquals("ليل البنفسج" to 2, bridge.splitPart("ليل البنفسج 2"))
        assertEquals("Some Show" to 3, bridge.splitPart("Some Show 3"))
    }
    private fun seriesAddon() = addon().copy(catalogs = listOf(CatalogDescriptor(ContentType.SERIES,
        id = "all", name = "All", extra = listOf(CatalogExtra("search")))))
    private fun episodes(title: String = "الحلقة", season: Int = 1, count: Int = 2) = (1..count).map {
        Video("tt1234567:$season:$it", "$title $it", null, null, season = season, episode = it, overview = null)
    }
    @Test fun bilingualTranslatedEditionIsSelectedByMatchingEpisodeLayout() = runBlocking {
        val canonical = canonical()
        every { canonical.name } returns "المدينة البعيدة"
        every { canonical.releaseInfo } returns "2024"
        every { canonical.videos } returns episodes()
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", releaseInfo = "2024"),
            MetaPreviewDto("xt_s_src_2", "series", "المدينة البعيدة"))))
        coEvery { api.getMeta(match { it.contains("xt_s_src_1") }) } returns Response.success(MetaResponseDto(
            MetaDto("xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", videos = (1..2).map {
                VideoDto("xt_s_src_1:1:$it", title = "AR-TR-S - Uzak Şehir - S01E0$it - Episode $it", season = 1, episode = it)
            })))
        coEvery { api.getMeta(match { it.contains("xt_s_src_2") }) } returns Response.success(MetaResponseDto(
            MetaDto("xt_s_src_2", "series", "المدينة البعيدة", videos = (1..10).map {
                VideoDto("xt_s_src_2:1:$it", title = "Episode $it", season = 1, episode = it)
            })))
        assertEquals(listOf("xt_s_src_1:1:1"), bridge.resolveAll(seriesAddon(), "series", canonical, 1, 1))
    }
    @Test fun separateNumberedWorkUsesNativePartWithoutChangingCanonicalEpisodeNumber() = runBlocking {
        val canonical = canonical()
        every { canonical.name } returns "ليل البنفسج 2"
        every { canonical.videos } returns episodes()
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_3", "series", "ليل البنفسج", imdbId = "tt111", tmdbId = "111"))))
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(
            MetaDto("xt_s_src_3", "series", "ليل البنفسج", imdbId = "tt111", tmdbId = "111", videos = (1..2).map {
                VideoDto("xt_s_src_3:2:$it", title = "AR - ليل البنفسج - S02E0$it - Episode $it", season = 2, episode = it)
            })))
        assertEquals("xt_s_src_3:2:1", bridge.resolve(seriesAddon(), "series", canonical, 1, 1))
    }
    @Test fun cumulativeSeasonsResolvePastEpisodeSevenWithOriginalXtreamIds() = runBlocking {
        val canonical = canonical()
        every { canonical.name } returns "المدينة البعيدة"
        every { canonical.releaseInfo } returns "2024"
        every { canonical.videos } returns (episodes(season = 1, count = 28) +
            episodes(season = 2, count = 35) + episodes(season = 3, count = 35).map {
                if (it.episode!! > 8) it.copy(released = "2099-01-01") else it
            })
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", releaseInfo = "2024"))))
        val source = (1..28).map { 1 to it } + (29..63).map { 2 to it } + (64..71).map { 3 to it }
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", videos = source.map { (s, e) ->
                VideoDto("xt_s_src_1:$s:$e", title = "Uzak Şehir - S${s}E${e}", season = s, episode = e)
            })))
        assertEquals("xt_s_src_1:2:36", bridge.resolve(seriesAddon(), "series", canonical, 2, 8))
        assertEquals("xt_s_src_1:2:63", bridge.resolve(seriesAddon(), "series", canonical, 2, 35))
        assertEquals("xt_s_src_1:3:71", bridge.resolve(seriesAddon(), "series", canonical, 3, 8))
        assertNull(bridge.resolve(seriesAddon(), "series", canonical, 3, 9))
    }
    private val api = mockk<AddonApi>()
    private val bridge = ExternalAddonBridge(api)
    private fun addon(base: String = "https://example.test/config?token=private") = Addon(
        id = "test", name = "Better Stream", version = "14", description = null, logo = null,
        baseUrl = base, types = listOf(ContentType.MOVIE, ContentType.SERIES), resources = emptyList(),
        catalogs = listOf(CatalogDescriptor(ContentType.MOVIE, id = "all", name = "All",
            extra = listOf(CatalogExtra("search")))))
    private fun canonical(): Meta = mockk<Meta> {
        every { id } returns "tt1234567"
        every { name } returns "Example"
        every { releaseInfo } returns "2020"
        every { released } returns null
        every { imdbId } returns "tt1234567"
        every { tmdbId } returns "42"
    }
    @Test fun nativeIdIsPreservedAndSubscriptionQuerySurvives() = runBlocking {
        coEvery { api.getCatalog("https://example.test/config/catalog/movie/all/search=Example.json?token=private") } returns
            Response.success(CatalogResponseDto(listOf(MetaPreviewDto(id = "xtream:vod:987", type = "movie", name = "Example", releaseInfo = "2020"))))
        assertEquals("xtream:vod:987", bridge.resolve(addon(), "movie", canonical(), null, null))
        assertEquals("xtream:vod:987", bridge.resolve(addon(), "movie", canonical(), null, null))
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }
    @Test fun differentSubscriptionsDoNotShareSearchResults() = runBlocking {
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto())
        bridge.resolve(addon(), "movie", canonical(), null, null)
        bridge.resolve(addon("https://example.test/config?token=another"), "movie", canonical(), null, null)
        coVerify(exactly = 2) { api.getCatalog(any()) }
    }
    @Test fun ambiguousSearchDoesNotCallMetaOrStreams() = runBlocking {
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto((1..17).map {
            MetaPreviewDto(id = "xtream:$it", type = "movie", name = "Example", releaseInfo = "2020")
        }))
        assertNull(bridge.resolve(addon(), "movie", canonical(), null, null))
        coVerify(exactly = 0) { api.getMeta(any()) }
        coVerify(exactly = 0) { api.getStreams(any()) }
    }
    @Test fun episodeRequiresMatchingTitleAndUniqueNumber() = runBlocking {
        val source = addon().copy(catalogs = listOf(CatalogDescriptor(ContentType.SERIES, id = "all", name = "All",
            extra = listOf(CatalogExtra("search")))))
        val canonical = canonical()
        every { canonical.videos } returns listOf(Video("tt1234567:1:2", "The Reunion", "2020-02-01", null,
            season = 1, episode = 2, overview = null))
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xtream:series:9", "series", "Example", releaseInfo = "2020"))))
        val right = VideoDto("xtream:series:9:1:2", title = "The Reunion", released = "2020-02-01", season = 1, episode = 2)
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xtream:series:9", "series", "Example", videos = listOf(right.copy(title = "Another Episode")))) )
        assertNull(bridge.resolve(source, "series", canonical, 1, 2))
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xtream:series:9", "series", "Example", videos = listOf(right, right.copy(id = "duplicate")))))
        assertNull(bridge.resolve(source, "series", canonical, 1, 2))
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xtream:series:9", "series", "Example", videos = listOf(right))))
        assertEquals(right.id, bridge.resolve(source, "series", canonical, 1, 2))
    }
}
