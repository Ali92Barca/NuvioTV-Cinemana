package com.nuvio.tv.data.offline

import org.json.JSONArray
import org.json.JSONObject

/** Catalog IDs only. Never match episodes by title or download filename. */
data class OfflineMediaIdentity(
    val type: String,
    val contentId: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val aliases: Set<String> = emptySet(),
    val displayName: String? = null
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("contentId", contentId)
        .put("videoId", videoId).put("season", season).put("episode", episode).put("aliases", JSONArray(aliases.toList())).put("displayName", displayName)
    fun keys(): Set<String> {
        if (type.lowercase() !in setOf("movie", "series", "tv")) return emptySet()
        val series = type.lowercase() in setOf("series", "tv")
        val pieces = videoId.orEmpty().split(':')
        val inferredEpisode = if (pieces.size >= 3) pieces.lastOrNull()?.toIntOrNull() else null
        val inferredSeason = if (pieces.size >= 3) pieces.getOrNull(pieces.size - 2)?.toIntOrNull() else null
        val s = season ?: inferredSeason
        val e = episode ?: inferredEpisode
        if (series && (s == null || e == null || s < 0 || e < 1)) return emptySet()
        val videoRoot = if (series && inferredSeason != null && inferredEpisode != null)
            pieces.dropLast(2).joinToString(":") else videoId
        // Plain episode IDs are provider-local; they are not reliable parent identities.
        val derived = videoRoot?.takeIf { !series || it.startsWith("tt") || it.startsWith("imdb:") || it.startsWith("tmdb:") || it.startsWith("cinemana-series:") || it.startsWith("cee:") }
        return (aliases + listOfNotNull(contentId, derived)).filter { id ->
            if (series) !id.startsWith("tmdb:movie:") else !id.startsWith("tmdb:tv:") && !id.startsWith("tmdb:series:")
        }.mapNotNull { canonicalId(it) }.map {
            if (series) "series|$it|$s|$e" else "movie|$it"
        }.toSet()
    }

    companion object {
        fun fromJson(value: JSONObject?): OfflineMediaIdentity? {
            value ?: return null
            val aliases = value.optJSONArray("aliases") ?: JSONArray()
            return OfflineMediaIdentity(value.optString("type"),
                value.optString("contentId").takeIf { it.isNotBlank() }, value.optString("videoId").takeIf { it.isNotBlank() },
                if (value.has("season") && !value.isNull("season")) value.optInt("season") else null,
                if (value.has("episode") && !value.isNull("episode")) value.optInt("episode") else null,
                (0 until aliases.length()).map { aliases.optString(it) }.filter { it.isNotBlank() }.toSet(),
                value.optString("displayName").takeIf { it.isNotBlank() })
        }
        fun canonicalId(value: String): String? {
            val id = value.trim().removePrefix("imdb:")
            if (id.isBlank()) return null
            if (id.startsWith("tmdb:")) {
                val parts = id.split(':')
                val number = if (parts.getOrNull(1) in setOf("tv", "series", "movie")) parts.getOrNull(2) else parts.getOrNull(1)
                return number?.toIntOrNull()?.takeIf { it > 0 }?.let { "tmdb:$it" }
            }
            return id
        }
    }
}
