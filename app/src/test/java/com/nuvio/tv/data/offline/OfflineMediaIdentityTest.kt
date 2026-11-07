package com.nuvio.tv.data.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineMediaIdentityTest {
    @Test fun `film bookmark matches only that film across known aliases`() {
        val saved = OfflineMediaIdentity("movie", "cinemana:900", "cinemana:900", null, null, setOf("imdb:tt42", "tmdb:movie:55"))
        val fromCatalog = OfflineMediaIdentity("movie", "tmdb:55", "tmdb:55", null, null)
        assertTrue(saved.keys().intersect(fromCatalog.keys()).isNotEmpty())
        assertTrue(saved.keys().intersect(fromCatalog.copy(contentId = "tmdb:56", videoId = "tmdb:56").keys()).isEmpty())
        assertTrue(saved.keys().intersect(fromCatalog.copy(type = "series", season = 1, episode = 1).keys()).isEmpty())
    }
    @Test fun `same episode matches IMDb and TMDB aliases without confusing neighbors`() {
        val saved = OfflineMediaIdentity("series", "tt123", "tt123:1:2", 1, 2, setOf("tmdb:tv:42"))
        val requested = OfflineMediaIdentity("tv", "tmdb:series:42", "provider-episode", 1, 2)
        assertTrue(saved.keys().intersect(requested.keys()).isNotEmpty())
        assertTrue(saved.keys().intersect(requested.copy(episode = 3).keys()).isEmpty())
        assertTrue(saved.keys().intersect(requested.copy(season = 2).keys()).isEmpty())
        assertTrue(saved.keys().intersect(requested.copy(contentId = "tmdb:43").keys()).isEmpty())
    }
    @Test fun `embedded episode ID can supply parent season and episode`() {
        val inferred = OfflineMediaIdentity("series", null, "tmdb:tv:42:1:2", null, null)
        val explicit = OfflineMediaIdentity("series", "tmdb:42", "other", 1, 2)
        assertEquals(explicit.keys(), inferred.keys())
        assertTrue(OfflineMediaIdentity("series", "tt123", "opaque", null, null).keys().isEmpty())
    }
    @Test fun `cinemana episodes match their show but not another episode or film`() {
        val saved = OfflineMediaIdentity("series", "cinemana-series:90", "cinemana:123", 1, 2)
        val requested = OfflineMediaIdentity("series", null, "cinemana-series:90:1:2", null, null)
        assertEquals(saved.keys(), requested.keys())
        assertTrue(saved.keys().intersect(requested.copy(videoId = "cinemana-series:90:1:3").keys()).isEmpty())
        assertTrue(saved.keys().intersect(saved.copy(type = "movie").keys()).isEmpty())
    }
    @Test fun `identity survives persistence and explicit wrong media type cannot match`() {
        val identity = OfflineMediaIdentity("series", "imdb:tt123", "tt123:0:2", 0, 2, setOf("tmdb:42"))
        assertEquals(identity, OfflineMediaIdentity.fromJson(identity.toJson()))
        assertNull(OfflineMediaIdentity.fromJson(null))
        assertTrue(OfflineMediaIdentity("series", "tmdb:movie:42", null, 1, 2).keys().isEmpty())
    }
}
