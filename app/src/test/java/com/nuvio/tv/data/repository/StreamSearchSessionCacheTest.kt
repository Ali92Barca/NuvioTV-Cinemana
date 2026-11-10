package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreamSearchSessionCacheTest {
    @Test fun `leaving interactive search cancels outstanding work and preserves ready links`() = runTest {
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cache = StreamSearchSessionCache(scope = this)
        cache.observe(key, false, stopWhenUnobserved = true) {
            flow {
                try { emit(ready); kotlinx.coroutines.awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }.let { stream -> stream.first { it is NetworkResult.Success } }
        stopped.await()
        assertEquals(ready.data, cache.peek(key))
        var restarted = false
        cache.observe(key, false, stopWhenUnobserved = true) {
            flow { restarted = true; emit(ready) }
        }.toList()
        assertTrue(restarted)
    }
    private val key = StreamSearchRequestKey(5, "series", "tt123:2:2", 2, 2, "scoped-config")
    private val ready = NetworkResult.Success(listOf(AddonStreams("ready-source", null, emptyList())))

    @Test fun `incomplete search keeps ready links briefly but not for fifteen minutes`() = runTest {
        var now = 0L
        var calls = 0
        val cache = StreamSearchSessionCache(scope = this, nowMs = { now })
        suspend fun load() = cache.observe(key, false, completedSuccessfully = { false }) {
            flow { calls++; emit(ready) }
        }.toList()
        load(); assertEquals(ready.data, cache.peek(key))
        now = 29_999; load(); assertEquals(1, calls)
        now = 30_000; load(); assertEquals(2, calls)
    }

    @Test fun `complete search still reuses its full cache lifetime`() = runTest {
        var now = 0L
        var calls = 0
        val cache = StreamSearchSessionCache(scope = this, nowMs = { now })
        suspend fun load() = cache.observe(key, false) { flow { calls++; emit(ready) } }.toList()
        load(); now = 30_001; load(); assertEquals(1, calls)
        now = 900_000; load(); assertEquals(2, calls)
    }

    @Test fun `failed source does not erase successful sibling links and expires promptly`() = runTest {
        var now = 0L
        val cache = StreamSearchSessionCache(scope = this, nowMs = { now })
        val results = cache.observe(key, false) {
            flow { emit(ready); delay(1); emit(NetworkResult.Error("another source failed")) }
        }.toList()
        assertEquals(ready, results.last())
        assertEquals(ready.data, cache.peek(key))
        now = 30_000; assertNull(cache.peek(key))
    }

    @Test fun `concurrent screens share one search and profiles never share results`() = runTest {
        var calls = 0
        val cache = StreamSearchSessionCache(scope = this)
        fun load(k: StreamSearchRequestKey) = cache.observe(k, false) {
            flow { calls++; delay(10); emit(ready) }
        }
        val a = async { load(key).toList() }
        val b = async { load(key).toList() }
        a.await(); b.await(); assertEquals(1, calls)
        load(key.copy(profileId = 6)).toList()
        assertEquals(2, calls); assertNull(cache.peek(key))
    }
}
