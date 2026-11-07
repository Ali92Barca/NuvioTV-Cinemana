package com.nuvio.tv.data.cee

import com.nuvio.android.cinemana.CinemanaEpisodeMapping

internal fun mapSharedEpisodes(
    episodes: List<CeeClient.Episode>,
    seasonCounts: Map<Int, Int>,
    originalLanguage: String? = null
): List<CeeClient.Episode>? = CinemanaEpisodeMapping.sharedEpisodeLayout(
    episodes.map { CinemanaEpisodeMapping.EpisodeRef(it.videoId, it.season, it.episode, "") },
    seasonCounts,
    originalLanguage
)?.map { CeeClient.Episode(it.videoId, it.season, it.episode) }
