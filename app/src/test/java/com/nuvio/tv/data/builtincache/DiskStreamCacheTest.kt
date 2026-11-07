package com.nuvio.tv.data.builtincache

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DiskStreamCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun payload(id: Int = 1) = """{"streams":[{"url":"https://video.test/$id.mp4","subtitles":[{"url":"https://video.test/$id.vtt","lang":"ara"}]}]}"""

    @Test fun survivesNewInstanceForAWeekAndKeepsSubtitles() = runBlocking {
        val dir = temporary.newFolder()
        var time = 100L
        var calls = 0
        val first = DiskStreamCache(dir, { time })
        val expected = first.get("profile1|cee|series|tt1:2:3") { calls++; payload() }
        time += 6L * 24 * 60 * 60 * 1000
        assertEquals(expected, DiskStreamCache(dir, { time }).get("profile1|cee|series|tt1:2:3") { calls++; payload(2) })
        assertEquals(1, calls)
        time += 24L * 60 * 60 * 1000
        assertEquals(payload(2), DiskStreamCache(dir, { time }).get("profile1|cee|series|tt1:2:3") { calls++; payload(2) })
        assertEquals(2, calls)
    }
    @Test fun separatesProvidersProfilesAndEpisodesAndCoalescesConcurrentCalls() = runBlocking {
        val cache = DiskStreamCache(temporary.newFolder())
        var calls = 0
        coroutineScope { (1..8).map { async { cache.get("1|cee|tt1:1:1") { calls++; delay(20); payload() } } }.awaitAll() }
        assertEquals(1, calls)
        for (key in listOf("2|cee|tt1:1:1", "1|cinemana|tt1:1:1", "1|cee|tt1:1:2")) cache.get(key) { calls++; payload(calls) }
        assertEquals(4, calls)
    }
    @Test fun invalidCachedLinkIsReloadedAndRefreshInvalidatesIt() = runBlocking {
        val cache = DiskStreamCache(temporary.newFolder())
        cache.get("a") { payload() }
        assertEquals(payload(2), cache.get("a", validate = { false }) { payload(2) })
        cache.invalidate("a")
        assertEquals(payload(3), cache.get("a") { payload(3) })
    }
    @Test fun failuresEmptyPartialAndCancellationAreNotCached() = runBlocking {
        val cache = DiskStreamCache(temporary.newFolder())
        try { cache.get("a") { throw IOException("offline") }; fail() } catch (_: IOException) {}
        cache.get("a") { """{"streams":[]}""" }
        cache.get("a") { payload().dropLast(1) + ",\"_cacheComplete\":false}" }
        try { cache.get("a") { throw CancellationException() }; fail() } catch (_: CancellationException) {}
        var loaded = false
        cache.get("a") { loaded = true; payload() }
        assertTrue(loaded)
    }
    @Test fun boundsEntriesAndRecoversFromCorruptFiles() = runBlocking {
        val dir = temporary.newFolder()
        val cache = DiskStreamCache(dir, maxEntries = 2)
        for (i in 1..4) cache.get("$i") { payload(i) }
        assertEquals(2, dir.listFiles()!!.size)
        for (file in dir.listFiles()!!) file.writeText("broken")
        assertEquals(payload(9), cache.get("4") { payload(9) })
    }
    @Test fun mediaServerThatRejectsHeadDoesNotCauseValidCacheToReload() = runBlocking {
        val cache = DiskStreamCache(temporary.newFolder())
        var loads = 0
        cache.get("movie") { loads++; payload() }
        val cached = cache.get("movie", validate = { value ->
            validateCachedMediaLink(value) { request ->
                if (request.method == "GET" && request.header("Range") == "bytes=0-0") 206 else 403
            }
        }) { loads++; payload(2) }
        assertEquals(payload(), cached)
        assertEquals(1, loads)
    }
}
