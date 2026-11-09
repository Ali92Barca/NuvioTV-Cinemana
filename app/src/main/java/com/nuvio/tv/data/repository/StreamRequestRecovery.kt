package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** One extra request per source/search, with only one recovery request active at a time. */
internal class StreamRequestRecovery(
    private val pause: suspend () -> Unit = { delay(750) },
    private val retryTimeoutMs: Long = 8_000
) {
    class Budget {
        private val used = AtomicBoolean()
        fun claim(): Boolean = used.compareAndSet(false, true)
    }

    private val retrySlot = Semaphore(1)

    suspend fun <T> request(
        budget: Budget,
        retryEmpty: (T) -> Boolean = { false },
        load: suspend () -> NetworkResult<T>
    ): NetworkResult<T> {
        val first = load()
        val retryable = when (first) {
            is NetworkResult.Error -> first.code == null || first.code == 408 || first.code in 500..599
            is NetworkResult.Success -> retryEmpty(first.data)
            NetworkResult.Loading -> false
        }
        if (!retryable || !budget.claim()) return first
        // The deadline includes queuing/backoff. Cancelling a search cancels its retry too.
        return withTimeoutOrNull(retryTimeoutMs) {
            retrySlot.withPermit {
                pause()
                load()
            }
        } ?: first
    }
}
