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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltinCatalogPosterTest {
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
