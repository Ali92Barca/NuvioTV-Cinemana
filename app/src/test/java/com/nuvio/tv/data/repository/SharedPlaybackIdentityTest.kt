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
    private fun repository(delegate: WatchProgressRepositoryImpl): SharedWatchProgressRepository {
        every { delegate.allProgress } returns flowOf(emptyList())
        every { delegate.continueWatching } returns flowOf(emptyList())
        every { delegate.watchedItems } returns flowOf(emptyList())
        every { delegate.localIdentityProgress(any(), any(), any(), any()) } returns flowOf(null)
        every { delegate.localIdentityEpisodes(any(), any()) } returns flowOf(emptyMap())
        val resolver = mockk<SharedPlaybackResolver> {
            coEvery { resolve(any()) } coAnswers { registry.canonical(firstArg()) }
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        return SharedWatchProgressRepository(delegate, registry, resolver, profiles)
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
