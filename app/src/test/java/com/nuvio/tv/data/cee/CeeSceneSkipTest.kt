package com.nuvio.tv.data.cee

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.domain.model.ProviderSceneSkip
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CeeSceneSkipTest {
    @Test fun exactVideoMetadataTravelsWithEveryQualityWithoutUsingSeriesRoot() = runBlocking {
        val requested=mutableListOf<String>()
        val client=CeeClient(OkHttpClient()) { path ->
            synchronized(requested) { requested.add(path) }
            when {
                path == "allVideoInfo/id/21" -> """{"nb":"21","duration":"100","parent_skipping":"1","skippingDurations":{"start":[10,30],"end":[12,35]}}"""
                path == "translationFiles/id/21" -> """{"translations":[]}"""
                path == "transcoddedFiles/id/21" -> """[{"videoUrl":"https://video.test/21-1080.mp4","resolution":"1080p"},{"videoUrl":"https://video.test/21-720.mp4","resolution":"720p"}]"""
                else -> error(path)
            }
        }
        val streams=CeeResourceAdapter(client) { com.nuvio.android.cinemana.TmdbCredentials("test", "ar") }.streams("21").streams!!.map { it.toDomain("Cee",null) }
        assertEquals(2,streams.size)
        streams.forEach { stream ->
            val data=ProviderSceneSkip.fromPayload(stream.sceneSkipData,stream.addonName)!!
            assertEquals("21",data.videoId)
            assertEquals(2,data.ranges.size)
            assertNull(ProviderSceneSkip.fromPayload(stream.sceneSkipData,"Cinemana"))
        }
        assertEquals(setOf("allVideoInfo/id/21","translationFiles/id/21","transcoddedFiles/id/21"),requested.toSet())
    }
}
