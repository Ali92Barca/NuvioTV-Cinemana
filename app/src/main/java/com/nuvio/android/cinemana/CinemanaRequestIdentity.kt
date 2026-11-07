package com.nuvio.android.cinemana

/** Nuvio sometimes supplies an episode's own ID separately from its show's
 * ID and season/episode. Cinemana needs the latter to resolve the right show. */
object CinemanaRequestIdentity {
    fun streamId(manifestId: String, type: String, videoId: String, parentId: String?, season: Int?, episode: Int?): String {
        // CEE: use the series identity when Nuvio supplies a separate episode ID.
        // Preserve already valid CEE/IMDb/TMDB episode routes.
        if (manifestId == "org.nuvio.cee" && type == "series" &&
            season != null && episode != null && season > 0 && episode > 0) {
            val validEpisode = Regex(
                "(?:cee:[0-9]+|(?:imdb:)?tt[0-9]+|tmdb:(?:(?:tv|series):)?[1-9][0-9]*):[1-9][0-9]*:[1-9][0-9]*"
            )
            if (!validEpisode.matches(videoId)) {
                val parent = parentId?.takeIf {
                    it.matches(Regex("(?:imdb:)?tt[0-9]+|tmdb:(?:(?:tv|series):)?[1-9][0-9]*|cinemana-series:[0-9]+|cee:[0-9]+"))
                }
                if (parent != null) return "$parent:$season:$episode"
            }
            return videoId
        }

        if (manifestId != "org.nuvio.cinemana.builtin" || type != "series" ||
            videoId.startsWith("cinemana:") || videoId.startsWith("cinemana-series:")) return videoId
        if (season == null || episode == null || season < 1 || episode < 1) return videoId
        val show = parentId?.takeIf { it.startsWith("tmdb:") || it.startsWith("tt") || it.startsWith("imdb:") }
            ?: return videoId
        return "$show:$season:$episode"
    }
}
