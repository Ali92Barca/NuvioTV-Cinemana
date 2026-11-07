package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltinCatalogPosterTest {
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
