package com.nuvio.tv.data.iptv

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Durable matching evidence only. Never store subscription URLs, tokens or playable links. */
@Singleton
class IptvIdentityCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val index get() = context.getSharedPreferences("iptv_identity_index_v1", Context.MODE_PRIVATE)
    internal fun scope(url: String) = MessageDigest.getInstance("SHA-256")
        .digest(url.trimEnd('/').toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(scope: String) = context.getSharedPreferences("iptv_identity_$scope", Context.MODE_PRIVATE)
    private fun remember(scope: String) {
        val known = index.getStringSet("scopes", mutableSetOf()).orEmpty()
        if (scope in known) return
        val scopes = known + scope
        check(index.edit().putStringSet("scopes", scopes).commit())
    }
    @Synchronized internal fun translated(url: String, id: String): Boolean = file(scope(url)).getBoolean("translated:$id", false)
    @Synchronized internal fun rememberCatalog(url: String, label: String, ids: List<String>) {
        if (!translatedCatalog(label)) return
        val scope = scope(url)
        val prefs = file(scope)
        val fresh = ids.filter { com.nuvio.tv.data.repository.privateAddonId(it) && !prefs.getBoolean("translated:$it", false) }
        if (fresh.isEmpty()) return
        remember(scope)
        val editor = prefs.edit()
        fresh.forEach { editor.putBoolean("translated:$it", true) }
        check(editor.commit())
    }
    @Synchronized internal fun work(url: String, id: String): JsonObject? = runCatching {
        file(scope(url)).getString("work:$id", null)?.let { JsonParser.parseString(it).asJsonObject }
    }.getOrNull()
    @Synchronized internal fun rememberWork(url: String, id: String, tmdb: String, imdb: String?, translated: Boolean) {
        if (tmdb.toIntOrNull()?.let { it > 0 } != true) return
        val existing = work(url, id)
        if (existing != null && existing.get("tmdb")?.asString != tmdb) return
        val data = (existing?.deepCopy() ?: JsonObject()).apply {
            addProperty("tmdb", tmdb)
            (imdb ?: existing?.get("imdb")?.asString)?.takeIf { Regex("tt[0-9]+").matches(it) }?.let { addProperty("imdb", it) }
            addProperty("translated", translated || existing?.get("translated")?.asBoolean == true)
        }
        val scope = scope(url)
        if (file(scope).getString("work:$id", null) == data.toString()) return
        remember(scope)
        check(file(scope).edit().putString("work:$id", data.toString()).commit())
    }
    @Synchronized internal fun rememberLayout(url: String, id: String, nativeLayout: String,
        providerLayout: String, verified: Set<String>) {
        val data = work(url, id) ?: return
        data.addProperty("nativeLayout", nativeLayout)
        data.addProperty("providerLayout", providerLayout)
        data.add("verifiedVideos", com.google.gson.JsonArray().apply {
            verified.filter { Regex("[A-Za-z0-9_:.-]{1,250}").matches(it) }.sorted().forEach { add(it) }
        })
        check(file(scope(url)).edit().putString("work:$id", data.toString()).commit())
    }
    @Synchronized internal fun saveEpisodes(scope: String, json: String) {
        if (file(scope).getString("episodes", null) == json) return
        remember(scope)
        check(file(scope).edit().putString("episodes", json).commit())
    }
    @Synchronized internal fun episodeFiles(): List<String> = index.getStringSet("scopes", mutableSetOf()).orEmpty()
        .mapNotNull { file(it).getString("episodes", null) }
    @Synchronized fun remove(url: String) {
        val scope = scope(url)
        check(file(scope).edit().clear().commit())
        check(index.edit().putStringSet("scopes", index.getStringSet("scopes", mutableSetOf()).orEmpty() - scope).commit())
    }
    companion object {
        internal fun translatedCatalog(label: String): Boolean =
            Regex("(?:^|\\s)مترجم(?:ة|ه)?(?:$|\\s)").containsMatchIn(ExternalAddonTitleMatcher.normalize(label))
    }
}
