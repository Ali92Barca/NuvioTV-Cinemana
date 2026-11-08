package com.nuvio.android.cinemana

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CinemanaStreamConcurrencyTest {
    @Test fun linkAndInfoRequestsStartTogetherWithoutLosingSubtitleOrEditionMetadata() = runBlocking {
        val started = mutableSetOf<String>()
        val bothStarted = CompletableDeferred<Unit>()
        val client = CinemanaClient(transport = { url ->
            val kind = if ("transcoddedFiles" in url) "links" else "info"
            started += kind
            if (started.size == 2) bothStarted.complete(Unit)
            bothStarted.await()
            if (kind == "links") {
                """[{"videoUrl":"https://video.test/1.mp4","resolution":"1080p","container":"MP4"}]"""
            } else {
                """{"nb":"1","en_title":"Fixture","duration":"7200","videoLanguages":{"en_title":"English"},"translations":[{"type":"ar","name":"Arabic","extention":"vtt","file":"https://subtitle.test/ar.vtt"}]}"""
            }
        })
        val adapter = CinemanaResourceAdapter(client, { TmdbCredentials("fixture", "en") },
            { error("Native movie playback must not need a TMDB request") })
        val payload = withTimeout(2_000) {
            JSONObject(adapter.handle("https://cinemana.nuvio.invalid/stream/movie/cinemana:1.json")!!)
        }
        assertEquals(setOf("info", "links"), started)
        val stream = payload.getJSONArray("streams").getJSONObject(0)
        assertEquals("https://video.test/1.mp4", stream.getString("url"))
        assertTrue(stream.getString("description").contains("Fixture"))
        assertTrue(stream.getString("description").contains("1080p"))
        assertEquals("https://subtitle.test/ar.vtt", stream.getJSONArray("subtitles").getJSONObject(0).getString("url"))
        assertEquals("cinemana-1-1080p", stream.getJSONObject("behaviorHints").getString("bingeGroup"))
    }
}
