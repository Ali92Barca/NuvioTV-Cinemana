package com.nuvio.android.cinemana

import java.text.Normalizer
import java.util.Locale

/**
 * Pure normalization for merging Cinemana series/episodes with TMDB metadata.
 * Stream selection must always resolve to [videoId], never a TMDB ID.
 */
object CinemanaEpisodeMapping {
    data class EpisodeRef(
        val videoId: String,
        val season: Int,
        val episode: Int,
        val title: String
    )
    data class TmdbEpisode(val season: Int, val episode: Int, val title: String)

    fun merge(
        seasonEndpoint: List<EpisodeRef>,
        searchFallback: List<EpisodeRef>,
        tmdbEpisodes: List<TmdbEpisode> = emptyList()
    ): List<EpisodeRef> {
        val byVideoId = linkedMapOf<String, EpisodeRef>()
        for (entry in seasonEndpoint + searchFallback) {
            if (!validId(entry.videoId) || entry.season < 1 || entry.episode < 1) continue
            byVideoId.putIfAbsent(entry.videoId, entry)
        }
        val officialTitles = tmdbEpisodes.associateBy { it.season to it.episode }
        return byVideoId.values.map { item ->
            val official = officialTitles[item.season to item.episode]
            if (official != null && official.title.isNotBlank())
                item.copy(title = official.title)
            else item
        }.sortedWith(compareBy(EpisodeRef::season, EpisodeRef::episode, EpisodeRef::videoId))
    }

    /** Convert continuous anime numbering only when every source episode belongs
     * to season one and a complete TMDB season-count map explains the numbers. */
    fun normalizeContinuous(episodes: List<EpisodeRef>, seasonCounts: Map<Int, Int>): List<EpisodeRef> {
        if (episodes.isEmpty() || episodes.any { it.season != 1 }) return episodes
        val counts = seasonCounts.filterKeys { it > 0 }.toSortedMap()
        if (counts.size < 2 || counts.keys.toList() != (1..counts.size).toList() || counts.values.any { it <= 0 }) return episodes
        val largest = episodes.maxOf { it.episode }
        if (largest <= counts.getValue(1) || largest > counts.values.sum()) return episodes
        return episodes.map { entry ->
            var remaining = entry.episode
            var result = entry
            for ((season, count) in counts) {
                if (remaining <= count) {
                    result = entry.copy(season = season, episode = remaining)
                    break
                }
                remaining -= count
            }
            result
        }
    }

    fun rootlessSeriesKey(title: String, year: String?): String =
        normalizeTitle(title) + "|" + (year ?: "")

    fun normalizeTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKD)
        .replace(Regex("[\\p{M}ـ]"), "")
        .lowercase(Locale.ROOT).replace('ı', 'i')
        .replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي').replace('ة', 'ه')
        .map { char ->
            when (char) {
                in '٠'..'٩' -> '0' + (char - '٠')
                in '۰'..'۹' -> '0' + (char - '۰')
                else -> char
            }
        }.joinToString("").replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .replace(Regex("\\s+"), " ")

    private fun validId(id: String) = id.isNotBlank() && id.all(Char::isDigit)
}
