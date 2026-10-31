package com.nuvio.tv.domain.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class ProviderSceneRange(val startMs: Long, val endMs: Long)

data class ProviderSceneSkip(
    val provider: String,
    val videoId: String,
    val durationMs: Long,
    val ranges: List<ProviderSceneRange>,
) {
    fun matchesDuration(actualMs: Long): Boolean =
        actualMs > 0 && durationMs > 0 && abs(actualMs - durationMs) <= 5000

    fun toPayload(): String = JSONObject().put("provider", provider).put("videoId", videoId)
        .put("durationMs", durationMs).put("ranges", JSONArray(ranges.map {
            JSONObject().put("startMs", it.startMs).put("endMs", it.endMs)
        })).toString()

    companion object {
        fun sourceFor(addon: String?): String? = when {
            addon.equals("Cinemana", true) -> "cinemana"
            addon.equals("Cee", true) -> "cee"
            else -> null
        }

        fun fromSource(provider: String, videoId: String, info: JSONObject): ProviderSceneSkip? {
            if (provider !in setOf("cinemana", "cee") || !videoId.matches(Regex("[0-9]+")) ||
                info.optString("nb") != videoId) return null
            val seconds = info.optString("duration").toDoubleOrNull()
            val duration = seconds?.takeIf { it.isFinite() && it > 0 && it < 172800 }?.let { (it * 1000).toLong() } ?: 0L
            val cuts = info.optJSONObject("skippingDurations")
            val starts = cuts?.optJSONArray("start")
            val ends = cuts?.optJSONArray("end")
            val ranges = mutableListOf<ProviderSceneRange>()
            if (info.optString("parent_skipping") == "1" && duration > 0 && starts != null && ends != null &&
                starts.length() == ends.length() && starts.length() <= 2000) {
                for (i in 0 until starts.length()) {
                    val start = starts.optString(i).toDoubleOrNull() ?: continue
                    val end = ends.optString(i).toDoubleOrNull() ?: continue
                    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end * 1000 > duration + 1000) continue
                    ranges += ProviderSceneRange(floor(start * 1000).toLong(), ceil(end * 1000).toLong().coerceAtMost(duration))
                }
            }
            return ProviderSceneSkip(provider, videoId, duration, merge(ranges))
        }

        fun fromPayload(raw: String?, addon: String?): ProviderSceneSkip? = runCatching {
            val expected = sourceFor(addon) ?: return null
            if (raw == null || raw.length > 200000) return null
            val json = JSONObject(raw)
            if (json.optString("provider") != expected) return null
            val id = json.optString("videoId")
            if (!id.matches(Regex("[0-9]+"))) return null
            val duration = json.optLong("durationMs").coerceIn(0, 172800000)
            val arr = json.optJSONArray("ranges") ?: return null
            if (arr.length() > 2000) return null
            val ranges = (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONObject(i) ?: return@mapNotNull null
                val start = row.optLong("startMs", -1)
                val end = row.optLong("endMs", -1)
                if (start < 0 || end <= start || end > duration) null else ProviderSceneRange(start, end)
            }
            ProviderSceneSkip(expected, id, duration, merge(ranges))
        }.getOrNull()

        private fun merge(ranges: List<ProviderSceneRange>): List<ProviderSceneRange> {
            val result = mutableListOf<ProviderSceneRange>()
            for (range in ranges.sortedBy { it.startMs }) {
                val previous = result.lastOrNull()
                if (previous != null && range.startMs <= previous.endMs) {
                    result[result.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, range.endMs))
                } else result += range
            }
            return result
        }
    }
}

/** One seek per pending crossing, but rewinding into a scene skips it again. */
class ProviderSceneSkipSession(val data: ProviderSceneSkip) {
    private var pendingEndMs: Long? = null
    private var lastSeekAtMs = 0L

    fun nextSeek(positionMs: Long, durationMs: Long, enabled: Boolean, nowMs: Long): Long? {
        if (!enabled || !data.matchesDuration(durationMs)) { pendingEndMs = null; return null }
        val pending = pendingEndMs
        if (pending != null && positionMs >= pending) pendingEndMs = null
        if (pendingEndMs != null && nowMs - lastSeekAtMs in 0..1500) return null
        // Position polling is every 500 ms; a small lead avoids entering the marked scene.
        val range = data.ranges.firstOrNull { positionMs >= (it.startMs - 600).coerceAtLeast(0) && positionMs < it.endMs } ?: return null
        pendingEndMs = range.endMs
        lastSeekAtMs = nowMs
        return range.endMs
    }
}
