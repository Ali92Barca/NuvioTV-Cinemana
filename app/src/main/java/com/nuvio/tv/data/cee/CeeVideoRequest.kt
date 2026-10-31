package com.nuvio.tv.data.cee

/** Series roots never resolve to playable videos without an explicit season and episode. */
internal data class CeeVideoRequest(
    val nativeId: String?, val externalId: String?, val season: Int?, val episode: Int?,
) {
    companion object {
        fun parse(id: String, series: Boolean): CeeVideoRequest? {
            val tokens = id.split(':')
            val base = if (series) {
                if (tokens.size < 3) return null
                tokens.dropLast(2).joinToString(":")
            } else id
            val season = if (series) tokens[tokens.size - 2].toIntOrNull()?.takeIf { it > 0 } ?: return null else null
            val episode = if (series) tokens.last().toIntOrNull()?.takeIf { it > 0 } ?: return null else null
            val native = Regex("cee:([0-9]+)").matchEntire(base)?.groupValues?.get(1)
            val kind = if (series) "(?:tv:|series:)?" else "(?:movie:)?"
            val external = base.takeIf { it.matches(Regex("(?:imdb:)?tt[0-9]+|tmdb:$kind[1-9][0-9]*")) }
            if (native == null && external == null) return null
            return CeeVideoRequest(native, external, season, episode)
        }
    }
}
