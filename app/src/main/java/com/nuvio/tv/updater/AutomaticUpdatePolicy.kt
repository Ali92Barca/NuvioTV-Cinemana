package com.nuvio.tv.updater

internal object AutomaticUpdatePolicy {
    private const val INTERVAL_MS = 15 * 60 * 1000L

    fun shouldCheck(
        isDebug: Boolean,
        enabled: Boolean,
        checking: Boolean,
        downloading: Boolean,
        lastCheckAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (isDebug || !enabled || checking || downloading) return false
        return lastCheckAtMs == null || nowMs < lastCheckAtMs ||
            nowMs - lastCheckAtMs >= INTERVAL_MS
    }
}
