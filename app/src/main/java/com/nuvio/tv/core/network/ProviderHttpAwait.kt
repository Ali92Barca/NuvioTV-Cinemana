package com.nuvio.tv.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Metadata text only. Cancellation closes the actual socket, not just its waiter. */
internal suspend fun Call.awaitProviderBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            val result = runCatching {
                response.use {
                    check(it.isSuccessful) { "Provider HTTP ${it.code}" }
                    requireNotNull(it.body) { "Provider response body missing" }.string()
                }
            }
            if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}
