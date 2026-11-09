package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class MetaRepositoryAddonCacheTest {
    @Test fun `failed provider metadata uses explicit TMDB fallback once and caches the result`() = runTest {
        val api=mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(null))
        val primary=com.nuvio.tv.domain.model.Addon(id="primary",name="Primary",version="1",description=null,logo=null,
            baseUrl="https://primary.example",catalogs=emptyList(),types=listOf(com.nuvio.tv.domain.model.ContentType.SERIES),
            resources=listOf(com.nuvio.tv.domain.model.AddonResource("meta",listOf("series"),listOf("tmdb:"))))
        val fallback=mockk<TmdbMetadataFallback>()
        val meta=MetaDto("tmdb:313314","series","Violet",tmdbId="313314").toDomain("Episode")
        coEvery { fallback.load("series","tmdb:313314") } returns meta
        val repo=newRepository(api,primary,fallback)
        assertEquals(meta,(repo.getMetaFromAllAddons("series","tmdb:313314").last() as NetworkResult.Success).data)
        assertEquals(meta,repo.getCachedMeta("series","tmdb:313314"))
        repo.getMetaFromAllAddons("series","tmdb:313314").last()
        coVerify(exactly=1) { fallback.load(any(),any()) }
        coVerify(exactly=1) { api.getMeta(any()) }
    }

    private val contentId = "tt3711878"

    @Test fun `primary card metadata is visible to canonical stream searches`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("Primary Card")
        val primary = com.nuvio.tv.domain.model.Addon(id = "primary", name = "Primary", version = "1",
            description = null, logo = null, baseUrl = "https://primary.example", catalogs = emptyList(),
            types = listOf(com.nuvio.tv.domain.model.ContentType.SERIES),
            resources = listOf(com.nuvio.tv.domain.model.AddonResource("meta", listOf("series"), listOf("tt"))))
        val repository = newRepository(api, primary)
        repository.getMetaFromPrimaryAddon("series", contentId).last()
        assertEquals("Primary Card", repository.getCachedMeta("series", contentId)?.name)
        assertEquals("Primary Card", repository.getCachedMeta("tv", contentId)?.name)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `meta from one addon is not served for another addon with the same content id`() = runTest {
        val repository = newRepository(
            "https://cinemeta.example/meta/series/$contentId.json" to "Cinemeta Meta",
            "https://aiometadata.example/meta/series/$contentId.json" to "AioMetadata Meta"
        )

        val first = repository.getMeta("https://cinemeta.example", "series", contentId).last()
        val second = repository.getMeta("https://aiometadata.example", "series", contentId).last()

        assertEquals("Cinemeta Meta", (first as NetworkResult.Success).data.name)
        assertEquals("AioMetadata Meta", (second as NetworkResult.Success).data.name)
    }

    @Test
    fun `repeat request for the same addon is served from cache`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("Cached Meta")
        val repository = newRepository(api)

        val first = repository.getMeta("https://cinemeta.example", "series", contentId).last()
        val second = repository.getMeta("https://cinemeta.example", "series", contentId).last()

        assertEquals("Cached Meta", (first as NetworkResult.Success).data.name)
        assertEquals("Cached Meta", (second as NetworkResult.Success).data.name)
        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `trailing slash in addon base url maps to the same cache entry`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("Slash Meta")
        val repository = newRepository(api)

        repository.getMeta("https://cinemeta.example/", "series", contentId).last()
        repository.getMeta("https://cinemeta.example", "series", contentId).last()

        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    @Test
    fun `query-bearing base url maps to the same cache entry regardless of slash placement`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } returns metaResponse("Query Meta")
        val repository = newRepository(api)

        repository.getMeta("https://addon.example/cfg/?token=abc", "series", contentId).last()
        repository.getMeta("https://addon.example/cfg?token=abc", "series", contentId).last()

        coVerify(exactly = 1) { api.getMeta(any()) }
    }

    private fun newRepository(vararg responsesByUrl: Pair<String, String>): MetaRepositoryImpl {
        val api = mockk<AddonApi>()
        responsesByUrl.forEach { (url, name) ->
            coEvery { api.getMeta(url) } returns metaResponse(name)
        }
        return newRepository(api)
    }

    private fun newRepository(api: AddonApi, primary: com.nuvio.tv.domain.model.Addon? = null, fallback: TmdbMetadataFallback? = null): MetaRepositoryImpl {
        val context = mockk<Context> {
            every { getString(any()) } returns "Episode"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        every { addonRepository.getInstalledAddons() } returns kotlinx.coroutines.flow.flowOf(listOfNotNull(primary))
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            serverCatalog = mockk(relaxed = true),
            tmdbFallback = fallback
        )
    }

    private fun metaResponse(name: String): Response<MetaResponseDto> =
        Response.success(
            MetaResponseDto(
                meta = MetaDto(
                    id = contentId,
                    type = "series",
                    name = name
                )
            )
        )
}
