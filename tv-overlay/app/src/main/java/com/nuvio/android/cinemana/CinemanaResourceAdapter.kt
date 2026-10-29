package com.nuvio.android.cinemana

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
) {
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
                val entries = if (query.isNotBlank()) client.search(query, type == "series")
                else {
                    val category = CinemanaCatalogs.category(id) ?: error("Unknown Cinemana catalog")
                    client.catalogPage(category.remoteIds, type == "series", extra["skip"]?.toIntOrNull() ?: 0)
                }
                JSONObject().put("metas", JSONArray(entries.map { preview(it, type) }))
            }
            "meta" -> JSONObject().put("meta", metadata(id, type))
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
                        val info = optional { client.info(video.videoId) }
                        val aliases = listOfNotNull(info?.optString("en_title"), info?.optString("ar_title"), video.title).filter { it.isNotBlank() }
                        val version = CinemanaEditions.label(aliases).takeIf { it != "نسخة Cinemana" } ?: video.version
                        val title = aliases.firstOrNull() ?: "Cinemana"
                        val duration = info?.let(CinemanaEditions::duration)
                        val rootId = info?.optString("rootSeries")?.takeIf { it != "0" && it.isNotEmpty() && it.all(Char::isDigit) } ?: video.rootId
                        // Bind subtitle files to the exact video edition, including downloads.
                        val subtitles = optional { client.subtitles(video.videoId, info).map { track ->
                            JSONObject().put("url", track.url).put("lang", track.language)
                                .put("name", "${track.label} • $version")
                        } }.orEmpty()
                        optional { client.streams(video.videoId).map { stream ->
                            val description = listOfNotNull(title, "$version • ${stream.quality} • ${stream.container}", duration?.let { "المدة: $it" }).joinToString("\n")
                            CinemanaQuality.height(stream.quality) to JSONObject()
                                .put("name", "Cinemana • ${stream.quality}").put("title", description).put("description", description)
                                .put("url", stream.url).put("subtitles", JSONArray(subtitles))
                                .put("behaviorHints", JSONObject().put("bingeGroup", "cinemana-$rootId-${stream.quality}"))
                        } }.orEmpty()
                    } }.flatMap { it.await() } }.sortedByDescending { it.first }.map { it.second }.distinctBy { it.optString("url") }
                    JSONObject().put("streams", JSONArray(streams))
                }
            }
            else -> error("Unknown Cinemana resource")
        }
        return result.toString()
    }

    fun manifest(): JSONObject {
        val types = listOf("movie", "series")
        val resources = JSONArray().put("catalog")
        resources.put(JSONObject().put("name", "meta").put("types", JSONArray(types)).put("idPrefixes", JSONArray(listOf("cinemana:", "cinemana-series:"))))
        for (resource in listOf("stream", "subtitles")) {
            resources.put(JSONObject().put("name", resource).put("types", JSONArray(types))
                .put("idPrefixes", JSONArray(listOf("cinemana:", "cinemana-series:", "tt", "imdb:", "tmdb:"))))
        }
        val catalogs = JSONArray()
        for (type in types) {
            catalogs.put(JSONObject().put("id", "cinemana_search_$type").put("type", type)
                .put("name", "Cinemana").put("extra", JSONArray().put(JSONObject().put("name", "search").put("isRequired", true))))
            for (category in CinemanaCatalogs.categories) {
                catalogs.put(JSONObject().put("id", category.id + if (type == "series") "_series" else "")
                    .put("type", type).put("name", "Cinemana • ${category.title}")
                    .put("extra", JSONArray().put(JSONObject().put("name", "skip"))))
            }
        }
        return JSONObject().put("id", "org.nuvio.cinemana.builtin").put("name", "Cinemana")
            .put("version", "0.7.2").put("description", "Built-in Cinemana movies and series")
            .put("types", JSONArray(types)).put("resources", resources).put("catalogs", catalogs)
    }

    private fun preview(entry: CinemanaClient.Entry, type: String): JSONObject =
        JSONObject().put("id", "${if (type == "series") "cinemana-series:" else "cinemana:"}${entry.id}").put("type", type).put("name", entry.title)
            .put("poster", entry.poster).put("releaseInfo", entry.year)

    private suspend fun metadata(id: String, type: String): JSONObject {
        val rawId = id.removePrefix("cinemana-series:").removePrefix("cinemana:").substringBefore(':')
        require((id.startsWith("cinemana:") || id.startsWith("cinemana-series:")) && rawId.all(Char::isDigit) && rawId.isNotEmpty())
        val record = client.info(rawId).also { if (!it.has("nb") && !it.has("id")) it.put("nb", rawId) }
        val entry = client.entry(record, type == "series") ?: error("Cinemana metadata unavailable")
        val meta = preview(entry.copy(id = rawId), type)
            .put("description", listOf("description", "en_description", "ar_description", "en_content", "ar_content")
                .firstNotNullOfOrNull { record.optString(it).takeIf { text -> text.isNotBlank() && text != "null" } }.orEmpty())
        val credentials = settings()
        val match = optional {
            CinemanaTmdbMatcher(credentials.apiKey, credentials.language, fetch)
                .match(entry.aliases, entry.year?.take(4)?.toIntOrNull(), type == "series")
        }
        val details = if (match != null) optional {
            tmdb("${if (type == "series") "tv" else "movie"}/${match.tmdbId}", "append_to_response=credits,external_ids")
        } else null
        if (match != null) {
            if (credentials.useBasicInfo) {
                meta.put("name", match.title)
                if (!match.overview.isNullOrBlank()) meta.put("description", match.overview)
            }
            if (credentials.useArtwork && match.poster != null) meta.put("poster", match.poster)
            meta.put("moviedb_id", match.tmdbId.toString())
            if (details != null) enrichDetails(meta, details, credentials)
        }
        val seasonCounts = linkedMapOf<Int, Int>()
        val seasons = details?.optJSONArray("seasons")
        if (seasons != null) for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val number = season.optInt("season_number")
            val count = season.optInt("episode_count")
            if (number > 0 && count > 0) seasonCounts[number] = count
        }
        if (type == "series") {
            val sourceEpisodes = optional { client.completeEpisodes(rawId, record) } ?: client.episodes(rawId)
            val episodes = CinemanaEpisodeMapping.normalizeContinuous(sourceEpisodes.map {
                CinemanaEpisodeMapping.EpisodeRef(it.videoId, it.season, it.episode, it.title)
            }, seasonCounts)
            val titles = mutableListOf<CinemanaEpisodeMapping.TmdbEpisode>()
            if (match != null && credentials.useEpisodes) for (season in episodes.map { it.season }.distinct()) {
                val data = optional { tmdb("tv/${match.tmdbId}/season/$season").optJSONArray("episodes") }
                if (data != null) for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    titles.add(CinemanaEpisodeMapping.TmdbEpisode(season, item.optInt("episode_number"), item.optString("name")))
                }
            }
            val merged = CinemanaEpisodeMapping.merge(episodes, emptyList(), titles)
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
        val data = tmdb("$kind/$tmdbId")
        val year = data.optString(if (series) "first_air_date" else "release_date").take(4).toIntOrNull()
        val variants = mutableListOf(data.optString(if (series) "name" else "title"), data.optString(if (series) "original_name" else "original_title"))
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
        // The search API does not necessarily fold Turkish accents or edition suffixes.
        // Keep the original request as well as the cleaned, ASCII search spelling.
        val queries = variants.filter { it.isNotBlank() && it != "null" }.flatMap {
            val cleaned = CinemanaEditions.identityTitle(it)
            listOf(it, cleaned, java.text.Normalizer.normalize(cleaned, java.text.Normalizer.Form.NFKD)
                .replace(Regex("[\\p{M}]"), "").replace('ı', 'i'))
        }.distinct().take(12)
        val entries = coroutineScope {
            queries.map { query -> async { optional { client.search(query, series, 5) }.orEmpty() } }
                .flatMap { it.await() }
        }.groupBy { it.id }.map { (_, versions) ->
            versions.first().copy(aliases = versions.flatMap { it.aliases }.distinct())
        }
        val matches = entries.filter { entry ->
            queries.any { query -> entry.aliases.any { normalize(query) == normalize(it) } } &&
                CinemanaIdentity.yearMatches(entry.year?.take(4)?.toIntOrNull(), year,
                    data.optString("last_air_date").take(4).toIntOrNull(), series)
        }
        if (!series) {
            if (matches.size > 1 && matches.any { !CinemanaEditions.explicit(it.aliases) }) return emptyList()
            return matches.map { ResolvedVideo(it.id, it.id, it.title, CinemanaEditions.label(it.aliases)) }
        }
        if (matches.isEmpty() || matches.size > 8) return emptyList()
        val counts = linkedMapOf<Int, Int>()
        val seasons = data.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until seasons.length()) {
            val item = seasons.optJSONObject(i) ?: continue
            counts[item.optInt("season_number")] = item.optInt("episode_count")
        }
        fun episodeIds(source: List<CinemanaClient.Episode>): List<String> {
            val episodes = CinemanaEpisodeMapping.normalizeContinuous(source.map {
                CinemanaEpisodeMapping.EpisodeRef(it.videoId, it.season, it.episode, it.title)
            }, counts)
            return episodes.filter { it.season == season && it.episode == episode }.map { it.videoId }.distinct()
        }
        // Try the season endpoint first. Full title-search expansion is only a
        // fallback; it must not delay an episode already available here.
        return coroutineScope {
            matches.map { entry -> async {
                optional {
                    val direct = episodeIds(client.episodes(entry.id))
                    val selected = direct.ifEmpty { optional { episodeIds(client.completeEpisodes(entry.id)) }.orEmpty() }
                    selected.map { ResolvedVideo(it, entry.id, entry.title, CinemanaEditions.label(entry.aliases)) }
                }.orEmpty()
            } }.flatMap { it.await() }.distinctBy { it.videoId }
        }
    }

    private suspend fun tmdb(path: String, extra: String = ""): JSONObject {
        val credentials = settings()
        require(credentials.apiKey.isNotBlank()) { "Enable TMDB in Nuvio settings to resolve external catalogs" }
        val url = "https://api.themoviedb.org/3/$path?api_key=${encode(credentials.apiKey)}&language=${encode(credentials.language)}&$extra"
        return JSONObject(fetch(url))
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun normalize(s: String) = CinemanaEpisodeMapping.normalizeTitle(CinemanaEditions.identityTitle(s))
    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}
