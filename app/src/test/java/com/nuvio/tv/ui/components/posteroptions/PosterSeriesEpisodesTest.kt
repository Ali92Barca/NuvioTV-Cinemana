package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.cee.CeeAddonApi
import com.nuvio.tv.data.cinemana.CinemanaAddonApi
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterSeriesEpisodesTest {

    @Test
    fun `canonical action uses the original catalog id and falls back when metadata has no episodes`() = runTest {
        val repository = mockk<MetaRepository>()
        val emptyMeta = mockk<Meta>()
        val episode = Video("tt39713912:1:1", "Episode 1", null, null, season = 1, episode = 1, overview = null)
        every { emptyMeta.watchableEpisodes() } returns emptyList()
        every { repository.getMeta("https://catalog.example", "series", "tmdb:123") } returns
            flowOf(NetworkResult.Success(emptyMeta))
        every { repository.getMetaFromPrimaryAddon("series", "tt39713912") } returns
            flowOf(NetworkResult.Success(emptyMeta))
        every { repository.getMetaFromAllAddons("series", "tt39713912") } returns
            flowOf(NetworkResult.Error("Unavailable"))

        val result = repository.fetchPosterSeriesEpisodes(
            "series",
            "tt39713912",
            originalId = "tmdb:123",
            sourceAddonBaseUrl = "https://catalog.example",
            canonicalFallback = { listOf(episode, episode.copy(season = 0)) }
        )

        assertEquals(listOf(episode), result)
        verify(exactly = 1) { repository.getMeta("https://catalog.example", "series", "tmdb:123") }
    }
    @Test
    fun `native catalogs load their own watchable episodes`() = runTest {
        val repository = mockk<MetaRepository>()
        val episode = mockk<Video>()

        for ((id, baseUrl) in listOf(
            "cee:123" to CeeAddonApi.BASE_URL,
            "cinemana-series:456" to CinemanaAddonApi.BASE_URL
        )) {
            val meta = mockk<Meta>()
            every { meta.id } returns id
            every { meta.watchableEpisodes() } returns listOf(episode)
            every { repository.getMeta(baseUrl, "series", id) } returns
                flowOf(NetworkResult.Loading, NetworkResult.Success(meta))

            var matched: Meta? = null
            val episodes = repository.fetchPosterSeriesEpisodes("tv", id) { matched = it }
            assertEquals(listOf(episode), episodes)
            assertEquals(meta, matched)
        }
        verify(exactly = 0) { repository.getMetaFromPrimaryAddon(any(), any()) }
    }

    @Test
    fun `native provider error cannot fall back to a whole series action`() = runTest {
        val repository = mockk<MetaRepository>()
        every { repository.getMeta(CeeAddonApi.BASE_URL, "series", "cee:123") } returns
            flowOf(NetworkResult.Error("Unavailable"))

        val failure = runCatching { repository.fetchPosterSeriesEpisodes("series", "cee:123") }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
    }
}
