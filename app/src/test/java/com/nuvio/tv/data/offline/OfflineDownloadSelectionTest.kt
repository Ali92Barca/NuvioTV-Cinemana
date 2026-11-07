package com.nuvio.tv.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDownloadSelectionTest {

    @Test
    fun resumesOldestUnfinishedEvenWhenLaterEpisodeHasMoreProgress() {
        val values = listOf(1f, 0.25f, 0.50f, 0f)
        assertEquals(1, firstUnfinishedDownloadedEpisodeIndex(values))
    }

    @Test
    fun advancesToThirdEpisodeWhenSecondCompletes() {
        val values = listOf(1f, 1f, 0.50f, 0f)
        assertEquals(2, firstUnfinishedDownloadedEpisodeIndex(values))
    }

    @Test
    fun choosesNeverWatchedEpisodeBeforeLaterPartialEpisode() {
        assertEquals(
            1,
            firstUnfinishedDownloadedEpisodeIndex(listOf(1f, 0f, 0.8f))
        )
    }

    @Test
    fun noResumeWhenEverythingCompleted() {
        assertNull(firstUnfinishedDownloadedEpisodeIndex(listOf(1f, 1f)))
        assertNull(firstUnfinishedDownloadedEpisodeIndex(emptyList()))
    }

    @Test
    fun seriesProgressCountsOnlyDownloadedEpisodes() {
        assertEquals(
            2f / 3f,
            downloadedSeriesWatchFraction(listOf(1f, 1f, 0f)),
            0.0001f
        )
        assertEquals(
            0.5f,
            downloadedSeriesWatchFraction(listOf(1f, 0f)),
            0.0001f
        )
    }

    @Test
    fun seriesProgressSupportsPartialEpisodes() {
        assertEquals(
            0.4375f,
            downloadedSeriesWatchFraction(listOf(1f, 0.25f, 0.50f, 0f)),
            0.0001f
        )
        assertEquals(0f, downloadedSeriesWatchFraction(emptyList()), 0f)
    }
}
