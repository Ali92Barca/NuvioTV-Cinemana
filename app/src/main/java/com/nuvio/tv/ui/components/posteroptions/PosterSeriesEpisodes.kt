package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.cee.CeeAddonApi
import com.nuvio.tv.data.cinemana.CinemanaAddonApi
import com.nuvio.tv.data.repository.nativePlaybackId
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.watchableEpisodes
import kotlinx.coroutines.flow.Flow
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.flow.first

/** Keep native episode numbering until the watch repository projects verified aliases. */
internal suspend fun MetaRepository.fetchPosterSeriesEpisodes(
    type: String,
    id: String,
    originalId: String = id,
    sourceAddonBaseUrl: String? = null,
    canonicalFallback: suspend () -> List<Video> = { emptyList() },
    onNativeMeta: suspend (Meta) -> Unit = {}
): List<Video> {
    if (nativePlaybackId(id)) {
        val baseUrl = if (id.startsWith("cee:")) CeeAddonApi.BASE_URL else CinemanaAddonApi.BASE_URL
        val meta = getMeta(baseUrl, "series", id).loadedMeta()?.takeIf { it.id == id }
        val episodes = meta?.watchableEpisodes().orEmpty()
        check(episodes.isNotEmpty()) { "No source episodes found for $id" }
        meta?.let { onNativeMeta(it) }
        return episodes
    }

    if (!sourceAddonBaseUrl.isNullOrBlank()) {
        val episodes = getMeta(sourceAddonBaseUrl, type, originalId).loadedMeta()?.watchableEpisodes().orEmpty()
        if (episodes.isNotEmpty()) return episodes
    }
    val primary = getMetaFromPrimaryAddon(type, id).loadedMeta()?.watchableEpisodes().orEmpty()
    if (primary.isNotEmpty()) return primary
    val alternate = getMetaFromAllAddons(type, id).loadedMeta()?.watchableEpisodes().orEmpty()
    if (alternate.isNotEmpty()) return alternate
    return canonicalFallback().watchableEpisodes()
}

private suspend fun Flow<NetworkResult<Meta>>.loadedMeta(): Meta? =
    (first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
