package com.nuvio.tv.data.offline

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.*
import org.junit.Test

class OfflineWatchProgressTest {
    private val episode = OfflineMediaIdentity("series", "tt123", "tt123:1:2", 1, 2, setOf("tmdb:tv:42"))
    private fun progress(id: String = "tmdb:42", season: Int? = 1, episode: Int? = 2, time: Long = 20) =
        WatchProgress(id, "series", "Same title", null, null, null, "$id:$season:$episode", season, episode, null,
            position = 250, duration = 1000, lastWatched = time)
    @Test fun `known aliases share progress for only the exact episode`() {
        assertEquals(.25f, offlineWatchFraction(episode, OfflineWatchState(listOf(progress()))), .001f)
        assertEquals(0f, offlineWatchFraction(episode.copy(episode = 3, videoId = null), OfflineWatchState(listOf(progress()))), 0f)
        assertEquals(0f, offlineWatchFraction(episode.copy(season = 2, videoId = null), OfflineWatchState(listOf(progress()))), 0f)
        assertEquals(0f, offlineWatchFraction(episode.copy(contentId = "tt999", aliases = emptySet(), videoId = null), OfflineWatchState(listOf(progress()))), 0f)
    }
    @Test fun `the newest matching record wins across local and remote entries`() {
        val newer = progress(id = "tt123", time = 30).copy(position = 700)
        assertEquals(.7f, offlineWatchFraction(episode, OfflineWatchState(listOf(newer, progress()))), .001f)
    }
    @Test fun `completed history fills the bar but newer rewatches show their actual position`() {
        val watched = WatchedItem("tt123", "series", "Same title", 1, 2, 25)
        assertEquals(1f, offlineWatchFraction(episode, OfflineWatchState(listOf(progress()), listOf(watched))), 0f)
        assertEquals(.25f, offlineWatchFraction(episode, OfflineWatchState(listOf(progress(time = 30)), listOf(watched))), .001f)
    }
    @Test fun `films stay distinct from shows and unknown identities show no progress`() {
        val movie = OfflineMediaIdentity("movie", "tt123", "tt123", null, null)
        assertEquals(0f, offlineWatchFraction(movie, OfflineWatchState(listOf(progress()))), 0f)
        val filmProgress = progress("tt123", null, null).copy(contentType = "movie", progressPercent = 60f)
        assertEquals(.6f, offlineWatchFraction(movie, OfflineWatchState(listOf(filmProgress))), .001f)
        assertEquals(0f, offlineWatchFraction(null, OfflineWatchState(listOf(filmProgress))), 0f)
        assertEquals(0f, offlineWatchFraction(episode, OfflineWatchState()), 0f)
    }
}
