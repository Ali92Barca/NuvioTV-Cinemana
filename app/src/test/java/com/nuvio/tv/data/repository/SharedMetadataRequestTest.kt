package com.nuvio.tv.data.repository

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SharedMetadataRequestTest {
    @Test fun `concurrent detail readers share one request without blocking different sources`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = ConcurrentHashMap<String, Deferred<Int>>()
        val starts = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        try {
            val readers = (1..32).map { async(Dispatchers.Default) {
                requests.sharedRequest("source-a|series|1") {
                    scope.async(start = CoroutineStart.LAZY) { starts.incrementAndGet(); gate.await(); 42 }
                }
            } }.awaitAll()
            assertTrue(readers.all { it === readers.first() })
            val other = requests.sharedRequest("source-b|series|1") { scope.async(start = CoroutineStart.LAZY) { 7 } }
            assertEquals(7, other.await())
            gate.complete(Unit)
            readers.forEach { assertEquals(42, it.await()) }
            assertEquals(1, starts.get())
        } finally { scope.cancel() }
    }
}
