package com.nuvio.tv.data.cee

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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts Cee catalog results into Nuvio-compatible metadata.
 * Cee identifiers remain separate from TMDB and Cinemana identifiers.
 */
class CeeResourceAdapter(
    private val client: CeeClient,
) {
    companion object {
        const val BASE_URL = "https://cee.nuvio.invalid"
        const val MOVIES_CATALOG = "cee-movies"
        const val SERIES_CATALOG = "cee-series"
    }

    suspend fun catalog(
        catalogId: String,
        page: Int = 0,
    ): CatalogResponseDto {
        val items = when (catalogId) {
            MOVIES_CATALOG -> client.latestMovies(page)
            SERIES_CATALOG -> client.latestSeries(page)
            else -> return CatalogResponseDto()
        }

        val type = if (catalogId == SERIES_CATALOG) "series" else "movie"

        return CatalogResponseDto(
            metas = (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.toPreview(type)
            }.distinctBy { it.id }
        )
    }

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

        return MetaPreviewDto(
            id = "cee:${if (type == "series") optString("rootSeries").takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: videoId else videoId}",
            type = type,
            name = title,
            poster = optString("imgObjUrl").takeIf { it.startsWith("https://") },
            description = optString("ar_content").takeIf { it.isNotBlank() }
                ?: optString("en_content").takeIf { it.isNotBlank() },
            releaseInfo = optString("year").takeIf { it.isNotBlank() },
            imdbId = imdbId
        )
    }

    suspend fun streams(videoId: String): StreamResponseDto = coroutineScope {
        // Subtitle files belong to this exact video/episode and travel with downloads too.
        val subtitleTask = async { loadSubtitles(videoId) }
        val sceneTask = async {
            try { com.nuvio.tv.domain.model.ProviderSceneSkip.fromSource("cee", videoId, client.videoInfo(videoId))?.toPayload() }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                kotlin.coroutines.coroutineContext[com.nuvio.android.cinemana.ProviderRequestState]?.complete?.set(false)
                null
            }
        }
        val items = client.streams(videoId)
        val subtitles = subtitleTask.await()
        val sceneData = sceneTask.await()

        val result = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("videoUrl")
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null

            val resolution = item.optString("resolution")
            val quality = Regex("[0-9]+").find(resolution)?.value?.toIntOrNull() ?: 0

            quality to StreamDto(
                name = "Cee",
                title = "Cee | $resolution",
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

    suspend fun meta(videoId: String, requestedType: String? = null): MetaResponseDto {
        val info = client.videoInfo(videoId)

        val title = info.optString("en_title")
            .takeIf { it.isNotBlank() }
            ?: info.optString("ar_title").ifBlank { "Cee $videoId" }

        val imdbId = Regex("tt[0-9]+")
            .find(info.optString("imdbUrlRef"))
            ?.value

        val type = if (requestedType == "series" || info.optString("kind") == "2") {
            "series"
        } else {
            "movie"
        }

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
                videos = if (type == "series") client.episodes(videoId).map { episode ->
                    VideoDto(id = "cee:$videoId:${episode.season}:${episode.episode}",
                        title = "Episode ${episode.episode}", season = episode.season,
                        episode = episode.episode, available = true)
                }.distinctBy { it.id } else null
            )
        )
    }
}
