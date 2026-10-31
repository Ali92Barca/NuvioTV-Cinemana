package com.nuvio.tv.data.offline

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.nuvio.tv.domain.model.Stream
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineTransfer(val id: Long, val status: Int, val downloaded: Long, val total: Long)
data class OfflineItem(val key: String, val title: String, val video: Long, val subtitles: List<Pair<Long, String>>,
    val identity: OfflineMediaIdentity? = null)

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
                OfflineMediaIdentity.fromJson(item.optJSONObject("identity")))
        }
    }
    private fun save(items: List<OfflineItem>) {
        val array = JSONArray(items.map { item ->
            JSONObject().put("key", item.key).put("title", item.title).put("video", item.video)
                .put("identity", item.identity?.toJson())
                .put("subtitles", JSONArray(item.subtitles.map { (id, lang) -> JSONObject().put("id", id).put("lang", lang) }))
        })
        check(prefs.edit().putString("items", array.toString()).commit()) { "تعذر حفظ قائمة التنزيلات" }
    }
    @Synchronized fun enqueue(stream: Stream, identity: OfflineMediaIdentity? = null): OfflineItem {
        val raw = requireNotNull(stream.url) { "لا يوجد رابط مباشر" }
        val videoUri = direct(raw)
        require(!videoUri.path.orEmpty().lowercase().let { it.endsWith(".m3u8") || it.endsWith(".mpd") }) { "تنزيل HLS/DASH غير مدعوم حالياً؛ اختر MP4 مباشر" }
        require(supportsBuiltinDownload(stream)) { "التنزيل متاح لمصادر Cinemana وCee المباشرة" }
        val key = UUID.randomUUID().toString()
        val created = mutableListOf<Long>()
        fun add(uri: Uri, file: String, title: String, headers: Map<String, String> = emptyMap()): Long {
            val request = DownloadManager.Request(uri).setTitle(title)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file)
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
            val item = OfflineItem(key, title, video, tracks, identity)
            save(list() + item)
            return item
        } catch (error: Exception) {
            created.forEach { manager.remove(it) }
            throw error
        }
    }
    fun transfer(id: Long): OfflineTransfer? = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) null else OfflineTransfer(id,
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
    }
    fun ready(item: OfflineItem): Boolean = (listOf(item.video) + item.subtitles.map { it.first }).all { id ->
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
        save(items.filterNot { it.key == key })
    }
}
