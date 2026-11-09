package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamRequestRecoveryTest {
    @Test fun `transient failure retries only once and returns recovered links`() = runTest {
        val recovery = StreamRequestRecovery(pause = { delay(750) })
        var calls = 0
        val result = recovery.request(StreamRequestRecovery.Budget()) {
            if (++calls == 1) NetworkResult.Error("temporary", 503) else NetworkResult.Success(listOf("ready"))
        }
        assertEquals(NetworkResult.Success(listOf("ready")), result)
        assertEquals(2, calls)
        assertEquals(750L, testScheduler.currentTime)
    }

    @Test fun `authentication missing and rate limited responses are not retried`() = runTest {
        for (code in listOf(400, 401, 403, 404, 429, -9999)) {
            var calls = 0
            StreamRequestRecovery().request<String>(StreamRequestRecovery.Budget()) {
                calls++; NetworkResult.Error("permanent", code)
            }
            assertEquals("code=$code", 1, calls)
        }
    }

    @Test fun `empty response retries only for a verified native episode`() = runTest {
        val recovery = StreamRequestRecovery(pause = {})
        var ordinary = 0
        recovery.request(StreamRequestRecovery.Budget()) { ordinary++; NetworkResult.Success(emptyList<String>()) }
        assertEquals(1, ordinary)
        var verified = 0
        recovery.request(StreamRequestRecovery.Budget(), retryEmpty = { v: List<String> -> v.isEmpty() }) {
            verified++; NetworkResult.Success(emptyList<String>())
        }
        assertEquals(2, verified)
    }

    @Test fun `parallel editions share one retry budget for their source`() = runTest {
        val recovery = StreamRequestRecovery(pause = {})
        val budget = StreamRequestRecovery.Budget()
        var calls = 0
        (1..10).map {
            async { recovery.request<String>(budget) { calls++; NetworkResult.Error("temporary", 502) } }
        }.awaitAll()
        assertEquals(11, calls)
    }

    @Test fun `only one recovery request runs at a time`() = runTest {
        val recovery = StreamRequestRecovery(pause = {})
        var active = 0
        var maximum = 0
        (1..3).map {
            async {
                var calls = 0
                recovery.request(StreamRequestRecovery.Budget()) {
                    if (++calls == 1) NetworkResult.Error("temporary", 503) else {
                        maximum = maxOf(maximum, ++active)
                        delay(100)
                        active--
                        NetworkResult.Success("ready")
                    }
                }
            }
        }.awaitAll()
        assertEquals(1, maximum)
    }

    @Test fun `cancellation during backoff does not issue a retry`() = runTest {
        val paused = CompletableDeferred<Unit>()
        val recovery = StreamRequestRecovery(pause = { paused.complete(Unit); delay(10_000) })
        var calls = 0
        val job = async {
            recovery.request<String>(StreamRequestRecovery.Budget()) { calls++; NetworkResult.Error("offline") }
        }
        paused.await(); job.cancel(); runCurrent()
        assertEquals(1, calls)
        assertTrue(job.isCancelled)
    }

    @Test fun `recovery has a bounded deadline and does not loop`() = runTest {
        var calls = 0
        val first = NetworkResult.Error("temporary", 500)
        val result = StreamRequestRecovery(pause = {}, retryTimeoutMs = 800).request<String>(StreamRequestRecovery.Budget()) {
            if (++calls == 1) first else { delay(10_000); NetworkResult.Success("late") }
        }
        assertEquals(first, result)
        assertEquals(2, calls)
        assertEquals(800L, testScheduler.currentTime)
    }
}
