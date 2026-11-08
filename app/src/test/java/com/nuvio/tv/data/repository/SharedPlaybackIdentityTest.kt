package com.nuvio.tv.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SharedPlaybackIdentityTest {
    @Test fun `Daha 17 native metadata links all 23 verified episodes and collapses duplicate progress`() = verifyNativeSibling(317883, "tt42764103", "Daha 17", 23, 25, 24)
    @Test fun `Omur native metadata links six episodes despite seventh TMDB episode`() = verifyNativeSibling(322280, "tt39333720", "Ömür Usta", 6, 7, 7)
    private fun verifyNativeSibling(tmdbId: Int, imdb: String, title: String, count: Int, total: Int, aired: Int) = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(com.nuvio.tv.BuildConfig.TMDB_API_KEY.isNotBlank())
        val original = meta("cinemana-series:7", tmdbId.toString(), true, true)
        val videos = (1..count).map { original.videos.first().copy(id = "cinemana:${100+it}", episode = it) }
        registry.learn(original.copy(videos = videos), imdb)
        val nativeId = "xt_s_src_42"
        val native = original.copy(id = nativeId, name = title, videos = videos.map { it.copy(id = "$nativeId:1:${it.episode}") })
        val api = mockk<com.nuvio.tv.data.remote.api.TmdbApi>()
        coEvery { api.getTvDetails(tmdbId, any(), any()) } returns retrofit2.Response.success(com.nuvio.tv.data.remote.api.TmdbDetailsResponse(tmdbId, name = title, originalName = title, originalLanguage = "tr"))
        coEvery { api.getTvExternalIds(tmdbId, any()) } returns retrofit2.Response.success(com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse(tmdbId, imdb))
        coEvery { api.findByExternalId(imdb, any(), any()) } returns retrofit2.Response.success(com.nuvio.tv.data.remote.api.TmdbFindResponse(tvResults = listOf(com.nuvio.tv.data.remote.api.TmdbFindResult(tmdbId))))
        coEvery { api.getTvSeasonDetails(tmdbId, 1, any(), any()) } returns retrofit2.Response.success(com.nuvio.tv.data.remote.api.TmdbSeasonResponse(1,
            (1..total).map { com.nuvio.tv.data.remote.api.TmdbEpisode(episodeNumber = it, name = "Episode $it", airDate = if (it <= aired) "2000-01-01" else "2099-01-01") }))
        val addon = Addon(id = "org.betterstream.test", name = "IPTV", version = "14", description = null, logo = null,
            baseUrl = "https://example.test", types = listOf(ContentType.SERIES),
            resources = listOf(AddonResource("stream",listOf("series"),listOf("xt_"))), catalogs = emptyList())
        val bridge = com.nuvio.tv.data.iptv.ExternalAddonBridge(mockk(), api, registry)
        val enriched = bridge.enrichNative(addon, native)
        assertTrue(enriched.sharedProgressCompatible)
        assertEquals(count, enriched.verifiedPlaybackVideoIds?.size)
        assertEquals(nativeId, enriched.id)
        val public = progress(imdb,20,true).copy(episode = 2, videoId = "$imdb:1:2")
        val source = progress(nativeId,10,true).copy(episode = 2, videoId = "$nativeId:1:2")
        assertEquals(imdb, registry.progress(source).contentId)
        assertEquals("$nativeId:1:2", registry.progress(public,nativeId).videoId)
        assertEquals(1, registry.project(listOf(source,public)).size)
        assertEquals(20L, registry.project(listOf(source,public)).single().lastWatched)
    }
    @Test fun `verified sibling seasons require one consistent provider layout`() {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt1234")
        assertEquals(setOf(1), registry.verifiedSeriesSeason("123", 1))
        assertNull(registry.verifiedSeriesSeason("999", 1))
        registry.learn(meta("cee:9", series = true, safe = true).copy(videos = emptyList()), "tt1234")
        assertEquals(setOf(1), registry.verifiedSeriesSeason("123", 1))
        val peer = meta("cee:9", series = true, safe = true)
        registry.learn(peer.copy(videos = peer.videos + peer.videos.first().copy(id = "cee:9:1:2", episode = 2)), "tt1234")
        assertNull(registry.verifiedSeriesSeason("123", 1))
        assertEquals(setOf(1), registry.verifiedSeriesSeasonForCount("123", 1, 1))
        assertEquals(setOf(1,2), registry.verifiedSeriesSeasonForCount("123", 1, 2))
    }
    private val values = mutableMapOf<String, String>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
        every { putString(any(), any()) } answers { values[firstArg()] = secondArg(); this@mockk }
        every { commit() } returns true
    }
    private val prefs = mockk<SharedPreferences> {
        every { all } returns emptyMap()
        every { getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { edit() } returns editor
    }
    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns prefs
    }
    private val registry = SharedPlaybackIdentity(context)
    private fun meta(id: String, tmdb: String = "123", series: Boolean = false, safe: Boolean = false) = Meta(
        id = id, type = if (series) ContentType.SERIES else ContentType.MOVIE, name = id,
        poster = null, posterShape = PosterShape.POSTER, background = null, logo = null,
        description = null, releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null,
        director = emptyList(), cast = emptyList(), videos = if (series) listOf(Video(
            id = if (id.startsWith("cee:")) "$id:1:1" else "cinemana:999", title = "Episode", released = null,
            thumbnail = null, season = 1, episode = 1, overview = null)) else emptyList(), country = null,
        awards = null, language = null, links = emptyList(), tmdbId = tmdb, sharedProgressCompatible = safe)
    private fun progress(id: String, time: Long = 10, series: Boolean = false) = WatchProgress(
        contentId = id, contentType = if (series) "series" else "movie", name = id, poster = null,
        backdrop = null, logo = null, videoId = if (series) "$id:1:1" else id,
        season = if (series) 1 else null, episode = if (series) 1 else null, episodeTitle = null,
        position = 500, duration = 1000, lastWatched = time)

    @Test fun `movie aliases persist without changing playback metadata`() {
        val movie = meta("cinemana:7")
        registry.learn(movie, "tt1234")
        registry.learn(meta("cee:9"), "tt1234")
        assertEquals("tt1234", registry.canonical("cee:9"))
        assertTrue(registry.related("tt1234").containsAll(listOf("cinemana:7", "cee:9", "tmdb:123")))
        assertEquals("tt1234", SharedPlaybackIdentity(context).canonical("cinemana:7"))
        assertEquals("cinemana:7", movie.id)
    }

    @Test fun `Xtream movie progress and watched flags join Nuvio Cinemana and Cee in both directions`() {
        val native = "xt_v_src_42"
        registry.learn(meta("cinemana:7"), "tt1234")
        registry.learn(meta("cee:9"), "tt1234")
        registry.learnExternalMovie("https://example.test/a", native, "123", "tt1234")
        val playback = progress(native).copy(addonBaseUrl = "https://example.test/a")
        assertEquals("tt1234", registry.progress(playback).contentId)
        assertEquals(native, registry.progress(progress("tt1234"), native).videoId)
        assertEquals("cinemana:7", registry.progress(playback, "cinemana:7").contentId)
        assertEquals(native, registry.progress(progress("cee:9"), native).contentId)
        assertEquals(native, registry.watched(WatchedItem("tt1234", "movie", "Movie", null, null, 1), native).contentId)
        assertTrue(registry.externalRelated("tt1234").contains(native))
        assertTrue(registry.related("tt1234", false).contains(native))
        assertTrue(registry.related("cinemana:7", false).contains(native))
        assertEquals(listOf(native), registry.externalMovieSources("https://example.test/a", "123", "tt1234"))
        assertTrue(registry.externalMovieSources("https://example.test/other", "123", "tt1234").isEmpty())
        assertEquals("tt1234", SharedPlaybackIdentity(context).canonical(native, false))
        assertEquals(native, playback.contentId)
    }

    @Test fun `colliding movie IDs in subscriptions never mix different films`() {
        val native = "xt_v_src_42"
        registry.learnExternalMovie("https://example.test/a", native, "123", "tt1234")
        registry.learnExternalMovie("https://example.test/b", native, "456", "tt5678")
        assertEquals(native, registry.canonical(native, false))
        assertEquals(native, registry.progress(progress(native)).contentId)
        assertEquals("tt1234", registry.progress(progress(native).copy(addonBaseUrl = "https://example.test/a")).contentId)
        assertEquals("tt5678", registry.progress(progress(native).copy(addonBaseUrl = "https://example.test/b")).contentId)
        assertFalse(native in registry.related("tt1234", false))
        assertEquals("tt1234", registry.progress(progress("tt1234"), native).contentId)
        assertFalse(values.getValue("external_movies_v1").contains("https://"))
    }

    @Test fun `movie identity upgrades to IMDb and rejects a conflicting rematch`() {
        val native = "xt_v_src_42"
        registry.learnExternalMovie("https://example.test", native, "123", null)
        assertEquals("tmdb:movie:123", registry.canonical(native, false))
        registry.learnExternalMovie("https://example.test", native, "123", "tt1234")
        registry.learnExternalMovie("https://example.test", native, "456", "tt5678")
        assertEquals("tt1234", registry.canonical(native, false))
        assertEquals("tt1234", registry.progress(progress("tmdb:movie:123")).contentId)
    }

    @Test fun `numeric TMDB movie identity does not join a TV work of the same number`() {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt8888")
        registry.learnExternalMovie("https://example.test", "xt_v_src_42", "123", "tt1234")
        assertEquals(setOf("tmdb:123"), registry.related("tmdb:123"))
        assertEquals(setOf("tmdb:123"), registry.externalRelated("tmdb:123"))
        assertEquals("tt1234", registry.canonical("xt_v_src_42"))
        assertFalse("xt_v_src_42" in registry.related("tmdb:123", true))
        assertTrue("xt_v_src_42" in registry.related("tmdb:movie:123", false))
    }

    @Test fun `watched TV episodes cannot cross a numeric TMDB movie collision`() = kotlinx.coroutines.runBlocking {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt8888")
        registry.learnExternalMovie("https://example.test", "xt_v_src_42", "123", "tt1234")
        val repo = repository(mockk(relaxed = true), watched = listOf(WatchedItem("tt8888", "series", "Show", 1, 1, 1)))
        val flags = kotlinx.coroutines.withTimeout(5_000) { repo.watchedItems.first() }
        assertTrue(flags.any { it.contentId == "cinemana-series:7" })
        assertFalse(flags.any { it.contentId in setOf("xt_v_src_42", "tt1234", "tmdb:movie:123") })
    }

    @Test fun `one Xtream playlist can project two independent TMDB works and survives restart`() {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test/private-one", native, "$native:1:1", 1, 1, "tt101", 1, 1)
        registry.learnExternalEpisode("https://example.test/private-one", native, "$native:2:1", 2, 1, "tt202", 1, 1)
        val first = registry.progress(progress(native, series = true))
        val second = registry.progress(progress(native, series = true).copy(season = 2, videoId = "$native:2:1"))
        assertEquals("tt101", first.contentId)
        assertEquals("tt202", second.contentId)
        assertEquals(1, second.season)
        assertEquals("tt202:1:1", second.videoId)
        val inverse = registry.progress(progress("tt202", series = true), native)
        assertEquals(2, inverse.season)
        assertEquals("$native:2:1", inverse.videoId)
        assertEquals(native, registry.progress(progress(native, series = true).copy(season = 3)).contentId)
        val restored = SharedPlaybackIdentity(context)
        assertEquals("tt202", restored.progress(progress(native, series = true).copy(season = 2)).contentId)
        assertFalse(values["external_episodes_v1"].orEmpty().contains("private-one"))
    }

    @Test fun `cumulative native episode coordinates round trip without moving the watch position`() {
        val native = "xt_s_src_42"
        registry.learnExternalSeason("https://example.test/a", native, "tt101", 2, 2,
            (1..35).map { Triple(it + 28, "$native:2:${it + 28}", it) })
        val raw = progress(native, series = true).copy(season = 2, episode = 36, videoId = "$native:2:36")
        val public = registry.progress(raw)
        assertEquals("tt101:2:8", public.videoId)
        assertEquals(8, public.episode)
        assertEquals(raw.position, public.position)
        assertEquals(raw.duration, public.duration)
        val restored = SharedPlaybackIdentity(context)
        val inverse = restored.progress(public, native)
        assertEquals(raw.videoId, inverse.videoId)
        assertEquals(36, inverse.episode)
        assertEquals(2 to 36, restored.externalQuery("tt101", native, 2, 8))
        assertEquals(native, restored.progress(raw.copy(episode = 28)).contentId)
    }
    @Test fun `revoking a recut season keeps verified seasons and other subscription scopes`() {
        val native = "xt_s_src_42"
        val source = "https://example.test/a"
        registry.learnExternalEpisode(source, native, "$native:1:1", 1, 1, "tt101", 1, 1)
        registry.learnExternalEpisode(source, native, "$native:3:8", 3, 8, "tt101", 3, 8)
        registry.learnExternalEpisode("https://example.test/b", native, "$native:3:8", 3, 8, "tt101", 3, 8)
        registry.invalidateExternalSeason(source, native, 3, "tt101")
        val raw = progress(native, series = true).copy(addonBaseUrl = source)
        assertEquals("tt101", registry.progress(raw).contentId)
        assertEquals(native, registry.progress(raw.copy(season = 3, episode = 8)).contentId)
        assertEquals("tt101", registry.progress(raw.copy(season = 3, episode = 8,
            addonBaseUrl = "https://example.test/b")).contentId)
        assertEquals(native, SharedPlaybackIdentity(context).progress(raw.copy(season = 3, episode = 8)).contentId)
    }
    @Test fun `IPTV posters reuse only a unique scoped primary identity and ignore duplicate part aliases`() {
        val native = "xt_s_src_42"
        val source = "https://example.test/a"
        registry.learnExternalEpisode(source, native, "$native:1:1", 1, 1, "tt101", 1, 1)
        registry.learnExternalSeason(source, native, "tmdb:tv:2", 2, 1,
            listOf(Triple(1, "$native:2:1", 1)), allowPartAlias = true)
        assertEquals("tt101", registry.externalPosterImdb(native, source))
        registry.learnExternalEpisode("https://example.test/b", native, "$native:1:1", 1, 1, "tt202", 1, 1)
        assertNull(registry.externalPosterImdb(native, null))
        assertEquals("tt101", registry.externalPosterImdb(native, source))
        assertEquals("tt202", registry.externalPosterImdb(native, "https://example.test/b"))
        assertNull(registry.externalPosterImdb(native, "https://example.test/missing"))
        assertEquals("tt101", SharedPlaybackIdentity(context).externalPosterImdb(native, source))
    }
    @Test fun `same Xtream id in two subscriptions requires matching source scope`() {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test/account-a", native, "$native:1:1", 1, 1, "tt101", 1, 1)
        registry.learnExternalEpisode("https://example.test/account-b", native, "$native:1:1", 1, 1, "tt202", 1, 1)
        val raw = progress(native, series = true)
        assertEquals(native, registry.progress(raw).contentId)
        assertEquals("tt101", registry.progress(raw.copy(addonBaseUrl = "https://example.test/account-a")).contentId)
        assertEquals("tt202", registry.progress(raw.copy(addonBaseUrl = "https://example.test/account-b")).contentId)
        assertEquals(native, registry.progress(raw.copy(addonBaseUrl = "https://example.test/account-b"), "tt101").contentId)
        assertEquals(native, registry.progress(raw, "tt101").contentId)
        assertEquals("tt101", registry.progress(progress("tt101", series = true), native).contentId)
    }

    @Test fun `external canonical aliases join TMDB and IMDb without changing another work`() {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test/a", native, "$native:1:1", 1, 1, "tmdb:tv:123", 1, 1)
        registry.learnExternalCanonical("123", "tt101")
        assertEquals("tt101", registry.progress(progress(native, series = true)).contentId)
        assertEquals(native, registry.progress(progress("tmdb:123", series = true), native).contentId)
        assertEquals("tt999", registry.progress(progress("tt999", series = true)).contentId)
    }
    @Test fun `a duplicate TMDB part aliases the existing second season without joining first season episodes`() {
        val source = "https://example.test/a"
        val native = "xt_s_src_42"
        val duplicate = "tmdb:tv:338743"
        registry.learnExternalEpisode(source, native, "$native:1:1", 1, 1, "tt100", 1, 1)
        registry.learnExternalEpisode(source, native, "$native:2:1", 2, 1, "tt100", 2, 1)
        registry.learnExternalSeason(source, native, duplicate, 2, 1, listOf(Triple(1, "$native:2:1", 1)), allowPartAlias = true)
        registry.learnExternalSeason(source, native, "cinemana-series:22", 2, 1,
            listOf(Triple(1, "$native:2:1", 1)), allowPartAlias = true, canonicalVideos = mapOf(1 to "cinemana:2201"))
        val raw = progress(native, series = true).copy(season = 2)
        assertEquals("tt100:2:1", registry.progress(raw).videoId)
        assertEquals("tt100:2:1", registry.progress(progress(duplicate, series = true)).videoId)
        val card = registry.progress(progress("tt100", series = true).copy(season = 2), duplicate)
        assertEquals(duplicate, card.contentId)
        assertEquals(1, card.season)
        assertEquals("tt100", registry.progress(progress("tt100", series = true), duplicate).contentId)
        assertEquals("cinemana:2201", registry.progress(raw, "cinemana-series:22").videoId)
        assertEquals(2 to 1, registry.externalQuery(duplicate, "tt100", 1, 1))
        val flags = registry.watched(WatchedItem("tt100", "series", "Show", 2, 1, 1), duplicate)
        assertEquals(duplicate, flags.contentId)
        assertEquals(1, flags.season)
        assertEquals("tt100", registry.watched(WatchedItem("tt100", "series", "Show", 1, 1, 1), duplicate).contentId)
    }
    @Test fun `a native primary identity can be learned after the part card and survives restart`() {
        val native = "xt_s_src_42"
        registry.learnExternalSeason("https://example.test/a", native, "tmdb:tv:338743", 2, 1,
            listOf(Triple(1, "$native:2:1", 1)), allowPartAlias = true)
        registry.learnExternalEpisode("https://example.test/a", native, "$native:2:1", 2, 1, "tt100", 2, 1)
        val restarted = SharedPlaybackIdentity(context)
        val row = restarted.progress(progress("tmdb:tv:338743", series = true))
        assertEquals("tt100", row.contentId)
        assertEquals(2, row.season)
    }
    @Test fun `Xtream episode reads and writes preserve explicit profile and part coordinates`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test/a", native, "$native:2:1", 2, 1, "tt202", 1, 1)
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        val repo = repository(backend)
        every { backend.getEpisodeProgress(any(), any(), any(), any<Int>()) } answers {
            val found = firstArg<String>() == "tt202" && secondArg<Int>() == 1 && thirdArg<Int>() == 1 && arg<Int>(3) == 5
            flowOf(if (found) progress("tt202", series = true) else null)
        }
        val projected = repo.getEpisodeProgress(native, 2, 1, 5).first()
        assertEquals(native, projected?.contentId)
        assertEquals(2, projected?.season)
        assertNull(repo.getEpisodeProgress(native, 2, 1, 6).first())
        repo.saveProgress(progress(native, series = true).copy(season = 2, videoId = "$native:2:1"), 5, false)
        coVerify(exactly = 1) { backend.saveProgress(match { it.contentId == "tt202" && it.season == 1 && it.episode == 1 }, 5, false) }
        verify(exactly = 0) { backend.getEpisodeProgress("tt202", 2, 1, any<Int>()) }
    }
    @Test fun `a series match without verified episode compatibility remains independent`() {
        registry.learn(meta("cinemana-series:7", series = true), "tt1234")
        assertEquals("cinemana-series:7", registry.canonical("cinemana-series:7"))
    }
    @Test fun `verified artwork does not join an incompatible edition`() {
        val id = "cinemana-series:7"
        registry.learnArtwork(id, "tt1234")
        registry.learn(meta(id, series = true), "tt1234")
        assertEquals("tt1234", registry.artwork.value[id])
        assertEquals(id, registry.canonical(id))
    }

    @Test fun `partial episode binding keeps unknown episodes independent`() {
        val id = "cinemana-series:7"
        val series = meta(id, series = true, safe = true)
        val first = series.videos.single()
        registry.learn(series.copy(
            videos = listOf(first, first.copy(id = "cinemana:1000", episode = 2)),
            verifiedPlaybackVideoIds = setOf(first.id)
        ), "tt1234")
        assertEquals("tt1234", registry.progress(progress(id, series = true)).contentId)
        assertEquals(id, registry.progress(progress(id, series = true).copy(episode = 2)).contentId)
        assertTrue(SharedPlaybackIdentity(context).entries.value.single().partial)
    }

    @Test fun `movie and TV with same numeric TMDB ID never merge`() {
        registry.learn(meta("cinemana:7"), "tt1234")
        registry.learn(meta("cee:9", series = true, safe = true), "tt5678")
        assertFalse("cee:9" in registry.related("cinemana:7"))
        assertEquals(setOf("tmdb:123"), registry.related("tmdb:123"))
        assertEquals("tmdb:123", registry.canonical("tmdb:123"))
        assertEquals("tt1234", registry.progress(progress("tmdb:123")).contentId)
        assertEquals("tt5678", registry.progress(progress("tmdb:123", series = true)).contentId)
    }
    @Test fun `conflicting rematch cannot change an existing watch identity`() {
        registry.learn(meta("cinemana:7"), "tt1234")
        registry.learn(meta("cinemana:7", tmdb = "456"), "tt5678")
        assertEquals("tt1234", registry.canonical("cinemana:7"))
    }
    @Test fun `different providers with conflicting IMDb evidence never merge`() {
        registry.learn(meta("cinemana:7"), "tt1234")
        registry.learn(meta("cee:9"), "tt5678")
        assertEquals("cee:9", registry.canonical("cee:9"))
    }
    @Test fun `latest progress wins rather than highest watched percentage`() {
        registry.learn(meta("cinemana:7"), "tt1234")
        val old = progress("cinemana:7", 10).copy(position = 1000)
        val replay = progress("tt1234", 20)
        val projected = registry.project(listOf(old, replay))
        assertEquals(1, projected.size)
        assertEquals(500L, projected.single().position)
    }
    @Test fun `episode projection restores each providers real playback video ID`() {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt1234")
        val projected = registry.progress(progress("tt1234", series = true), "cinemana-series:7")
        assertEquals("cinemana:999", projected.videoId)
        assertEquals(1, projected.season)
        assertEquals(1, projected.episode)
    }
    @Test fun `ongoing series accepts verified available coordinates but rejects recut overflow and duplicates`() {
        val official = (1..10).map { 1 to it }.toSet()
        assertTrue(verifiedEpisodeCoordinates((1..4).map { 1 to it }, official))
        assertFalse(verifiedEpisodeCoordinates((1..20).map { 1 to it }, official))
        assertFalse(verifiedEpisodeCoordinates(listOf(1 to 1, 1 to 1), official))
        assertFalse(verifiedEpisodeCoordinates(emptyList(), official))
    }
    private fun repository(delegate: WatchProgressRepositoryImpl, identities: SharedPlaybackIdentity = registry,
        watched: List<WatchedItem> = emptyList(), activeProfile: MutableStateFlow<Int> = MutableStateFlow(1)): SharedWatchProgressRepository {
        every { delegate.allProgress } returns flowOf(emptyList())
        every { delegate.continueWatching } returns flowOf(emptyList())
        every { delegate.watchedItems } returns flowOf(watched)
        every { delegate.localIdentityProgress(any(), any(), any(), any()) } returns flowOf(null)
        every { delegate.localIdentityEpisodes(any(), any()) } returns flowOf(emptyMap())
        val resolver = mockk<SharedPlaybackResolver> {
            coEvery { resolve(any()) } coAnswers { registry.canonical(firstArg()) }
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns activeProfile }
        return SharedWatchProgressRepository(delegate, identities, resolver, profiles)
    }
    @Test fun `large watched history projects off collector thread and resolves each title only once`() = kotlinx.coroutines.runBlocking {
        val collector = Thread.currentThread()
        val native = "xt_s_src_42"
        registry.learnExternalSeason("https://example.test", native, "tt1234", 1, 1,
            (1..100).map { Triple(it, "$native:1:$it", it) })
        val tracked = spyk(registry)
        every { tracked.externalRelated(any()) } answers {
            assertNotSame(collector, Thread.currentThread())
            callOriginal()
        }
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        val repo = repository(backend, tracked, (1..100).map { WatchedItem("tt1234", "series", "Show", 1, it, 1L) })
        val result = kotlinx.coroutines.withTimeout(10_000) { repo.watchedItems.first() }
        assertEquals(200, result.size)
        assertTrue(result.any { it.contentId == native && it.episode == 100 })
        verify(exactly = 1) { tracked.externalRelated("tt1234") }
    }

    @Test fun `cached title relationships refresh after a new part and revoked season`() {
        val base = "https://example.test"
        val native = "xt_s_src_42"
        registry.learnExternalEpisode(base, native, "$native:1:1", 1, 1, "tt1234", 1, 1)
        assertEquals(setOf(native, "tt1234"), registry.externalRelated(native))
        registry.learnExternalEpisode(base, native, "$native:2:1", 2, 1, "tt5678", 1, 1)
        assertTrue("tt5678" in registry.externalRelated(native))
        registry.invalidateExternalSeason(base, native, 2, "tt5678")
        assertEquals(setOf(native, "tt1234"), registry.externalRelated(native))
    }
    @Test fun `mark watched dispatches once to canonical backend not once per alias`() = runTest {
        registry.learn(meta("cinemana:7"), "tt1234")
        registry.learn(meta("cee:9"), "tt1234")
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).markAsCompleted(progress("cinemana:7"), 1, true)
        coVerify(exactly = 1) { backend.markAsCompleted(match { it.contentId == "tt1234" && it.videoId == "tt1234" }, 1, true) }
        coVerify(exactly = 1) { backend.markAsCompleted(any(), any<Int>(), any()) }
    }
    @Test fun `a batch containing two native aliases emits one episode history entry`() = runTest {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt1234")
        registry.learn(meta("cee:9", series = true, safe = true), "tt1234")
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).markAsCompletedBatch(listOf(progress("cinemana-series:7", series = true), progress("cee:9", series = true)))
        coVerify(exactly = 1) { backend.markAsCompletedBatch(match { it.size == 1 && it.single().contentId == "tt1234" && it.single().videoId == "tt1234:1:1" }) }
    }
    @Test fun `unmark broadcasts one canonical removal and cleans aliases locally only`() = runTest {
        registry.learn(meta("cinemana:7"), "tt1234")
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).removeFromHistory("cinemana:7", null, null, null)
        coVerify(exactly = 1) { backend.removeFromHistory("tt1234", null, null, null) }
        coVerify(exactly = 1) { backend.removeFromHistory(any(), any(), any(), any()) }
        coVerify(exactly = 1) { backend.removeLocalIdentityAlias("cinemana:7", null, null, true, 1) }
    }
    @Test fun `unmark from cumulative IPTV coordinates removes canonical watched episode once`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test", native, "$native:2:36", 2, 36, "tt1234", 2, 8)
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).removeFromHistory(native, "$native:2:36", 2, 36)
        coVerify(exactly = 1) { backend.removeFromHistory("tt1234", null, 2, 8) }
        coVerify(exactly = 1) { backend.removeFromHistory(any(), any(), any(), any()) }
        coVerify(exactly = 1) { backend.removeLocalIdentityAlias(native, 2, 36, true, 1) }
    }
    @Test fun `unmark native second part removes only its separate TMDB card`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test", native, "$native:1:1", 1, 1, "tmdb:tv:313314", 1, 1)
        registry.learnExternalSeason("https://example.test", native, "tmdb:tv:338743", 2, 1,
            listOf(Triple(1, "$native:2:1", 1)), allowPartAlias = true)
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).removeFromHistory(native, "$native:2:1", 2, 1)
        coVerify(exactly = 1) { backend.removeFromHistory("tmdb:tv:338743", null, 1, 1) }
        coVerify(exactly = 1) { backend.removeFromHistory(any(), any(), any(), any()) }
        coVerify(exactly = 0) { backend.removeLocalIdentityAlias("tmdb:tv:313314", any(), any(), any(), any()) }
    }
    @Test fun `batch unmark maps cumulative episodes before dispatching canonical removal`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalSeason("https://example.test", native, "tt1234", 2, 2,
            listOf(Triple(36, "$native:2:36", 8), Triple(37, "$native:2:37", 9)))
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).removeFromHistoryBatch(native, null, listOf(Triple(2,36,"$native:2:36"), Triple(2,37,"$native:2:37")))
        coVerify(exactly = 1) { backend.removeFromHistoryBatch("tt1234", null,
            listOf(Triple(2,8,"tt1234:2:8"), Triple(2,9,"tt1234:2:9"))) }
        coVerify(exactly = 1) { backend.removeLocalIdentityAlias(native, 2, 36, true, 1) }
        coVerify(exactly = 1) { backend.removeLocalIdentityAlias(native, 2, 37, true, 1) }
    }
    @Test fun `removing progress from native episode projects canonical and local coordinates separately`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test", native, "$native:2:36", 2, 36, "tt1234", 2, 8)
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        repository(backend).removeProgress(native, 2, 36)
        coVerify(exactly = 1) { backend.removeProgress("tt1234", 2, 8) }
        coVerify(exactly = 1) { backend.removeLocalIdentityAlias(native, 2, 36, false, 1) }
    }
    @Test fun `batch history removal stops dispatching after profile changes`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test", native, "$native:1:1", 1, 1, "tt111", 1, 1)
        registry.learnExternalEpisode("https://example.test", native, "$native:2:1", 2, 1, "tt222", 1, 1)
        val active = MutableStateFlow(1)
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        coEvery { backend.removeFromHistoryBatch(any(), any(), any()) } coAnswers { active.value = 2 }
        repository(backend, activeProfile = active).removeFromHistoryBatch(native, null,
            listOf(Triple(1,1,"$native:1:1"), Triple(2,1,"$native:2:1")))
        coVerify(exactly = 1) { backend.removeFromHistoryBatch(any(), any(), any()) }
    }
    @Test fun `reads select newest alias progress and preserve requested source identity`() = runTest {
        registry.learn(meta("cinemana:7"), "tt1234")
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        val repo = repository(backend)
        every { backend.getProgress(any(), 1) } answers {
            flowOf(if (firstArg<String>() == "tt1234") progress("tt1234", 20) else progress(firstArg(), 10))
        }
        val result = repo.getProgress("cinemana:7", 1).first()
        assertEquals("cinemana:7", result?.contentId)
        assertEquals(20L, result?.lastWatched)
    }
    @Test fun `legacy local progress remains readable when active remote backend has no native record`() = runTest {
        registry.learn(meta("cinemana:7"), "tt1234")
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        val repo = repository(backend)
        every { backend.getProgress(any(), 1) } returns flowOf(null)
        every { backend.localIdentityProgress("cinemana:7", null, null, 1) } returns flowOf(progress("cinemana:7", 20))
        assertEquals(20L, repo.getProgress("tt1234", 1).first()?.lastWatched)
    }
    @Test fun `legacy IPTV movie progress remains readable through its canonical card with a remote backend`() = runTest {
        val native = "xt_m_src_42"
        registry.learnExternalMovie("https://example.test", native, "123", "tt1234")
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        val repo = repository(backend)
        every { backend.getProgress(any(), 1) } returns flowOf(null)
        every { backend.localIdentityProgress(native, null, null, 1) } returns flowOf(progress(native, 20))
        val restored = repo.getProgress("tt1234", 1).first()
        assertEquals("tt1234", restored?.contentId)
        assertEquals(20L, restored?.lastWatched)
    }
    @Test fun `legacy cumulative IPTV episode resumes through canonical season and episode with a remote backend`() = runTest {
        val native = "xt_s_src_42"
        registry.learnExternalEpisode("https://example.test", native, "$native:2:36", 2, 36, "tt1234", 2, 8)
        val backend = mockk<WatchProgressRepositoryImpl>(relaxed = true)
        val repo = repository(backend)
        every { backend.getEpisodeProgress(any(), any(), any(), 1) } returns flowOf(null)
        every { backend.localIdentityProgress(native, 2, 36, 1) } returns flowOf(
            progress(native, 20, true).copy(season = 2, episode = 36, videoId = "$native:2:36"))
        val restored = repo.getEpisodeProgress("tt1234", 2, 8, 1).first()
        assertEquals("tt1234", restored?.contentId)
        assertEquals(2, restored?.season)
        assertEquals(8, restored?.episode)
        assertEquals(20L, restored?.lastWatched)
    }
    @Test fun `TMDB-only identity retains media type and is resolvable by tracking writers`() {
        registry.learn(meta("cinemana:7"), null)
        val id = registry.canonical("cinemana:7")
        assertEquals("tmdb:movie:123", id)
        assertEquals(123, parseContentIds(id).tmdb)
        assertEquals(123L, com.nuvio.tv.core.tracking.parseTrackingExternalIds(id).tmdb)
    }
    @Test fun `downloaded native movie displays canonical progress without rewriting its identity`() {
        registry.learn(meta("cinemana:7"), "tt1234")
        val identity = com.nuvio.tv.data.offline.OfflineMediaIdentity("movie", "cinemana:7", "cinemana:7", null, null)
        val shared = progress("tt1234", 20)
        val rows = registry.related(shared.contentId).map { registry.progress(shared, it) }
        val fraction = com.nuvio.tv.data.offline.offlineWatchFraction(identity,
            com.nuvio.tv.data.offline.OfflineWatchState(rows))
        assertEquals(0.5f, fraction, 0.001f)
        assertEquals("cinemana:7", identity.contentId)
    }
    @Test fun `a new episode outside the verified coordinate map remains independent`() {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt1234")
        registry.learnExternalEpisode("https://example.test/a", "xt_s_src_42", "xt_s_src_42:1:1", 1, 1, "tt1234", 1, 1)
        registry.learnExternalEpisode("https://example.test/a", "xt_s_src_42", "xt_s_src_42:1:2", 1, 2, "tt1234", 1, 2)
        val next = progress("cinemana-series:7", series = true).copy(episode = 2)
        assertEquals("cinemana-series:7", registry.progress(next).contentId)
        assertEquals("cinemana-series:7", registry.progress(next, "xt_s_src_42").contentId)
        val external = progress("tt1234", series = true).copy(episode = 2)
        assertEquals("tt1234", registry.progress(external, "cinemana-series:7").contentId)
        assertFalse(registry.canProjectEpisode("cinemana-series:7", "tt1234", 1, 2))
    }
    private fun library(backend: LibraryRepositoryImpl, entries: List<LibraryEntry> = emptyList()): SharedLibraryRepository {
        every { backend.libraryItems } returns flowOf(entries)
        every { backend.sourceMode } returns flowOf(LibrarySourceMode.TRAKT)
        val resolver = mockk<SharedPlaybackResolver> { coEvery { resolve(any()) } coAnswers { registry.canonical(firstArg()) } }
        val local = mockk<com.nuvio.tv.data.local.LibraryPreferences> { coEvery { containsItem(any(), any(), any()) } returns false }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        return SharedLibraryRepository(backend, registry, resolver, local, profiles)
    }
    private fun entry(id: String, list: String) = LibraryEntry(id = id, type = "series", name = id,
        poster = null, background = null, logo = null, description = null, releaseInfo = null,
        imdbRating = null, genres = emptyList(), addonBaseUrl = null, listKeys = setOf(list))

    @Test fun `list membership badges include all verified source aliases`() = runTest {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt1234")
        registry.learn(meta("cee:9", series = true, safe = true), "tt1234")
        val repo = library(mockk(relaxed = true), listOf(entry("tt1234", "trakt:watchlist")))
        val ids = repo.membershipItemIds.first()
        assertTrue(("series" to "cinemana-series:7") in ids)
        assertTrue(("series" to "cee:9") in ids)
        assertTrue(("series" to "tt1234") in ids)
    }
    @Test fun `adding a native series sends one canonical list operation`() = runTest {
        registry.learn(meta("cinemana-series:7", series = true, safe = true), "tt1234")
        val backend = mockk<LibraryRepositoryImpl>(relaxed = true)
        coEvery { backend.toggleDefault(any(), any()) } returns com.nuvio.tv.core.tracking.TrackingMembershipApplyResult()
        library(backend).toggleDefault(LibraryEntryInput("cinemana-series:7", "series", "Show"), emptySet())
        coVerify(exactly = 1) { backend.toggleDefault(match { it.itemId == "tt1234" && it.tmdbId == 123 }, emptySet()) }
        coVerify(exactly = 1) { backend.toggleDefault(any(), any()) }
    }
    @Test fun `watchlist and favorite memberships remain distinct and mutation is not duplicated`() = runTest {
        registry.learn(meta("cee:9", series = true, safe = true), "tt1234")
        val backend = mockk<LibraryRepositoryImpl>(relaxed = true)
        coEvery { backend.getMembershipSnapshot(any()) } returns ListMembershipSnapshot(mapOf("trakt:watchlist" to false, "trakt:favorites" to true))
        coEvery { backend.applyMembershipChanges(any(), any(), any()) } returns com.nuvio.tv.core.tracking.TrackingMembershipApplyResult()
        val repo = library(backend)
        val item = LibraryEntryInput("cee:9", "series", "Show")
        val snapshot = repo.getMembershipSnapshot(item)
        assertEquals(false, snapshot.listMembership["trakt:watchlist"])
        assertEquals(true, snapshot.listMembership["trakt:favorites"])
        repo.applyMembershipChanges(item, ListMembershipChanges(mapOf("trakt:watchlist" to true)), emptySet())
        coVerify(exactly = 1) { backend.applyMembershipChanges(match { it.itemId == "tt1234" }, any(), any()) }
    }
    @Test fun `legacy and canonical entries render once while retaining both list memberships`() = runTest {
        registry.learn(meta("cee:9", series = true, safe = true), "tt1234")
        val repo = library(mockk(relaxed = true), listOf(entry("cee:9", "local"), entry("tt1234", "trakt:watchlist")))
        val items = repo.libraryItems.first()
        assertEquals(1, items.size)
        assertEquals(setOf("local", "trakt:watchlist"), items.single().listKeys)
    }
}
