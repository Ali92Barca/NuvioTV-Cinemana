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
