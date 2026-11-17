package com.nuvio.tv.data.repository

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class TraktFriendActivity(
    val friend: String, val title: String, val id: String, val episode: String?,
    val series: Boolean, val watching: Boolean, val poster: String?,
    val backdrop: String?, val avatar: String?, val timestamp: String?
)

internal suspend fun TraktAuthService.friendsActivity(): List<TraktFriendActivity>? =
    withContext(Dispatchers.IO) {
        suspend fun request(path: String): String? {
            val response = socialActivity(path) ?: return null
            response.errorBody()?.close()
            return try {
                response.body()?.use { it.string() }?.takeIf { it.isNotBlank() }
            } catch (_: IOException) {
                null
            }
        }

        for (feed in listOf("friends", "following")) {
            val rows = request("users/me/$feed/activities?extended=full&page=1&limit=12")
                ?.asArray()?.objects().orEmpty()
            val activity = rows.mapNotNull { it.activity(it.optJSONObject("user")) }
            if (activity.isNotEmpty()) return@withContext activity
        }

        val following = request("users/me/following?extended=full")?.asArray()
            ?: return@withContext null
        following.objects().mapNotNull { it.optJSONObject("user") }
            .mapNotNull { user ->
                val slug = user.optJSONObject("ids")?.optString("slug")?.takeIf(SLUG::matches)
                    ?: user.optString("username").takeIf(SLUG::matches)
                slug?.let { it to user }
            }.distinctBy { it.first }.take(8).mapNotNull { (slug, user) ->
                val watching = request("users/$slug/watching?extended=full")
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.activity(user, true)
                watching ?: request("users/$slug/history?limit=1&extended=full")
                    ?.asArray()?.optJSONObject(0)?.activity(user)
            }.sortedByDescending { it.watching }
    }

private val SLUG = Regex("[a-zA-Z0-9_-]{1,128}")
private val IMDB = Regex("tt[0-9]+")
private fun String.asArray() = runCatching { JSONArray(this) }.getOrNull()
private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)

private fun JSONObject.activity(user: JSONObject?, watching: Boolean = false): TraktFriendActivity? {
    val friend = user?.optString("name")?.takeIf { it.isNotBlank() }
        ?: user?.optString("username")?.takeIf { it.isNotBlank() } ?: return null
    val series = optJSONObject("show") != null
    val media = optJSONObject(if (series) "show" else "movie") ?: return null
    val ids = media.optJSONObject("ids") ?: return null
    val imdb = ids.optString("imdb").takeIf(IMDB::matches)
    val id = imdb ?: ids.optInt("tmdb").takeIf { it > 0 }?.let { "tmdb:$it" } ?: return null
    val title = media.optString("title").takeIf { it.isNotBlank() } ?: return null
    val episode = optJSONObject("episode")?.let {
        val season = it.optInt("season", -1)
        val number = it.optInt("number", -1)
        if (season >= 0 && number > 0) "S${season}E$number" else null
    }
    val isWatching = watching || optString("action") == "watching"
    val avatar = user?.optJSONObject("images")?.opt("avatar").let { image ->
        when (image) {
            is JSONObject -> image.stringOrNull("full")
            is JSONArray -> image.optString(0).takeIf { it.isNotBlank() }
            is String -> image
            else -> null
        }
    }?.let { url ->
        when {
            url.startsWith("https://") || url.startsWith("http://") -> url
            url.startsWith("//") -> "https:$url"
            '.' in url.substringBefore('/') -> "https://$url"
            else -> null
        }
    }
    val timestamp = if (isWatching) stringOrNull("started_at") else stringOrNull("watched_at")
    return TraktFriendActivity(
        friend, title, id, episode, series, isWatching,
        imdb?.let { "https://images.metahub.space/poster/medium/$it/img" },
        imdb?.let { "https://images.metahub.space/background/medium/$it/img" },
        avatar, timestamp ?: stringOrNull("activity_at") ?: stringOrNull("created_at")
    )
}

private fun JSONObject.stringOrNull(key: String): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() }
