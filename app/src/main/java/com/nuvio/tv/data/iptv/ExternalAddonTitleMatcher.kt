package com.nuvio.tv.data.iptv

import java.text.Normalizer
import java.util.Locale

private val arabicMarksPattern = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
private val nonTitlePattern = Regex("[^\\p{L}\\p{N}]+")
private val spacesPattern = Regex("\\s+")

/**
 * Pure, credential-free matcher for catalog search results returned by external Stremio addons.
 *
 * A catalog search result is NOT a verified playback identity. Callers may use an accepted
 * result to request meta/streams, but must validate the resulting season/episode before
 * sharing watch progress with the canonical title.
 *
 * Intentionally avoids selecting the first of multiple identically named IPTV editions.
 */
internal object ExternalAddonTitleMatcher {
    private const val arabicLetters = "\\u0600-\\u06FF\\u0750-\\u077F\\u0870-\\u08FF"
    private const val latinLetters = "a-z\\u00C0-\\u024F\\u1E00-\\u1EFF"
    private const val searchLatinLetters = "a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF"
    private val searchAliasPatterns = listOf(arabicLetters, searchLatinLetters).map { script ->
        Regex("[$script\\p{N}]+(?:\\s+[$script\\p{N}]+)*")
    }
    private val arabicTitlePattern = Regex("[$arabicLetters\\p{N}]+(?:\\s+[$arabicLetters\\p{N}]+)*")
    private val latinTitlePattern = Regex("[$latinLetters\\p{N}]+(?:\\s+[$latinLetters\\p{N}]+)*")
    private val arabicLetterPattern = Regex("[$arabicLetters]")
    private val latinLetterPattern = Regex("[$latinLetters]")

    data class Candidate(
        val originalId: String,
        val name: String,
        val year: Int? = null,
        val imdbId: String? = null,
        val tmdbId: String? = null
    )

    sealed interface Decision {
        data class Matched(val candidate: Candidate, val score: Int) : Decision
        data object Ambiguous : Decision
        data object NoMatch : Decision
    }

    fun choose(
        titles: List<String>,
        year: Int?,
        candidates: List<Candidate>,
        expectedImdbId: String? = null,
        expectedTmdbId: String? = null
    ): Decision {
        val normalizedNames = titles.map(::normalize).filter(String::isNotEmpty).toSet()
        val expectedVariants = titles.flatMap(::titleVariants).toSet()
        if (normalizedNames.isEmpty()) return Decision.NoMatch

        val scored = candidates.asSequence()
            .filter { it.originalId.isNotBlank() }
            .distinct()
            .mapNotNull { candidate ->
                // Explicit identity conflicts must never be overridden by a title score.
                if (!expectedImdbId.isNullOrBlank() && !candidate.imdbId.isNullOrBlank() &&
                    !expectedImdbId.equals(candidate.imdbId, ignoreCase = true)) return@mapNotNull null
                if (!expectedTmdbId.isNullOrBlank() && !candidate.tmdbId.isNullOrBlank() &&
                    expectedTmdbId != candidate.tmdbId) return@mapNotNull null
                val candidateName = normalize(candidate.name)
                if (candidateName.isEmpty()) return@mapNotNull null
                val imdbExact = !expectedImdbId.isNullOrBlank() &&
                    expectedImdbId.equals(candidate.imdbId, ignoreCase = true)
                val tmdbExact = !expectedTmdbId.isNullOrBlank() &&
                    expectedTmdbId == candidate.tmdbId
                val nameExact = titleVariants(candidate.name).any { it in expectedVariants }
                // Do not accept substring-only matches: same-name shows across regions
                // and editions are common, and often share a release year.
                if (!nameExact && !imdbExact && !tmdbExact) return@mapNotNull null
                if (year != null && candidate.year != null &&
                    kotlin.math.abs(year - candidate.year) > 1 &&
                    !imdbExact && !tmdbExact) return@mapNotNull null
                val score = (if (nameExact) 100 else 0) +
                    (if (year != null && candidate.year == year) 20 else 0) +
                    (if (imdbExact) 200 else 0) +
                    (if (tmdbExact) 200 else 0)
                candidate to score
            }
            .sortedByDescending { it.second }
            .toList()

        val best = scored.firstOrNull() ?: return Decision.NoMatch
        val runnerUp = scored.getOrNull(1)
        // A confident external ID can disambiguate otherwise identical titles;
        // name + year alone cannot distinguish duplicated IPTV editions.
        if (runnerUp != null && best.second - runnerUp.second < 100) {
            return Decision.Ambiguous
        }
        return Decision.Matched(best.first, best.second)
    }

    internal fun normalize(value: String): String {
        val cleaned = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
            .replace(arabicMarksPattern, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .replace(nonTitlePattern, " ")
            .trim()
        return cleaned.replace(spacesPattern, " ")
    }

    /** A bilingual label can carry two complete titles; never use arbitrary substrings. */
    internal fun searchTitles(value: String): List<String> {
        // Search services do not necessarily normalize Arabic letters as we do. Keep the
        // original spelling (مناعة, not مناعه) and bilingual aliases in outbound queries.
        val aliases = listOf(value.trim()) + searchAliasPatterns.flatMap { pattern ->
            pattern.findAll(value).map { it.value }.filter { it.length >= 4 }.toList()
        }
        return aliases.filter(String::isNotBlank).distinctBy(::normalize)
    }

    internal fun titleVariants(value: String): Set<String> {
        val normalized = normalize(value)
        // Android uses ICU regex, which rejects the JVM-only IsArabic/IsLatin aliases.
        // Explicit script ranges work on both Android and the desktop test runtime.
        val arabic = arabicTitlePattern.findAll(normalized)
            .map { it.value }.filter { it.length >= 4 && arabicLetterPattern.containsMatchIn(it) }.toList()
        val latin = latinTitlePattern.findAll(normalized)
            .map { it.value }.filter { it.length >= 4 && latinLetterPattern.containsMatchIn(it) }.toList()
        return (listOf(normalized) + arabic + latin).filter(String::isNotBlank).toSet()
    }
}
