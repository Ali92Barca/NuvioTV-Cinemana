package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class StreamSceneSkipSidecarTest {
    @Test fun exactUrlAndSourceAreRequiredAndAnotherStreamClearsMetadata() {
        val raw=ProviderSceneSkip("cee","21",100000,listOf(ProviderSceneRange(10000,12000))).toPayload()
        StreamSceneSkipSidecar.set("https://video.test/21.mp4",raw,"Cee")
        assertNotNull(StreamSceneSkipSidecar.forUrl("https://video.test/21.mp4","Cee"))
        assertNull(StreamSceneSkipSidecar.forUrl("https://video.test/22.mp4","Cee"))
        assertNull(StreamSceneSkipSidecar.forUrl("https://video.test/21.mp4","Cinemana"))
        StreamSceneSkipSidecar.set("https://other.test/video.mp4",null,"Torrentio")
        assertNull(StreamSceneSkipSidecar.forUrl("https://video.test/21.mp4","Cee"))
        StreamSceneSkipSidecar.set("file:///video.mp4",raw,"Cee")
        assertNull(StreamSceneSkipSidecar.forUrl("file:///video.mp4","Cee"))
    }
}
