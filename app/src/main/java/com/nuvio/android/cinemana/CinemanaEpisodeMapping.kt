package com.nuvio.android.cinemana

import java.text.Normalizer
import java.util.Locale

private val marksPattern = Regex("[\\p{M}ـ]")
private val alefPattern = Regex("[أإآٱ]")
private val nonTitlePattern = Regex("[^\\p{L}\\p{N}]+")
private val spacesPattern = Regex("\\s+")

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

    /** The title identity is already resolved. Equal total counts use source order. */
    fun matchEpisodeCount(episodes: List<EpisodeRef>, seasonCounts: Map<Int, Int>): List<EpisodeRef>? {
        val counts = seasonCounts.filterKeys { it > 0 }.toSortedMap()
        if (counts.isEmpty() || counts.values.any { it <= 0 } ||
            counts.values.sum() != episodes.size || episodes.isEmpty() ||
            episodes.map { it.videoId }.distinct().size != episodes.size ||
            episodes.map { it.season to it.episode }.distinct().size != episodes.size
        ) return null
        val official = counts.flatMap { (season, count) -> (1..count).map { season to it } }
        return episodes.sortedWith(compareBy(EpisodeRef::season, EpisodeRef::episode)).zip(official) { source, target ->
            source.copy(season = target.first, episode = target.second)
        }
    }

    fun sharedEpisodeLayout(
        episodes: List<EpisodeRef>,
        seasonCounts: Map<Int, Int>,
        originalLanguage: String?
    ): List<EpisodeRef>? = matchEpisodeCount(episodes, seasonCounts)
        ?: if (originalLanguage == "ar") normalizeContinuous(episodes, seasonCounts) else null

    /** Original Turkish audio, with work identity already verified: an available
     * contiguous prefix need not wait for every planned TMDB episode or its runtime. */
    fun translatedTurkishPrefix(episodes: List<EpisodeRef>, seasonCounts: Map<Int, Int>): List<EpisodeRef>? {
        val counts = seasonCounts.filterKeys { it > 0 }.toSortedMap()
        if (episodes.isEmpty() || counts.isEmpty() || counts.keys.toList() != (1..counts.size).toList() ||
            counts.values.any { it <= 0 } || episodes.map { it.videoId }.distinct().size != episodes.size ||
            episodes.any { !validId(it.videoId) } || episodes.size > counts.values.sum()) return null
        val normalized = normalizeContinuous(episodes, counts)
        val coordinates = normalized.map { it.season to it.episode }.sortedWith(compareBy({ it.first }, { it.second }))
        val expected = counts.flatMap { (season, count) -> (1..count).map { season to it } }.take(episodes.size)
        return normalized.takeIf { coordinates == expected }
    }

    /** Convert continuous anime numbering only when every source episode belongs
     * to season one and a complete TMDB season-count map explains the numbers. */
    fun normalizeContinuous(episodes: List<EpisodeRef>, seasonCounts: Map<Int, Int>): List<EpisodeRef> {
        if (episodes.isEmpty()) return episodes

        val counts = seasonCounts.filterKeys { it > 0 }.toSortedMap()
        if (counts.size < 2 ||
            counts.keys.toList() != (1..counts.size).toList() ||
            counts.values.any { it <= 0 } ||
            episodes.size > counts.values.sum()
        ) return episodes

        val numbers = episodes.map { it.episode }
        val absolute =
            numbers.toSet().size == numbers.size &&
            numbers.minOrNull() == 1 &&
            numbers.maxOrNull() == numbers.size

        val sourceGroups = episodes.groupBy { it.season }.toSortedMap()
        val sourceSeasons = sourceGroups.keys.toList()
        val orderedGroups = sourceGroups.values.toList()
        val officialBoundaries = counts.values
            .runningReduce { total, count -> total + count }
            .dropLast(1)
            .toSet()

        var cumulative = 0
        val resettingAligned =
            !absolute &&
            sourceSeasons.size >= 2 &&
            sourceSeasons == (1..sourceSeasons.size).toList() &&
            orderedGroups.first().size > counts.getValue(1) &&
            orderedGroups.all { group ->
                val groupNumbers = group.map { it.episode }
                groupNumbers.toSet().size == group.size &&
                groupNumbers.minOrNull() == 1 &&
                groupNumbers.maxOrNull() == group.size
            } &&
            orderedGroups.dropLast(1).all { group ->
                cumulative += group.size
                cumulative in officialBoundaries
            }

        if (!absolute && !resettingAligned) return episodes

        val positioned = if (absolute) {
            episodes.map { it to it.episode }
        } else {
            episodes.sortedWith(
                compareBy(EpisodeRef::season, EpisodeRef::episode, EpisodeRef::videoId)
            ).mapIndexed { index, entry -> entry to index + 1 }
        }

        if (positioned.maxOf { it.second } <= counts.getValue(1)) return episodes

        return positioned.map { (entry, absoluteNumber) ->
            var remaining = absoluteNumber
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

    /** Cinemana numeric duration: accept only plausible second values. */
    fun numericCinemanaSeconds(value: Double): Long? =
        value.takeIf { it.isFinite() && it in 600.0..18000.0 }?.toLong()
    /** A mismatch in any measured season rejects the external episode layout. */
    fun durationSamplesCompatible(
        samples: List<Long?>,
        officialMinutes: Int?
    ): Boolean? {
        if (samples.isEmpty()) return null
        val checks = samples.map { durationCompatible(it, officialMinutes) }
        if (checks.any { it == false }) return false
        if (checks.size < 3 || checks.any { it != true }) return null
        return true
    }
    /** null = missing duration, never confirmed compatible. */
    fun durationCompatible(sourceSeconds: Long?, officialMinutes: Int?): Boolean? {
        if (sourceSeconds == null || sourceSeconds !in 600L..18000L) return null
        if (officialMinutes == null || officialMinutes !in 10..300) return null
        return sourceSeconds.toDouble() / (officialMinutes * 60.0) in 0.70..1.30
    }

    fun rootlessSeriesKey(title: String, year: String?): String =
        normalizeTitle(title) + "|" + (year ?: "")

    fun normalizeTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKD)
        .replace(marksPattern, "")
        .lowercase(Locale.ROOT).replace('ı', 'i')
        .replace(alefPattern, "ا").replace('ى', 'ي').replace('ة', 'ه')
        .map { char ->
            when (char) {
                in '٠'..'٩' -> '0' + (char - '٠')
                in '۰'..'۹' -> '0' + (char - '۰')
                else -> char
            }
        }.joinToString("").replace("&", " and ")
        .replace(nonTitlePattern, " ").trim()
        .replace(spacesPattern, " ")

    private fun validId(id: String) = id.isNotBlank() && id.all(Char::isDigit)
}
