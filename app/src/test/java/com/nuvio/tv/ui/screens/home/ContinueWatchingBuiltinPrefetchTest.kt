package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.data.builtincache.BuiltinStreamPrefetcher.Target
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ContinueWatchingBuiltinPrefetchTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun remoteInputCancelsOptionalWorkAndRestartsOnlyAfterIdle() = runTest {
        val target = MutableStateFlow("episode")
        val input = MutableStateFlow(0L)
        var started = 0
        var cancelled = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectHomeIdlePrefetch(target, input) {
                started++
                try { awaitCancellation() } finally { cancelled++ }
            }
        }
        runCurrent(); advanceTimeBy(4_999); runCurrent()
        assertEquals(0, started)
        input.value = 1L; runCurrent()
        advanceTimeBy(4_999); runCurrent(); assertEquals(0, started)
        advanceTimeBy(1); runCurrent(); assertEquals(1, started)
        input.value = 2L; runCurrent(); assertEquals(1, cancelled)
        advanceTimeBy(5_000); runCurrent(); assertEquals(2, started)
        job.cancelAndJoin(); assertEquals(2, cancelled)
    }
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
