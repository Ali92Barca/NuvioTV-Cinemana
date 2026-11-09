package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class StreamRepositoryPluginIsolationTest {
    @Test fun `verified cached movie links publish while the public ID request is pending`() = runBlocking {
        val addon = compatibleAddon().copy(
            resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt", "xt_"))))
        val identities = mockk<SharedPlaybackIdentity> {
            every { externalMovieSources(any(), null, "tt1341338") } returns listOf("xt_m_src_42")
        }
        val harness = newHarness(emptyList(), addon, identities = identities)
        val slow = CompletableDeferred<Response<StreamResponseDto>>()
        coEvery { harness.api.getStreams(match { it.contains("tt1341338") }) } coAnswers { slow.await() }
        coEvery { harness.api.getStreams(match { it.contains("xt_m_src_42") }) } returns Response.success(
            StreamResponseDto(listOf(StreamDto(url = "https://stream.example/native.mp4"))))
        try {
            val ready = withTimeout(3_000) { harness.repository.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { it is NetworkResult.Success } } as NetworkResult.Success
            assertEquals("https://stream.example/native.mp4", ready.data.single().streams.single().url)
            assertTrue(slow.isActive)
            coVerify(exactly = 1) { harness.api.getStreams(match { it.contains("xt_m_src_42") }) }
        } finally { slow.complete(Response.success(StreamResponseDto(emptyList()))) }
    }

    @Test fun `verified episode links do not wait for metadata and keep original cumulative video ID`() = runBlocking {
        val addon = compatibleAddon().copy(resources = listOf(AddonResource("stream", listOf("series"), listOf("tt", "xt_"))))
        val identities = mockk<SharedPlaybackIdentity> {
            every { externalEpisodeSources(addon.baseUrl, "tt38282411", 2, 2) } returns listOf("xt_s_src_42:2:20")
        }
        // No canonical metadata is available: the verified cache must be sufficient.
        val harness = newHarness(emptyList(), addon, identities = identities)
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_42") }) } returns Response.success(
            StreamResponseDto(listOf(StreamDto(url = "https://stream.example/original-episode.m3u8"))))
        val ready = withTimeout(3000) {
            harness.repository.getStreamsFromAllAddons("series", "tt38282411:2:2", 2, 2).first { it is NetworkResult.Success }
        } as NetworkResult.Success
        assertEquals("https://stream.example/original-episode.m3u8", ready.data.single().streams.single().url)
        coVerify(exactly = 1) { harness.api.getStreams(match { it.contains("xt_s_src_42%3A2%3A20") || it.contains("xt_s_src_42:2:20") }) }
        coVerify(exactly = 0) { harness.api.getMeta(any()) }
        coVerify(exactly = 0) { harness.api.getCatalog(any()) }
        coVerify(exactly = 0) { harness.api.getStreams(match { it.contains("tt38282411") }) }
    }

    @Test fun `empty cached episode falls back to fresh matching instead of hiding replacement links`() = runBlocking {
        val addon = compatibleAddon().copy(
            catalogs = listOf(com.nuvio.tv.domain.model.CatalogDescriptor(com.nuvio.tv.domain.model.ContentType.SERIES,
                id = "search", name = "Search", extra = listOf(com.nuvio.tv.domain.model.CatalogExtra("search")))),
            resources = listOf(AddonResource("stream", listOf("series"), listOf("tt", "xt_"))))
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true) {
            every { externalEpisodeSources(addon.baseUrl, "tt101", 1, 1) } returns listOf("xt_s_src_42:1:1")
        }
        val harness = newHarness(emptyList(), addon, seriesCanonical(), identities = identities)
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_42") }) } returns Response.success(StreamResponseDto(emptyList()))
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_43") }) } returns Response.success(
            StreamResponseDto(listOf(StreamDto(url = "https://stream.example/replacement.m3u8"))))
        coEvery { harness.api.getCatalog(any()) } returns Response.success(com.nuvio.tv.data.remote.dto.CatalogResponseDto(
            listOf(com.nuvio.tv.data.remote.dto.MetaPreviewDto("xt_s_src_43", "series", "Series", releaseInfo = "2020"))))
        coEvery { harness.api.getMeta(any()) } returns nativeMeta("xt_s_src_43")
        val ready = withTimeout(3000) { harness.repository.getStreamsFromAllAddons("series", "tt101:1:1", 1, 1)
            .first { it is NetworkResult.Success } } as NetworkResult.Success
        assertEquals("https://stream.example/replacement.m3u8", ready.data.single().streams.single().url)
        coVerify(exactly = 2) { harness.api.getStreams(match { it.contains("xt_s_src_42") }) }
        coVerify(exactly = 1) { harness.api.getStreams(match { it.contains("xt_s_src_43") }) }
    }

    @Test fun `empty verified native episode recovers without refreshing successful sources or metadata`() = runBlocking {
        val addon = compatibleAddon().copy(resources = listOf(AddonResource("stream", listOf("series"), listOf("tt", "xt_"))))
        val identities = mockk<SharedPlaybackIdentity> {
            every { externalEpisodeSources(addon.baseUrl, "tt38282411", 2, 2) } returns listOf("xt_s_src_42:2:20")
        }
        val harness = newHarness(emptyList(), addon, identities = identities)
        var nativeCalls = 0
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_42") }) } coAnswers {
            Response.success(StreamResponseDto(if (++nativeCalls == 1) emptyList() else
                listOf(StreamDto(url = "https://stream.example/recovered.m3u8"))))
        }
        val ready = withTimeout(3000) {
            harness.repository.getStreamsFromAllAddons("series", "tt38282411:2:2", 2, 2).first { it is NetworkResult.Success }
        } as NetworkResult.Success
        assertEquals("https://stream.example/recovered.m3u8", ready.data.single().streams.single().url)
        assertEquals(2, nativeCalls)
        coVerify(exactly = 0) { harness.api.getMeta(any()) }
        coVerify(exactly = 0) { harness.api.getCatalog(any()) }
        coVerify(exactly = 0) { harness.api.getStreams(match { it.contains("tt38282411") }) }
    }

    @Test fun `recovering one source keeps sibling links visible without requesting them again`() = runBlocking {
        val fast = compatibleAddon()
        val recovering = fast.copy(id = "recovering", name = "Recovering", baseUrl = "https://recovering.example")
        val harness = newHarness(emptyList(), fast)
        every { harness.addonRepository.getInstalledAddons() } returns flowOf(listOf(fast, recovering))
        var retryCalls = 0
        coEvery { harness.api.getStreams(match { it.startsWith(recovering.baseUrl) }) } coAnswers {
            if (++retryCalls == 1) Response.error(503, "temporary".toResponseBody()) else
                Response.success(StreamResponseDto(listOf(StreamDto(url = "https://stream.example/recovered.m3u8"))))
        }
        val snapshots = withTimeout(3000) {
            harness.repository.getStreamsFromAllAddons("movie", "tt123", null, null).toList()
        }.filterIsInstance<NetworkResult.Success<List<AddonStreams>>>()
        assertEquals(listOf(fast.displayName), snapshots.first().data.map { it.addonName })
        assertEquals(setOf(fast.displayName, recovering.displayName), snapshots.last().data.map { it.addonName }.toSet())
        coVerify(exactly = 1) { harness.api.getStreams(match { it.startsWith(fast.baseUrl) }) }
        assertEquals(2, retryCalls)
    }

    private fun seriesCanonical() = mockk<com.nuvio.tv.domain.model.Meta> {
        every { id } returns "tt101"
        every { name } returns "Series"
        every { releaseInfo } returns "2020"
        every { released } returns null
        every { imdbId } returns "tt101"
        every { tmdbId } returns "9"
        every { videos } returns (1..2).map { com.nuvio.tv.domain.model.Video("tt101:1:$it", "Episode $it",
            null, null, season = 1, episode = it, overview = null) }
    }
    private fun nativeMeta(id: String) = Response.success(com.nuvio.tv.data.remote.dto.MetaResponseDto(
        com.nuvio.tv.data.remote.dto.MetaDto(id, "series", "Series", videos = (1..2).map {
            com.nuvio.tv.data.remote.dto.VideoDto("$id:1:$it", title = "Episode $it", season = 1, episode = it)
        })))
    @Test fun `native IPTV links appear while another edition is still resolving and then merge without duplicates`() = runBlocking {
        val addon = compatibleAddon().copy(
            catalogs = listOf(com.nuvio.tv.domain.model.CatalogDescriptor(com.nuvio.tv.domain.model.ContentType.SERIES,
                id = "search", name = "Search", extra = listOf(com.nuvio.tv.domain.model.CatalogExtra("search")))),
            resources = listOf(AddonResource("stream", listOf("series"), listOf("tt", "xt_"))))
        val harness = newHarness(emptyList(), addon, seriesCanonical())
        val slow = CompletableDeferred<Response<com.nuvio.tv.data.remote.dto.MetaResponseDto>>()
        coEvery { harness.api.getCatalog(any()) } returns Response.success(com.nuvio.tv.data.remote.dto.CatalogResponseDto(
            listOf("xt_s_src_42", "xt_s_src_43").map { com.nuvio.tv.data.remote.dto.MetaPreviewDto(it, "series", "Series", releaseInfo = "2020") }))
        coEvery { harness.api.getMeta(match { it.contains("xt_s_src_42") }) } returns nativeMeta("xt_s_src_42")
        coEvery { harness.api.getMeta(match { it.contains("xt_s_src_43") }) } coAnswers { slow.await() }
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_42") }) } returns Response.success(
            StreamResponseDto(listOf(StreamDto(url = "https://stream.example/first.m3u8"))))
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_43") }) } returns Response.success(
            StreamResponseDto(listOf(StreamDto(url = "https://stream.example/second.m3u8"))))
        val first = CompletableDeferred<List<AddonStreams>>()
        val both = CompletableDeferred<List<AddonStreams>>()
        val collector = launch {
            harness.repository.getStreamsFromAllAddons("series", "tt101:1:1", 1, 1).collect { result ->
                if (result is NetworkResult.Success) {
                    val urls = result.data.flatMap { it.streams }.map { it.url }
                    if ("https://stream.example/first.m3u8" in urls) first.complete(result.data)
                    if ("https://stream.example/second.m3u8" in urls) both.complete(result.data)
                }
            }
        }
        try {
            assertEquals(1, withTimeout(3_000) { first.await() }.single().streams.size)
            assertTrue(slow.isActive)
            slow.complete(nativeMeta("xt_s_src_43"))
            assertEquals(2, withTimeout(3_000) { both.await() }.single().streams.size)
            coVerify(exactly = 2) { harness.api.getStreams(any()) }
        } finally {
            slow.complete(nativeMeta("xt_s_src_43"))
            collector.cancelAndJoin()
        }
    }
    @Test fun `direct movie links appear while native movie search is still pending`() = runBlocking {
        val addon = compatibleAddon().copy(
            catalogs = listOf(com.nuvio.tv.domain.model.CatalogDescriptor(com.nuvio.tv.domain.model.ContentType.MOVIE,
                id = "search", name = "Search", extra = listOf(com.nuvio.tv.domain.model.CatalogExtra("search")))),
            resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt", "xt_"))))
        val canonical = mockk<com.nuvio.tv.domain.model.Meta> {
            every { id } returns "tt1341338"
            every { name } returns "Movie"
            every { releaseInfo } returns "2020"
            every { released } returns null
            every { imdbId } returns "tt1341338"
            every { tmdbId } returns "9"
        }
        val harness = newHarness(emptyList(), addon, canonical)
        val slow = CompletableDeferred<Response<com.nuvio.tv.data.remote.dto.CatalogResponseDto>>()
        coEvery { harness.api.getCatalog(any()) } coAnswers { slow.await() }
        try {
            val ready = withTimeout(3_000) { harness.repository.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { it is NetworkResult.Success } } as NetworkResult.Success
            assertEquals(1, ready.data.single().streams.size)
            assertTrue(slow.isActive)
        } finally { slow.complete(Response.success(com.nuvio.tv.data.remote.dto.CatalogResponseDto(emptyList()))) }
    }
    @Test fun `canonical Xtream episode requests use verified native IDs without a discarded canonical request`() = verifyNativeSearch(false)
    @Test fun `cold metadata cache still resolves native IPTV instead of returning unverified direct links`() = verifyNativeSearch(true)
    private fun verifyNativeSearch(coldCache: Boolean) = runTest {
        val addon = compatibleAddon().copy(
            catalogs = listOf(com.nuvio.tv.domain.model.CatalogDescriptor(com.nuvio.tv.domain.model.ContentType.SERIES,
                id = "search", name = "Search", extra = listOf(com.nuvio.tv.domain.model.CatalogExtra("search")))),
            resources = listOf(AddonResource("stream", listOf("series"), listOf("tt", "xt_"))))
        val canonical = mockk<com.nuvio.tv.domain.model.Meta> {
            every { id } returns "tt101"
            every { name } returns "Series"
            every { releaseInfo } returns "2020"
            every { released } returns null
            every { imdbId } returns "tt101"
            every { tmdbId } returns "9"
            every { videos } returns (1..2).map { com.nuvio.tv.domain.model.Video("tt101:1:$it", "Episode $it",
                null, null, season = 1, episode = it, overview = null) }
        }
        val harness = newHarness(emptyList(), addon, canonical, coldCache)
        coEvery { harness.api.getCatalog(any()) } returns Response.success(com.nuvio.tv.data.remote.dto.CatalogResponseDto(listOf(
            com.nuvio.tv.data.remote.dto.MetaPreviewDto("xt_s_src_42", "series", "Series", releaseInfo = "2020"))))
        coEvery { harness.api.getMeta(any()) } returns Response.success(com.nuvio.tv.data.remote.dto.MetaResponseDto(
            com.nuvio.tv.data.remote.dto.MetaDto("xt_s_src_42", "series", "Series", videos = (1..2).map {
                com.nuvio.tv.data.remote.dto.VideoDto("xt_s_src_42:1:$it", title = "Episode $it", season = 1, episode = it)
            })))
        coEvery { harness.api.getStreams(match { it.contains("xt_s_src_42") }) } returns Response.success(
            StreamResponseDto(streams = listOf(StreamDto(name = "Translated", url = "https://stream.example/translated.m3u8"))))
        val results = harness.repository.getStreamsFromAllAddons("series", "tt101:1:1", 1, 1).toList()
        val groups = (results.last() as NetworkResult.Success).data
        assertEquals(listOf("https://stream.example/translated.m3u8"), groups.flatMap { it.streams }.map { it.url })
        coVerify(exactly = 1) { harness.api.getCatalog(any()) }
        coVerify(exactly = 0) { harness.api.getStreams(match { it.contains("tt101") }) }
    }
    @Test fun `missing canonical metadata cannot return unverified Xtream inline or direct episodes`() = runTest {
        val addon = compatibleAddon().copy(
            catalogs = listOf(com.nuvio.tv.domain.model.CatalogDescriptor(com.nuvio.tv.domain.model.ContentType.SERIES,
                id = "search", name = "Search", extra = listOf(com.nuvio.tv.domain.model.CatalogExtra("search")))),
            resources = listOf(AddonResource("stream", listOf("series"), listOf("tt", "xt_"))))
        val harness = newHarness(emptyList(), addon)
        val results = harness.repository.getStreamsFromAllAddons("series", "tt101:1:1", 1, 1).toList()
        assertTrue(results.none { it is NetworkResult.Success && it.data.any { group -> group.streams.isNotEmpty() } })
        coVerify(exactly = 0) { harness.api.getStreams(any()) }
        coVerify(exactly = 0) { harness.api.getMeta(any()) }
    }
    @Test
    fun `addon results arrive while plugin TMDB lookup is pending`() = runBlocking {
        val harness = newHarness(listOf(compatibleScraper()))
        val tmdbResult = CompletableDeferred<String?>()
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } coAnswers {
            tmdbResult.await()
        }

        val result = withTimeout(1_000) {
            harness.repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1341338",
                season = null,
                episode = null
            ).first { it is NetworkResult.Success }
        }

        val groups = (result as NetworkResult.Success).data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(1, groups.single().streams.size)
        assertTrue(tmdbResult.isActive)
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
        tmdbResult.complete(null)
        Unit
    }

    @Test
    fun `TMDB lookup is skipped when no compatible plugin is enabled`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        assertTrue(results.last() is NetworkResult.Success)
        coVerify(exactly = 0) { harness.tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 1) { harness.api.getStreams(any()) }
    }

    /**
     * The stream path must read addon display info from the installed [Addon] it was given, not
     * refetch the manifest. fetchAddon is deliberately left stubbed on the harness so this asserts
     * the call is available and still not made, rather than merely un-stubbed.
     */
    @Test
    fun `stream lookup issues no manifest request`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        assertEquals(listOf("Fast Addon"), success.data.map { it.addonName })
        // Pins the URL too: the regression is not merely "one call happened", it is that the
        // stream request is the only request, built from the addon's own baseUrl.
        coVerify(exactly = 1) { harness.api.getStreams("https://addon.example/stream/movie/tt1341338.json") }
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `streams carry the installed addon name and logo`() = runTest {
        val harness = newHarness(emptyList())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).toList()

        val success = results.last() as NetworkResult.Success
        val groups = success.data
        assertEquals(listOf("Fast Addon"), groups.map { it.addonName })
        assertEquals(listOf(ADDON_LOGO), groups.map { it.addonLogo })
        assertTrue(groups.single().streams.all { it.addonName == "Fast Addon" })
        assertTrue(groups.single().streams.all { it.addonLogo == ADDON_LOGO })
        coVerify(exactly = 0) { harness.addonRepository.fetchAddon(any()) }
    }

    @Test
    fun `repository grouping preserves each plugin provider name`() = runTest {
        assertPluginProviderNames(groupByRepository = true)
    }

    @Test
    fun `ungrouped plugins retain their existing group names`() = runTest {
        assertPluginProviderNames(groupByRepository = false)
    }

    private suspend fun assertPluginProviderNames(groupByRepository: Boolean) {
        val scrapers = listOf(
            compatibleScraper().copy(id = "first", name = "First provider"),
            compatibleScraper().copy(id = "second", name = "Second provider")
        )
        val harness = newHarness(scrapers)
        every { harness.pluginManager.groupStreamsByRepository } returns flowOf(groupByRepository)
        every { harness.pluginManager.repositories } returns flowOf(
            listOf(PluginRepository(id = "repo", name = "Repository", url = "https://repo.example"))
        )
        coEvery { harness.tmdbService.ensureTmdbId("tt1341338", "movie") } returns "123"
        every { harness.pluginManager.executeScrapersStreaming("123", "movie", null, null) } returns
            flowOf(*scrapers.map { scraper ->
                scraper to listOf(LocalScraperResult(
                    title = "Shared title",
                    url = "https://stream.example/${scraper.id}.m3u8"
                ))
            }.toTypedArray())

        val results = harness.repository.getStreamsFromAllAddons(
            type = "movie", videoId = "tt1341338", season = null, episode = null
        ).toList()
        val groups = (results.last() as NetworkResult.Success).data
        val pluginGroups = groups.filter { it.addonName != "Fast Addon" }
        assertEquals(
            if (groupByRepository) listOf("Repository") else scrapers.map { it.name },
            pluginGroups.map { it.addonName }
        )
        val streams = pluginGroups.flatMap { it.streams }
        assertEquals(scrapers.map { it.name }, streams.map { it.pluginSourceName })
        assertEquals(listOf("Shared title", "Shared title"), streams.map { it.title })
        assertTrue(streams.all { stream ->
            stream.addonName == if (groupByRepository) "Repository" else stream.pluginSourceName
        })
        assertTrue(groups.single { it.addonName == "Fast Addon" }.streams.all { it.pluginSourceName == null })
    }

    private fun newHarness(enabledScrapers: List<ScraperInfo>, addon: Addon = compatibleAddon(),
        canonical: com.nuvio.tv.domain.model.Meta? = null, coldCache: Boolean = false,
        identities: SharedPlaybackIdentity? = null): Harness {
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any()) } returns Response.success(
            StreamResponseDto(
                streams = listOf(
                    StreamDto(
                        name = "Fast Stream",
                        url = "https://stream.example/video.m3u8"
                    )
                )
            )
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonRepository.fetchAddon(addon.baseUrl) } returns NetworkResult.Success(addon)

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(enabledScrapers)
        every { pluginManager.pluginsEnabled } returns flowOf(enabledScrapers.isNotEmpty())
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val tmdbService = mockk<TmdbService>(relaxed = true)
        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())
        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }
        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers {
            firstArg<List<AddonStreams>>()
        }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers {
            firstArg<List<AddonStreams>>()
        }

        return Harness(
            repository = StreamRepositoryImpl(
                context = mockk<Context>(relaxed = true),
                api = api,
                addonRepository = addonRepository,
                pluginManager = pluginManager,
                profileManager = profileManager,
                debridSettingsDataStore = debridSettingsDataStore,
                tmdbService = tmdbService,
                debridStreamPresentation = presentation,
                localDebridAvailabilityService = availability,
                externalAddonBridge = com.nuvio.tv.data.iptv.ExternalAddonBridge(api, identities = identities),
                metaRepository = mockk(relaxed = true) {
                    every { getCachedMeta(any(), any()) } returns if (coldCache) null else canonical
                    coEvery { getCachedBuiltinDetails(any(), any()) } returns null
                    every { getMetaFromAllAddons(any(), any(), any()) } returns if (canonical == null)
                        flowOf(NetworkResult.Error("Missing metadata")) else flowOf(NetworkResult.Success(canonical))
                },
                serverStreams = mockk {
                    every { isNativeRequest(any()) } returns false
                    every { revision } returns 0
                    every { sources(any(), any(), any(), any(), any()) } returns emptyList()
                }
            ),
            api = api,
            tmdbService = tmdbService,
            addonRepository = addonRepository,
            pluginManager = pluginManager
        )
    }

    private fun compatibleAddon(): Addon = Addon(
        id = "fast-addon",
        name = "Fast Addon",
        version = "1.0.0",
        description = null,
        logo = ADDON_LOGO,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "stream",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    private fun compatibleScraper(): ScraperInfo = ScraperInfo(
        id = "plugin",
        name = "Plugin",
        description = "",
        version = "1.0.0",
        filename = "plugin.js",
        supportedTypes = listOf("movie"),
        enabled = true,
        manifestEnabled = true,
        logo = null,
        contentLanguage = emptyList(),
        repositoryId = "repo",
        formats = null,
        type = RepositoryType.NUVIO_JS
    )

    private companion object {
        const val ADDON_LOGO = "https://addon.example/logo.png"
    }

    private data class Harness(
        val repository: StreamRepositoryImpl,
        val api: AddonApi,
        val tmdbService: TmdbService,
        val addonRepository: AddonRepository,
        val pluginManager: PluginManager
    )
}
