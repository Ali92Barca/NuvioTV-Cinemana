package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadEpisodeFocusTest {
    private fun episode(id: String, season: Int, episode: Int) = Video(id = id,
        title = id, released = null, thumbnail = null, season = season, episode = episode, overview = null)

    @Test fun `episode one stays selected regardless of episode ordering`() {
        val videos = listOf(episode("native-last", 1, 9), episode("native-first", 1, 1))
        assertEquals("native-first", exactDownloadEpisode(videos, 1, 1)?.id)
        assertEquals("native-first", exactDownloadEpisode(videos.reversed(), 1, 1)?.id)
    }
    @Test fun `same episode number in another season does not steal focus`() {
        val videos = listOf(episode("s2e1", 2, 1), episode("s1e1", 1, 1))
        assertEquals("s2e1", exactDownloadEpisode(videos, 2, 1)?.id)
    }
    @Test fun `missing requested episode does not select next episode`() {
        assertNull(exactDownloadEpisode(listOf(episode("last", 1, 9)), 1, 1))
    }
}
