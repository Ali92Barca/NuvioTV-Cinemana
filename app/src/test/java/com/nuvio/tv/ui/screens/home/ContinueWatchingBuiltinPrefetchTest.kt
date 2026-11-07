package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.data.builtincache.BuiltinStreamPrefetcher.Target
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingBuiltinPrefetchTest {
    @Test fun keepsExactNativeEpisodeAndAddsExternalIdentityForBothProviders() {
        val progress = WatchProgress(contentId = "tt123", contentType = "series", name = "Show",
            poster = null, backdrop = null, logo = null, videoId = "cee:10:2:3", season = 2, episode = 3,
            episodeTitle = null, position = 100, duration = 1000, lastWatched = 1)
        val item = ContinueWatchingItem.InProgress(progress)
        val targets = continueWatchingPrefetchTargets(listOf(item, item))
        assertEquals(listOf(Target("series", "cee:10:2:3"), Target("series", "tt123:2:3")), targets)
        assertFalse(targets.any { it.videoId.endsWith(":1:3") })
    }
}
