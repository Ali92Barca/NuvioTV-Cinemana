package com.nuvio.tv.data.local

/** A local decision changed while a remote page was in flight. Do not advance
 * the remote cursor; the next sync can safely retry against the new state. */
internal class StaleWatchStatePullException : IllegalStateException(
    "Local watch state changed during remote read; retry synchronization."
)
