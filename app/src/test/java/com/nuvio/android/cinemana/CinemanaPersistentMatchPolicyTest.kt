package com.nuvio.android.cinemana

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemanaPersistentMatchPolicyTest {
    @Test fun completeEmptyResponsesWithImdbConfirmMissing() = runBlocking {
        val matcher = CinemanaTmdbMatcher("fixture", "ar-SA") { url ->
            if ("/find/" in url) """{"tv_results":[],"movie_results":[]}""" else """{"results":[]}"""
        }
        val result = matcher.resolve(listOf("Fixture"), 2022, false, "tt1234")
        assertNull(result.match)
        assertTrue(result.confirmedMissing)
        assertFalse(matcher.resolve(listOf("Fixture"), 2022, false, null).confirmedMissing)
    }

    @Test fun partialSearchFailureStaysRetryable() = runBlocking {
        val matcher = CinemanaTmdbMatcher("fixture", "ar-SA") { url ->
            when {
                "/find/" in url -> """{"tv_results":[],"movie_results":[]}"""
                "language=en-US" in url -> throw IOException("Temporary failure")
                else -> """{"results":[]}"""
            }
        }
        val result = matcher.resolve(listOf("Fixture"), 2022, false, "tt1234")
        assertNull(result.match)
        assertFalse(result.confirmedMissing)
    }
}
