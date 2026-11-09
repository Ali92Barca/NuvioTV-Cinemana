package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.VideoDto
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit public IDs only; invoked after installed metadata providers fail. */
@Singleton
class TmdbMetadataFallback @Inject constructor(private val api: TmdbApi) {
    suspend fun load(type: String, id: String): Meta? = withContext(Dispatchers.IO) {
        if (type !in setOf("series", "tv") || BuildConfig.TMDB_API_KEY.isBlank()) return@withContext null
        val number = Regex("tmdb:(?:(?:tv|series):)?([1-9][0-9]*)").matchEntire(id)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        withTimeoutOrNull(15_000) {
            try {
                val response=api.getTvDetails(number,BuildConfig.TMDB_API_KEY,"ar-SA")
                val details=response.body()?.takeIf { response.isSuccessful && it.id==number } ?: return@withTimeoutOrNull null
                val seasons=details.numberOfSeasons?.takeIf { it in 1..30 } ?: return@withTimeoutOrNull null
                val limit=Semaphore(2)
                val rows=coroutineScope {
                    (1..seasons).map { season -> async {
                        limit.withPermit {
                            val result=api.getTvSeasonDetails(number,season,BuildConfig.TMDB_API_KEY,"ar-SA")
                            val body=result.body()?.takeIf { result.isSuccessful && (it.seasonNumber==null || it.seasonNumber==season) }
                                ?: return@withPermit null
                            val episodes=body.episodes ?: return@withPermit null
                            val numbers=episodes.mapNotNull { it.episodeNumber }
                            if (numbers.size!=episodes.size || numbers.any { it<=0 } || numbers.distinct().size!=numbers.size) return@withPermit null
                            episodes.map { item -> VideoDto("$id:$season:${item.episodeNumber}",title=item.name,
                                released=item.airDate,season=season,episode=item.episodeNumber) }
                        }
                    } }.awaitAll()
                }
                if (rows.any { it==null }) return@withTimeoutOrNull null
                MetaDto(id,"series",details.name ?: details.originalName ?: return@withTimeoutOrNull null,
                    tmdbId=number.toString(),description=details.overview,released=details.firstAirDate,
                    releaseInfo=details.firstAirDate?.take(4),status=details.status,
                    poster=details.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    background=details.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    genres=details.genres?.map { it.name },language=details.originalLanguage,
                    videos=rows.filterNotNull().flatten()).toDomain("Episode")
            } catch(e:CancellationException) { throw e } catch(_:Exception) { null }
        }
    }
}
