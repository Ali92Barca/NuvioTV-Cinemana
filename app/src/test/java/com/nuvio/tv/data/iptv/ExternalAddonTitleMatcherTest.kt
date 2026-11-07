package com.nuvio.tv.data.iptv

import org.junit.Assert.*
import org.junit.Test

class ExternalAddonTitleMatcherTest {
    private fun candidate(id: String = "xtream:42", name: String = "A Different World", year: Int? = 1987,
                          imdb: String? = null, tmdb: String? = null) =
        ExternalAddonTitleMatcher.Candidate(id, name, year, imdb, tmdb)
    private fun choose(vararg candidates: ExternalAddonTitleMatcher.Candidate) =
        ExternalAddonTitleMatcher.choose(listOf("A Different World"), 1987, candidates.toList())

    @Test fun exactTitlePreservesOriginalIdentity() {
        val decision = choose(candidate()) as ExternalAddonTitleMatcher.Decision.Matched
        assertEquals("xtream:42", decision.candidate.originalId)
    }
    @Test fun seventeenEditionsAreAmbiguous() {
        assertEquals(ExternalAddonTitleMatcher.Decision.Ambiguous,
            choose(*(1..17).map { candidate("xtream:$it") }.toTypedArray()))
    }
    @Test fun substringIsNotIdentity() {
        assertEquals(ExternalAddonTitleMatcher.Decision.NoMatch, choose(candidate(name = "A Different World Reunion")))
    }
    @Test fun wrongYearIsRejected() {
        assertEquals(ExternalAddonTitleMatcher.Decision.NoMatch, choose(candidate(year = 2025)))
    }
    @Test fun duplicateSearchRowsAreDeduplicated() {
        assertTrue(choose(candidate(), candidate()) is ExternalAddonTitleMatcher.Decision.Matched)
    }
    @Test fun conflictingRowsWithSameIdRemainAmbiguous() {
        assertEquals(ExternalAddonTitleMatcher.Decision.Ambiguous, choose(candidate(), candidate(year = null)))
    }
    @Test fun explicitImdbConflictRejectsExactTitle() {
        assertEquals(ExternalAddonTitleMatcher.Decision.NoMatch, ExternalAddonTitleMatcher.choose(
            listOf("A Different World"), 1987, listOf(candidate(imdb = "tt9999999")), expectedImdbId = "tt0092339"))
    }
    @Test fun explicitTmdbConflictRejectsMatchingImdb() {
        assertEquals(ExternalAddonTitleMatcher.Decision.NoMatch, ExternalAddonTitleMatcher.choose(
            listOf("A Different World"), 1987, listOf(candidate(imdb = "tt0092339", tmdb = "2")),
            expectedImdbId = "tt0092339", expectedTmdbId = "1"))
    }
    @Test fun externalIdDisambiguates() {
        val result = ExternalAddonTitleMatcher.choose(listOf("A Different World"), 1987,
            listOf(candidate("xtream:1"), candidate("xtream:2", tmdb = "1")), expectedTmdbId = "1")
        assertEquals("xtream:2", (result as ExternalAddonTitleMatcher.Decision.Matched).candidate.originalId)
    }
    @Test fun emptyIdsCannotMatch() {
        assertEquals(ExternalAddonTitleMatcher.Decision.NoMatch, choose(candidate(id = " ")))
    }
    @Test fun arabicAndUnicodeNormalize() {
        assertEquals(ExternalAddonTitleMatcher.normalize("أَلْعَالَم"), ExternalAddonTitleMatcher.normalize("العالم"))
        assertEquals("abc 123", ExternalAddonTitleMatcher.normalize("ＡＢＣ—123"))
    }
}
