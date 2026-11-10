package com.nuvio.tv.data.cee
import com.nuvio.android.cinemana.TmdbCredentials
import com.nuvio.tv.data.remote.api.AddonApi
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.json.JSONArray
import java.io.IOException

class CeeManifestTest {
    @Before fun isolateProviderNetwork() {
        mockkConstructor(CeeClient::class)
        coEvery { anyConstructed<CeeClient>().homeGroups(any()) } returns JSONArray(
            """[{"groupsID":"7","title":"Series fixture","content":[{"kind":2}]}]"""
        )
    }
    @After fun restoreProviderConstructor() { unmockkConstructor(CeeClient::class) }

    @Test fun metadataOnlyClaimsCeeIdsWhileStreamsAcceptExternalMovieIds() = runBlocking {
        val api = CeeAddonApi(mockk<AddonApi>(), OkHttpClient(), { TmdbCredentials("", "ar") })
        val manifest = api.getManifest("${CeeAddonApi.BASE_URL}/manifest.json").body()!!
        val resources = manifest.resources.filterIsInstance<Map<*, *>>()
        assertEquals(listOf("cee:"), resources.single { it["name"] == "meta" }["idPrefixes"])
        val streamPrefixes = resources.single { it["name"] == "stream" }["idPrefixes"] as List<*>
        assertTrue(streamPrefixes.contains("tt"))
        assertTrue(streamPrefixes.contains("tmdb:"))
        assertTrue(manifest.catalogs.any { it.id == "cee-home-7" && it.type == "series" })
    }

    @Test fun providerFailurePropagatesInsteadOfReplacingAnExistingManifestWithAnEmptyOne() = runBlocking {
        coEvery { anyConstructed<CeeClient>().homeGroups(any()) } throws IOException("fixture unavailable")
        val api = CeeAddonApi(mockk<AddonApi>(), OkHttpClient(), { TmdbCredentials("", "ar") })
        try {
            api.getManifest("${CeeAddonApi.BASE_URL}/manifest.json")
            fail("A provider failure must not become a successful empty manifest")
        } catch (expected: IOException) { assertEquals("fixture unavailable", expected.message) }
    }
}
