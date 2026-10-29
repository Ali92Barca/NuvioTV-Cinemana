package com.nuvio.android.cinemana

/** Nuvio sometimes supplies an episode's own ID separately from its show's
 * ID and season/episode. Cinemana needs the latter to resolve the right show. */
object CinemanaRequestIdentity {
    fun streamId(manifestId: String, type: String, videoId: String, parentId: String?, season: Int?, episode: Int?): String {
        if (manifestId != "org.nuvio.cinemana.builtin" || type != "series" ||
            videoId.startsWith("cinemana:") || videoId.startsWith("cinemana-series:")) return videoId
        if (season == null || episode == null || season < 1 || episode < 1) return videoId
        val show = parentId?.takeIf { it.startsWith("tmdb:") || it.startsWith("tt") || it.startsWith("imdb:") }
            ?: return videoId
        return "$show:$season:$episode"
    }
}
