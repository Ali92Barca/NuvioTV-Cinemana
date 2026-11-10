package com.nuvio.tv.core.network

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProviderMetadataFetchTest {
    @Test fun metadataFetchCancelsARealSocketAndStripsDiagnosticInterceptors() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3_000 }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "metadata-fixture").apply { isDaemon = true } }
        val arrived = CompletableDeferred<Unit>()
        val closed = executor.submit<Boolean> {
            server.accept().use { socket ->
                socket.soTimeout = 3_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                arrived.complete(Unit)
                reader.read() == -1
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { error("Metadata must not reach diagnostic logging") }.build()
        val fetcher = ProviderMetadataFetch(http)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { fetcher.fetch("http://127.0.0.1:${server.localPort}/fixture") }
        try {
            withTimeout(3_000) { arrived.await() }
            pending.cancelAndJoin()
            assertTrue("Server must observe client socket closing", closed.get(3, TimeUnit.SECONDS))
        } finally {
            pending.cancelAndJoin()
            server.close()
            executor.shutdownNow()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
