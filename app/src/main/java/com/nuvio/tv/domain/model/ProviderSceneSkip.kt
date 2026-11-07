package com.nuvio.tv.domain.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class ProviderSceneRange(val startMs: Long, val endMs: Long)

data class ProviderSkipSegment(val kind: String, val startMs: Long, val endMs: Long)

data class ProviderSceneSkip(
    val provider: String,
    val videoId: String,
    val durationMs: Long,
    val ranges: List<ProviderSceneRange>,
    // Intro/outro from the SAME edition and provider; never Nuvio/IntroDB timing.
    val nativeSegments: List<ProviderSkipSegment> = emptyList(),
) {
    fun matchesDuration(actualMs: Long): Boolean =
        actualMs > 0 && durationMs > 0 && abs(actualMs - durationMs) <= 5000

    fun toPayload(): String = JSONObject().put("provider", provider).put("videoId", videoId)
        .put("durationMs", durationMs).put("ranges", JSONArray(ranges.map {
            JSONObject().put("startMs", it.startMs).put("endMs", it.endMs)
        })).put("nativeSegments", JSONArray(nativeSegments.map {
            JSONObject().put("kind", it.kind).put("startMs", it.startMs).put("endMs", it.endMs)
        })).toString()

    companion object {
        fun sourceFor(addon: String?): String? = when {
            addon.equals("Cinemana", true) -> "cinemana"
            addon.equals("Cee", true) -> "cee"
            else -> null
        }

        fun fromSource(provider: String, videoId: String, info: JSONObject): ProviderSceneSkip? {
            if (provider !in setOf("cinemana", "cee") || !videoId.matches(Regex("[0-9]+"))) return null
            // allVideoInfo may identify a record as `id` rather than `nb`.
            // Keep exact-ID verification to avoid applying another edition's cuts.
            val infoId = info.optString("nb").takeIf { it.isNotBlank() && it != "null" }
                ?: info.optString("id")
            if (infoId != videoId) return null
            // Providers may send duration as seconds or HH:MM:SS.
            val rawDuration = info.optString("duration").trim()
            val seconds = rawDuration.toDoubleOrNull() ?: run {
                val parts = rawDuration.split(':').map { it.toDoubleOrNull() }
                if (parts.size in 2..3 && parts.all { it != null }) {
                    parts.fold(0.0) { total, part -> total * 60 + (part ?: 0.0) }
                } else null
            }
            val duration = seconds?.takeIf { it.isFinite() && it > 0 && it < 172800 }?.let { (it * 1000).toLong() } ?: 0L
            // An incomplete API record cannot safely supply skip data. Do not cache
            // a zero-duration payload as if this edition has no scenes to skip.
            if (duration <= 0L) return null
            val cuts = info.optJSONObject("skippingDurations")
                ?: info.optString("skippingDurations").takeIf { it.trim().startsWith("{") }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val starts = cuts?.optJSONArray("start")
            val ends = cuts?.optJSONArray("end")
            val ranges = mutableListOf<ProviderSceneRange>()
            // Older records may omit the flag, but an explicit disable is authoritative.
            val skippingDisabled = info.has("parent_skipping") &&
                info.optString("parent_skipping").trim().lowercase(java.util.Locale.ROOT) in
                    setOf("0", "false", "off", "no")
            if (!skippingDisabled && duration > 0 && starts != null && ends != null &&
                starts.length() == ends.length() && starts.length() <= 2000) {
                for (i in 0 until starts.length()) {
                    val start = starts.optString(i).toDoubleOrNull() ?: continue
                    val end = ends.optString(i).toDoubleOrNull() ?: continue
                    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end * 1000 > duration + 1000) continue
                    ranges += ProviderSceneRange(floor(start * 1000).toLong(), ceil(end * 1000).toLong().coerceAtMost(duration))
                }
            }
            return ProviderSceneSkip(provider, videoId, duration, merge(ranges), parseNativeSegments(info, cuts, duration))
        }

        fun fromPayload(raw: String?, addon: String?): ProviderSceneSkip? = runCatching {
            val expected = sourceFor(addon) ?: return null
            if (raw == null || raw.length > 200000) return null
            val json = JSONObject(raw)
            if (json.optString("provider") != expected) return null
            val id = json.optString("videoId")
            if (!id.matches(Regex("[0-9]+"))) return null
            val duration = json.optLong("durationMs").coerceIn(0, 172800000)
            if (duration <= 0L) return null
            val arr = json.optJSONArray("ranges") ?: return null
            if (arr.length() > 2000) return null
            val ranges = (0 until arr.length()).mapNotNull { i ->
                val row = arr.optJSONObject(i) ?: return@mapNotNull null
                val start = row.optLong("startMs", -1)
                val end = row.optLong("endMs", -1)
                if (start < 0 || end <= start || end > duration) null else ProviderSceneRange(start, end)
            }
            val native = json.optJSONArray("nativeSegments")?.takeIf { it.length() <= 24 }
                ?.let { arr -> (0 until arr.length()).mapNotNull { i ->
                    val row = arr.optJSONObject(i) ?: return@mapNotNull null
                    val kind = row.optString("kind")
                    val start = row.optLong("startMs", -1)
                    val end = row.optLong("endMs", -1)
                    if (kind !in setOf("intro", "outro") || start < 0 || end <= start || end > duration) null
                    else ProviderSkipSegment(kind, start, end)
                } } ?: emptyList()
            ProviderSceneSkip(expected, id, duration, merge(ranges), native)
        }.getOrNull()

        /** Only explicit time RANGES, never estimated lengths, chapter heuristics or Nuvio timings.
         * Unknown provider field shapes are intentionally ignored until verified from real API samples.
         */
        private fun parseNativeSegments(info: JSONObject, cuts: JSONObject?, duration: Long): List<ProviderSkipSegment> {
            if (duration <= 0) return emptyList()
            val result = mutableListOf<ProviderSkipSegment>()
            fun read(kind: String, obj: JSONObject?) {
                if (obj == null) return
                // Provider-native objects must supply both start and end, in seconds.
                val start = obj.opt("start")?.toString()?.toDoubleOrNull() ?: return
                val end = obj.opt("end")?.toString()?.toDoubleOrNull() ?: return
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return
                val startMs = (start * 1000).toLong()
                val endMs = (end * 1000).toLong()
                if (endMs > duration || startMs >= endMs) return
                result.add(ProviderSkipSegment(kind, startMs, endMs))
            }
            fun readVariants(kind: String, root: JSONObject, key: String) {
                root.optJSONObject(key)?.let { read(kind, it) }
                root.optJSONArray(key)?.let { array ->
                    for (index in 0 until minOf(array.length(), 24)) {
                        read(kind, array.optJSONObject(index))
                    }
                }
            }
            for (root in listOfNotNull(info, cuts)) {
                for (key in listOf("intro", "opening", "skipIntro", "introSkipping")) {
                    readVariants("intro", root, key)
                }
                for (key in listOf("outro", "ending", "skipOutro", "outroSkipping")) {
                    readVariants("outro", root, key)
                }
            }
            return result.distinctBy { Triple(it.kind, it.startMs, it.endMs) }.take(8)
        }

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
