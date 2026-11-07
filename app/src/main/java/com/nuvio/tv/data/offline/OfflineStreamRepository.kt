package com.nuvio.tv.data.offline

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Decorates the official repository; network providers and their caches are unchanged. */
@Singleton
class OfflineStreamRepository @Inject constructor(
    private val delegate: StreamRepositoryImpl,
    private val downloads: OfflineDownloads
) : StreamRepository by delegate {
    override fun getStreamsFromAllAddons(type: String, videoId: String, season: Int?, episode: Int?,
        forceRefresh: Boolean, parentMetaId: String?): Flow<NetworkResult<List<AddonStreams>>> {
        val identity = OfflineMediaIdentity(type, parentMetaId, videoId, season, episode)
        val remote = delegate.getStreamsFromAllAddons(type, videoId, season, episode, forceRefresh, parentMetaId)
            .onStart { emit(NetworkResult.Loading) }
        // Preserve the official flow's completion signal (used by autoplay selection).
        return flow {
          remote.collect { result ->
            if (result is NetworkResult.Success) runCatching { downloads.associateLegacy(identity, result.data.flatMap { it.streams }) }
            val current = runCatching { downloads.matching(identity) }.getOrDefault(emptyList())
            val group = if (current.isEmpty()) emptyList() else listOf(AddonStreams("Downloads", null, current.map { item ->
                Stream(name = "Downloads", title = item.title, description = "محفوظ على الجهاز • تشغيل بدون إنترنت",
                    url = "nuvio-offline://${item.key}", ytId = null, infoHash = null, fileIdx = null,
                    externalUrl = null, behaviorHints = null, addonName = "Downloads", addonLogo = null)
            }))
            emit(when {
                group.isEmpty() -> result
                result is NetworkResult.Success -> NetworkResult.Success(group + result.data)
                else -> NetworkResult.Success(group)
            })
          }
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }
}
