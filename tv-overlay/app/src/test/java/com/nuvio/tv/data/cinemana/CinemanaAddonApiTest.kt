package com.nuvio.tv.data.cinemana

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class CinemanaAddonApiTest {
    private val delegate = mockk<AddonApi>()
    private val api = CinemanaAddonApi(delegate, Moshi.Builder().add(KotlinJsonAdapterFactory()).build(), OkHttpClient())
    @Test fun builtinManifestDecodesUsingTvDtosWithoutNetwork() = runBlocking {
        val manifest = api.getManifest("${CinemanaAddonApi.BASE_URL}/manifest.json").body()!!
        assertEquals("org.nuvio.cinemana.builtin", manifest.id)
        assertTrue(manifest.catalogs.any { it.type == "movie" })
        assertTrue(manifest.catalogs.any { it.type == "series" })
        assertTrue(manifest.resources.isNotEmpty())
        coVerify(exactly = 0) { delegate.getManifest(any()) }
    }
    @Test fun externalProvidersContinueThroughRetrofit() = runBlocking {
        val url = "https://example.org/stream/movie/tt123.json"
        val response = Response.success(StreamResponseDto(emptyList()))
        coEvery { delegate.getStreams(url) } returns response
        assertSame(response, api.getStreams(url))
        coVerify(exactly = 1) { delegate.getStreams(url) }
    }
}
