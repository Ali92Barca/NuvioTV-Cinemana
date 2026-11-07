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
    @Test fun arabicLanguageBypassesCountTitleAndDateConditionsButDoesNotChangePartMapping() {
        assertTrue(bridge.acceptsLayout("ar", setOf(1, 2), setOf(1, 2, 3, 4)))
        assertFalse(bridge.acceptsLayout("tr", setOf(1, 2), setOf(1, 2, 3, 4)))
        assertTrue(bridge.acceptsLayout("tr", setOf(1, 2), setOf(1, 2)))
        val expected = episodes().first().copy(released = "2026-01-01")
        val actual = VideoDto("xt_s_src_42:1:1", title = "Different title", released = "2025-01-01", season = 1, episode = 1)
        assertTrue(bridge.compatible(expected, actual, arabic = true, count = 2))
        assertFalse(bridge.compatible(expected, actual, arabic = false, count = 2))
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
    private val api = mockk<AddonApi>()
    private val bridge = ExternalAddonBridge(api)
    private fun addon(base: String = "https://example.test/config?token=private") = Addon(
        id = "test", name = "Better Stream", version = "14", description = null, logo = null,
        baseUrl = base, types = listOf(ContentType.MOVIE, ContentType.SERIES), resources = emptyList(),
        catalogs = listOf(CatalogDescriptor(ContentType.MOVIE, id = "all", name = "All",
            extra = listOf(CatalogExtra("search")))))
    private fun canonical(): Meta = mockk<Meta> {
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
