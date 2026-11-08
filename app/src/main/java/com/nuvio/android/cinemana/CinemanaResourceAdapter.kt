package com.nuvio.android.cinemana

import com.nuvio.tv.data.builtincache.BuiltinDetailsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class TmdbCredentials(
    val apiKey: String, val language: String,
    val useArtwork: Boolean = true, val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true, val useEpisodes: Boolean = true,
)

/** Injectable transport and settings make all resource routes testable without a device. */
class CinemanaResourceAdapter(
    private val client: CinemanaClient,
    private val settings: () -> TmdbCredentials,
    private val fetch: suspend (String) -> String,
    private val detailsStore: BuiltinDetailsStore? = null,
) {
    private val metadataCache = TmdbResponseCache()
    private val cachedFetch: suspend (String) -> String = { url -> metadataCache.get(url) { fetch(url) } }

    suspend fun handle(url: String): String? {
        if (!url.startsWith("https://cinemana.nuvio.invalid/")) return null
        val uri = URI(url)
        if (uri.host != "cinemana.nuvio.invalid") return null
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null)
        val rawParts = uri.rawPath.trim('/').split('/')
        val parts = rawParts.map(::decode)
        if (parts == listOf("manifest.json")) return manifest().toString()
        require(parts.size in 3..4 && parts.last().endsWith(".json")) { "Invalid Cinemana resource route" }
        val resource = parts[0]
        val type = parts[1]
        require(type == "movie" || type == "series") { "Unsupported Cinemana content type" }
        val id = if (parts.size == 3) parts[2].removeSuffix(".json") else parts[2]
        val extra = if (parts.size == 4) rawParts[3].removeSuffix(".json").split('&').associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        } else emptyMap()
        val result = when (resource) {
            "catalog" -> {
                val query = extra["search"].orEmpty()
                val entries = when {
                    query.isNotBlank() -> client.search(query, type == "series")
                    id.startsWith("cinemana-home-") -> {
                        val groupId = id.removePrefix("cinemana-home-")
                        require(groupId.matches(Regex("[0-9]+"))) {
                            "Invalid Cinemana Home group"
                        }
                        val skip = (extra["skip"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                        val items = client.homeGroupPage(groupId, skip / 12)
                        val metas = JSONArray()

                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            val itemType = when (item.optInt("kind")) {
                                1 -> "movie"
                                2 -> "series"
                                else -> continue
                            }

                            val entry = client.entry(item, itemType == "series") ?: continue
                            metas.put(preview(entry, itemType))
                        }

                        return JSONObject().put("metas", metas).toString()
                    }
                    id == "cinemana_all" || id == "cinemana_all_series" -> {
                        val skip = (extra["skip"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                        client.allCatalogPage(type == "series", skip / 12, extra["year"])
                    }
                    else -> {
                        val category = CinemanaCatalogs.category(id) ?: error("Unknown Cinemana catalog")
                        val year = extra["year"]?.takeIf { it.isNotBlank() }
                        val skip = (extra["skip"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                        if (year != null) {
                            client.allCatalogPage(
                                type == "series",
                                skip / 12,
                                year,
                                category.remoteIds
                            )
                        } else {
                            client.catalogPage(
                                category.remoteIds,
                                type == "series",
                                skip,
                                extra["sort"] ?: "desc",
                                extra["language"]?.takeIf { it.isNotBlank() }
                            )
                        }
                    }
                }
                JSONObject().put("metas", JSONArray(entries.map { preview(it, type) }))
            }
            "meta" -> JSONObject().put("meta", metadata(id, type, uri.rawQuery == "artworkOnly=1"))
            "stream", "subtitles" -> {
                val videos = resolve(id, type)
                if (resource == "subtitles") {
                    val tracks = coroutineScope { videos.map { video -> async {
                        val info = optional { client.info(video.videoId) }
                        val label = info?.let { CinemanaEditions.label(listOf(it.optString("en_title"), it.optString("ar_title"))) }
                            ?.takeIf { it != "نسخة Cinemana" } ?: video.version
                        optional { client.subtitles(video.videoId).mapIndexed { index, track ->
                            JSONObject().put("id", "cinemana-${video.videoId}-$index").put("url", track.url)
                                .put("lang", track.language).put("label", "${track.label} • $label • ${video.title}")
                        } }.orEmpty()
                    } }.flatMap { it.await() } }
                    JSONObject().put("subtitles", JSONArray(tracks))
                } else {
                    val streams = coroutineScope { videos.map { video -> async {
                        // Link retrieval does not depend on the descriptive info response.
                        // Start both together, then retain all metadata when assembling streams.
                        val streamsTask = async {
                            optional { client.streams(video.videoId) }.orEmpty()
                        }
                        val info = optional { client.info(video.videoId) }
                        val aliases = listOfNotNull(info?.optString("en_title"), info?.optString("ar_title"), video.title).filter { it.isNotBlank() }
                        val fallbackVersion = CinemanaEditions.label(aliases).takeIf { it != "نسخة Cinemana" } ?: video.version
                        val title = aliases.firstOrNull() ?: "Cinemana"
                        val duration = info?.let(CinemanaEditions::duration)
                        val rootId = info?.optString("rootSeries")?.takeIf { it != "0" && it.isNotEmpty() && it.all(Char::isDigit) } ?: video.rootId
                        // Bind subtitle files to the exact video edition, including downloads.
                        val subtitlesTask = async {
                            optional { client.subtitles(video.videoId, info).map { track ->
                            JSONObject().put("url", track.url).put("lang", track.language)
                                .put("name", "${track.label} • $fallbackVersion")
                            } }.orEmpty()
                        }
                        val subtitles = subtitlesTask.await()
                        val audioLanguage = info?.optJSONObject("videoLanguages")
                            ?.optString("en_title").orEmpty()
                        val hasArabicSubtitles = subtitles.any {
                            it.optString("lang").equals("ar", ignoreCase = true)
                        }
                        val version = when {
                            audioLanguage.equals("Arabic", ignoreCase = true) &&
                                fallbackVersion == "مدبلجة" -> "مدبلج عربي"
                            audioLanguage.equals("Arabic", ignoreCase = true) -> "صوت عربي"
                            hasArabicSubtitles -> "مترجم عربي"
                            fallbackVersion != "نسخة Cinemana" -> fallbackVersion
                            audioLanguage.isNotBlank() -> "صوت $audioLanguage"
                            else -> "نسخة Cinemana"
                        }
                        streamsTask.await().map { stream ->
                            val description = listOfNotNull(title, "$version • ${stream.quality} • ${stream.container}", duration?.let { "المدة: $it" }).joinToString("\n")
                            CinemanaQuality.height(stream.quality) to JSONObject()
                                .put("name", "Cinemana • ${stream.quality} • $version").put("title", description).put("description", description)
                                .put("url", stream.url).put("subtitles", JSONArray(subtitles))
                                .put("sceneSkipData", info?.let { record ->
                                    com.nuvio.tv.domain.model.ProviderSceneSkip
                                        .fromSource("cinemana", video.videoId, record)?.toPayload()
                                })
                                .put("behaviorHints", JSONObject().put("bingeGroup", "cinemana-$rootId-${stream.quality}"))
                        }
                    } }.flatMap { it.await() } }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.optString("url") }
                    JSONObject().put("streams", JSONArray(streams))
                }
            }
            else -> error("Unknown Cinemana resource")
        }
        return result.toString()
    }

    suspend fun manifest(): JSONObject {
        val types = listOf("movie", "series")
        val resources = JSONArray().put("catalog")
        resources.put(JSONObject().put("name", "meta").put("types", JSONArray(types)).put("idPrefixes", JSONArray(listOf("cinemana:", "cinemana-series:"))))
        for (resource in listOf("stream", "subtitles")) {
            resources.put(JSONObject().put("name", resource).put("types", JSONArray(types))
                .put("idPrefixes", JSONArray(listOf("cinemana:", "cinemana-series:", "tt", "imdb:", "tmdb:"))))
        }
        val catalogs = JSONArray()
        // Live Home collections, preserving Cinemana's server order.
        val homeGroups = client.homeGroups("ar")
        for (index in 0 until homeGroups.length()) {
            val group = homeGroups.optJSONObject(index) ?: continue
            val groupId = group.optString("groupsID")
            if (!groupId.matches(Regex("[0-9]+"))) continue

            val title = group.optString("title").trim()
            if (title.isEmpty()) continue

            val content = group.optJSONArray("content") ?: JSONArray()
            val kinds = (0 until content.length()).mapNotNull { itemIndex ->
                content.optJSONObject(itemIndex)?.optInt("kind")
            }

            val type = if (kinds.isNotEmpty() && kinds.all { it == 2 }) {
                "series"
            } else {
                "movie"
            }

            catalogs.put(JSONObject()
                .put("id", "cinemana-home-$groupId")
                .put("type", type)
                .put("name", "Cinemana • $title")
                .put("showInHome", true)
                .put("extra", JSONArray().put(JSONObject().put("name", "skip"))))
        }
        for (type in types) {
            catalogs.put(JSONObject().put("id", "cinemana_search_$type").put("type", type)
                .put("name", "Cinemana").put("extra", JSONArray().put(JSONObject().put("name", "search").put("isRequired", true))))
            catalogs.put(JSONObject().put("id", if (type == "series") "cinemana_all_series" else "cinemana_all")
                .put("type", type).put("name", "Cinemana • All").put("showInHome", false)
                .put("extra", JSONArray().put(JSONObject().put("name", "skip")).put(JSONObject().put("name", "sort")).put(JSONObject().put("name", "language"))))
            for (category in CinemanaCatalogs.categories) {
                catalogs.put(JSONObject().put("id", category.id + if (type == "series") "_series" else "")
                    .put("type", type).put("name", "Cinemana • ${category.title}").put("showInHome", false)
                    .put("extra", JSONArray().put(JSONObject().put("name", "skip")).put(JSONObject().put("name", "sort")).put(JSONObject().put("name", "language"))))
            }
        }
        return JSONObject().put("id", "org.nuvio.cinemana.builtin").put("name", "Cinemana")
            .put("version", "0.7.2").put("description", "Built-in Cinemana movies and series")
            .put("types", JSONArray(types)).put("resources", resources).put("catalogs", catalogs)
    }

    private fun preview(entry: CinemanaClient.Entry, type: String): JSONObject =
        JSONObject().put("id", "${if (type == "series") "cinemana-series:" else "cinemana:"}${entry.id}").put("type", type).put("name", entry.title)
            .put("poster", entry.poster).put("releaseInfo", entry.year)


    // Compare only explicitly understood episode durations.
    // null means unknown, never proof of a matching edition.
    private suspend fun episodeDurationCompatibility(
        episodes: List<CinemanaClient.Episode>,
        details: JSONObject,
        incremental: Boolean = false
    ): Boolean? {
        val runtimes = details.optJSONArray("episode_run_time") ?: return null
        val official = (0 until runtimes.length())
            .map { runtimes.optInt(it) }
            .filter { it in 10..300 }
            .sorted()
            .let { it.getOrNull(it.size / 2) }
            ?: return null

        // Sample beginning, middle and end of every native season.
        val samples = if (incremental) episodes else episodes.groupBy { it.season }
            .toSortedMap()
            .values
            .flatMap { group ->
                val sorted = group.sortedBy { it.episode }
                if (sorted.isEmpty()) emptyList()
                else listOf(
                    sorted.first(),
                    sorted[sorted.size / 2],
                    sorted.last()
                )
            }
            .distinctBy { it.videoId }

        val measured = samples.map { sourceEpisodeSeconds(it) }

        if (!incremental) return CinemanaEpisodeMapping.durationSamplesCompatible(measured, official)
        val checks = measured.map { CinemanaEpisodeMapping.durationCompatible(it, official) }
        return when {
            false in checks -> false
            checks.isEmpty() || null in checks -> null
            else -> true
        }
    }
    private suspend fun sourceEpisodeSeconds(episode: CinemanaClient.Episode): Long? =
        episode.durationSeconds ?: optional { CinemanaEditions.episodeSeconds(client.info(episode.videoId)) }

    private suspend fun metadata(id: String, type: String, artworkOnly: Boolean): JSONObject {
        val rawId = id.removePrefix("cinemana-series:").removePrefix("cinemana:").substringBefore(':')
        require((id.startsWith("cinemana:") || id.startsWith("cinemana-series:")) && rawId.all(Char::isDigit) && rawId.isNotEmpty())
        val record = client.info(rawId).also { if (!it.has("nb") && !it.has("id")) it.put("nb", rawId) }
        val entry = client.entry(record, type == "series") ?: error("Cinemana metadata unavailable")
        val meta = preview(entry.copy(id = rawId), type)
            .put("description", listOf("description", "en_description", "ar_description", "en_content", "ar_content")
                .firstNotNullOfOrNull { record.optString(it).takeIf { text -> text.isNotBlank() && text != "null" } }.orEmpty())
        // Preserve Cinemana's original IMDb identity independently of TMDB.

        val credentials = settings()
        val bindingKey = "cinemana|$type|$rawId"
        val find = suspend {
            CinemanaTmdbMatcher(credentials.apiKey, credentials.language, cachedFetch)
                .resolve(entry.aliases, entry.year?.take(4)?.toIntOrNull(), type == "series", entry.imdbId)
        }
        val match = optional {
            if (detailsStore != null) detailsStore.resolve(
                bindingKey, id, type == "series", entry.title, find
            ) else find().match
        }
        match?.tmdbId?.let { meta.put("_artworkTmdbId", it.toString()) }
        if (artworkOnly) {
            entry.imdbId?.let { meta.put("imdb_id", it) }
            if (match != null && type == "movie") meta.put("moviedb_id", match.tmdbId.toString())
            return meta
        }
        val details = if (match != null) optional {
            tmdb("${if (type == "series") "tv" else "movie"}/${match.tmdbId}", "append_to_response=credits,external_ids")
        } else null
        val officialImdb = details?.optJSONObject("external_ids")?.optString("imdb_id")
            ?.takeIf { it.matches(Regex("tt[0-9]+")) }
        if (officialImdb != null && (entry.imdbId == null || officialImdb == entry.imdbId)) {
            detailsStore?.rememberVerifiedArtwork(meta.optString("id"), officialImdb)
        }
        val seasonCounts = linkedMapOf<Int, Int>()
        val seasons = details?.optJSONArray("seasons")
        if (seasons != null) for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val number = season.optInt("season_number")
            val count = season.optInt("episode_count")
            if (number > 0 && count > 0) seasonCounts[number] = count
        }
        val sourceEpisodes = if (type == "series") {
            optional { client.completeEpisodes(rawId, record, seasonCounts) }
                .orEmpty()
                .ifEmpty { client.episodes(rawId) }
        } else emptyList<CinemanaClient.Episode>()

        val nativeEpisodes = sourceEpisodes.map {
            CinemanaEpisodeMapping.EpisodeRef(it.videoId, it.season, it.episode, it.title)
        }
        val originalArabic = details?.optString("original_language") == "ar"
        val directlyMatched = CinemanaEpisodeMapping.sharedEpisodeLayout(
            nativeEpisodes, seasonCounts, details?.optString("original_language")
        )
        val normalized = directlyMatched ?: CinemanaEpisodeMapping.normalizeContinuous(nativeEpisodes, seasonCounts)
        var individualVideos: Set<String>? = null
        val durationStatus = if (directlyMatched != null && match != null) true else if (type == "series" && details != null && match != null) {
            val expected = seasonCounts.flatMap { (season, count) -> (1..count).map { season to it } }.toSet()
            val coordinates = normalized.map { it.season to it.episode }
            if (detailsStore != null && com.nuvio.tv.data.repository.verifiedEpisodeCoordinates(coordinates, expected)) {
                val mapped = normalized.associateBy { it.videoId }
                val keys = sourceEpisodes.map { episode ->
                    val target = mapped.getValue(episode.videoId)
                    BuiltinDetailsStore.EpisodeKey(
                        "${episode.videoId}:${target.season}:${target.episode}", episode.season, episode.episode
                    )
                }
                val generalRuntimes = details.optJSONArray("episode_run_time")
                if (generalRuntimes == null || (0 until generalRuntimes.length()).none { generalRuntimes.optInt(it) in 10..300 }) {
                    val episodesByKey = keys.zip(sourceEpisodes).toMap()
                    val verified = detailsStore.verifyEpisodesIndividually("$bindingKey|${match.tmdbId}", keys.toSet()) { unchecked ->
                        val runtimes = unchecked.groupBy { mapped.getValue(episodesByKey.getValue(it).videoId).season }
                            .flatMap { (season, _) ->
                                val list = optional { tmdb("tv/${match.tmdbId}/season/$season").optJSONArray("episodes") }
                                (0 until (list?.length() ?: 0)).mapNotNull { index ->
                                    val episode = list?.optJSONObject(index) ?: return@mapNotNull null
                                    (season to episode.optInt("episode_number")) to episode.optInt("runtime")
                                }
                            }.toMap()
                        unchecked.chunked(4).flatMap { batch ->
                            coroutineScope {
                                batch.map { key -> async {
                                    val episode = episodesByKey.getValue(key)
                                    val coord = mapped.getValue(episode.videoId)
                                    val runtime = runtimes[coord.season to coord.episode]?.takeIf { it in 10..300 }
                                    key to runtime?.let { CinemanaEpisodeMapping.durationCompatible(sourceEpisodeSeconds(episode), it) }
                                } }.map { it.await() }
                            }
                        }.toMap()
                    }
                    individualVideos = verified?.map { "cinemana:${episodesByKey.getValue(it).videoId}" }?.toSet()
                    when {
                        verified == null -> false
                        verified.isEmpty() -> null
                        else -> true
                    }
                } else detailsStore.verify("$bindingKey|${match.tmdbId}", keys) { unchecked, incremental ->
                    val newEpisodes = sourceEpisodes.zip(keys).filter { (_, key) -> key in unchecked }.map { it.first }
                    episodeDurationCompatibility(newEpisodes, details, incremental)
                }
            } else episodeDurationCompatibility(sourceEpisodes, details)
        } else null

        val declaredEpisodes = details?.optJSONArray("seasons")?.let { seasons ->
            (0 until seasons.length()).sumOf { index ->
                val season = seasons.optJSONObject(index)
                if (season != null && season.optInt("season_number") > 0)
                    season.optInt("episode_count").coerceAtLeast(0)
                else 0
            }
        } ?: 0

        val editionMismatch = type == "series" && !originalArabic && (
            durationStatus == false ||
            (declaredEpisodes > 0 && sourceEpisodes.size > declaredEpisodes)
        )

        if (!editionMismatch) {
            entry.imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let {
                meta.put("imdb_id", it)
            }
        }
        if (match != null && !editionMismatch) {
            if (credentials.useBasicInfo) {
                val name = details?.optString(if (type == "series") "name" else "title")
                    ?.takeIf { it.isNotBlank() && it != "null" } ?: match.title
                meta.put("name", name)
                match.year?.let { meta.put("releaseInfo", it.toString()) }
                val overview = details?.optString("overview")?.takeIf { it.isNotBlank() && it != "null" } ?: match.overview
                if (!overview.isNullOrBlank()) meta.put("description", overview)
            }
            if (credentials.useArtwork) {
                val poster = details?.optString("poster_path")?.takeIf { it.startsWith('/') }
                    ?.let { "https://image.tmdb.org/t/p/w500$it" } ?: match.poster
                if (poster != null) meta.put("poster", poster)
            }
            meta.put("moviedb_id", match.tmdbId.toString())
            if (details != null) enrichDetails(meta, details, credentials)
        }
        if (type == "series") {
            val durationCompatible = durationStatus == true && !editionMismatch
            val episodes = if (durationCompatible) normalized else nativeEpisodes
            val titles = mutableListOf<CinemanaEpisodeMapping.TmdbEpisode>()
            if (match != null && !editionMismatch && credentials.useEpisodes) for (season in episodes.map { it.season }.distinct()) {
                val data = optional { tmdb("tv/${match.tmdbId}/season/$season").optJSONArray("episodes") }
                if (data != null) for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    titles.add(CinemanaEpisodeMapping.TmdbEpisode(season, item.optInt("episode_number"), item.optString("name")))
                }
            }
            // TMDB titles are safe only for a complete matching episode layout.
            // Otherwise preserve Cinemana's original episode titles and numbers.
            val nativeCounts = episodes.groupingBy { it.season }.eachCount()
            val compatibleTitles = if (
                durationCompatible &&
                seasonCounts.isNotEmpty() &&
                nativeCounts == seasonCounts
            ) titles else emptyList()
            val coordinates = episodes.map { it.season to it.episode }
            val expected = seasonCounts.flatMap { (season, count) -> (1..count).map { season to it } }.toSet()
            meta.put("_sharedProgressCompatible", match != null && durationCompatible &&
                (originalArabic || com.nuvio.tv.data.repository.verifiedEpisodeCoordinates(coordinates, expected)))
            individualVideos?.let { meta.put("_verifiedPlaybackVideoIds", JSONArray(it.toList())) }
            val merged = CinemanaEpisodeMapping.merge(episodes, emptyList(), compatibleTitles)
            meta.put("videos", JSONArray(merged.map { episode ->
                JSONObject().put("id", "cinemana:${episode.videoId}").put("season", episode.season)
                    .put("episode", episode.episode).put("title", episode.title)
            }))
        }
        return meta
    }

    private fun enrichDetails(meta: JSONObject, details: JSONObject, credentials: TmdbCredentials) {
        if (credentials.useArtwork) {
            val backdrop = details.optString("backdrop_path")
            if (backdrop.startsWith('/')) meta.put("background", "https://image.tmdb.org/t/p/w1280$backdrop")
        }
        val imdb = details.optString("imdb_id").takeIf { it.matches(Regex("tt[0-9]+")) }
            ?: details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.matches(Regex("tt[0-9]+")) }
        if (imdb != null) meta.put("imdb_id", imdb)
        if (imdb != null) meta.put("links", JSONArray().put(JSONObject()
            .put("name", "IMDb").put("category", "imdb").put("url", "https://www.imdb.com/title/$imdb/")))
        if (!credentials.useDetails) return
        val genres = details.optJSONArray("genres") ?: JSONArray()
        meta.put("genres", JSONArray((0 until genres.length()).mapNotNull { genres.optJSONObject(it)?.optString("name") }))
        val rating = details.optDouble("vote_average", 0.0)
        if (rating > 0) meta.put("imdbRating", String.format(Locale.ROOT, "%.1f", rating))
        val runtime = details.optInt("runtime")
        if (runtime > 0) meta.put("runtime", "$runtime min")
        val cast = details.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
        meta.put("cast", JSONArray((0 until minOf(cast.length(), 30)).mapNotNull { cast.optJSONObject(it)?.optString("name") }))
        for (field in listOf("production_companies", "networks")) {
            details.optJSONArray(field)?.let { meta.put(field, it) }
        }
    }

    private suspend fun subtitleArray(id: String): JSONArray = JSONArray(client.subtitles(id).mapIndexed { index, subtitle ->
        JSONObject().put("id", "cinemana-$id-$index").put("url", subtitle.url)
            .put("lang", subtitle.language).put("label", subtitle.label)
    })

    private data class ResolvedVideo(val videoId: String, val rootId: String = videoId,
        val title: String = "Cinemana", val version: String = "نسخة Cinemana")

    /** Resolve external catalog IDs conservatively; every series request requires S/E. */
    private suspend fun resolve(id: String, type: String): List<ResolvedVideo> {
        if (id.startsWith("cinemana:")) return id.removePrefix("cinemana:").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { listOf(ResolvedVideo(it)) }.orEmpty()
        if (id.startsWith("cinemana-series:")) {
            val values = id.removePrefix("cinemana-series:").split(':')
            if (values.size != 3) return emptyList() // Never play a series root as an episode.
            val season = values[1].toIntOrNull() ?: return emptyList()
            val episode = values[2].toIntOrNull() ?: return emptyList()
            return client.completeEpisodes(values[0]).filter { it.season == season && it.episode == episode }
                .map { ResolvedVideo(it.videoId, values[0], it.title) }
        }
        val series = type == "series"
        val tokens = id.removePrefix("imdb:").split(':')
        val season = if (series) tokens.getOrNull(tokens.size - 2)?.toIntOrNull() else null
        val episode = if (series) tokens.lastOrNull()?.toIntOrNull() else null
        if (series && (season == null || episode == null || season < 1 || episode < 1)) return emptyList()
        val kind = if (series) "tv" else "movie"
        val tmdbId = when {
            tokens[0] == "tmdb" -> {
                val index = if (tokens.getOrNull(1) in listOf("movie", "tv", "series")) 2 else 1
                tokens.getOrNull(index)?.toIntOrNull()?.takeIf { it > 0 }
            }
            tokens[0].matches(Regex("tt[0-9]+")) -> {
                val array = tmdb("find/${tokens[0]}", "external_source=imdb_id").optJSONArray(if (series) "tv_results" else "movie_results")
                array?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
            }
            else -> null
        } ?: return emptyList()
        if (tokens[0] == "tmdb" && tokens.getOrNull(1) in listOf("movie", "tv", "series")) {
            if ((tokens[1] == "movie") == series) return emptyList()
        }
        val data = tmdb("$kind/$tmdbId", "append_to_response=external_ids")
        val expectedImdb = tokens[0].takeIf { it.matches(Regex("tt[0-9]+")) }
            ?: CinemanaIdentity.imdbId(data.optString("imdb_id"))
            ?: CinemanaIdentity.imdbId(data.optJSONObject("external_ids")?.optString("imdb_id"))
        val year = data.optString(if (series) "first_air_date" else "release_date").take(4).toIntOrNull()
        val variants = mutableListOf(data.optString(if (series) "name" else "title"), data.optString(if (series) "original_name" else "original_title"))
        suspend fun addAlternativeTitles() {
        val translations = optional { tmdb("$kind/$tmdbId/translations").optJSONArray("translations") }
        if (translations != null) for (i in 0 until translations.length()) {
            val translation = translations.optJSONObject(i) ?: continue
            if (translation.optString("iso_639_1") !in listOf("en", "ar", "tr")) continue
            val value = translation.optJSONObject("data") ?: continue
            variants.add(value.optString(if (series) "name" else "title"))
        }
        val alternatives = optional { tmdb("$kind/$tmdbId/alternative_titles") }
        val alternativeRows = alternatives?.optJSONArray(if (series) "results" else "titles")
        if (alternativeRows != null) for (i in 0 until alternativeRows.length()) {
            val row = alternativeRows.optJSONObject(i) ?: continue
            if (row.optString("iso_3166_1") in listOf("US", "GB", "TR", "IQ", "SA", "AE")) variants.add(row.optString("title"))
        }
        }
        // The search API does not necessarily fold Turkish accents or edition suffixes.
        // Keep the original request as well as the cleaned, ASCII search spelling.
        fun queries() = variants.filter { it.isNotBlank() && it != "null" }.flatMap {
            val cleaned = CinemanaEditions.identityTitle(it)
            listOf(it, cleaned, java.text.Normalizer.normalize(cleaned, java.text.Normalizer.Form.NFKD)
                .replace(Regex("[\\p{M}]"), "").replace('ı', 'i'))
        }.distinct().take(12)
        var searchQueries = queries()
        suspend fun searchEntries(pages: Int): List<CinemanaClient.Entry> =
            coroutineScope {
                searchQueries.map { query ->
                    async { optional { client.search(query, series, pages) }.orEmpty() }
                }.flatMap { it.await() }
            }.groupBy { it.id }.map { (_, versions) ->
                versions.first().copy(
                    aliases = versions.flatMap { it.aliases }.distinct(),
                    imdbId = versions.mapNotNull { it.imdbId }.distinct().singleOrNull()
                )
            }

        fun matchingEntries(entries: List<CinemanaClient.Entry>) = entries.filter { entry ->
            CinemanaIdentity.matches(expectedImdb, entry.imdbId,
                searchQueries.any { query -> entry.aliases.any { normalize(query) == normalize(it) } },
                entry.year?.take(4)?.toIntOrNull(),
                year,
                data.optString("last_air_date").take(4).toIntOrNull(),
                series
            )
        }

        // Search every available page up to the existing bound: an early hit
        // does not imply that later pages contain no other editions.
        var matches = matchingEntries(searchEntries(5))
        if (matches.isEmpty()) {
            // Translations and historical alternative names are a fallback.
            // Successful canonical-title searches already cover all five pages.
            addAlternativeTitles()
            searchQueries = queries()
            matches = matchingEntries(searchEntries(5))
        }
        if (!series) {
            return matches.map { ResolvedVideo(it.id, it.id, it.title, CinemanaEditions.label(it.aliases)) }
        }
        if (matches.isEmpty() || matches.size > 8) return emptyList()

        // Native editions with substantially different episode durations
        // must not share an external season/episode stream identity.
        // A release delay does not change edition compatibility.
        val compatibleMatches = if (matches.size == 1) {
            matches
        } else {
            val measured = coroutineScope {
                matches.map { entry -> async {
                    val duration = optional {
                        client.info(entry.id).optString("duration")
                            .toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it in 60.0..30000.0 }
                    }
                    entry to duration
                } }.map { it.await() }
            }
            val reference = measured.mapNotNull { it.second }.maxOrNull()
            if (reference == null) {
                // Unknown cuts from different roots cannot be assumed equal.
                emptyList()
            } else {
                measured.filter { (_, seconds) ->
                    seconds != null && seconds >= reference * 0.65
                }.map { it.first }
            }
        }
        val counts = linkedMapOf<Int, Int>()
        val seasons = data.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until seasons.length()) {
            val item = seasons.optJSONObject(i) ?: continue
            counts[item.optInt("season_number")] = item.optInt("episode_count")
        }
        fun episodeIds(
            source: List<CinemanaClient.Episode>,
            verifiedDuration: Boolean
        ): List<String> {
            val nativeEpisodes = source.map {
                CinemanaEpisodeMapping.EpisodeRef(it.videoId, it.season, it.episode, it.title)
            }
            // Do not impose TMDB numbering when runtime compatibility is unknown.
            val episodes = if (verifiedDuration)
                CinemanaEpisodeMapping.normalizeContinuous(nativeEpisodes, counts)
            else nativeEpisodes

            return episodes.filter { it.season == season && it.episode == episode }
                .map { it.videoId }.distinct()
        }
        // Try the season endpoint first. Full title-search expansion is only a
        // fallback; it must not delay an episode already available here.
        // Reject only confirmed runtime-incompatible editions for external IDs.
        // Unknown duration keeps existing streaming fallback behavior.
        val durationSafeMatches = compatibleMatches.mapNotNull { entry ->
            val nativeEpisodes = optional { client.episodes(entry.id) }.orEmpty()
            val compatibility = episodeDurationCompatibility(nativeEpisodes, data)
            if (compatibility == false) null
            else entry to (compatibility == true)
        }
        return coroutineScope {
            durationSafeMatches.map { (entry, verifiedDuration) -> async {
                optional {
                    val direct = episodeIds(client.episodes(entry.id), verifiedDuration)
                    val selected = direct.ifEmpty {
                        optional { episodeIds(client.completeEpisodes(entry.id), verifiedDuration) }.orEmpty()
                    }
                    selected.map { ResolvedVideo(it, entry.id, entry.title, CinemanaEditions.label(entry.aliases)) }
                }.orEmpty()
            } }.flatMap { it.await() }.distinctBy { it.videoId }
        }
    }

    private suspend fun tmdb(path: String, extra: String = ""): JSONObject {
        val credentials = settings()
        require(credentials.apiKey.isNotBlank()) { "Enable TMDB in Nuvio settings to resolve external catalogs" }
        val url = "https://api.themoviedb.org/3/$path?api_key=${encode(credentials.apiKey)}&language=${encode(credentials.language)}&$extra"
        return JSONObject(cachedFetch(url))
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { kotlin.coroutines.coroutineContext[ProviderRequestState]?.complete?.set(false); null }

    private fun normalize(s: String) = CinemanaEpisodeMapping.normalizeTitle(CinemanaEditions.identityTitle(s))
    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}
