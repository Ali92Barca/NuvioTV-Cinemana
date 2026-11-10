package com.nuvio.tv.core.network

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.Call
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderHttpAwaitTest {
    @Test fun cancellingTheCoroutineCancelsTheSocketAndLateResponseIsClosedWithoutReading() = runTest {
        val call = mockk<Call>()
        lateinit var callback: Callback
        every { call.enqueue(any()) } answers { callback = firstArg() }
        every { call.cancel() } just Runs
        val job = async(start = CoroutineStart.UNDISPATCHED) { call.awaitProviderBody() }
        job.cancelAndJoin()
        verify(exactly = 1) { call.cancel() }
        val body = mockk<ResponseBody>(relaxed = true)
        callback.onResponse(call, response(body))
        verify(exactly = 1) { body.close() }
        verify(exactly = 0) { body.string() }
        callback.onFailure(call, IOException("cancelled"))
        assertTrue(job.isCancelled)
    }
    @Test fun successfulAndFailedResponsesAlwaysCloseTheBody() = runTest {
        for (code in listOf(200, 503)) {
            val call = mockk<Call>()
            val body = mockk<ResponseBody>(relaxed = true)
            every { body.string() } returns "metadata"
            every { call.enqueue(any()) } answers { firstArg<Callback>().onResponse(call, response(body, code)) }
            every { call.cancel() } just Runs
            if (code == 200) assertEquals("metadata", call.awaitProviderBody())
            else {
                try { call.awaitProviderBody(); fail("HTTP error should not become cached metadata") }
                catch (error: IllegalStateException) { assertEquals("Provider HTTP 503", error.message) }
            }
            verify(exactly = 1) { body.close() }
        }
    }
    private fun response(body: ResponseBody, code: Int = 200) = Response.Builder()
        .request(Request.Builder().url("https://provider.example/metadata").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()
}
