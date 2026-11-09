package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.EpisodeWatchIdentity
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDisplayIdentityTest {
    @Test fun `player labels use shared coordinates while playback selection remains native`() {
        val source=Video("xt_s_src_42:2:20","ABI S02E20",null,null,season=2,episode=20,overview=null,
            watchIdentity=EpisodeWatchIdentity("tt38282411",2,2))
        val state=PlayerUiState(currentVideoId=source.id,currentSeason=2,currentEpisode=20,episodesAll=listOf(source))
        assertEquals(2,state.displayEpisode)
        assertEquals("ABI S02E02",state.displayEpisodeTitle)
        assertEquals(20,state.currentEpisode)
        assertEquals(source.id,state.currentVideoId)
        val unmapped=state.copy(currentVideoId="unknown",currentEpisode=21)
        assertEquals(21,unmapped.displayEpisode)
    }
}
