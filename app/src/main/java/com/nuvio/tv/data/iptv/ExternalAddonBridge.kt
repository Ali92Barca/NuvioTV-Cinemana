package com.nuvio.tv.data.iptv

import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest

/** Search only advertised search catalogs; never infer Xtream IDs from canonical IDs. */
@Singleton
class ExternalAddonBridge @Inject constructor(private val api: AddonApi) {
    private data class SearchEntry(val created: Long, val candidates: List<ExternalAddonTitleMatcher.Candidate>)
    // Shared metadata only. Never cache streams, playback URLs, profile state, or raw subscription URLs.
    private val searches = object : LinkedHashMap<String, SearchEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchEntry>?) = size > 128
    }
    fun supports(addon: Addon, type: String): Boolean =
        addon.resources.any { it.name == "stream" && (it.types.isEmpty() || type in it.types) } &&
            catalogs(addon, type).isNotEmpty()

    private fun catalogs(addon: Addon, type: String) = addon.catalogs.filter { catalog ->
        catalog.apiType == type &&
            (catalog.extra.any { it.name == "search" } || "search" in catalog.extraSupported) &&
            catalog.extra.none { it.isRequired && it.name != "search" } &&
            catalog.extraRequired.none { it != "search" }
    }

    suspend fun resolve(addon: Addon, type: String, canonical: Meta, season: Int?, episode: Int?): String? =
        withTimeoutOrNull(15_000) {
            val key = MessageDigest.getInstance("SHA-256").digest(
                listOf(addon.baseUrl, addon.version, addon.catalogs.toString(), type, canonical.name).joinToString("\u0000").toByteArray()
            ).joinToString("") { "%02x".format(it) }
            val cached = synchronized(searches) { searches[key]?.takeIf { System.nanoTime() - it.created < 300_000_000_000L } }
            val candidates = cached?.candidates?.toMutableList() ?: mutableListOf()
            // Search across all advertised catalogs, independent of home-row selection.
            // If a catalog fails, do not select from an incomplete ambiguous result set.
            for (catalog in if (cached == null) catalogs(addon, type) else emptyList()) {
                val response = api.getCatalog(url(addon, "catalog/${encode(type)}/${encode(catalog.id)}/search=${encode(canonical.name)}.json"))
                if (!response.isSuccessful) return@withTimeoutOrNull null
                val body = response.body() ?: return@withTimeoutOrNull null
                candidates += body.metas.filterNotNull().filter { it.type == type }.mapNotNull { item ->
                    val id = item.id ?: return@mapNotNull null
                    val name = item.name ?: return@mapNotNull null
                    ExternalAddonTitleMatcher.Candidate(id, name, year(item.releaseInfo ?: item.released), item.imdbId, item.tmdbId)
                }
            }
            if (cached == null) synchronized(searches) { searches[key] = SearchEntry(System.nanoTime(), candidates.toList()) }
            val decision = ExternalAddonTitleMatcher.choose(
                listOf(canonical.name), year(canonical.releaseInfo ?: canonical.released), candidates,
                canonical.imdbId, canonical.tmdbId
            ) as? ExternalAddonTitleMatcher.Decision.Matched ?: return@withTimeoutOrNull null
            val originalId = decision.candidate.originalId
            if (type == "movie") return@withTimeoutOrNull originalId
            if (type != "series" || season == null || episode == null) return@withTimeoutOrNull null
            val expected = canonical.videos.singleOrNull { it.season == season && it.episode == episode }
                ?: return@withTimeoutOrNull null
            val response = api.getMeta(url(addon, "meta/${encode(type)}/${encode(originalId)}.json"))
            if (!response.isSuccessful) return@withTimeoutOrNull null
            val meta = response.body()?.meta ?: return@withTimeoutOrNull null
            if (meta.id != originalId || meta.type != type) return@withTimeoutOrNull null
            if (!canonical.imdbId.isNullOrBlank() && !meta.imdbId.isNullOrBlank() &&
                !canonical.imdbId.equals(meta.imdbId, ignoreCase = true)) return@withTimeoutOrNull null
            if (!canonical.tmdbId.isNullOrBlank() && !meta.tmdbId.isNullOrBlank() &&
                canonical.tmdbId != meta.tmdbId) return@withTimeoutOrNull null
            val canonicalNumbers = canonical.videos.filter { it.season == season }.mapNotNull { it.episode }.toSet()
            val nativeNumbers = meta.videos.orEmpty().filter { it.season == season }.mapNotNull { it.episode }.toSet()
            if (canonicalNumbers != nativeNumbers) return@withTimeoutOrNull null
            val video = meta.videos?.singleOrNull { it.season == season && it.episode == episode }
                ?: return@withTimeoutOrNull null
            // Numbers alone are insufficient: alternate cuts and split episodes exist.
            val expectedName = ExternalAddonTitleMatcher.normalize(expected.title)
            val actualName = ExternalAddonTitleMatcher.normalize(video.title ?: video.name.orEmpty())
            val generic = Regex("(?:episode|ep|الحلقه) [0-9]+")
            if (expectedName.isBlank() || generic.matches(expectedName) || expectedName != actualName)
                return@withTimeoutOrNull null
            val expectedDate = expected.released?.take(10)
            val actualDate = video.released?.take(10)
            if (expectedDate != null && actualDate != null && expectedDate != actualDate)
                return@withTimeoutOrNull null
            video.id.takeIf { it.isNotBlank() }
        }

    private fun year(value: String?): Int? = value?.let { Regex("(?:19|20)[0-9]{2}").find(it)?.value?.toIntOrNull() }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun url(addon: Addon, path: String): String {
        val base = addon.baseUrl.trimEnd('/')
        val query = base.indexOf('?')
        return if (query < 0) "$base/$path" else "${base.substring(0, query).trimEnd('/')}/$path${base.substring(query)}"
    }
}
