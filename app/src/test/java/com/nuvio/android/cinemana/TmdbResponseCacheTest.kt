package com.nuvio.android.cinemana
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TmdbResponseCacheTest {
    @Test fun expiresAndSeparatesCredentialsAndLanguage() = runBlocking {
        var time = 0L
        var loads = 0
        val cache = TmdbResponseCache(10, 2) { time }
        suspend fun read(key: String) = cache.get(key) { loads++; "{\"id\":$loads}" }
        assertEquals(read("key1/ar"), read("key1/ar"))
        read("key2/ar"); assertEquals(2, loads)
        read("key2/en"); assertEquals(3, loads)
        read("key1/ar"); assertEquals(4, loads)
        time = 10
        read("key1/ar"); assertEquals(5, loads)
    }
    @Test fun failureIsRetried() = runBlocking {
        val cache = TmdbResponseCache()
        try { cache.get("a") { error("offline") }; fail() } catch (_: IllegalStateException) {}
        assertEquals("{\"id\":1}", cache.get("a") { "{\"id\":1}" })
    }
}
