package com.nuvio.tv.data.cee

import com.nuvio.tv.data.builtincache.BuiltinDetailsStore
import com.nuvio.android.cinemana.CinemanaTmdbMatcher
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.data.remote.dto.SubtitleDto
import com.nuvio.tv.data.remote.dto.SubtitleItemDto
import com.nuvio.tv.data.remote.dto.SubtitleResponseDto
import com.nuvio.tv.data.remote.dto.VideoDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts Cee catalog results into Nuvio-compatible metadata.
 * Cee identifiers remain separate from TMDB and Cinemana identifiers.
 */
class CeeResourceAdapter(
    private val client: CeeClient,
    private val verifyEpisodes: suspend (Int, List<CeeClient.Episode>, Set<BuiltinDetailsStore.EpisodeKey>?) -> Boolean = { _, _, _ -> false },
    private val detailsStore: BuiltinDetailsStore? = null,
    private val matchEpisodeLayout: suspend (Int, List<CeeClient.Episode>) -> List<CeeClient.Episode>? = { _, _ -> null },
    private val tmdbFetch: (suspend (String) -> String)? = null,
    private val tmdbCredentials: () -> com.nuvio.android.cinemana.TmdbCredentials,
) {
    companion object {
        const val BASE_URL = "https://cee.nuvio.invalid"
        const val MOVIES_CATALOG = "cee-movies"
        const val SERIES_CATALOG = "cee-series"
        const val DISCOVER_MOVIES_CATALOG = "cee-discover-movies"
        const val DISCOVER_SERIES_CATALOG = "cee-discover-series"
        const val SEARCH_MOVIES_CATALOG = "cee-search-movies"
        const val SEARCH_SERIES_CATALOG = "cee-search-series"
    }

    data class HomeCatalog(
        val id: String,
        val name: String,
        val type: String,
    )

    suspend fun homeCatalogs(language: String = "ar"): List<HomeCatalog> {
        val groups = client.homeGroups(language)
        return (0 until groups.length()).mapNotNull { index ->
            val group = groups.optJSONObject(index) ?: return@mapNotNull null
            val id = group.optString("groupsID")
                .takeIf { it.matches(Regex("[0-9]+")) }
                ?: return@mapNotNull null
            val name = group.optString("title")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val content = group.optJSONArray("content") ?: JSONArray()
            val kinds = (0 until content.length()).mapNotNull { itemIndex ->
                content.optJSONObject(itemIndex)?.optInt("kind")
            }
            val type = when {
                kinds.isNotEmpty() && kinds.all { it == 2 } -> "series"
                else -> "movie"
            }
            HomeCatalog("cee-home-$id", name, type)
        }.distinctBy { it.id }
    }

    suspend fun homeCatalog(
        catalogId: String,
        page: Int = 0,
    ): CatalogResponseDto {
        val groupId = catalogId.removePrefix("cee-home-")
        require(catalogId.startsWith("cee-home-") &&
            groupId.matches(Regex("[0-9]+"))) {
            "Invalid Cee Home catalog"
        }

        val items = client.homeGroupPage(groupId, page)
        return CatalogResponseDto(
            metas = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val type = when (item.optInt("kind")) {
                    1 -> "movie"
                    2 -> "series"
                    else -> return@mapNotNull null
                }
                item.toPreview(type)
            }.distinctBy { it.id }
        )
    }
    suspend fun catalog(
        catalogId: String,
        page: Int = 0,
        startYear: Int = 1900,
        endYear: Int = java.time.Year.now().value + 1,
        category: String? = null,
    ): CatalogResponseDto {
        val items = when (catalogId) {
            MOVIES_CATALOG -> client.latestMovies(page)
            SERIES_CATALOG -> client.latestSeries(page)
            DISCOVER_MOVIES_CATALOG -> client.discover(
                series = false,
                page = page,
                startYear = startYear,
                endYear = endYear,
                category = category
            )
            DISCOVER_SERIES_CATALOG -> client.discover(
                series = true,
                page = page,
                startYear = startYear,
                endYear = endYear,
                category = category
            )
            else -> return CatalogResponseDto()
        }

        val type = if (
            catalogId == SERIES_CATALOG ||
            catalogId == DISCOVER_SERIES_CATALOG
        ) "series" else "movie"

        return CatalogResponseDto(
            metas = (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.toPreview(type)
            }.distinctBy { it.id }
        )
    }
    // Search catalogs are separate from Home/Discover catalogs: no new rows on Home.
    // The background index is bounded and reused; do not crawl the entire service per keystroke.
    private data class SearchSnapshot(val createdMs: Long, val records: List<JSONObject>)
    private val searchIndexLock = kotlinx.coroutines.sync.Mutex()
    private val searchIndex = java.util.concurrent.ConcurrentHashMap<Boolean, SearchSnapshot>()
    private val searchIndexTtlMs = 15 * 60 * 1000L

    private suspend fun indexedCatalogRecords(series: Boolean): List<JSONObject> {
        val now = android.os.SystemClock.elapsedRealtime()
        searchIndex[series]?.takeIf { now - it.createdMs in 0 until searchIndexTtlMs }
            ?.let { return it.records }
        return searchIndexLock.withLock {
            val current = android.os.SystemClock.elapsedRealtime()
            searchIndex[series]?.takeIf { current - it.createdMs in 0 until searchIndexTtlMs }
                ?.let { return@withLock it.records }
            val found = linkedMapOf<String, JSONObject>()
            var complete = true
            fun add(record: JSONObject?) {
                if (record == null) return
                val id = record.optString("nb")
                if (id.matches(Regex("[0-9]+"))) found[id] = record
            }
            // A bounded snapshot from Discover (both movie and series indexes).
            for (page in 0 until 5) {
                val records = try { client.discover(series = series, page = page) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { complete = false; break }
                for (i in 0 until records.length()) add(records.optJSONObject(i))
                if (records.length() == 0) break
            }
            // Home groups include curated items absent from ordinary title search.
            val groups = try { client.homeGroups("ar") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { complete = false; JSONArray() }
            var checked = 0
            for (i in 0 until groups.length()) {
                if (checked >= 8) break
                val group = groups.optJSONObject(i) ?: continue
                val groupId = group.optString("groupsID")
                if (!groupId.matches(Regex("[0-9]+"))) continue
                val inline = group.optJSONArray("content")
                if (inline != null) for (j in 0 until inline.length()) {
                    val record = inline.optJSONObject(j) ?: continue
                    if (record.optInt("kind") == (if (series) 2 else 1)) add(record)
                }
                val page = try { client.homeGroupPage(groupId, 0) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { complete = false; continue }
                for (j in 0 until page.length()) {
                    val record = page.optJSONObject(j) ?: continue
                    if (record.optInt("kind") == (if (series) 2 else 1)) add(record)
                }
                checked++
            }
            val snapshot = found.values.toList()
            // Failures should not permanently cache an incomplete empty index.
            if (complete || snapshot.isNotEmpty()) {
                searchIndex[series] = SearchSnapshot(current, snapshot)
            }
            android.util.Log.d("CeeSearch", "index type=${if (series) "series" else "movie"} items=${snapshot.size} complete=$complete")
            snapshot
        }
    }

    suspend fun searchCatalog(keyword: String, series: Boolean, skip: Int = 0): CatalogResponseDto {
        val term = keyword.trim()
        if (term.isBlank()) return CatalogResponseDto()
        val unique = linkedMapOf<String, MetaPreviewDto>()
        fun collect(record: JSONObject?) {
            val item = record ?: return
            val preview = item.toPreview(if (series) "series" else "movie") ?: return
            val previewId = preview.id?.takeIf { it.isNotBlank() } ?: return
            unique.putIfAbsent(previewId, preview)
        }
        var directCount = 0
        for (page in 0 until 3) {
            val rows = try { client.search(term, series, page) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("CeeSearch", "direct search failed type=$series page=$page", e)
                break
            }
            directCount += rows.length()
            for (i in 0 until rows.length()) collect(rows.optJSONObject(i))
            if (rows.length() == 0) break
        }
        // Cee's direct search sometimes indexes a final ta marbuta as ha.
        val alternate = term.replace('ة', 'ه')
        if (alternate != term && unique.values.none { normalizeCeeSearch(it.name.orEmpty()) == normalizeCeeSearch(term) }) {
            val rows = try { client.search(alternate, series, 0) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { JSONArray() }
            for (i in 0 until rows.length()) collect(rows.optJSONObject(i))
        }
        // Search across cached Home and Discover content as well as CEE direct search.
        // Only includes bounded pages in the snapshot, never claims full-service enumeration.
        val needle = normalizeCeeSearch(term)
        for (item in indexedCatalogRecords(series)) {
            val english = normalizeCeeSearch(item.optString("en_title"))
            val arabic = normalizeCeeSearch(item.optString("ar_title"))
            val other = normalizeCeeSearch(item.optString("other_title"))
            if (english.contains(needle) || arabic.contains(needle) || other.contains(needle)) collect(item)
        }
        android.util.Log.d("CeeSearch", "queryLength=${term.length} type=$series direct=$directCount matches=${unique.size}")
        return CatalogResponseDto(metas = unique.values.drop(skip.coerceAtLeast(0)).take(24))
    }

    private fun normalizeCeeSearch(value: String): String = value.trim()
        .lowercase(java.util.Locale.ROOT)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[\u064B-\u065F\u0670]"), "")
        .replace(Regex("\\s+"), " ")

    private fun JSONObject.toPreview(type: String): MetaPreviewDto? {
        val videoId = optString("nb").takeIf {
            it.matches(Regex("[0-9]+"))
        } ?: return null

        val title = optString("en_title")
            .takeIf { it.isNotBlank() }
            ?: optString("ar_title").takeIf { it.isNotBlank() }
            ?: return null

        val imdbId = Regex("tt[0-9]+")
            .find(optString("imdbUrlRef"))
            ?.value
        val malId = Regex("""myanimelist\.net/anime/([0-9]+)""", RegexOption.IGNORE_CASE)
            .find(optString("imdbUrlRef"))
            ?.groupValues?.getOrNull(1)?.toLongOrNull()

        return MetaPreviewDto(
            id = "cee:${if (type == "series") optString("rootSeries").takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: videoId else videoId}",
            type = type,
            name = title,
            poster = optString("imgObjUrl").takeIf { it.startsWith("https://") },
            description = optString("ar_content").takeIf { it.isNotBlank() }
                ?: optString("en_content").takeIf { it.isNotBlank() },
            releaseInfo = optString("year").takeIf { it.isNotBlank() },
            imdbId = imdbId,
            malId = malId
        )
    }

    suspend fun streams(videoId: String): StreamResponseDto = coroutineScope {
        // Subtitle files belong to this exact video/episode and travel with downloads too.
        val subtitleTask = async { loadSubtitles(videoId) }
        val sceneTask = async {
            try {
                val info = client.videoInfo(videoId)
                info to com.nuvio.tv.domain.model.ProviderSceneSkip
                    .fromSource("cee", videoId, info)?.toPayload()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                kotlin.coroutines.coroutineContext[com.nuvio.android.cinemana.ProviderRequestState]?.complete?.set(false)
                null to null
            }
        }
        val items = client.streams(videoId)
        val subtitles = subtitleTask.await()
        val (videoInfo, sceneData) = sceneTask.await()
        val language = videoInfo?.optJSONObject("videoLanguages")
            ?.optString("en_title").orEmpty()
        val sourceTitle = listOf(
            videoInfo?.optString("ar_title").orEmpty(),
            videoInfo?.optString("en_title").orEmpty()
        ).joinToString(" ")
        val hasArabicSubtitles = subtitles.any {
            it.lang.equals("ar", ignoreCase = true)
        }
        val editionLabel = when {
            sourceTitle.contains("DUBBED", ignoreCase = true) ||
                sourceTitle.contains("مدبلج") -> "مدبلج عربي"
            language.equals("Arabic", ignoreCase = true) -> "صوت عربي"
            hasArabicSubtitles -> "مترجم عربي"
            language.isNotBlank() -> "صوت $language"
            else -> "نسخة CEE"
        }

        val result = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("videoUrl")
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null

            val resolution = item.optString("resolution")
            val quality = Regex("[0-9]+").find(resolution)?.value?.toIntOrNull() ?: 0

            quality to StreamDto(
                name = "Cee",
                title = "Cee | $resolution | $editionLabel",
                url = url,
                subtitles = subtitles,
                sceneSkipData = sceneData
            )
        }.sortedByDescending { it.first }.map { it.second }

        StreamResponseDto(streams = result)
    }

    suspend fun subtitles(videoId: String): SubtitleResponseDto =
        SubtitleResponseDto(
            subtitles = loadSubtitles(videoId).map {
                SubtitleItemDto(id = it.id, url = it.url, lang = it.lang)
            }
        )

    private suspend fun loadSubtitles(videoId: String): List<SubtitleDto> {
        val info = try {
            client.subtitles(videoId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            kotlin.coroutines.coroutineContext[com.nuvio.android.cinemana.ProviderRequestState]?.complete?.set(false)
            return emptyList()
        }
        val translations = info.optJSONArray("translations") ?: JSONArray()
        fun language(code: String) = when (code.lowercase(java.util.Locale.ROOT)) {
            "ar", "ara" -> "ara"
            "en", "eng" -> "eng"
            "sp", "es", "spa" -> "spa"
            else -> code.ifBlank { "und" }
        }
        val candidates = (0 until translations.length()).mapNotNull { index ->
            val item = translations.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("file").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            SubtitleDto(id = "cee-$videoId-$index", url = url, lang = language(item.optString("type")))
        }.toMutableList()
        for ((code, field) in listOf("ara" to "arTranslationFilePath", "eng" to "enTranslationFilePath", "spa" to "spTranslationFilePath")) {
            val url = info.optString(field)
            if (candidates.none { it.lang == code } && (url.startsWith("https://") || url.startsWith("http://")))
                candidates.add(SubtitleDto(id = "cee-$videoId-$code", url = url, lang = code))
        }
        return candidates.groupBy { it.lang }.values.map { tracks ->
            tracks.firstOrNull { it.url.substringBefore('?').endsWith(".vtt", true) } ?: tracks.first()
        }
    }

    suspend fun meta(videoId: String, requestedType: String? = null, artworkOnly: Boolean = false): MetaResponseDto {
        val info = client.videoInfo(videoId)

        val title = info.optString("en_title")
            .takeIf { it.isNotBlank() }
            ?: info.optString("ar_title").ifBlank { "Cee $videoId" }

        val imdbId = Regex("tt[0-9]+")
            .find(info.optString("imdbUrlRef"))
            ?.value
        val malId = Regex("""myanimelist\.net/anime/([0-9]+)""", RegexOption.IGNORE_CASE)
            .find(info.optString("imdbUrlRef"))
            ?.groupValues?.getOrNull(1)?.toLongOrNull()

        val type = if (requestedType == "series" || info.optString("kind") == "2") {
            "series"
        } else {
            "movie"
        }

        // Enrich tracking identity only; never replace Cee playback IDs.
        val credentials = tmdbCredentials()
        val bindingKey = "cee|$type|$videoId"
        val find = suspend {
            val matcher = tmdbFetch?.let { CinemanaTmdbMatcher(credentials.apiKey, credentials.language, it) }
                ?: CinemanaTmdbMatcher(credentials.apiKey, credentials.language)
            matcher.resolve(
                titles = listOf(info.optString("en_title"), info.optString("ar_title")),
                year = info.optString("year").take(4).toIntOrNull(),
                series = type == "series",
                imdbId = imdbId
            )
        }
        val tmdbMatch = if (detailsStore != null) detailsStore.resolve(
            bindingKey, "cee:$videoId", type == "series", title, find
        ) else find().match
        if (artworkOnly) return MetaResponseDto(MetaDto(
            id = "cee:$videoId", type = type, name = title, imdbId = imdbId,
            artworkTmdbId = tmdbMatch?.tmdbId?.toString(),
            tmdbId = tmdbMatch?.tmdbId?.toString()?.takeIf { type == "movie" }
        ))
        val nativeEpisodes = if (type == "series") client.episodes(videoId) else emptyList()
        val directlyMatched = if (type == "series" && tmdbMatch != null)
            matchEpisodeLayout(tmdbMatch.tmdbId, nativeEpisodes) else null
        val compatible = directlyMatched != null || if (type == "series" && tmdbMatch != null) {
            val id = tmdbMatch.tmdbId
            if (detailsStore != null) {
                val keys = nativeEpisodes.map { BuiltinDetailsStore.EpisodeKey(it.videoId, it.season, it.episode) }
                detailsStore.verify("$bindingKey|$id", keys) { unchecked, incremental ->
                    verifyEpisodes(id, nativeEpisodes, unchecked.takeIf { incremental })
                } == true
            } else verifyEpisodes(id, nativeEpisodes, null)
        } else false
        val sourceEpisodesById = nativeEpisodes.associateBy { it.videoId }
        return MetaResponseDto(
            meta = MetaDto(
                id = "cee:$videoId",
                type = type,
                name = title,
                poster = info.optString("imgObjUrl")
                    .takeIf { it.startsWith("https://") },
                description = info.optString("ar_content")
                    .takeIf { it.isNotBlank() }
                    ?: info.optString("en_content")
                        .takeIf { it.isNotBlank() },
                releaseInfo = info.optString("year")
                    .takeIf { it.isNotBlank() },
                imdbId = imdbId,
                malId = malId,
                tmdbId = tmdbMatch?.tmdbId?.toString(),
                sharedProgressCompatible = compatible,
                videos = if (type == "series") (directlyMatched ?: nativeEpisodes).map { episode ->
                    val source = sourceEpisodesById.getValue(episode.videoId)
                    VideoDto(id = "cee:$videoId:${source.season}:${source.episode}",
                        title = "Episode ${episode.episode}", season = episode.season,
                        episode = episode.episode, available = true)
                }.distinctBy { it.id } else null
            )
        )
    }
}
