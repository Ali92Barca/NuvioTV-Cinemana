package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.Video

internal fun exactDownloadEpisode(videos: List<Video>, season: Int, episode: Int): Video? =
    videos.firstOrNull { it.season == season && it.episode == episode }
