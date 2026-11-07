package com.nuvio.tv.data.builtincache

import android.content.Context
import android.os.StatFs

internal enum class NuvioCacheKind(val key: String, val minimum: Int, val maximum: Int, val step: Int) {
    POSTERS("poster_mb", 250, 10_240, 250),
    DETAILS("details_mb", 64, 1_024, 64)
}

internal class NuvioCachePolicy(private val context: Context) {
    private val prefs = context.getSharedPreferences("nuvio_enhanced_cache_v1", Context.MODE_PRIVATE)
    private val mib = 1024L * 1024L

    fun customMb(kind: NuvioCacheKind): Int? =
        prefs.getInt(kind.key, 0).takeIf { it in kind.minimum..kind.maximum }

    fun setCustomMb(kind: NuvioCacheKind, value: Int?) {
        prefs.edit().putInt(kind.key, value?.coerceIn(kind.minimum, kind.maximum) ?: 0).apply()
    }

    var conservativeRam: Boolean
        get() = prefs.getBoolean("conservative_ram", false)
        set(value) { prefs.edit().putBoolean("conservative_ram", value).apply() }

    private fun availableMb(): Long = runCatching {
        StatFs(context.cacheDir.absolutePath).availableBytes / mib
    }.getOrDefault(0)

    fun hasWriteHeadroom(): Boolean = availableMb() > 256

    fun limitBytes(kind: NuvioCacheKind): Long {
        val free = availableMb()
        val requested = customMb(kind)?.toLong() ?: when (kind) {
            NuvioCacheKind.POSTERS -> (free / 16).coerceIn(64, 512)
            NuvioCacheKind.DETAILS -> (free / 32).coerceIn(64, 512)
        }
        val budget = (free - 256).coerceAtLeast(0)
        val other = if (kind == NuvioCacheKind.POSTERS) NuvioCacheKind.DETAILS else NuvioCacheKind.POSTERS
        val reserved = if (kind == NuvioCacheKind.POSTERS) (customMb(other)?.toLong() ?: 512L) else 0L
        val safe = if (kind == NuvioCacheKind.POSTERS) {
            (budget - reserved.coerceAtMost(budget / 3)).coerceAtLeast(0)
        } else budget / 3
        return minOf(requested, safe) * mib
    }
}
