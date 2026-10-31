package com.nuvio.android.cinemana

import org.json.JSONObject

/** Instance-local, bounded metadata cache. Never use for playback URLs or subtitles. */
internal class TmdbResponseCache(
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val capacity: Int = 256,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry(val value: String, val created: Long)
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun get(key: String, load: suspend () -> String): String {
        synchronized(entries) {
            entries[key]?.let {
                if (now() - it.created < ttlMillis) return it.value
                entries.remove(key)
            }
        }
        // Failures, cancellation and malformed responses never enter the cache.
        val value = load()
        val json = JSONObject(value)
        require(!json.has("status_code") && json.optBoolean("success", true))
        synchronized(entries) {
            if (capacity > 0) {
                entries[key] = Entry(value, now())
                while (entries.size > capacity) entries.remove(entries.keys.first())
            }
        }
        return value
    }
}
