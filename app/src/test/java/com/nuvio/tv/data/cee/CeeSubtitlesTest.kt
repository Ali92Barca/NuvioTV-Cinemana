package com.nuvio.tv.data.cee

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.offline.supportsBuiltinDownload
import com.nuvio.tv.data.offline.OfflineMediaIdentity
import com.nuvio.android.cinemana.ProviderRequestState
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CeeSubtitlesTest {
    @Test fun objectResponseAttachesExactVideoSubtitlesToPlayableAndDownloadableStream() = runBlocking {
        val paths = mutableListOf<String>()
        val client = CeeClient(OkHttpClient()) { path ->
            synchronized(paths) { paths.add(path) }
            when {
                path.startsWith("allVideoInfo/id/21") -> """{"nb":"21","duration":"100","parent_skipping":"0"}"""
                path.startsWith("transcoddedFiles/id/21") -> """[{"videoUrl":"https://video.test/21.mp4","resolution":"1080p"}]"""
                path.startsWith("translationFiles/id/21") -> """{"translations":[
                    {"type":"ar","file":"https://sub.test/21.srt"},
                    {"type":"ar","file":"https://sub.test/21.vtt"},
                    {"type":"en","file":"https://sub.test/21-en.srt"}]}"""
                else -> error(path)
            }
        }
        val stream = CeeResourceAdapter(client) { com.nuvio.android.cinemana.TmdbCredentials("test", "ar") }.streams("21").streams!!.single().toDomain("Cee", null)
        assertEquals(listOf("ara", "eng"), stream.subtitles.map { it.lang })
        assertTrue(stream.subtitles.first().url.endsWith("21.vtt"))
        assertTrue(stream.subtitles.all { it.isStreamProvided })
        assertTrue(supportsBuiltinDownload(stream))
        assertEquals(setOf("transcoddedFiles/id/21", "translationFiles/id/21", "allVideoInfo/id/21"), paths.toSet())
        assertTrue(OfflineMediaIdentity("series", null, "cee:10:2:3", 2, 3).keys().contains("series|cee:10|2|3"))
        assertFalse(supportsBuiltinDownload(stream.copy(url = "https://video.test/live.m3u8")))
    }
    @Test fun legacyArrayResponseAndFailedSubtitleRequestsRemainSafe() = runBlocking {
        val legacy = CeeClient(OkHttpClient()) { """[{"translations":[]}]""" }
        assertTrue(legacy.subtitles("21").has("translations"))
        val failing = CeeClient(OkHttpClient()) { path ->
            if (path.startsWith("translationFiles")) throw java.io.IOException("offline")
            """[{"videoUrl":"https://video.test/21.mp4","resolution":"1080p"}]"""
        }
        val state = ProviderRequestState()
        val response = withContext(state) { CeeResourceAdapter(failing) { com.nuvio.android.cinemana.TmdbCredentials("test", "ar") }.streams("21") }
        assertEquals(1, response.streams!!.size)
        assertTrue(response.streams!!.single().subtitles!!.isEmpty())
        assertFalse(state.complete.get())
    }
}
