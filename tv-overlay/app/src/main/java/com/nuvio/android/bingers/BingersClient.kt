package com.nuvio.android.bingers

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import com.nuvio.android.cinemana.CinemanaEpisodeMapping

/** Protocol based on Scroblarr's MIT-licensed Bingers integration; see third-party notice.
 * Session cookies never leave api.bingers.app; public catalog requests use no cookies.
 */
data class BingersResponse(val status: Int, val body: String, val cookies: List<String> = emptyList())
data class BingersMedia(val titles: List<String>, val year: Int?, val tmdb: Long?, val imdb: String?, val season: Int?, val episode: Int?)
data class BingersSession(val cookie: String, val userId: String)
data class BingersEntity(val kind: String, val id: String)
class BingersFailure(val status: Int, val reason: String) : Exception(reason)

class BingersClient(private val request: suspend (String, String, Map<String, String>, String?) -> BingersResponse) {
    companion object {
        const val API = "https://api.bingers.app"
        const val CATALOG = "https://catalog.bingers.app"
        fun tmdbId(raw: String): Long? {
            val parts = raw.trim().split(':')
            if (parts.firstOrNull() != "tmdb") return null
            val index = if (parts.getOrNull(1) in listOf("tv", "series", "movie")) 2 else 1
            return parts.getOrNull(index)?.toLongOrNull()?.takeIf { it > 0 }
        }
        fun completed(action: String, percent: Double) = action == "stop" && percent.isFinite() && percent in 80.0..100.0
        fun token(link: String): String {
            val uri = runCatching { URI(link.trim()) }.getOrNull()
            require(uri != null && uri.scheme == "https" && uri.host == "bingers.app" && uri.port == -1 && uri.userInfo == null && uri.path == "/m") { "الصق رابط الدخول الأصلي من رسالة Bingers" }
            val token = uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "token" }
                ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
            require(!token.isNullOrBlank() && token.length <= 4096) { "رابط الدخول غير صالح" }
            return token
        }
        private fun segment(value: String): String {
            require(value.matches(Regex("[a-zA-Z0-9_.-]{1,200}")) && value != "." && value != "..") { "Invalid catalog identifier" }
            return value
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }

    suspend fun login(link: String): BingersSession {
        val response = request("GET", "$API/auth/magic-link/verify?token=${encode(token(link))}", mapOf("Origin" to "https://bingers.app"), null)
        if (response.status !in 200..399) failure(response)
        val cookies = response.cookies.mapNotNull { value ->
            value.substringBefore(';').takeIf { it.substringBefore('=').endsWith("session_token") && it.substringAfter('=', "").isNotBlank() }
        }.joinToString("; ")
        if (cookies.isBlank()) throw BingersFailure(401, "رابط الدخول منتهي أو مستخدم؛ اطلب رابطاً جديداً")
        return BingersSession(cookies, session(cookies))
    }

    suspend fun session(cookie: String): String {
        val data = json("GET", "$API/auth/get-session", cookie)
        val id = data.optJSONObject("user")?.optString("id")
            ?: data.optJSONObject("session")?.optString("userId")
        if (id.isNullOrBlank()) throw BingersFailure(401, "أعد ربط حساب Bingers")
        return id
    }

