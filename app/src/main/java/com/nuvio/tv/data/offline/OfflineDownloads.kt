package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.nuvio.tv.domain.model.Stream
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineTransfer(val id: Long, val status: Int, val downloaded: Long, val total: Long)
data class OfflineItem(val key: String, val title: String, val video: Long, val subtitles: List<Pair<Long, String>>,
    val identity: OfflineMediaIdentity? = null,
    val posterPath: String? = null,
    val thumbnailPath: String? = null,
    val sceneSkipPayload: String? = null,
    val sceneSkipAddon: String? = null,
    val releaseYear: String? = null,
    val downloadFolder: String? = null)

/** Android DownloadManager owns transfers across activity/process restarts. No URL is persisted in our index. */
@Singleton
class OfflineDownloads @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("cinemana_offline_v1", Context.MODE_PRIVATE)
    private fun direct(raw: String): Uri {
        val uri = Uri.parse(raw)
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) { "التنزيل يحتاج رابط HTTP مباشر" }
        return uri
    }
    @Synchronized fun list(): List<OfflineItem> {
        val array = JSONArray(prefs.getString("items", "[]"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val tracks = item.getJSONArray("subtitles")
            OfflineItem(item.getString("key"), item.getString("title"), item.getLong("video"),
                (0 until tracks.length()).map { i -> tracks.getJSONObject(i).let { it.getLong("id") to it.getString("lang") } },
                OfflineMediaIdentity.fromJson(item.optJSONObject("identity")),
                item.optString("posterPath").takeIf { it.isNotBlank() },
                item.optString("thumbnailPath").takeIf { it.isNotBlank() },
                item.optString("sceneSkipPayload").takeIf { it.isNotBlank() },
                item.optString("sceneSkipAddon").takeIf { it.isNotBlank() },
                item.optString("releaseYear").takeIf { it.matches(Regex("(?:19|20)[0-9]{2}")) },
                item.optString("downloadFolder").takeIf { it.isNotBlank() })
        }
    }
    /** Exact parent identity only. Never guess titles, aliases or provider episode IDs. */
    fun detailsMeta(type: String, id: String): com.nuvio.tv.domain.model.Meta? {
        val series = type.lowercase() in setOf("series", "tv")
        val items = list().filter { item ->
            val identity = item.identity
            identity != null && identity.contentId == id &&
                (identity.type.lowercase() in setOf("series", "tv")) == series
        }
        val first = items.firstOrNull() ?: return null
        fun artwork(path: String?): String? = path?.let { File(it) }?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
        val videos = if (!series) emptyList() else items.mapNotNull { item ->
            val identity = item.identity ?: return@mapNotNull null
            val season = identity.season ?: return@mapNotNull null
            val episode = identity.episode ?: return@mapNotNull null
            val videoId = identity.videoId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.nuvio.tv.domain.model.Video(id = videoId, title = item.title,
                released = null, thumbnail = artwork(item.thumbnailPath), season = season,
                episode = episode, overview = null, available = true)
        }.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode }))
        return com.nuvio.tv.domain.model.Meta(
            id = id, type = if (series) com.nuvio.tv.domain.model.ContentType.SERIES else com.nuvio.tv.domain.model.ContentType.MOVIE,
            name = first.identity?.displayName ?: first.title,
            poster = artwork(first.posterPath), posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
            background = null, logo = null,
            description = "معلومات محفوظة من التنزيلات. تعذر جلب التفاصيل الكاملة من المصدر.",
            releaseInfo = first.releaseYear, imdbRating = null, genres = emptyList(), runtime = null,
            director = emptyList(), cast = emptyList(), videos = videos, country = null,
            awards = null, language = null, links = emptyList())
    }

    private fun save(items: List<OfflineItem>) {
        val array = JSONArray(items.map { item ->
            JSONObject().put("key", item.key).put("title", item.title).put("video", item.video)
                .put("identity", item.identity?.toJson())
                .put("posterPath", item.posterPath)
                .put("thumbnailPath", item.thumbnailPath)
                .put("sceneSkipPayload", item.sceneSkipPayload)
                .put("sceneSkipAddon", item.sceneSkipAddon)
                .put("releaseYear", item.releaseYear)
                .put("downloadFolder", item.downloadFolder)
                .put("subtitles", JSONArray(item.subtitles.map { (id, lang) -> JSONObject().put("id", id).put("lang", lang) }))
        })
        check(prefs.edit().putString("items", array.toString()).commit()) { "تعذر حفظ قائمة التنزيلات" }
    }
    @Synchronized fun enqueue(
        stream: Stream,
        identity: OfflineMediaIdentity? = null,
        posterUrl: String? = null,
        thumbnailUrl: String? = null,
        releaseYear: String? = null
    ): OfflineItem {
        val raw = requireNotNull(stream.url) { "لا يوجد رابط مباشر" }
        val videoUri = direct(raw)
        require(!videoUri.path.orEmpty().lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mpd") }) { "تنزيل HLS/DASH غير مدعوم حالياً؛ اختر MP4 مباشر" }
        require(supportsBuiltinDownload(stream)) { "التنزيل متاح لمصادر Cinemana وCee المباشرة" }
        // Resolve once for the whole item: video and subtitles stay on the same volume.
        val destination = OfflineDownloadLocation(context).destinationFolder()
        val key = UUID.randomUUID().toString()
        val created = mutableListOf<Long>()
        fun add(uri: Uri, file: String, title: String, headers: Map<String, String> = emptyMap()): Long {
            val request = DownloadManager.Request(uri).setTitle(title)
                .setDestinationUri(Uri.fromFile(File(destination, file)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            headers.forEach { (name, value) -> request.addRequestHeader(name, value) }
            return manager.enqueue(request).also(created::add)
        }
        try {
            val title = (identity?.displayName?.takeIf { it.isNotBlank() } ?: stream.title?.lineSequence()?.firstOrNull())?.take(180) ?: "Cinemana"
            val video = add(videoUri, "$key-video.mp4", title, stream.behaviorHints?.proxyHeaders?.request.orEmpty())
            val tracks = stream.subtitles.mapIndexed { i, track ->
                add(direct(track.url), "$key-subtitle-$i", "$title • ${track.lang}", track.headers.orEmpty()) to track.lang
            }
            // Artwork belongs to the offline item, not Coil's evictable network cache.
            // Failure to fetch artwork must never interrupt a valid video download.
            val posterPath = runCatching { cacheArtwork(posterUrl, key, "poster") }.getOrNull()
            val thumbnailPath = runCatching { cacheArtwork(thumbnailUrl, key, "episode") }.getOrNull()
            // Persist the release-specific provider timing alongside the offline file.
            // Never reuse timing from a different release or video URL.
            val scenePayload = stream.sceneSkipData?.takeIf {
                it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 256 * 1024
            }
            val sceneAddon = stream.addonName.takeIf {
                it.equals("Cinemana", true) || it.equals("Cee", true)
            }
            val item = OfflineItem(key, title, video, tracks, identity, posterPath, thumbnailPath,
                if (sceneAddon != null) scenePayload else null, sceneAddon,
                releaseYear?.takeIf { it.matches(Regex("(?:19|20)[0-9]{2}")) }, destination.absolutePath)
            save(list() + item)
            return item
        } catch (error: Exception) {
            created.forEach { manager.remove(it) }
            throw error
        }
    }
    private fun cacheArtwork(raw: String?, key: String, kind: String): String? {
        val link = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = Uri.parse(link)
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return null
        val folder = File(context.filesDir, "offline-artwork").apply { mkdirs() }
        val target = File(folder, "$key-$kind.img")
        val temporary = File(folder, "$key-$kind.tmp")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
            }
            val mime = connection.contentType.orEmpty().lowercase()
            if (!mime.startsWith("image/")) return null
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        count += n
                        if (count > 4L * 1024 * 1024) error("Artwork too large")
                        output.write(buf, 0, n)
                    }
                    if (count == 0L) error("Empty artwork")
                }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.absolutePath
        } finally {
            temporary.delete()
            connection?.disconnect()
        }
    }

    /** Upgrade legacy generic artwork only when the exact episode's thumbnail is known.
     * Never touch the downloaded media, subtitles or watch progress. */
    fun upgradeEpisodeThumbnail(item: OfflineItem, episodeUrl: String) {
        val identity = item.identity ?: return
        if (identity.type.lowercase() !in setOf("series", "tv") || identity.episode == null) return
        val fingerprintKey = "episode_thumbnail_v6_${item.key}"
        if (prefs.getString(fingerprintKey, null) == episodeUrl &&
            item.thumbnailPath?.let { File(it).isFile } == true) return
        val path = runCatching { cacheArtwork(episodeUrl, item.key, "episode-v6") }.getOrNull() ?: return
        synchronized(this) {
            val current = list()
            if (current.none { it.key == item.key }) {
                File(path).delete()
                return
            }
            save(current.map { if (it.key == item.key) it.copy(thumbnailPath = path) else it })
            prefs.edit().putString(fingerprintKey, episodeUrl).apply()
        }
    }

    fun transfer(id: Long): OfflineTransfer? = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) null else OfflineTransfer(id,
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
    }
    fun locationUnavailable(item: OfflineItem): Boolean {
        val folder = item.downloadFolder ?: return false // Legacy records retain DownloadManager ownership.
        return runCatching { Environment.getExternalStorageState(File(folder)) != Environment.MEDIA_MOUNTED }
            .getOrDefault(true)
    }
    fun ready(item: OfflineItem): Boolean = !locationUnavailable(item) && (listOf(item.video) + item.subtitles.map { it.first }).all { id ->
        transfer(id)?.status == DownloadManager.STATUS_SUCCESSFUL && runCatching {
            context.contentResolver.openAssetFileDescriptor(uri(id), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
    fun matching(identity: OfflineMediaIdentity): List<OfflineItem> {
        val keys = identity.keys()
        if (keys.isEmpty()) return emptyList()
        return list().filter { it.identity?.keys()?.any(keys::contains) == true && ready(it) }
    }
    /** Upgrade legacy records only on an exact DownloadManager source-URL match. */
    @Synchronized fun associateLegacy(identity: OfflineMediaIdentity, streams: List<Stream>) {
        if (identity.keys().isEmpty()) return
        val urls = streams.filter(::supportsBuiltinDownload).mapNotNull { it.url }.toSet()
        if (urls.isEmpty()) return
        var changed = false
        val updated = list().map { item ->
            if (item.identity != null) item else {
                val raw = manager.query(DownloadManager.Query().setFilterById(item.video))?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) else null
                }
                if (raw != null && raw in urls) { changed = true; item.copy(identity = identity) } else item
            }
        }
        if (changed) save(updated)
    }
    fun uri(id: Long): Uri = requireNotNull(manager.getUriForDownloadedFile(id)) { "الملف غير مكتمل أو تم حذفه" }
    @Synchronized fun remove(key: String) {
        val items = list()
        val item = items.find { it.key == key } ?: return
        (listOf(item.video) + item.subtitles.map { it.first }).forEach { manager.remove(it) }
        listOfNotNull(item.posterPath, item.thumbnailPath).forEach { path ->
            runCatching {
                val artworkRoot = File(context.filesDir, "offline-artwork").canonicalFile
                val file = File(path).canonicalFile
                if (file.parentFile == artworkRoot) file.delete()
            }
        }
        save(items.filterNot { it.key == key })
    }
}
