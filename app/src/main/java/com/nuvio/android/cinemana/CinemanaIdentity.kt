package com.nuvio.android.cinemana

/** A season may carry a later year than the show's first broadcast. Only
 * accept that difference when TMDB's verified air-date interval explains it. */
object CinemanaIdentity {
    fun imdbId(value: String?): String? = Regex("tt[0-9]+", RegexOption.IGNORE_CASE)
        .find(value.orEmpty())?.value?.lowercase(java.util.Locale.ROOT)

    fun matches(expectedImdb: String?, candidateImdb: String?, titleMatches: Boolean,
        candidateYear: Int?, firstYear: Int?, lastYear: Int?, series: Boolean): Boolean {
        val expected = imdbId(expectedImdb)
        val candidate = imdbId(candidateImdb)
        // A verified ID outranks unreliable provider years (e.g. Malcolm is labeled 1920).
        // Conflicting verified IDs must never fall back to a coincidentally matching title.
        if (expected != null && candidate != null) return expected == candidate
        return titleMatches && yearMatches(candidateYear, firstYear, lastYear, series)
    }

    fun yearMatches(candidate: Int?, first: Int?, last: Int?, series: Boolean): Boolean {
        if (candidate == null || first == null) return true
        if (!series) return candidate == first
        if (candidate == first) return true
        return last != null && last >= first && candidate in first..last
    }
}
