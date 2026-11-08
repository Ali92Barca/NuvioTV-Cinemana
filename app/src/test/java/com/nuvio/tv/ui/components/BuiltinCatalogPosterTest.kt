package com.nuvio.tv.ui.components

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.SharedPlaybackIdentity
import com.nuvio.tv.data.repository.SharedPlaybackResolver
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltinCatalogPosterTest {
    @Test fun `native series badge follows manual watched changes without detail navigation`() = runBlocking {
        val prefs = mockk<SharedPreferences> { every { getBoolean(any(), any()) } returns true }
        val context = mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs }
        val id = "cinemana-series:7"
        val identities = mockk<SharedPlaybackIdentity> {
            every { entries } returns MutableStateFlow(listOf(com.nuvio.tv.data.repository.PlaybackIdentity(
                id, "tt1234", true, setOf(id,"tt1234"), mapOf((1 to 1) to "cinemana:8"))))
            every { artwork } returns MutableStateFlow(emptyMap())
            every { externalRevision } returns MutableStateFlow(0L)
        }
        val progress = mockk<WatchProgressRepository> {
            every { watchedItems } returns flowOf(emptyList())
            every { observeWatchedMovieIds() } returns flowOf(emptySet())
        }
        val completed = MutableStateFlow<Set<String>>(emptySet())
        val holder = mockk<WatchedSeriesStateHolder> { every { fullyWatchedSeriesIds } returns completed }
        val resolver = mockk<SharedPlaybackResolver> { every { seriesPosterCandidates } returns MutableStateFlow(emptyMap()) }
        val posters = BuiltinCatalogPosters(context, identities, resolver, progress, holder)
        val states = kotlinx.coroutines.channels.Channel<Boolean?>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch { posters.observe(id,false).collect { states.send(it.watched) } }
        try {
            withTimeout(5_000) {
                assertEquals(false,states.receive())
                completed.value=setOf("tt1234")
                assertEquals(true,states.receive())
                completed.value=emptySet()
                assertEquals(false,states.receive())
            }
        } finally { job.cancel(); states.close() }
    }
    @Test fun `IPTV keeps native artwork without reading identity or subscribing to watch history`() = runBlocking {
        val preferences = mockk<SharedPreferences> { every { getBoolean(any(), any()) } returns true }
        val context = mockk<Context> { every { getSharedPreferences(any(), any()) } returns preferences }
        val identities = mockk<SharedPlaybackIdentity>()
        val progress = mockk<WatchProgressRepository> {
            every { watchedItems } returns flow { error("IPTV poster subscribed to episode history") }
            every { observeWatchedMovieIds() } returns flow { error("IPTV poster subscribed to movie history") }
        }
        val watched = mockk<WatchedSeriesStateHolder> {
            every { fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        }
        val posters = BuiltinCatalogPosters(context, identities, mockk<SharedPlaybackResolver>(), progress, watched)
        val result = withTimeout(5_000) { posters.observe("xt_s_src_1", false, "https://example.test").first() }
        assertEquals(BuiltinPosterState(), result)
        verify { identities wasNot Called }
    }

    @Test fun `language chooses the requested BetterPosters template`() {
        val english = "https://btttr.cc/poster/imdb/poster-default/tt1234.jpg"
        assertEquals(english + "?lang=ar", betterPosterUrl("tt1234", true))
        assertEquals(english, betterPosterUrl("tt1234", false))
    }

    @Test fun `provider and TMDB IDs are never substituted into the IMDb template`() {
        listOf("cinemana:7", "cinemana-series:7", "cee:9", "tmdb:movie:123", "tt1234:1:1").forEach {
            assertNull(betterPosterUrl(it, true))
        }
    }
}
