package com.nuvio.tv.data.repository

import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

/** Install one lazy request before starting it. ConcurrentMap.getOrPut may run
 * multiple factories, which would start duplicate eager network requests. */
internal fun <K, V> ConcurrentHashMap<K, Deferred<V>>.sharedRequest(
    key: K, createLazy: () -> Deferred<V>
): Deferred<V> = synchronized(this) {
    this[key] ?: createLazy().also { this[key] = it }
}.also { it.start() }
