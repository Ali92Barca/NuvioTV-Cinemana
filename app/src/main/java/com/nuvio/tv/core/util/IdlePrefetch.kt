package com.nuvio.tv.core.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/** Any input or target change cancels optional work, including an in-flight network request. */
internal suspend fun <T> collectIdlePrefetch(targets: Flow<T>, input: Flow<Long>, warm: suspend (T) -> Unit) {
    combine(targets, input) { batch, _ -> batch }.collectLatest { batch ->
        delay(5_000)
        warm(batch)
    }
}
