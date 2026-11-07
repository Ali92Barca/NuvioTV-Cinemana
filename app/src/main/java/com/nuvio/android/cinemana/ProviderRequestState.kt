package com.nuvio.android.cinemana

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Handled request failures must not turn a partial result into a week-long cache entry. */
internal class ProviderRequestState : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderRequestState>
    val complete = AtomicBoolean(true)
}
