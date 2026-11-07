package com.nuvio.tv.data.offline

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import com.nuvio.tv.domain.model.AddonStreams
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OfflineStreamRepositoryTest {
    @Test fun `a local storage failure cannot block official network sources`() = runTest {
        val delegate = mockk<StreamRepositoryImpl>()
        val downloads = mockk<OfflineDownloads>()
        every { downloads.matching(any()) } throws IllegalStateException("Unavailable storage")
        every { downloads.associateLegacy(any(), any()) } throws IllegalStateException("Unavailable storage")
        every { delegate.getStreamsFromAllAddons("movie", "tt1", null, null, false, null) } returns
            flowOf(NetworkResult.Success(listOf(AddonStreams("Remote", null, emptyList()))))
        val result = OfflineStreamRepository(delegate, downloads).getStreamsFromAllAddons("movie", "tt1", null, null, false, null)
            .toList().last()
        assertEquals(listOf("Remote"), (result as NetworkResult.Success).data.map { it.addonName })
    }
    @Test fun `deleted files are absent on the next stream search`() = runTest {
        val delegate = mockk<StreamRepositoryImpl>()
        val downloads = mockk<OfflineDownloads>()
        val item = OfflineItem("fixture", "Film", 1, emptyList())
        var present = true
        every { downloads.matching(any()) } answers { if (present) listOf(item) else emptyList() }
        every { downloads.associateLegacy(any(), any()) } returns Unit
        every { delegate.getStreamsFromAllAddons("movie", "tt1", null, null, false, null) } returns
            flowOf(NetworkResult.Success(emptyList<AddonStreams>()))
        val repository = OfflineStreamRepository(delegate, downloads)
        assertTrue((repository.getStreamsFromAllAddons("movie", "tt1", null, null, false, null).toList().last() as NetworkResult.Success).data.isNotEmpty())
        present = false
        assertTrue((repository.getStreamsFromAllAddons("movie", "tt1", null, null, false, null).toList().last() as NetworkResult.Success).data.isEmpty())
    }
    @Test fun `ready local episode is offered even when network sources fail`() = runTest {
        val delegate = mockk<StreamRepositoryImpl>()
        val downloads = mockk<OfflineDownloads>()
        val item = OfflineItem("fixture", "Episode", 1, emptyList(), OfflineMediaIdentity("series", "tt1", "tt1:1:2", 1, 2))
        every { downloads.matching(any()) } returns listOf(item)
        every { delegate.getStreamsFromAllAddons("series", "tt1:1:2", 1, 2, false, "tt1") } returns
            flowOf(NetworkResult.Error("Offline"))
        val result = OfflineStreamRepository(delegate, downloads).getStreamsFromAllAddons("series", "tt1:1:2", 1, 2, false, "tt1").toList().last()
        val groups = (result as NetworkResult.Success).data
        assertEquals("Downloads", groups.single().addonName)
        assertEquals("nuvio-offline://fixture", groups.single().streams.single().url)
    }
    @Test fun `no local episode leaves network results unchanged and hides Downloads`() = runTest {
        val delegate = mockk<StreamRepositoryImpl>()
        val downloads = mockk<OfflineDownloads>()
        every { downloads.matching(any()) } returns emptyList()
        every { downloads.associateLegacy(any(), any()) } returns Unit
        every { delegate.getStreamsFromAllAddons("movie", "tt1", null, null, false, null) } returns
            flowOf(NetworkResult.Success(listOf(AddonStreams("Remote", null, emptyList()))))
        val result = OfflineStreamRepository(delegate, downloads).getStreamsFromAllAddons("movie", "tt1", null, null, false, null)
            .first { it is NetworkResult.Success }
        assertEquals(listOf("Remote"), (result as NetworkResult.Success).data.map { it.addonName })
    }
}
