package com.nuvio.tv.data.cee
import com.nuvio.android.cinemana.TmdbCredentials
import com.nuvio.tv.data.remote.api.AddonApi
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CeeManifestTest {
    @Test fun metadataOnlyClaimsCeeIdsWhileStreamsAcceptExternalMovieIds() = runBlocking {
        val api = CeeAddonApi(mockk<AddonApi>(), OkHttpClient(), { TmdbCredentials("", "ar") })
        val manifest = api.getManifest("${CeeAddonApi.BASE_URL}/manifest.json").body()!!
        val resources = manifest.resources.filterIsInstance<Map<*, *>>()
        assertEquals(listOf("cee:"), resources.single { it["name"] == "meta" }["idPrefixes"])
        val streamPrefixes = resources.single { it["name"] == "stream" }["idPrefixes"] as List<*>
        assertTrue(streamPrefixes.contains("tt"))
        assertTrue(streamPrefixes.contains("tmdb:"))
    }
}
