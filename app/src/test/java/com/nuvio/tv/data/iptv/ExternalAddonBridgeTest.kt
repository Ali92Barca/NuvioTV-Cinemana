package com.nuvio.tv.data.iptv

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.repository.SharedPlaybackIdentity
import com.nuvio.tv.data.repository.PlaybackIdentity
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class ExternalAddonBridgeTest {
    @Test fun incompleteSeasonResponseDoesNotRevokeAnEstablishedDurableWork() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val source = addon().copy(resources = listOf(AddonResource("stream", listOf("series"), listOf("xt_"))))
        val native = MetaDto("xt_s_src_42","series","Example",videos = listOf(
            VideoDto("xt_s_src_42:1:1",season=1,episode=1))).toDomain("Episode")
        val stored = com.google.gson.JsonObject().apply { addProperty("tmdb","42"); addProperty("translated",true) }
        val cache = mockk<IptvIdentityCache>(relaxed=true) { every { work(source.baseUrl,native.id) } returns stored }
        val identities = mockk<SharedPlaybackIdentity>(relaxed=true)
        every { identities.entries } returns kotlinx.coroutines.flow.MutableStateFlow<List<PlaybackIdentity>>(emptyList())
        every { identities.withWatchIdentity(any(),any()) } answers { firstArg() }
        val tmdb = mockk<TmdbApi>()
        coEvery { tmdb.getTvDetails(42,any(),any()) } returns Response.success(TmdbDetailsResponse(42,name="Example",originalLanguage="tr"))
        coEvery { tmdb.getTvExternalIds(42,any()) } returns Response.success(TmdbExternalIdsResponse(42,imdbId="tt1234567"))
        coEvery { tmdb.getTvSeasonDetails(42,1,any(),any()) } returns Response.success(com.nuvio.tv.data.remote.api.TmdbSeasonResponse(1,emptyList()))
        ExternalAddonBridge(api,tmdb,identities,cache).enrichNative(source,native)
        verify(exactly=0) { identities.invalidateExternalSeason(any(),any(),any(),any()) }
    }
    @Test fun existingRouteReturnsWhileOneCoalescedRefreshWaitsAndRemovalCancelsIt() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val source = addon().copy(resources = listOf(AddonResource("stream", listOf("series"), listOf("xt_"))))
        val native = MetaDto("xt_s_src_42", "series", "Example", videos = (1..9).map {
            VideoDto("xt_s_src_42:1:$it", season = 1, episode = it)
        }).toDomain("Episode")
        val stored = com.google.gson.JsonObject().apply {
            addProperty("tmdb", "42"); addProperty("imdb", "tt1234567")
            addProperty("nativeLayout", "previous-layout"); addProperty("providerLayout", "previous-provider")
            add("verifiedVideos", com.google.gson.JsonArray().apply { native.videos.take(8).forEach { add(it.id) } })
        }
        val cache = mockk<IptvIdentityCache>(relaxed = true) { every { work(source.baseUrl,native.id) } returns stored }
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        every { identities.entries } returns kotlinx.coroutines.flow.MutableStateFlow<List<PlaybackIdentity>>(emptyList())
        every { identities.hasExternalSeries(source.baseUrl,native.id) } returns true
        every { identities.withWatchIdentity(any(),source.baseUrl) } answers { firstArg() }
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blocker = kotlinx.coroutines.CompletableDeferred<Response<TmdbDetailsResponse>>()
        val tmdb = mockk<TmdbApi>()
        coEvery { tmdb.getTvDetails(42,any(),any()) } coAnswers {
            started.complete(Unit)
            try { blocker.await() } finally { stopped.complete(Unit) }
        }
        val bridge = ExternalAddonBridge(api,tmdb,identities,cache)
        repeat(2) {
            val result = kotlinx.coroutines.withTimeout(10_000) { bridge.enrichNative(source,native) }
            assertEquals(8,result.verifiedPlaybackVideoIds?.size)
            assertFalse(result.verifiedPlaybackVideoIds.orEmpty().contains("xt_s_src_42:1:9"))
        }
        kotlinx.coroutines.withTimeout(10_000) { started.await() }
        coVerify(exactly = 1) { tmdb.getTvDetails(42,any(),any()) }
        bridge.forgetAddon(source.baseUrl)
        kotlinx.coroutines.withTimeout(10_000) { stopped.await() }
        verify(exactly = 1) { identities.forgetExternalAddon(source.baseUrl) }
    }
    @Test fun durableRouteReopensAcrossBridgeInstancesWithoutAnyNetworkMatching() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val source = addon().copy(resources = listOf(AddonResource("stream", listOf("series"), listOf("xt_"))))
        val native = MetaDto("xt_s_src_42", "series", "Example", videos = (1..8).map {
            VideoDto("xt_s_src_42:1:$it", season = 1, episode = it)
        }).toDomain("Episode")
        fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val stored = com.google.gson.JsonObject().apply {
            addProperty("tmdb", "42"); addProperty("imdb", "tt1234567")
            addProperty("nativeLayout", hash(native.videos.map { listOf(it.id,it.season,it.episode) }.toString()))
            addProperty("providerLayout", hash("[]"))
            add("verifiedVideos", com.google.gson.JsonArray().apply { native.videos.forEach { add(it.id) } })
        }
        val cache = mockk<IptvIdentityCache> { every { work(source.baseUrl, native.id) } returns stored }
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        every { identities.entries } returns kotlinx.coroutines.flow.MutableStateFlow<List<PlaybackIdentity>>(emptyList())
        every { identities.hasExternalSeries(source.baseUrl, native.id) } returns true
        every { identities.withWatchIdentity(any(), source.baseUrl) } answers { firstArg() }
        val tmdb = mockk<TmdbApi>()
        repeat(2) {
            val result = ExternalAddonBridge(api, tmdb, identities, cache).enrichNative(source,native)
            assertEquals("tt1234567", result.imdbId)
            assertTrue(result.sharedProgressCompatible)
            assertEquals(8, result.verifiedPlaybackVideoIds?.size)
        }
        coVerify(exactly = 0) { api.getMeta(any()) }
        coVerify(exactly = 0) { api.getCatalog(any()) }
        coVerify(exactly = 0) { api.getStreams(any()) }
        coVerify(exactly = 0) { tmdb.getTvDetails(any(),any(),any()) }
        coVerify(exactly = 0) { tmdb.getTvSeasonDetails(any(),any(),any(),any()) }
    }
    @Test fun originalEditionAddsNewVerifiedProviderEpisodeBeforeTmdbUpdates() {
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        every { identities.verifiedSeriesSeasonAvailable("42", 1) } returns (1..9).toSet()
        val map = ExternalAddonBridge(api, identities = identities).sharedOriginalMap("42",
            mapOf(1 to (1..8).toSet()), mapOf(1 to (1..10).toList()), 1, 1)!!
        assertEquals(9, map[9])
        assertFalse(10 in map)
    }
    @Test fun nonTurkishTranslatedCatalogSharesCommonEpisodesWithoutDatesOrEqualCounts() = runBlocking {
        val source = addon().copy(catalogs = listOf(CatalogDescriptor(ContentType.SERIES, id = "all", name = "All", extra = listOf(CatalogExtra("search")))))
        val nativeId = "xt_s_src_42"
        val canonical = MetaDto("tt1234567", "series", "Example", tmdbId = "42", imdbId = "tt1234567",
            videos = (1..8).map { VideoDto("tt1234567:1:$it", title = "Official $it", season = 1, episode = it) }).toDomain("Episode")
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(MetaPreviewDto(nativeId,"series","Example"))))
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(nativeId,"series","Example",
            videos = (1..10).map { VideoDto("$nativeId:1:$it", title = "Different label $it", season = 1, episode = it) })))
        val cache = mockk<IptvIdentityCache>(relaxed = true) { every { translated(source.baseUrl, nativeId) } returns true }
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        val translated = ExternalAddonBridge(api, identities = identities, matchCache = cache)
        assertEquals("$nativeId:1:8", translated.resolve(source,"series",canonical,1,8))
        assertNull(translated.resolve(source,"series",canonical,1,9))
        verify { identities.learnExternalSeason(source.baseUrl, nativeId, "tt1234567", 1, 1,
            match { it.size == 8 && it.last().third == 8 }, any(), any()) }
        assertNull(ExternalAddonBridge(api).resolve(source,"series",canonical,1,8))
    }
    @Test fun verifiedEpisodeCacheRejectsConflictingEpisodeSuffixes() {
        val identities = mockk<SharedPlaybackIdentity> {
            every { externalEpisodeSources(any(), "tt38282411", 2, 2) } returns listOf("xt_s_src_42:2:20")
        }
        val bridge = ExternalAddonBridge(api, identities = identities)
        assertEquals(listOf("xt_s_src_42:2:20"), bridge.cachedSeriesIds(addon(), "tt38282411:2:2", 2, 2))
        assertTrue(bridge.cachedSeriesIds(addon(), "tt38282411:2:3", 2, 2).isEmpty())
        assertTrue(bridge.cachedSeriesIds(addon(), "tmdb:movie:123:2:2", 2, 2).isEmpty())
        assertTrue(bridge.cachedSeriesIds(addon(), "https://invalid.test/tt38282411", 2, 2).isEmpty())
    }
    @Test fun translatedStreamProofIsBoundedCachedAndIsolatedBySubscription() = runBlocking {
        val source = addon().copy(resources = listOf(AddonResource("stream", listOf("series"), listOf("xt_"))))
        val native = MetaDto("xt_s_src_50302", "series", "Alikara", videos = listOf(
            VideoDto("xt_s_src_50302:1:1", season = 1, episode = 1))).toDomain("Episode")
        coEvery { api.getStreams(any()) } returns Response.success(StreamResponseDto(listOf(
            StreamDto(description = "AR-TR-S - Alikara | يعرض الآن تركي مترجم"))))
        assertTrue(bridge.translatedEdition(source, native))
        assertTrue(bridge.translatedEdition(source, native))
        coVerify(exactly = 1) { api.getStreams(any()) }
        coEvery { api.getStreams(any()) } returns Response.success(StreamResponseDto(listOf(
            StreamDto(description = "AR-TR-D - Alikara مدبلج"))))
        assertFalse(bridge.translatedEdition(source.copy(baseUrl = "https://other.test"), native))
        coVerify(exactly = 2) { api.getStreams(any()) }
    }
    @Test fun translatedProofSupportsAnyOriginalLanguage() {
        assertTrue(bridge.translatedLabel("AR-TR-S - Alikara"))
        assertTrue(bridge.translatedLabel("يعرض الآن تركي مترجم"))
        assertTrue(bridge.translatedLabel("مسلسلات تركية مترجمة"))
        assertTrue(bridge.translatedLabel("مسلسلات أجنبية مترجمة"))
        assertTrue(bridge.translatedLabel("كوري مترجم"))
        assertFalse(bridge.translatedLabel("AR-TR-D - Alikara مدبلج"))
        assertFalse(bridge.translatedLabel("ENGLISH SERIES (TR)"))
        assertFalse(bridge.translatedLabel("فارسي"))
    }
    @Test fun translatedSiblingLayoutMapsOnlyCommonEpisodesWithoutRelaxingOtherEditions() {
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        every { identities.verifiedSeriesSeasonForCount(any(), any(), any()) } returns null
        val bridge = ExternalAddonBridge(api, identities = identities)
        val canonical = mapOf(1 to (1..8).toSet())
        val native = mapOf(1 to (1..10).toList())
        assertNull(bridge.verifiedSiblingMap("42", canonical, native, 1, 1, true))
        assertEquals((1..8).associateWith { it }, bridge.verifiedSiblingMap("42", canonical, native, 1, 1, true, true))
        assertNull(bridge.verifiedSiblingMap("42", canonical, native, 1, 1, false, true))
    }
    @Test fun verifiedPreviousSeasonAvailabilityDoesNotBlockAbiCumulativeSecondSeason() {
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        every { identities.verifiedSeriesSeasonForCount("42", 1, 18) } returns (1..18).toSet()
        every { identities.verifiedSeriesSeasonForCount("42", 2, 5) } returns (1..5).toSet()
        val bridge = ExternalAddonBridge(api, identities = identities)
        assertEquals((1..5).associateWith { it + 18 }, bridge.verifiedSiblingMap("42",
            mapOf(1 to (1..20).toSet(), 2 to (1..8).toSet()),
            mapOf(1 to (1..18).toList(), 2 to (19..23).toList()), 2, 2, true))
    }
    @Test fun numericPrimaryTitleUsesOfficialImdbProofWithoutJoiningASeparateNumberedPart() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val tmdb = mockk<TmdbApi>()
        coEvery { tmdb.findByExternalId(any(), any(), any()) } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbFindResponse(tvResults = listOf(com.nuvio.tv.data.remote.api.TmdbFindResult(317883))))
        val bridge = ExternalAddonBridge(api, tmdb)
        assertTrue(bridge.canUseSeriesImdb("317883", "tt42764103", setOf("Daha 17")))
        assertFalse(bridge.canUseSeriesImdb("338743", "tt42764103", setOf("ليل البنفسج 2")))
    }
    @Test fun verifiedSiblingLayoutSupportsMatchingAvailableTurkishEditionsBehindTmdbSchedule() {
        val identities = mockk<SharedPlaybackIdentity>()
        every { identities.verifiedSeriesSeasonForCount("42", 1, any()) } returns (1..6).toSet()
        val bridge = ExternalAddonBridge(api, identities = identities)
        val expected = mapOf(1 to (1..7).toSet())
        assertEquals((1..6).associateWith { it }, bridge.verifiedSiblingMap("42", expected, mapOf(1 to (1..6).toList()), 1, 1, true))
        assertNull(bridge.verifiedSiblingMap("42", expected, mapOf(1 to listOf(1,2,3,4,5,5)), 1, 1, true))
        assertNull(bridge.verifiedSiblingMap("42", expected, mapOf(1 to (1..5).toList()), 1, 1, true))
        assertNull(bridge.verifiedSiblingMap("42", expected, mapOf(1 to (1..6).toList()), 1, 1, false))
        assertNull(bridge.verifiedSiblingMap("42", expected, mapOf(2 to (1..6).toList()), 1, 2, true))
        every { identities.verifiedSeriesSeasonForCount("42", 2, any()) } returns (1..6).toSet()
        assertNull(bridge.verifiedSiblingMap("42", mapOf(1 to (1..10).toSet(),2 to (1..7).toSet()),
            mapOf(1 to (1..20).toList(),2 to (1..6).toList()), 2, 2, true))
    }
    @Test fun betterStreamMovieRequestsUseTheSupportedUntypedTmdbRouteOnly() {
        val source = addon().copy(id = "org.betterstream.example", resources = listOf(AddonResource("stream", listOf("movie", "series"), listOf("xt_", "tmdb:"))))
        assertEquals("tmdb:315635", bridge.streamRequestId(source, "movie", "tmdb:movie:315635"))
        assertEquals("tmdb:969681", bridge.streamRequestId(source, "movie", "tmdb:movie:969681"))
        listOf("tt2250912", "tmdb:315635", "xt_m_src_42").forEach { assertEquals(it, bridge.streamRequestId(source, "movie", it)) }
        assertEquals("tmdb:274556:3:8", bridge.streamRequestId(source, "series", "tmdb:274556:3:8"))
        assertEquals("tmdb:movie:315635", bridge.streamRequestId(source.copy(id = "org.nuvio.cee"), "movie", "tmdb:movie:315635"))
    }
    @Test fun forwardMovieMatchLearnsNativeIdentityWithoutChangingPlaybackId() = runBlocking {
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        val source = addon().copy(resources = listOf(AddonResource("stream", listOf("movie"), listOf("xt_"))))
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_v_src_42", "movie", "Example", releaseInfo = "2020"))))
        assertEquals("xt_v_src_42", ExternalAddonBridge(api, identities = identities).resolve(source, "movie", canonical(), null, null))
        verify(exactly = 1) { identities.learnExternalMovie(source.baseUrl, "xt_v_src_42", "42", "tt1234567") }
    }

    @Test fun nativeMovieOpeningLearnsOfficialIdentityAndReusesVerifiedEnrichment() = runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val tmdb = mockk<TmdbApi>()
        val identities = mockk<SharedPlaybackIdentity>(relaxed = true)
        every { identities.entries } returns kotlinx.coroutines.flow.MutableStateFlow<List<com.nuvio.tv.data.repository.PlaybackIdentity>>(emptyList())
        val source = addon().copy(resources = listOf(AddonResource("stream", listOf("movie"), listOf("xt_"))))
        val native = MetaDto("xt_v_src_42", "movie", "Example", releaseInfo = "2020", tmdbId = "42").toDomain("Movie")
        coEvery { tmdb.getMovieDetails(42, any(), any()) } returns Response.success(TmdbDetailsResponse(42, title = "Example", releaseDate = "2020-01-01"))
        coEvery { tmdb.getMovieExternalIds(42, any()) } returns Response.success(TmdbExternalIdsResponse(42, imdbId = "tt1234567"))
        val bridge = ExternalAddonBridge(api, tmdb, identities)
        repeat(2) {
            val enriched = bridge.enrichNative(source, native)
            assertEquals(native.id, enriched.id)
            assertEquals("tt1234567", enriched.imdbId)
            assertTrue(enriched.sharedProgressCompatible)
        }
        coVerify(exactly = 1) { tmdb.getMovieDetails(42, any(), any()) }
        verify(exactly = 1) { identities.learnExternalMovie(source.baseUrl, native.id, "42", "tt1234567") }
    }

    @Test fun verifiedEpisodeSurvivesTimeoutInALaterSearchCatalog() = retainedEpisode(true)
    @Test fun verifiedEpisodeSurvivesNetworkFailureInALaterSearchCatalog() = retainedEpisode(false)
    private fun retainedEpisode(timeout: Boolean) = kotlinx.coroutines.test.runTest {
        val canonical = canonical()
        every { canonical.videos } returns episodes()
        val addon = seriesAddon().copy(catalogs = seriesAddon().catalogs + CatalogDescriptor(
            ContentType.SERIES, id = "slow", name = "Slow", extra = listOf(CatalogExtra("search"))))
        coEvery { api.getCatalog(match { it.contains("/all/") }) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_42", "series", "Example", releaseInfo = "2020"))))
        coEvery { api.getCatalog(match { it.contains("/slow/") }) } coAnswers {
            if (!timeout) throw java.io.IOException("Catalog unavailable")
            kotlinx.coroutines.delay(30_000)
            Response.success(CatalogResponseDto(emptyList()))
        }
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xt_s_src_42", "series", "Example", videos = (1..2).map {
                VideoDto("xt_s_src_42:1:$it", title = "Episode $it", season = 1, episode = it)
            })))
        assertEquals(listOf("xt_s_src_42:1:1"), bridge.resolveAll(addon, "series", canonical, 1, 1))
        coVerify(exactly = 1) { api.getMeta(any()) }
    }
    @Test fun tmdbTurkishIdentityEnablesCountBasedNativeResolution() = runBlocking {
        // CI supplies the registered key; all requests in this test use mocks.
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val tmdb = mockk<com.nuvio.tv.data.remote.api.TmdbApi>()
        coEvery { tmdb.getTvDetails(42, any(), any()) } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbDetailsResponse(42, name = "المدينة البعيدة", originalLanguage = "tr"))
        coEvery { tmdb.getTvAlternativeTitles(42, any()) } returns Response.success(
            com.nuvio.tv.data.remote.api.TmdbAlternativeTitlesResponse())
        val canonical = canonical()
        every { canonical.name } returns "المدينة البعيدة"
        every { canonical.videos } returns episodes(season = 2, count = 35).map {
            it.copy(title = "", released = "2025-11-03")
        }
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", releaseInfo = "2018"))))
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", videos = (29..63).map {
                VideoDto("xt_s_src_1:2:$it", title = "AR-TR-S - Descriptive native label", released = "2026-01-05", season = 2, episode = it)
            })))
        val addon = seriesAddon().copy(catalogs = seriesAddon().catalogs + CatalogDescriptor(
            ContentType.SERIES, id = "slow", name = "Slow", extra = listOf(CatalogExtra("search"))))
        val bridge = ExternalAddonBridge(api, tmdb)
        assertEquals("xt_s_src_1:2:36", bridge.resolve(addon, "series", canonical, 2, 8))
        assertEquals("xt_s_src_1:2:37", bridge.resolve(addon, "series", canonical, 2, 9))
        coVerify(exactly = 1) { tmdb.getTvDetails(42, any(), any()) }
        coVerify(exactly = 1) { api.getCatalog(any()) }
    }
    @Test fun verifiedTurkishCountMappingDoesNotRequireMatchingEpisodeTitlesOrDates() {
        val expected = episodes().first().copy(released = "2026-01-01")
        val actual = VideoDto("xt_s_src_42:2:36", title = "Descriptive Turkish title",
            released = "2026-01-05", season = 2, episode = 36)
        assertTrue(bridge.compatible(expected, actual, arabic = false, count = 35, turkish = true))
        assertFalse(bridge.compatible(expected, actual.copy(id = ""), arabic = false, count = 35, turkish = true))
        assertFalse(bridge.compatible(expected, actual, arabic = false, count = 35))
    }
    @Test fun arabicLanguageBypassesCountTitleAndDateConditionsButDoesNotChangePartMapping() {
        assertTrue(bridge.acceptsLayout("ar", setOf(1, 2), setOf(1, 2, 3, 4)))
        assertFalse(bridge.acceptsLayout("tr", setOf(1, 2), setOf(1, 2, 3, 4)))
        assertTrue(bridge.acceptsLayout("tr", setOf(1, 2), setOf(1, 2)))
        assertTrue(bridge.acceptsLayout("tr", (1..35).toSet(), (1..8).toSet(), (1..8).toSet()))
        assertFalse(bridge.acceptsLayout("tr", (1..35).toSet(), (1..7).toSet(), (1..8).toSet()))
        val expected = episodes().first().copy(released = "2026-01-01")
        val actual = VideoDto("xt_s_src_42:1:1", title = "Different title", released = "2025-01-01", season = 1, episode = 1)
        assertTrue(bridge.compatible(expected, actual, arabic = true, count = 2))
        assertFalse(bridge.compatible(expected, actual, arabic = false, count = 2))
        assertTrue(bridge.compatible(expected, actual.copy(title = "AR - Some Show مترجم - S01E01", released = null), arabic = false, count = 2))
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
    @Test fun cumulativeSeasonsResolvePastEpisodeSevenWithOriginalXtreamIds() = runBlocking {
        val canonical = canonical()
        every { canonical.name } returns "المدينة البعيدة"
        every { canonical.releaseInfo } returns "2024"
        every { canonical.videos } returns (episodes(season = 1, count = 28) +
            episodes(season = 2, count = 35) + episodes(season = 3, count = 35).map {
                if (it.episode!! > 8) it.copy(released = "2099-01-01") else it
            })
        coEvery { api.getCatalog(any()) } returns Response.success(CatalogResponseDto(listOf(
            MetaPreviewDto("xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", releaseInfo = "2024"))))
        val source = (1..28).map { 1 to it } + (29..63).map { 2 to it } + (64..71).map { 3 to it }
        coEvery { api.getMeta(any()) } returns Response.success(MetaResponseDto(MetaDto(
            "xt_s_src_1", "series", "Uzak Şehir المدينة البعيدة", videos = source.map { (s, e) ->
                VideoDto("xt_s_src_1:$s:$e", title = "Uzak Şehir - S${s}E${e}", season = s, episode = e)
            })))
        assertEquals("xt_s_src_1:2:36", bridge.resolve(seriesAddon(), "series", canonical, 2, 8))
        assertEquals("xt_s_src_1:2:63", bridge.resolve(seriesAddon(), "series", canonical, 2, 35))
        assertEquals("xt_s_src_1:3:71", bridge.resolve(seriesAddon(), "series", canonical, 3, 8))
        assertNull(bridge.resolve(seriesAddon(), "series", canonical, 3, 9))
    }
    private val api = mockk<AddonApi>()
    private val bridge = ExternalAddonBridge(api)
    private fun addon(base: String = "https://example.test/config?token=private") = Addon(
        id = "test", name = "Better Stream", version = "14", description = null, logo = null,
        baseUrl = base, types = listOf(ContentType.MOVIE, ContentType.SERIES), resources = emptyList(),
        catalogs = listOf(CatalogDescriptor(ContentType.MOVIE, id = "all", name = "All",
            extra = listOf(CatalogExtra("search")))))
    private fun canonical(): Meta = mockk<Meta> {
        every { id } returns "tt1234567"
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
