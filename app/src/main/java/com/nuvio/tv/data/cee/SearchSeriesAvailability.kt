package com.nuvio.tv.data.cee

import com.nuvio.android.cinemana.CinemanaEditions
import com.nuvio.tv.domain.model.MetaPreview
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.nuvio.tv.core.network.awaitProviderStatus

/** Confirms an unusable Cinemana result against a visible, playable Cee series. */
@Singleton
class SearchSeriesAvailability @Inject constructor(http: OkHttpClient) {
    private val transport = http.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
    private val cee = CeeClient(transport)

    internal fun sameSeries(cinemana: MetaPreview, ceeItem: MetaPreview): Boolean {
        val nativeId = cinemana.id.removePrefix("cinemana-series:")
        val ceeId = ceeItem.id.removePrefix("cee:")
        if (!cinemana.id.startsWith("cinemana-series:") ||
            !ceeItem.id.startsWith("cee:") ||
            !nativeId.matches(Regex("[0-9]+")) || nativeId != ceeId
        ) return false
        val year = cinemana.releaseInfo?.take(4)?.toIntOrNull() ?: return false
        if (year != ceeItem.releaseInfo?.take(4)?.toIntOrNull()) return false
        if (CinemanaEditions.label(listOf(cinemana.name)) != CinemanaEditions.label(listOf(ceeItem.name))) return false
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .lowercase(Locale.ROOT).replace(Regex("[\\p{M}\\s\\p{Punct}]+"), "")
        return normalize(cinemana.name).takeIf { it.isNotEmpty() } == normalize(ceeItem.name)
    }

    suspend fun shouldHide(cinemana: MetaPreview, alternate: MetaPreview): Boolean = withContext(Dispatchers.IO) {
        if (!sameSeries(cinemana, alternate)) return@withContext false
        try {
            val id = cinemana.id.removePrefix("cinemana-series:")
            val episodes = cee.episodes(id)
            if (episodes.size < 2 || episodes.map { it.season to it.episode }.distinct().size != episodes.size) {
                return@withContext false
            }
            val samples = listOf(episodes.first(), episodes.last()).distinctBy { it.videoId }
            if (!samples.all { episode ->
                    val streams = cee.streams(episode.videoId)
                    (0 until streams.length()).any { index ->
                        val url = streams.optJSONObject(index)?.optString("videoUrl").orEmpty()
                        url.startsWith("http://") || url.startsWith("https://")
                    }
                }) return@withContext false

            val base = "https://cinemana.shabakaty.com/api/android"
            listOf("allVideoInfo", "videoSeason").all { route ->
                val request = Request.Builder().url("$base/$route/id/$id").get().build()
                transport.newCall(request).awaitProviderStatus() in setOf(400, 404, 410)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
