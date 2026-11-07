package com.nuvio.tv.data.builtincache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Private, bounded disk cache of link metadata, never video bytes. */
internal class DiskStreamCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val maxEntries: Int = 512,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    private val locks = Array(64) { Mutex() }
    private val commits = Mutex()
    private fun file(key: String) = File(directory, digest(key) + ".json")
    private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    suspend fun get(key: String, validate: suspend (String) -> Boolean = { true }, load: suspend () -> String): String =
        withContext(Dispatchers.IO) {
            lock(key).withLock {
                val target = file(key)
                val cached = try {
                    if (!target.isFile || target.length() > 2L * 1024 * 1024) null else {
                        val envelope = JSONObject(target.readText())
                        val age = now() - envelope.getLong("savedAt")
                        envelope.getString("payload").takeIf { age >= 0 && age < ttlMs && usable(it) }
                    }
                } catch (_: Exception) { null }
                if (cached != null && validate(cached)) return@withLock cached
                target.delete()
                val value = load()
                coroutineContext.ensureActive()
                if (usable(value) && value.toByteArray(Charsets.UTF_8).size < 1_900_000) {
                    commits.withLock {
                        try {
                            directory.mkdirs()
                            directory.listFiles { f -> f.extension == "tmp" }.orEmpty().forEach { it.delete() }
                            val temporary = File.createTempFile("links-", ".tmp", directory)
                            try {
                                temporary.writeText(JSONObject().put("savedAt", now()).put("payload", value).toString())
                                if (!temporary.renameTo(target)) {
                                    target.delete()
                                    temporary.renameTo(target)
                                }
                            } finally { temporary.delete() }
                            val files = directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
                            var bytes = files.sumOf { it.length() }
                            var count = files.size
                            for (old in files) {
                                if (count <= maxEntries && bytes <= maxBytes) break
                                val length = old.length()
                                if (old.delete()) { count--; bytes -= length }
                            }
                        } catch (_: java.io.IOException) { /* Cache failure must not block playback. */ }
                    }
                }
                value
            }
        }

    suspend fun invalidate(key: String) = withContext(Dispatchers.IO) { lock(key).withLock { file(key).delete(); Unit } }

    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun usable(raw: String): Boolean = try {
            val json = JSONObject(raw)
            val streams = json.optJSONArray("streams")
            json.optBoolean("_cacheComplete", true) && streams != null && streams.length() > 0 && (0 until streams.length()).all {
                val url = streams.optJSONObject(it)?.optString("url").orEmpty()
                url.startsWith("https://") || url.startsWith("http://")
            }
        } catch (_: Exception) { false }
    }
}