    suspend fun resolve(media: BingersMedia): BingersEntity {
        val episode = media.episode != null
        if (episode) require(media.season != null && media.season >= 0 && media.episode!! > 0)
        val kind = if (episode) "show" else "movie"
        val matches = linkedMapOf<String, JSONObject>()
        for (title in media.titles.filter { it.isNotBlank() }.distinct().take(4)) {
            val rows = json("GET", "$API/search/titles?q=${encode(title)}&page=0&lang=en").optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(rows.length(), 12)) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("kind") != kind) continue
                val id = segment(row.optString("id"))
                val hash = row.optString("metadata")
                if (hash.isBlank()) continue
                val metadata = try { json("GET", "$CATALOG/catalog/$id/metadata@${segment(hash)}.json") }
                    catch (error: BingersFailure) { if (error.status in listOf(401, 403, 429)) throw error else continue }
                if (metadata.optString("kind", kind) != kind) continue
                val ids = metadata.optJSONArray("external_ids") ?: JSONArray()
                var verified = false
                var conflicting = false
                for (j in 0 until ids.length()) {
                    val ref = ids.optJSONObject(j) ?: continue
                    val source = ref.optString("source").lowercase()
                    val actual = ref.optString("id")
                    if (source in listOf("tmdb", "themoviedb.com") && media.tmdb != null) {
                        if (actual == media.tmdb.toString()) verified = true else conflicting = true
                    }
                    if (source == "imdb" && !media.imdb.isNullOrBlank()) {
                        if (actual.equals(media.imdb, true)) verified = true else conflicting = true
                    }
                }
                // Never fall back to title/year when supplied external IDs conflict.
                if (!verified && media.tmdb == null && media.imdb.isNullOrBlank() && !conflicting && media.year != null) {
                    val card = row.optJSONObject("card")
                    val names = mutableListOf(card?.optString("originalTitle").orEmpty())
                    card?.optJSONObject("titlesI18n")?.let { names.addAll(it.keys().asSequence().map { key -> it.optString(key) }.toList()) }
                    verified = metadata.optInt("year", -1) == media.year && names.any {
                        CinemanaEpisodeMapping.normalizeTitle(it) == CinemanaEpisodeMapping.normalizeTitle(title)
                    }
                }
                if (verified && !conflicting) matches[id] = row
            }
            if (matches.size == 1) break
        }
        val titleId = matches.keys.singleOrNull() ?: throw BingersFailure(422, "تعذر تحديد المحتوى في Bingers بثقة؛ لم تُسجل مشاهدة")
        if (!episode) return BingersEntity("movie", titleId)
        val versions = json("GET", "$CATALOG/catalog/$titleId/versions.json")
        val hash = versions.optJSONObject("files")?.optJSONObject("seasons")?.optString(media.season.toString()).orEmpty()
        if (hash.isBlank()) throw BingersFailure(422, "الموسم غير موجود في Bingers")
        val season = json("GET", "$CATALOG/catalog/$titleId/season-${media.season}@${segment(hash)}.json")
        val episodes = season.optJSONArray("episodes") ?: JSONArray()
        val ids = (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
            .filter { it.optInt("n", -1) == media.episode }.map { segment(it.optString("id")) }.distinct()
        return BingersEntity("episode", ids.singleOrNull() ?: throw BingersFailure(422, "الحلقة غير موجودة أو غير محددة في Bingers"))
    }

    /** Read first; failed reads must never be interpreted as an empty watch history. */
    suspend fun prepare(entity: BingersEntity, cookie: String): String? {
        val remote = json("GET", "$API/sync/pull?domains=entries&entityKind=${entity.kind}&entityId=${encode(entity.id)}", cookie)
        val entries = remote.optJSONArray("entries") ?: throw BingersFailure(502, "استجابة سجل Bingers غير مكتملة")
        val entry = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .firstOrNull { it.optString("entityKind") == entity.kind && it.optString("entityId") == entity.id }
        if (entry?.optBoolean("watched") == true) return null
        return JSONObject().put("clientBatchId", UUID.randomUUID().toString()).put("ops", JSONArray().put(
            JSONObject().put("opId", UUID.randomUUID().toString()).put("table", "entries")
                .put("pk", JSONObject().put("entityKind", entity.kind).put("entityId", entity.id))
                .put("fields", JSONObject().put("watched", true).put("plays", 1).put("batchId", JSONObject.NULL))
        )).toString()
    }

    /** Persist this exact batch before invoking push, then reuse it on retries. */
    suspend fun push(cookie: String, batch: String) { json("POST", "$API/sync/push", cookie, batch) }

    private suspend fun json(method: String, url: String, cookie: String? = null, body: String? = null): JSONObject {
        val headers = mutableMapOf("Accept" to "application/json", "Origin" to "https://bingers.app")
        if (cookie != null) {
            require(URI(url).host == "api.bingers.app")
            headers["Cookie"] = cookie
        }
        val response = request(method, url, headers, body)
        if (response.status !in 200..299) failure(response)
        val result = if (response.body.isBlank()) JSONObject() else runCatching { JSONObject(response.body) }.getOrElse { throw BingersFailure(502, "استجابة Bingers غير صالحة") }
        if (!result.isNull("error")) failure(response)
        return result
    }

    private fun failure(response: BingersResponse): Nothing {
        val code = runCatching { JSONObject(response.body).optJSONObject("error")?.optString("code").orEmpty() }.getOrDefault("")
        val reason = when {
            response.status == 402 || code.contains("subscription", true) || code.contains("premium", true) -> "Bingers يطلب اشتراكاً لهذه العملية؛ لم تتم المزامنة"
            response.status == 401 -> "انتهت جلسة Bingers؛ أعد ربط الحساب"
            response.status == 403 -> "Bingers رفض صلاحية المزامنة لهذا الحساب"
            response.status == 429 -> "طلبات كثيرة؛ أعد المحاولة لاحقاً"
            else -> "تعذر الاتصال بـ Bingers (HTTP ${response.status})؛ أعد المحاولة لاحقاً"
        }
        throw BingersFailure(response.status, reason)
    }
}
