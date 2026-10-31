package com.nuvio.tv.data.builtincache

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

class BuiltinPrefetchTest {
    @Test fun warmsBothProvidersWithTwoRequestsAtMostAndSkipsUnsupportedIds() = runBlocking {
        val api = mockk<AddonApi>()
        val repository = mockk<AddonRepository>()
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val addons = listOf("cee", "cinemana").map { name -> mockk<Addon> {
            every { enabled } returns true
            every { baseUrl } returns "https://$name.nuvio.invalid"
        } }
        every { repository.getInstalledAddons() } returns flowOf(addons)
        val active = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        coEvery { api.getStreams(any()) } coAnswers {
            calls.incrementAndGet(); val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            delay(20); active.decrementAndGet()
            Response.success(StreamResponseDto(emptyList()))
        }
        val warmer = BuiltinStreamPrefetcher(api, repository, profiles)
        warmer.warm(listOf(BuiltinStreamPrefetcher.Target("movie", "tt1"),
            BuiltinStreamPrefetcher.Target("series", "tt2:1:2"), BuiltinStreamPrefetcher.Target("movie", "unknown:3")))
        assertEquals(4, calls.get()); assertTrue(peak.get() <= 2)
        warmer.warm(listOf(BuiltinStreamPrefetcher.Target("movie", "tt1")), expectedProfile = 2)
        assertEquals(4, calls.get())
    }
    @Test fun seriesRootsAreNotPrefetchedAsEpisodes() {
        assertFalse(BuiltinStreamPrefetcher.supports("cee", BuiltinStreamPrefetcher.Target("series", "cee:10")))
        assertFalse(BuiltinStreamPrefetcher.supports("cinemana", BuiltinStreamPrefetcher.Target("series", "tt1")))
        assertTrue(BuiltinStreamPrefetcher.supports("cee", BuiltinStreamPrefetcher.Target("series", "tmdb:tv:1:2:3")))
    }
}
